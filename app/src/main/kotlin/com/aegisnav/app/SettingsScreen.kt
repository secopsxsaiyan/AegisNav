package com.aegisnav.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.aegisnav.app.data.DataDownloadManager
import com.aegisnav.app.data.model.IgnoreListEntry
import com.aegisnav.app.data.model.WatchlistEntry
import com.aegisnav.app.ui.DataDownloadScreen
import com.aegisnav.app.security.SecureDataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onShowReportHistory: (() -> Unit)? = null,
    onShowThreatHistory: (() -> Unit)? = null,
    routingGraphAvailable: Boolean = false,
    routingGraphPath: String = "",
    isScanning: Boolean = false,
    scanIntensity: String = "balanced",
    onScanIntensityChange: (String) -> Unit = {},
    themeMode: ThemeMode = ThemeMode.AUTO,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    onToggleScan: () -> Unit = {},
    viewModel: MainViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedStates by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) { selectedStates = loadSelectedStates(context) }
    var showStateSelector by remember { mutableStateOf(false) }
    var showWipeConfirm by remember { mutableStateOf(false) }
    var showIgnoreList by remember { mutableStateOf(false) }
    var showDataDownload by remember { mutableStateOf(false) }
    val offlineMode by viewModel.offlineMode.collectAsStateWithLifecycle()
    val ttsMasterEnabled by viewModel.ttsMasterEnabled.collectAsStateWithLifecycle()
    val ttsTrackerEnabled by viewModel.ttsTrackerEnabled.collectAsStateWithLifecycle()
    val ttsPoliceEnabled by viewModel.ttsPoliceEnabled.collectAsStateWithLifecycle()
    val ttsSurveillanceEnabled by viewModel.ttsSurveillanceEnabled.collectAsStateWithLifecycle()
    val ttsConvoyEnabled by viewModel.ttsConvoyEnabled.collectAsStateWithLifecycle()
    var showOfflineModeTooltip by remember { mutableStateOf(false) }
    val ignoreList by viewModel.ignoreList.collectAsStateWithLifecycle()
    val recentDevices by viewModel.recentDeviceAddresses.collectAsStateWithLifecycle()
    var tileManifest by remember { mutableStateOf(TileManifest()) }
    LaunchedEffect(Unit) {
        tileManifest = withContext(kotlinx.coroutines.Dispatchers.IO) {
            MapTileManifest.load(context)
        }
    }
    val mapTilesAvailable = tileManifest.states.any { MapTileManifest.installedTilesFile(context, it.abbr) != null }
    val activeState by viewModel.activeState.collectAsStateWithLifecycle()
    var showActiveStateDialog by remember { mutableStateOf(false) }

    val suggestedIgnoreDevices by viewModel.suggestedIgnoreDevices.collectAsStateWithLifecycle()
    val watchlist by viewModel.watchlist.collectAsStateWithLifecycle()

    if (showDataDownload) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(modifier = Modifier.widthIn(max = 600.dp).fillMaxHeight()) {
                DataDownloadScreen(
                    onBack = { showDataDownload = false },
                    onSkip = null,  // settings mode — no Skip button
                    onDataChanged = { viewModel.refreshTileUri(context) }
                )
            }
        }
        return
    }

    if (showIgnoreList) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(modifier = Modifier.widthIn(max = 600.dp).fillMaxHeight()) {
                IgnoreListScreen(
                    ignoreList = ignoreList,
                    recentDevices = recentDevices,
                    suggestedDevices = suggestedIgnoreDevices.toList(),
                    onAddToIgnoreList = viewModel::addToIgnoreList,
                    onRemoveFromIgnoreList = viewModel::removeFromIgnoreList,
                    onBack = { showIgnoreList = false }
                )
            }
        }
        return
    }

    if (showStateSelector) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(modifier = Modifier.widthIn(max = 600.dp).fillMaxHeight()) {
                StateSelectionScreen(
                    onComplete = { abbrs ->
                        selectedStates = abbrs
                        scope.launch {
                            saveSelectedStates(context, abbrs) // Async DataStore read
                            // Reset preload flag so new states get loaded on next launch
                            SecureDataStore.get(context, "alpr_prefs").edit { // Async DataStore read
                                it.remove(booleanPreferencesKey("alpr_preloaded_v5"))
                            }
                        }
                        showStateSelector = false
                    }
                )
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            // ── Display / Theme ────────────────────────────────────────────
            item { SettingsSectionHeader("Display") }
            item {
                SettingsCard {
                    Text(
                        "Theme",
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Adjusts between light and dark based on sunrise, sunset, and ambient light.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.values().forEach { mode ->
                            val selected = mode == themeMode
                            OutlinedButton(
                                onClick = { onThemeModeChange(mode) },
                                border = if (selected)
                                    androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                else
                                    androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (selected)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else
                                        Color.Transparent
                                )
                            ) {
                                Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                            }
                        }
                    }
                }
            }

            // ── Reports & Threat History ────────────────────────────────────
            if (onShowReportHistory != null || onShowThreatHistory != null) {
                item { SettingsSectionHeader("Reports") }
                if (onShowReportHistory != null) {
                    item {
                        SettingsCard {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Report History", fontWeight = FontWeight.Bold)
                                    Text("View all submitted reports",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = onShowReportHistory) { Text("View") }
                            }
                        }
                    }
                }
                if (onShowThreatHistory != null) {
                    item {
                        SettingsCard {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Threat History", fontWeight = FontWeight.Bold)
                                    Text("View tracker alerts and BLE detections",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = onShowThreatHistory) { Text("View") }
                            }
                        }
                    }
                }
            }

            // ── Network / Offline Mode ─────────────────────────────────────
            item { SettingsSectionHeader("Network") }
            item {
                SettingsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Offline Mode", fontWeight = FontWeight.Bold)
                                Text(
                                    when {
                                        !FeatureFlags.FEATURE_ONLINE_MODE_AVAILABLE ->
                                            "Locked on — online features not yet available"
                                        !mapTilesAvailable ->
                                            "Download a state map to enable Offline Mode"
                                        offlineMode ->
                                            "No network data sent or received - offline routing only"
                                        else ->
                                            "Online mode - live routing and online features active"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (!mapTilesAvailable)
                                        MaterialTheme.colorScheme.error
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = if (!FeatureFlags.FEATURE_ONLINE_MODE_AVAILABLE) true else offlineMode,
                                enabled = FeatureFlags.FEATURE_ONLINE_MODE_AVAILABLE && mapTilesAvailable,
                                onCheckedChange = { enabled ->
                                    if (!FeatureFlags.FEATURE_ONLINE_MODE_AVAILABLE) return@Switch
                                    if (!mapTilesAvailable) {
                                        showOfflineModeTooltip = true
                                    } else {
                                        viewModel.setOfflineMode(enabled)
                                    }
                                }
                            )
                        }
                        // Street labels note - always visible to set expectations
                        Text(
                            "Street labels are available offline - bundled with the app.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF43A047)
                        )
                        Text(
                            "State map data available for download in Settings → Download Map Data.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Available offline: address search, navigation, anti-tracking, camera alerts.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Privacy ────────────────────────────────────────────────────
            item { SettingsSectionHeader("Privacy") }
            item {
                SettingsCard(onClick = { showIgnoreList = true }) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Ignore List", fontWeight = FontWeight.Bold)
                        Text(
                            "Manage devices excluded from reports (WiFi networks, BLE devices).",
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray
                        )
                        Text("${ignoreList.size} device(s) on ignore list",
                            style = MaterialTheme.typography.labelSmall)
                        Text("Tap to manage →",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // ── Watchlist ─────────────────────────────────────────────────
            item { SettingsSectionHeader("Watchlist") }
            item {
                SettingsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Always Alert MACs", fontWeight = FontWeight.Bold)
                        Text(
                            "Devices on the watchlist trigger an immediate alert whenever seen. Tap 🗑️ to remove.",
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray
                        )
                        if (watchlist.isEmpty()) {
                            Text(
                                "No devices on watchlist. Long-press a MAC in Threat History to add one.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            watchlist.forEach { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            entry.mac,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            "${entry.label}  •  ${entry.type}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.Gray
                                        )
                                    }
                                    IconButton(onClick = { viewModel.removeFromWatchlist(entry.mac) }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Remove from watchlist",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            }
                        }
                    }
                }
            }

            // ── Scanning ───────────────────────────────────────────────────
            item { SettingsSectionHeader("Scanning") }
            item {
                SettingsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (isScanning) "Scanning - Active" else "Scanning - Stopped",
                                fontWeight = FontWeight.Bold,
                                color = if (isScanning) Color(0xFF43A047) else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Continuously scans for Bluetooth Low Energy (BLE) and WiFi devices in the area. " +
                                "Detects trackers, persistent devices, and signals that may indicate surveillance or stalking.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = isScanning,
                            onCheckedChange = { onToggleScan() }
                        )
                    }
                }
            }

            // ── Scan Intensity (API 36+ only) ─────────────────────────────
            if (android.os.Build.VERSION.SDK_INT >= 36) {
                item {
                    SettingsCard {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Scan Intensity",
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    if (scanIntensity == "low_latency")
                                        "Maximum — 90% duty cycle, fastest detection, higher battery usage"
                                    else
                                        "Balanced — 10% duty cycle, good detection, battery friendly",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = scanIntensity == "low_latency",
                                onCheckedChange = { checked ->
                                    onScanIntensityChange(if (checked) "low_latency" else "balanced")
                                }
                            )
                        }
                    }
                }
            }

            // ── Voice Alerts ───────────────────────────────────────────────
            item { SettingsSectionHeader("Voice Alerts") }
            item {
                SettingsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Voice Alerts", fontWeight = FontWeight.Bold)
                                Text(
                                    "Enable or disable all spoken alerts",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = ttsMasterEnabled,
                                onCheckedChange = { viewModel.setTtsMasterEnabled(it) }
                            )
                        }
                        if (ttsMasterEnabled) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Tracker Alerts", fontWeight = FontWeight.Medium)
                                    Text(
                                        "Announce high-risk tracker detections",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = ttsTrackerEnabled,
                                    onCheckedChange = { viewModel.setTtsTrackerEnabled(it) }
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Police Alerts", fontWeight = FontWeight.Medium)
                                    Text(
                                        "Announce police equipment detection",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = ttsPoliceEnabled,
                                    onCheckedChange = { viewModel.setTtsPoliceEnabled(it) }
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Surveillance Alerts", fontWeight = FontWeight.Medium)
                                    Text(
                                        "Announce nearby cameras while navigating",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = ttsSurveillanceEnabled,
                                    onCheckedChange = { viewModel.setTtsSurveillanceEnabled(it) }
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Convoy Alerts", fontWeight = FontWeight.Medium)
                                    Text(
                                        "Announce coordinated movement and surveillance",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = ttsConvoyEnabled,
                                    onCheckedChange = { viewModel.setTtsConvoyEnabled(it) }
                                )
                            }
                        }
                    }
                }
            }

            // ── Data ───────────────────────────────────────────────────────
            item { SettingsSectionHeader("Data & Storage") }

            // Download Map Data card (unified: map tiles + ALPR coverage + state selection)
            item {
                SettingsCard(onClick = { showDataDownload = true }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Download Map Data", fontWeight = FontWeight.Bold)
                            Text(
                                "Map tiles, address search, routing graphs, and ALPR camera coverage for US states.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            var installedCount by remember { mutableIntStateOf(0) }
                            LaunchedEffect(showDataDownload) {
                                installedCount = DataDownloadManager.ALL_STATES.count {
                                    DataDownloadManager.isTilesInstalled(context, it.code)
                                }
                            }
                            // Active ALPR states
                            val stateNames = USStates.forAbbrs(selectedStates)
                                .joinToString(", ") { it.abbr }
                                .ifBlank { "None" }
                            Text(
                                "ALPR active: $stateNames (${selectedStates.size} state${if (selectedStates.size != 1) "s" else ""})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (installedCount > 0) "$installedCount state${if (installedCount > 1) "s" else ""} downloaded • 78,000+ US cameras available"
                                else "No states downloaded • 78,000+ US cameras available",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (installedCount > 0) Color(0xFF43A047) else Color.Gray
                            )
                        }
                        Text("→", style = MaterialTheme.typography.titleMedium,
                             color = MaterialTheme.colorScheme.primary,
                             modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            // ── Active States card ─────────────────────────────────────────────
            item {
                val downloadedStates = remember(showDataDownload) {
                    DataDownloadManager.ALL_STATES.filter {
                        DataDownloadManager.isTilesInstalled(context, it.code)
                    }
                }
                if (downloadedStates.isEmpty()) {
                    SettingsCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🗺️ ", style = MaterialTheme.typography.titleMedium)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Active Map State",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                                Text(
                                    "Download map data first",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }
                } else {
                    val activeStateName = downloadedStates.firstOrNull { it.code == activeState }?.name
                    SettingsCard(onClick = { showActiveStateDialog = true }) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🗺️ ", style = MaterialTheme.typography.titleMedium)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Active Map State",
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    if (activeStateName != null) "Active: $activeStateName"
                                    else "None selected — tap to choose",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (activeStateName != null)
                                        Color(0xFF00BCD4)
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "${downloadedStates.size} state${if (downloadedStates.size != 1) "s" else ""} available",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text("→", style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }

            item {
                SettingsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Danger Zone", fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error)
                        Text(
                            "Permanently delete all reports, scan logs, tracker alerts, saved locations, and ignore list entries from this device. Maps and routing data are not deleted.",
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray
                        )
                        Button(
                            onClick = { showWipeConfirm = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Delete, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Wipe All Data")
                        }
                    }
                }
            }

            // ── Routing Data ───────────────────────────────────────────────
            item { SettingsSectionHeader("Routing Data") }
            item {
                RoutingDataCard(
                    graphAvailable = routingGraphAvailable,
                    graphPath = routingGraphPath
                )
            }

            // ── About ──────────────────────────────────────────────────────
            item { SettingsSectionHeader("About") }
            item {
                SettingsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("AegisNav", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text("Version: 2026.03.19", style = MaterialTheme.typography.bodySmall)
                        Text("Licensed under the MIT License", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(2.dp))
                        Text("Open-Source Libraries", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        val notices = listOf(
                            "MapLibre GL Android — BSD 2-Clause License\nhttps://github.com/maplibre/maplibre-gl-native",
                            "GraphHopper 6.2 — Apache License 2.0\nhttps://github.com/graphhopper/graphhopper",
                            "JTS Topology Suite — BSD 3-Clause License (via GraphHopper)\nhttps://github.com/locationtech/jts",
                            "Android Jetpack (Room, Compose, Lifecycle, WorkManager) — Apache License 2.0\nhttps://developer.android.com/jetpack",
                            "Dagger / Hilt — Apache License 2.0\nhttps://github.com/google/dagger",
                            "Kotlin — Apache License 2.0\nhttps://kotlinlang.org",
                            "SQLCipher for Android — BSD-style License\nhttps://www.zetetic.net/sqlcipher",
                            "OkHttp — Apache License 2.0\nhttps://square.github.io/okhttp",
                            "Gson — Apache License 2.0\nhttps://github.com/google/gson",
                            "zstd-jni — BSD 2-Clause License\nhttps://github.com/luben/zstd-jni",
                            "Material Components for Android — Apache License 2.0\nhttps://github.com/material-components/material-components-android",
                            "Jetpack Security (security-crypto) — Apache License 2.0\nhttps://developer.android.com/jetpack/androidx/releases/security"
                        )
                        notices.forEach { notice ->
                            Text(notice, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                        Spacer(Modifier.height(2.dp))
                        Text("Data Sources", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "Map, address & routing data © OpenStreetMap contributors (ODbL)\nhttps://www.openstreetmap.org/copyright",
                            style = MaterialTheme.typography.labelSmall, color = Color.Gray
                        )
                        Text(
                            "Surveillance camera data: OpenStreetMap contributors (ODbL), EFF Atlas of Surveillance (CC BY 4.0)\nhttps://atlasofsurveillance.org — 78,460+ US cameras indexed",
                            style = MaterialTheme.typography.labelSmall, color = Color.Gray
                        )
                        Text(
                            "PMTiles format — BSD 3-Clause License\nhttps://github.com/protomaps/PMTiles",
                            style = MaterialTheme.typography.labelSmall, color = Color.Gray
                        )

                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    // Wipe confirmation dialog
    if (showWipeConfirm) {
        AlertDialog(
            onDismissRequest = { showWipeConfirm = false },
            title = { Text("Wipe All Data?") },
            text = {
                Text("This will permanently delete:\n• All reports and scan logs\n• Tracker alerts and threat history\n• Flock sightings\n• Beacon history\n• User-submitted ALPR cameras\n• Ignore list\n• Watchlist\n• All saved navigation locations\n• Baseline device fingerprints\n• Following detection events\n• Debug logs\n\nThe setup wizard will run again on next launch.\n\n⚠ Maps and routing data are NOT deleted.\n\nThis cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.wipeAllData(context)
                        showWipeConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Delete Everything") }
            },
            dismissButton = {
                TextButton(onClick = { showWipeConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showOfflineModeTooltip) {
        AlertDialog(
            onDismissRequest = { showOfflineModeTooltip = false },
            title = { Text("Map Download Required") },
            text = {
                Text(
                    "Offline Mode requires a downloaded state map. " +
                    "Go to Settings → Download Map Data to get your state."
                )
            },
            confirmButton = {
                TextButton(onClick = { showOfflineModeTooltip = false }) { Text("Got it") }
            }
        )
    }

    // ── Active State selection dialog ─────────────────────────────────────
    if (showActiveStateDialog) {
        val downloadedStates = remember {
            DataDownloadManager.ALL_STATES.filter {
                DataDownloadManager.isTilesInstalled(context, it.code)
            }
        }
        Dialog(onDismissRequest = { showActiveStateDialog = false }) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = Color.White,
                tonalElevation = 8.dp
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        "🗺️  Active Map State",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00BCD4)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Select which downloaded state to show on the map.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF666666)
                    )
                    Spacer(Modifier.height(16.dp))
                    downloadedStates.forEach { state ->
                        val isActive = state.code == activeState
                        val tileFile = context.filesDir.resolve("tiles/${state.code.lowercase()}.pmtiles")
                            .takeIf { it.exists() }
                            ?: context.filesDir.resolve("tiles/${state.name.lowercase()}.pmtiles")
                                .takeIf { it.exists() }
                        val sizeMb = tileFile?.length()?.let { it / (1024 * 1024) } ?: state.tileSizeMb.toLong()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clickable {
                                    viewModel.setActiveState(state.code, context)
                                    showActiveStateDialog = false
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                RadioButton(
                                    selected = isActive,
                                    onClick = {
                                        viewModel.setActiveState(state.code, context)
                                        showActiveStateDialog = false
                                    },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = Color(0xFF00BCD4),
                                        unselectedColor = Color(0xFF999999)
                                    )
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        state.name,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isActive) Color(0xFF00BCD4)
                                                else Color(0xFF1A1A1A)
                                    )
                                    Text(
                                        "$sizeMb MB on disk",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF888888)
                                    )
                                }
                            }
                        }
                        HorizontalDivider(
                            color = Color(0xFFE0E0E0),
                            modifier = Modifier.padding(start = 48.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { showActiveStateDialog = false },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("Done", color = Color(0xFF00BCD4)) }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    if (onClick != null) {
        Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
            Column(modifier = Modifier.padding(16.dp), content = content)
        }
    } else {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), content = content)
        }
    }
}

@Composable
fun IgnoreListScreen(
    ignoreList: List<IgnoreListEntry>,
    recentDevices: List<String>,
    suggestedDevices: List<String> = emptyList(),
    onAddToIgnoreList: (String) -> Unit,
    onRemoveFromIgnoreList: (String) -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Ignore List", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Ignored devices are excluded from threat detection and reports.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (ignoreList.isEmpty() && recentDevices.isEmpty()) {
            Text(
                "No devices detected yet. Start scanning to populate this list.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val ignoredAddresses = ignoreList.map { it.address }.toSet()
            val notIgnored = recentDevices.filter { it !in ignoredAddresses }
            val suggestedNotIgnored = suggestedDevices.filter { it !in ignoredAddresses }
            LazyColumn {
                if (ignoreList.isNotEmpty()) {
                    item {
                        Text(
                            "IGNORED",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    items(ignoreList, key = { it.address }) { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = true,
                                onCheckedChange = { onRemoveFromIgnoreList(entry.address) }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(entry.address, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    entry.label.ifBlank { "Unknown device" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                if (notIgnored.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "RECENTLY DETECTED",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    items(notIgnored, key = { it }) { address ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = false,
                                onCheckedChange = { if (it) onAddToIgnoreList(address) }
                            )
                            Text(address, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (suggestedNotIgnored.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "SUGGESTED FOR IGNORE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    items(suggestedNotIgnored, key = { it }) { address ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = false,
                                onCheckedChange = { if (it) onAddToIgnoreList(address) }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(address, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Seen at session start across multiple sessions",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MapDownloadSection(
    manifest: TileManifest,
    context: android.content.Context,
    onDownload: (TileStateEntry) -> Unit,
    onCancel: (String) -> Unit
) {
    if (manifest.states.isEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Map Tiles", fontWeight = FontWeight.Bold)
                Text(
                    "No states available. Check back later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        manifest.states.forEach { entry ->
            val workInfos by WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkLiveData(TileDownloadWorker.workName(entry.abbr))
                .observeAsState(emptyList())
            val workInfo = workInfos.firstOrNull()
            val isRunning = workInfo?.state == WorkInfo.State.RUNNING ||
                workInfo?.state == WorkInfo.State.ENQUEUED
            val phase   = workInfo?.progress?.getString(TileDownloadWorker.KEY_PHASE) ?: ""
            val percent = workInfo?.progress?.getInt(TileDownloadWorker.KEY_PERCENT, 0) ?: 0
            val tilesInstalled    = MapTileManifest.installedTilesFile(context, entry.abbr) != null
            val geocoderInstalled = MapTileManifest.installedGeocoderFile(context, entry.abbr) != null

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.name, fontWeight = FontWeight.Bold)
                            Text(
                                buildString {
                                    append("Map: ${entry.tilesSizeMb} MB")
                                    if (entry.geocoderSizeMb > 0) append(" · Geocoder: ${entry.geocoderSizeMb} MB")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        when {
                            tilesInstalled && !isRunning -> {
                                Text(
                                    "✓ Installed",
                                    color = Color(0xFF43A047),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            isRunning -> {
                                TextButton(onClick = { onCancel(entry.abbr) }) { Text("Cancel") }
                            }
                            entry.tilesUrl.isBlank() -> {
                                Text(
                                    "Not yet available",
                                    color = Color.Gray,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            else -> {
                                Button(
                                    onClick = { onDownload(entry) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text("Download")
                                }
                            }
                        }
                    }

                    if (isRunning) {
                        val label = when (phase) {
                            "tiles"    -> "Downloading map tiles… $percent%"
                            "geocoder" -> "Downloading geocoder… $percent%"
                            else       -> "Starting…"
                        }
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        LinearProgressIndicator(
                            progress = { (percent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (tilesInstalled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                "Map ✓",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF43A047)
                            )
                            if (entry.geocoderSizeMb > 0) {
                                Text(
                                    if (geocoderInstalled) "Geocoder ✓" else "Geocoder not installed",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (geocoderInstalled) Color(0xFF43A047) else Color.Gray
                                )
                            }
                        }
                    }
                }
            }
        }

        Text(
            "Tip: Host your .pmtiles files on GitHub Releases or Cloudflare R2 and update the manifest URL in MapTileManifest.kt",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutingDataCard(
    graphAvailable: Boolean,
    @Suppress("UNUSED_PARAMETER") graphPath: String
) {
    val statusText = if (graphAvailable) "Available ✓" else "Not found"
    val statusColor = if (graphAvailable) Color(0xFF43A047) else MaterialTheme.colorScheme.error

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Routing Graph", fontWeight = FontWeight.Bold)
            Text("Graph: $statusText", color = statusColor, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
