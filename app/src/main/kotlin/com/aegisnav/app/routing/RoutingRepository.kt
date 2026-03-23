package com.aegisnav.app.routing

import android.content.Context
import com.aegisnav.app.data.dao.ALPRBlocklistDao
import com.aegisnav.app.data.dao.RedLightCameraDao
import com.aegisnav.app.data.dao.SpeedCameraDao

import com.aegisnav.app.util.AppLog
import com.graphhopper.GHRequest
import com.graphhopper.GHResponse
import com.graphhopper.GraphHopper
import com.graphhopper.GraphHopperConfig
import com.graphhopper.config.Profile
import com.graphhopper.json.Statement
import com.graphhopper.util.CustomModel
import com.graphhopper.util.JsonFeature
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.util.GeometricShapeFactory
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

data class LatLon(val lat: Double, val lon: Double)

/**
 * Returns true if a U-turn instruction (sign == -7) appears to be on a highway,
 * based on its own ref field or adjacent instructions' highway context.
 */
internal fun isHighwayUTurn(index: Int, instructions: List<TurnInstruction>): Boolean {
    val instr = instructions.getOrNull(index) ?: return false
    if (instr.sign != -7) return false
    if (instr.ref != null) return true
    val prev = instructions.getOrNull(index - 1)
    val next = instructions.getOrNull(index + 1)
    return (prev?.isHighwayManeuver == true || prev?.ref != null ||
            next?.isHighwayManeuver == true || next?.ref != null)
}

/**
 * Post-processes a list of [TurnInstruction]s and replaces highway U-turns
 * (sign == -7 near highway context) with a "Take the next exit to reroute" instruction.
 */
internal fun suppressHighwayUTurns(instructions: List<TurnInstruction>): List<TurnInstruction> =
    instructions.mapIndexed { index, instr ->
        if (isHighwayUTurn(index, instructions)) {
            instr.copy(text = "Take the next exit to reroute", sign = 0)
        } else {
            instr
        }
    }

data class RouteResult(
    val points: List<LatLon>,
    val distanceMeters: Double,
    val durationSeconds: Long,
    val instructions: List<TurnInstruction>,
    val etaMs: Long = System.currentTimeMillis() + durationSeconds * 1000L,
    /** Speed limits in mph, one per route geometry point (null = unknown). */
    val speedLimitsMph: List<Int?> = emptyList()
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
    private val alprBlocklistDao: ALPRBlocklistDao,
    private val speedCameraDao: SpeedCameraDao,
    private val redLightCameraDao: RedLightCameraDao,
    private val crashReporter: com.aegisnav.app.crash.CrashReporter
) {
    private val tag = "RoutingRepository"

    companion object {
        /** Max iterations for iterative ALPR avoidance. */
        private const val MAX_AVOID_ITERATIONS = 4
        /** Corridor distance: cameras within this many metres of route are "on route". */
        private const val CAMERA_CORRIDOR_M = 125.0
        /** Max camera clusters per block_area hint (GH performance limit). */
        private const val MAX_BLOCK_CLUSTERS = 30
        /** Radius per cluster in block_area string — large enough GH can't sneak through. */
        private const val BLOCK_RADIUS_M = 80.0
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
                        Profile("car").setVehicle("car").setWeighting("fastest").setTurnCosts(true),
                        Profile("car_shortest").setVehicle("car").setWeighting("shortest").setTurnCosts(true),
                        Profile("car_avoid_alpr").setVehicle("car").setWeighting("fastest").setTurnCosts(true)
                        // 3 profiles — must match graph's properties file exactly.
                        // turn_costs=true enables U-turn penalties and restricted-turn enforcement.
                        // car_avoid_alpr uses same fastest weighting; ALPR avoidance applied via edge penalties at query time.
                    ))
                hopper.init(config)
                // Read-only mode - we never write to the graph on device
                hopper.setAllowWrites(false)
                // CH (Contraction Hierarchies) enabled for fast route queries.
                // ALPR avoidance and alternative routes disable CH at query time (block_area/alt_route incompatible).
                val loaded = hopper.load()
                if (!loaded) {
                    val reason = "GraphHopper.load() returned false at ${dir.absolutePath} - " +
                        "graph may be corrupt, built with different GH version, or profile mismatch"
                    AppLog.e(tag, reason)
                    lastLoadError = reason
                    crashReporter.captureMessage("GraphHopper load FAILED: $reason")
                    // Do NOT set loadAttempted=true here - allow retry after user fixes files.
                    // Do NOT set offlineRoutingUnsupported - this is a file/path error, not an ART API error.
                    return@withLock null
                }
                hopperInstance = hopper
                loadAttempted = true  // Only set true on successful load
                AppLog.i(tag, "GraphHopper graph loaded successfully")
                crashReporter.captureMessage("GraphHopper loaded: ${dir.absolutePath} profiles=car")
                hopper
            } catch (e: Exception) {
                // File/path errors, IO errors, etc. - allow retry (do not set loadAttempted)
                AppLog.e(tag, "Failed to load GraphHopper graph: ${e.message}", e)
                crashReporter.captureMessage("GraphHopper load FAILED: ${e.message}")
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
    internal fun isGhHighwayManeuver(sign: Int): Boolean = sign in listOf(-6, 7, 8)

    /**
     * Calculate a route from [from] to [to] using the specified [profile].
     * Profile determines weighting: "car" = fastest, "car_shortest" = shortest distance.
     * When [headingDegrees] is provided, sets a heading hint so GraphHopper prefers
     * routes that go forward from the user's current bearing (avoids U-turns on reroute).
     * Optional [waypoints] list adds intermediate via-points to the route (multi-stop).
     * When [avoidHighways] is true, applies a custom_model hint to deprioritize motorways.
     * Returns null if graph is unavailable or route calculation fails.
     */
    suspend fun calculateRoute(
        from: LatLon,
        to: LatLon,
        profile: String = "car",
        headingDegrees: Float? = null,
        waypoints: List<LatLon> = emptyList(),
        avoidHighways: Boolean = false
    ): RouteResult? = withContext(Dispatchers.IO) {
        AppLog.i(tag, "calculateRoute: profile=$profile heading=$headingDegrees waypoints=${waypoints.size} avoidHighways=$avoidHighways")
        val hopper = getOrLoadHopper() ?: run {
            AppLog.w(tag, "calculateRoute: graph not loaded")
            return@withContext null
        }

        return@withContext try {
            val request = buildGHRequest(from, to, profile, waypoints)
            request.setPathDetails(listOf("max_speed"))
            if (headingDegrees != null && waypoints.isEmpty()) {
                request.setHeadings(listOf(headingDegrees.toDouble()))
            }
            if (avoidHighways) {
                applyAvoidHighwaysHint(request)
            }

            var response: GHResponse = hopper.route(request)
            if (response.hasErrors()) {
                // Retry without heading hint
                AppLog.w(tag, "Route failed, retrying: ${response.errors.first().message}")
                val req2 = buildGHRequest(from, to, profile, waypoints)
                req2.setPathDetails(listOf("max_speed"))
                if (avoidHighways) applyAvoidHighwaysHint(req2)
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

    /**
     * Build a [GHRequest] with optional via-points for multi-stop routing.
     * GH 6.2 supports multiple points natively via addPoint().
     */
    private fun buildGHRequest(
        from: LatLon,
        to: LatLon,
        profile: String,
        waypoints: List<LatLon>
    ): GHRequest {
        return if (waypoints.isEmpty()) {
            GHRequest(from.lat, from.lon, to.lat, to.lon).setProfile(profile).setLocale(Locale.US)
        } else {
            val request = GHRequest()
            request.setProfile(profile)
            request.setLocale(Locale.US)
            request.addPoint(com.graphhopper.util.shapes.GHPoint(from.lat, from.lon))
            waypoints.forEach { request.addPoint(com.graphhopper.util.shapes.GHPoint(it.lat, it.lon)) }
            request.addPoint(com.graphhopper.util.shapes.GHPoint(to.lat, to.lon))
            request
        }
    }

    /**
     * Apply avoid-highways hint to a [GHRequest] using GraphHopper custom_model.
     * Assigns priority 0 to road segments classified as MOTORWAY.
     * Requires CH to be disabled (custom_model is flex-mode only).
     */
    private fun applyAvoidHighwaysHint(request: GHRequest) {
        request.hints.putObject("ch.disable", true)
        // Custom model JSON: deprioritize MOTORWAY class roads to effectively avoid highways
        val customModel = """{"priority":[{"if":"road_class == MOTORWAY","multiply_by":"0.0"}]}"""
        request.hints.putObject("custom_model", customModel)
        AppLog.d(tag, "applyAvoidHighwaysHint: custom_model=$customModel")
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
            // Extract roundabout exit number if this is a RoundaboutInstruction
            val exitNum = (instr as? com.graphhopper.util.RoundaboutInstruction)
                ?.getExitNumber()
                ?.takeIf { it > 0 }
                ?.toString()
            TurnInstruction(
                text = signToText(instr.sign, streetName, ref),
                distanceMeters = instr.distance,
                sign = instr.sign,
                streetName = streetName,
                point = instrPoint,
                ref = ref,
                exitNumber = exitNum,
                isHighwayManeuver = isGhHighwayManeuver(instr.sign),
                durationMillis = instr.getTime()
            )
        }
        val filteredInstructions = suppressHighwayUTurns(instructions)
        val dur = path.time / 1000L

        // Extract max_speed path details → per-point speed limits in mph
        val speedLimitsMph: List<Int?> = try {
            val details = path.pathDetails["max_speed"]
            if (details != null && points.isNotEmpty()) {
                val perPoint = arrayOfNulls<Int>(points.size)
                for (pd in details) {
                    val kmh = (pd.value as? Number)?.toDouble() ?: continue
                    if (kmh <= 0) continue
                    val mph = (kmh * 0.621371).toInt()
                    val first = pd.first.coerceIn(0, points.size - 1)
                    val last = pd.last.coerceIn(0, points.size - 1)
                    for (i in first..last) perPoint[i] = mph
                }
                perPoint.toList()
            } else List(points.size) { null }
        } catch (e: Exception) {
            AppLog.w(tag, "max_speed path detail parse failed: ${e.message}")
            List(points.size) { null }
        }

        return RouteResult(points, path.distance, dur, filteredInstructions,
            etaMs = System.currentTimeMillis() + dur * 1000L,
            speedLimitsMph = speedLimitsMph)
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
             8   -> "Keep right$on"
            else -> "Continue$on"
        }
    }

    // ── Phase 1.3: Alternative routes ────────────────────────────────────────

    /**
     * Calculate up to 3 alternative routes via GH alternative_route algorithm.
     * Falls back to single primary route on failure.
     * When [waypoints] are provided, alternative routing is skipped (GH doesn't support
     * alt_route with via-points) and a single route is returned.
     * When [avoidHighways] is true, applies motorway-avoidance custom_model hint.
     */
    suspend fun calculateAlternativeRoutes(
        from: LatLon,
        to: LatLon,
        profile: String = "car",
        waypoints: List<LatLon> = emptyList(),
        avoidHighways: Boolean = false
    ): List<RouteResult> = withContext(Dispatchers.IO) {
        AppLog.i(tag, "calculateAlternativeRoutes: profile=$profile waypoints=${waypoints.size} avoidHighways=$avoidHighways")

        // Alternative routes + via-points are not compatible with GH's alt_route algorithm
        if (waypoints.isNotEmpty()) {
            AppLog.i(tag, "calculateAlternativeRoutes: waypoints present, returning single multi-stop route")
            return@withContext listOfNotNull(calculateRoute(from, to, profile, null, waypoints, avoidHighways))
        }

        val hopper = getOrLoadHopper()
        if (hopper != null) {
            try {
                val request = GHRequest(from.lat, from.lon, to.lat, to.lon)
                    .setProfile(profile).setLocale(Locale.US)
                request.hints.putObject("ch.disable", true)
                request.hints.putObject("algorithm", "alternative_route")
                request.hints.putObject("alternative_route.max_paths", 3)
                if (avoidHighways) applyAvoidHighwaysHint(request)
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
        listOfNotNull(calculateRoute(from, to, profile, null, emptyList(), avoidHighways))
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
     * Create a circular JTS polygon geometry centred at ([lat], [lon]) with [radiusM] metres radius.
     * Uses a 16-point polygon approximation. JTS coordinate order is (lon, lat) — i.e. (x, y).
     * The radius is converted from metres to approximate degrees (1° ≈ 111 000 m).
     */
    private fun createCircleGeometry(lat: Double, lon: Double, radiusM: Double): org.locationtech.jts.geom.Geometry {
        val shapeFactory = GeometricShapeFactory()
        val radiusDeg = radiusM / 111_000.0
        shapeFactory.setCentre(Coordinate(lon, lat))  // JTS uses x=lon, y=lat
        shapeFactory.setWidth(radiusDeg * 2)
        shapeFactory.setHeight(radiusDeg * 2)
        shapeFactory.setNumPoints(16)
        return shapeFactory.createCircle()
    }

    /**
     * Calculate route avoiding ALPR, speed, and red-light cameras aggressively.
     * Iterative best-of-all routing: tracks the route with fewest cameras across all iterations.
     *
     * Strategy:
     * 1. Load ALL camera types (ALPR + speed + red-light) into a combined set.
     * 2. Calculate a normal fastest baseline route.
     * 3. Find cameras within [CAMERA_CORRIDOR_M] of the route.
     * 4. Cluster on-route cameras (max [MAX_BLOCK_CLUSTERS]) and build a GraphHopper
     *    [CustomModel] with named geographic areas and a priority penalty that makes
     *    routing through camera areas progressively more expensive:
     *      - Iteration 1: multiply priority by 0.1   (10×  penalty — soft avoidance)
     *      - Iteration 2: multiply priority by 0.01  (100× penalty — strong avoidance)
     *      - Iteration 3: multiply priority by 0.001 (1000× penalty — near-blocking)
     *      - Iteration 4: fall back to block_area     (complete block, last resort)
     *    CustomModel is preferred over block_area because it keeps roads accessible when
     *    no alternative exists, avoiding "route not found" failures.
     * 5. If CustomModel throws an exception, the iteration falls back to block_area automatically.
     * 6. Track the route with fewest cameras across ALL iterations; stop early on zero cameras.
     * 7. Return the route with the absolute fewest cameras, not just the last one.
     *
     * [ch.disable] = true is required for both CustomModel and block_area (flex-mode only).
     * Never returns null if a normal route exists.
     */
    suspend fun calculateAvoidAlprRoute(
        from: LatLon, to: LatLon, profile: String = "car", waypoints: List<LatLon> = emptyList()
    ): RouteResult? = withContext(Dispatchers.IO) {
        // Load ALL camera types for maximum avoidance
        val alprCameras = try {
            alprBlocklistDao.getAll().first().map { LatLon(it.lat, it.lon) }
        } catch (e: Exception) {
            AppLog.w(tag, "Failed to load ALPR cameras: ${e.message}")
            emptyList()
        }
        val speedCameras = try {
            speedCameraDao.getAll().first().map { LatLon(it.lat, it.lon) }
        } catch (e: Exception) {
            AppLog.w(tag, "Failed to load speed cameras: ${e.message}")
            emptyList()
        }
        val redLightCameras = try {
            redLightCameraDao.getAll().first().map { LatLon(it.lat, it.lon) }
        } catch (e: Exception) {
            AppLog.w(tag, "Failed to load red-light cameras: ${e.message}")
            emptyList()
        }
        val allCameras = (alprCameras + speedCameras + redLightCameras).distinctBy { "${it.lat},${it.lon}" }
        AppLog.i(tag, "calculateAvoidAlprRoute: loaded ${alprCameras.size} ALPR + ${speedCameras.size} speed + ${redLightCameras.size} red-light = ${allCameras.size} total cameras")

        val hopper = getOrLoadHopper()

        // For multi-stop routes, fall back to segment-by-segment avoidance.
        // CustomModel with via-points is not reliably supported in GH 6.2.
        if (waypoints.isNotEmpty()) {
            return@withContext calculateSegmentedAvoidRoute(from, to, profile, waypoints, allCameras, hopper)
        }

        // Step 1: Get a baseline route (fastest, CH disabled for flex-mode)
        val baselineRoute = if (hopper != null) {
            try {
                val req = buildGHRequest(from, to, profile, emptyList())
                req.hints.putObject("ch.disable", true)
                val resp = hopper.route(req)
                if (!resp.hasErrors()) buildRouteResult(from, resp.best) else null
            } catch (e: Exception) {
                AppLog.w(tag, "calculateAvoidAlprRoute baseline GH error: ${e.message}")
                null
            }
        } else null

        if (baselineRoute == null) {
            AppLog.w(tag, "calculateAvoidAlprRoute: local routing failed, returning null")
            return@withContext calculateRoute(from, to, profile)
        }

        if (allCameras.isEmpty()) {
            AppLog.i(tag, "calculateAvoidAlprRoute: no cameras loaded, returning fastest")
            return@withContext baselineRoute
        }

        // Track best route across all iterations (fewest cameras wins)
        var bestRoute: RouteResult = baselineRoute
        var bestCameraCount = countCamerasOnRoute(baselineRoute, allCameras, CAMERA_CORRIDOR_M)
        AppLog.i(tag, "calculateAvoidAlprRoute: baseline has $bestCameraCount cameras on route")

        if (bestCameraCount == 0) {
            AppLog.i(tag, "calculateAvoidAlprRoute: baseline already camera-free, done")
            return@withContext bestRoute
        }

        // Cumulative set of camera keys blocked across iterations (for block_area fallback)
        val alreadyBlocked = mutableSetOf<String>()  // "lat,lon" keys

        // Reduce iterations for long routes (>20 miles / 32186m) to save time
        val iterationLimit = if (bestRoute.distanceMeters > 32186.0) 3 else MAX_AVOID_ITERATIONS
        AppLog.i(tag, "calculateAvoidAlprRoute: iterationLimit=$iterationLimit (route=${bestRoute.distanceMeters.toInt()}m)")

        for (iteration in 1..iterationLimit) {
            // Find cameras within CAMERA_CORRIDOR_M of the current best route not yet blocked
            val routePoints = bestRoute.points
            val onRouteCameras = allCameras.filter { cam ->
                val key = "${cam.lat},${cam.lon}"
                key !in alreadyBlocked && routePoints.any { rp -> haversineMeters(cam, rp) <= CAMERA_CORRIDOR_M }
            }

            if (onRouteCameras.isEmpty()) {
                AppLog.i(tag, "calculateAvoidAlprRoute: iteration $iteration — no new cameras to block, done")
                break
            }

            AppLog.i(tag, "calculateAvoidAlprRoute: iteration $iteration — ${onRouteCameras.size} new cameras to block")

            // Add new cameras to cumulative blocked set (capped at MAX_BLOCK_CLUSTERS total)
            val camerasToBlock = onRouteCameras.take(MAX_BLOCK_CLUSTERS)
            camerasToBlock.forEach { alreadyBlocked.add("${it.lat},${it.lon}") }

            if (hopper == null) break

            // Determine penalty for this iteration:
            // iterations 1–3 use CustomModel penalties; iteration 4 falls back to block_area
            val penalty: String? = when (iteration) {
                1    -> "0.1"    // 10× more expensive — soft avoidance
                2    -> "0.01"   // 100× — strong avoidance
                3    -> "0.001"  // 1000× — near-blocking
                else -> null     // null → use block_area fallback
            }

            val candidateRoute: RouteResult? = if (penalty != null) {
                // ── CustomModel penalty approach ──────────────────────────────
                // Build named circular areas for each clustered camera, then apply
                // a single priority penalty for any area match.  This is preferred
                // over block_area because it keeps roads routable when no detour exists.
                try {
                    val allBlockedCams = alreadyBlocked.map { k ->
                        val parts = k.split(",")
                        LatLon(parts[0].toDouble(), parts[1].toDouble())
                    }
                    // Cluster nearby cameras so we don't exceed condition-string limits
                    val cameraClusters = buildCameraClusterList(allBlockedCams)

                    val cameraAreas = mutableMapOf<String, JsonFeature>()
                    val areaNames = mutableListOf<String>()

                    cameraClusters.forEachIndexed { i, cam ->
                        val areaId = "cam_$i"
                        val geom = createCircleGeometry(cam.lat, cam.lon, BLOCK_RADIUS_M)
                        cameraAreas[areaId] = JsonFeature(areaId, "Feature", null, geom, null)
                        areaNames.add("in_$areaId")
                    }

                    val customModel = CustomModel()
                    customModel.setAreas(cameraAreas)
                    // Condition: "in_cam_0 || in_cam_1 || ..."
                    val condition = areaNames.joinToString(" || ")
                    customModel.addToPriority(Statement.If(condition, Statement.Op.MULTIPLY, penalty))

                    val req = buildGHRequest(from, to, profile, emptyList())
                    req.hints.putObject("ch.disable", true)
                    req.setCustomModel(customModel)
                    AppLog.d(tag, "calculateAvoidAlprRoute: iter=$iteration CustomModel penalty=$penalty areas=${cameraClusters.size}")

                    val resp = hopper.route(req)
                    if (!resp.hasErrors()) {
                        buildRouteResult(from, resp.best)
                    } else {
                        val errMsg = resp.errors.joinToString { it.message ?: "unknown" }
                        AppLog.w(tag, "calculateAvoidAlprRoute: iter=$iteration CustomModel GH failed: $errMsg — falling back to block_area")
                        // Fall through to block_area below
                        routeWithBlockArea(hopper, from, to, profile, alreadyBlocked)
                    }
                } catch (e: Exception) {
                    AppLog.w(tag, "calculateAvoidAlprRoute: iter=$iteration CustomModel exception: ${e.message} — falling back to block_area")
                    routeWithBlockArea(hopper, from, to, profile, alreadyBlocked)
                }
            } else {
                // ── block_area fallback (iteration 4 or penalty==null) ────────
                AppLog.i(tag, "calculateAvoidAlprRoute: iter=$iteration using block_area fallback")
                routeWithBlockArea(hopper, from, to, profile, alreadyBlocked)
            }

            if (candidateRoute != null) {
                val camerasOnCandidate = countCamerasOnRoute(candidateRoute, allCameras, CAMERA_CORRIDOR_M)
                AppLog.i(tag, "calculateAvoidAlprRoute: iteration $iteration — candidate has $camerasOnCandidate cameras (best so far: $bestCameraCount)")
                if (camerasOnCandidate < bestCameraCount) {
                    bestCameraCount = camerasOnCandidate
                    bestRoute = candidateRoute
                    AppLog.i(tag, "calculateAvoidAlprRoute: iteration $iteration — new best route! $bestCameraCount cameras")
                }
                if (bestCameraCount == 0) {
                    AppLog.i(tag, "calculateAvoidAlprRoute: iteration $iteration — zero cameras achieved, done")
                    break
                }
            } else {
                // GH couldn't route — cameras unavoidable, keep best-so-far
                AppLog.w(tag, "calculateAvoidAlprRoute: iteration $iteration — no route found, keeping best route (best effort)")
                break
            }
        }

        AppLog.i(tag, "calculateAvoidAlprRoute: returning best route with $bestCameraCount cameras within ${CAMERA_CORRIDOR_M.toInt()}m corridor (blocked ${alreadyBlocked.size} camera clusters)")
        bestRoute
    }

    /**
     * Route using the traditional [block_area] hint (complete road exclusion).
     * Used as fallback when CustomModel fails or on the final iteration.
     * Returns null if GH fails to find a route.
     */
    private fun routeWithBlockArea(
        hopper: GraphHopper,
        from: LatLon,
        to: LatLon,
        profile: String,
        alreadyBlocked: Set<String>
    ): RouteResult? {
        val blockedCams = alreadyBlocked.map { k ->
            val parts = k.split(",")
            LatLon(parts[0].toDouble(), parts[1].toDouble())
        }
        val blockStr = buildBlockAreaString(blockedCams)
        if (blockStr.isBlank()) return null
        return try {
            val req = buildGHRequest(from, to, profile, emptyList())
            req.hints.putObject("ch.disable", true)
            req.hints.putObject("block_area", blockStr)
            AppLog.d(tag, "routeWithBlockArea: block_area=$blockStr")
            val resp = hopper.route(req)
            if (!resp.hasErrors()) buildRouteResult(from, resp.best) else {
                val errMsg = resp.errors.joinToString { it.message ?: "unknown" }
                AppLog.w(tag, "routeWithBlockArea: GH failed: $errMsg")
                null
            }
        } catch (e: Exception) {
            AppLog.w(tag, "routeWithBlockArea: exception: ${e.message}")
            null
        }
    }

    /**
     * Cluster a list of camera positions so nearby cameras share a single area circle.
     * Returns at most [MAX_BLOCK_CLUSTERS] representative cluster centres.
     */
    private fun buildCameraClusterList(cameraPositions: List<LatLon>): List<LatLon> {
        val clusterDistM = 100.0
        val clusters = mutableListOf<LatLon>()
        for (cam in cameraPositions) {
            if (clusters.none { haversineMeters(it, cam) <= clusterDistM }) clusters.add(cam)
            if (clusters.size >= MAX_BLOCK_CLUSTERS) break
        }
        return clusters
    }

    /**
     * Count cameras within [corridorM] metres of any point on [route].
     */
    private fun countCamerasOnRoute(route: RouteResult, cameras: List<LatLon>, corridorM: Double): Int =
        cameras.count { cam -> route.points.any { rp -> haversineMeters(cam, rp) <= corridorM } }

    /**
     * Segmented ALPR avoidance for multi-stop routes.
     * Splits the route into segments (from→wp1, wp1→wp2, ..., wpN→to),
     * runs avoidance on each segment independently, then merges results.
     */
    private suspend fun calculateSegmentedAvoidRoute(
        from: LatLon, to: LatLon, profile: String,
        waypoints: List<LatLon>, allCameras: List<LatLon>,
        hopper: GraphHopper?
    ): RouteResult? {
        val stops = listOf(from) + waypoints + listOf(to)
        AppLog.i(tag, "calculateSegmentedAvoidRoute: ${stops.size} stops, ${waypoints.size} waypoints")
        val segmentResults = mutableListOf<RouteResult>()
        
        for (i in 0 until stops.size - 1) {
            val segFrom = stops[i]
            val segTo = stops[i + 1]
            // Calculate avoid route for this segment (no waypoints — direct)
            val segResult = calculateAvoidAlprRoute(segFrom, segTo, profile, emptyList())
                ?: calculateRoute(segFrom, segTo, profile) // fallback to fastest if avoidance fails
                ?: return null
            segmentResults.add(segResult)
        }
        
        // Merge all segments into one RouteResult
        val allPoints = mutableListOf<LatLon>()
        val allInstructions = mutableListOf<com.aegisnav.app.routing.TurnInstruction>()
        var totalDistance = 0.0
        var totalDuration = 0L
        
        segmentResults.forEachIndexed { idx, seg ->
            if (idx == 0) allPoints.addAll(seg.points)
            else allPoints.addAll(seg.points.drop(1)) // skip duplicate start point
            allInstructions.addAll(seg.instructions)
            totalDistance += seg.distanceMeters
            totalDuration += seg.durationSeconds
        }
        
        return RouteResult(
            points = allPoints,
            instructions = allInstructions,
            distanceMeters = totalDistance,
            durationSeconds = totalDuration,
            speedLimitsMph = segmentResults.flatMap { it.speedLimitsMph }
        )
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
