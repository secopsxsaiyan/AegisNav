package com.aegisnav.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.aegisnav.app.TileDownloadWorker
import com.aegisnav.app.TileSourceResolver
import com.aegisnav.app.USStates
import com.aegisnav.app.data.DataDownloadManager
import com.aegisnav.app.data.StateInfo
import com.aegisnav.app.loadSelectedStates
import com.aegisnav.app.util.AppLog

private const val TAG = "DataDownloadScreen"

/**
 * In-app map data download screen.
 *
 * Used in two places:
 *  1. Privacy Wizard (step 4) – shows Skip/Continue button; [onSkip] is non-null.
 *  2. Settings screen – full standalone screen; [onSkip] is null, [onBack] navigates back.
 *
 * Features:
 *  - LazyColumn of all 50 US states + DC with checkboxes
 *  - Estimated download sizes per state (tiles + geocoder + routing)
 *  - Total size warning: amber if > 2 GB, red if > 4 GB
 *  - LinearProgressIndicator for download progress
 *  - ✅ checkmark + Remove button for already-installed states
 *  - Offline-mode guard (shows message instead of downloading)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataDownloadScreen(
    onBack: () -> Unit,
    onSkip: (() -> Unit)? = null,   // null = settings mode; non-null = wizard mode
    onDataChanged: () -> Unit = {}  // called after download starts or data deleted
) {
    val context = LocalContext.current
    val isWizardMode = onSkip != null

    // Track which states the user has checked for download
    var selectedCodes by remember { mutableStateOf(setOf<String>()) }

    // Track installed state – refreshed when composition re-enters foreground
    // Source of truth = filesystem (survives factory reset wipe of DataStore) merged with DataStore
    var installedStates by remember { mutableStateOf(setOf<String>()) }

    // Helper to rescan installed states from disk + DataStore
    fun refreshInstalledStates() {
        val fromDataStore = DataDownloadManager.ALL_STATES
            .filter { DataDownloadManager.isTilesInstalled(context, it.code) }
            .map { it.code }
            .toSet()
        val fromDisk = TileSourceResolver.downloadedStateCodes(context).toSet()
        installedStates = fromDataStore + fromDisk
    }

    LaunchedEffect(Unit) {
        refreshInstalledStates()
        AppLog.i("DataDownloadScreen", "installedStates: $installedStates")

        // In wizard mode, pre-select the states chosen in step 3
        if (onSkip != null) {
            val wizardSelected = loadSelectedStates(context)
            AppLog.i("WizardFlow", "DataDownloadScreen: isWizardMode=$isWizardMode")
            AppLog.i("WizardFlow", "DataDownloadScreen: wizardSelected=$wizardSelected")
            selectedCodes = wizardSelected.filter { code ->
                DataDownloadManager.byCode(code) != null && code !in installedStates
            }.toSet()
            AppLog.i("WizardFlow", "DataDownloadScreen: selectedCodes=$selectedCodes")
            AppLog.i("WizardFlow", "DataDownloadScreen: installedStates=$installedStates")
        }
    }

    // Observe all download workers — refresh installed states when any finishes
    val allDownloadWork by WorkManager.getInstance(context)
        .getWorkInfosByTagLiveData("tile_download")
        .observeAsState(emptyList())
    val completedCount = allDownloadWork.count { it.state == WorkInfo.State.SUCCEEDED }
    LaunchedEffect(completedCount) {
        if (completedCount > 0) {
            refreshInstalledStates()
            AppLog.i("DataDownloadScreen", "Download completed — refreshed installedStates: $installedStates")
        }
    }

    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }

    // Compute total selected MB
    val totalMb = remember(selectedCodes) {
        DataDownloadManager.ALL_STATES
            .filter { it.code in selectedCodes }
            .sumOf { it.totalSizeMb }
    }
    val totalGb = totalMb / 1024.0

    val availableMb = remember { DataDownloadManager.getAvailableStorageBytes(context) / 1_048_576 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Download Map Data") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isWizardMode) {
                        TextButton(onClick = { onSkip?.invoke() }) { Text("Skip") }
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {

            // ── Header ────────────────────────────────────────────────────
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        if (isWizardMode)
                            "Download map data for offline navigation"
                        else
                            "Select states to download offline data",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Each state includes map tiles, address search, routing graph, and ALPR/speed/red-light camera data. " +
                        "Downloads use HTTPS — use Wi-Fi for large files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // ALPR coverage summary
                    var alprStates by remember { mutableStateOf<Set<String>>(emptySet()) }
                    LaunchedEffect(Unit) { alprStates = loadSelectedStates(context) }
                    val alprLabel = USStates.forAbbrs(alprStates).joinToString(", ") { it.abbr }.ifBlank { "None" }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("📷 ALPR Coverage", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                "Active: $alprLabel — 78,000+ US cameras available",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Downloading a state automatically enables ALPR camera alerts for that state.",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                        }
                    }

                    Text(
                        "Free storage: ${availableMb} MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (availableMb < 500) MaterialTheme.colorScheme.error
                                else Color(0xFF43A047)
                    )
                }
            }

            // ── Total size warning ────────────────────────────────────────
            if (selectedCodes.isNotEmpty()) {
                item {
                    val (warningColor, warningBg, warningText) = when {
                        totalGb > 4.0 -> Triple(
                            Color(0xFFB71C1C),
                            Color(0xFFFFEBEE),
                            "⛔ ${String.format("%.1f", totalGb)} GB selected — This may cause crashes on devices with limited storage"
                        )
                        totalGb > 2.0 -> Triple(
                            Color(0xFFE65100),
                            Color(0xFFFFF3E0),
                            "⚠️ ${String.format("%.1f", totalGb)} GB selected — Large download, consider selecting fewer states"
                        )
                        else -> Triple(
                            MaterialTheme.colorScheme.onSurface,
                            MaterialTheme.colorScheme.surfaceVariant,
                            "📦 ${String.format("%.1f", totalGb)} GB selected (${selectedCodes.size} state${if (selectedCodes.size > 1) "s" else ""})"
                        )
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = warningBg)
                    ) {
                        Text(
                            warningText,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = warningColor,
                            fontWeight = if (totalGb > 2.0) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }

                // Download button
                item {
                    Button(
                        onClick = {
                            AppLog.i("WizardFlow", "Download button tapped: selectedCodes=$selectedCodes")
                            val statesToDownload = DataDownloadManager.ALL_STATES
                                .filter { it.code in selectedCodes && !DataDownloadManager.isTilesInstalled(context, it.code) }
                            statesToDownload.forEach { state ->
                                val started = DataDownloadManager.startDownload(context, state)
                                AppLog.i(TAG, "Download started for ${state.code}: $started")
                            }
                            selectedCodes = emptySet()
                            // NOTE: onDataChanged() intentionally NOT called here — startDownload()
                            // just enqueues a WorkManager job; files don't exist yet. The
                            // WorkManager observer in MainActivity will call refreshTileUri()
                            // when TileDownloadWorker actually completes (state == SUCCEEDED).
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        enabled = true
                    ) {
                        Text(
                            "Download ${selectedCodes.size} state${if (selectedCodes.size > 1) "s" else ""} " +
                            "(~${String.format("%.1f", totalGb)} GB)",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // ── State list section header ─────────────────────────────────
            item {
                Text(
                    "SELECT STATES",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }

            // ── Per-state rows ────────────────────────────────────────────
            items(DataDownloadManager.ALL_STATES, key = { it.code }) { state ->
                StateDownloadRow(
                    state = state,
                    isSelected = state.code in selectedCodes || state.code in installedStates,
                    isTilesInstalled = state.code in installedStates,
                    onToggleSelect = {
                        if (state.code in installedStates) {
                            // Unchecking an installed state → delete confirmation
                            showDeleteConfirm = state.code
                        } else {
                            selectedCodes = if (state.code in selectedCodes)
                                selectedCodes - state.code
                            else
                                selectedCodes + state.code
                        }
                    },
                    onRemove = { showDeleteConfirm = state.code }
                )
            }

            item { Spacer(Modifier.height(32.dp)) }

            // ── Wizard mode: Continue button at the bottom ────────────────
            if (isWizardMode) {
                item {
                    OutlinedButton(
                        onClick = { onSkip?.invoke() },
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("Continue Without Downloading →")
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    // (Offline mode download block removed — downloads always allowed)

    // ── Delete confirmation dialog ─────────────────────────────────────────
    showDeleteConfirm?.let { code ->
        val stateName = DataDownloadManager.byCode(code)?.name ?: code
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete $stateName Data?") },
            text = {
                Text("This will permanently delete the map tiles, address database, and routing graph for $stateName. " +
                     "You can re-download later.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        DataDownloadManager.deleteStateData(context, code)
                        installedStates = installedStates - code
                        showDeleteConfirm = null
                        onDataChanged()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text("Cancel") }
            }
        )
    }
}

/**
 * A single state row in the download list.
 * Shows:
 *  - Checkbox (if not installed)
 *  - State name + size estimate
 *  - WorkManager download progress (LinearProgressIndicator)
 *  - ✅ installed badge + Remove button (if tiles installed)
 */
@Composable
private fun StateDownloadRow(
    state: StateInfo,
    isSelected: Boolean,
    isTilesInstalled: Boolean,
    onToggleSelect: () -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current

    // Observe WorkManager for this state's download job (by tag, since downloads are serialized)
    val workInfos by WorkManager.getInstance(context)
        .getWorkInfosByTagLiveData(TileDownloadWorker.workName(state.code))
        .observeAsState(emptyList())
    // Find the most recent non-cancelled work info for this state
    val workInfo = workInfos.lastOrNull { it.state != WorkInfo.State.CANCELLED }
    val isRunning = workInfo?.state == WorkInfo.State.RUNNING ||
                    workInfo?.state == WorkInfo.State.ENQUEUED
    val isFailed  = workInfo?.state == WorkInfo.State.FAILED
    val phase     = workInfo?.progress?.getString(TileDownloadWorker.KEY_PHASE) ?: ""
    val percent   = workInfo?.progress?.getInt(TileDownloadWorker.KEY_PERCENT, 0) ?: 0

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Checkbox – always visible except during active download
                if (!isRunning) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() },
                        modifier = Modifier.size(36.dp)
                    )
                } else {
                    Spacer(Modifier.width(36.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.name, fontWeight = FontWeight.Medium)
                        if (isTilesInstalled) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "✅",
                                fontSize = 14.sp,
                                modifier = Modifier.padding(start = 2.dp)
                            )
                        }
                    }
                    Text(
                        "~${state.tileSizeMb} MB tiles · ${state.geocoderSizeMb} MB geocoder · ${state.routingSizeMb} MB routing",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                when {
                    isRunning -> {
                        TextButton(
                            onClick = {
                                DataDownloadManager.cancelDownload(context, state.code)
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "Cancel",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    isFailed -> {
                        Text(
                            "Failed",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Progress bar
            if (isRunning) {
                val phaseLabel = when (phase) {
                    "tiles"    -> "Downloading map tiles… $percent%"
                    "geocoder" -> "Downloading geocoder… $percent%"
                    "routing"  -> "Downloading routing graph… $percent%"
                    "done"     -> "Finalising…"
                    else       -> "Starting download…"
                }
                Text(
                    phaseLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                LinearProgressIndicator(
                    progress = { (percent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
