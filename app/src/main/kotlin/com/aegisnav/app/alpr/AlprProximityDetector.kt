package com.aegisnav.app.alpr

import com.aegisnav.app.util.AppLog
import com.aegisnav.app.data.dao.ALPRBlocklistDao
import com.aegisnav.app.data.model.ALPRBlocklist
import com.aegisnav.app.data.model.Report
import com.aegisnav.app.data.model.ScanLog
import com.aegisnav.app.data.model.ThreatEvent
import com.aegisnav.app.data.repository.ReportsRepository
import com.aegisnav.app.data.repository.ThreatEventRepository
import com.aegisnav.app.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

@Singleton
class AlprProximityDetector @Inject constructor(
    private val alprBlocklistDao: ALPRBlocklistDao,
    private val threatEventRepository: ThreatEventRepository,
    private val reportsRepository: ReportsRepository,
    @ApplicationScope private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "AlprProximityDetector"
        const val CLUSTER_RADIUS_M = 5.0          // group signals within 5 metres
        const val ALPR_LOOKUP_RADIUS_M = 100.0    // check for ALPR cameras within 100m
        const val MIN_SIGNALS_TO_EMIT = 2          // need at least 2 signals before emitting
        const val CLUSTER_TTL_MS = 60_000L         // flush clusters older than 1 min
        const val COOLDOWN_MS = 5 * 60_000L        // 5 min cooldown per camera
        // RSSI @ 1m reference: BLE -59 dBm, WiFi -40 dBm
        const val BLE_TX_POWER = -59
        const val WIFI_TX_POWER = -40
        // Path-loss exponent: BLE 2.0, WiFi 2.7
        const val BLE_PATH_LOSS_N = 2.0
        const val WIFI_PATH_LOSS_N = 2.7

    fun rssiToDistance(rssi: Int, scanType: String): Double {
        val txPower = if (scanType == "BLE") BLE_TX_POWER.toDouble() else WIFI_TX_POWER.toDouble()
        val n = if (scanType == "BLE") BLE_PATH_LOSS_N else WIFI_PATH_LOSS_N
        return 10.0.pow((txPower - rssi) / (10.0 * n)).coerceIn(0.1, 200.0)
    }

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
    }

    data class SignalPoint(
        val lat: Double,
        val lon: Double,
        val rssi: Int,
        val scanType: String,
        val timestamp: Long,
        val deviceAddress: String
    )

    data class AlprCluster(
        val clusterId: String = UUID.randomUUID().toString(),
        val nearestCamera: ALPRBlocklist,
        val signals: MutableList<SignalPoint> = mutableListOf(),
        var createdAt: Long = System.currentTimeMillis()
    ) {
        fun centroid(): Pair<Double, Double> {
            if (signals.isEmpty()) return nearestCamera.lat to nearestCamera.lon
            var wLat = 0.0; var wLon = 0.0; var wSum = 0.0
            signals.forEach { s ->
                val dist = rssiToDistance(s.rssi, s.scanType).coerceAtLeast(0.1)
                val w = 1.0 / (dist * dist)
                wLat += w * s.lat; wLon += w * s.lon; wSum += w
            }
            return (wLat / wSum) to (wLon / wSum)
        }

        fun accuracyMeters(): Double {
            if (signals.size < 2) return 50.0
            val (cLat, cLon) = centroid()
            return signals.map { haversineMeters(cLat, cLon, it.lat, it.lon) }.average()
        }
    }

    // Active clusters keyed by camera id
    private val clusters = HashMap<Int, AlprCluster>()
    private val lastEmitTime = HashMap<Int, Long>()
        /**
     * Feed a scan result. If the scan position is within ALPR_LOOKUP_RADIUS_M of a known
     * ALPR camera, add it to that camera's cluster. If the cluster has MIN_SIGNALS_TO_EMIT
     * or more signals, emit a ThreatEvent + Report.
     */
    fun onScanResult(log: ScanLog) {
        val lat = log.lat ?: return
        val lon = log.lng ?: return
        scope.launch {
            try {
                evictStaleClusters()
                val pad = 0.001  // ~111m padding for the DB bounding box query
                val nearby = alprBlocklistDao.getNearby(lat - pad, lat + pad, lon - pad, lon + pad)
                val closest = nearby.minByOrNull { haversineMeters(lat, lon, it.lat, it.lon) }
                    ?.takeIf { haversineMeters(lat, lon, it.lat, it.lon) <= ALPR_LOOKUP_RADIUS_M }
                    ?: return@launch

                val signal = SignalPoint(lat, lon, log.rssi, log.scanType, log.timestamp, log.deviceAddress)
                val cluster = clusters.getOrPut(closest.id) { AlprCluster(nearestCamera = closest) }

                // Only add if within CLUSTER_RADIUS_M of existing cluster centroid (or first signal)
                if (cluster.signals.isNotEmpty()) {
                    val (cLat, cLon) = cluster.centroid()
                    if (haversineMeters(lat, lon, cLat, cLon) > CLUSTER_RADIUS_M) return@launch
                }
                cluster.signals.add(signal)
                AppLog.d(TAG, "Cluster for camera ${closest.id}: ${cluster.signals.size} signals")

                if (cluster.signals.size >= MIN_SIGNALS_TO_EMIT) {
                    val now = System.currentTimeMillis()
                    val lastEmit = lastEmitTime[closest.id] ?: 0L
                    if (now - lastEmit < COOLDOWN_MS) return@launch
                    lastEmitTime[closest.id] = now
                    emitAlprProximity(cluster)
                    clusters.remove(closest.id)
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "Error processing scan result", e)
            }
        }
    }

    private suspend fun emitAlprProximity(cluster: AlprCluster) {
        val (estLat, estLon) = cluster.centroid()
        val accuracy = cluster.accuracyMeters()
        val cam = cluster.nearestCamera

        val sightingsJson = JSONArray(cluster.signals.map { s ->
            JSONObject().apply {
                put("lat", s.lat); put("lon", s.lon); put("rssi", s.rssi)
                put("scanType", s.scanType); put("timestamp", s.timestamp)
                put("deviceAddress", s.deviceAddress)
                put("estimatedDistanceM", rssiToDistance(s.rssi, s.scanType))
            }
        })

        val detailJson = JSONObject().apply {
            put("estimatedLat", estLat)
            put("estimatedLon", estLon)
            put("estimatedAccuracyM", accuracy)
            put("signalCount", cluster.signals.size)
            put("nearestCameraId", cam.id)
            put("nearestCameraDesc", cam.desc)
            put("nearestCameraLat", cam.lat)
            put("nearestCameraLon", cam.lon)
            put("clusterId", cluster.clusterId)
            put("sightings", sightingsJson)
        }.toString()

        val event = ThreatEvent(
            type = "ALPR_PROXIMITY",
            mac = cluster.signals.firstOrNull()?.deviceAddress ?: "",
            timestamp = System.currentTimeMillis(),
            detailJson = detailJson
        )
        threatEventRepository.insert(event)

        // Also insert into Report history (so it appears in ReportHistoryScreen)
        val report = Report(
            type = "SURVEILLANCE",
            subtype = "ALPR_PROXIMITY",
            latitude = estLat,
            longitude = estLon,
            description = "ALPR camera detected nearby: ${cam.desc} " +
                "(${cluster.signals.size} signals, est. accuracy ${accuracy.toInt()}m)",
            timestamp = System.currentTimeMillis()
        )
        reportsRepository.insert(report)

        AppLog.i(TAG, "ALPR_PROXIMITY emitted: camera ${cam.id} at ($estLat,$estLon) acc=${accuracy.toInt()}m signals=${cluster.signals.size}")
    }

    private fun evictStaleClusters() {
        val now = System.currentTimeMillis()
        clusters.entries.removeIf { now - it.value.createdAt > CLUSTER_TTL_MS }
    }

    private fun evictStaleClustersForTest(now: Long) {
        clusters.entries.removeIf { now - it.value.createdAt > CLUSTER_TTL_MS }
    }

    // ── Internal pure helpers — exposed for testing ───────────────────────────

    
}