package com.aegisnav.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aegisnav.app.correlation.CorrelationEngine
import com.aegisnav.app.util.AppLog
import com.aegisnav.app.correlation.ThreatLevel
import com.aegisnav.app.data.dao.ALPRBlocklistDao
import com.aegisnav.app.flock.FlockSightingDao
import com.aegisnav.app.data.model.ALPRBlocklist
import com.aegisnav.app.data.model.IgnoreListEntry
import com.aegisnav.app.data.model.Report
import com.aegisnav.app.data.model.ScanLog
import com.aegisnav.app.data.model.ThreatEvent
import com.aegisnav.app.data.repository.AppPreferencesRepository
import com.aegisnav.app.data.repository.IgnoreListRepository
import com.aegisnav.app.data.repository.ReportsRepository
import com.aegisnav.app.data.repository.SavedLocationRepository
import com.aegisnav.app.data.repository.ScanLogRepository
import com.aegisnav.app.data.repository.ThreatEventRepository
import com.aegisnav.app.p2p.IncomingReport
import com.aegisnav.app.p2p.P2PManager
import com.aegisnav.app.baseline.BaselineEngine
import com.aegisnav.app.signal.SignalTriangulator
import com.aegisnav.app.tracker.TrackerAlert
import com.aegisnav.app.tracker.TrackerDetectionEngine
import com.aegisnav.app.data.dao.WatchlistDao
import com.aegisnav.app.data.model.WatchlistEntry
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aegisnav.app.security.SecureDataStore
import com.aegisnav.app.TileSourceResolver
import com.aegisnav.app.routing.RoutingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class MainViewModel @Inject constructor(
    private val reportsRepository: ReportsRepository,
    private val alprBlocklistDao: ALPRBlocklistDao,
    private val scanLogRepository: ScanLogRepository,
    private val ignoreListRepository: IgnoreListRepository,
    private val correlationEngine: CorrelationEngine,
    private val p2pManager: P2PManager,
    private val threatEventRepository: ThreatEventRepository,
    private val flockSightingDao: FlockSightingDao,
    private val policeSightingDao: com.aegisnav.app.police.PoliceSightingDao,
    private val officerUnitDao: com.aegisnav.app.police.OfficerUnitDao,
    private val redLightCameraDao: com.aegisnav.app.data.dao.RedLightCameraDao,
    private val speedCameraDao: com.aegisnav.app.data.dao.SpeedCameraDao,
    val scanState: ScanState,
    private val appPrefs: AppPreferencesRepository,
    private val trackerDetectionEngine: TrackerDetectionEngine,
    private val savedLocationRepository: SavedLocationRepository,
    // Phase 2B
    private val baselineEngine: BaselineEngine,
    private val signalTriangulator: SignalTriangulator,
    @ApplicationContext private val appContext: android.content.Context,
    // Watchlist
    private val watchlistDao: WatchlistDao,
    // Tracker / intelligence DAOs
    private val beaconSightingDao: com.aegisnav.app.tracker.BeaconSightingDao,
    private val baselineDao: com.aegisnav.app.baseline.BaselineDao,
    private val followingEventDao: com.aegisnav.app.intelligence.FollowingEventDao,
    private val debugLogDao: com.aegisnav.app.data.dao.DebugLogDao,
    // DataStore instances (migrated from SharedPreferences)
    @Named("popup_prefs") private val popupDataStore: DataStore<Preferences>,
    @Named("an_prefs") private val anDataStore: DataStore<Preferences>,
    @Named("tracker_engine_prefs") private val trackerEngineDataStore: DataStore<Preferences>,
    @Named("app_prefs") private val appDataStore: DataStore<Preferences>,
    @Named("state_prefs") private val stateDataStore: DataStore<Preferences>,
    @Named("alpr_prefs") private val alprDataStore: DataStore<Preferences>,
    // Routing — switch graph when active state changes
    private val routingRepository: RoutingRepository
) : ViewModel() {

    val reports: StateFlow<List<Report>> = reportsRepository.getAllReports()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Phase 2B: Baseline Environment Learning (2.4) ────────────────────────

    /** Per-zone baseline status: LEARNING or BASELINE_ACTIVE. */
    val baselineZoneStatus: StateFlow<Map<String, BaselineEngine.ZoneStatus>> =
        baselineEngine.zoneStatus
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // ── Phase 2B: Signal Triangulation (2.6) ─────────────────────────────────

    /**
     * Live snapshot of triangulated device positions.
     * Updated whenever [SignalTriangulator.currentResults] changes.
     * Exposed to the map for probability-circle overlay rendering.
     */
    // Finding 1.3: currentTriangulationResults() removed — was dead code, no callers found.

    val ignoreList: StateFlow<List<IgnoreListEntry>> = ignoreListRepository.getAllEntries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val selectedStates: kotlinx.coroutines.flow.Flow<List<String>> =
        appPrefs.getSelectedStates()

    /** ALPR cameras with ignored entries filtered out of the map overlay.
     *  Returns ALL cameras from DB — viewport filtering handled by updateMapOverlays (200-cap).
     *  Previously filtered by selectedStates but cameras have state="" so none matched. */
    val alprBlocklist: StateFlow<List<ALPRBlocklist>> = combine(
        alprBlocklistDao.getAll(),
        ignoreListRepository.getAllEntries()
    ) { all, ignored ->
        val ignoredIds = ignored
            .filter { it.type == "ALPR" }
            .mapNotNull { it.address.removePrefix("alpr_cam:").toIntOrNull() }
            .toSet()
        if (ignoredIds.isEmpty()) all else all.filter { it.id !in ignoredIds }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentScanLogs: StateFlow<List<ScanLog>> = scanLogRepository.getRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val threatEvents: StateFlow<List<ThreatEvent>> = threatEventRepository.getAllNewestFirst()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Police sightings — displayed in Report History */
    val policeSightings: StateFlow<List<com.aegisnav.app.police.PoliceSighting>> =
        policeSightingDao.getAllNewestFirst()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Officer units — correlated unit records for police sightings */
    val officerUnits: StateFlow<List<com.aegisnav.app.police.OfficerUnit>> =
        officerUnitDao.getAllNewestFirst()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Count of police sightings — observed in MainActivity to drive the blue strobe */
    val policeSightingCount: StateFlow<Int> = policeSightingDao.countFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Red light camera locations for map overlay — filtered by selected states */
    val redLightCameras: StateFlow<List<com.aegisnav.app.data.model.RedLightCamera>> =
        selectedStates.flatMapLatest { states -> redLightCameraDao.getByStates(states) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Speed camera locations for map overlay — filtered by selected states */
    val speedCameras: StateFlow<List<com.aegisnav.app.data.model.SpeedCamera>> =
        selectedStates.flatMapLatest { states -> speedCameraDao.getByStates(states) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentDeviceAddresses: StateFlow<List<String>> = scanLogRepository
        .getRecentAddresses(System.currentTimeMillis() - 10 * 60 * 1000)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentDeviceLogs: StateFlow<List<ScanLog>> = recentScanLogs
        .map { logs ->
            logs.groupBy { it.deviceAddress }
                .mapNotNull { (_, entries) -> entries.maxByOrNull { it.timestamp } }
                .sortedByDescending { it.timestamp }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Previously used for companion device auto-suggestions (removed — feature defeated core purpose).
     *  Kept as an empty flow so SettingsScreen references still compile. */
    val suggestedIgnoreDevices: StateFlow<Set<String>> =
        kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet()).asStateFlow()

    // All unique devices seen this session - sourced from ScanState.scannedDevices
    // (Compose mutableStateMapOf populated by ScanService directly).
    // DB scan_logs table is never written to by ScanService; this is the real source.
    val sessionDevicesSeen: StateFlow<List<ScanLog>> = snapshotFlow {
        scanState.scannedDevices.values.sortedByDescending { it.timestamp }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Active State — single selected state for tile rendering ──────────────

    private val _activeState = MutableStateFlow<String?>(null)
    val activeState: StateFlow<String?> = _activeState.asStateFlow()

    /** Persists the selected state code to DataStore and refreshes the tile URI.
     *  Also triggers a one-time ALPR camera import for [code] if not already done,
     *  so cameras for the new state appear on the map without a reinstall. */
    fun setActiveState(code: String, context: android.content.Context) {
        _activeState.value = code
        viewModelScope.launch {
            anDataStore.edit { it[ACTIVE_STATE_KEY] = code }
            // Import ALPR cameras for this state if not already imported.
            // Idempotent — skips instantly on subsequent switches back to same state.
            val app = context.applicationContext as AegisNavApplication
            app.importAlprForStateIfNeeded(code)
        }
        _tileUri.value = TileSourceResolver.resolveUri(context, code)
        // Switch the routing graph to match the new active state
        routingRepository.switchToState(code)
    }

    /** Loads the persisted active state from DataStore; filters out deleted states. */
    fun loadActiveState(context: android.content.Context) {
        viewModelScope.launch {
            AppLog.i("ActiveState", "loadActiveState: ENTRY")
            val saved = try {
                kotlinx.coroutines.withTimeout(2000) { anDataStore.data.first()[ACTIVE_STATE_KEY] }
            } catch (e: Exception) {
                AppLog.w("ActiveState", "DataStore read failed/timed out: ${e.message}")
                null
            }
            if (saved != null && TileSourceResolver.resolveUri(context, saved) != null) {
                _activeState.value = saved
                _tileUri.value = TileSourceResolver.resolveUri(context, saved)
                AppLog.i("ActiveState", "loadActiveState: resolved=$saved, importing ALPR...")
                (context.applicationContext as? AegisNavApplication)?.importAlprForStateIfNeeded(saved)
            } else {
                // Fallback: auto-select first downloaded state, if any
                val first = TileSourceResolver.downloadedStateCodes(context).firstOrNull()
                if (first != null) {
                    _activeState.value = first
                    _tileUri.value = TileSourceResolver.resolveUri(context, first)
                    anDataStore.edit { it[ACTIVE_STATE_KEY] = first }
                    AppLog.i("ActiveState", "loadActiveState: fallback to first=$first, importing ALPR...")
                    (context.applicationContext as? AegisNavApplication)?.importAlprForStateIfNeeded(first)
                } else {
                    _activeState.value = null
                    _tileUri.value = null
                }
            }
            // Also set routing to active state
            _activeState.value?.let { code ->
                routingRepository.switchToState(code)
            }
        }
    }

    // ── Reactive tile URI — refreshed after download/delete ──────────────────

    private val _tileUri = MutableStateFlow<String?>(null)
    val tileUri: StateFlow<String?> = _tileUri.asStateFlow()

    fun refreshTileUri(context: android.content.Context) {
        val active = _activeState.value
        _tileUri.value = if (active != null) {
            TileSourceResolver.resolveUri(context, active)
                ?: TileSourceResolver.resolveUri(context) // fallback to any tile
        } else {
            TileSourceResolver.resolveUri(context)
        }
    }

    // ── Police marker lifecycle: dismissed MACs (in-memory TTL dismiss) ───────

    private val _dismissedTriangulationMacs = MutableStateFlow<Set<String>>(emptySet())
    val dismissedTriangulationMacs: StateFlow<Set<String>> = _dismissedTriangulationMacs.asStateFlow()

    // ── Watchlist ─────────────────────────────────────────────────────────────

    /** Live list of all watchlist entries (user-defined "always alert" MACs). */
    val watchlist: StateFlow<List<WatchlistEntry>> = watchlistDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun dismissTriangulationMarker(mac: String) {
        _dismissedTriangulationMacs.value = _dismissedTriangulationMacs.value + mac
        signalTriangulator.dismissDevice(mac)
    }

    // ── Persisted popup-reviewed state (survives app restarts) ───────────────

    // DataStore keys for popup_prefs and an_prefs
    private companion object PopupKeys {
        val KEY_REVIEWED_MACS        = stringPreferencesKey("reviewed_triangulation_macs")
        val KEY_MACS_LAST_CLEAR_MS   = longPreferencesKey("macs_last_clear_ms")
        val ACTIVE_STATE_KEY         = stringPreferencesKey("active_state")
    }

    /** MACs that the popup host has already reviewed (confirmed/dismissed/expired).
     *  Backed by DataStore (migrated from SharedPreferences popup_prefs).
     *  Cleared every 24 hours to prevent stale growth. */
    private val _reviewedTriangulationMacs: MutableStateFlow<Set<String>> = MutableStateFlow(
        run {
            // TODO(phase-refactor): Convert to async — blocking read at init time
            val prefs = runBlocking(Dispatchers.IO) { popupDataStore.data.first() }
            val lastClear = prefs[KEY_MACS_LAST_CLEAR_MS] ?: 0L
            if (System.currentTimeMillis() - lastClear > 24 * 60 * 60 * 1000L) {
                // More than 24h since last clear — reset the set
                runBlocking(Dispatchers.IO) {
                    popupDataStore.edit {
                        it.remove(KEY_REVIEWED_MACS)
                        it[KEY_MACS_LAST_CLEAR_MS] = System.currentTimeMillis()
                    }
                }
                emptySet()
            } else {
                val json = prefs[KEY_REVIEWED_MACS]
                if (json.isNullOrEmpty()) emptySet()
                else json.removeSurrounding("[", "]")
                    .split(",")
                    .map { it.trim().removeSurrounding("\"") }
                    .filter { it.isNotEmpty() }
                    .toSet()
            }
        }
    )
    val reviewedTriangulationMacs: StateFlow<Set<String>> = _reviewedTriangulationMacs.asStateFlow()

    fun addReviewedTriangulationMac(mac: String) {
        val updated = _reviewedTriangulationMacs.value + mac
        _reviewedTriangulationMacs.value = updated
        val json = updated.joinToString(",") { "\"$it\"" }.let { "[${it}]" }
        viewModelScope.launch { popupDataStore.edit { it[KEY_REVIEWED_MACS] = json } }
    }

    fun setReviewedTriangulationMacs(macs: Set<String>) {
        _reviewedTriangulationMacs.value = macs
        val json = macs.joinToString(",") { "\"$it\"" }.let { "[${it}]" }
        viewModelScope.launch { popupDataStore.edit { it[KEY_REVIEWED_MACS] = json } }
    }

    init {
        // loadActiveState() moved to MainActivity LaunchedEffect so it runs
        // after the activity is fully created and the encrypted DataStore is ready.
    }

    val p2pConnectionState: StateFlow<P2PManager.ConnectionState> = p2pManager.overallState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), P2PManager.ConnectionState.DISCONNECTED)

    val incomingReports: StateFlow<List<IncomingReport>> = combine(
        p2pManager.incomingReports,
        appPrefs.offlineMode
    ) { reports, offline ->
        if (!offline) reports else emptyList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** True when NOT in offline mode - live P2P reports active. */
    val liveReportsEnabled: StateFlow<Boolean> = appPrefs.offlineMode
        .map { !it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), !appPrefs.offlineMode.value)

    val offlineMode: StateFlow<Boolean> = appPrefs.offlineMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), appPrefs.offlineMode.value)

    // ── TTS preferences ────────────────────────────────────────────────────────
    val ttsMasterEnabled: StateFlow<Boolean> get() = appPrefs.ttsMasterEnabled
    val ttsTrackerEnabled: StateFlow<Boolean> get() = appPrefs.ttsTrackerEnabled
    val ttsPoliceEnabled: StateFlow<Boolean> get() = appPrefs.ttsPoliceEnabled
    val ttsSurveillanceEnabled: StateFlow<Boolean> get() = appPrefs.ttsSurveillanceEnabled
    val ttsConvoyEnabled: StateFlow<Boolean> get() = appPrefs.ttsConvoyEnabled

    fun setTtsMasterEnabled(v: Boolean) = appPrefs.setTtsMasterEnabled(v)
    fun setTtsTrackerEnabled(v: Boolean) = appPrefs.setTtsTrackerEnabled(v)
    fun setTtsPoliceEnabled(v: Boolean) = appPrefs.setTtsPoliceEnabled(v)
    fun setTtsSurveillanceEnabled(v: Boolean) = appPrefs.setTtsSurveillanceEnabled(v)
    fun setTtsConvoyEnabled(v: Boolean) = appPrefs.setTtsConvoyEnabled(v)

    /** Submit a report - triggers correlation with ring buffer, persists, broadcasts P2P */
    fun submitReport(
        type: String,
        subtype: String? = null,
        latitude: Double,
        longitude: Double,
        description: String,
        subOption: String? = null,
        isGroup: Boolean = false
    ) {
        val report = Report(
            type = type,
            subtype = subtype,
            subOption = subOption,
            isGroup = isGroup,
            latitude = latitude,
            longitude = longitude,
            description = description
        )
        if (!appPrefs.offlineMode.value) {
            // Online mode - CorrelationEngine handles persistence + P2P broadcast
            correlationEngine.correlate(report)
        } else {
            // Offline mode - persist locally only, no P2P broadcast
            viewModelScope.launch {
                reportsRepository.insert(report)
            }
        }
    }

    fun setOfflineMode(enabled: Boolean) {
        appPrefs.setOfflineMode(enabled)
        if (!enabled) {
            p2pManager.connect()
        } else {
            p2pManager.disconnect()
        }
    }

    /** Direct DB query for ignore list count — used by wizard to avoid Flow race condition. */
    suspend fun getIgnoreListCount(): Int = ignoreListRepository.getAllAddresses().size

    fun addToIgnoreList(address: String) {
        viewModelScope.launch {
            ignoreListRepository.addAddress(IgnoreListEntry(address = address, type = "UNKNOWN", label = ""))
        }
    }

    fun addToIgnoreList(address: String, type: String, label: String) {
        viewModelScope.launch {
            ignoreListRepository.addAddress(IgnoreListEntry(address = address, type = type, label = label))
        }
    }

    fun ignoreTrackerMac(mac: String) {
        viewModelScope.launch {
            ignoreListRepository.addAddress(
                com.aegisnav.app.data.model.IgnoreListEntry(
                    address = mac,
                    type = "BLE",
                    label = "Ignored tracker",
                    expiresAt = Long.MAX_VALUE
                )
            )
        }
    }

    /**
     * Phase 3 — Feature 3.9: Permanently ignore a MAC address.
     * Sets [IgnoreListEntry.permanent] = true so it survives expiry pruning.
     */
    fun addToPermanentIgnoreList(mac: String, type: String = "BLE", label: String = "Ignored (permanent)") {
        viewModelScope.launch {
            ignoreListRepository.addPermanent(
                com.aegisnav.app.data.model.IgnoreListEntry(
                    address = mac,
                    type = type,
                    label = label,
                    permanent = true,
                    expiresAt = Long.MAX_VALUE
                )
            )
        }
    }

    fun removeFromIgnoreList(address: String) {
        viewModelScope.launch {
            ignoreListRepository.removeAddress(address)
        }
    }

    /**
     * Inserts a user-reported ALPR camera at [lat]/[lon] with source="USER".
     * Returns the inserted entity with the real DB-assigned ID for undo support.
     * If an existing entry already exists within ~10 m of [lat]/[lon], returns
     * that existing entry without inserting a duplicate.
     *
     * @param desc Optional description for the camera (defaults to "User ALPR Camera").
     */
    suspend fun addUserAlprCamera(lat: Double, lon: Double, desc: String = "User ALPR Camera"): ALPRBlocklist {
        // Dedup check: ~0.0001° ≈ 11 m — if a nearby entry exists, return it without inserting
        val delta = 0.0001
        val existing = alprBlocklistDao.findWithinBounds(lat - delta, lat + delta, lon - delta, lon + delta)
        if (existing.isNotEmpty()) return existing.first()

        val camera = ALPRBlocklist(
            lat = lat,
            lon = lon,
            ssid = null,
            mac = null,
            desc = desc,
            reported = System.currentTimeMillis(),
            verified = false,
            source = "USER"
        )
        val insertedId = alprBlocklistDao.insert(camera)
        return camera.copy(id = insertedId.toInt())
    }

    fun deleteUserAlprCamera(camera: ALPRBlocklist) {
        viewModelScope.launch { alprBlocklistDao.deleteById(camera.id) }
    }

    /**
     * Adds an ALPR camera to the ignore list by its DB id.
     * Ignored cameras are filtered out of [alprBlocklist] flow above.
     */
    fun ignoreAlprCamera(camera: ALPRBlocklist) {
        viewModelScope.launch {
            ignoreListRepository.addAddress(
                IgnoreListEntry(
                    address = "alpr_cam:${camera.id}",
                    type = "ALPR",
                    label = camera.desc.take(60),
                    expiresAt = Long.MAX_VALUE  // never expires
                )
            )
        }
    }

    fun wipeAllData(context: android.content.Context) {
        viewModelScope.launch {
            // ── DB tables — user data only ────────────────────────────────────
            reportsRepository.deleteAll()
            scanLogRepository.deleteAll()
            flockSightingDao.deleteAll()
            alprBlocklistDao.deleteBySource("USER")   // keeps system cameras
            ignoreListRepository.deleteAll()
            threatEventRepository.deleteAll()
            savedLocationRepository.deleteAll()
            policeSightingDao.deleteAll()
            officerUnitDao.deleteAll()
            beaconSightingDao.deleteAll()
            baselineDao.deleteAll()
            followingEventDao.deleteAll()
            watchlistDao.deleteAll()
            debugLogDao.deleteAll()

            // ── DataStore — selective wipe (preserve map/download prefs) ─────────
            // Clear user-behavior flags only; keep download tracking keys so
            // the wizard doesn't re-trigger and map files aren't re-downloaded
            // after a factory reset when the tiles/geocoder/routing still exist on disk.
            anDataStore.edit {
                it.remove(stringPreferencesKey("init_popup_shown_v1"))
                it.remove(stringPreferencesKey("first_launch_ignore_shown_v1"))
                it.remove(longPreferencesKey("first_scan_start_ms"))
                // DO NOT remove "selected_state_v1" — wizard completion marker
                // DO NOT remove "active_state" — persisted active map state
            }
            trackerEngineDataStore.edit { it.clear() }
            popupDataStore.edit { it.clear() }
            appDataStore.edit { it.clear() }
            // DO NOT clear stateDataStore — it tracks which states are downloaded/selected;
            // map data files survive the wipe and should remain usable immediately.
            alprDataStore.edit { it.clear() }
            // p2p_prefs is not injected but can be cleared via SecureDataStore
            SecureDataStore.get(context, com.aegisnav.app.p2p.P2PManager.PREFS_NAME)
                .edit { it.clear() }
            // nav_prefs — recent address searches
            SecureDataStore.get(context, "nav_prefs")
                .edit { it.clear() }

            // ── Log files ─────────────────────────────────────────────────────
            java.io.File(context.filesDir, "crash_log.txt").delete()
            java.io.File(context.filesDir, "an_debug.log").delete()
        }
    }

    fun upvoteReport(id: Int) {
        viewModelScope.launch { reportsRepository.upvote(id) }
    }

    fun clearReport(id: Int) {
        viewModelScope.launch { reportsRepository.markCleared(id) }
    }

    /**
     * Set the local user verdict for a report ("confirmed" or "dismissed").
     * Also increments the community count (upvote for confirmed, markCleared for dismissed).
     * No-op if verdict is already set (enforced at DB level too).
     */
    fun confirmReport(id: Int) {
        viewModelScope.launch {
            reportsRepository.setUserVerdict(id, "confirmed")
            reportsRepository.upvote(id)
        }
    }

    fun dismissReport(id: Int) {
        viewModelScope.launch {
            reportsRepository.setUserVerdict(id, "dismissed")
            reportsRepository.markCleared(id)
        }
    }

    fun connectP2P() {
        if (!FeatureFlags.FEATURE_P2P_ENABLED) return
        p2pManager.connect()
    }

    fun disconnectP2P() {
        if (!FeatureFlags.FEATURE_P2P_ENABLED) return
        p2pManager.disconnect()
    }

    suspend fun getNearbyAlpr(
        minLat: Double, maxLat: Double,
        minLon: Double, maxLon: Double
    ): List<ALPRBlocklist> = alprBlocklistDao.getNearby(minLat, maxLat, minLon, maxLon)

    override fun onCleared() {
        super.onCleared()
        p2pManager.disconnect()
    }

    // ── Detection Popups: Police Sightings + ALPR (Phase 3 Feature 4) ────────

    /** Most recent police sighting — for confirm/dismiss popup */
    val latestPoliceSighting: StateFlow<com.aegisnav.app.police.PoliceSighting?> =
        policeSightingDao.getMostRecent()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Confirm a police sighting — increment unit confirm tap count, lock at 3 */
    fun confirmPoliceSighting(id: String) {
        viewModelScope.launch {
            policeSightingDao.setUserVerdict(id, "confirmed")
            val sighting = policeSightings.value.firstOrNull { it.id == id }
            sighting?.officerUnitId?.let { unitId ->
                officerUnitDao.incrementConfirmCount(unitId)
                val unit = officerUnitDao.getById(unitId) ?: return@let
                val newCount = unit.userConfirmTapCount + 1
                officerUnitDao.setConfirmTapCount(unitId, newCount)
                // Record the timestamp of this confirm tap for 1-hour expiry
                officerUnitDao.updateLastConfirmTimestamp(unitId, System.currentTimeMillis())
                if (newCount >= 3) {
                    officerUnitDao.lockVerdict(unitId, "confirmed")
                }
            }
        }
    }

    /** Dismiss a police sighting — increment unit dismiss tap count, lock at 3 */
    fun dismissPoliceSighting(id: String, mac: String?) {
        viewModelScope.launch {
            policeSightingDao.setUserVerdict(id, "dismissed")
            mac?.let { dismissTriangulationMarker(it) }
            val sighting = policeSightings.value.firstOrNull { it.id == id }
            sighting?.officerUnitId?.let { unitId ->
                val unit = officerUnitDao.getById(unitId) ?: return@let
                val newCount = unit.userDismissTapCount + 1
                officerUnitDao.setDismissTapCount(unitId, newCount)
                if (newCount >= 3) {
                    officerUnitDao.lockVerdict(unitId, "dismissed")
                }
            }
        }
    }

    /** Mark a police sighting as expired (auto-dismissed by timer, no user action) */
    fun expirePoliceSighting(id: String) {
        viewModelScope.launch {
            policeSightingDao.setUserVerdict(id, "expired")
        }
    }

    /**
     * Confirm a newly detected ALPR proximity alert.
     * Adds the camera location to the ALPRBlocklist DB so it renders as a permanent
     * map marker alongside the 78K+ static cameras.
     *
     * @param mac  Device MAC that triggered the alert (kept for future reference).
     * @param camLat  Estimated camera latitude (from triangulation result).
     * @param camLon  Estimated camera longitude.
     * @param camDesc Human-readable description for the blocklist entry.
     */
    fun confirmDetectedAlpr(mac: String, camLat: Double, camLon: Double, camDesc: String = "Detected ALPR Camera") {
        viewModelScope.launch {
            addUserAlprCamera(camLat, camLon, camDesc)
        }
    }

    /** Dismiss a newly detected ALPR — remove from map */
    fun dismissDetectedAlpr(mac: String) {
        dismissTriangulationMarker(mac)
    }

    /** Snapshot of current triangulation results for the popup UI. */
    fun getTriangulationResultsSnapshot(): Collection<com.aegisnav.app.signal.SignalTriangulator.TriangulationResult> =
        signalTriangulator.currentResults.values.toList()

    // ── Watchlist management ──────────────────────────────────────────────────

    /** Add a MAC to the watchlist. Future sightings will trigger a HIGH-threat WATCHLIST alert. */
    fun addToWatchlist(mac: String, type: String = "BLE", label: String = "Watched device") {
        viewModelScope.launch {
            watchlistDao.insert(WatchlistEntry(mac = mac, type = type, label = label))
        }
    }

    /** Remove a MAC from the watchlist. */
    fun removeFromWatchlist(mac: String) {
        viewModelScope.launch {
            watchlistDao.delete(mac)
        }
    }

}

