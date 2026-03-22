package com.aegisnav.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aegisnav.app.data.model.ScanLog
import com.aegisnav.app.tracker.OuiLookup

@Composable
fun FirstLaunchIgnoreDialog(
    deviceLogs: List<ScanLog>,
    onIgnoreAll: (List<String>) -> Unit,
    onIgnoreSelected: (List<String>) -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    var showAdvanced by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>().also { list ->
        deviceLogs.forEach { list.add(it.deviceAddress) }
    }}

    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("Nearby Devices Detected", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${deviceLogs.size} device(s) detected nearby. Add familiar devices " +
                    "(your phone, car, router, earbuds) to the ignore list to prevent false tracker alerts.",
                    style = MaterialTheme.typography.bodySmall
                )
                HorizontalDivider()
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(deviceLogs, key = { it.deviceAddress }) { log ->
                        val manufacturer = remember(log.deviceAddress) {
                            OuiLookup.lookup(context, log.deviceAddress)
                        }
                        val isSelected = log.deviceAddress in selected
                        val signalBars = rssiToBars(log.rssi)
                        val signalColor = when {
                            log.rssi > -60 -> Color(0xFF43A047)
                            log.rssi > -75 -> Color(0xFFFFB300)
                            else           -> Color(0xFFE53935)
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected)
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.surface
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (showAdvanced) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            if (checked) selected.add(log.deviceAddress)
                                            else selected.remove(log.deviceAddress)
                                        }
                                    )
                                }

                                // Type badge
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (log.scanType == "BLE") Color(0xFF1565C0)
                                            else Color(0xFF00796B),
                                    modifier = Modifier.padding(end = 2.dp)
                                ) {
                                    Text(
                                        text = if (log.scanType == "BLE") "BLE" else "WiFi",
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    // MAC address (full, not redacted)
                                    Text(
                                        text = log.deviceAddress,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                    // SSID (WiFi only)
                                    if (log.scanType == "WIFI" && !log.ssid.isNullOrBlank()) {
                                        Text(
                                            text = "📶 ${log.ssid}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    // Manufacturer
                                    Text(
                                        text = manufacturer ?: if (log.scanType == "BLE") "Unknown BLE device" else "Unknown AP",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    // Tracker flag
                                    if (log.isTracker) {
                                        Text(
                                            text = "⚠️ Flagged as possible tracker",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                // Signal strength
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = signalBars,
                                        fontSize = 14.sp,
                                        color = signalColor
                                    )
                                    Text(
                                        text = "${log.rssi} dBm",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!showAdvanced) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showAdvanced = true }) { Text("Choose") }
                    Button(onClick = { onIgnoreAll(deviceLogs.map { it.deviceAddress }) }) {
                        Text("Ignore All")
                    }
                }
            } else {
                Button(
                    onClick = { onIgnoreSelected(selected.toList()) },
                    enabled = selected.isNotEmpty()
                ) {
                    Text("Ignore ${selected.size} Device${if (selected.size != 1) "s" else ""}")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip) { Text("Skip") }
        }
    )
}

private fun rssiToBars(rssi: Int): String = when {
    rssi > -60 -> "▂▄▆█"
    rssi > -70 -> "▂▄▆░"
    rssi > -80 -> "▂▄░░"
    else       -> "▂░░░"
}
