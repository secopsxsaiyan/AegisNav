package com.aegisnav.app.routing

import android.content.Context
import android.speech.tts.TextToSpeech
import com.aegisnav.app.util.AppLog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import com.aegisnav.app.LocationRepository
import com.aegisnav.app.data.dao.ALPRBlocklistDao
import com.aegisnav.app.data.dao.RedLightCameraDao
import com.aegisnav.app.data.dao.SpeedCameraDao
import com.aegisnav.app.data.model.SavedLocation
import com.aegisnav.app.data.repository.AppPreferencesRepository
import com.aegisnav.app.data.repository.SavedLocationRepository
import com.aegisnav.app.geocoder.OfflineGeocoderRepository
import com.aegisnav.app.geocoder.OfflineGeocoderResult

import com.aegisnav.app.security.SecureDataStore
import com.aegisnav.app.security.readBlocking
import com.aegisnav.app.security.editBlocking
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aegisnav.app.util.AlertTtsManager
import com.aegisnav.app.util.TtsCategory
import kotlin.math.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

data class RecentSearch(val name: String, val lat: Double, val lon: Double)

/**
 * Full turn-by-turn navigation ViewModel.
 *
 * - Loads route via RoutingRepository (GraphHopper offline graph)
 * - Tracks location from LocationRepository and advances instructions
 * - Speaks instructions via Android TTS (no external library)
 * - Gracefully degrades if graph unavailable
 */
@HiltViewModel
class NavigationViewModel @Inject constructor(
    private val routingRepository: RoutingRepository,
    private val locationRepository: LocationRepository,
    private val appPrefs: AppPreferencesRepository,
    private val offlineGeocoder: OfflineGeocoderRepository,
    private val savedLocationRepository: SavedLocationRepository,
    private val alprBlocklistDao: ALPRBlocklistDao,
    private val redLightCameraDao: RedLightCameraDao,
    private val speedCameraDao: SpeedCameraDao,
    private val alertTtsManager: AlertTtsManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val tag = "NavigationViewModel"

    private fun profileFor(preference: RoutePreference): String = when (preference) {
        RoutePreference.FASTEST           -> "car"
        RoutePreference.SHORTEST_DISTANCE -> "car_shortest"
        RoutePreference.AVOID_ALPR        -> "car_avoid_alpr"
    }

    // ── Recent searches (DataStore) ──────────────────────────────────────────

    private val navDataStore: DataStore<Preferences> =
        SecureDataStore.get(context, "nav_prefs")

    private val _recentSearches = MutableStateFlow<List<RecentSearch>>(loadRecentSearches())
    val recentSearches: StateFlow<List<RecentSearch>> = _recentSearches.asStateFlow()

    private fun loadRecentSearches(): List<RecentSearch> {
        // TODO(phase-refactor): Convert to Flow-based async read
        val json = navDataStore.readBlocking()[stringPreferencesKey("recent_searches")] ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                RecentSearch(obj.getString("name"), obj.getDouble("lat"), obj.getDouble("lon"))
            }
        }.getOrElse { emptyList() }
    }

    fun addRecentSearch(name: String, lat: Double, lon: Double) {
        val current = _recentSearches.value.toMutableList()
        current.removeAll { it.name == name && it.lat == lat && it.lon == lon }
        current.add(0, RecentSearch(name, lat, lon))
        val capped = current.take(10)
        _recentSearches.value = capped
        val arr = JSONArray()
        capped.forEach { r ->
            arr.put(JSONObject().apply {
                put("name", r.name)
                put("lat", r.lat)
                put("lon", r.lon)
            })
        }
        navDataStore.editBlocking { this[stringPreferencesKey("recent_searches")] = arr.toString() }
    }

    fun removeRecentSearch(name: String, lat: Double, lon: Double) {
        val current = _recentSearches.value.toMutableList()
        current.removeAll { it.name == name && it.lat == lat && it.lon == lon }
        _recentSearches.value = current
        val arr = JSONArray()
        current.forEach { r ->
            arr.put(JSONObject().apply {
                put("name", r.name)
                put("lat", r.lat)
                put("lon", r.lon)
            })
        }
        navDataStore.editBlocking { this[stringPreferencesKey("recent_searches")] = arr.toString() }
    }

    // ── Saved locations (Room) ─────────────────────────────────────────────────

    val savedLocations: StateFlow<List<SavedLocation>> = savedLocationRepository
        .getAllNewestFirst()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun saveLocation(name: String, lat: Double, lon: Double, type: String) {
        viewModelScope.launch {
            // For preset types, remove previous entry of same type first
            if (type != "CUSTOM") {
                savedLocationRepository.deleteByType(type)
            }
            savedLocationRepository.insert(SavedLocation(name = name, lat = lat, lon = lon, type = type))
        }
    }

    fun deleteLocation(id: Int) {
        viewModelScope.launch { savedLocationRepository.deleteById(id) }
    }

    fun getLocationByType(type: String): SavedLocation? =
        savedLocations.value.firstOrNull { it.type == type }

    // ── Public state ───────────────────────────────────────────────────────────

    private val _routePoints = MutableStateFlow<List<LatLon>?>(null)
    val routePoints: StateFlow<List<LatLon>?> = _routePoints.asStateFlow()

    private val _currentInstruction = MutableStateFlow<TurnInstruction?>(null)
    val currentInstruction: StateFlow<TurnInstruction?> = _currentInstruction.asStateFlow()

    private val _distanceToNext = MutableStateFlow(0.0)
    val distanceToNext: StateFlow<Double> = _distanceToNext.asStateFlow()

    private val _isNavigating = MutableStateFlow(false)
    val isNavigating: StateFlow<Boolean> = _isNavigating.asStateFlow()

    private val _routingAvailable = MutableStateFlow(routingRepository.isGraphAvailable())
    val routingAvailable: StateFlow<Boolean> = _routingAvailable.asStateFlow()

    /** Exposes offline mode flag for UI to skip geocoder when offline. */
    val offlineMode: StateFlow<Boolean> = appPrefs.offlineMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * True when routing is possible via any method:
     *  - offline graph loaded, OR
     *  - online mode is active (OSRM available when not in offline mode)
     */
    val canRoute: StateFlow<Boolean> = combine(
        _routingAvailable,
        appPrefs.offlineMode
    ) { graphAvailable, offline ->
        graphAvailable || !offline   // graph available OR online mode active
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _routeResult = MutableStateFlow<RouteResult?>(null)
    val routeResult: StateFlow<RouteResult?> = _routeResult.asStateFlow()

    // ── Phase 1.3: Alternative routes ─────────────────────────────────────────
    private val _alternativeRoutes = MutableStateFlow<List<RouteResult>>(emptyList())
    val alternativeRoutes: StateFlow<List<RouteResult>> = _alternativeRoutes.asStateFlow()

    /** Select an alternative route by index from [alternativeRoutes]. */
    fun selectAlternativeRoute(index: Int) {
        val routes = _alternativeRoutes.value
        if (index !in routes.indices) return
        val chosen = routes[index]
        _routeResult.value = chosen
        _routePoints.value = chosen.points
        currentInstructionIndex = 0
        _currentInstruction.value = chosen.instructions.firstOrNull()
        _distanceToNext.value = chosen.instructions.firstOrNull()?.distanceMeters ?: 0.0
        updateEta(chosen.durationSeconds, chosen.distanceMeters)
        AppLog.i(tag, "Alternative route $index selected: ${chosen.distanceMeters.toInt()}m")
    }

    // ── Phase 1.6: Route preference ────────────────────────────────────────────
    private val _routePreference = MutableStateFlow(RoutePreference.FASTEST)
    val routePreference: StateFlow<RoutePreference> = _routePreference.asStateFlow()

    fun setRoutePreference(pref: RoutePreference) {
        _routePreference.value = pref
        AppLog.i(tag, "Route preference: $pref")
    }



    // ── Phase 1.9: ETA ────────────────────────────────────────────────────────
    private val _eta = MutableStateFlow<String?>(null)
    val eta: StateFlow<String?> = _eta.asStateFlow()

    private var routeStartMs = 0L
    private var initialDurationSeconds = 0L
    private var initialDistanceMeters = 0.0

    internal fun formatEta(remainingSeconds: Long, distanceMeters: Double): String {
        val clampedSeconds = maxOf(0L, remainingSeconds)
        val etaMs = System.currentTimeMillis() + clampedSeconds * 1000L
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = etaMs
        val h = cal.get(java.util.Calendar.HOUR).let { if (it == 0) 12 else it }
        val m = cal.get(java.util.Calendar.MINUTE)
        val amPm = if (cal.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "AM" else "PM"
        val mins = clampedSeconds / 60L
        val distMi = distanceMeters / 1609.34
        return if (distMi >= 0.1) {
            "ETA %d:%02d %s · %d min · %.1f mi".format(h, m, amPm, mins, distMi)
        } else {
            "ETA %d:%02d %s · %d min".format(h, m, amPm, mins)
        }
    }

    private fun updateEta(remainingSeconds: Long, distanceMeters: Double) {
        _eta.value = formatEta(remainingSeconds, distanceMeters)
    }

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // ── Internal state ─────────────────────────────────────────────────────────

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var locationJob: Job? = null
    private var currentInstructionIndex = 0
    private var lastSpokenInstruction: String? = null

    // ── Rerouting ─────────────────────────────────────────────────────────────
    /** Saved destination for automatic rerouting when user deviates from route */
    private var routeDestination: LatLon? = null
    /** Saved routing profile for rerouting */
    private var routeProfile: String = "car"
    /** Count of consecutive location updates where user is off-route (>100m from polyline) */
    private var offRouteCount = 0
    /** Minimum consecutive off-route updates before triggering reroute */
    private val OFF_ROUTE_THRESHOLD = 3
    /** Maximum distance from route polyline (meters) before considered off-route */
    private val OFF_ROUTE_DISTANCE_M = 100.0
    /** Minimum time between reroutes (ms) to prevent hammering */
    private val REROUTE_COOLDOWN_MS = 15_000L
    /** Timestamp of last reroute */
    private var lastRerouteMs = 0L
    /** True while a reroute calculation is in progress */
    private var isRerouting = false

    // ── ALPR / camera proximity tracking ──────────────────────────────────────
    // Camera cell keys announced at half-mile (804m) threshold
    private val announcedAtHalfMile = mutableSetOf<String>()
    // Camera IDs that have been announced at the 100m threshold
    private val announcedAt100m = mutableSetOf<String>()
    // Surveillance TTS cooldown: camera ID → last announcement timestamp
    private val survTtsCooldowns = mutableMapOf<String, Long>()
    // Phase 1.1: highway pre-announcement tracking (per-instruction index)
    private val preAnnouncedAt1mi = mutableSetOf<Int>()
    private val preAnnouncedAtHalfMi = mutableSetOf<Int>()
    private val preAnnouncedAt1000ft = mutableSetOf<Int>()
    private val SURV_TTS_COOLDOWN_MS = 120_000L  // 2-minute cooldown per camera
    private var lastAnySurvAnnouncementMs = 0L
    private var lastAnnouncementLatLon: LatLon? = null

    init {
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
            if (ttsReady) {
                tts?.language = Locale.US
                AppLog.i(tag, "TTS initialized successfully")
            } else {
                AppLog.w(tag, "TTS initialization failed: $status")
            }
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Request a route. Updates [routePoints], [routeResult], and [currentInstruction].
     * Phase 1.6: uses [routePreference] to pick routing method (FASTEST vs AVOID_ALPR).
     * Phase 1.3: populates [alternativeRoutes] when possible.
     */
    fun requestRoute(
        from: LatLon, to: LatLon, profile: String = "car",
        routePreference: RoutePreference = _routePreference.value
    ) {
        routeDestination = to
        routeProfile = profileFor(routePreference)
        _routePreference.value = routePreference

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            _routingAvailable.value = routingRepository.isGraphAvailable()
            if (!_routingAvailable.value && appPrefs.offlineMode.value) {
                _errorMessage.value = "Routing data not loaded - see Settings for setup instructions"
                _isLoading.value = false
                return@launch
            }

            val forceOnline = routingRepository.offlineRoutingUnsupported
            val ghProfile = profileFor(routePreference)

            // Phase 1.6: AVOID_ALPR uses dedicated routing method
            if (routePreference == RoutePreference.AVOID_ALPR && !forceOnline) {
                val result = routingRepository.calculateAvoidAlprRoute(from, to, ghProfile)
                _isLoading.value = false
                if (result != null) {
                    applyRouteResult(from, result)
                    _alternativeRoutes.value = listOf(result)
                } else {
                    setRoutingError()
                }
                return@launch
            }

            // Phase 1.3: try to get alternatives (offline only)
            val alternatives: List<RouteResult> = when {
                forceOnline -> {
                    AppLog.w(tag, "requestRoute: offline routing unsupported on this device")
                    emptyList()
                }
                else -> routingRepository.calculateAlternativeRoutes(from, to, ghProfile)
            }

            _isLoading.value = false

            if (alternatives.isEmpty()) {
                setRoutingError()
                return@launch
            }

            _alternativeRoutes.value = alternatives
            val result = alternatives[0]
            applyRouteResult(from, result)
        }
    }

    private fun applyRouteResult(from: LatLon, result: RouteResult) {
        _routeResult.value = result
        _routePoints.value = result.points
        currentInstructionIndex = 0
        _currentInstruction.value = result.instructions.firstOrNull()
        _distanceToNext.value = result.instructions.firstOrNull()?.distanceMeters ?: 0.0
        // Phase 1.9: compute initial ETA
        initialDurationSeconds = result.durationSeconds
        initialDistanceMeters = result.distanceMeters
        updateEta(result.durationSeconds, result.distanceMeters)
        // Reset rerouting and camera state
        offRouteCount = 0
        isRerouting = false
        announcedAtHalfMile.clear()
        announcedAt100m.clear()
        survTtsCooldowns.clear()
        lastAnySurvAnnouncementMs = 0L
        lastAnnouncementLatLon = null
        // Reset pre-announcement tracking
        preAnnouncedAt1mi.clear()
        preAnnouncedAtHalfMi.clear()
        preAnnouncedAt1000ft.clear()
        AppLog.i(tag, "Route applied: ${result.points.size} points, " +
            "${result.instructions.size} instructions, " +
            "${result.distanceMeters.toInt()}m, ${result.durationSeconds}s")
    }

    private fun setRoutingError() {
        _errorMessage.value = when {
            routingRepository.offlineRoutingUnsupported ->
                "Could not reach routing server. Check network connection."
            routingRepository.lastLoadError != null ->
                "Routing graph failed to load: ${routingRepository.lastLoadError}"
            else ->
                "Could not calculate route. Check that routing data covers this area."
        }
    }

    /** Start navigation - begins tracking location and advancing instructions. */
    fun startNavigation() {
        if (_routePoints.value == null) {
            AppLog.w(tag, "startNavigation called but no route loaded")
            return
        }
        _isNavigating.value = true
        _currentInstruction.value?.let { speakInstruction(it) }
        startLocationTracking()
    }

    /** Stop navigation and clear route state. */
    fun stopNavigation() {
        _isNavigating.value = false
        locationJob?.cancel()
        locationJob = null
        _routePoints.value = null
        _routeResult.value = null
        _currentInstruction.value = null
        _distanceToNext.value = 0.0
        _errorMessage.value = null
        currentInstructionIndex = 0
        lastSpokenInstruction = null
        offRouteCount = 0
        isRerouting = false
        routeDestination = null
        announcedAtHalfMile.clear()
        announcedAt100m.clear()
        survTtsCooldowns.clear()
        lastAnySurvAnnouncementMs = 0L
        lastAnnouncementLatLon = null
        tts?.stop()
    }

    fun clearError() {
        _errorMessage.value = null
    }

    // ── Offline geocoder ───────────────────────────────────────────────────────

    /** Returns true if an offline geocoder DB exists for the given state abbreviation. */
    fun isOfflineGeocoderAvailable(stateAbbr: String): Boolean =
        offlineGeocoder.isAvailable(stateAbbr)

    /**
     * Search the offline geocoder for [query] in [stateAbbr].
     * When [userLat]/[userLon] are provided, results are proximity-sorted
     * and geographic filtering is applied to street-name queries.
     * Returns results if DB is present; empty list if unavailable.
     */
    suspend fun searchOffline(
        query: String,
        stateAbbr: String,
        userLat: Double? = null,
        userLon: Double? = null
    ): List<OfflineGeocoderResult> =
        offlineGeocoder.search(query, stateAbbr, userLat, userLon)

    // ── Internal navigation logic ──────────────────────────────────────────────

    private fun startLocationTracking() {
        locationJob?.cancel()
        locationJob = viewModelScope.launch {
            locationRepository.locationFlow(minTimeMs = 2000L, minDistanceM = 5f)
                .collect { location ->
                    if (!_isNavigating.value) return@collect
                    val result = _routeResult.value ?: return@collect
                    val instructions = result.instructions
                    if (instructions.isEmpty()) return@collect

                    val userLatLon = LatLon(location.latitude, location.longitude)
                    val currentIdx = currentInstructionIndex
                    if (currentIdx >= instructions.size) {
                        onNavigationComplete()
                        return@collect
                    }

                    val currentInstr = instructions[currentIdx]
                    val distToInstr = haversineMeters(userLatLon, currentInstr.point)
                    _distanceToNext.value = distToInstr

                    // Phase 1.9: real-time ETA update
                    if (routeStartMs > 0L) {
                        val elapsedS = (System.currentTimeMillis() - routeStartMs) / 1000L
                        val remaining = maxOf(0L, initialDurationSeconds - elapsedS)
                        updateEta(remaining, result.distanceMeters)
                    }

                    // Phase 1.1: highway pre-announcements for upcoming maneuvers
                    checkHighwayPreAnnouncements(currentIdx, distToInstr, instructions)

                    // Speed-adaptive advance threshold
                    val currentSpeedMps = location.speed
                    val advanceThresholdMeters = maxOf(30.0, currentSpeedMps * 5.0)

                    if (distToInstr < advanceThresholdMeters && currentIdx + 1 < instructions.size) {
                        currentInstructionIndex = currentIdx + 1
                        val nextInstr = instructions[currentInstructionIndex]
                        _currentInstruction.value = nextInstr
                        _distanceToNext.value = nextInstr.distanceMeters
                        speakInstruction(nextInstr)
                    }

                    checkOffRouteAndReroute(userLatLon, result.points)

                    // ── Surveillance camera proximity alerts ───────────────
                    checkSurveillanceCameraProximity(userLatLon, result.points)
                }
        }
    }

    // ── Off-route detection & automatic rerouting ──────────────────────────

    /**
     * Check if user has deviated from route. If off-route for [OFF_ROUTE_THRESHOLD]
     * consecutive location updates (>100m from nearest polyline point), automatically
     * recalculate route from current position to saved destination.
     */
    private fun checkOffRouteAndReroute(userLatLon: LatLon, routePoints: List<LatLon>) {
        if (isRerouting || routeDestination == null) return

        val minDist = minDistanceToPolyline(userLatLon, routePoints)

        if (minDist > OFF_ROUTE_DISTANCE_M) {
            offRouteCount++ 
            if (offRouteCount >= OFF_ROUTE_THRESHOLD) {
                val now = System.currentTimeMillis()
                if (now - lastRerouteMs < REROUTE_COOLDOWN_MS) {
                    AppLog.d(tag, "Off-route but reroute cooldown active (${(REROUTE_COOLDOWN_MS - (now - lastRerouteMs)) / 1000}s left)")
                    return
                }
                AppLog.i(tag, "Off-route detected: ${minDist.toInt()}m from route, $offRouteCount consecutive — rerouting")
                lastRerouteMs = now
                offRouteCount = 0
                isRerouting = true
                triggerReroute(userLatLon)
            }
        } else {
            // Back on route — reset counter
            if (offRouteCount > 0) {
                AppLog.d(tag, "Back on route (${minDist.toInt()}m from polyline)")
            }
            offRouteCount = 0
        }
    }

    /**
     * Calculate the minimum distance from [point] to the nearest segment of the [polyline].
     * Uses point-to-segment projection for accuracy between polyline vertices.
     */
    private fun minDistanceToPolyline(point: LatLon, polyline: List<LatLon>): Double {
        if (polyline.isEmpty()) return Double.MAX_VALUE
        if (polyline.size == 1) return haversineMeters(point, polyline[0])

        var minDist = Double.MAX_VALUE
        for (i in 0 until polyline.size - 1) {
            val dist = pointToSegmentDistance(point, polyline[i], polyline[i + 1])
            if (dist < minDist) minDist = dist
        }
        return minDist
    }

    /**
     * Distance from [point] to the line segment [segA]→[segB] in meters.
     * Projects onto the segment and returns haversine distance to the closest point.
     */
    private fun pointToSegmentDistance(point: LatLon, segA: LatLon, segB: LatLon): Double {
        val dx = segB.lon - segA.lon
        val dy = segB.lat - segA.lat
        if (dx == 0.0 && dy == 0.0) return haversineMeters(point, segA)

        // Project point onto segment (parameter t ∈ [0,1])
        val t = ((point.lon - segA.lon) * dx + (point.lat - segA.lat) * dy) / (dx * dx + dy * dy)
        val clamped = t.coerceIn(0.0, 1.0)
        val closest = LatLon(segA.lat + clamped * dy, segA.lon + clamped * dx)
        return haversineMeters(point, closest)
    }

    /**
     * Trigger an automatic reroute from [currentPos] to the saved destination.
     * Runs async — on completion, replaces the current route and resets navigation state.
     */
    private fun triggerReroute(currentPos: LatLon) {
        val dest = routeDestination ?: run { isRerouting = false; return }
        viewModelScope.launch {
            alertTtsManager.speak("Rerouting", "reroute")
            AppLog.i(tag, "Rerouting from ${currentPos.lat},${currentPos.lon} to ${dest.lat},${dest.lon}")

            val forceOnline = routingRepository.offlineRoutingUnsupported
            val result = when {
                forceOnline -> {
                    AppLog.w(tag, "triggerReroute: offline routing unsupported on this device")
                    null
                }
                else -> routingRepository.calculateRoute(currentPos, dest, routeProfile)
            }

            if (result != null) {
                _routeResult.value = result
                _routePoints.value = result.points
                currentInstructionIndex = 0
                _currentInstruction.value = result.instructions.firstOrNull()
                _distanceToNext.value = result.instructions.firstOrNull()?.distanceMeters ?: 0.0
                offRouteCount = 0
                // Clear camera announcements — new route may pass different cameras
                announcedAtHalfMile.clear()
                announcedAt100m.clear()
                survTtsCooldowns.clear()
                lastAnySurvAnnouncementMs = 0L
                lastAnnouncementLatLon = null
                lastSpokenInstruction = null
                AppLog.i(tag, "Reroute complete: ${result.points.size} points, ${result.distanceMeters.toInt()}m")
                // Speak first instruction of new route
                result.instructions.firstOrNull()?.let { speakInstruction(it) }
            } else {
                AppLog.w(tag, "Reroute failed — keeping current route")
                alertTtsManager.speak("Reroute failed", "reroute_fail")
            }
            isRerouting = false
        }
    }

    /**
     * Speaks "Surveillance ahead" at 1609m (1 mile) and "Near surveillance system" at 100m,
     * but only for cameras that are on the current route (within 100m of any route point).
     *
     * Cameras are clustered into 25m grid cells — multiple cameras in the same cell
     * only trigger one alert (e.g., 3 ALPR cameras at one intersection = 1 announcement).
     * 2-minute cooldown per grid cell.
     */
    private suspend fun checkSurveillanceCameraProximity(
        userLatLon: LatLon,
        routePoints: List<LatLon>
    ) {
        if (routePoints.isEmpty()) return

        data class CameraEntry(val id: String, val lat: Double, val lon: Double)

        val cameras = mutableListOf<CameraEntry>()
        try {
            alprBlocklistDao.getAll().first().forEach { cam ->
                cameras.add(CameraEntry("alpr_${cam.id}", cam.lat, cam.lon))
            }
            redLightCameraDao.getAll().first().forEach { cam ->
                cameras.add(CameraEntry("rlc_${cam.id}", cam.lat, cam.lon))
            }
            speedCameraDao.getAll().first().forEach { cam ->
                cameras.add(CameraEntry("spd_${cam.id}", cam.lat, cam.lon))
            }
        } catch (e: Exception) {
            AppLog.w(tag, "Failed to load surveillance cameras for proximity check", e)
            return
        }

        // ── Cluster cameras into 25m grid cells ──────────────────────────────
        // At ~28°N (Florida), 1° lat ≈ 110,574m, 1° lon ≈ 97,304m
        // 25m grid: lat step ≈ 0.000226, lon step ≈ 0.000257
        // We use a simple grid key: (lat_bucket, lon_bucket) → list of cameras
        val gridSizeM = 25.0
        val latStep = gridSizeM / 110574.0   // degrees per 25m latitude
        val lonStep = gridSizeM / (110574.0 * cos(Math.toRadians(userLatLon.lat)))  // adjusted for longitude

        data class GridCell(val latBucket: Long, val lonBucket: Long)

        val cellMap = mutableMapOf<GridCell, MutableList<CameraEntry>>()
        for (cam in cameras) {
            val distToUser = haversineMeters(userLatLon, LatLon(cam.lat, cam.lon))
            if (distToUser > 2000.0) continue  // pre-filter: skip if >2km away

            val isOnRoute = routePoints.any { rp ->
                haversineMeters(LatLon(cam.lat, cam.lon), rp) <= 100.0
            }
            if (!isOnRoute) continue

            val cell = GridCell(
                latBucket = (cam.lat / latStep).toLong(),
                lonBucket = (cam.lon / lonStep).toLong()
            )
            cellMap.getOrPut(cell) { mutableListOf() }.add(cam)
        }

        val now = System.currentTimeMillis()

        // Global 2-minute cooldown across ALL surveillance announcements
        if ((now - lastAnySurvAnnouncementMs) < SURV_TTS_COOLDOWN_MS) return

        for ((cell, camsInCell) in cellMap) {
            val cellKey = "cell_${cell.latBucket}_${cell.lonBucket}"
            val lastSpoke = survTtsCooldowns[cellKey] ?: 0L
            if ((now - lastSpoke) < SURV_TTS_COOLDOWN_MS) continue

            val closest = camsInCell.minByOrNull { haversineMeters(userLatLon, LatLon(it.lat, it.lon)) } ?: continue
            val distToUser = haversineMeters(userLatLon, LatLon(closest.lat, closest.lon))

            // 25m minimum distance from last announcement location
            val lastPos = lastAnnouncementLatLon
            if (lastPos != null && haversineMeters(userLatLon, lastPos) < 25.0) continue

            // 100m threshold — "Near surveillance system" (second and final announcement)
            if (distToUser <= 100.0 && cellKey !in announcedAt100m) {
                announcedAt100m.add(cellKey)
                survTtsCooldowns[cellKey] = now
                lastAnySurvAnnouncementMs = now
                lastAnnouncementLatLon = userLatLon
                alertTtsManager.speakIfEnabled("Near surveillance system", TtsCategory.SURVEILLANCE, "surv_100_$cellKey")
                AppLog.i(tag, "TTS: Near surveillance system (cell=$cellKey, ${camsInCell.size} cameras)")
                return  // one announcement per location update
            }
            // Half-mile / 804m threshold — "Surveillance ahead" (first announcement)
            else if (distToUser <= 804.0 && cellKey !in announcedAtHalfMile) {
                announcedAtHalfMile.add(cellKey)
                survTtsCooldowns[cellKey] = now
                lastAnySurvAnnouncementMs = now
                lastAnnouncementLatLon = userLatLon
                alertTtsManager.speakIfEnabled("Surveillance ahead", TtsCategory.SURVEILLANCE, "surv_half_$cellKey")
                AppLog.i(tag, "TTS: Surveillance ahead (cell=$cellKey, ${camsInCell.size} cameras, dist=${distToUser.toInt()}m)")
                return  // one announcement per location update
            }
        }
    }

    private fun onNavigationComplete() {
        _isNavigating.value = false
        locationJob?.cancel()
        tts?.speak("You have arrived at your destination.", TextToSpeech.QUEUE_FLUSH, null, "arrive")
        AppLog.i(tag, "Navigation complete - destination reached")
    }

    private fun speakInstruction(instruction: TurnInstruction) {
        if (!ttsReady) return
        val text = instruction.text.ifBlank { return }
        if (text == lastSpokenInstruction) return
        lastSpokenInstruction = text
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav_instr")
    }

        /**
     * Phase 1.1: Pre-announce highway maneuvers at 1mi, 0.5mi, and 1000ft.
     */
    private fun checkHighwayPreAnnouncements(
        currentIdx: Int,
        distToCurrentM: Double,
        instructions: List<TurnInstruction>
    ) {
        if (currentIdx < 0 || currentIdx >= instructions.size) return
        val instr = instructions[currentIdx]
        if (!instr.isHighwayManeuver) return

        val ft = distToCurrentM * 3.28084
        val mi = distToCurrentM / 1609.344

        val ref = instr.ref?.let { " onto $it" } ?: ""
        val exit = instr.exitNumber?.let { "Take exit $it" } ?: ""

        when {
            mi <= 1.05 && mi > 0.55 && currentIdx !in preAnnouncedAt1mi -> {
                preAnnouncedAt1mi.add(currentIdx)
                val text = if (exit.isNotBlank()) "In 1 mile, $exit$ref"
                           else "In 1 mile, ${instr.text}$ref"
                alertTtsManager.speak(text, "preannounce_1mi_$currentIdx")
            }
            mi <= 0.55 && mi > 0.22 && currentIdx !in preAnnouncedAtHalfMi -> {
                preAnnouncedAtHalfMi.add(currentIdx)
                val text = if (exit.isNotBlank()) "In half a mile, $exit$ref"
                           else "In half a mile, ${instr.text}$ref"
                alertTtsManager.speak(text, "preannounce_halfmi_$currentIdx")
            }
            ft <= 1100 && ft > 300 && currentIdx !in preAnnouncedAt1000ft -> {
                preAnnouncedAt1000ft.add(currentIdx)
                val text = if (exit.isNotBlank()) "In 1000 feet, $exit$ref"
                           else "In 1000 feet, ${instr.text}$ref"
                alertTtsManager.speak(text, "preannounce_1000ft_$currentIdx")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        locationJob?.cancel()
        tts?.shutdown()
        tts = null
    }
}

// ── Haversine distance helper ──────────────────────────────────────────────────

private fun haversineMeters(a: LatLon, b: LatLon): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val sinDLat = sin(dLat / 2)
    val sinDLon = sin(dLon / 2)
    val x = sinDLat * sinDLat +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) *
            sinDLon * sinDLon
    return 2 * r * asin(sqrt(x))
}
