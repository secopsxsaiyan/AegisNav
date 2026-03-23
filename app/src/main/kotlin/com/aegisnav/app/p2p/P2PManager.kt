package com.aegisnav.app.p2p

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aegisnav.app.security.SecureDataStore
import com.aegisnav.app.di.ApplicationScope
import androidx.datastore.preferences.core.emptyPreferences
import com.aegisnav.app.util.AppLog
import com.google.gson.Gson
import com.aegisnav.app.correlation.P2PReportBundle
import com.aegisnav.app.flock.FlockSighting
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import androidx.datastore.preferences.core.edit
import okhttp3.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class P2PManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope
) {
    companion object {
        const val PREFS_NAME          = "p2p_prefs"
        const val PREF_NODE_ID        = "node_id"
        const val PREF_NODE_CREATED   = "node_id_created_at"
        const val PREF_USE_DEFAULT    = "use_default_relay"
        const val PREF_CUSTOM_NODE_1  = "custom_node_1"
        const val PREF_CUSTOM_NODE_2  = "custom_node_2"
        const val NODE_ID_ROTATION_MS = 24L * 60 * 60 * 1000
        const val RECONNECT_DELAY_MS  = 10_000L
        const val MAX_QUEUE_SIZE      = 50
        const val MAX_HOPS            = 3
    }

    private val gson      = Gson()
    // All prefs now encrypted via SecureDataStore (replaces EncryptedSharedPreferences)
    private val dataStore: DataStore<Preferences> by lazy { SecureDataStore.get(context, PREFS_NAME) }

    /**
     * Pattern A: cache all preferences in a hot StateFlow so reads are non-blocking.
     * SharingStarted.Eagerly starts collecting immediately when P2PManager is injected.
     * `prefsFlow.value[key]` is instant — no IO, no blocking.
     */
    private val prefsFlow: StateFlow<Preferences> by lazy {
        dataStore.data.stateIn(scope, SharingStarted.Eagerly, emptyPreferences())
    }

    /** Cached offline_mode from app_prefs — eliminates runBlocking reads on main thread. */
    private val _offlineMode = MutableStateFlow(true) // default offline until prefs load
    init {
        scope.launch {
            SecureDataStore.get(context, "app_prefs").data.collect { prefs ->
                _offlineMode.value = prefs[booleanPreferencesKey("offline_mode")] ?: true
            }
        }
    }

    private val nodeId: String get() = rotatedNodeId()

    // Active WebSocket connections - one per enabled relay
    private val connections = ConcurrentHashMap<String, WebSocket>()

    // Reconnect job tracking to prevent coroutine leaks
    private val reconnectJobs = ConcurrentHashMap<String, Job>()
    private val reconnectBackoffs = ConcurrentHashMap<String, Long>()

    // Offline queue - broadcasts when connection restored (thread-safe)
    private val pendingQueue = ConcurrentLinkedQueue<String>()

    private val _states = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, ConnectionState>> = _states

    // Convenience: overall state = CONNECTED if any node is connected
    private val _overallState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val overallState: StateFlow<ConnectionState> = _overallState

    private val _incomingReports = MutableStateFlow<List<IncomingReport>>(emptyList())
    val incomingReports: StateFlow<List<IncomingReport>> = _incomingReports

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    // ── Configuration ──────────────────────────────────────────────────────

    fun configure(useDefault: Boolean, customNodes: List<String>) {
        // Pattern A: fire-and-forget write — the StateFlow will reflect the update immediately.
        scope.launch {
            dataStore.edit { prefs ->
                prefs[booleanPreferencesKey(PREF_USE_DEFAULT)] = useDefault
                prefs[stringPreferencesKey(PREF_CUSTOM_NODE_1)] = customNodes.getOrNull(0) ?: ""
                prefs[stringPreferencesKey(PREF_CUSTOM_NODE_2)] = customNodes.getOrNull(1) ?: ""
            }
        }
    }

    fun activeRelayUrls(): List<String> {
        // Pattern A: read from hot StateFlow — non-blocking, instant.
        val snapshot = prefsFlow.value
        val urls = mutableListOf<String>()
        if (snapshot[booleanPreferencesKey(PREF_USE_DEFAULT)] != false) {
            urls.add(DEFAULT_RELAY_URL)
            urls.add(DEFAULT_RELAY_URL_2)
        }
        val custom1 = snapshot[stringPreferencesKey(PREF_CUSTOM_NODE_1)] ?: ""
        val custom2 = snapshot[stringPreferencesKey(PREF_CUSTOM_NODE_2)] ?: ""
        custom1.takeIf { it.isNotBlank() }?.let { urls.add(it) }
        custom2.takeIf { it.isNotBlank() }?.let { urls.add(it) }
        return urls.filter { it.startsWith("wss://") }
    }

    // ── Connection ─────────────────────────────────────────────────────────

    fun connect() {
        if (_offlineMode.value) {
            AppLog.i("P2P", "Offline mode ON - skipping relay connections")
            return
        }
        val urls = activeRelayUrls()
        if (urls.isEmpty()) {
            AppLog.i("P2P", "No relay URLs configured - running offline")
            return
        }
        urls.forEach { url -> connectTo(url) }
    }

    private fun connectTo(url: String) {
        if (connections[url]?.let { true } == true) return
        val request = Request.Builder().url(url).build()
        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                updateState(url, ConnectionState.CONNECTED)
                reconnectBackoffs.remove(url)
                AppLog.i("P2P", "Connected to $url as $nodeId")
                ws.send(gson.toJson(mapOf("type" to "HELLO", "nodeId" to nodeId, "version" to "1")))
                flushQueue(ws)
            }
            override fun onMessage(ws: WebSocket, text: String) { handleIncoming(text) }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                updateState(url, ConnectionState.DISCONNECTED)
                connections.remove(url)
                AppLog.w("P2P", "Failed $url: ${t.message}")
                val backoff = (reconnectBackoffs[url] ?: RECONNECT_DELAY_MS).coerceAtMost(60_000L)
                reconnectBackoffs[url] = (backoff * 2).coerceAtMost(60_000L)
                reconnectJobs[url]?.cancel()
                reconnectJobs[url] = scope.launch { delay(backoff); connectTo(url) }
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                updateState(url, ConnectionState.DISCONNECTED)
                connections.remove(url)
                if (code != 1000) {
                    reconnectJobs[url]?.cancel()
                    val backoff = reconnectBackoffs.getOrDefault(url, RECONNECT_DELAY_MS)
                    // Update backoff BEFORE launching coroutine so concurrent onClosed/onFailure
                    // calls read the already-doubled value rather than the stale one.
                    reconnectBackoffs[url] = (backoff * 2).coerceAtMost(60_000L)
                    reconnectJobs[url] = scope.launch {
                        delay(backoff)
                        connectTo(url)
                    }
                }
            }
        })
        connections[url] = ws
        updateState(url, ConnectionState.CONNECTING)
    }

    fun disconnect() {
        connections.values.forEach { it.close(1000, "disconnect") }
        connections.clear()
        _states.value = emptyMap()
    }

    // ── Broadcast ──────────────────────────────────────────────────────────

    fun broadcastReport(bundle: P2PReportBundle) {
        val payload = gson.toJson(mapOf(
            "type"           to "REPORT",
            "nodeId"         to nodeId,
            "hops"           to 0,
            "maxHops"        to MAX_HOPS,
            "timestamp"      to bundle.timestamp,
            "report"         to mapOf(
                "type"        to bundle.report.type,
                "subtype"     to (bundle.report.subtype ?: ""),
                "lat"         to bundle.report.latitude,
                "lon"         to bundle.report.longitude,
                "description" to bundle.report.description,
                "threatLevel" to bundle.threatLevel.name
            ),
            "correlatedCount" to bundle.correlatedLogs.size,
            "hasTracker"      to bundle.correlatedLogs.any { it.isTracker }
        ))
        val sent = connections.values.filter { it.send(payload) }.size
        if (sent == 0) {
            // Queue for when reconnected
            if (pendingQueue.size >= MAX_QUEUE_SIZE) pendingQueue.poll()
            pendingQueue.offer(payload)
            AppLog.w("P2P", "No connections - queued report (${pendingQueue.size}/$MAX_QUEUE_SIZE)")
        } else {
            AppLog.i("P2P", "Broadcast to $sent relay(s), threat=${bundle.threatLevel}")
        }
    }

    /**
     * Broadcast a FlockSighting to P2P peers.
     * Only sends if P2P is configured (at least one relay URL is set).
     * Serializes to JSON and wraps in the standard P2P message envelope.
     * Queues offline if no connections are currently active.
     *
     * Call this ONLY after checking the user's P2P sharing preference.
     */
    fun broadcastFlockSighting(sighting: FlockSighting) {
        val payload = gson.toJson(mapOf(
            "type"      to "flock_sighting",
            "nodeId"    to nodeId,
            "hops"      to 0,
            "maxHops"   to MAX_HOPS,
            "timestamp" to sighting.timestamp,
            "sighting"  to mapOf(
                "id"             to sighting.id,
                "lat"            to sighting.lat,
                "lon"            to sighting.lon,
                "confidence"     to sighting.confidence,
                "matchedSignals" to sighting.matchedSignals
            )
        ))
        val sent = connections.values.filter { it.send(payload) }.size
        if (sent == 0) {
            if (pendingQueue.size >= MAX_QUEUE_SIZE) pendingQueue.poll()
            pendingQueue.offer(payload)
            AppLog.w("P2P", "No connections - queued flock sighting (${pendingQueue.size}/$MAX_QUEUE_SIZE)")
        } else {
            AppLog.i("P2P", "Broadcast flock sighting to $sent relay(s)")
        }
    }

    fun broadcastPoliceSighting(sighting: com.aegisnav.app.police.PoliceSighting) {
        val payload = gson.toJson(mapOf(
            "type"      to "police_sighting",
            "nodeId"    to nodeId,
            "hops"      to 0,
            "maxHops"   to MAX_HOPS,
            "timestamp" to sighting.timestamp,
            "sighting"  to mapOf(
                "id"               to sighting.id,
                "lat"              to sighting.lat,
                "lon"              to sighting.lon,
                "confidence"       to sighting.confidence,
                "matchedSignals"   to sighting.matchedSignals,
                "detectionCategory" to sighting.detectionCategory
            )
        ))
        val sent = connections.values.filter { it.send(payload) }.size
        if (sent == 0) {
            if (pendingQueue.size >= MAX_QUEUE_SIZE) pendingQueue.poll()
            pendingQueue.offer(payload)
            AppLog.w("P2P", "No connections - queued police sighting (${pendingQueue.size}/$MAX_QUEUE_SIZE)")
        } else {
            AppLog.i("P2P", "Broadcast police sighting to $sent relay(s)")
        }
    }

    /** Returns true if P2P sharing is configured AND offline mode is OFF.
     *  This is the single choke point — all broadcast paths check this before sending. */
    fun isP2PEnabled(): Boolean {
        if (_offlineMode.value) return false
        return activeRelayUrls().isNotEmpty()
    }

    private fun flushQueue(ws: WebSocket) {
        var flushed = 0
        while (pendingQueue.poll()?.also { ws.send(it) } != null) { flushed++ }
        if (flushed > 0) AppLog.i("P2P", "Flushed $flushed queued reports")
    }

    // ── Incoming ───────────────────────────────────────────────────────────

    private fun handleIncoming(text: String) {
        try {
            @Suppress("UNCHECKED_CAST")
            val msg = gson.fromJson(text, Map::class.java) as Map<String, Any>
            if (msg["type"] != "REPORT") return
            val hops = (msg["hops"] as? Double)?.toInt() ?: 0
            if (hops >= MAX_HOPS) return
            @Suppress("UNCHECKED_CAST")
            val r = msg["report"] as? Map<String, Any> ?: return
            val incoming = IncomingReport(
                lat          = (r["lat"] as? Double) ?: return,
                lon          = (r["lon"] as? Double) ?: return,
                type         = r["type"] as? String ?: "Unknown",
                subtype      = r["subtype"] as? String ?: "",
                description  = r["description"] as? String ?: "",
                threatLevel  = r["threatLevel"] as? String ?: "LOW",
                timestamp    = (msg["timestamp"] as? Double)?.toLong() ?: System.currentTimeMillis(),
                sourceNodeId = msg["nodeId"] as? String ?: "unknown"
            )
            val cutoff = System.currentTimeMillis() - 90 * 60_000L
            _incomingReports.value = (_incomingReports.value
                .filter { it.timestamp >= cutoff && !(it.sourceNodeId == incoming.sourceNodeId && it.type == incoming.type) }
                + incoming)
        } catch (e: Exception) {
            AppLog.e("P2P", "Parse error: ${e.message}")
        }
    }

    private fun updateState(url: String, state: ConnectionState) {
        val newMap = _states.value.toMutableMap().also { it[url] = state }
        _states.value = newMap
        _overallState.value = when {
            newMap.values.any { it == ConnectionState.CONNECTED }  -> ConnectionState.CONNECTED
            newMap.values.any { it == ConnectionState.CONNECTING } -> ConnectionState.CONNECTING
            else -> ConnectionState.DISCONNECTED
        }
    }

    // ── Node ID rotation ───────────────────────────────────────────────────

    private fun rotatedNodeId(): String {
        // Pattern A: read from hot StateFlow (non-blocking).
        val snapshot = prefsFlow.value
        val createdAt = snapshot[longPreferencesKey(PREF_NODE_CREATED)] ?: 0L
        val now = System.currentTimeMillis()
        val existingId = snapshot[stringPreferencesKey(PREF_NODE_ID)]
        if (now - createdAt > NODE_ID_ROTATION_MS || existingId == null) {
            val newId = UUID.randomUUID().toString().take(16)
            // Fire-and-forget write — the StateFlow will reflect the update on next emission.
            scope.launch {
                dataStore.edit { prefs ->
                    prefs[stringPreferencesKey(PREF_NODE_ID)] = newId
                    prefs[longPreferencesKey(PREF_NODE_CREATED)] = now
                }
            }
            AppLog.i("P2P", "Node ID rotated → $newId")
            return newId
        }
        return existingId
    }

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }
}
