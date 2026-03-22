package com.aegisnav.app.routing

import android.content.Context
import com.aegisnav.app.data.dao.ALPRBlocklistDao
import com.aegisnav.app.util.AppLog
import com.graphhopper.GHRequest
import com.graphhopper.GHResponse
import com.graphhopper.GraphHopper
import com.graphhopper.GraphHopperConfig
import com.graphhopper.config.Profile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

data class LatLon(val lat: Double, val lon: Double)

data class RouteResult(
    val points: List<LatLon>,
    val distanceMeters: Double,
    val durationSeconds: Long,
    val instructions: List<TurnInstruction>,
    val etaMs: Long = System.currentTimeMillis() + durationSeconds * 1000L
)

/**
 * Routing repository backed by a pre-built GraphHopper 6.2 graph.
 *
 * The graph is built offline using tools/build_routing_graph.sh and pushed to
 * the device via adb into app-private external storage (files/routing/).
 *
 * Graceful degradation: if no graph is found, [calculateRoute] returns null
 * and [isGraphAvailable] returns false. The rest of the app continues normally.
 */
@Singleton
class RoutingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alprBlocklistDao: ALPRBlocklistDao
) {
    private val tag = "RoutingRepository"

    companion object {
        /** Max iterations for iterative ALPR avoidance. */
        private const val MAX_AVOID_ITERATIONS = 3
        /** Corridor distance: cameras within this many metres of route are "on route". */
        private const val CAMERA_CORRIDOR_M = 100.0
        /** Max camera clusters per block_area hint (GH performance limit). */
        private const val MAX_BLOCK_CLUSTERS = 30
        /** Radius per cluster in block_area string. */
        private const val BLOCK_RADIUS_M = 75.0
    }

    @Volatile private var hopperInstance: GraphHopper? = null
    @Volatile private var loadAttempted = false
    @Volatile var offlineRoutingUnsupported = false  // true when ART lacks required Java APIs
    @Volatile var lastLoadError: String? = null      // human-readable reason for last load failure
    @Volatile private var activeStateCode: String? = null
    private val loadMutex = Mutex()

    /** Candidate graph directories to check in order. */
    private fun candidateGraphDirs(): List<File> = listOfNotNull(
        // App-scoped external storage (no permission needed on Android 10+)
        context.getExternalFilesDir(null)?.resolve("routing"),
        // Internal storage (guaranteed accessible)
        context.filesDir.resolve("routing")
        // Legacy /sdcard/AegisNav/ path removed: files on shared external storage can be
        // read or modified by any other app with storage permission. Use filesDir only.
    )

    /**
     * Returns true if [dir] is a valid GraphHopper graph directory.
     * GraphHopper always writes a "properties" file to the graph folder on first build.
     * Checking for this file is the canonical way to detect a real GH graph dir.
     */
    private fun isValidGraphDir(dir: File): Boolean =
        dir.exists() && dir.isDirectory && File(dir, "properties").exists()


    /**
     * Returns the first populated graph directory, or null if none found.
     *
     * When [activeStateCode] is set, checks `routing/{stateCode}/` first in every
     * candidate root before falling back to the general scan.  This ensures the
     * correct state graph is loaded after the user switches active states.
     *
     * IMPORTANT: `adb push` can create nested paths (e.g. routing/routing/)
     * when the source dir name is appended to the destination.
     * So we must check both the candidate dir AND its immediate subdirectories for the
     * GH `properties` marker file, rather than relying on isNotEmpty().
     */
    private fun graphDir(): File? {
        val stateCode = activeStateCode

        // Priority 1: active state subdir (e.g. routing/FL/ or routing/fl/)
        if (stateCode != null) {
            for (dir in candidateGraphDirs()) {
                // Try both exact case and lowercase variants
                for (variant in listOf(stateCode, stateCode.lowercase(), stateCode.uppercase())) {
                    val stateDir = dir.resolve(variant)
                    if (isValidGraphDir(stateDir)) {
                        AppLog.i(tag, "Graph found for active state '$stateCode' at: ${stateDir.absolutePath}")
                        return stateDir
                    }
                    // One level deeper inside the state subdir (adb push nesting)
                    if (stateDir.exists() && stateDir.isDirectory) {
                        val nested = stateDir.listFiles { f -> f.isDirectory }
                        if (nested != null) {
                            for (sub in nested) {
                                if (isValidGraphDir(sub)) {
                                    AppLog.i(tag, "Graph found (nested) for active state '$stateCode' at: ${sub.absolutePath}")
                                    return sub
                                }
                            }
                        }
                    }
                }
            }
            AppLog.w(tag, "No graph found for active state '$stateCode', falling back to general scan")
        }

        // Priority 2: general scan (original behaviour)
        for (dir in candidateGraphDirs()) {
            // Direct match: candidate dir itself contains the GH properties file
            if (isValidGraphDir(dir)) {
                AppLog.i(tag, "Graph found at: ${dir.absolutePath}")
                return dir
            }
            // One level deeper: handle `adb push` nesting (e.g. routing/routing/)
            if (dir.exists() && dir.isDirectory) {
                val nested = dir.listFiles { f -> f.isDirectory }
                if (nested != null) {
                    for (sub in nested) {
                        if (isValidGraphDir(sub)) {
                            AppLog.i(tag, "Graph found (nested) at: ${sub.absolutePath}")
                            return sub
                        }
                    }
                }
            }
        }
        AppLog.w(tag, "Graph not found. Checked: ${candidateGraphDirs().joinToString { it.absolutePath }}")
        return null
    }

    fun isGraphAvailable(): Boolean = graphDir() != null

    fun expectedGraphPath(): String =
        context.getExternalFilesDir(null)?.resolve("routing")?.absolutePath
            ?: context.filesDir.resolve("routing").absolutePath

    /**
     * Lazily loads the GraphHopper graph on first call (IO thread).
     * Returns null if graph not found or load fails.
     */
    private suspend fun getOrLoadHopper(): GraphHopper? = withContext(Dispatchers.IO) {
        hopperInstance?.let { return@withContext it }
        loadMutex.withLock {
            // Double-check after acquiring lock
            hopperInstance?.let { return@withLock it }
            if (loadAttempted) return@withLock null

            val dir = graphDir() ?: run {
                AppLog.w(tag, "No routing graph found. Expected at: ${expectedGraphPath()}")
                // Do NOT set loadAttempted=true - allow retry if files appear later
                return@withLock null
            }

            return@withLock try {
                AppLog.i(tag, "Loading GraphHopper graph from: ${dir.absolutePath}")
                val hopper = GraphHopper()
                // Use GraphHopperConfig to set MMAP data access before init.
                // Android heap is capped at ~256 MB; the FL graph is ~337 MB on disk.
                // MMAP lets the OS page files in/out without consuming heap space.
                // Log the stored properties so profile mismatches are visible in logcat
                val propsFile = File(dir, "properties")
                if (propsFile.exists()) {
                    AppLog.i(tag, "Graph properties:\n${propsFile.readText().take(1000)}")
                } else {
                    AppLog.w(tag, "No 'properties' file in graph dir - dir may be empty or corrupt")
                }

                val config = GraphHopperConfig()
                    .putObject("graph.location", dir.absolutePath)
                    .putObject("graph.dataaccess", "MMAP")
                    .setProfiles(listOf(
                        Profile("car").setVehicle("car").setWeighting("fastest").setTurnCosts(false),
                        Profile("car_shortest").setVehicle("car").setWeighting("shortest").setTurnCosts(false),
                        Profile("car_avoid_alpr").setVehicle("car").setWeighting("fastest").setTurnCosts(false)
                        // 3 profiles — must match graph's properties file exactly.
                        // car_avoid_alpr uses same fastest weighting; ALPR avoidance applied via edge penalties at query time.
                    ))
                hopper.init(config)
                // Read-only mode - we never write to the graph on device
                hopper.setAllowWrites(false)
                // Do NOT configure CHProfiles - current graph was built without CH preparation.
                // A*/Dijkstra routing works fine without CH; just slightly slower for long routes.
                val loaded = hopper.load()
                if (!loaded) {
                    val reason = "GraphHopper.load() returned false at ${dir.absolutePath} - " +
                        "graph may be corrupt, built with different GH version, or profile mismatch"
                    AppLog.e(tag, reason)
                    lastLoadError = reason
                    // Do NOT set loadAttempted=true here - allow retry after user fixes files.
                    // Do NOT set offlineRoutingUnsupported - this is a file/path error, not an ART API error.
                    return@withLock null
                }
                hopperInstance = hopper
                loadAttempted = true  // Only set true on successful load
                AppLog.i(tag, "GraphHopper graph loaded successfully")
                hopper
            } catch (e: Exception) {
                // File/path errors, IO errors, etc. - allow retry (do not set loadAttempted)
                AppLog.e(tag, "Failed to load GraphHopper graph: ${e.message}", e)
                null
            } catch (e: NoSuchMethodError) {
                // ART is missing a Java API that GH requires (e.g. VarHandle on old Android)
                // This is a permanent device incompatibility - never retry.
                AppLog.e(tag, "GraphHopper incompatible with this device ART [NoSuchMethodError]: ${e.message}")
                loadAttempted = true
                offlineRoutingUnsupported = true
                null
            } catch (e: UnsatisfiedLinkError) {
                // Missing native library or JNI method - permanent device incompatibility.
                AppLog.e(tag, "GraphHopper incompatible with this device [UnsatisfiedLinkError]: ${e.message}")
                loadAttempted = true
                offlineRoutingUnsupported = true
                null
            } catch (e: OutOfMemoryError) {
                // OOM during graph load - do NOT mark as unsupported (may succeed with smaller graph or MMAP)
                AppLog.e(tag, "GraphHopper OOM during load - not marking as unsupported: ${e.message}")
                null
            } catch (e: Error) {
                // All other JVM Errors (StackOverflow, etc.) - allow retry, do not permanently disable
                AppLog.e(tag, "GraphHopper load Error [${e.javaClass.name}]: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }

    fun resetGraphState() {
        hopperInstance = null
        loadAttempted = false
        lastLoadError = null
    }

    /**
     * Switch the active routing state.  Unloads the current graph so the next
     * [calculateRoute] / [calculateAvoidAlprRoute] call picks up the new state's graph.
     */
    fun switchToState(code: String) {
        AppLog.i(tag, "switchToState: $code (was $activeStateCode)")
        activeStateCode = code
        hopperInstance?.close()
        hopperInstance = null
        loadAttempted = false
        lastLoadError = null
    }

    // ── Phase 1.1: Route reference extraction ────────────────────────────────

    /** Matches route refs like I-95, US-1, SR-826, FL-874, A-1A */
    private val refRegex = Regex("""^([A-Z]{1,3}-\d+[A-Z]?)(?:[;,]\s*(.*))?$""")

    /**
     * Extract route reference and plain street name from a GH instruction name.
     * e.g. "I-95" → ("I-95", "")
     *      "US-1;Biscayne Blvd" → ("US-1", "Biscayne Blvd")
     *      "Main Street" → (null, "Main Street")
     */
    internal fun extractGhRef(name: String): Pair<String?, String> {
        if (name.isBlank()) return null to ""
        val m = refRegex.matchEntire(name.trim())
        return if (m != null) {
            val ref = m.groupValues[1]
            val street = m.groupValues[2].trim()
            ref to street
        } else null to name
    }

    /** Combine ref + street into spoken street description. */
    internal fun buildStreetWithRef(street: String, ref: String?): String {
        val hasRef = !ref.isNullOrBlank()
        val hasStreet = street.isNotBlank()
        return when {
            hasRef && hasStreet -> "$ref, $street"
            hasRef              -> ref.orEmpty()
            hasStreet           -> street
            else                -> ""
        }
    }

    /** Returns true for GH sign values representing highway/motorway maneuvers. */
    internal fun isGhHighwayManeuver(sign: Int): Boolean = sign in listOf(-6, 7)

    /**
     * Calculate a route from [from] to [to] using the specified [profile].
     * Profile determines weighting: "car" = fastest, "car_shortest" = shortest distance.
     * Returns null if graph is unavailable or route calculation fails.
     */
    suspend fun calculateRoute(
        from: LatLon,
        to: LatLon,
        profile: String = "car"
    ): RouteResult? = withContext(Dispatchers.IO) {
        AppLog.i(tag, "calculateRoute: profile=$profile")
        val hopper = getOrLoadHopper() ?: run {
            AppLog.w(tag, "calculateRoute: graph not loaded")
            return@withContext null
        }

        return@withContext try {
            val request = GHRequest(from.lat, from.lon, to.lat, to.lon)
                .setProfile(profile)
                .setLocale(Locale.US)
            request.hints.putObject("ch.disable", true)

            var response: GHResponse = hopper.route(request)
            if (response.hasErrors()) {
                // Retry without turn_costs hint
                AppLog.w(tag, "Route failed, retrying: ${response.errors.first().message}")
                val req2 = GHRequest(from.lat, from.lon, to.lat, to.lon)
                    .setProfile(profile).setLocale(Locale.US)
                req2.hints.putObject("ch.disable", true)
                response = hopper.route(req2)
            }
            if (response.hasErrors()) {
                AppLog.w(tag, "Route errors: ${response.errors.joinToString { it.message ?: "unknown" }}")
                return@withContext null
            }

            buildRouteResult(from, response.best)
        }
        catch (e: Exception) {
            AppLog.e(tag, "Route calculation failed: ${e.message}", e)
            null
        } catch (e: NoSuchMethodError) {
            AppLog.e(tag, "Route calculation: ART API missing [NoSuchMethodError] - marking unsupported: ${e.message}")
            hopperInstance = null
            offlineRoutingUnsupported = true
            null
        } catch (e: UnsatisfiedLinkError) {
            AppLog.e(tag, "Route calculation: native link error [UnsatisfiedLinkError] - marking unsupported: ${e.message}")
            hopperInstance = null
            offlineRoutingUnsupported = true
            null
        } catch (t: Throwable) {
            AppLog.e(tag, "Route calculation threw ${t.javaClass.simpleName}: ${t.message}")
            hopperInstance = null
            loadAttempted = false
            null
        }
    }

    /** Build a [RouteResult] from a GraphHopper ResponsePath (Phase 1.1: extracts refs). */
    private fun buildRouteResult(from: LatLon, path: com.graphhopper.ResponsePath): RouteResult {
        val ghPoints = path.points
        val points = (0 until ghPoints.size()).map { i -> LatLon(ghPoints.getLat(i), ghPoints.getLon(i)) }
        val instructions = path.getInstructions().map { instr ->
            val pts = instr.points
            val instrPoint = if (pts.size() > 0) LatLon(pts.getLat(0), pts.getLon(0)) else from
            val rawName = instr.getName() ?: ""
            val (ref, streetName) = extractGhRef(rawName)
            TurnInstruction(
                text = signToText(instr.sign, streetName, ref),
                distanceMeters = instr.distance,
                sign = instr.sign,
                streetName = streetName,
                point = instrPoint,
                ref = ref,
                exitNumber = null,
                isHighwayManeuver = isGhHighwayManeuver(instr.sign)
            )
        }
        val dur = path.time / 1000L
        return RouteResult(points, path.distance, dur, instructions,
            etaMs = System.currentTimeMillis() + dur * 1000L)
    }

    /**
     * Phase 1.1: Convert a GH sign constant + street name (+ optional ref) to spoken text.
     */
    internal fun signToText(sign: Int, streetName: String, ref: String? = null): String {
        val streetPart = buildStreetWithRef(streetName, ref)
        val on = if (streetPart.isNotBlank()) " onto $streetPart" else ""
        return when (sign) {
            -7   -> "Make a U-turn"
            -6   -> "Keep left$on"
            -3   -> "Turn sharp left$on"
            -2   -> "Turn left$on"
            -1   -> "Turn slight left$on"
             0   -> "Continue straight$on"
             1   -> "Turn slight right$on"
             2   -> "Turn right$on"
             3   -> "Turn sharp right$on"
             4   -> "Arrive at destination"
             5   -> "Continue to waypoint"
             6   -> "Enter roundabout$on"
             7   -> "Keep right$on"
            else -> "Continue$on"
        }
    }

    // ── Phase 1.3: Alternative routes ────────────────────────────────────────

    /**
     * Calculate up to 3 alternative routes via GH alternative_route algorithm.
     * Falls back to single primary route on failure.
     */
    suspend fun calculateAlternativeRoutes(
        from: LatLon, to: LatLon, profile: String = "car"
    ): List<RouteResult> = withContext(Dispatchers.IO) {
        AppLog.i(tag, "calculateAlternativeRoutes: profile=$profile")
        val hopper = getOrLoadHopper()
        if (hopper != null) {
            try {
                val request = GHRequest(from.lat, from.lon, to.lat, to.lon)
                    .setProfile(profile).setLocale(Locale.US)
                request.hints.putObject("ch.disable", true)
                request.hints.putObject("algorithm", "alternative_route")
                request.hints.putObject("alternative_route.max_paths", 3)
                val response: GHResponse = hopper.route(request)
                if (!response.hasErrors() && response.all.isNotEmpty()) {
                    val results = response.all.mapNotNull { path ->
                        try { buildRouteResult(from, path) } catch (e: Exception) { AppLog.w(tag, "buildRouteResult failed: ${e.message}"); null }
                    }
                    if (results.isNotEmpty()) return@withContext results
                }
                AppLog.w(tag, "Alternative routes failed, falling back to single route")
            } catch (e: Exception) {
                AppLog.w(tag, "calculateAlternativeRoutes: ${e.message}")
            }
        }
        listOfNotNull(calculateRoute(from, to, profile))
    }



    // ── Phase 1.5: Map matching ──────────────────────────────────────────────

    /**
     * Snap a GPS trace to the road network by chaining routes between consecutive points.
     * Returns null if graph unavailable or trace has fewer than 2 points.
     */
    suspend fun mapMatch(points: List<LatLon>): RouteResult? = withContext(Dispatchers.IO) {
        if (points.size < 2) {
            AppLog.w(tag, "mapMatch: need at least 2 points, got ${points.size}")
            return@withContext null
        }
        val hopper = getOrLoadHopper() ?: run {
            AppLog.w(tag, "mapMatch: graph not loaded")
            return@withContext null
        }
        return@withContext try {
            val allPts = mutableListOf<LatLon>()
            val allInstrs = mutableListOf<TurnInstruction>()
            var totalDist = 0.0
            var totalDur = 0L
            for (i in 0 until points.size - 1) {
                val req = GHRequest(points[i].lat, points[i].lon, points[i+1].lat, points[i+1].lon)
                    .setProfile("car").setLocale(Locale.US)
                req.hints.putObject("ch.disable", true)
                val resp = hopper.route(req)
                if (resp.hasErrors()) continue
                val path = resp.best
                val segPts = (0 until path.points.size()).map { j ->
                    LatLon(path.points.getLat(j), path.points.getLon(j))
                }
                if (allPts.isEmpty()) allPts.addAll(segPts) else allPts.addAll(segPts.drop(1))
                totalDist += path.distance
                totalDur += path.time / 1000L
                if (i == 0) allInstrs.addAll(buildRouteResult(points[i], resp.best).instructions)
            }
            if (allPts.size < 2) return@withContext null
            AppLog.i(tag, "mapMatch: ${points.size} trace pts → ${allPts.size} road pts")
            RouteResult(allPts, totalDist, totalDur, allInstrs,
                etaMs = System.currentTimeMillis() + totalDur * 1000L)
        } catch (e: Exception) {
            AppLog.e(tag, "mapMatch failed: ${e.message}")
            null
        }
    }

    // ── Phase 1.6: ALPR avoidance ────────────────────────────────────────────

    /**
     * Calculate route avoiding ALPR camera locations.
     * Iterative best-effort ALPR avoidance routing.
     *
     * Strategy:
     * 1. Calculate a normal fastest route.
     * 2. Find ALPR cameras within 100m of the route corridor.
     * 3. Cluster those on-route cameras and build block_area hints.
     * 4. Re-route with block_area. If GH fails (cameras unavoidable), fall back
     *    to the previous best route — never returns null if a normal route exists.
     * 5. Repeat up to [MAX_AVOID_ITERATIONS] times to catch cameras on the new route.
     *
     * ALPR cameras ONLY — does NOT avoid speed/red-light cameras.
     */
    suspend fun calculateAvoidAlprRoute(
        from: LatLon, to: LatLon, profile: String = "car"
    ): RouteResult? = withContext(Dispatchers.IO) {
        val allCameras = try {
            alprBlocklistDao.getAll().first().map { LatLon(it.lat, it.lon) }
        } catch (e: Exception) {
            AppLog.w(tag, "Failed to load ALPR cameras: ${e.message}")
            emptyList()
        }

        val hopper = getOrLoadHopper()

        // Step 1: Get a baseline route (fastest)
        var bestRoute = if (hopper != null) {
            try {
                val req = GHRequest(from.lat, from.lon, to.lat, to.lon)
                    .setProfile(profile).setLocale(Locale.US)
                req.hints.putObject("ch.disable", true)
                val resp = hopper.route(req)
                if (!resp.hasErrors()) buildRouteResult(from, resp.best) else null
            } catch (e: Exception) {
                AppLog.w(tag, "calculateAvoidAlprRoute baseline GH error: ${e.message}")
                null
            }
        } else null

        if (bestRoute == null) {
            AppLog.w(tag, "calculateAvoidAlprRoute: local routing failed, returning null")
            return@withContext calculateRoute(from, to, profile)
        }

        if (allCameras.isEmpty()) {
            AppLog.i(tag, "calculateAvoidAlprRoute: no ALPR cameras loaded, returning fastest")
            return@withContext bestRoute
        }

        // Step 2-5: Iterative avoidance
        val alreadyBlocked = mutableSetOf<String>()  // "lat,lon" keys of cameras we've tried blocking
        for (iteration in 1..MAX_AVOID_ITERATIONS) {
            // Find cameras within CAMERA_CORRIDOR_M of the current route
            val routePoints = bestRoute?.points ?: break
            val onRouteCameras = allCameras.filter { cam ->
                val key = "${cam.lat},${cam.lon}"
                key !in alreadyBlocked && routePoints.any { rp -> haversineMeters(cam, rp) <= CAMERA_CORRIDOR_M }
            }

            if (onRouteCameras.isEmpty()) {
                AppLog.i(tag, "calculateAvoidAlprRoute: iteration $iteration — no more cameras on route, done")
                break
            }

            AppLog.i(tag, "calculateAvoidAlprRoute: iteration $iteration — ${onRouteCameras.size} cameras on route")

            // Cluster and build block_area
            val camerasToBlock = onRouteCameras.take(MAX_BLOCK_CLUSTERS)
            val blockStr = buildBlockAreaString(camerasToBlock)
            if (blockStr.isBlank()) break

            // Add to cumulative block set
            camerasToBlock.forEach { alreadyBlocked.add("${it.lat},${it.lon}") }
            val fullBlockStr = buildBlockAreaString(
                alreadyBlocked.map { k ->
                    val parts = k.split(",")
                    LatLon(parts[0].toDouble(), parts[1].toDouble())
                }
            )

            // Try routing with block_area
            if (hopper != null) {
                try {
                    val req = GHRequest(from.lat, from.lon, to.lat, to.lon)
                        .setProfile(profile).setLocale(Locale.US)
                    req.hints.putObject("ch.disable", true)
                    if (fullBlockStr.isNotBlank()) req.hints.putObject("block_area", fullBlockStr)
                    AppLog.d(tag, "calculateAvoidAlprRoute: block_area=$fullBlockStr")
                    val resp = hopper.route(req)
                    if (!resp.hasErrors()) {
                        bestRoute = buildRouteResult(from, resp.best)
                        AppLog.i(tag, "calculateAvoidAlprRoute: iteration $iteration — rerouted avoiding ${alreadyBlocked.size} camera clusters")
                    } else {
                        // GH couldn't route with these blocks — cameras unavoidable, keep previous best
                        val errMsg = resp.errors.joinToString { it.message ?: "unknown" }
                        AppLog.w(tag, "calculateAvoidAlprRoute: iteration $iteration — GH failed: $errMsg, keeping previous route (best effort)")
                        break
                    }
                } catch (e: Exception) {
                    AppLog.w(tag, "calculateAvoidAlprRoute: iteration $iteration — GH exception: ${e.message}, keeping previous route")
                    break
                }
            }
        }

        // Count remaining cameras on final route for logging
        val finalOnRoute = bestRoute?.let { route ->
            allCameras.count { cam -> route.points.any { rp -> haversineMeters(cam, rp) <= CAMERA_CORRIDOR_M } }
        } ?: 0
        AppLog.i(tag, "calculateAvoidAlprRoute: final route has $finalOnRoute cameras within ${CAMERA_CORRIDOR_M.toInt()}m corridor (blocked ${alreadyBlocked.size} clusters)")

        bestRoute
    }

    // ── Helpers: block_area, haversine ────────

    /**
     * Build GH block_area string: clusters nearby cameras (within 100m), max [MAX_BLOCK_CLUSTERS],
     * [BLOCK_RADIUS_M] radius each.
     */
    internal fun buildBlockAreaString(cameraPositions: List<LatLon>): String {
        if (cameraPositions.isEmpty()) return ""
        val clusterDistM = 100.0
        val clusters = mutableListOf<LatLon>()
        for (cam in cameraPositions) {
            if (clusters.none { haversineMeters(it, cam) <= clusterDistM }) clusters.add(cam)
            if (clusters.size >= MAX_BLOCK_CLUSTERS) break
        }
        return clusters.joinToString(";") { c -> "${c.lat},${c.lon},${BLOCK_RADIUS_M.toInt()}" }
    }



    internal fun haversineMeters(a: LatLon, b: LatLon): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val s1 = sin(dLat / 2); val s2 = sin(dLon / 2)
        return 2 * r * asin(sqrt(s1*s1 + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * s2*s2))
    }
}
