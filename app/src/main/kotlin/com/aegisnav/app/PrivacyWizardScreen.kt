package com.aegisnav.app

import android.Manifest
import com.aegisnav.app.util.AppLog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.core.content.ContextCompat
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aegisnav.app.p2p.P2PManager
import com.aegisnav.app.security.SecureDataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.aegisnav.app.ui.DataDownloadScreen
import kotlinx.coroutines.delay

/**
 * First-run wizard - 5 steps:
 * 1. Privacy: passive BLE+WiFi scan → build ignore list
 * 2. Background power: battery optimization exemption
 * 3. State selection: choose home state(s) for ALPR data
 * 4. Data download: download map tiles, geocoder DB, routing graph (skippable)
 * 5. P2P setup: configure relay nodes (skippable, defaults to offline-only)
 */
@Composable
fun PrivacyWizardScreen(
    onComplete: () -> Unit,
    viewModel: PrivacyWizardViewModel = hiltViewModel(),
    mainViewModel: MainViewModel = hiltViewModel()
) {
    var step by remember { mutableStateOf(1) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Request POST_NOTIFICATIONS on Android 13+ (needed for tracker/police/flock alerts)
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — alerts just won't show if denied */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // ── Step 2: Battery Optimization ──────────────────────────────────────
    if (step == 2) {
        val batteryStepLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { /* result ignored - user can deny, app still proceeds */ }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(5) { i ->
                        Box(modifier = Modifier.size(if (i == 1) 10.dp else 8.dp).padding(2.dp)) {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                shape = MaterialTheme.shapes.small,
                                color = if (i == 1) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant
                            ) {}
                        }
                    }
                }

                Text("Step 2 of 5 - Background Power",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)

                Text("Background Power", fontSize = 22.sp, fontWeight = FontWeight.Bold)

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("🔋", fontSize = 36.sp)
                        Text(
                            "AegisNav needs to run uninterrupted in the background. " +
                            "Tap below to exempt it from battery optimization.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(
                            onClick = {
                                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                                if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                                    try {
                                        batteryStepLauncher.launch(
                                            Intent(
                                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                        )
                                    } catch (e: Exception) {
                                        AppLog.w("PrivacyWizardScreen", "Battery optimization intent failed: ${e.message}")
                                        try {
                                            batteryStepLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                        } catch (e2: Exception) { AppLog.w("PrivacyWizardScreen", "Fallback battery settings intent failed: ${e2.message}") }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Grant Exemption", fontWeight = FontWeight.Bold) }
                    }
                }

                Spacer(Modifier.weight(1f))

                Button(
                    onClick = { step = 3 },
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) { Text("Next: Select Your State →", fontWeight = FontWeight.Bold) }
            }
        }
        return
    }

    // ── Step 3: State Selection ────────────────────────────────────────────
    if (step == 3) {
        StateSelectionScreen(
            onComplete = { abbrs ->
                scope.launch {
                    saveSelectedStates(context, abbrs) // Async DataStore read
                    // Reset ALPR preload so new state data gets loaded
                    SecureDataStore.get(context, "alpr_prefs").edit { // Async DataStore read
                        it.remove(booleanPreferencesKey("alpr_preloaded_v5"))
                    }
                    AppLog.i("WizardFlow", "Step 3 complete: saved states=$abbrs, advancing to step 4")
                    step = 4 // now inside the coroutine, after write completes
                }
            }
        )
        return
    }

    // ── Step 4: Download Map Data ──────────────────────────────────────────
    if (step == 4) {
        DataDownloadScreen(
            onBack = { step = 3 },
            onSkip = { step = 5 },
            onDataChanged = { mainViewModel.refreshTileUri(context) }
        )
        return
    }

    // ── Step 5: P2P Setup ─────────────────────────────────────────────────
    if (step == 5) {
        com.aegisnav.app.p2p.P2PSetupScreen(
            onComplete = { useDefault, customNodes ->
                scope.launch {
                    // Async DataStore read
                    SecureDataStore.get(context, P2PManager.PREFS_NAME).edit {
                        it[booleanPreferencesKey(P2PManager.PREF_USE_DEFAULT)] = useDefault
                        it[stringPreferencesKey(P2PManager.PREF_CUSTOM_NODE_1)] = customNodes.getOrNull(0) ?: ""
                        it[stringPreferencesKey(P2PManager.PREF_CUSTOM_NODE_2)] = customNodes.getOrNull(1) ?: ""
                    }
                    markWizardDone(context)
                }
                onComplete()
            }
        )
        return
    }

    // ── Step 1: Privacy / Ignore List ─────────────────────────────────────
    var scanningDone by remember { mutableStateOf(false) }
    var secondsRemaining by remember { mutableStateOf(60) }
    val detectedDevices = viewModel.detectedDevices
    val selectedAddresses = viewModel.selectedAddresses

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED) {
            val bleScanner = PassiveBleScanner(context)
            bleScanner.scan(
                onResult = { result ->
                    viewModel.addDetected(result.address, result.name ?: "Unknown BLE Device", "BLE")
                },
                onDone = { scanningDone = true }
            )
        } else {
            scanningDone = true
        }
        val wifiScanner = PassiveWifiScanner(context)
        wifiScanner.scan { results ->
            results.forEach { (ssid, bssid) ->
                if (ssid.isNotBlank()) viewModel.addDetected(bssid, ssid, "WIFI")
            }
        }
        while (secondsRemaining > 0 && !scanningDone) {
            delay(1000L)
            secondsRemaining--
        }
        scanningDone = true
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Progress indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(5) { i ->
                    Box(
                        modifier = Modifier
                            .size(if (i == 0) 10.dp else 8.dp)
                            .padding(2.dp)
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            shape = MaterialTheme.shapes.small,
                            color = if (i == 0) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                        ) {}
                    }
                }
            }

            Text("Step 1 of 5 - Protect Your Privacy",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)

            Text("Before we start - protect your privacy",
                fontSize = 22.sp, fontWeight = FontWeight.Bold)

            Text(
                "These devices were detected nearby. Check any that belong to you. " +
                "They will NEVER be included in reports or shared with the network.",
                style = MaterialTheme.typography.bodyMedium
            )

            if (!scanningDone) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("⏳", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("Scanning… ${secondsRemaining}s remaining")
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { scanningDone = true }) {
                            Text("Stop Scanning")
                        }
                    }
                }
            }

            if (scanningDone && detectedDevices.isEmpty()) {
                Text("No nearby devices detected.",
                    style = MaterialTheme.typography.bodySmall)
            }

            if (detectedDevices.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.addAll() }) { Text("Add All") }
                    OutlinedButton(onClick = { viewModel.saveIgnoreList(); step = 2 }) { Text("Skip") }
                }

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(detectedDevices, key = { it.address }) { device ->
                        val isSelected = device.address in selectedAddresses
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { viewModel.toggleSelection(device.address) }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(device.label, fontWeight = FontWeight.Medium)
                                    Text(device.address,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Surface(
                                    color = if (device.type == "BLE")
                                        MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.secondaryContainer,
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(device.type,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }

            Button(
                onClick = {
                    viewModel.saveIgnoreList()
                    step = 2
                },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) { Text("Next: Background Power →", fontWeight = FontWeight.Bold) }
        }
    }
}

suspend fun markWizardDone(context: Context) {
    // Async DataStore read
    SecureDataStore.get(context, "an_prefs").edit {
        it[booleanPreferencesKey("privacy_wizard_done")] = true
    }
}
