package com.aegisnav.app.routing

import android.content.Context
import android.speech.tts.TextToSpeech
import com.aegisnav.app.crash.CrashReporter
import com.aegisnav.app.util.AppLog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
import com.aegisnav.app.data.dao.SavedRouteDao
import com.aegisnav.app.data.model.SavedLocation
import com.aegisnav.app.data.model.SavedRoute
import com.aegisnav.app.data.repository.AppPreferencesRepository
import com.aegisnav.app.data.repository.SavedLocationRepository
import com.aegisnav.app.geocoder.OfflineGeocoderRepository
import com.aegisnav.app.geocoder.OfflineGeocoderResult

import com.aegisnav.app.security.SecureDataStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aegisnav.app.util.AlertTtsManager
import com.aegisnav.app.util.TtsCategory
import kotlin.math.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

data class RecentSearch(val name: String, val lat: Double, val lon: Double)

data class RecentRoute(val stops: List<RecentSearch>, val destination: RecentSearch)

data class RouteSurveillanceSummary(
    val alprCount: Int,
    val redLightCount: Int,
    val speedCameraCount: Int
)

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
    private val savedRouteDao: SavedRouteDao,
    private val alertTtsManager: AlertTtsManager,
    private val crashReporter: CrashReporter,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val tag = "NavigationViewModel"

    // Sentry throttle state
    private val lastSentryMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun sentryCaptureThrottled(key: String, cooldownMs: Long = 300_000L, message: String) {
        val now = System.currentTimeMillis()
        val last = lastSentryMs[key] ?: 0L
        if (now - last >= cooldownMs) {
            lastSentryMs[key] = now
            crashReporter.captureMessage(message)
        }
    }

    private fun profileFor(preference: RoutePreference): String = when (preference) {
        RoutePreference.FASTEST           -> "car"
        RoutePreference.SHORTEST_DISTANCE -> "car_shortest"
        RoutePreference.AVOID_ALPR        -> "car_avoid_alpr"
        RoutePreference.AVOID_HIGHWAYS    -> "car"   // uses custom_model hint, same base profile
    }

    // ── Recent searches (DataStore) ──────────────────────────────────────────

    private val navDataStore: DataStore<Preferences> =
        SecureDataStore.get(context, "nav_prefs")

    private val _recentSearches = MutableStateFlow<List<RecentSearch>>(emptyList())
    val recentSearches: StateFlow<List<RecentSearch>> = _recentSearches.asStateFlow()

    private fun loadRecentSearchesAsync() {
        viewModelScope.launch {
            val json = navDataStore.data.first()[stringPreferencesKey("recent_searches")] ?: return@launch
            val parsed = runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    RecentSearch(obj.getString("name"), obj.getDouble("lat"), obj.getDouble("lon"))
                }
            }.getOrElse { emptyList() }
            _recentSearches.value = parsed
        }
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
        viewModelScope.launch {
            navDataStore.edit { it[stringPreferencesKey("recent_searches")] = arr.toString() }
        }
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
        viewModelScope.launch {
            navDataStore.edit { it[stringPreferencesKey("recent_searches")] = arr.toString() }
        }
    }

    // ── Recent multi-stop routes (DataStore) ──────────────────────────────────

    private val _recentRoutes = MutableStateFlow<List<RecentRoute>>(emptyList())
    val recentRoutes: StateFlow<List<RecentRoute>> = _recentRoutes.asStateFlow()

    private fun loadRecentRoutesAsync() {
        viewModelScope.launch {
            val json = navDataStore.data.first()[stringPreferencesKey("recent_routes")] ?: return@launch
            val parsed = runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    val stopsArr = obj.getJSONArray("stops")
                    val stops = (0 until stopsArr.length()).map { j ->
                        val s = stopsArr.getJSONObject(j)
                        RecentSearch(s.getString("name"), s.getDouble("lat"), s.getDouble("lon"))
                    }
                    val dest = obj.getJSONObject("destination")
                    RecentRoute(stops, RecentSearch(dest.getString("name"), dest.getDouble("lat"), dest.getDouble("lon")))
                }
            }.getOrElse { emptyList() }
            _recentRoutes.value = parsed
        }
    }

    fun saveRecentRoute(stops: List<RecentSearch>, destination: RecentSearch) {
        val current = _recentRoutes.value.toMutableList()
        // Remove duplicate (same destination + stops)
        current.removeAll { it.destination == destination && it.stops == stops }
        current.add(0, RecentRoute(stops, destination))
        val capped = current.take(5)
        _recentRoutes.value = capped
        val arr = JSONArray()
        capped.forEach { route ->
            val obj = JSONObject()
            val stopsArr = JSONArray()
            route.stops.forEach { s ->
                stopsArr.put(JSONObject().apply {
                    put("name", s.name); put("lat", s.lat); put("lon", s.lon)
                })
            }
            obj.put("stops", stopsArr)
            obj.put("destination", JSONObject().apply {
                put("name", route.destination.name)
                put("lat", route.destination.lat)
                put("lon", route.destination.lon)
            })
            arr.put(obj)
        }
        viewModelScope.launch {
            navDataStore.edit { it[stringPreferencesKey("recent_routes")] = arr.toString() }
        }
    }

    fun removeRecentRoute(route: RecentRoute) {
        val current = _recentRoutes.value.toMutableList()
        current.remove(route)
        _recentRoutes.value = current
        val arr = JSONArray()
        current.forEach { r ->
            val obj = JSONObject()
            val stopsArr = JSONArray()
            r.stops.forEach { s ->
                stopsArr.put(JSONObject().apply { put("name", s.name); put("lat", s.lat); put("lon", s.lon) })
            }
            obj.put("stops", stopsArr)
            obj.put("destination", JSONObject().apply {
                put("name", r.destination.name); put("lat", r.destination.lat); put("lon", r.destination.lon)
            })
            arr.put(obj)
        }
        viewModelScope.launch {
            navDataStore.edit { it[stringPreferencesKey("recent_routes")] = arr.toString() }
        }
    }

    fun loadRecentRoute(route: RecentRoute, from: LatLon? = null) {
        _waypoints.value = route.stops.map { LatLon(it.lat, it.lon) }
        val dest = LatLon(route.destination.lat, route.destination.lon)
        val startFrom = from ?: _lastRouteFrom ?: return
        calculateAllRoutes(startFrom, dest)
    }

    fun clearWaypoints() {
        _waypoints.value = emptyList()
    }

    // ── Saved routes (Room) ───────────────────────────────────────────────────

    val savedRoutes: StateFlow<List<SavedRoute>> = savedRouteDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun saveRouteToRoom(name: String, stops: List<RecentSearch>, destination: RecentSearch) {
        viewModelScope.launch {
            val allStops = stops + destination
            val arr = JSONArray()
            allStops.forEach { s ->
                arr.put(JSONObject().apply { put("name", s.name); put("lat", s.lat); put("lon", s.lon) })
            }
            savedRouteDao.insert(SavedRoute(name = name, stopsJson = arr.toString()))
        }
    }

    fun deleteSavedRoute(id: Int) {
        viewModelScope.launch { savedRouteDao.deleteById(id) }
    }

    fun loadSavedRoute(route: SavedRoute, from: LatLon? = null) {
        runCatching {
            val arr = JSONArray(route.stopsJson)
            val allStops = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RecentSearch(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"))
            }
            if (allStops.isEmpty()) return
            val dest = allStops.last()
            val stops = allStops.dropLast(1)
            _waypoints.value = stops.map { LatLon(it.lat, it.lon) }
            val startFrom = from ?: _lastRouteFrom ?: return
            calculateAllRoutes(startFrom, LatLon(dest.lat, dest.lon))
        }
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

    /** Current road speed limit in mph (null = unknown). Visual-only; no TTS. */
    private val _currentSpeedLimitMph = MutableStateFlow<Int?>(null)
    val currentSpeedLimitMph: StateFlow<Int?> = _currentSpeedLimitMph.asStateFlow()

    // ── Route progress (0.0 = start, 1.0 = arrived) ───────────────────────────
    private val _routeProgress = MutableStateFlow(0f)
    val routeProgress: StateFlow<Float> = _routeProgress.asStateFlow()

    // ── Number of surveillance cameras on the current route ───────────────────
    private val _routeCameraCount = MutableStateFlow(0)
    val routeCameraCount: StateFlow<Int> = _routeCameraCount.asStateFlow()

    // ── Detailed surveillance summary (ALPR / red light / speed) ──────────────
    private val _routeSurveillanceSummary = MutableStateFlow<RouteSurveillanceSummary?>(null)
    val routeSurveillanceSummary: StateFlow<RouteSurveillanceSummary?> = _routeSurveillanceSummary.asStateFlow()

    // ── Camera cache (per-state, loaded once — avoids DB scans every 2s) ──────
    data class CachedCamera(val id: String, val lat: Double, val lon: Double)

    /** Axis-aligned bounding box: minLat, maxLat, minLon, maxLon */
    data class BoundingBox(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double) {
        fun contains(lat: Double, lon: Double) = lat in minLat..maxLat && lon in minLon..maxLon
    }

    private val _cachedCameras = MutableStateFlow<List<CachedCamera>>(emptyList())

    /** Refresh cached camera list from DB. Filters to the active state's bounding box only. */
    fun refreshCameraCache() {
        viewModelScope.launch {
            val cameras = mutableListOf<CachedCamera>()
            val stateCode = try { _activeStateCode.value } catch (_: Exception) { "fl" }
            val bbox = STATE_BOUNDING_BOXES[stateCode]
            try {
                alprBlocklistDao.getAll().first().forEach { cam ->
                    if (bbox == null || bbox.contains(cam.lat, cam.lon))
                        cameras.add(CachedCamera("alpr_${cam.id}", cam.lat, cam.lon))
                }
                redLightCameraDao.getAll().first().forEach { cam ->
                    if (bbox == null || bbox.contains(cam.lat, cam.lon))
                        cameras.add(CachedCamera("rlc_${cam.id}", cam.lat, cam.lon))
                }
                speedCameraDao.getAll().first().forEach { cam ->
                    if (bbox == null || bbox.contains(cam.lat, cam.lon))
                        cameras.add(CachedCamera("spd_${cam.id}", cam.lat, cam.lon))
                }
            } catch (e: Exception) {
                AppLog.w(tag, "Failed to load surveillance cameras for cache", e)
            }
            _cachedCameras.value = cameras
            AppLog.i(tag, "Camera cache refreshed: ${cameras.size} cameras (state=$stateCode)")
        }
    }

    /** Called when user switches active state in selector. Refreshes camera + routing cache. */
    fun onActiveStateChanged() {
        refreshCameraCache()
    }

    init {
        // Load camera cache at startup
        refreshCameraCache()
        // Load persisted data async (no blocking reads)
        loadRecentSearchesAsync()
        loadRecentRoutesAsync()
        loadActiveStateCodeAsync()
        loadHeadingUpPrefAsync()
    }

    // ── Multi-route selection (3 routes shown simultaneously on map) ──────────
    data class RouteOption(
        val preference: RoutePreference,
        val result: RouteResult,
        val color: Int,       // route line color (ARGB)
        val label: String,    // "Fastest" / "Shortest" / "Avoid ALPR"
        val surveillanceSummary: RouteSurveillanceSummary?
    )

    private val _routeOptions = MutableStateFlow<List<RouteOption>>(emptyList())
    val routeOptions: StateFlow<List<RouteOption>> = _routeOptions.asStateFlow()

    private val _selectedRouteIndex = MutableStateFlow(0)
    val selectedRouteIndex: StateFlow<Int> = _selectedRouteIndex.asStateFlow()

    /**
     * Calculate only the fastest route and populate [routeOptions].
     * Shows the fastest route on the map before user taps "Go".
     * Shortest and Avoid ALPR routes can be loaded on-demand via [loadShortestRoute] and [loadAvoidAlprRoute].
     */
    fun calculateAllRoutes(from: LatLon, to: LatLon) {
        AppLog.i("NavigationViewModel", "calculateAllRoutes: from=${from.lat},${from.lon} to=${to.lat},${to.lon}")
        routeDestination = to
        _routeDestinationState.value = to
        _lastRouteFrom = from
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _routeOptions.value = emptyList()

            _routingAvailable.value = routingRepository.isGraphAvailable()
            if (!_routingAvailable.value && appPrefs.offlineMode.value) {
                _errorMessage.value = "Routing data not loaded - see Settings for setup instructions"
                _isLoading.value = false
                return@launch
            }

            val fastest = withContext(Dispatchers.IO) {
                routingRepository.calculateRoute(from, to, "car", waypoints = _waypoints.value)
            }

            val options = mutableListOf<RouteOption>()
            fastest?.let { result ->
                options.add(RouteOption(RoutePreference.FASTEST, result, 0xFF2196F3.toInt(), "Fastest", null))
                _routeOptions.value = options.toList()
                _selectedRouteIndex.value = 0
                _routeResult.value = result
                _routePoints.value = result.points
                initialDurationSeconds = result.durationSeconds
                initialDistanceMeters = result.distanceMeters
                updateEta(result.durationSeconds, result.distanceMeters)
                val cameras = _cachedCameras.value
                val pts = result.points
                _routeCameraCount.value = cameras.count { cam ->
                    pts.any { pt -> haversineMeters(LatLon(cam.lat, cam.lon), pt) <= 100.0 }
                }
                // Compute surveillance in background
                launch(Dispatchers.IO) {
                    val summary = computeSurveillanceSummary(result)
                    _routeOptions.value = _routeOptions.value.map { opt ->
                        if (opt.preference == RoutePreference.FASTEST) opt.copy(surveillanceSummary = summary) else opt
                    }
                    _routeSurveillanceSummary.value = summary
                }
            }

            if (options.isEmpty()) {
                _errorMessage.value = "Could not calculate route. Check routing data."
            }

            _isLoading.value = false
        }
    }

    /** Load the shortest-distance route on demand and add it to [routeOptions]. */
    fun loadShortestRoute(from: LatLon, to: LatLon) {
        viewModelScope.launch {
            _isLoadingShortest.value = true
            val result = withContext(Dispatchers.IO) {
                routingRepository.calculateRoute(from, to, "car_shortest", waypoints = _waypoints.value)
            }
            result?.let { route ->
                val current = _routeOptions.value.toMutableList()
                current.removeAll { it.preference == RoutePreference.SHORTEST_DISTANCE }
                current.add(RouteOption(RoutePreference.SHORTEST_DISTANCE, route, 0xFF4CAF50.toInt(), "Shortest", null))
                _routeOptions.value = current
                launch(Dispatchers.IO) {
                    val summary = computeSurveillanceSummary(route)
                    _routeOptions.value = _routeOptions.value.map { opt ->
                        if (opt.preference == RoutePreference.SHORTEST_DISTANCE) opt.copy(surveillanceSummary = summary) else opt
                    }
                }
            }
            _isLoadingShortest.value = false
        }
    }

    /** Load the avoid-ALPR route on demand and add it to [routeOptions]. */
    fun loadAvoidAlprRoute(from: LatLon, to: LatLon) {
        viewModelScope.launch {
            _isLoadingAvoidAlpr.value = true
            val result = withContext(Dispatchers.IO) {
                routingRepository.calculateAvoidAlprRoute(from, to, "car_avoid_alpr", _waypoints.value)
            }
            result?.let { route ->
                val current = _routeOptions.value.toMutableList()
                current.removeAll { it.preference == RoutePreference.AVOID_ALPR }
                current.add(RouteOption(RoutePreference.AVOID_ALPR, route, 0xFFFF9800.toInt(), "Avoid ALPR", null))
                _routeOptions.value = current
                launch(Dispatchers.IO) {
                    val summary = computeSurveillanceSummary(route)
                    _routeOptions.value = _routeOptions.value.map { opt ->
                        if (opt.preference == RoutePreference.AVOID_ALPR) opt.copy(surveillanceSummary = summary) else opt
                    }
                }
            }
            _isLoadingAvoidAlpr.value = false
        }
    }

    /** Select a route option by index (called when user taps a route on the map). */
    fun selectRoute(index: Int) {
        _selectedRouteIndex.value = index
        val option = _routeOptions.value.getOrNull(index) ?: return
        _routeResult.value = option.result
        _routePoints.value = option.result.points
        initialDurationSeconds = option.result.durationSeconds
        initialDistanceMeters = option.result.distanceMeters
        updateEta(option.result.durationSeconds, option.result.distanceMeters)
        // Update camera count for selected route
        val cameras = _cachedCameras.value
        val pts = option.result.points
        _routeCameraCount.value = cameras.count { cam ->
            pts.any { pt -> haversineMeters(LatLon(cam.lat, cam.lon), pt) <= 100.0 }
        }
        _routeSurveillanceSummary.value = option.surveillanceSummary
        AppLog.i(tag, "Route selected: index=$index label=${option.label}")
    }

    /** Cancel route selection and clear all route options. */
    fun cancelRouteSelection() {
        _routeOptions.value = emptyList()
        _routeResult.value = null
        _routePoints.value = null
        _selectedRouteIndex.value = 0
        _routeCameraCount.value = 0
        _routeSurveillanceSummary.value = null
        _eta.value = null
        _waypoints.value = emptyList()
        routeDestination = null
        _routeDestinationState.value = null
        _isLoading.value = false
        _isLoadingShortest.value = false
        _isLoadingAvoidAlpr.value = false
        _errorMessage.value = null
        lastNavTtsMs = 0L
        AppLog.i(tag, "Route selection cancelled — all stops and routing cleared")
    }

    /** Compute surveillance summary for a route using cached cameras (state-filtered, no DAO calls). */
    private suspend fun computeSurveillanceSummary(result: RouteResult): RouteSurveillanceSummary =
        withContext(Dispatchers.IO) {
            val routePts = result.points
            val cached = _cachedCameras.value
            val alprCount = cached.filter { it.id.startsWith("alpr_") }.count { cam ->
                routePts.any { pt -> haversineMeters(LatLon(cam.lat, cam.lon), pt) <= 100.0 }
            }
            val redLightCount = cached.filter { it.id.startsWith("rlc_") }.count { cam ->
                routePts.any { pt -> haversineMeters(LatLon(cam.lat, cam.lon), pt) <= 100.0 }
            }
            val speedCount = cached.filter { it.id.startsWith("spd_") }.count { cam ->
                routePts.any { pt -> haversineMeters(LatLon(cam.lat, cam.lon), pt) <= 100.0 }
            }
            RouteSurveillanceSummary(alprCount, redLightCount, speedCount)
        }

    // ── Phase 1.3: Alternative routes ─────────────────────────────────────────
    private val _alternativeRoutes = MutableStateFlow<List<RouteResult>>(emptyList())
    val alternativeRoutes: StateFlow<List<RouteResult>> = _alternativeRoutes.asStateFlow()

    // ── Waypoints (multi-stop routing) ────────────────────────────────────────
    private val _waypoints = MutableStateFlow<List<LatLon>>(emptyList())
    val waypoints: StateFlow<List<LatLon>> = _waypoints.asStateFlow()

    /** Max 1 stop allowed to prevent ANR from multi-segment routing */
    val canAddWaypoint: Boolean get() = _waypoints.value.size < 1

    fun addWaypoint(latLon: LatLon) {
        if (!canAddWaypoint) {
            AppLog.w(tag, "addWaypoint blocked — max 1 stop allowed")
            return
        }
        _waypoints.value = _waypoints.value + latLon
        AppLog.i(tag, "Waypoint added: ${latLon.lat},${latLon.lon}")
    }

    fun removeWaypoint(index: Int) {
        val current = _waypoints.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _waypoints.value = current
            AppLog.i(tag, "Waypoint $index removed — total=${_waypoints.value.size}")
        }
    }

    // ── Faster route detection ─────────────────────────────────────────────────
    /** Non-null when a faster route has been found (>2 min faster than current ETA). */
    private val _fasterRouteAvailable = MutableStateFlow<RouteResult?>(null)
    val fasterRouteAvailable: StateFlow<RouteResult?> = _fasterRouteAvailable.asStateFlow()

    /** Dismiss the "faster route available" banner without switching to the new route. */
    fun dismissFasterRoute() {
        _fasterRouteAvailable.value = null
        AppLog.i(tag, "Faster route dismissed by user")
    }

    /** Accept the faster route — switch to it immediately. */
    fun acceptFasterRoute() {
        val faster = _fasterRouteAvailable.value ?: return
        _fasterRouteAvailable.value = null
        applyRouteResult(_lastRouteFrom ?: return, faster)
        AppLog.i(tag, "Faster route accepted: ${faster.distanceMeters.toInt()}m, ${faster.durationSeconds}s")
    }

    var lastRouteFrom: LatLon? = null
        private set
    private var _lastRouteFrom: LatLon?
        get() = lastRouteFrom
        set(value) { lastRouteFrom = value }
    private var fasterRouteCheckJob: Job? = null
    private val FASTER_ROUTE_CHECK_INTERVAL_MS = 5 * 60 * 1000L  // 5 minutes
    private val FASTER_ROUTE_THRESHOLD_SECONDS = 120L             // 2 minutes

    // ── Wrong-way detection ────────────────────────────────────────────────────
    private var wrongWayCount = 0
    private var wrongWayTtsLastMs = 0L      // N6: 15s TTS cooldown for wrong-way announcements
    private var hasBearingData = false       // N7: true after first real GPS bearing received

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

    private val _isLoadingShortest = MutableStateFlow(false)
    val isLoadingShortest: StateFlow<Boolean> = _isLoadingShortest.asStateFlow()

    private val _isLoadingAvoidAlpr = MutableStateFlow(false)
    val isLoadingAvoidAlpr: StateFlow<Boolean> = _isLoadingAvoidAlpr.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // ── Internal state ─────────────────────────────────────────────────────────

    // Local TTS removed (perf item 9) — all speech via alertTtsManager
    private var locationJob: Job? = null
    private var currentInstructionIndex = 0
    private var completedDistanceM = 0.0   // Item 11: cached cumulative distance
    private var lastClosestPtIdx = 0       // N5: forward-search optimization
    private var lastSpokenInstruction: String? = null

    // ── Item 4: Camera update tick (throttle map camera animation to >15m moves) ──
    private var lastCameraUpdateLat = 0.0
    private var lastCameraUpdateLon = 0.0
    private val _cameraUpdateTick = MutableStateFlow(0L)
    val navCameraUpdateTick: StateFlow<Long> = _cameraUpdateTick.asStateFlow()

    // ── Rerouting ─────────────────────────────────────────────────────────────
    /** Saved destination for automatic rerouting when user deviates from route */
    private var routeDestination: LatLon? = null

    /** Public read-only access to the current route destination (for waypoint recalculation). */
    private val _routeDestinationState = MutableStateFlow<LatLon?>(null)
    val routeDestinationState: StateFlow<LatLon?> = _routeDestinationState.asStateFlow()
    /** Saved routing profile for rerouting */
    private var routeProfile: String = "car"
    /** Current bearing (degrees) captured from location updates for heading-hint rerouting */
    private var currentBearingDegrees: Float = 0f
    /** Current speed in meters per second, updated on every location update */
    private var currentSpeedMps: Float = 0f
    /** Count of consecutive location updates where user is off-route (>100m from polyline) */
    private var offRouteCount = 0
    /** Minimum consecutive off-route updates before triggering reroute */
    private val OFF_ROUTE_THRESHOLD = 2
    /** Maximum distance from route polyline (meters) before considered off-route */
    private val OFF_ROUTE_DISTANCE_M = 100.0
    /** Minimum time between reroutes (ms) to prevent hammering */
    private val REROUTE_COOLDOWN_MS = 10_000L
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

    // ── Compass / heading mode preference ─────────────────────────────────────
    private val _headingUp = MutableStateFlow(true)   // true = heading-up, false = north-up
    val headingUp: StateFlow<Boolean> = _headingUp.asStateFlow()

    fun toggleHeadingUp() {
        _headingUp.value = !_headingUp.value
        viewModelScope.launch {
            navDataStore.edit { it[stringPreferencesKey("heading_up")] = _headingUp.value.toString() }
        }
    }

    private fun loadHeadingUpPrefAsync() {
        viewModelScope.launch {
            val saved = navDataStore.data.first()[stringPreferencesKey("heading_up")]
            _headingUp.value = saved?.toBooleanStrictOrNull() ?: true
        }
    }

    // ── Night mode auto-switch: track last applied style to avoid redundant switches ──
    private var lastAppliedNightStyle: Boolean? = null

    // ── Bearing-based turn advancement state ──────────────────────────────────
    private var reachedCurrentTurn = false
    private var previousDistToInstr = Double.MAX_VALUE

    // ── Navigation TTS announcement tracking ──────────────────────────────────
    private val announcedAtHalfMileSet = mutableSetOf<String>()
    private val announcedAt250ftSet = mutableSetOf<String>()
    private var lastAnySurvAnnouncementMs = 0L
    private var lastAnnouncementLatLon: LatLon? = null
    // Long-stretch announcement: set of instruction indices already announced
    private val longStretchAnnouncedSet = mutableSetOf<Int>()
    // Arrival side: cached calculation for arrival instructions
    private var arrivalSide: String? = null   // "left", "right", or null

    // ── Dynamic TTS gap / cooldown ─────────────────────────────────────────────
    private var lastNavTtsMs = 0L
    private val NAV_TTS_COOLDOWN_MS = 8000L // 8-second cooldown for non-priority nav announcements

    /**
     * Speak a navigation announcement with optional priority.
     * - priority=true  → always fires; interrupts any queued speech first (250ft, at-turn)
     * - priority=false → obeys [NAV_TTS_COOLDOWN_MS] cooldown (half-mile, long-stretch, route start)
     */
    private fun speakNav(text: String, key: String, priority: Boolean = false) {
        val now = System.currentTimeMillis()
        if (priority) {
            alertTtsManager.interrupt()
            alertTtsManager.speakIfEnabled(text, TtsCategory.NAVIGATION, key)
            lastNavTtsMs = now
        } else {
            if (now - lastNavTtsMs >= NAV_TTS_COOLDOWN_MS) {
                alertTtsManager.speakIfEnabled(text, TtsCategory.NAVIGATION, key)
                lastNavTtsMs = now
            }
        }
    }

    init {
        // initTts removed
    }

    // initTts removed — all TTS routed through alertTtsManager (perf item 9)

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Request a route. Updates [routePoints], [routeResult], and [currentInstruction].
     * Phase 1.6: uses [routePreference] to pick routing method (FASTEST vs AVOID_ALPR).
     * Phase 1.3: populates [alternativeRoutes] when possible.
     * Waypoints support: if [waypoints] is provided (or uses current [_waypoints] state),
     * the route is calculated with intermediate via-points (multi-stop routing).
     */
    fun requestRoute(
        from: LatLon, to: LatLon, profile: String = "car",
        routePreference: RoutePreference = _routePreference.value,
        waypoints: List<LatLon> = _waypoints.value
    ) {
        routeDestination = to
        _routeDestinationState.value = to
        routeProfile = profileFor(routePreference)
        _routePreference.value = routePreference
        _lastRouteFrom = from

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
            val avoidHighways = routePreference == RoutePreference.AVOID_HIGHWAYS

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
                else -> routingRepository.calculateAlternativeRoutes(
                    from, to, ghProfile,
                    waypoints = waypoints,
                    avoidHighways = avoidHighways
                )
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
        // Reset route progress
        _routeProgress.value = 0f
        // Compute camera count on the route (async — uses cached cameras)
        viewModelScope.launch {
            val routePts = result.points
            val cameras = _cachedCameras.value
            val onRouteCount = cameras.count { cam ->
                routePts.any { pt -> haversineMeters(LatLon(cam.lat, cam.lon), pt) <= 100.0 }
            }
            _routeCameraCount.value = onRouteCount

            // Compute detailed surveillance summary per camera type using cached cameras
            val cached = cameras
            val alprCount = cached.filter { it.id.startsWith("alpr_") }.count { cam ->
                routePts.any { pt -> haversineMeters(LatLon(cam.lat, cam.lon), pt) <= 100.0 }
            }
            val redLightCount = cached.filter { it.id.startsWith("rlc_") }.count { cam ->
                routePts.any { pt -> haversineMeters(LatLon(cam.lat, cam.lon), pt) <= 100.0 }
            }
            val speedCount = cached.filter { it.id.startsWith("spd_") }.count { cam ->
                routePts.any { pt -> haversineMeters(LatLon(cam.lat, cam.lon), pt) <= 100.0 }
            }
            _routeSurveillanceSummary.value = RouteSurveillanceSummary(alprCount, redLightCount, speedCount)
        }
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
        // Reset bearing-based advancement and TTS announcement state
        reachedCurrentTurn = false
        previousDistToInstr = Double.MAX_VALUE
        announcedAtHalfMileSet.clear()
        announcedAt250ftSet.clear()
        longStretchAnnouncedSet.clear()
        arrivalSide = null
        _currentSpeedLimitMph.value = null
        // Reset faster route and wrong-way state
        _fasterRouteAvailable.value = null
        wrongWayCount = 0; wrongWayTtsLastMs = 0L; hasBearingData = false
        AppLog.i(tag, "Route applied: ${result.points.size} points, " +
            "${result.instructions.size} instructions, " +
            "${result.distanceMeters.toInt()}m, ${result.durationSeconds}s")

        // Pre-calculate arrival side from the last segment
        arrivalSide = computeArrivalSide(result)

        // Route start TTS moved to startNavigation() — only fires on "Go"
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
        routeStartMs = System.currentTimeMillis()
        wrongWayCount = 0
        // Audio focus managed per-utterance — no continuous hold
        // Save recent route if multi-stop
        val currentWaypoints = _waypoints.value
        val dest = routeDestination
        if (dest != null && currentWaypoints.isNotEmpty()) {
            // Build stop list from waypoint LatLons (use coordinate strings as names for now)
            val stopSearches = currentWaypoints.map { wp ->
                RecentSearch("%.5f, %.5f".format(wp.lat, wp.lon), wp.lat, wp.lon)
            }
            val destSearch = RecentSearch("%.5f, %.5f".format(dest.lat, dest.lon), dest.lat, dest.lon)
            saveRecentRoute(stopSearches, destSearch)
        }
        // Route start TTS — fires on "Go", not during route calculation
        val result = _routeResult.value
        val firstInstr = result?.instructions?.firstOrNull()
        if (result != null && firstInstr != null) {
            val pts = result.points
            val bearingDir = if (pts.size >= 2) bearingToCardinal(bearingBetween(pts[0], pts[1])) else "forward"
            val roadName = when {
                !firstInstr.ref.isNullOrBlank() && firstInstr.streetName.isNotBlank() -> "${firstInstr.ref}, ${firstInstr.streetName}"
                !firstInstr.ref.isNullOrBlank() -> firstInstr.ref!!
                firstInstr.streetName.isNotBlank() -> firstInstr.streetName
                else -> null
            }
            val distMi = result.distanceMeters / 1609.344
            val distStr = if (distMi >= 10) "${distMi.toInt()} miles" else "%.1f miles".format(distMi)
            val mins = (result.durationSeconds / 60L).toInt()
            val timeStr = when { mins < 1 -> "less than a minute"; mins == 1 -> "1 minute"; else -> "$mins minutes" }
            val headPart = if (roadName != null) "Head $bearingDir on $roadName." else "Head $bearingDir."
            speakNav("$headPart Your route is $distStr, estimated $timeStr.", "nav_route_start", priority = false)
        }
        // Delay tracking start so TTS can speak without main-thread contention
        viewModelScope.launch {
            kotlinx.coroutines.delay(500L)
            startLocationTracking()
            startFasterRouteChecking()
        }
    }

    /** Stop navigation and clear route state. */
    fun stopNavigation() {
        _isNavigating.value = false
        routeStartMs = 0L
        locationJob?.cancel()
        locationJob = null
        fasterRouteCheckJob?.cancel()
        fasterRouteCheckJob = null
        _routePoints.value = null
        _routeResult.value = null
        _routeOptions.value = emptyList()
        _selectedRouteIndex.value = 0
        _currentInstruction.value = null
        _distanceToNext.value = 0.0
        _errorMessage.value = null
        _routeProgress.value = 0f
        _routeCameraCount.value = 0
        _routeSurveillanceSummary.value = null
        currentInstructionIndex = 0
        lastSpokenInstruction = null
        offRouteCount = 0
        isRerouting = false
        routeDestination = null
        _routeDestinationState.value = null
        _waypoints.value = emptyList()
        announcedAtHalfMile.clear()
        announcedAt100m.clear()
        survTtsCooldowns.clear()
        lastAnySurvAnnouncementMs = 0L
        lastAnnouncementLatLon = null
        // Reset bearing-based advancement and TTS announcement state
        reachedCurrentTurn = false
        previousDistToInstr = Double.MAX_VALUE
        announcedAtHalfMileSet.clear()
        announcedAt250ftSet.clear()
        longStretchAnnouncedSet.clear()
        arrivalSide = null
        _currentSpeedLimitMph.value = null
        // Reset faster route and wrong-way state
        _fasterRouteAvailable.value = null
        wrongWayCount = 0; wrongWayTtsLastMs = 0L; hasBearingData = false
        lastNavTtsMs = 0L
        lastCameraUpdateLat = 0.0
        lastCameraUpdateLon = 0.0
        alertTtsManager.interrupt()
    }

    fun clearError() {
        _errorMessage.value = null
    }

    // ── Offline geocoder ───────────────────────────────────────────────────────

    /** Returns true if an offline geocoder DB exists for the given state abbreviation. */
    fun isOfflineGeocoderAvailable(stateAbbr: String): Boolean =
        offlineGeocoder.isAvailable(stateAbbr)

    // Cached active state code — loaded async at init, avoids blocking reads
    private val _activeStateCode = MutableStateFlow("fl")
    val activeStateCode: StateFlow<String> = _activeStateCode.asStateFlow()

    private fun loadActiveStateCodeAsync() {
        viewModelScope.launch {
            val prefs = SecureDataStore.get(context, "an_prefs")
            val code = prefs.data.first()[stringPreferencesKey("selected_state_v1")]?.lowercase() ?: "fl"
            _activeStateCode.value = code
        }
    }

    /**
     * Returns the active state abbreviation (lowercase) from the cached StateFlow.
     * Falls back to "fl" if no state has been selected.
     */
    fun getActiveStateCode(): String = _activeStateCode.value

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

                    withContext(Dispatchers.Default) {
                        if (location.hasBearing()) {
                            currentBearingDegrees = location.bearing
                            hasBearingData = true
                        }
                        currentSpeedMps = location.speed

                        val userLatLon = LatLon(location.latitude, location.longitude)
                        val currentIdx = currentInstructionIndex
                        if (currentIdx >= instructions.size) {
                            onNavigationComplete()
                            return@withContext
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

                        // Phase 1.1: pre-announcements for upcoming maneuvers (highway always; non-highway at high speed)
                        checkPreAnnouncements(currentIdx, distToInstr, instructions, location.speed)

                        // ── Speed limit: update from route's pre-computed per-point speed limits ──
                        val closestPtIdx = findClosestRoutePointIndex(userLatLon, result.points)
                        val newLimit = result.speedLimitsMph.getOrNull(closestPtIdx)
                        if (newLimit != _currentSpeedLimitMph.value) {
                            _currentSpeedLimitMph.value = newLimit
                        }

                        // ── Route progress: sum of completed instruction distances / total ──
                        if (result.distanceMeters > 0) {
                            val completedDist = instructions.take(currentIdx).sumOf { it.distanceMeters }
                            val totalDist = result.distanceMeters
                            _routeProgress.value = (completedDist / totalDist).toFloat().coerceIn(0f, 1f)
                        }

                        // ── Item 4: Camera update tick — only fire when moved >15m ──────────
                        val now = System.currentTimeMillis()
                        if (haversineMeters(userLatLon, LatLon(lastCameraUpdateLat, lastCameraUpdateLon)) > 15.0) {
                            lastCameraUpdateLat = userLatLon.lat
                            lastCameraUpdateLon = userLatLon.lon
                            _cameraUpdateTick.value = now
                        }

                        // ── Distance-based TTS announcements (0.5 mi and 250 ft) ──────────────
                        val isContinueStraight = currentInstr.sign == 0
                        val distToNextTurn = currentInstr.distanceMeters
                        // Continue-straight only speaks when the gap to the next real turn is >1 mile
                        val shouldSpeakAtAll = !isContinueStraight || distToNextTurn > 1609.0
                        // Half-mile warning only makes sense when the turn is >1 mile away
                        val shouldAnnounceHalfMile = distToNextTurn > 1609.0 && shouldSpeakAtAll

                        // Speed-adaptive announcement thresholds
                        val earlyThreshold = max(804.7, currentSpeedMps.toDouble() * 15.0)
                        val closeThreshold = max(76.2, currentSpeedMps.toDouble() * 5.0)

                        val halfMileKey = "half_$currentIdx"
                        if (distToInstr <= earlyThreshold && distToInstr > closeThreshold && halfMileKey !in announcedAtHalfMileSet) {
                            announcedAtHalfMileSet.add(halfMileKey)
                            if (shouldAnnounceHalfMile) {
                                val distText = distanceToSpokenText(distToInstr)
                                val text = "$distText, ${turnToText(currentInstr)}"
                                speakNav(text, "nav_halfmile_$currentIdx", priority = false)
                            }
                        }

                        val ft250Key = "250ft_$currentIdx"
                        if (distToInstr <= closeThreshold && distToInstr > 15.0 && ft250Key !in announcedAt250ftSet) {
                            announcedAt250ftSet.add(ft250Key)
                            // Close announcement only fires for actual turns (sign != 0); skip for continue-straight
                            if (!isContinueStraight) {
                                // For the final instruction, use arrival-side announcement if available
                                if (currentInstr.sign == 4 && arrivalSide != null) {
                                    val side = arrivalSide!!
                                    val distText = distanceToSpokenText(distToInstr)
                                    speakNav(
                                        "$distText, your destination is on the $side",
                                        "nav_250ft_$currentIdx",
                                        priority = true
                                    )
                                } else {
                                    val batchText = buildBatchAnnouncement(currentIdx, instructions, distToInstr)
                                    speakNav(batchText, "nav_250ft_$currentIdx", priority = true)
                                }
                            }
                        }

                        // ── Bearing-based turn advancement ────────────────────────────────────
                        // Track if we've been close to the turn point (speed-adaptive: 2 seconds warning min 50m)
                        val reachedThreshold = max(50.0, currentSpeedMps.toDouble() * 2.0)
                        if (distToInstr < reachedThreshold) {
                            reachedCurrentTurn = true
                        }

                        // Advance only when: (1) we reached the turn, (2) distance is now increasing, (3) bearing matches post-turn direction
                        if (reachedCurrentTurn && distToInstr > previousDistToInstr && currentIdx + 1 < instructions.size) {
                            val nextInstr = instructions[currentIdx + 1]
                            val expectedBearing = bearingBetween(currentInstr.point, nextInstr.point)
                            val bearingDiff = abs(normalizeBearing(currentBearingDegrees.toDouble() - expectedBearing))
                            if (bearingDiff < 45.0) {
                                // Confirmed: user has passed the turn and is heading in the right direction
                                // Only speak turn confirmation for actual turns (not continue-straight)
                                if (currentInstr.sign != 0) {
                                    speakShortConfirmation(currentInstr, priority = true)
                                }
                                val newIdx = currentIdx + 1
                                currentInstructionIndex = newIdx
                                _currentInstruction.value = nextInstr
                                _distanceToNext.value = haversineMeters(userLatLon, nextInstr.point)
                                reachedCurrentTurn = false
                                previousDistToInstr = Double.MAX_VALUE
                                // Reset announcement flags for new instruction
                                announcedAtHalfMileSet.remove("half_$currentIdx")
                                announcedAt250ftSet.remove("250ft_$currentIdx")
                                // Long-stretch announcement: if next turn is >2 miles away, announce it
                                // Skip if the new instruction is continue-straight with dist ≤1 mile
                                val nextIsContinueStraight = nextInstr.sign == 0
                                val nextIsLongStretch = nextInstr.distanceMeters > 3218.0
                                if (nextIsLongStretch && newIdx !in longStretchAnnouncedSet) {
                                    longStretchAnnouncedSet.add(newIdx)
                                    val road = when {
                                        !nextInstr.ref.isNullOrBlank() -> nextInstr.ref!!
                                        nextInstr.streetName.isNotBlank() -> nextInstr.streetName
                                        else -> null
                                    }
                                    val distMi = nextInstr.distanceMeters / 1609.344
                                    val distStr = if (distMi >= 10) "${distMi.toInt()} miles"
                                                  else "%.1f miles".format(distMi)
                                    val stretchMsg = if (road != null) "Continue on $road for $distStr"
                                                     else "Continue for $distStr"
                                    speakNav(stretchMsg, "nav_longstretch_$newIdx", priority = false)
                                } else if (nextIsContinueStraight && !nextIsLongStretch) {
                                    // Silently advance display — user just turned, don't narrate short continue-straight
                                    AppLog.d(tag, "Post-turn: silently showing continue-straight (dist=${nextInstr.distanceMeters.toInt()}m)")
                                }
                            }
                        } else {
                            previousDistToInstr = distToInstr
                        }

                        // ── Wrong-way detection (N6/N7/N8) ──────────────────────
                        if (hasBearingData) {
                            // N8: Use route polyline direction, with last-instruction fallback
                            val expectedBearing = if (closestPtIdx + 1 < result.points.size)
                                bearingBetween(result.points[closestPtIdx], result.points[closestPtIdx + 1])
                            else if (currentIdx + 1 < instructions.size)
                                bearingBetween(userLatLon, instructions[currentIdx + 1].point)
                            else routeDestination?.let { bearingBetween(userLatLon, it) } ?: 0.0

                            val bearingDiff = abs(normalizeBearing(currentBearingDegrees.toDouble() - expectedBearing))
                            if (bearingDiff > 150.0) {
                                wrongWayCount++
                                // N6: TTS cooldown to prevent spam
                                val nowWw = System.currentTimeMillis()
                                if (wrongWayCount >= 3 && nowWw - wrongWayTtsLastMs >= 15_000L) {
                                    wrongWayTtsLastMs = nowWw
                                    alertTtsManager.speakIfEnabled("Make a U-turn when possible", TtsCategory.NAVIGATION, "wrong_way")
                                    AppLog.w(tag, "Wrong-way detected: $wrongWayCount consecutive, bearingDiff=${bearingDiff.toInt()}°")
                                }
                                if (wrongWayCount >= 5) {
                                    AppLog.w(tag, "Wrong-way persistent ($wrongWayCount updates) — triggering reroute")
                                    val nowMs = System.currentTimeMillis()
                                    if (!isRerouting && nowMs - lastRerouteMs >= REROUTE_COOLDOWN_MS) {
                                        // N6: only reset count when reroute actually fires
                                        wrongWayCount = 0
                                        lastRerouteMs = nowMs
                                        isRerouting = true
                                        triggerReroute(userLatLon)
                                    }
                                    // If cooldown blocked, don't reset — keep counting
                                }
                            } else {
                                if (wrongWayCount > 0) {
                                    AppLog.d(tag, "Wrong-way resolved after $wrongWayCount updates")
                                }
                                wrongWayCount = 0
                            }
                        }

                        checkOffRouteAndReroute(userLatLon, result.points)

                        // ── Surveillance camera proximity alerts ───────────────
                        checkSurveillanceCameraProximity(userLatLon, result.points)
                    }
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
            // Directional bearing check: suppress ONLY if heading in the FORWARD route direction
            // A U-turn aligns with the route going BACKWARD — that should NOT be suppressed
            if (hasBearingData && routePoints.size >= 2) {
                val nearestIdx = findClosestRoutePointIndex(userLatLon, routePoints)
                // Forward direction: from nearest point toward the next point (toward destination)
                val fwdEnd = if (nearestIdx + 1 < routePoints.size) nearestIdx + 1 else nearestIdx
                val fwdStart = if (fwdEnd > nearestIdx) nearestIdx else maxOf(0, nearestIdx - 1)
                if (fwdStart != fwdEnd) {
                    val forwardBearing = bearingBetween(routePoints[fwdStart], routePoints[fwdEnd])
                    val bearingDiff = abs(normalizeBearing(currentBearingDegrees.toDouble() - forwardBearing))
                    if (bearingDiff <= 45.0) {
                        // Heading forward along route = GPS drift, not real deviation
                        AppLog.d(tag, "Off-route ${minDist.toInt()}m but heading forward (diff=${bearingDiff.toInt()}°) — GPS drift")
                        return
                    }
                    // bearingDiff > 45° = genuinely off-route (U-turn, wrong turn, etc.)
                }
            }
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
            // Speak "Recalculating" immediately before the new route loads
            alertTtsManager.speak("Recalculating", "reroute")
            sentryCaptureThrottled("reroute", 60_000L, "Rerouting: from=${currentPos.lat},${currentPos.lon}")
            AppLog.i(tag, "Rerouting from ${currentPos.lat},${currentPos.lon} to ${dest.lat},${dest.lon}")

            val forceOnline = routingRepository.offlineRoutingUnsupported
            var result = when {
                forceOnline -> {
                    AppLog.w(tag, "triggerReroute: offline routing unsupported on this device")
                    null
                }
                else -> {
                    // Try with heading hint to avoid U-turns
                    val withHeading = routingRepository.calculateRoute(currentPos, dest, routeProfile, currentBearingDegrees)
                    // If first instruction is still a U-turn, retry without heading constraint
                    if (withHeading != null && withHeading.instructions.firstOrNull()?.sign == -7) {
                        AppLog.w(tag, "Reroute with heading produced U-turn, retrying without heading")
                        val fallback = routingRepository.calculateRoute(currentPos, dest, routeProfile, null)
                        if (fallback != null) fallback else withHeading
                    } else {
                        withHeading
                    }
                }
            }

            if (result != null) {
                _routeResult.value = result
                _routePoints.value = result.points
                currentInstructionIndex = 0
                _currentInstruction.value = result.instructions.firstOrNull()
                _distanceToNext.value = result.instructions.firstOrNull()?.distanceMeters ?: 0.0
                routeStartMs = System.currentTimeMillis()
                initialDurationSeconds = result.durationSeconds
                initialDistanceMeters = result.distanceMeters
                offRouteCount = 0
                wrongWayCount = 0
                _fasterRouteAvailable.value = null
                // Clear camera announcements — new route may pass different cameras
                announcedAtHalfMile.clear()
                announcedAt100m.clear()
                survTtsCooldowns.clear()
                lastAnySurvAnnouncementMs = 0L
                lastAnnouncementLatLon = null
                lastSpokenInstruction = null
                // Reset bearing-based advancement and nav TTS state
                reachedCurrentTurn = false
                previousDistToInstr = Double.MAX_VALUE
                announcedAtHalfMileSet.clear()
                announcedAt250ftSet.clear()
                longStretchAnnouncedSet.clear()
                arrivalSide = computeArrivalSide(result)
                _currentSpeedLimitMph.value = null
                lastNavTtsMs = 0L
                AppLog.i(tag, "Reroute complete: ${result.points.size} points, ${result.distanceMeters.toInt()}m")
                // Speak first instruction of new route
                result.instructions.firstOrNull()?.let { firstInstr ->
                    val action = signToAction(firstInstr.sign)
                    val street = if (firstInstr.streetName.isNotBlank()) " onto ${firstInstr.streetName}" else ""
                    alertTtsManager.speak("$action$street", "reroute_first_instr")
                }
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
    /**
     * Periodically check (every 5 minutes) if there is a faster route available.
     * If the alternative is >2 minutes faster than the remaining ETA, expose it
     * via [fasterRouteAvailable] without auto-switching.
     * Runs only while navigation is active.
     */
    private fun startFasterRouteChecking() {
        fasterRouteCheckJob?.cancel()
        fasterRouteCheckJob = viewModelScope.launch {
            delay(FASTER_ROUTE_CHECK_INTERVAL_MS)
            while (_isNavigating.value) {
                try {
                    checkForFasterRoute()
                } catch (e: Exception) {
                    AppLog.w(tag, "Faster route check failed: ${e.message}")
                }
                delay(FASTER_ROUTE_CHECK_INTERVAL_MS)
            }
        }
    }

    private suspend fun checkForFasterRoute() {
        val dest = routeDestination ?: return
        val from = _lastRouteFrom ?: return
        val currentResult = _routeResult.value ?: return
        if (!_isNavigating.value) return

        // Compute remaining duration (approximate: use current route remaining)
        val elapsedS = if (routeStartMs > 0L) (System.currentTimeMillis() - routeStartMs) / 1000L else 0L
        val remainingCurrentS = maxOf(0L, currentResult.durationSeconds - elapsedS)

        // Calculate a fresh alternative route from current cached position
        if (routingRepository.offlineRoutingUnsupported) return
        val profile = routeProfile.let {
            // Use base "car" profile for faster route check regardless of current preference
            if (it == "car_avoid_alpr") "car" else it
        }
        val alternative = routingRepository.calculateRoute(from, dest, profile)
            ?: return

        val diff = remainingCurrentS - alternative.durationSeconds
        if (diff >= FASTER_ROUTE_THRESHOLD_SECONDS) {
            AppLog.i(tag, "Faster route found: saves ${diff}s (${diff / 60}min) vs current remaining ${remainingCurrentS}s")
            _fasterRouteAvailable.value = alternative
        } else if (diff < FASTER_ROUTE_THRESHOLD_SECONDS && _fasterRouteAvailable.value != null) {
            // Difference dropped below threshold — clear the banner
            AppLog.d(tag, "Faster route no longer relevant: diff=${diff}s < threshold")
            _fasterRouteAvailable.value = null
        }
    }

    /**
     * Find the index of the route point closest to [userLatLon].
     * Used to determine which part of the route is "ahead" of the user.
     */
    /**
     * N5 + Item 10: Forward-search from [lastClosestPtIdx] using fast equirectangular distance.
     * Falls back to full scan if forward search doesn't improve (reroute, jump, etc.).
     */
    internal fun findClosestRoutePointIndex(userLatLon: LatLon, routePoints: List<LatLon>): Int {
        if (routePoints.isEmpty()) return 0
        val searchStart = lastClosestPtIdx.coerceIn(0, routePoints.size - 1)
        var closestIdx = searchStart
        var closestDist = fastDistMeters(userLatLon, routePoints[searchStart])
        // Forward search up to 200 points
        val searchEnd = minOf(searchStart + 200, routePoints.size)
        for (i in searchStart + 1 until searchEnd) {
            val d = fastDistMeters(userLatLon, routePoints[i])
            if (d < closestDist) { closestDist = d; closestIdx = i }
        }
        // Full scan fallback if we didn't improve and are far from route
        if (closestIdx == searchStart && closestDist > 200.0) {
            for (i in routePoints.indices) {
                val d = fastDistMeters(userLatLon, routePoints[i])
                if (d < closestDist) { closestDist = d; closestIdx = i }
            }
        }
        lastClosestPtIdx = closestIdx
        return closestIdx
    }

    private suspend fun checkSurveillanceCameraProximity(
        userLatLon: LatLon,
        routePoints: List<LatLon>
    ) {
        if (routePoints.isEmpty()) return

        // Only check cameras against route points AHEAD of the user's current position
        val closestIdx = findClosestRoutePointIndex(userLatLon, routePoints)
        val aheadPoints = routePoints.subList(closestIdx, routePoints.size)

        // Use cached camera list (refreshed per-state, not per-location-update)
        val cameras = _cachedCameras.value
        if (cameras.isEmpty()) return

        // ── Cluster cameras into 25m grid cells ──────────────────────────────
        // At ~28°N (Florida), 1° lat ≈ 110,574m, 1° lon ≈ 97,304m
        // 25m grid: lat step ≈ 0.000226, lon step ≈ 0.000257
        // We use a simple grid key: (lat_bucket, lon_bucket) → list of cameras
        val gridSizeM = 25.0
        val latStep = gridSizeM / 110574.0   // degrees per 25m latitude
        val lonStep = gridSizeM / (110574.0 * kotlin.math.cos(Math.toRadians(userLatLon.lat)))  // adjusted for longitude

        data class GridCell(val latBucket: Long, val lonBucket: Long)

        val cellMap = mutableMapOf<GridCell, MutableList<CachedCamera>>()
        for (cam in cameras) {
            val distToUser = haversineMeters(userLatLon, LatLon(cam.lat, cam.lon))
            if (distToUser > 5000.0) continue  // pre-filter: skip if >5km away

            val isOnRoute = aheadPoints.any { rp ->
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
                sentryCaptureThrottled("alpr_proximity", 300_000L, "ALPR proximity alert: cameras nearby during navigation")
                alertTtsManager.speakIfEnabled("Surveillance ahead", TtsCategory.SURVEILLANCE, "surv_half_$cellKey")
                AppLog.i(tag, "TTS: Surveillance ahead (cell=$cellKey, ${camsInCell.size} cameras, dist=${distToUser.toInt()}m)")
                return  // one announcement per location update
            }
        }
    }

    private fun onNavigationComplete() {
        _isNavigating.value = false
        locationJob?.cancel()
        alertTtsManager.speakIfEnabled("You have arrived at your destination.", TtsCategory.NAVIGATION, "arrive")
        AppLog.i(tag, "Navigation complete - destination reached")
    }

    /**
     * Pre-announce maneuvers at distance thresholds.
     * - Highway maneuvers (isHighwayManeuver): always pre-announce at 1mi, 0.5mi, 1000ft.
     * - Non-highway turns at high speed (>20 m/s / ~45mph): pre-announce at 0.5mi and 1000ft only.
     */
    private fun checkPreAnnouncements(
        currentIdx: Int,
        distToCurrentM: Double,
        instructions: List<TurnInstruction>,
        speedMps: Float
    ) {
        if (currentIdx < 0 || currentIdx >= instructions.size) return
        val instr = instructions[currentIdx]

        val ft = distToCurrentM * 3.28084
        val mi = distToCurrentM / 1609.344

        // Highway maneuvers: always pre-announce at 1mi, 0.5mi, and 1000ft
        if (instr.isHighwayManeuver) {
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
            return
        }

        // Non-highway turns at high speed (>20 m/s / ~45mph): pre-announce at 0.5mi and 1000ft
        if (speedMps < 20f) return

        when {
            mi <= 0.55 && mi > 0.22 && currentIdx !in preAnnouncedAtHalfMi -> {
                preAnnouncedAtHalfMi.add(currentIdx)
                alertTtsManager.speak("In half a mile, ${instr.text}", "preannounce_halfmi_$currentIdx")
            }
            ft <= 1100 && ft > 300 && currentIdx !in preAnnouncedAt1000ft -> {
                preAnnouncedAt1000ft.add(currentIdx)
                alertTtsManager.speak("In 1000 feet, ${instr.text}", "preannounce_1000ft_$currentIdx")
            }
        }
    }

    // ── Navigation TTS helpers ─────────────────────────────────────────────────

    /**
     * Build a TTS announcement that includes the current turn plus any
     * subsequent turns within the batch threshold (speed-adaptive, min 250ft/76.2m).
     * Caps at 3 turns in one announcement.
     */
    private fun buildBatchAnnouncement(
        currentIdx: Int,
        instructions: List<TurnInstruction>,
        distToCurrentM: Double = 76.2
    ): String {
        val current = instructions[currentIdx]
        val distText = distanceToSpokenText(distToCurrentM)
        val parts = mutableListOf("$distText, ${turnToText(current)}")

        // Speed-adaptive batch threshold: 3 seconds warning, min 250ft (76.2m)
        val batchThreshold = max(76.2, currentSpeedMps.toDouble() * 3.0)

        var lookIdx = currentIdx + 1
        var totalDist = 0.0
        while (lookIdx < instructions.size && parts.size < 3) {
            val nextInstr = instructions[lookIdx]
            totalDist += nextInstr.distanceMeters
            if (totalDist > batchThreshold) break

            val connector = if (nextInstr.distanceMeters < 15.2) "then immediately"
                            else "then in ${(nextInstr.distanceMeters * 3.281).toInt()} feet"
            parts.add("$connector ${turnToShortText(nextInstr)}")
            lookIdx++
        }

        return parts.joinToString(", ")
    }

    /** Full turn text: "turn left onto Oak Street" */
    private fun turnToText(instr: TurnInstruction): String {
        // Roundabout: "at the roundabout, take the 2nd exit"
        if (instr.sign == 6 && !instr.exitNumber.isNullOrBlank()) {
            val exitOrdinal = exitNumberToOrdinal(instr.exitNumber)
            val street = if (instr.streetName.isNotBlank()) " onto ${instr.streetName}" else ""
            return "at the roundabout, take the $exitOrdinal exit$street"
        }
        // Highway exit: "take exit 82B toward downtown"
        if (instr.isHighwayManeuver && !instr.exitNumber.isNullOrBlank()) {
            val exitNum = instr.exitNumber
            val street = if (instr.streetName.isNotBlank()) " toward ${instr.streetName}"
                         else if (instr.ref != null) " toward ${instr.ref}" else ""
            return "take exit $exitNum$street"
        }
        val action = signToAction(instr.sign)
        val street = if (instr.streetName.isNotBlank()) " onto ${instr.streetName}" else ""
        val ref = if (instr.ref != null) " ${instr.ref}" else ""
        return "$action$street$ref"
    }

    /** Convert a numeric exit number string to an ordinal word (1→"1st", 2→"2nd", etc.). */
    private fun exitNumberToOrdinal(num: String): String {
        val n = num.trim().toIntOrNull() ?: return num
        return when (n) {
            1 -> "1st"
            2 -> "2nd"
            3 -> "3rd"
            else -> "${n}th"
        }
    }

    /** Short turn text for batch: same as full for now */
    private fun turnToShortText(instr: TurnInstruction): String = turnToText(instr)

    /** Speak with distance prefix: "In half a mile, turn left onto Oak Street" */
    private fun speakWithPrefix(prefix: String, instr: TurnInstruction, priority: Boolean = false) {
        val text = "$prefix, ${turnToText(instr)}"
        speakNav(text, "nav_prefix_${instr.hashCode()}", priority = priority)
    }

    /** Short confirmation when actually passing a turn: "Turn left" */
    private fun speakShortConfirmation(instr: TurnInstruction, priority: Boolean = false) {
        val action = signToAction(instr.sign)
        speakNav(action, "nav_confirm_${instr.hashCode()}", priority = priority)
    }

    /** Convert a distance in meters to a natural spoken distance string. */
    private fun distanceToSpokenText(distMeters: Double): String {
        val distFeet = distMeters * 3.281
        val distMiles = distMeters / 1609.34
        return when {
            distMiles >= 0.9 -> "In ${"%.1f".format(distMiles)} miles"
            distMiles >= 0.4 -> "In half a mile"
            distFeet >= 900  -> "In ${(distFeet / 100).toInt() * 100} feet"
            distFeet >= 200  -> "In ${(distFeet / 50).toInt() * 50} feet"
            else             -> "In ${distFeet.toInt()} feet"
        }
    }

    /** Map GraphHopper sign integer to spoken action. */
    private fun signToAction(sign: Int): String = when (sign) {
        -7 -> "make a U-turn"
        -6 -> "keep left"
        -3 -> "turn sharp left"
        -2 -> "turn left"
        -1 -> "turn slight left"
        0  -> "continue straight"
        1  -> "turn slight right"
        2  -> "turn right"
        3  -> "turn sharp right"
        4  -> "arrive at destination"
        5  -> "arrive at waypoint"
        6  -> "take the roundabout"
        7  -> "keep right"
        8  -> "keep right"
        else -> "continue"
    }

    // ── Bearing / direction helpers ────────────────────────────────────────────

    /** Convert bearing degrees to cardinal direction word. */
    private fun bearingToCardinal(bearing: Double): String {
        val b = ((bearing % 360) + 360) % 360
        return when {
            b < 22.5  -> "north"
            b < 67.5  -> "northeast"
            b < 112.5 -> "east"
            b < 157.5 -> "southeast"
            b < 202.5 -> "south"
            b < 247.5 -> "southwest"
            b < 292.5 -> "west"
            b < 337.5 -> "northwest"
            else      -> "north"
        }
    }

    /**
     * Compute which side of the road the destination is on relative to the final approach segment.
     * Uses cross product of the last segment vector and the vector from segment end to destination.
     * Returns "left", "right", or null if insufficient data.
     */
    private fun computeArrivalSide(route: RouteResult): String? {
        val pts = route.points
        if (pts.size < 2) return null
        // Last two route points define the approach direction
        val segA = pts[pts.size - 2]
        val segB = pts[pts.size - 1]
        val dest = route.instructions.lastOrNull()?.point ?: segB
        // Cross product (segB - segA) × (dest - segA) in lat/lon plane
        val ax = segB.lon - segA.lon
        val ay = segB.lat - segA.lat
        val bx = dest.lon - segA.lon
        val by = dest.lat - segA.lat
        val cross = ax * by - ay * bx
        return if (cross > 0) "right" else if (cross < 0) "left" else null
    }

    private fun bearingBetween(from: LatLon, to: LatLon): Double {
        val dLon = Math.toRadians(to.lon - from.lon)
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private fun normalizeBearing(diff: Double): Double {
        var d = diff % 360
        if (d > 180) d -= 360
        if (d < -180) d += 360
        return d
    }

    override fun onCleared() {
        super.onCleared()
        locationJob?.cancel()
        fasterRouteCheckJob?.cancel()
        // tts removed — alertTtsManager lifecycle managed by Hilt
    }

    companion object {
        /** Approximate bounding boxes (minLat, maxLat, minLon, maxLon) for all 50 US states. */
        val STATE_BOUNDING_BOXES: Map<String, BoundingBox> = mapOf(
            "al" to BoundingBox(30.1, 35.0, -88.5, -84.9),
            "ak" to BoundingBox(51.2, 71.4, -179.1, -129.9),
            "az" to BoundingBox(31.3, 37.0, -114.8, -109.0),
            "ar" to BoundingBox(33.0, 36.5, -94.6, -89.6),
            "ca" to BoundingBox(32.5, 42.0, -124.5, -114.1),
            "co" to BoundingBox(36.9, 41.0, -109.1, -102.0),
            "ct" to BoundingBox(40.9, 42.1, -73.7, -71.8),
            "de" to BoundingBox(38.4, 39.8, -75.8, -75.0),
            "fl" to BoundingBox(24.4, 31.1, -87.7, -79.9),
            "ga" to BoundingBox(30.4, 35.0, -85.6, -80.8),
            "hi" to BoundingBox(18.9, 22.2, -160.2, -154.8),
            "id" to BoundingBox(41.9, 49.0, -117.2, -111.0),
            "il" to BoundingBox(36.9, 42.5, -91.5, -87.5),
            "in" to BoundingBox(37.8, 41.8, -88.1, -84.8),
            "ia" to BoundingBox(40.4, 43.5, -96.6, -90.1),
            "ks" to BoundingBox(36.9, 40.0, -102.1, -94.6),
            "ky" to BoundingBox(36.5, 39.1, -89.6, -81.9),
            "la" to BoundingBox(28.9, 33.0, -94.0, -88.8),
            "me" to BoundingBox(43.0, 47.5, -71.1, -66.9),
            "md" to BoundingBox(37.9, 39.7, -79.5, -75.0),
            "ma" to BoundingBox(41.2, 42.9, -73.5, -69.9),
            "mi" to BoundingBox(41.7, 48.3, -90.4, -82.4),
            "mn" to BoundingBox(43.5, 49.4, -97.2, -89.5),
            "ms" to BoundingBox(30.2, 35.0, -91.7, -88.1),
            "mo" to BoundingBox(35.9, 40.6, -95.8, -89.1),
            "mt" to BoundingBox(44.4, 49.0, -116.0, -104.0),
            "ne" to BoundingBox(40.0, 43.0, -104.1, -95.3),
            "nv" to BoundingBox(35.0, 42.0, -120.0, -114.0),
            "nh" to BoundingBox(42.7, 45.3, -72.6, -70.6),
            "nj" to BoundingBox(38.9, 41.4, -75.6, -73.9),
            "nm" to BoundingBox(31.3, 37.0, -109.1, -103.0),
            "ny" to BoundingBox(40.5, 45.0, -79.8, -71.9),
            "nc" to BoundingBox(33.8, 36.6, -84.3, -75.5),
            "nd" to BoundingBox(45.9, 49.0, -104.1, -96.6),
            "oh" to BoundingBox(38.4, 42.0, -84.8, -80.5),
            "ok" to BoundingBox(33.6, 37.0, -103.0, -94.4),
            "or" to BoundingBox(41.9, 46.3, -124.6, -116.5),
            "pa" to BoundingBox(39.7, 42.3, -80.5, -74.7),
            "ri" to BoundingBox(41.1, 42.0, -71.9, -71.1),
            "sc" to BoundingBox(32.0, 35.2, -83.4, -78.5),
            "sd" to BoundingBox(42.5, 45.9, -104.1, -96.4),
            "tn" to BoundingBox(34.9, 36.7, -90.3, -81.6),
            "tx" to BoundingBox(25.8, 36.5, -106.6, -93.5),
            "ut" to BoundingBox(37.0, 42.0, -114.1, -109.0),
            "vt" to BoundingBox(42.7, 45.0, -73.4, -71.5),
            "va" to BoundingBox(36.5, 39.5, -83.7, -75.2),
            "wa" to BoundingBox(45.5, 49.0, -124.7, -116.9),
            "wv" to BoundingBox(37.2, 40.6, -82.6, -77.7),
            "wi" to BoundingBox(42.5, 47.1, -92.9, -86.8),
            "wy" to BoundingBox(41.0, 45.0, -111.1, -104.0),
            "dc" to BoundingBox(38.8, 39.0, -77.1, -76.9)
        )
    }
}

// ── Haversine distance helper ──────────────────────────────────────────────────

/**
 * Fast equirectangular distance approximation. ~10x faster than haversine.
 * Accurate within 0.5% for distances < 50km at mid-latitudes.
 * Use for pre-filtering; haversine for final precision checks.
 */
private fun fastDistMeters(a: LatLon, b: LatLon): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon) * cos(Math.toRadians((a.lat + b.lat) / 2.0))
    return 6371000.0 * sqrt(dLat * dLat + dLon * dLon)
}

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
