package com.aegisnav.app.geocoder

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.aegisnav.app.util.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline geocoder backed by per-state SQLite FTS4 databases.
 *
 * Database location: context.filesDir/geocoder/{stateAbbr}.db
 * Schema (FTS4 virtual table):
 *   CREATE VIRTUAL TABLE entries USING fts4(
 *       name TEXT, type TEXT, state TEXT,
 *       tokenize=unicode61
 *   );
 *   -- companion docid→coords table for efficient lookup:
 *   CREATE TABLE coords (docid INTEGER PRIMARY KEY, lat REAL, lon REAL);
 *
 * The database is generated offline by tools/build_geocoder.py and pushed
 * to the device (or downloaded as part of the state map package).
 *
 * Graceful degradation: returns empty list if database file not found.
 */
@Singleton
class OfflineGeocoderRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "OfflineGeocoder"
        private const val MAX_RESULTS = 10
    }

    // Open database cache - one per state abbr.
    // Access is guarded by dbCacheLock to prevent double-open under concurrent callers.
    private val dbCache = HashMap<String, SQLiteDatabase?>()
    private val dbCacheLock = Any()

    /**
     * Returns true if a geocoder database exists for the given state abbreviation.
     */
    fun isAvailable(stateAbbr: String): Boolean =
        dbFile(stateAbbr).exists()

    /**
     * Search for [query] in the given state's geocoder database.
     * Returns up to [MAX_RESULTS] results, ranked by FTS relevance.
     * Returns empty list if database is unavailable or query fails.
     */
    suspend fun search(query: String, stateAbbr: String, userLat: Double? = null, userLon: Double? = null): List<OfflineGeocoderResult> =
        withContext(Dispatchers.IO) {
            val db = getOrOpenDb(stateAbbr) ?: return@withContext emptyList()
            try {
                val parsed = parseAddressComponents(query)
                AppLog.i(TAG, "search: parsed=$parsed state=$stateAbbr")

                // Expanded street tokens shared by pass 1.5 and pass 2
                val expandedStreetTokens = expandRoadTypes(parsed.streetTokens).filter { it.length > 1 }
                val streetFts = buildFtsTokens(expandedStreetTokens, dropShort = true)

                // City/zip tokens for geographic narrowing (only effective with 5-col schema)
                val dbHasCityZip = hasCityZip(db)
                val cityTokens = if (dbHasCityZip && !parsed.city.isNullOrBlank())
                    parsed.city.split("\\s+".toRegex()).filter { it.length > 1 }
                else emptyList()
                val zipToken = if (dbHasCityZip && !parsed.zip.isNullOrBlank()) parsed.zip else null
                val geoTokens = cityTokens + listOfNotNull(zipToken)

                // Build FTS variants: with and without city/zip geographic tokens
                val streetFtsGeo = if (geoTokens.isNotEmpty())
                    buildFtsTokens(expandedStreetTokens + geoTokens, dropShort = true) else ""

                // Pass 1: full query including house number → exact address node in DB
                // Use expandedStreetTokens (abbreviations already expanded) so that
                // "Pkwy" → "Parkway", "Blvd" → "Boulevard" etc. match the full forms
                // stored in the OSM geocoder DB.
                val fullFts = buildFtsTokens(
                    expandedStreetTokens + listOfNotNull(parsed.houseNumber), dropShort = false
                )
                val fullFtsGeo = if (geoTokens.isNotEmpty())
                    buildFtsTokens(
                        expandedStreetTokens + listOfNotNull(parsed.houseNumber) + geoTokens,
                        dropShort = false
                    ) else ""

                // Per-search proximity city cache: reuse findNearestCity results for
                // results in the same geographic area within one search() call.
                val proximityCityCache = mutableMapOf<String, Pair<String?, String?>>()

                // Pass 1: try with city/zip first for geographic precision, then without
                var results = emptyList<OfflineGeocoderResult>()
                if (fullFtsGeo.isNotBlank()) {
                    AppLog.i(TAG, "search pass1+geo fts='$fullFtsGeo'")
                    results = runQuery(db, fullFtsGeo, parsed, overrideDisplay = null, streetOnly = false,
                        userLat = userLat, userLon = userLon, proximityCityCache = proximityCityCache)
                }
                if (results.isEmpty()) {
                    AppLog.i(TAG, "search pass1 fts='$fullFts'")
                    results = runQuery(db, fullFts, parsed, overrideDisplay = null, streetOnly = false,
                        userLat = userLat, userLon = userLon, proximityCityCache = proximityCityCache)
                }

                // Pass 1.5 (interpolation): exact address not in OSM, but we have bracketing
                // address nodes - interpolate coordinates linearly between them.
                // Try with city/zip first (address nodes have city data), then without.
                if (results.isEmpty() && parsed.houseNumber != null && streetFts.isNotBlank()) {
                    var interpolated: OfflineGeocoderResult? = null
                    if (streetFtsGeo.isNotBlank()) {
                        interpolated = interpolateAddress(db, parsed, streetFtsGeo, stateAbbr,
                            userLat = userLat, userLon = userLon)
                    }
                    if (interpolated == null) {
                        interpolated = interpolateAddress(db, parsed, streetFts, stateAbbr,
                            userLat = userLat, userLon = userLon)
                    }
                    if (interpolated != null) {
                        AppLog.i(TAG, "search pass1.5 interpolated: ${interpolated.displayName}")
                        results = listOf(interpolated)
                    }
                }

                // Pass 2: street-node fallback - no address nodes available for interpolation.
                // Street entries don't have city/zip in DB, so use plain streetFts.
                // Return multiple candidates for proximity sorting.
                if (results.isEmpty() && parsed.houseNumber != null && streetFts.isNotBlank()) {
                    AppLog.i(TAG, "search pass2 (street fallback) fts='$streetFts'")
                    results = runQuery(db, streetFts, parsed, overrideDisplay = null, streetOnly = true,
                        userLat = userLat, userLon = userLon, proximityCityCache = proximityCityCache)
                }

                // Pass 3: no house number - plain street/place search
                if (results.isEmpty() && parsed.houseNumber == null && streetFts.isNotBlank()) {
                    AppLog.i(TAG, "search pass3 (place/street) fts='$streetFts'")
                    results = runQuery(db, streetFts, parsed, overrideDisplay = null, streetOnly = false,
                        userLat = userLat, userLon = userLon, proximityCityCache = proximityCityCache)
                }

                // Final proximity sort when user location is available
                if (userLat != null && userLon != null && results.size > 1) {
                    results = results.sortedBy { r ->
                        approxDistanceSq(r.lat, r.lon, userLat, userLon)
                    }
                }

                AppLog.i(TAG, "search: ${results.size} results for '$query'")
                results
            } catch (e: Exception) {
                AppLog.e(TAG, "Geocoder search FAILED for '$query' in $stateAbbr", e)
                synchronized(dbCacheLock) {
                    dbCache[stateAbbr]?.close()
                    dbCache.remove(stateAbbr)
                }
                emptyList()
            }
        }

    /**
     * Linear interpolation between the two address nodes that bracket [parsed.houseNumber]
     * on the same street. Works even when the exact house is not in OSM.
     *
     * Requires at least 2 address nodes on the street with parseable house numbers
     * that bracket the target. Returns null if interpolation is not possible.
     */
    private fun interpolateAddress(
        db: SQLiteDatabase,
        parsed: ParsedAddress,
        streetFts: String,
        stateAbbr: String,
        userLat: Double? = null,
        userLon: Double? = null
    ): OfflineGeocoderResult? {
        val targetNum = parsed.houseNumber?.toIntOrNull() ?: return null

        data class AddrNode(val num: Int, val name: String, val lat: Double, val lon: Double, val city: String?, val zip: String?)

        // Bbox filter: ±0.5° (~55 km) around user position to avoid mixing
        // same-name streets from different cities during interpolation
        val useBbox = userLat != null && userLon != null
        val bboxWhere = if (useBbox)
            " AND c.lat BETWEEN ? AND ? AND c.lon BETWEEN ? AND ?" else ""
        val queryArgs = if (useBbox && userLat != null && userLon != null)
            arrayOf(streetFts,
                (userLat - 0.5).toString(), (userLat + 0.5).toString(),
                (userLon - 0.5).toString(), (userLon + 0.5).toString())
        else arrayOf(streetFts)

        val nodes = mutableListOf<AddrNode>()
        db.rawQuery(
            """
            SELECT e.name, c.lat, c.lon ${if (hasCityZip(db)) ", e.city, e.zip" else ""}
            FROM entries e
            JOIN coords c ON c.docid = e.docid
            WHERE entries MATCH ? AND e.type = 'address'$bboxWhere
            LIMIT 50
            """.trimIndent(),
            queryArgs
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                val houseMatch = Regex("^(\\d+)").find(name.trim())
                val num = houseMatch?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val city = if (cursor.columnCount > 3) cursor.getString(3).orEmpty() else ""
                val zip  = if (cursor.columnCount > 4) cursor.getString(4).orEmpty() else ""
                nodes.add(AddrNode(num, name, cursor.getDouble(1), cursor.getDouble(2),
                    city.takeIf { it.isNotBlank() }, zip.takeIf { it.isNotBlank() }))
            }
        }

        if (nodes.size < 2) return null
        nodes.sortBy { it.num }

        // Find bracketing pair
        val lo = nodes.lastOrNull { it.num <= targetNum } ?: return null
        val hi = nodes.firstOrNull { it.num > targetNum } ?: return null
        if (lo.num == hi.num) return null

        val fraction = (targetNum - lo.num).toDouble() / (hi.num - lo.num).toDouble()
        val lat = lo.lat + fraction * (hi.lat - lo.lat)
        val lon = lo.lon + fraction * (hi.lon - lo.lon)

        // Prefer city/zip from DB nodes; fall back to user's typed input
        val city = lo.city ?: hi.city ?: parsed.city
        val zip  = lo.zip  ?: hi.zip  ?: parsed.zip

        // Extract the actual street name.  Prefer the canonical 'street' entry name
        // (way name from OSM, e.g. "James L Redman Parkway") over address-node names
        // which sometimes omit middle initials (e.g. "James Redman Parkway").
        val streetName = run {
            // Query matching 'street' entries and pick the one closest to the
            // interpolated point — avoids grabbing a similarly-named but unrelated street.
            val canonicalStreet = try {
                val candidates = mutableListOf<Pair<String, Double>>()
                db.rawQuery(
                    """
                    SELECT e.name, c.lat, c.lon FROM entries e
                    JOIN coords c ON c.docid = e.docid
                    WHERE entries MATCH ? AND e.type = 'street'
                    LIMIT 20
                    """.trimIndent(),
                    arrayOf(streetFts)
                ).use { c ->
                    while (c.moveToNext()) {
                        val n = c.getString(0) ?: continue
                        val sLat = c.getDouble(1)
                        val sLon = c.getDouble(2)
                        // Simple Euclidean distance (fine for nearby points)
                        val dist = Math.sqrt((sLat - lat) * (sLat - lat) + (sLon - lon) * (sLon - lon))
                        candidates.add(n to dist)
                    }
                }
                candidates.minByOrNull { it.second }?.first
            } catch (e: Exception) { AppLog.w(TAG, "Street candidate query failed: ${e.message}"); null }

            canonicalStreet
                ?: lo.name.replace(Regex("^\\d+\\s+"), "").takeIf { it.isNotBlank() }
                ?: hi.name.replace(Regex("^\\d+\\s+"), "").takeIf { it.isNotBlank() }
                ?: parsed.streetTokens.joinToString(" ")
        }
        val displayName = buildDisplayName(
            "${parsed.houseNumber ?: targetNum} $streetName",
            stateAbbr, city, zip
        )
        AppLog.i(TAG, "interpolated $targetNum between ${lo.num}@(${lo.lat},${lo.lon}) " +
            "and ${hi.num}@(${hi.lat},${hi.lon}) → frac=${"%.3f".format(fraction)} → ($lat,$lon)")

        return OfflineGeocoderResult(displayName, lat, lon, "address", stateAbbr)
    }

    /** True when the open DB has city and zip columns (new 5-column schema). */
    private fun hasCityZip(db: SQLiteDatabase): Boolean {
        return try {
            db.rawQuery("SELECT city FROM entries LIMIT 0", null).use { true }
        } catch (_: Exception) { false }
    }

    /**
     * Find the city name and zip for coordinates using a two-pass strategy:
     *   1. Check nearby address entries (addr:city tags) — most accurate, uses consensus
     *      of the most common city name among nearby addresses.
     *   2. Fall back to nearest place node (city/town/village/suburb) within ±0.05°.
     *
     * This mirrors Nominatim's approach: admin boundary → place node → address tags.
     * We skip admin boundaries (would require polygon geometry) and go address → place.
     *
     * @return Pair(cityName, zip) or Pair(null, null) if nothing found.
     */
    private fun findNearestCity(
        db: SQLiteDatabase,
        lat: Double,
        lon: Double
    ): Pair<String?, String?> {
        val delta = 0.05 // ~5.5 km

        // Pass 1: consensus city from nearby address entries (most accurate for urban areas)
        try {
            db.rawQuery(
                """
                SELECT e.city, e.zip, COUNT(*) AS cnt FROM entries e
                JOIN coords c ON c.docid = e.docid
                WHERE e.type = 'address'
                  AND e.city IS NOT NULL AND e.city != ''
                  AND c.lat BETWEEN ? AND ?
                  AND c.lon BETWEEN ? AND ?
                GROUP BY e.city
                ORDER BY cnt DESC
                LIMIT 1
                """.trimIndent(),
                arrayOf(
                    (lat - delta).toString(), (lat + delta).toString(),
                    (lon - delta).toString(), (lon + delta).toString()
                )
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val city = cursor.getString(0)?.takeIf { it.isNotBlank() }
                    val zip  = cursor.getString(1)?.takeIf { it.isNotBlank() }
                    if (city != null) return Pair(city, zip)
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "findNearestCity address pass failed at ($lat,$lon): ${e.message}")
        }

        // Pass 2: nearest place node (city/town/village/suburb)
        return try {
            db.rawQuery(
                """
                SELECT e.name, e.zip FROM entries e
                JOIN coords c ON c.docid = e.docid
                WHERE e.type IN ('city', 'town', 'village', 'suburb')
                  AND c.lat BETWEEN ? AND ?
                  AND c.lon BETWEEN ? AND ?
                ORDER BY ABS(c.lat - ?) + ABS(c.lon - ?)
                LIMIT 1
                """.trimIndent(),
                arrayOf(
                    (lat - delta).toString(), (lat + delta).toString(),
                    (lon - delta).toString(), (lon + delta).toString(),
                    lat.toString(), lon.toString()
                )
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)?.takeIf { it.isNotBlank() }
                    val zip  = cursor.getString(1)?.takeIf { it.isNotBlank() }
                    Pair(name, zip)
                } else {
                    Pair(null, null)
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "findNearestCity place pass failed at ($lat,$lon): ${e.message}")
            Pair(null, null)
        }
    }

    private fun runQuery(
        db: SQLiteDatabase,
        ftsQuery: String,
        parsed: ParsedAddress,
        overrideDisplay: String?,
        streetOnly: Boolean,
        userLat: Double? = null,
        userLon: Double? = null,
        proximityCityCache: MutableMap<String, Pair<String?, String?>>? = null
    ): List<OfflineGeocoderResult> {
        if (ftsQuery.isBlank()) return emptyList()
        val orderBy = if (streetOnly)
            "CASE WHEN e.type = 'street' THEN 0 ELSE 1 END ASC"
        else if (parsed.houseNumber != null)
            "CASE WHEN e.type = 'address' THEN 0 WHEN e.type = 'street' THEN 1 ELSE 2 END ASC"
        else
            "CASE WHEN e.type = 'street' THEN 0 WHEN e.type = 'address' THEN 1 ELSE 2 END ASC"

        // Select city + zip when available in the new 5-column DB schema
        val withCityZip = hasCityZip(db)
        val selectExtra = if (withCityZip) ", e.city, e.zip" else ""

        // Bounding-box filter: ±0.5° (~55 km) around user, narrows street-only queries
        val useBbox = userLat != null && userLon != null
        val bboxWhere = if (useBbox)
            " AND c.lat BETWEEN ? AND ? AND c.lon BETWEEN ? AND ?" else ""
        val queryArgs = if (useBbox && userLat != null && userLon != null)
            arrayOf(ftsQuery,
                (userLat - 0.5).toString(), (userLat + 0.5).toString(),
                (userLon - 0.5).toString(), (userLon + 0.5).toString())
        else arrayOf(ftsQuery)

        val results = mutableListOf<OfflineGeocoderResult>()
        db.rawQuery(
            """
            SELECT e.name, e.type, e.state, c.lat, c.lon$selectExtra
            FROM entries e
            JOIN coords c ON c.docid = e.docid
            WHERE entries MATCH ?$bboxWhere
            ORDER BY $orderBy
            LIMIT $MAX_RESULTS
            """.trimIndent(),
            queryArgs
        ).use { cursor ->
            val seen = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                val name  = cursor.getString(0)
                val type  = cursor.getString(1)
                val state = cursor.getString(2)
                if (streetOnly && type == "address") continue
                // Include approximate coords in dedup key so same-name entries in
                // different locations (e.g. "Pine Street" in Lakeland vs Gainesville)
                // are treated as distinct results
                val dedupeKey = "${name.lowercase()}|${"%.2f".format(cursor.getDouble(3))}|${"%.2f".format(cursor.getDouble(4))}"
                if (!seen.add(dedupeKey)) continue

                // Prefer city/zip from DB; fall back to what the user typed
                val dbCity = if (withCityZip) cursor.getString(5)?.takeIf { it.isNotBlank() } else null
                val dbZip  = if (withCityZip) cursor.getString(6)?.takeIf { it.isNotBlank() } else null
                var city = dbCity ?: parsed.city
                var zip  = dbZip  ?: parsed.zip

                // Last-resort: proximity lookup to find nearest 'place' entry when
                // neither the DB nor user input provided city/zip data.
                if (city == null && zip == null) {
                    val resultLat = cursor.getDouble(3)
                    val resultLon = cursor.getDouble(4)
                    // Use a coarse cache key (0.05° grid ≈ bounding-box cell) to avoid
                    // re-querying the DB for results in the same geographic area.
                    val cacheKey = "${"%.2f".format(resultLat)}|${"%.2f".format(resultLon)}"
                    val (proxCity, proxZip) = proximityCityCache?.getOrPut(cacheKey) {
                        findNearestCity(db, resultLat, resultLon)
                    } ?: findNearestCity(db, resultLat, resultLon)
                    city = proxCity
                    zip  = proxZip
                }

                val display = overrideDisplay
                    ?: if (parsed.houseNumber != null && type != "address")
                        buildDisplayName("${parsed.houseNumber} $name", state, city, zip)
                    else
                        buildDisplayName(name, state, city, zip)
                results.add(OfflineGeocoderResult(
                    displayName = display,
                    lat = cursor.getDouble(3),
                    lon = cursor.getDouble(4),
                    type = type,
                    state = state
                ))
            }
        }

        // Sort by proximity when user location is available
        if (userLat != null && userLon != null && results.size > 1) {
            return results.sortedBy { r -> approxDistanceSq(r.lat, r.lon, userLat, userLon) }
        }
        return results
    }

    // ── Address component parser ───────────────────────────────────────────────

    data class ParsedAddress(
        val houseNumber: String?,
        val streetTokens: List<String>,
        val city: String?,
        val zip: String?
    )

    /**
     * Parse "2602 James L Redman Pkwy, Plant City, FL 33566" into components.
     * Handles: optional house number, optional comma-delimited city/state/zip suffix.
     */
    private fun parseAddressComponents(raw: String): ParsedAddress {
        // Split on first comma to isolate street part from city/state/zip
        val firstComma = raw.indexOf(',')
        val streetRaw = if (firstComma >= 0) raw.substring(0, firstComma) else raw
        val suffixRaw = if (firstComma >= 0) raw.substring(firstComma + 1) else ""

        val streetTokens = streetRaw.trim()
            .replace("\"", "").replace("'", "")
            .replace(".", "").replace("/", " ")
            .split("\\s+".toRegex()).filter { it.isNotBlank() }

        val houseNumber = streetTokens.firstOrNull { it.all { c -> c.isDigit() } && it == streetTokens.first() }
        val streetOnly = if (houseNumber != null) streetTokens.drop(1) else streetTokens

        // Parse zip (5-digit sequence) from suffix
        val zip = Regex("\\b(\\d{5})\\b").find(suffixRaw)?.value

        // Parse city: text in the suffix before the state abbreviation, stripped of zip
        val withoutZip = suffixRaw.replace(Regex("\\b\\d{5}\\b"), "")
        val statePattern = Regex("\\b[A-Z]{2}\\b")
        val stateMatch = statePattern.find(withoutZip)
        val city = if (stateMatch != null) {
            withoutZip.substring(0, stateMatch.range.first)
                .replace(",", "").trim().takeIf { it.isNotBlank() }
        } else {
            withoutZip.replace(",", "").trim().takeIf { it.isNotBlank() }
        }

        return ParsedAddress(houseNumber, streetOnly, city, zip)
    }

    /** Build the display label from the user's own typed address components.
     *  [overrideCity] and [overrideZip] come from DB address nodes and take priority. */
    private fun buildUserLabel(
        parsed: ParsedAddress,
        stateAbbr: String,
        overrideCity: String? = null,
        overrideZip: String? = null
    ): String {
        val city = overrideCity ?: parsed.city
        val zip  = overrideZip  ?: parsed.zip
        return buildString {
            parsed.houseNumber?.let { append(it); append(" ") }
            append(parsed.streetTokens.joinToString(" "))
            city?.let { append(", "); append(it) }
            append(", "); append(stateAbbr.uppercase())
            zip?.let { append(" "); append(it) }
        }
    }

    /**
     * Reverse geocode coordinates to nearest city/place name.
     * Queries the existing geocoder DB for 'place' entries within ~5.5km.
     * Returns lowercased, spaces/special chars removed: "Plant City" → "plantcity".
     * Returns "unknown" if no place found or DB unavailable.
     */
    suspend fun reverseGeocode(lat: Double, lon: Double, stateAbbr: String): String = withContext(Dispatchers.IO) {
        val db = getOrOpenDb(stateAbbr) ?: return@withContext "unknown"
        try {
            val delta = 0.05 // ~5.5km
            val cursor = db.rawQuery(
                """
                SELECT e.name, c.lat, c.lon
                FROM entries e
                JOIN coords c ON c.docid = e.docid
                WHERE e.type = 'place'
                  AND c.lat BETWEEN ? AND ?
                  AND c.lon BETWEEN ? AND ?
                LIMIT 20
                """.trimIndent(),
                arrayOf(
                    (lat - delta).toString(), (lat + delta).toString(),
                    (lon - delta).toString(), (lon + delta).toString()
                )
            )
            var bestName = "unknown"
            var bestDistSq = Double.MAX_VALUE
            cursor.use {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: continue
                    val pLat = it.getDouble(1)
                    val pLon = it.getDouble(2)
                    val distSq = approxDistanceSq(lat, lon, pLat, pLon)
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq
                        bestName = name
                    }
                }
            }
            bestName.lowercase().replace(Regex("[^a-z0-9]"), "")
        } catch (e: Exception) {
            AppLog.w(TAG, "reverseGeocode failed: ${e.message}")
            "unknown"
        }
    }

    private fun getOrOpenDb(stateAbbr: String): SQLiteDatabase? {
        synchronized(dbCacheLock) {
            if (dbCache.containsKey(stateAbbr)) return dbCache[stateAbbr]
            val file = dbFile(stateAbbr)
            if (!file.exists()) {
                AppLog.d(TAG, "Geocoder DB not found for $stateAbbr at ${file.absolutePath}")
                dbCache[stateAbbr] = null
                return null
            }
            val db = try {
                SQLiteDatabase.openDatabase(
                    file.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY
                ).also { AppLog.i(TAG, "Opened geocoder DB for $stateAbbr") }
            } catch (e: Exception) {
                AppLog.e(TAG, "Failed to open geocoder DB for $stateAbbr: ${e.message}")
                null
            }
            dbCache[stateAbbr] = db
            return db
        }
    }

    private fun dbFile(stateAbbr: String): File {
        // Sanitize stateAbbr: only letters allowed (path traversal prevention)
        val safe = stateAbbr.replace(Regex("[^A-Za-z]"), "")
        return File(context.filesDir, "geocoder/${safe.lowercase()}.db")
    }

    /** Close all open databases (call on app destroy if needed). */
    fun closeAll() {
        synchronized(dbCacheLock) {
            dbCache.values.forEach { it?.close() }
            dbCache.clear()
        }
    }

    /**
     * Fast approximate distance² (squared) for ranking — no sqrt, no trig except cos.
     * Accounts for longitude compression at the given latitude.
     * NOT suitable for actual distance measurement — only for sorting/comparing.
     */
    private fun approxDistanceSq(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = lat1 - lat2
        val dLon = (lon1 - lon2) * Math.cos(Math.toRadians((lat1 + lat2) / 2.0))
        return dLat * dLat + dLon * dLon
    }

    /** Build an FTS4 AND-query from a token list. Adds wildcard suffix to each token. */
    private fun buildFtsTokens(tokens: List<String>, dropShort: Boolean): String {
        val filtered = if (dropShort) tokens.filter { it.length > 1 } else tokens
        // Strip FTS4 special characters to prevent unintended query operators:
        // quotes, parentheses, asterisks, hyphens, plus signs
        return filtered.joinToString(" ") { token ->
            val safe = token.replace(Regex("[\"()*+\\-]"), "")
            if (safe.isNotBlank()) "$safe*" else ""
        }.trim()
    }

    /**
     * Expand common US road-type abbreviations to their full form so the FTS
     * search matches entries stored either way in OSM.
     * e.g. "Pkwy" → "Parkway", "Blvd" → "Boulevard"
     */
    private fun expandRoadTypes(tokens: List<String>): List<String> {
        val map = mapOf(
            // Road types
            "pkwy" to "Parkway", "blvd" to "Boulevard", "ave" to "Avenue",
            "st" to "Street", "str" to "Street",
            "dr" to "Drive", "rd" to "Road", "ln" to "Lane", "ct" to "Court",
            "pl" to "Place", "hwy" to "Highway", "fwy" to "Freeway",
            "ter" to "Terrace", "trl" to "Trail", "cir" to "Circle",
            "sq" to "Square", "expy" to "Expressway", "aly" to "Alley",
            "bnd" to "Bend", "xing" to "Crossing", "pass" to "Pass",
            // Directional abbreviations
            "e" to "East", "w" to "West", "n" to "North", "s" to "South",
            "ne" to "Northeast", "nw" to "Northwest",
            "se" to "Southeast", "sw" to "Southwest"
        )
        return tokens.map { t -> map[t.lowercase()] ?: t }
    }

    private fun buildDisplayName(name: String, state: String, city: String? = null, zip: String? = null): String {
        val stateUpper = state.uppercase()
        return buildString {
            append(name)
            if (!city.isNullOrBlank()) { append(", "); append(city) }
            append(", "); append(stateUpper)
            if (!zip.isNullOrBlank()) { append(" "); append(zip) }
        }
    }
}
