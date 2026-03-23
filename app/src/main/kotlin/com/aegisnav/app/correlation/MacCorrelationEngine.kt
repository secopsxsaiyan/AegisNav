package com.aegisnav.app.correlation

import com.aegisnav.app.data.model.ScanLog
import com.aegisnav.app.di.ApplicationScope
import com.aegisnav.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * MAC Correlation Engine — Phase 2A items 2.1 & 2.2.
 *
 * Responsibilities:
 *
 * **2.1 — MAC Randomization Detection**
 * - Identifies locally-assigned (randomized) MACs via bit 1 of first octet.
 * - Tracks rotation patterns: when a device disappears and a new MAC appears
 *   at the same location within [ROTATION_WINDOW_MS], links them to a group.
 * - Correlation uses any 2-of-3 signals: temporal+spatial proximity, RSSI similarity,
 *   mfgData fingerprint match.
 * - Creates and maintains [MacIdentityGroup] singletons per physical device.
 *
 * **2.2 — Global MAC Leak Detection**
 * - Detects transition from randomized → globally-assigned MAC.
 * - Promotes the leaked global MAC to primary identifier (sets [MacIdentityGroup.virtualMac]).
 * - Emits [MacLeakAlert] for user notification.
 * - Applies to both tracker and police detection via [resolveVirtualMac].
 *
 * Consumers call [resolveVirtualMac] to get a stable virtual MAC for any observed address.
 * [CrossCorrelationEngine] feeds additional links via [linkDevices].
 */
@Singleton
class MacCorrelationEngine @Inject constructor(
    @ApplicationScope private val engineScope: CoroutineScope
) {

    companion object {
        private const val TAG = "MacCorrelationEngine"

        /** Locally administered bit: bit 1 of first octet. Masks to 0x02. */
        private const val LOCALLY_ASSIGNED_MASK = 0x02

        /**
         * A disappearing MAC and a new MAC must overlap within this window
         * to be considered a rotation event.
         */
        const val ROTATION_WINDOW_MS = 2_000L

        /** Location proximity for rotation linkage (meters). */
        const val ROTATION_RADIUS_M = 30.0

        /** RSSI must differ by no more than this between the two MACs to link them. */
        const val RSSI_LINK_TOLERANCE = 10

        /** How long a "recently seen" MAC entry stays relevant for rotation detection. */
        const val RECENT_SEEN_TTL_MS = 10_000L

        /** First N hex chars of mfgData used as fingerprint (= first 4 bytes). */
        const val MFG_FINGERPRINT_CHARS = 8

        /**
         * Wider window for global MAC leak detection — slightly more lenient
         * because the device may briefly show both the old random MAC and new global MAC.
         */
        private const val LEAK_WINDOW_MS = ROTATION_WINDOW_MS * 5

        /** Minimum criteria match count to link two MACs as the same device. */
        private const val LINK_SCORE_THRESHOLD = 2

        /** Groups whose lastSeen is older than this are eligible for eviction. */
        private const val GROUP_TTL_MS = 60 * 60_000L

        /** Minimum interval between successive prune sweeps to avoid overhead. */
        private const val PRUNE_INTERVAL_MS = 5 * 60_000L
    }

    // ── Internal state ────────────────────────────────────────────────────────

    /** MAC (uppercase) → groupId */
    private val macToGroup = ConcurrentHashMap<String, String>()

    /** groupId → MacIdentityGroup */
    private val groups = ConcurrentHashMap<String, MacIdentityGroup>()

    /** Timestamp of the last prune sweep; 0L means never pruned. */
    private var lastPruneMs = 0L

    /**
     * Recently-seen MAC table used for rotation detection.
     * MAC (uppercase) → snapshot at last observation time.
     */
    private data class RecentEntry(
        val mac: String,
        val lat: Double,
        val lon: Double,
        val rssi: Int,
        val mfgFingerprint: String?,
        val lastSeen: Long
    )
    private val recentlySeen = ConcurrentHashMap<String, RecentEntry>()

        // ── Public streams ────────────────────────────────────────────────────────

    private val _leakAlerts = MutableSharedFlow<MacLeakAlert>(extraBufferCapacity = 16)

    /**
     * Emits a [MacLeakAlert] whenever a device transitions from randomized → global MAC.
     * Collect in ScanService to show the user notification.
     */
    val leakAlerts: SharedFlow<MacLeakAlert> = _leakAlerts

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns true if [mac] is a locally-assigned (randomized) address.
     *
     * Per IEEE 802, bit 1 (value 0x02) of the first octet set = locally administered.
     * This matches the pattern ?2:xx, ?6:xx, ?A:xx, ?E:xx for the first octet.
     */
    fun isLocallyAssigned(mac: String): Boolean {
        val parts = mac.split(":")
        if (parts.isEmpty()) return false
        val firstOctet = try {
            parts[0].toInt(16)
        } catch (_: NumberFormatException) {
            return false
        }
        return (firstOctet and LOCALLY_ASSIGNED_MASK) != 0
    }

    /** Returns true if [mac] is globally unique (OUI-registered, not locally assigned). */
    fun isGloballyAssigned(mac: String): Boolean = !isLocallyAssigned(mac)

    /**
     * Extracts a short fingerprint from a manufacturer-data hex string.
     * Takes the first [MFG_FINGERPRINT_CHARS] characters (= first 4 bytes =
     * company ID + 2 payload bytes), lowercased.
     * Returns null if [mfgHex] is null, blank, or shorter than 4 characters.
     */
    fun mfgDataFingerprint(mfgHex: String?): String? {
        if (mfgHex.isNullOrBlank() || mfgHex.length < 4) return null
        return mfgHex.take(MFG_FINGERPRINT_CHARS).lowercase()
    }

    /**
     * Main entry point. Call for every BLE and WiFi [ScanLog].
     *
     * Processing order:
     * 1. Skip entries without GPS coordinates (can't do spatial correlation).
     * 2. Prune stale recently-seen entries.
     * 3. If MAC is new → attempt rotation linkage or leak detection.
     * 4. If MAC is known → update group timestamp.
     * 5. Update the recently-seen entry for future rotation detection.
     */
    fun onScanResult(log: ScanLog) {
        val mac = log.deviceAddress.uppercase()
        val lat = log.lat ?: return
        val lon = log.lng ?: return
        val now = log.timestamp
        val isRandom = isLocallyAssigned(mac)
        val fingerprint = mfgDataFingerprint(log.manufacturerDataHex)

        if (System.currentTimeMillis() - lastPruneMs > PRUNE_INTERVAL_MS) {
            pruneStaleGroups()
        }

        pruneRecentlySeen(now)

        val existingGroupId = macToGroup[mac]

        if (existingGroupId == null) {
            // Unknown MAC — try to correlate it with an existing group
            if (isRandom) {
                // Randomized MAC: attempt rotation linkage to existing randomized group
                val linkedGroupId = findRotationMatch(mac, lat, lon, log.rssi, fingerprint, now)
                if (linkedGroupId != null) {
                    addMacToGroup(linkedGroupId, mac, fingerprint, now, isRandom)
                    AppLog.d(TAG, "Rotation linked: $mac → group $linkedGroupId")
                } else {
                    createGroup(mac, fingerprint, now, isRandom)
                }
            } else {
                // Globally-assigned MAC: check if a nearby randomized group is the same device
                val leakedGroupId = findRandomizedGroupNearby(lat, lon, log.rssi, fingerprint, now)
                if (leakedGroupId != null) {
                    handleGlobalMacLeak(leakedGroupId, mac, now, log)
                } else {
                    createGroup(mac, fingerprint, now, isRandom)
                }
            }
        } else {
            // Known MAC — just refresh the group's lastSeen
            updateGroupTimestamp(existingGroupId, now, isRandom)
        }

        // Always update recently-seen for rotation detection on the next cycle
        recentlySeen[mac] = RecentEntry(mac, lat, lon, log.rssi, fingerprint, now)
    }

    /**
     * Links a BLE MAC and a WiFi BSSID as belonging to the same physical device.
     * Called by [CrossCorrelationEngine] once co-occurrence threshold is reached.
     *
     * Merges groups when both devices are already in different groups.
     */
    fun linkDevices(bleMac: String, wifiBssid: String) {
        val macUpper = bleMac.uppercase()
        val bssidUpper = wifiBssid.uppercase()

        val bleGroupId = macToGroup[macUpper]
        val wifiGroupId = macToGroup[bssidUpper]

        when {
            bleGroupId != null && wifiGroupId != null && bleGroupId != wifiGroupId -> {
                // Both exist in different groups → merge WiFi group into BLE group
                mergeGroups(primaryId = bleGroupId, secondaryId = wifiGroupId)
                AppLog.i(TAG, "Cross-corr merge: BLE $macUpper + WiFi $bssidUpper → group $bleGroupId")
            }
            bleGroupId != null && wifiGroupId == null -> {
                // Only BLE group exists → absorb the BSSID
                macToGroup[bssidUpper] = bleGroupId
                groups.compute(bleGroupId) { _, g ->
                    g?.copy(
                        knownMacs = g.knownMacs + bssidUpper,
                        linkedWifiBssids = g.linkedWifiBssids + bssidUpper
                    )
                }
                AppLog.i(TAG, "Cross-corr: WiFi $bssidUpper added to BLE group $bleGroupId")
            }
            bleGroupId == null && wifiGroupId != null -> {
                // Only WiFi group exists → absorb the BLE MAC
                macToGroup[macUpper] = wifiGroupId
                groups.compute(wifiGroupId) { _, g ->
                    g?.copy(
                        knownMacs = g.knownMacs + macUpper,
                        linkedBleMacs = g.linkedBleMacs + macUpper
                    )
                }
                AppLog.i(TAG, "Cross-corr: BLE $macUpper added to WiFi group $wifiGroupId")
            }
            bleGroupId == null && wifiGroupId == null -> {
                // Neither is grouped yet → create a linked group for both
                val groupId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                groups[groupId] = MacIdentityGroup(
                    groupId = groupId,
                    virtualMac = groupId,
                    knownMacs = setOf(macUpper, bssidUpper),
                    leakedGlobalMac = null,
                    leakedMacManufacturer = null,
                    mfgDataFingerprint = null,
                    firstSeen = now,
                    lastSeen = now,
                    isCurrentlyRandomized = isLocallyAssigned(macUpper),
                    rotationCount = 0,
                    lastRotationMs = 0L,
                    linkedWifiBssids = setOf(bssidUpper),
                    linkedBleMacs = setOf(macUpper)
                )
                macToGroup[macUpper] = groupId
                macToGroup[bssidUpper] = groupId
                AppLog.i(TAG, "Cross-corr new group: BLE $macUpper + WiFi $bssidUpper → $groupId")
            }
            // else: bleGroupId == wifiGroupId → already in same group, no-op
        }
    }

    /**
     * Resolves [mac] to its canonical virtual identifier.
     *
     * Return priority:
     * 1. Leaked global MAC (stable, has valid OUI) — if the group has one.
     * 2. The groupId string — stable across MAC rotations.
     * 3. The input MAC unchanged — if this address isn't tracked.
     */
    fun resolveVirtualMac(mac: String): String {
        val upper = mac.uppercase()
        val groupId = macToGroup[upper] ?: return mac
        return groups[groupId]?.virtualMac ?: mac
    }

    /**
     * Returns the [MacIdentityGroup] for [mac], or null if not tracked.
     */
    fun getGroupForMac(mac: String): MacIdentityGroup? {
        val groupId = macToGroup[mac.uppercase()] ?: return null
        return groups[groupId]
    }

    /**
     * Returns all known identity groups. Snapshot of current state.
     */
    fun getAllGroups(): List<MacIdentityGroup> = groups.values.toList()

    /**
     * Returns the count of tracked device groups.
     */
    fun groupCount(): Int = groups.size

    /**
     * Clears all correlation state. Should be called when scanning stops.
     */
    fun clear() {
        macToGroup.clear()
        groups.clear()
        recentlySeen.clear()
        AppLog.d(TAG, "State cleared")
    }

    // ── Private — Rotation Detection ──────────────────────────────────────────

    /**
     * Searches recently-seen randomized MACs for a rotation candidate.
     *
     * A candidate qualifies if it scores >= [LINK_SCORE_THRESHOLD] across:
     *   +1 for temporal+spatial: candidate was seen within [ROTATION_WINDOW_MS] at ≤[ROTATION_RADIUS_M]
     *   +1 for RSSI similarity: within [RSSI_LINK_TOLERANCE] dBm
     *   +1 for mfgData fingerprint: exact match of first 4 bytes
     *
     * Returns the groupId of the best match, or null if no match found.
     */
    private fun findRotationMatch(
        newMac: String,
        lat: Double,
        lon: Double,
        rssi: Int,
        fingerprint: String?,
        now: Long
    ): String? {
        val cutoff = now - ROTATION_WINDOW_MS
        var bestGroupId: String? = null
        var bestScore = LINK_SCORE_THRESHOLD - 1

        for ((candidateMac, entry) in recentlySeen) {
            if (candidateMac == newMac) continue
            if (entry.lastSeen < cutoff) continue
            // Only link to existing, tracked randomized MACs
            val groupId = macToGroup[candidateMac] ?: continue

            var score = 0

            // Criterion 1: temporal + spatial
            if (haversineMeters(lat, lon, entry.lat, entry.lon) <= ROTATION_RADIUS_M) score++

            // Criterion 2: RSSI similarity
            if (abs(rssi - entry.rssi) <= RSSI_LINK_TOLERANCE) score++

            // Criterion 3: mfgData fingerprint (strongest signal)
            if (fingerprint != null && entry.mfgFingerprint != null &&
                fingerprint == entry.mfgFingerprint
            ) score++

            if (score > bestScore) {
                bestScore = score
                bestGroupId = groupId
            }
        }

        return bestGroupId
    }

    /**
     * Searches recently-seen randomized MACs for a group that may be transitioning
     * to the provided globally-assigned MAC (global MAC leak detection).
     *
     * Uses a slightly wider spatial and RSSI window than rotation detection.
     */
    private fun findRandomizedGroupNearby(
        lat: Double,
        lon: Double,
        rssi: Int,
        fingerprint: String?,
        now: Long
    ): String? {
        val cutoff = now - LEAK_WINDOW_MS

        for ((candidateMac, entry) in recentlySeen) {
            if (!isLocallyAssigned(candidateMac)) continue
            if (entry.lastSeen < cutoff) continue
            val groupId = macToGroup[candidateMac] ?: continue
            val group = groups[groupId] ?: continue
            // Skip groups that already have a global MAC
            if (group.leakedGlobalMac != null) continue

            var score = 0
            if (haversineMeters(lat, lon, entry.lat, entry.lon) <= ROTATION_RADIUS_M * 3) score++
            if (abs(rssi - entry.rssi) <= RSSI_LINK_TOLERANCE * 2) score++
            // Fingerprint is a strong signal → count double
            if (fingerprint != null && entry.mfgFingerprint != null &&
                fingerprint == entry.mfgFingerprint
            ) score += 2

            if (score >= LINK_SCORE_THRESHOLD) return groupId
        }
        return null
    }

    // ── Private — Group Management ────────────────────────────────────────────

    private fun createGroup(
        mac: String,
        fingerprint: String?,
        now: Long,
        isRandom: Boolean
    ) {
        val groupId = UUID.randomUUID().toString()
        groups[groupId] = MacIdentityGroup(
            groupId = groupId,
            virtualMac = groupId,   // UUID until a global MAC is leaked
            knownMacs = setOf(mac),
            leakedGlobalMac = null,
            leakedMacManufacturer = null,
            mfgDataFingerprint = fingerprint,
            firstSeen = now,
            lastSeen = now,
            isCurrentlyRandomized = isRandom,
            rotationCount = 0,
            lastRotationMs = 0L,
            linkedWifiBssids = emptySet(),
            linkedBleMacs = emptySet()
        )
        macToGroup[mac] = groupId
        AppLog.d(TAG, "New group: $groupId ← $mac (random=$isRandom)")
    }

    private fun addMacToGroup(
        groupId: String,
        newMac: String,
        fingerprint: String?,
        now: Long,
        isRandom: Boolean
    ) {
        groups.compute(groupId) { _, g ->
            if (g == null) {
                // Group vanished concurrently — leave the map entry null (will be removed by compute)
                null
            } else {
                val newRotations = if (isRandom) g.rotationCount + 1 else g.rotationCount
                g.copy(
                    knownMacs = g.knownMacs + newMac,
                    lastSeen = now,
                    isCurrentlyRandomized = isRandom,
                    rotationCount = newRotations,
                    lastRotationMs = if (isRandom) now else g.lastRotationMs,
                    mfgDataFingerprint = g.mfgDataFingerprint ?: fingerprint
                )
            }
        }
        macToGroup[newMac] = groupId
    }

    private fun updateGroupTimestamp(groupId: String, now: Long, isRandom: Boolean) {
        groups.compute(groupId) { _, g ->
            g?.copy(lastSeen = now, isCurrentlyRandomized = isRandom)
        }
    }

    /**
     * Handles a detected global MAC leak:
     * 1. Adds the global MAC to the group.
     * 2. Promotes it as the primary virtual identifier.
     * 3. Emits a [MacLeakAlert] for user notification.
     */
    private fun handleGlobalMacLeak(
        groupId: String,
        globalMac: String,
        now: Long,
        log: ScanLog
    ) {
        // Capture the pre-update group snapshot for alert construction.
        // compute() is atomic on ConcurrentHashMap, eliminating the TOCTOU race between
        // the read and the copy-replace that existed when using groups[groupId] = group.copy(…).
        var previousGroup: MacIdentityGroup? = null
        val updated = groups.compute(groupId) { _, existing ->
            if (existing == null) {
                // Group was removed concurrently — signal by returning null (entry stays absent).
                null
            } else {
                previousGroup = existing
                existing.copy(
                    knownMacs = existing.knownMacs + globalMac,
                    leakedGlobalMac = globalMac,
                    virtualMac = globalMac,   // promoted: leaked MAC is now the canonical identifier
                    lastSeen = now,
                    isCurrentlyRandomized = false
                )
            }
        }

        if (updated == null) {
            // Group was removed concurrently; create a fresh one instead.
            createGroup(globalMac, mfgDataFingerprint(log.manufacturerDataHex), now, false)
            return
        }

        macToGroup[globalMac] = groupId

        val group = previousGroup!! // non-null: updated != null implies previousGroup was set
        val previousRandomMacs = group.knownMacs.filter { isLocallyAssigned(it) }.toSet()
        val alert = MacLeakAlert(
            groupId = groupId,
            leakedMac = globalMac,
            previousRandomMacs = previousRandomMacs,
            timestamp = now,
            lat = log.lat,
            lon = log.lng
        )

        AppLog.i(
            TAG,
            "Global MAC leak: $globalMac in group $groupId " +
                "(previousRandom=$previousRandomMacs, rotations=${group.rotationCount})"
        )
        engineScope.launch { _leakAlerts.emit(alert) }
    }

    /**
     * Merges [secondaryId] into [primaryId].
     * All MACs from the secondary group are remapped to the primary.
     * The primary retains the best virtual MAC (leaked global MAC preferred).
     */
    private fun mergeGroups(primaryId: String, secondaryId: String) {
        val primary = groups[primaryId] ?: return
        val secondary = groups[secondaryId] ?: return

        // Prefer leaked global MAC as the virtual identifier
        val bestVirtualMac = primary.leakedGlobalMac
            ?: secondary.leakedGlobalMac
            ?: primaryId

        val merged = primary.copy(
            virtualMac = bestVirtualMac,
            knownMacs = primary.knownMacs + secondary.knownMacs,
            leakedGlobalMac = primary.leakedGlobalMac ?: secondary.leakedGlobalMac,
            leakedMacManufacturer = primary.leakedMacManufacturer ?: secondary.leakedMacManufacturer,
            mfgDataFingerprint = primary.mfgDataFingerprint ?: secondary.mfgDataFingerprint,
            firstSeen = minOf(primary.firstSeen, secondary.firstSeen),
            lastSeen = maxOf(primary.lastSeen, secondary.lastSeen),
            rotationCount = primary.rotationCount + secondary.rotationCount,
            lastRotationMs = maxOf(primary.lastRotationMs, secondary.lastRotationMs),
            linkedWifiBssids = primary.linkedWifiBssids + secondary.linkedWifiBssids,
            linkedBleMacs = primary.linkedBleMacs + secondary.linkedBleMacs
        )

        // Remap all secondary MACs to primary group
        secondary.knownMacs.forEach { mac -> macToGroup[mac] = primaryId }

        groups[primaryId] = merged
        groups.remove(secondaryId)
        AppLog.d(TAG, "Merged group $secondaryId → $primaryId (knownMacs=${merged.knownMacs.size})")
    }

    // ── Private — Math ────────────────────────────────────────────────────────

    /**
     * Evicts groups whose [MacIdentityGroup.lastSeen] is older than [GROUP_TTL_MS].
     * Also cleans up all [macToGroup] entries pointing at evicted groups.
     * Throttled by [lastPruneMs] — call site checks interval before invoking.
     */
    private fun pruneStaleGroups() {
        val cutoff = System.currentTimeMillis() - GROUP_TTL_MS
        val staleGroupIds = groups.entries
            .filter { it.value.lastSeen < cutoff }
            .map { it.key }
            .toSet()

        if (staleGroupIds.isNotEmpty()) {
            staleGroupIds.forEach { groupId -> groups.remove(groupId) }
            macToGroup.entries.removeAll { it.value in staleGroupIds }
            AppLog.d(TAG, "Pruned ${staleGroupIds.size} stale group(s)")
        }

        lastPruneMs = System.currentTimeMillis()
    }

    private fun pruneRecentlySeen(now: Long) {
        val cutoff = now - RECENT_SEEN_TTL_MS
        recentlySeen.entries.removeAll { it.value.lastSeen < cutoff }
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}

/**
 * Emitted by [MacCorrelationEngine] when a device transitions from a locally-assigned
 * (randomized) MAC to a globally-unique (OUI-registered) MAC, revealing its real identity.
 *
 * Consumers should display a user alert such as:
 * "Device revealed real MAC: [leakedMac] (manufacturer if known)"
 */
data class MacLeakAlert(
    /** The groupId of the [MacIdentityGroup] that leaked its real MAC. */
    val groupId: String,

    /** The newly observed globally-assigned MAC address. */
    val leakedMac: String,

    /** All previously-observed randomized MACs for this group. */
    val previousRandomMacs: Set<String>,

    /** When the leak was detected (epoch millis). */
    val timestamp: Long,

    /** GPS latitude at time of detection, if available. */
    val lat: Double?,

    /** GPS longitude at time of detection, if available. */
    val lon: Double?
)
