package com.aegisnav.app.scan

import com.aegisnav.app.AppLifecycleTracker
import com.aegisnav.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.PriorityQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 3.4 — Scan Orchestration with Priority Queuing.
 *
 * Manages BLE scan start-call budget to respect Android's 5-scan-per-30-second
 * limit. Calls in excess of that limit are silently rejected by the OS (returns
 * SCAN_FAILED_APPLICATION_REGISTRATION_FAILED on API 29+).
 *
 * Priority levels (highest first): HIGH → MEDIUM → LOW.
 *  - HIGH  : active foreground scan (ScanService continuous scan, burst mode)
 *  - MEDIUM: opportunistic / PendingIntent-based scan start
 *  - LOW   : WorkManager background worker scans
 *
 * Usage:
 *   scanOrchestrator.requestScan(ScanPriority.HIGH, "foreground") {
 *       leScanner?.startScan(callback)
 *   }
 *
 * Thread-safe. All internal state is guarded by [lock].
 */
@Singleton
class ScanOrchestrator @Inject constructor() {

    // ── Public API ────────────────────────────────────────────────────────────

    enum class ScanPriority { HIGH, MEDIUM, LOW }

    companion object {
        private const val TAG = "ScanOrchestrator"

        /** Android rejects > 5 startScan() calls in any 30-second rolling window. */
        const val MAX_SCANS_PER_WINDOW = 5
        const val WINDOW_MS = 30_000L
    }

    // ── Internal state ────────────────────────────────────────────────────────

    private data class PendingScan(
        val priority: ScanPriority,
        val tag: String,
        val execute: () -> Unit,
        val enqueuedAt: Long = System.currentTimeMillis()
    )

    private val lock = Any()

    /** Timestamps (ms) of recent startScan() calls within the rolling window. */
    private val recentStarts = ArrayDeque<Long>()

    /**
     * Pending scans sorted by priority (HIGH first). When priorities are equal,
     * earlier enqueuedAt is favoured (FIFO within a priority band).
     */
    private val pendingQueue: PriorityQueue<PendingScan> = PriorityQueue(
        compareBy<PendingScan> { it.priority.ordinal }.thenBy { it.enqueuedAt }
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** True while a retry coroutine is already scheduled to drain the queue. */
    private var retryScheduled = false

    // ── Public methods ────────────────────────────────────────────────────────

    /**
     * Returns true if scans should be completely suppressed right now.
     *
     * Conditions:
     *  - App was killed (swipe from recents) → always suppress
     *  - App has been backgrounded for >= 10 minutes → suppress LOW-priority scans;
     *    HIGH/MEDIUM are still allowed (e.g. WorkManager already constrained by battery/network)
     */
    fun isScanSuppressed(priority: ScanPriority): Boolean {
        if (AppLifecycleTracker.isKilled) return true
        // After 10-min background: suppress LOW-priority scans only
        if (AppLifecycleTracker.isBackgroundThrottled && priority == ScanPriority.LOW) {
            AppLog.d(TAG, "Background throttle active — suppressing LOW-priority scan")
            return true
        }
        return false
    }

    /**
     * Request a BLE scan start.
     *
     * If budget is available, the highest-priority pending request (including
     * this one) is executed immediately. Otherwise the request is queued and
     * will execute automatically once the 30-second window allows.
     *
     * @param priority Determines execution order when multiple requests compete.
     * @param tag      Human-readable label for log messages.
     * @param execute  Lambda that calls [android.bluetooth.le.BluetoothLeScanner.startScan].
     */
    fun requestScan(priority: ScanPriority, tag: String, execute: () -> Unit) {
        // Check background/kill suppression before touching the queue
        if (isScanSuppressed(priority)) {
            AppLog.d(TAG, "Scan [$tag] suppressed (killed=${AppLifecycleTracker.isKilled} " +
                    "throttled=${AppLifecycleTracker.isBackgroundThrottled})")
            return
        }
        val scanToRun: PendingScan?
        synchronized(lock) {
            purgeWindow()
            val incoming = PendingScan(priority, tag, execute)
            pendingQueue.offer(incoming)

            scanToRun = if (recentStarts.size < MAX_SCANS_PER_WINDOW) {
                pendingQueue.poll()
            } else {
                AppLog.d(TAG, "Budget exhausted (${recentStarts.size}/$MAX_SCANS_PER_WINDOW). " +
                        "Queued [$tag] priority=$priority; queue depth=${pendingQueue.size}")
                scheduleRetryIfNeeded()
                null
            }

            if (scanToRun != null) {
                recentStarts.addLast(System.currentTimeMillis())
            }
        }
        scanToRun?.let { runScan(it) }
    }

    /** How many startScan() calls remain in the current 30-second window. */
    fun availableBudget(): Int = synchronized(lock) {
        purgeWindow()
        MAX_SCANS_PER_WINDOW - recentStarts.size
    }

    /** Number of scan requests waiting in the priority queue. */
    fun pendingCount(): Int = synchronized(lock) { pendingQueue.size }

    /**
     * Returns a snapshot of recent scan start timestamps (ms) for the current
     * window — useful for debugging and tests.
     */
    fun recentScanTimestamps(): List<Long> = synchronized(lock) {
        purgeWindow()
        recentStarts.toList()
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /** Remove scan timestamps older than [WINDOW_MS]. Must be called under [lock]. */
    private fun purgeWindow() {
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        while (recentStarts.isNotEmpty() && recentStarts.first() < cutoff) {
            recentStarts.removeFirst()
        }
    }

    /** Launch a retry coroutine only if one isn't already running. Must be called under [lock]. */
    private fun scheduleRetryIfNeeded() {
        if (retryScheduled) return
        retryScheduled = true
        val oldestStart = recentStarts.firstOrNull()
        scope.launch {
            if (oldestStart != null) {
                val waitMs = (oldestStart + WINDOW_MS) - System.currentTimeMillis() + 200L
                if (waitMs > 0) delay(waitMs)
            } else {
                delay(WINDOW_MS)
            }
            drainQueue()
        }
    }

    private fun drainQueue() {
        val toRun = mutableListOf<PendingScan>()
        synchronized(lock) {
            purgeWindow()
            while (pendingQueue.isNotEmpty() && recentStarts.size < MAX_SCANS_PER_WINDOW) {
                val next = pendingQueue.poll() ?: break
                recentStarts.addLast(System.currentTimeMillis())
                toRun.add(next)
            }
            retryScheduled = false
            if (pendingQueue.isNotEmpty()) {
                scheduleRetryIfNeeded()
            }
        }
        toRun.forEach { runScan(it) }
    }

    private fun runScan(scan: PendingScan) {
        AppLog.i(TAG, "Executing scan [${scan.tag}] priority=${scan.priority} " +
                "remaining budget ~${availableBudget()}/$MAX_SCANS_PER_WINDOW")
        try {
            scan.execute()
        } catch (e: Exception) {
            AppLog.e(TAG, "Scan execution failed [${scan.tag}]: ${e.message}", e)
        }
    }
}
