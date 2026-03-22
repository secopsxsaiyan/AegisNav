package com.aegisnav.app.police

import com.aegisnav.app.di.ApplicationScope
import com.aegisnav.app.geocoder.OfflineGeocoderRepository
import com.aegisnav.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * Correlates police equipment MACs across locations to identify officer units.
 *
 * Algorithm:
 * 1. When 2+ MACs appear in the same PoliceSighting, record all pairs as co-occurrence.
 * 2. If the same pair appears at 2+ distinct cells (>100m apart), promote to confirmed unit.
 * 3. Confirmed units persist across sessions in the officer_units Room table.
 * 4. Any single MAC from a known unit immediately identifies the unit.
 * 5. Units with confirmCount >= 3 trigger auto-confirm on new sightings.
 *
 * Unit IDs are city-based: "plantcity0", "tampa1", etc.
 */
@Singleton
class OfficerCorrelationEngine @Inject constructor(
    private val officerUnitDao: OfficerUnitDao,
    private val offlineGeocoder: OfflineGeocoderRepository,
    @ApplicationScope private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "OfficerCorrelationEngine"
        private const val MIN_LOCATIONS_FOR_UNIT = 2
        private const val CELL_SIZE_DEG = 50.0 / 110574.0  // ~50m in degrees
        private const val MIN_CELL_DISTANCE_M = 100.0
    }

    // In-memory: MAC pair → set of grid cells where they co-occurred
    // Key: sorted pair "MAC1,MAC2", Value: set of "latBucket:lonBucket"
    private val coOccurrences = ConcurrentHashMap<String, MutableSet<String>>()

    // In-memory cache: MAC → unitId (loaded from DB on init)
    private val macToUnit = ConcurrentHashMap<String, String>()

    // Track if we've loaded from DB
    @Volatile private var initialized = false

    private suspend fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
        }
        try {
            val units = officerUnitDao.getAll()
            for (unit in units) {
                unit.macSet.split(",").filter { it.isNotBlank() }.forEach { mac ->
                    macToUnit[mac.uppercase()] = unit.unitId
                }
            }
            initialized = true
            AppLog.i(TAG, "Loaded ${units.size} officer units, ${macToUnit.size} MAC mappings")
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to load officer units: ${e.message}")
            initialized = true // prevent infinite retry
        }
    }

    /**
     * Process a police sighting. Returns the officerUnitId if this sighting matches
     * or creates a unit, null otherwise.
     */
    suspend fun correlate(sighting: PoliceSighting): String? {
        ensureInitialized()

        val macs = sighting.deviceMacs?.split(",")
            ?.map { it.trim().uppercase() }
            ?.filter { it.isNotBlank() }
            ?: return null

        if (macs.isEmpty()) return null

        val cellId = gridCell(sighting.lat, sighting.lon)

        // Check if any MAC already belongs to a known unit
        for (mac in macs) {
            val existingUnitId = macToUnit[mac]
            if (existingUnitId != null) {
                // Known unit — update it with any new MACs from this sighting
                val unit = officerUnitDao.getById(existingUnitId)
                if (unit != null) {
                    val existingMacs = unit.macSet.split(",").map { it.trim().uppercase() }.toMutableSet()
                    val newMacs = macs.filter { it !in existingMacs }
                    if (newMacs.isNotEmpty()) {
                        existingMacs.addAll(newMacs)
                        val updatedMacSet = existingMacs.sorted().joinToString(",")
                        officerUnitDao.updateMacSet(existingUnitId, updatedMacSet)
                        newMacs.forEach { macToUnit[it] = existingUnitId }
                        AppLog.i(TAG, "Unit $existingUnitId: added ${newMacs.size} new MACs → ${existingMacs.size} total")
                    }
                    officerUnitDao.updateLastSeen(existingUnitId, System.currentTimeMillis())
                }
                AppLog.i(TAG, "Sighting matched existing unit: $existingUnitId")
                return existingUnitId
            }
        }

        // No existing unit match — record co-occurrences if 2+ MACs
        if (macs.size >= 2) {
            for (i in macs.indices) {
                for (j in i + 1 until macs.size) {
                    val pairKey = listOf(macs[i], macs[j]).sorted().joinToString(",")
                    val cells = coOccurrences.getOrPut(pairKey) { ConcurrentHashMap.newKeySet() }
                    cells.add(cellId)

                    // Check if this pair now qualifies for unit promotion
                    if (cells.size >= MIN_LOCATIONS_FOR_UNIT && hasDistinctLocations(cells)) {
                        val unitId = createUnit(macs, sighting.lat, sighting.lon)
                        if (unitId != null) {
                            AppLog.i(TAG, "New officer unit created: $unitId from pair ($pairKey) at ${cells.size} locations")
                            return unitId
                        }
                    }
                }
            }
        }

        return null
    }

    /**
     * Check if any two cells in the set are >100m apart (truly distinct locations).
     */
    private fun hasDistinctLocations(cells: Set<String>): Boolean {
        val cellList = cells.toList()
        for (i in cellList.indices) {
            for (j in i + 1 until cellList.size) {
                val (lat1, lon1) = parseCellCenter(cellList[i])
                val (lat2, lon2) = parseCellCenter(cellList[j])
                if (haversineMeters(lat1, lon1, lat2, lon2) >= MIN_CELL_DISTANCE_M) {
                    return true
                }
            }
        }
        return false
    }

    private fun parseCellCenter(cellId: String): Pair<Double, Double> {
        val parts = cellId.split(":")
        val latBucket = parts[0].toLong()
        val lonBucket = parts[1].toLong()
        return Pair(latBucket * CELL_SIZE_DEG, lonBucket * CELL_SIZE_DEG)
    }

    /**
     * Create a new officer unit with a city-based ID.
     */
    private suspend fun createUnit(macs: List<String>, lat: Double, lon: Double): String? {
        return try {
            // Reverse geocode to get city name
            val cityName = offlineGeocoder.reverseGeocode(lat, lon, "fl")
            val cityPrefix = if (cityName.isBlank() || cityName == "unknown") "unknown" else cityName

            // Count existing units with same city prefix to determine index
            val existingCount = officerUnitDao.countByCity(cityPrefix)
            val unitId = "$cityPrefix$existingCount"

            val macSet = macs.sorted().joinToString(",")
            val now = System.currentTimeMillis()

            val unit = OfficerUnit(
                unitId = unitId,
                macSet = macSet,
                firstSeenMs = now,
                lastSeenMs = now,
                confirmCount = 0
            )
            officerUnitDao.insert(unit)

            // Update in-memory cache
            macs.forEach { macToUnit[it] = unitId }

            AppLog.i(TAG, "Created officer unit: $unitId with ${macs.size} MACs, city=$cityPrefix")
            unitId
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to create officer unit: ${e.message}")
            null
        }
    }

    /** Get unit by ID (for auto-confirm check). */
    suspend fun getUnit(unitId: String): OfficerUnit? = officerUnitDao.getById(unitId)

    /** Clear in-memory state (does NOT clear DB). */
    fun clearSession() {
        coOccurrences.clear()
        // Don't clear macToUnit — that's persistent knowledge
    }

    private fun gridCell(lat: Double, lon: Double): String {
        val latBucket = (lat / CELL_SIZE_DEG).toLong()
        val lonBucket = (lon / CELL_SIZE_DEG).toLong()
        return "$latBucket:$lonBucket"
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
