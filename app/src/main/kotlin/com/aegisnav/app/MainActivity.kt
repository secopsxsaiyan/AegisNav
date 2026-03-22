package com.aegisnav.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import com.aegisnav.app.util.AppLog
import com.aegisnav.app.security.SecureDataStore
import com.aegisnav.app.security.readBlocking
import com.aegisnav.app.security.editBlocking
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.DisposableEffect
import android.location.LocationListener
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.aegisnav.app.correlation.ThreatLevel
import com.aegisnav.app.data.model.ALPRBlocklist
import com.aegisnav.app.data.model.Report
import com.aegisnav.app.police.PoliceSighting
import com.aegisnav.app.signal.SignalTriangulator

import com.aegisnav.app.flock.FlockSighting
import com.aegisnav.app.flock.FlockViewModel
import com.aegisnav.app.p2p.IncomingReport
import com.aegisnav.app.ui.DetectionPopupHost
import com.aegisnav.app.ui.ReportBubbleMenu
import com.aegisnav.app.ui.ReportCategory
import com.aegisnav.app.ui.StrobeEdgeOverlay
import com.aegisnav.app.ui.initMapSources
import com.aegisnav.app.ui.updateAlternativeRoutes
import com.aegisnav.app.ui.updateConfirmedPoliceOverlay
import com.aegisnav.app.ui.updateFlockOverlay
import com.aegisnav.app.ui.updateMapOverlays
import com.aegisnav.app.ui.updateRoutePolyline
import com.aegisnav.app.ui.updateUserLocationOverlay
import com.aegisnav.app.ui.LAYER_ALPR
import com.aegisnav.app.ui.LAYER_FLOCK
import com.aegisnav.app.ui.LAYER_REDLIGHT
import com.aegisnav.app.ui.LAYER_SPEED
import com.aegisnav.app.ui.CompassButton
import com.aegisnav.app.ui.NewReportPopupHost
import com.aegisnav.app.ui.SpeedGauge
import com.aegisnav.app.ui.registerMapTapHandler
import com.aegisnav.app.util.fileLog
import com.aegisnav.app.flock.FlockScreen
import com.aegisnav.app.ui.alpr.AlprDetailBottomSheet
import com.aegisnav.app.routing.NavigationFab
import com.aegisnav.app.routing.NavigationHud
import com.aegisnav.app.routing.NavigationInputSheet
import com.aegisnav.app.routing.NavigationViewModel
import com.aegisnav.app.routing.RoutingUnavailableSnackbar
import dagger.hilt.android.AndroidEntryPoint
import android.app.KeyguardManager
import android.location.Location
import com.aegisnav.app.data.db.DatabaseModule
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point


@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val RC_CONFIRM_CREDENTIALS = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevent app contents from appearing in recent-tasks screenshots or being
        // captured by screenshot tools - important for a privacy/surveillance-detection app.
        // ⚠️ DEV ONLY: FLAG_SECURE is disabled in debug builds to allow screenshots for
        // documentation and GitHub. Release builds always enable it to prevent screen capture.
        // NEVER upload a debug APK to GitHub Releases - only signed release APKs are public.
        val isDebugBuild = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebugBuild) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }

        // Keep screen on while AegisNav is in the foreground — surveillance detection
        // requires continuous map visibility; screen timeout breaks user awareness.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Warm up app-scoped external storage directories so Android's FUSE overlay registers
        // them before TileSourceResolver or RoutingRepository check for files. Without this,
        // files pushed via `adb push` to Android/data/<package>/files/ show as non-existent
        // from the app's perspective until getExternalFilesDir() is called at least once.
        getExternalFilesDir(null)?.let { base ->
            java.io.File(base, "tiles").mkdirs()
            java.io.File(base, "routing").mkdirs()
            java.io.File(base, "geocoder").mkdirs()
        }

        // If the Keystore key required user authentication and we fell back to an in-memory DB,
        // prompt for device credentials now. On success the process restarts with a real DB.
        if (DatabaseModule.authRequired) {
            val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            @Suppress("DEPRECATION")
            val intent = km.createConfirmDeviceCredentialIntent(
                "AegisNav",
                "Authenticate to unlock your secure database"
            )
            if (intent != null) {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, RC_CONFIRM_CREDENTIALS)
                return  // don't set content until auth resolves
            }
            // No secure lock screen - just proceed (key shouldn't have thrown in this case)
        }

        setContent {
            AegisNavApp()
        }

        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    @Deprecated("Required for KeyguardManager.createConfirmDeviceCredentialIntent compat")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_CONFIRM_CREDENTIALS) {
            if (resultCode == RESULT_OK) {
                // Auth succeeded - Keystore key is now usable within the 30s window.
                // Clear any preload/first-run flags that were set against the in-memory DB
                // so they re-run correctly against the real encrypted DB on restart.
                // Clear in background — no need for synchronous blocking on main thread
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    SecureDataStore.get(applicationContext, "alpr_prefs").edit { it.clear() }
                    SecureDataStore.get(applicationContext, "tile_prefs").edit { it.clear() }
                }
                // Restart the process so Hilt re-initialises with the real encrypted DB.
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                    ?: run { finishAffinity(); return }
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
            } else {
                // User cancelled or failed - show a message and close
                Toast.makeText(this, "Authentication required to open AegisNav", Toast.LENGTH_LONG).show()
                finishAffinity()
            }
        }
    }
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AegisNavApp() {
    val context = LocalContext.current

    // ── Hoisted popup-reviewed state (persisted; backed by ViewModel/SharedPreferences) ──
    // reviewedPopupSightingIds removed — police sighting popups now use DB userVerdict field

    // ── Theme engine ──────────────────────────────────────────────────────
    val anPrefs = remember { SecureDataStore.get(context, "an_prefs") }
    // TODO(phase-refactor): Convert to Flow-based async read
    var themeMode by remember { mutableStateOf(ThemeMode.valueOf(anPrefs.readBlocking()[stringPreferencesKey("theme_mode")] ?: "AUTO")) }
    var scanIntensity by remember { mutableStateOf(anPrefs.readBlocking()[stringPreferencesKey("scan_intensity")] ?: "balanced") }
    val themeEngine = remember { ThemeEngine(context) }
    DisposableEffect(themeMode) {
        themeEngine.start(themeMode, null)
        onDispose { themeEngine.stop() }
    }
    val isDarkTheme by themeEngine.isDark.collectAsStateWithLifecycle()

    val viewModel: MainViewModel = hiltViewModel()
    val isScanning by viewModel.scanState.isScanning

    val reports by viewModel.reports.collectAsStateWithLifecycle()
    val alprList by viewModel.alprBlocklist.collectAsStateWithLifecycle()
    val incomingReports by viewModel.incomingReports.collectAsStateWithLifecycle()
    val liveReportsEnabled by viewModel.liveReportsEnabled.collectAsStateWithLifecycle()
    // p2pState removed - P2PStatusDot removed from toolbar
    val navViewModel: NavigationViewModel = hiltViewModel()
    val flockViewModel: FlockViewModel = hiltViewModel()
    val navRoutePoints by navViewModel.routePoints.collectAsStateWithLifecycle()
    val isNavigating by navViewModel.isNavigating.collectAsStateWithLifecycle()
    val navAlternativeRoutes by navViewModel.alternativeRoutes.collectAsStateWithLifecycle()

    val flockSightings by flockViewModel.sightings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showList by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showReportHistory by rememberSaveable { mutableStateOf(false) }
    var showThreatHistory by rememberSaveable { mutableStateOf(false) }
    var showFlockScreen by rememberSaveable { mutableStateOf(false) }
    var showNavSheet by rememberSaveable { mutableStateOf(false) }
    var threatDetailEvent by remember { mutableStateOf<com.aegisnav.app.data.model.ThreatEvent?>(null) }

    // System back button: close sub-views in reverse order instead of exiting the app
    val anySubViewOpen = showFlockScreen || showReportHistory || showThreatHistory ||
            showSettings || showList || showNavSheet || threatDetailEvent != null
    BackHandler(enabled = anySubViewOpen) {
        when {
            threatDetailEvent != null -> threatDetailEvent = null
            showReportHistory -> showReportHistory = false
            showThreatHistory -> showThreatHistory = false
            showFlockScreen   -> showFlockScreen   = false
            showSettings      -> showSettings      = false
            showNavSheet      -> showNavSheet      = false
            showList          -> showList          = false
        }
    }
    // Strobe effects: red = tracker alert, blue = police detection
    var strobeActive by remember { mutableStateOf(false) }
    var policeStrobeActive by remember { mutableStateOf(false) }
    val threatEvents by viewModel.threatEvents.collectAsStateWithLifecycle()
    val policeSightingCount by viewModel.policeSightingCount.collectAsStateWithLifecycle()
    val latestPoliceSighting by viewModel.latestPoliceSighting.collectAsStateWithLifecycle()
    val policeSightingsAll by viewModel.policeSightings.collectAsStateWithLifecycle()
    val officerUnitsAll by viewModel.officerUnits.collectAsStateWithLifecycle()
    // Snapshot of triangulation results — polled every 2s (ConcurrentHashMap, not a Flow)
    val triangulationResults by produceState<Collection<SignalTriangulator.TriangulationResult>>(
        initialValue = emptyList()
    ) {
        while (true) {
            value = viewModel.getTriangulationResultsSnapshot()
            kotlinx.coroutines.delay(2_000L)
        }
    }
    val dismissedTriangulationMacs by viewModel.dismissedTriangulationMacs.collectAsStateWithLifecycle()
    val reviewedPopupTriangulationMacs by viewModel.reviewedTriangulationMacs.collectAsStateWithLifecycle()
    val redLightCameras by viewModel.redLightCameras.collectAsStateWithLifecycle()
    val speedCameras by viewModel.speedCameras.collectAsStateWithLifecycle()
    // -1 = sentinel "not yet initialized" so we don't false-strobe on first composition
    var lastAlertCount by remember { mutableIntStateOf(-1) }
    // Flock map center request
    var flockCenterRequest by remember { mutableStateOf<Pair<Double,Double>?>(null) }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapReady by remember { mutableStateOf(false) }

    val routingAvailableForSettings by navViewModel.routingAvailable.collectAsStateWithLifecycle()

    var currentLat by remember { mutableStateOf(27.9944024) }
    var currentLon by remember { mutableStateOf(-81.7602544) }
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var currentBearing by remember { mutableStateOf(0f) }   // GPS bearing degrees
    var userSpeedMs by remember { mutableStateOf(0f) }      // GPS speed m/s
    var autoCenterEnabled by remember { mutableStateOf(true) }
    var courseUpEnabled by remember { mutableStateOf(false) } // false=north-up, true=course-up
    var selectedAlprCamera by remember { mutableStateOf<ALPRBlocklist?>(null) }
    var selectedFlockSighting by remember { mutableStateOf<FlockSighting?>(null) }
    var selectedRedLightCamera by remember { mutableStateOf<com.aegisnav.app.data.model.RedLightCamera?>(null) }
    var selectedSpeedCamera by remember { mutableStateOf<com.aegisnav.app.data.model.SpeedCamera?>(null) }

    // mapRef and mapReady declared earlier; wired up in onMapReady below.
    var mapBounds by remember { mutableStateOf<org.maplibre.android.geometry.LatLngBounds?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingCameraUndo by remember { mutableStateOf<ALPRBlocklist?>(null) }
    var showAddCameraDialog by remember { mutableStateOf(false) }
    var longPressLatLng by remember { mutableStateOf<LatLng?>(null) }
    var navDestFromMap by remember { mutableStateOf<com.aegisnav.app.routing.LatLon?>(null) }

    // ── Ignore / wizard state - collected early so both LaunchedEffects can gate on it ──
    val ignoreList by viewModel.ignoreList.collectAsStateWithLifecycle()

    // ── State selection - shown on very first launch before anything else ─
    val stateSelectedPref = remember {
        // TODO(phase-refactor): Convert to Flow-based async read
        anPrefs.readBlocking()[stringPreferencesKey("selected_state_v1")]
    }
    var showStateSelector by remember { mutableStateOf(stateSelectedPref == null) }
    var showWizardDownload by remember { mutableStateOf(false) }
    var tileManifestForPicker by remember { mutableStateOf(TileManifest()) }
    // Defer loadActiveState() to here so the encrypted DataStore is ready
    // (ViewModel init runs before keystore auth; moving it here ensures it runs
    // after the Activity is fully created and the secure store is accessible).
    LaunchedEffect(Unit) {
        viewModel.loadActiveState(context)
    }

    LaunchedEffect(showStateSelector) {
        if (showStateSelector) {
            tileManifestForPicker = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                MapTileManifest.load(context)
            }
        }
    }
    if (showStateSelector && !showWizardDownload) {
        // Wrap in AegisNavTheme(isDark=false): dialog renders before the main WtwTheme block
        // so it would otherwise inherit Android system dark mode.
        AegisNavTheme(isDark = false) {
            StateSelectorDialog(
                manifest = tileManifestForPicker,
                onStateSelected = { abbr ->
                    scope.launch {
                        // Mark wizard complete (legacy single-string key)
                        anPrefs.edit { it[stringPreferencesKey("selected_state_v1")] = abbr } // Async DataStore read
                        // ALSO write to state_prefs so Settings "Active States" reflects the pick
                        saveSelectedStates(context, setOf(abbr)) // Async DataStore read
                        // Auto-start download for the selected state so progress is visible immediately
                        val state = com.aegisnav.app.data.DataDownloadManager.byCode(abbr)
                        if (state != null) {
                            com.aegisnav.app.data.DataDownloadManager.startDownload(context, state)
                        }
                        // Navigate to download screen AFTER DataStore writes complete (fixes race condition)
                        showWizardDownload = true
                    }
                }
            )
        }
    }
    // Wizard step 2: Download Map Data for selected state
    if (showWizardDownload) {
        com.aegisnav.app.ui.DataDownloadScreen(
            onBack = {
                showWizardDownload = false
                showStateSelector = false
                // Trigger ALPR data load for the newly selected state
                (context.applicationContext as? AegisNavApplication)?.preloadALPRData()
                viewModel.refreshTileUri(context)
            },
            onSkip = {
                showWizardDownload = false
                showStateSelector = false
                // Trigger ALPR data load for the newly selected state
                (context.applicationContext as? AegisNavApplication)?.preloadALPRData()
                viewModel.refreshTileUri(context)
            },
            onDataChanged = { viewModel.refreshTileUri(context) }
        )
    }

    // ── Startup initialization popup - shown once ever, never again after first dismiss ──
    // Async DataStore read
    var showInitializingPopup by remember { mutableStateOf(false) }
    LaunchedEffect(isScanning) {
        // Read persisted value directly — collectAsState initial=false races with DataStore load
        val persistedInitShown = anPrefs.data.first()[booleanPreferencesKey("init_popup_shown_v1")] ?: false
        if (isScanning && !persistedInitShown) {
            showInitializingPopup = true
            // Auto-dismiss after 10s; user can also tap "Got it"
            kotlinx.coroutines.delay(10_000L)
            showInitializingPopup = false
            anPrefs.edit { it[booleanPreferencesKey("init_popup_shown_v1")] = true }
        }
    }

    // ── First-launch ignore wizard (fires 30s after FIRST scan start, ever) ───
    var showIgnoreWizard by remember { mutableStateOf(false) }
    // Async DataStore read
    val ignoreWizardShown by anPrefs.data.map { it[booleanPreferencesKey("first_launch_ignore_shown_v1")] ?: false }
        .collectAsState(initial = false)
    val sessionDevicesSeen by viewModel.sessionDevicesSeen.collectAsStateWithLifecycle()

    // Uses a persistent timestamp so any isScanning toggles (service restart,
    // brief glitch) do NOT reset the countdown - the 30s is wall-clock time
    // from the very first moment scanning ever started, across all state changes.
    LaunchedEffect(isScanning) {
        // Read persisted values directly — the composed states (ignoreWizardShown, ignoreList)
        // may still be at their initial defaults (false, empty) before DataStore/Room load from disk.
        val persistedWizardShown = anPrefs.data.first()[booleanPreferencesKey("first_launch_ignore_shown_v1")] ?: false
        val persistedIgnoreCount = try { viewModel.getIgnoreListCount() } catch (_: Exception) { 0 }
        fileLog(context, "WizardTimer", "isScanning=$isScanning wizardShown=$persistedWizardShown ignoreListSize=$persistedIgnoreCount")
        if (isScanning && !persistedWizardShown && persistedIgnoreCount == 0) {
            val wizardPrefsSnapshot = anPrefs.data.first()
            if (wizardPrefsSnapshot[longPreferencesKey("first_scan_start_ms")] == null) {
                anPrefs.edit { it[longPreferencesKey("first_scan_start_ms")] = System.currentTimeMillis() } // Async DataStore read
                fileLog(context, "WizardTimer", "Recorded first_scan_start_ms")
            }
            val startMs = anPrefs.data.first()[longPreferencesKey("first_scan_start_ms")] ?: System.currentTimeMillis() // Async DataStore read
            val elapsed = System.currentTimeMillis() - startMs
            val remaining = 30_000L - elapsed
            fileLog(context, "WizardTimer", "elapsed=${elapsed}ms remaining=${remaining}ms sessionDevices=${viewModel.sessionDevicesSeen.value.size}")
            if (remaining > 0) kotlinx.coroutines.delay(remaining)
            val deviceCount = viewModel.sessionDevicesSeen.value.size
            fileLog(context, "WizardTimer", "Timer fired. sessionDevices=$deviceCount ignoreListSize=${viewModel.ignoreList.value.size}")
            if (deviceCount > 0) {
                showIgnoreWizard = true
                fileLog(context, "WizardTimer", "Showing ignore wizard")
            } else {
                fileLog(context, "WizardTimer", "No devices seen - wizard suppressed")
            }
        } else {
            fileLog(context, "WizardTimer", "Skipped: isScanning=$isScanning wizardShown=$ignoreWizardShown ignoreListEmpty=${ignoreList.isEmpty()}")
        }
    }

    // Initialization popup - shown once ever (gated by init_popup_shown_v1 prefs flag)
    if (showInitializingPopup) {
        val dismissInit = {
            showInitializingPopup = false
            scope.launch { anPrefs.edit { it[booleanPreferencesKey("init_popup_shown_v1")] = true } } // Async DataStore read
        }
        AlertDialog(
            onDismissRequest = { dismissInit() },
            title = { Text("⚙️ Device Initializing") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Scanners are warming up. Please wait approximately 45 seconds before acting on scan results.")
                    Text(
                        "After 30 seconds of scanning you will be prompted to review and ignore any known safe devices.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { dismissInit() }) { Text("Got it") }
            }
        )
    }

    if (showIgnoreWizard) {
        FirstLaunchIgnoreDialog(
            deviceLogs = sessionDevicesSeen,
            onIgnoreAll = { addresses ->
                addresses.forEach { viewModel.addToIgnoreList(it) }
                scope.launch { anPrefs.edit { it[booleanPreferencesKey("first_launch_ignore_shown_v1")] = true } } // Async DataStore read
                showIgnoreWizard = false
            },
            onIgnoreSelected = { selected ->
                selected.forEach { viewModel.addToIgnoreList(it) }
                scope.launch { anPrefs.edit { it[booleanPreferencesKey("first_launch_ignore_shown_v1")] = true } } // Async DataStore read
                showIgnoreWizard = false
            },
            onSkip = {
                scope.launch { anPrefs.edit { it[booleanPreferencesKey("first_launch_ignore_shown_v1")] = true } } // Async DataStore read
                showIgnoreWizard = false
            }
        )
    }

    // ── Battery drain warning for Maximum scan intensity (once per session) ──
    var highIntensityWarningShown by remember { mutableStateOf(false) }
    var showHighIntensityWarning by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val intensity = anPrefs.data.first()[stringPreferencesKey("scan_intensity")] ?: "balanced"
        if (intensity == "low_latency" && !highIntensityWarningShown) {
            showHighIntensityWarning = true
        }
    }
    if (showHighIntensityWarning) {
        Dialog(onDismissRequest = {
            highIntensityWarningShown = true
            showHighIntensityWarning = false
        }) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = Color.White,
                tonalElevation = 8.dp
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        "⚠️ High Scan Intensity",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE65100)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Battery will drain faster. Maximum scan intensity is active. Switch to Balanced in Settings to save battery.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF333333)
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = {
                            // Switch to balanced
                            scanIntensity = "balanced"
                            scope.launch { anPrefs.edit { it[stringPreferencesKey("scan_intensity")] = "balanced" } }
                            ScanService.instance?.setScanIntensity("balanced")
                            highIntensityWarningShown = true
                            showHighIntensityWarning = false
                        }) {
                            Text("Switch to Balanced", color = Color(0xFF00BCD4))
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            highIntensityWarningShown = true
                            showHighIntensityWarning = false
                        }) {
                            Text("Keep Maximum", color = Color(0xFF888888))
                        }
                    }
                }
            }
        }
    }

    // Observe all tile downloads; refresh tile URI when any completes successfully.
    // This is the correct hook — startDownload() just enqueues, actual files land later.
    val downloadWorkInfos by WorkManager.getInstance(context)
        .getWorkInfosByTagLiveData("tile_download")
        .observeAsState()
    LaunchedEffect(downloadWorkInfos) {
        val anySucceeded = downloadWorkInfos?.any { it.state == WorkInfo.State.SUCCEEDED } == true
        if (anySucceeded) {
            // Auto-activate the newly downloaded state if none is active yet
            if (viewModel.activeState.value == null) {
                val firstDownloaded = TileSourceResolver.downloadedStateCodes(context).firstOrNull()
                if (firstDownloaded != null) {
                    viewModel.setActiveState(firstDownloaded, context)
                }
            } else {
                viewModel.refreshTileUri(context)
            }
        }
    }

    // Tile pre-cache progress (legacy - no longer enqueued; always reports idle)
    val tileWorkInfos by WorkManager.getInstance(context)
        .getWorkInfosByTagLiveData("tile_pre_cache")
        .observeAsState(emptyList())
    val tileWorkInfo = tileWorkInfos.firstOrNull()
    val isCachingTiles = tileWorkInfo?.state == WorkInfo.State.RUNNING
    val tileCacheProgress = tileWorkInfo?.progress?.getInt("progress", 0) ?: 0
    val tileCacheDone = tileWorkInfo?.state == WorkInfo.State.SUCCEEDED

    LaunchedEffect(tileCacheDone) {
        if (tileCacheDone) {
            // Async DataStore read
            SecureDataStore.get(context, "tile_prefs").edit {
                it[booleanPreferencesKey("tiles_precached_v1")] = true
            }
        }
    }

    // Continuous location updates
    DisposableEffect(Unit) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = LocationListener { loc ->
            currentLat = loc.latitude
            currentLon = loc.longitude
            currentLocation = loc
            themeEngine.updateLocation(loc)
            if (loc.hasSpeed()) {
                val raw = loc.speed
                // GPS chips report noise (0.2–2 m/s) even when stationary. Use the chipset's
                // own accuracy estimate (API 26+, always available on our minSdk 31): if the
                // measured speed is within the accuracy margin it's indistinguishable from zero.
                // Fall back to a 0.5 m/s (~1 mph) noise floor for any edge cases.
                userSpeedMs = when {
                    loc.hasSpeedAccuracy() && raw <= loc.speedAccuracyMetersPerSecond -> 0f
                    raw < 0.5f -> 0f
                    else -> raw
                }
            }
            if (loc.hasBearing() && loc.speed > 1.5f) { // > ~3.4 mph
                currentBearing = loc.bearing
                if (!courseUpEnabled) courseUpEnabled = true
                autoCenterEnabled = true // re-lock center when movement starts
            }
        }
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
                val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                    LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
                lm.requestLocationUpdates(provider, 3000L, 5f, listener)
                lm.getLastKnownLocation(provider)?.let { loc ->
                    currentLat = loc.latitude
                    currentLon = loc.longitude
                    currentLocation = loc
                }
            }
        } catch (e: Exception) { AppLog.w("MainActivity", "Location request failed: ${e.message}") }
        onDispose {
            try { lm.removeUpdates(listener) } catch (_: Exception) {}
        }
    }

    // Update user location overlay + auto-center/course-up camera
    LaunchedEffect(currentLat, currentLon, currentBearing, courseUpEnabled, autoCenterEnabled) {
        val map = mapRef ?: return@LaunchedEffect
        map.getStyle { style ->
            updateUserLocationOverlay(style, currentLat, currentLon, currentBearing, courseUpEnabled)
        }
        if (autoCenterEnabled) {
            val bearing = if (courseUpEnabled) currentBearing.toDouble() else 0.0
            map.animateCamera(
                org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(currentLat, currentLon))
                        .bearing(bearing)
                        .build()
                ), 800
            )
        }
    }

    // Update map overlays whenever data changes
    LaunchedEffect(reports, alprList, incomingReports, mapBounds, mapReady, redLightCameras, speedCameras) {
        if (!mapReady) return@LaunchedEffect
        mapRef?.getStyle { style ->
            updateMapOverlays(style, reports, alprList, incomingReports, redLightCameras, speedCameras, mapBounds)
        }
    }

    // Update route polyline when navigation route changes OR when map style is re-initialized.
    LaunchedEffect(navRoutePoints, mapReady) {
        if (!mapReady) return@LaunchedEffect
        mapRef?.getStyle { style ->
            updateRoutePolyline(style, navRoutePoints)
        }
    }

    // Phase 5 Task 2: Update alternative route dashed polylines
    LaunchedEffect(navAlternativeRoutes, mapReady) {
        if (!mapReady) return@LaunchedEffect
        mapRef?.getStyle { style ->
            updateAlternativeRoutes(style, navAlternativeRoutes, navRoutePoints)
        }
    }

    

    // Update flock sightings overlay
    LaunchedEffect(flockSightings) {
        mapRef?.getStyle { style ->
            updateFlockOverlay(style, flockSightings)
        }
    }

    // Update confirmed police sightings overlay
    LaunchedEffect(policeSightingsAll, officerUnitsAll, mapReady) {
        if (!mapReady) return@LaunchedEffect
        mapRef?.getStyle { style ->
            updateConfirmedPoliceOverlay(style, policeSightingsAll, officerUnitsAll)
        }
    }

    // Detect new tracker alerts → activate red strobe.
    // lastAlertCount starts at -1 (uninitialized) so first observation just sets the baseline
    // without triggering a false strobe from pre-existing DB events.
    LaunchedEffect(threatEvents.size) {
        val trackerCount = threatEvents.count { it.type == "TRACKER" }
        if (lastAlertCount == -1) {
            lastAlertCount = trackerCount
        } else if (trackerCount > lastAlertCount) {
            strobeActive = true
            kotlinx.coroutines.delay(30_000L)
            strobeActive = false
            lastAlertCount = trackerCount
        } else {
            lastAlertCount = trackerCount
        }
    }

    // Detect new police detections → activate blue strobe.
    // Same baseline pattern as tracker strobe: -1 sentinel avoids false-fire on first composition.
    var lastPoliceCount by remember { mutableStateOf(-1) }
    LaunchedEffect(policeSightingCount) {
        if (lastPoliceCount == -1) {
            lastPoliceCount = policeSightingCount
        } else if (policeSightingCount > lastPoliceCount) {
            policeStrobeActive = true
            kotlinx.coroutines.delay(30_000L)
            policeStrobeActive = false
            lastPoliceCount = policeSightingCount
        } else {
            lastPoliceCount = policeSightingCount
        }
    }

    // Center map on flock sighting when requested.
    // Disable autoCenterEnabled so GPS doesn't immediately override the sighting location.
    // mapRef null guard is sufficient — map is never destroyed since we use overlay architecture.
    LaunchedEffect(flockCenterRequest) {
        if (mapRef == null) return@LaunchedEffect
        val req = flockCenterRequest ?: return@LaunchedEffect
        autoCenterEnabled = false   // prevent GPS re-center from overriding the sighting
        mapRef?.animateCamera(
            org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(req.first, req.second))
                    .zoom(15.0)
                    .build()
            ), 600
        )
        flockCenterRequest = null
    }

    val bgLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { /* non-blocking; app works without it */ }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val locationOk = perms.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false)
        val btScanOk = perms.getOrDefault(Manifest.permission.BLUETOOTH_SCAN, false)
        val btConnectOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms[Manifest.permission.BLUETOOTH_CONNECT] == true
        } else true
        if (locationOk && btScanOk && btConnectOk) {
            startScanService(context, viewModel)
            bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            Toast.makeText(
                context,
                "Location and Bluetooth permissions are required to detect trackers and ALPR cameras.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun hasPermissions() = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_WIFI_STATE
    ).all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    fun requestPermissions() {
        permissionLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.NEARBY_WIFI_DEVICES
        ))
    }

    LaunchedEffect(Unit) {
        if (hasPermissions()) {
            startScanService(context, viewModel)
            // Background location must be requested separately from foreground on Android 11+.
            // Request it now if not already granted, non-blocking — app works without it
            // (foreground location is sufficient when app is visible).
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        } else {
            requestPermissions()
        }
        // P2P disabled for alpha — connectP2P() not called
    }

    // Add ALPR Camera / Navigate here dialog (long-press on map)
    if (showAddCameraDialog && longPressLatLng != null) {
        @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
        val ll = longPressLatLng!!
        AlertDialog(
            onDismissRequest = { showAddCameraDialog = false },
            title = { Text("Map Action") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(String.format("%.5f, %.5f", ll.latitude, ll.longitude),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(
                        onClick = {
                            showAddCameraDialog = false
                            scope.launch {
                                val inserted = viewModel.addUserAlprCamera(ll.latitude, ll.longitude)
                                pendingCameraUndo = inserted
                                viewModel.submitReport(
                                    type = "ALPR",
                                    latitude = ll.latitude,
                                    longitude = ll.longitude,
                                    description = "ALPR Camera (User Report) at ${String.format("%.5f, %.5f", ll.latitude, ll.longitude)}"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("📷 Add ALPR Camera here") }
                    TextButton(
                        onClick = {
                            showAddCameraDialog = false
                            navDestFromMap = com.aegisnav.app.routing.LatLon(ll.latitude, ll.longitude)
                            showNavSheet = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("🧭 Navigate here") }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAddCameraDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Undo snackbar for camera submission
    LaunchedEffect(pendingCameraUndo) {
        val cam = pendingCameraUndo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "ALPR camera added",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.deleteUserAlprCamera(cam)
        }
        pendingCameraUndo = null
    }

    AegisNavTheme(isDark = isDarkTheme) {
    Surface(modifier = Modifier.fillMaxSize()) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Toolbar ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                    .clipToBounds()
                    .padding(horizontal = 12.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showList = !showList }) {
                    Text("📡", style = MaterialTheme.typography.bodyLarge)
                }
                IconButton(onClick = { showFlockScreen = true; showList = false }) {
                    Text("📷", style = MaterialTheme.typography.bodyLarge)
                }
                IconButton(onClick = { showSettings = true; showList = false }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }

            // ── Map + Bubble menu ─────────────────────────────────────────────
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {

                // MapLibre map via Compose wrapper
                val pmtilesUri by viewModel.tileUri.collectAsStateWithLifecycle()
                MapLibreMapView(
                    modifier = Modifier.fillMaxSize(),
                    isDark = isDarkTheme,
                    pmtilesUri = pmtilesUri,
                    triangulationResults = triangulationResults,
                    dismissedTriangulationMacs = dismissedTriangulationMacs,
                    onThemeSwitch = { _, style ->
                        val density = context.resources.displayMetrics.density
                        initMapSources(style, density)
                        updateMapOverlays(style, reports, alprList, incomingReports, redLightCameras, speedCameras)
                        updateUserLocationOverlay(style, currentLat, currentLon, currentBearing, courseUpEnabled)
                        updateConfirmedPoliceOverlay(style, policeSightingsAll, officerUnitsAll)
                    },
                    onStyleReloaded = { style ->
                        // Fired on every style finish-load, including after app-switch resume
                        // when MapLibre recreates its GL surface. Re-apply all dynamic overlays
                        // so the route polyline and markers are visible again.
                        val density = context.resources.displayMetrics.density
                        initMapSources(style, density)
                        updateMapOverlays(style, reports, alprList, incomingReports, redLightCameras, speedCameras, mapBounds)
                        updateRoutePolyline(style, navRoutePoints)
                        updateAlternativeRoutes(style, navAlternativeRoutes, navRoutePoints)
                        updateUserLocationOverlay(style, currentLat, currentLon, currentBearing, courseUpEnabled)
                        updateConfirmedPoliceOverlay(style, policeSightingsAll, officerUnitsAll)
                        updateFlockOverlay(style, flockSightings)
                    },
                    onMapReady = { map, _ ->
                        mapRef = map
                        map.cameraPosition = CameraPosition.Builder()
                            .target(LatLng(27.9944024, -81.7602544))
                            .zoom(13.0)
                            .build()
                        map.uiSettings.isAttributionEnabled = false
                        map.uiSettings.isLogoEnabled = false
                        map.uiSettings.isCompassEnabled = false // we draw our own
                        // Disable auto-center when user manually interacts with map
                        map.addOnCameraMoveStartedListener { reason ->
                            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                                autoCenterEnabled = false
                            }
                        }
                        // Initial overlay render
                        val density = context.resources.displayMetrics.density
                        map.getStyle { style ->
                            initMapSources(style, density)
                            updateMapOverlays(style, reports, alprList, incomingReports, redLightCameras, speedCameras)
                            updateUserLocationOverlay(style, currentLat, currentLon, currentBearing, courseUpEnabled)
                            mapReady = true
                        }
                        // Update bounds on camera idle
                        map.addOnCameraIdleListener {
                            mapBounds = map.projection.visibleRegion.latLngBounds
                        }
                        // Long-press → Add ALPR camera
                        map.addOnMapLongClickListener { latLng ->
                            longPressLatLng = latLng
                            showAddCameraDialog = true
                            true
                        }
                        // Tap → Show ALPR camera or Flock sighting detail bottom sheet
                        registerMapTapHandler(
                            map = map,
                            flockSightings = flockSightings,
                            alprList = alprList,
                            redLightCameras = redLightCameras,
                            speedCameras = speedCameras,
                            onFlockSelected = { selectedFlockSighting = it },
                            onAlprSelected = { selectedAlprCamera = it },
                            onRedLightSelected = { selectedRedLightCamera = it },
                            onSpeedCameraSelected = { selectedSpeedCamera = it }
                        )
                    }
                )

                // Navigation HUD - shown at top when navigating (z-ordered above map, below nothing)
                NavigationHud(
                    viewModel = navViewModel,
                    modifier = Modifier.align(Alignment.TopCenter)
                )

                // Blue strobe edge effect overlay when police equipment detected
                if (policeStrobeActive) {
                    StrobeEdgeOverlay(
                        modifier = Modifier.fillMaxSize(),
                        color = Color.Blue,
                        onTap = { policeStrobeActive = false }
                    )
                }

                // Red strobe edge effect overlay when tracker alert fires
                if (strobeActive) {
                    StrobeEdgeOverlay(
                        modifier = Modifier.fillMaxSize(),
                        onTap = { strobeActive = false; showList = true }
                    )
                }

                // Tile cache progress overlay (PMTiles - typically instant)
                if (isCachingTiles) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "Preparing offline map… $tileCacheProgress%",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White
                        )
                        // LinearProgressIndicator uses keyframes animation - crashes on material3:1.1.2
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .background(Color.White.copy(alpha = 0.3f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(tileCacheProgress / 100f)
                                    .fillMaxHeight()
                                    .background(Color.White)
                            )
                        }
                    }
                }

                // Download progress banner — shown when any tile_download job is RUNNING
                DownloadProgressBanner(
                    workInfos = downloadWorkInfos,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )

                // Snackbar host
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(bottom = 80.dp, start = 16.dp)
                )

                // Routing unavailable snackbar (one-time)
                RoutingUnavailableSnackbar(snackbarHostState, navViewModel)

                // Speed gauge - above center FAB
                val speedMph = (userSpeedMs * 2.23694f).toInt()
                SpeedGauge(
                    speedMph = speedMph,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(bottom = 88.dp, start = 20.dp)
                )

                // Center-on-location FAB
                FloatingActionButton(
                    onClick = {
                        autoCenterEnabled = true
                        val bearing = if (courseUpEnabled) currentBearing.toDouble() else 0.0
                        mapRef?.animateCamera(
                            org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder()
                                    .target(LatLng(currentLat, currentLon))
                                    .zoom(15.0)
                                    .bearing(bearing)
                                    .build()
                            )
                        )
                    },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(bottom = 16.dp, start = 16.dp),
                    containerColor = if (autoCenterEnabled) MaterialTheme.colorScheme.primary
                                     else MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = if (autoCenterEnabled) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    Text("◎", fontSize = 20.sp)
                }

                // Compass button - top right, toggles north-up / course-up
                CompassButton(
                    courseUpEnabled = courseUpEnabled,
                    onToggle = {
                        courseUpEnabled = !courseUpEnabled
                        autoCenterEnabled = true
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 12.dp)
                )

                // Navigation FAB - positioned above report bubble menu
                NavigationFab(
                    isNavigating = isNavigating,
                    onClick = {
                        if (isNavigating) navViewModel.stopNavigation()
                        else showNavSheet = true
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 88.dp, end = 16.dp)
                )

                // Report bubble menu - hidden when live reports are disabled
                if (liveReportsEnabled) {
                    ReportBubbleMenu(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 160.dp, end = 16.dp),
                        onReportSelected = { category, subOption, isGroup ->
                            viewModel.submitReport(
                                type = category.dbType,
                                subtype = null,
                                latitude = currentLat,
                                longitude = currentLon,
                                description = buildString {
                                    append(category.label)
                                    if (subOption != null) append(": $subOption")
                                    if (isGroup) append(" (Group)")
                                },
                                subOption = subOption,
                                isGroup = isGroup
                            )
                        }
                    )
                }

                // ── New report popup overlay ──────────────────────────────────
                // Shows for new incoming P2P reports; auto-dismisses after 30s.
                // Positioned bottom-right, below the NavigationFab.
                if (liveReportsEnabled) {
                    NewReportPopupHost(
                        incomingReports = incomingReports,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 16.dp, end = 16.dp)
                    )
                }

                // ── Detection popup overlay ───────────────────────────────────
                // Shows for new police scan detections, ALPR triangulations, and
                // proximity to POLICE triangulation markers. Offset above P2P popup.
                DetectionPopupHost(
                    latestPoliceSighting = latestPoliceSighting,
                    triangulationResults = triangulationResults,
                    userLat = currentLat,
                    userLon = currentLon,
                    onConfirmPolice = { id, mac -> viewModel.confirmPoliceSighting(id) },
                    onDismissPolice = { id, mac -> viewModel.dismissPoliceSighting(id, mac) },
                    onConfirmAlpr = { mac, camLat, camLon, camDesc -> viewModel.confirmDetectedAlpr(mac, camLat, camLon, camDesc) },
                    onDismissAlpr = { mac -> viewModel.dismissDetectedAlpr(mac) },
                    onExpirePolice = { id -> viewModel.expirePoliceSighting(id) },
                    reviewedTriangulationMacs = reviewedPopupTriangulationMacs,
                    onReviewedTriangulationMacsChange = { viewModel.setReviewedTriangulationMacs(it) },
                    officerUnits = officerUnitsAll,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 70.dp, end = 16.dp)
                )
            }

            // ── Flock Sighting Detail Bottom Sheet (from main map tap) ───────
            selectedFlockSighting?.let { sighting ->
                com.aegisnav.app.flock.FlockSightingDetailSheet(
                    sighting = sighting,
                    onDismiss = { selectedFlockSighting = null },
                    onReport = { s ->
                        selectedFlockSighting = null
                        viewModel.submitReport(
                            type = "ALPR",
                            latitude = s.lat,
                            longitude = s.lon,
                            description = "ALPR Camera (Flock Safety) at ${String.format("%.5f, %.5f", s.lat, s.lon)}"
                        )
                        flockViewModel.markReported(s.id)
                    }
                )
            }

            // ── ALPR Camera Detail Bottom Sheet ──────────────────────────────
            selectedAlprCamera?.let { camera ->
                AlprDetailBottomSheet(
                    camera = camera,
                    userLocation = currentLocation,
                    onDismiss = { selectedAlprCamera = null },
                    onReport = { cam ->
                        selectedAlprCamera = null
                        viewModel.submitReport(
                            type = "ALPR",
                            subtype = "Fixed",
                            latitude = cam.lat,
                            longitude = cam.lon,
                            description = "ALPR Camera at ${
                                String.format("%.5f, %.5f", cam.lat, cam.lon)
                            } [source: ${cam.source}]"
                        )
                    },
                    onIgnore = { cam ->
                        viewModel.ignoreAlprCamera(cam)
                    }
                )
            }

            // ── Red Light Camera Detail Dialog ────────────────────────────────
            selectedRedLightCamera?.let { cam ->
                Dialog(onDismissRequest = { selectedRedLightCamera = null }) {
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 4.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🚦 Red Light Camera", style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold)
                                IconButton(onClick = { selectedRedLightCamera = null }) {
                                    Icon(Icons.Default.Close, contentDescription = "Dismiss")
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(cam.desc.ifBlank { "Red Light Camera" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text("%.5f, %.5f".format(cam.lat, cam.lon),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text("Source: ${cam.source}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    selectedRedLightCamera = null
                                    viewModel.submitReport(
                                        type = "SURVEILLANCE",
                                        latitude = cam.lat,
                                        longitude = cam.lon,
                                        description = "Red light camera at ${String.format("%.5f, %.5f", cam.lat, cam.lon)} [source: ${cam.source}]"
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Report this camera") }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }

            // ── Speed Camera Detail Dialog ─────────────────────────────────────
            selectedSpeedCamera?.let { cam ->
                Dialog(onDismissRequest = { selectedSpeedCamera = null }) {
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 4.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🚔 Speed Camera", style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold)
                                IconButton(onClick = { selectedSpeedCamera = null }) {
                                    Icon(Icons.Default.Close, contentDescription = "Dismiss")
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(cam.desc.ifBlank { "Speed Camera" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text("%.5f, %.5f".format(cam.lat, cam.lon),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text("Source: ${cam.source}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    selectedSpeedCamera = null
                                    viewModel.submitReport(
                                        type = "SURVEILLANCE",
                                        latitude = cam.lat,
                                        longitude = cam.lon,
                                        description = "Speed camera at ${String.format("%.5f, %.5f", cam.lat, cam.lon)} [source: ${cam.source}]"
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Report this camera") }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }

            // ── Alert Log panel ────────────────────────────────────────────────
            if (showList) {
                AlertLogPanel(
                    threatEvents = viewModel.threatEvents.collectAsStateWithLifecycle().value,
                    onAlertTap = { _ ->
                        // Navigate to TrackerDetailScreen would need nav; open as detail for now
                    },
                    modifier = Modifier.fillMaxHeight(0.4f)
                )
            }
        }

        // ── Navigation sheet overlay (inside Activity Box, not Dialog) ────────
        // Must be here so Activity adjustResize + WindowInsets.ime work on Android 15.
        if (showNavSheet) {
            NavigationInputSheet(
                currentLat = currentLat,
                currentLon = currentLon,
                preselectedDest = navDestFromMap,
                onDismiss = {
                    navDestFromMap = null
                    showNavSheet = false
                },
                viewModel = navViewModel
            )
        }

        // ── Sub-screen overlays — always composed, slide over the map ─────────
        // The map and all its chrome remain alive. Sub-screens slide in from bottom.

        // FlockScreen overlay
        AnimatedVisibility(
            visible = showFlockScreen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            AegisNavTheme(isDark = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Box(modifier = Modifier.widthIn(max = 600.dp).fillMaxHeight()) {
                            FlockScreen(
                                onBack = { showFlockScreen = false },
                                onReportFlock = { lat, lon ->
                                    showFlockScreen = false
                                    viewModel.submitReport(
                                        type = "ALPR",
                                        latitude = lat,
                                        longitude = lon,
                                        description = "ALPR Camera (Flock Safety) at ${String.format("%.5f, %.5f", lat, lon)}"
                                    )
                                },
                                onViewOnMap = { lat, lon ->
                                    flockCenterRequest = lat to lon
                                    showFlockScreen = false
                                },
                                scanServiceRunning = isScanning
                            )
                        }
                    }
                }
            }
        }

        // ReportHistory overlay
        AnimatedVisibility(
            visible = showReportHistory,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            AegisNavTheme(isDark = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Box(modifier = Modifier.widthIn(max = 600.dp).fillMaxHeight()) {
                            ReportHistoryScreen(onBack = { showReportHistory = false })
                        }
                    }
                }
            }
        }

        // ThreatHistory overlay
        AnimatedVisibility(
            visible = showThreatHistory,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            AegisNavTheme(isDark = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Box(modifier = Modifier.widthIn(max = 600.dp).fillMaxHeight()) {
                            ThreatHistoryScreen(
                                onBack = { showThreatHistory = false },
                                onViewTrackerDetail = { event -> threatDetailEvent = event },
                                onIgnoreMac = { mac ->
                                    scope.launch {
                                        viewModel.addToIgnoreList(mac, "BLE", "Ignored from convoy/coordinated alert")
                                    }
                                },
                                onWatchlistAdd = { mac ->
                                    viewModel.addToWatchlist(mac, "BLE", "Watched device")
                                }
                            )
                        }
                    }
                }
            }
        }

        // TrackerDetail overlay (from threat history)
        AnimatedVisibility(
            visible = threatDetailEvent != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            // Capture event for use inside lambda (avoids smart-cast issue with nullable var)
            val event = threatDetailEvent
            if (event != null) {
                val detail = try { org.json.JSONObject(event.detailJson) } catch (e: org.json.JSONException) { AppLog.w("MainActivity", "Failed to parse event detailJson: ${e.message}"); org.json.JSONObject() }
                val sightingsList = try {
                    val arr = detail.optJSONArray("sightings")
                    if (arr != null) (0 until arr.length()).map { i ->
                        val s = arr.getJSONObject(i)
                        com.aegisnav.app.tracker.Sighting(
                            timestamp = s.optLong("timestamp", 0L),
                            lat = s.optDouble("lat", 0.0),
                            lon = s.optDouble("lon", 0.0),
                            rssi = s.optInt("rssi", -80)
                        )
                    } else emptyList()
                } catch (e: org.json.JSONException) { AppLog.w("MainActivity", "Failed to parse sightings: ${e.message}"); emptyList() }
                val rssiTrend = try {
                    val arr = detail.optJSONArray("rssiTrend")
                    if (arr != null) (0 until arr.length()).map { arr.getInt(it) } else emptyList()
                } catch (e: org.json.JSONException) { AppLog.w("MainActivity", "Failed to parse rssiTrend: ${e.message}"); emptyList() }
                val sightingsForAlert = if (sightingsList.isEmpty()) {
                    listOf(com.aegisnav.app.tracker.Sighting(
                        timestamp = event.timestamp, lat = 0.0, lon = 0.0, rssi = -80))
                } else sightingsList
                val alert = com.aegisnav.app.tracker.TrackerAlert(
                    mac = event.mac.ifBlank { detail.optString("mac", "??:??:??:??:??:??") },
                    manufacturer = detail.optString("manufacturer", "").ifBlank { null },
                    sightings = sightingsForAlert,
                    firstSeen = detail.optLong("firstSeen", event.timestamp),
                    lastSeen = detail.optLong("lastSeen", event.timestamp),
                    rssiTrend = rssiTrend
                )
                AegisNavTheme(isDark = false) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                            Box(modifier = Modifier.widthIn(max = 600.dp).fillMaxHeight()) {
                                TrackerDetailScreen(
                                    alert = alert,
                                    onBack = { threatDetailEvent = null },
                                    onIgnore = { _ -> threatDetailEvent = null },
                                    onReport = { _ -> threatDetailEvent = null }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Settings overlay
        AnimatedVisibility(
            visible = showSettings,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            AegisNavTheme(isDark = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Box(modifier = Modifier.widthIn(max = 600.dp).fillMaxHeight()) {
                            SettingsScreen(
                                onBack = { showSettings = false },
                                onShowReportHistory = { showSettings = false; showReportHistory = true },
                                onShowThreatHistory = { showSettings = false; showThreatHistory = true },
                                routingGraphAvailable = routingAvailableForSettings,
                                routingGraphPath = "",
                                isScanning = isScanning,
                                scanIntensity = scanIntensity,
                                onScanIntensityChange = { intensity ->
                                    scanIntensity = intensity
                                    scope.launch { anPrefs.edit { it[stringPreferencesKey("scan_intensity")] = intensity } }
                                    // Notify running ScanService to switch mode immediately
                                    val scanService = ScanService.instance
                                    scanService?.setScanIntensity(intensity)
                                },
                                themeMode = themeMode,
                                onThemeModeChange = { mode ->
                                    themeMode = mode
                                    scope.launch { anPrefs.edit { it[stringPreferencesKey("theme_mode")] = mode.name } } // Async DataStore read
                                },
                                onToggleScan = {
                                    if (isScanning) {
                                        // Persist user intent before stopping — prevents system-kill restart
                                        scope.launch { anPrefs.edit { it[booleanPreferencesKey("user_wants_scanning")] = false } } // Async DataStore read
                                        context.stopService(Intent(context, ScanService::class.java))
                                        viewModel.scanState.setScanning(false)
                                    } else {
                                        val permsOk = listOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.BLUETOOTH_SCAN,
                                            Manifest.permission.BLUETOOTH_CONNECT,
                                            Manifest.permission.ACCESS_WIFI_STATE
                                        ).all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
                                        if (permsOk) startScanService(context, viewModel)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    } // end Box
    } // end Surface
    } // end WtwTheme
}

// ── Helper composables ─────────────────────────────────────────────────────────

@Composable
fun ScanButton(isScanning: Boolean, threatLevel: ThreatLevel, onClick: () -> Unit) {
    val ringColor = when (threatLevel) {
        ThreatLevel.HIGH   -> Color(0xFFE53935)
        ThreatLevel.MEDIUM -> Color(0xFFFFB300)
        ThreatLevel.LOW    -> Color(0xFF43A047)
    }
    val label = if (isScanning) "⏸ Scanning" else "▶ Start Scan"
    Box(contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(if (isScanning) 112.dp else 0.dp)
            .clip(CircleShape).background(ringColor.copy(alpha = 0.25f)))
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isScanning) ringColor else MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.height(40.dp)
        ) { Text(label, fontWeight = FontWeight.Bold) }
    }
}

// P2PStatusDot removed for alpha — P2P feature not exposed in UI

@Composable
fun ReportListItem(report: Report, onConfirm: () -> Unit, onClear: () -> Unit) {
    val category = ReportCategory.entries.firstOrNull { it.dbType == report.type }
    Card(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(category?.emoji ?: "📍", fontSize = 24.sp)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("${report.type}${report.subtype?.let { " · $it" } ?: ""}",
                        fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(String.format("%.4f, %.4f", report.latitude, report.longitude),
                        fontSize = 11.sp, color = Color.Gray)
                }
                ThreatBadge(report.threatLevel)
            }
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF43A047))
                ) {
                    Text("✓ Confirm  ${report.confirmedCount}")
                }
                OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE53935))
                ) {
                    Text("✕ Clear  ${report.clearedCount}")
                }
            }
        }
    }
}

@Composable
fun IncomingReportItem(report: IncomingReport) {
    val category = ReportCategory.entries.firstOrNull { it.dbType == report.type }
    Card(modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(category?.emoji ?: "🌐", fontSize = 20.sp)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("${report.type}${report.subtype.let { if (it.isNotBlank()) " · $it" else "" }}",
                    fontWeight = FontWeight.Medium, fontSize = 12.sp)
                Text(String.format("%.4f, %.4f", report.lat, report.lon),
                    fontSize = 10.sp, color = Color.Gray)
            }
            ThreatBadge(report.threatLevel)
        }
    }
}

@Composable
fun ThreatBadge(level: String) {
    val color = when (level) {
        "HIGH"   -> Color(0xFFE53935)
        "MEDIUM" -> Color(0xFFFFB300)
        else     -> Color(0xFF43A047)
    }
    Box(modifier = Modifier.clip(CircleShape).background(color).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(level.take(1), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun AlertLogPanel(
    threatEvents: List<com.aegisnav.app.data.model.ThreatEvent>,
    onAlertTap: (com.aegisnav.app.data.model.ThreatEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val trackerEvents = threatEvents.filter { it.type == "TRACKER" || it.type == "CONVOY" || it.type == "COORDINATED" }
    LazyColumn(modifier = modifier) {
        item {
            Text(
                "📡 Tracker Log",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(8.dp),
                fontWeight = FontWeight.Bold
            )
        }
        if (trackerEvents.isEmpty()) {
            item {
                Text(
                    "No alerts detected yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        } else {
            items(trackerEvents, key = { it.id }) { event ->
                AlertLogRow(event = event, onClick = { onAlertTap(event) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertLogRow(
    event: com.aegisnav.app.data.model.ThreatEvent,
    onClick: () -> Unit
) {
    // Full MAC shown locally - user needs this to make an informed decision
    val displayMac = event.mac.ifBlank { "Unknown MAC" }

    // Parse all display fields from detailJson
    val manufacturer = remember(event.detailJson) {
        try { org.json.JSONObject(event.detailJson).optString("manufacturer", "Unknown").ifBlank { "Unknown" } }
        catch (e: org.json.JSONException) { AppLog.w("MainActivity", "Failed to parse manufacturer: ${e.message}"); "Unknown" }
    }
    val ssid = remember(event.detailJson) {
        try { org.json.JSONObject(event.detailJson).optString("ssid", "").ifBlank { null } }
        catch (e: org.json.JSONException) { AppLog.w("MainActivity", "Failed to parse ssid: ${e.message}"); null }
    }
    val lastRssi = remember(event.detailJson) {
        try {
            val j = org.json.JSONObject(event.detailJson)
            if (j.isNull("lastRssi")) null else j.optInt("lastRssi")
        } catch (e: org.json.JSONException) { AppLog.w("MainActivity", "Failed to parse lastRssi: ${e.message}"); null }
    }
    val sightingCount = remember(event.detailJson) {
        try { org.json.JSONObject(event.detailJson).optJSONArray("sightings")?.length() ?: 0 }
        catch (e: org.json.JSONException) { AppLog.w("MainActivity", "Failed to parse sightingCount: ${e.message}"); 0 }
    }
    val timeStr = remember(event.timestamp) {
        java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.US).format(java.util.Date(event.timestamp))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        onClick = onClick
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            Text("🔴", fontSize = 20.sp)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    displayMac,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
                Text(manufacturer, fontSize = 11.sp, color = Color.Gray)
                if (ssid != null) {
                    Text("SSID: $ssid", fontSize = 11.sp, color = Color(0xFF4FC3F7))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Seen: ${sightingCount}× locations", fontSize = 11.sp, color = Color.Gray)
                    if (lastRssi != null) {
                        val rssiColor = when {
                            lastRssi >= -60 -> Color(0xFFE53935)  // strong/close
                            lastRssi >= -75 -> Color(0xFFFFB300)  // moderate
                            else            -> Color.Gray          // weak/far
                        }
                        Text("Signal: $lastRssi dBm", fontSize = 11.sp, color = rssiColor)
                    }
                }
            }
            Text(timeStr, fontSize = 10.sp, color = Color.Gray)
        }
    }
}

// ── Download Progress Banner ───────────────────────────────────────────────────

/**
 * Compact banner shown at the bottom of the map when a tile_download WorkManager job is RUNNING.
 * Auto-hides when all jobs are no longer running.
 *
 * Uses a manual Box-based bar instead of LinearProgressIndicator (crashes on compose material3:1.1.2).
 * Colors: dark charcoal (#1A1A2E) background, cyan (#00BCD4) accent — matches AegisNav dark theme.
 */
@Composable
fun DownloadProgressBanner(
    workInfos: List<WorkInfo>?,
    modifier: Modifier = Modifier
) {
    val runningInfos = workInfos?.filter { it.state == WorkInfo.State.RUNNING } ?: emptyList()
    if (runningInfos.isEmpty()) return

    // Use the first running job for display (most common case is one download at a time)
    val info = runningInfos.first()
    val phase   = info.progress.getString(TileDownloadWorker.KEY_PHASE) ?: "tiles"
    val percent = info.progress.getInt(TileDownloadWorker.KEY_PERCENT, 0)
    val stateAbbr = info.tags
        .firstOrNull { it.startsWith(TileDownloadWorker.WORK_NAME_PREFIX) }
        ?.removePrefix(TileDownloadWorker.WORK_NAME_PREFIX)
        ?.uppercase()
        ?: ""

    val phaseLabel = when (phase) {
        "tiles"    -> "Map tiles"
        "geocoder" -> "Search index"
        "routing"  -> "Routing graph"
        "done"     -> "Complete"
        else       -> phase.replaceFirstChar { it.uppercase() }
    }
    val countSuffix = if (runningInfos.size > 1) " (+${runningInfos.size - 1} more)" else ""

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A2E).copy(alpha = 0.92f))
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "⬇ Downloading${if (stateAbbr.isNotBlank()) " $stateAbbr" else ""}$countSuffix — $phaseLabel",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF00BCD4),
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f)
            )
        }
        Spacer(Modifier.height(4.dp))
        // Progress bar — no LinearProgressIndicator (crashes on material3:1.1.2)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(Color.White.copy(alpha = 0.15f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = (percent / 100f).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(Color(0xFF00BCD4))
            )
        }
    }
}

// ── Threat computation ─────────────────────────────────────────────────────────

private fun computeCurrentThreatLevel(
    reports: List<Report>,
    incoming: List<IncomingReport>
): ThreatLevel {
    val recentCutoff = System.currentTimeMillis() - 15 * 60 * 1000L
    val recentHighThreat = reports.count {
        it.timestamp >= recentCutoff && it.threatLevel == "HIGH"
    } + incoming.count {
        it.timestamp >= recentCutoff && it.threatLevel == "HIGH"
    }
    val recentMediumThreat = reports.count {
        it.timestamp >= recentCutoff && it.threatLevel == "MEDIUM"
    } + incoming.count {
        it.timestamp >= recentCutoff && it.threatLevel == "MEDIUM"
    }
    return when {
        recentHighThreat > 0   -> ThreatLevel.HIGH
        recentMediumThreat > 0 -> ThreatLevel.MEDIUM
        else                   -> ThreatLevel.LOW
    }
}

// NewReportPopupHost and NewReportPopup moved to com.aegisnav.app.ui.NewReportPopup

private fun startScanService(context: Context, viewModel: MainViewModel) {
    try {
        // Persist user intent so ScanService knows a system-kill restart is wanted
        // Use Activity lifecycleScope to avoid GlobalScope leak
        (context as? ComponentActivity)?.lifecycleScope?.launch(kotlinx.coroutines.Dispatchers.IO) {
            SecureDataStore.get(context, "an_prefs").edit { // Async DataStore read
                it[booleanPreferencesKey("user_wants_scanning")] = true
            }
        }
        ContextCompat.startForegroundService(context, Intent(context, ScanService::class.java))
        viewModel.scanState.setScanning(true)
    } catch (e: Exception) {
        AppLog.e("Scan", "Error starting service", e)
    }
}

@Composable
fun StateSelectorDialog(
    manifest: TileManifest,
    onStateSelected: (String) -> Unit
) {
    var selectedAbbr by remember { mutableStateOf<String?>(null) }

    // Fall back to FL if manifest hasn't loaded yet
    val states = manifest.states.ifEmpty {
        listOf(TileStateEntry(abbr = "FL", name = "Florida"))
    }

    // Use Dialog + Surface instead of AlertDialog so we control height and can use LazyColumn.
    // AlertDialog's text slot cannot scroll properly with 50 items.
    Dialog(
        onDismissRequest = { /* non-dismissable - user must pick a state */ }
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {

                // Title
                Text(
                    "Welcome to AegisNav",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Select your state to load the correct ALPR camera database.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                // Scrollable state list
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(states, key = { it.abbr }) { entry ->
                        val selected = selectedAbbr == entry.abbr
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedAbbr = entry.abbr }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { selectedAbbr = entry.abbr }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    entry.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (selected) FontWeight.SemiBold
                                                 else FontWeight.Normal
                                )
                                if (entry.tilesSizeMb > 0) {
                                    Text(
                                        "Map ${entry.tilesSizeMb} MB - Geocoder ${entry.geocoderSizeMb} MB",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Text(
                                        "Select to download",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                entry.abbr,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "You can change this later in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { selectedAbbr?.let { onStateSelected(it) } },
                    enabled = selectedAbbr != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Continue", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
