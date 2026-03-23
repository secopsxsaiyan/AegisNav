package com.aegisnav.app.routing

import androidx.compose.foundation.background
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Route selection overlay shown at the bottom of the map (NOT a bottom sheet)
 * when routes have been calculated but navigation hasn't started yet.
 *
 * Displays the selected route summary and Go/Cancel buttons.
 * Shows buttons to load Shortest and Avoid ALPR routes on demand.
 */
@Composable
fun RouteSelectionOverlay(
    routeOptions: List<NavigationViewModel.RouteOption>,
    selectedIndex: Int,
    isLoading: Boolean,
    isLoadingShortest: Boolean = false,
    isLoadingAvoidAlpr: Boolean = false,
    onSelectRoute: (Int) -> Unit,
    onLoadShortest: () -> Unit = {},
    onLoadAvoidAlpr: () -> Unit = {},
    onGo: () -> Unit,
    onCancel: () -> Unit,
    onAddStop: (() -> Unit)? = null,
    canAddStop: Boolean = true,
    onSaveRoute: ((String) -> Unit)? = null,
    hasWaypoints: Boolean = false,
    modifier: Modifier = Modifier
) {
    var showSaveRouteDialog by remember { mutableStateOf(false) }
    var saveRouteNameInput by remember { mutableStateOf("") }

    if (showSaveRouteDialog) {
        AlertDialog(
            onDismissRequest = { showSaveRouteDialog = false },
            title = { Text("Save Route", color = Color.Black) },
            text = {
                OutlinedTextField(
                    value = saveRouteNameInput,
                    onValueChange = { saveRouteNameInput = it },
                    label = { Text("Route name", color = Color.Black.copy(alpha = 0.6f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color.Black
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = saveRouteNameInput.trim().ifBlank { "Route" }
                    onSaveRoute?.invoke(name)
                    showSaveRouteDialog = false
                    saveRouteNameInput = ""
                }) { Text("Save", color = Color.Black) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveRouteDialog = false }) { Text("Cancel", color = Color.Black) }
            }
        )
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            .padding(16.dp)
    ) {
        if (isLoading) {
            Text(
                "Calculating route…",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            val selected = routeOptions.getOrNull(selectedIndex)
            if (selected != null) {
                Text(
                    "${selected.label}: ${formatDistance(selected.result.distanceMeters)} • ${formatDuration(selected.result.durationSeconds)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                selected.surveillanceSummary?.let { surv ->
                    if (surv.alprCount + surv.redLightCount + surv.speedCameraCount > 0) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "📷 ${surv.alprCount} ALPR • 🔴 ${surv.redLightCount} Red Light • ⚡ ${surv.speedCameraCount} Speed",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFF8A65)
                        )
                    } else {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "✓ No cameras on this route",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF81C784)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Route option buttons row
            val hasFastest = routeOptions.any { it.preference == RoutePreference.FASTEST }
            val hasShortest = routeOptions.any { it.preference == RoutePreference.SHORTEST_DISTANCE }
            val hasAvoidAlpr = routeOptions.any { it.preference == RoutePreference.AVOID_ALPR }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Fastest button (always loaded)
                if (hasFastest) {
                    val fastestIdx = routeOptions.indexOfFirst { it.preference == RoutePreference.FASTEST }
                    val isFastestSelected = selectedIndex == fastestIdx
                    Button(
                        onClick = { onSelectRoute(fastestIdx) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isFastestSelected) Color(0xFF2196F3) else Color(0xFF2196F3).copy(alpha = 0.5f)
                        )
                    ) {
                        Text(
                            if (isFastestSelected) "✓ Fastest" else "Fastest",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isFastestSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }

                // Shortest button: load or select
                if (!hasShortest) {
                    if (isLoadingShortest) {
                        OutlinedButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.weight(1f)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Loading…", style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onLoadShortest,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Load Shortest", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else {
                    val shortestIdx = routeOptions.indexOfFirst { it.preference == RoutePreference.SHORTEST_DISTANCE }
                    val isShortestSelected = selectedIndex == shortestIdx
                    Button(
                        onClick = { onSelectRoute(shortestIdx) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isShortestSelected) Color(0xFF4CAF50) else Color(0xFF4CAF50).copy(alpha = 0.5f)
                        )
                    ) {
                        Text(
                            if (isShortestSelected) "✓ Shortest" else "Shortest",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isShortestSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }

                // Avoid ALPR button: load or select
                if (!hasAvoidAlpr) {
                    if (isLoadingAvoidAlpr) {
                        OutlinedButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.weight(1f)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Loading…", style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onLoadAvoidAlpr,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Load Avoid ALPR", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else {
                    val avoidIdx = routeOptions.indexOfFirst { it.preference == RoutePreference.AVOID_ALPR }
                    val isAvoidSelected = selectedIndex == avoidIdx
                    Button(
                        onClick = { onSelectRoute(avoidIdx) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isAvoidSelected) Color(0xFFFF9800) else Color(0xFFFF9800).copy(alpha = 0.5f)
                        )
                    ) {
                        Text(
                            if (isAvoidSelected) "✓ Avoid ALPR" else "Avoid ALPR",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isAvoidSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (onAddStop != null && canAddStop) {
                    OutlinedButton(
                        onClick = onAddStop,
                        modifier = Modifier.weight(1f)
                    ) { Text("+ Add Stop") }
                }

                if (onSaveRoute != null && hasWaypoints) {
                    OutlinedButton(
                        onClick = {
                            saveRouteNameInput = ""
                            showSaveRouteDialog = true
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("💾 Save", style = MaterialTheme.typography.labelSmall) }
                }

                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) { Text("Cancel") }

                Button(
                    onClick = onGo,
                    modifier = Modifier.weight(1f),
                    enabled = routeOptions.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF43A047))
                ) {
                    Text("Go", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
