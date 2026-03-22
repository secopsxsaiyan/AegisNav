package com.aegisnav.app.routing

import android.location.Geocoder
import android.location.Address
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

// ── Turn arrow mapping ────────────────────────────────────────────────────────

/** Returns a Unicode arrow character for a GraphHopper sign constant. */
fun turnArrow(sign: Int): String = when (sign) {
    -3   -> "↰"  // sharp left
    -2   -> "←"  // left
    -1   -> "↖"  // slight left
     0   -> "↑"  // straight
     1   -> "↗"  // slight right
     2   -> "→"  // right
     3   -> "↱"  // sharp right
     4   -> "🏁"  // finish
     5   -> "📍"  // via
     6   -> "🔄"  // roundabout
    else -> "↑"
}

// ── Navigation FAB ─────────────────────────────────────────────────────────────

/**
 * Floating action button that opens/closes the navigation bottom sheet.
 * Place in the map Box composable.
 */
@Composable
fun NavigationFab(
    isNavigating: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = if (isNavigating) MaterialTheme.colorScheme.tertiary
                         else MaterialTheme.colorScheme.primary
    ) {
        Icon(
            imageVector = if (isNavigating) Icons.Default.Close else Icons.Default.Place,
            contentDescription = if (isNavigating) "Stop Navigation" else "Navigate"
        )
    }
}

// ── Navigation Input Bottom Sheet ─────────────────────────────────────────────

/**
 * Bottom sheet for entering a destination and starting navigation.
 */
data class GeocoderResult(val displayName: String, val lat: Double, val lon: Double)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NavigationInputSheet(
    currentLat: Double,
    currentLon: Double,
    onDismiss: () -> Unit,
    preselectedDest: LatLon? = null,
    viewModel: NavigationViewModel = hiltViewModel()
) {
    // ── Dark color palette ─────────────────────────────────────────────────────
    val sheetBg      = Color(0xFF1A1A2E)
    val cardBg       = Color(0xFF16213E)
    val textPrimary  = Color(0xFFE0E0E0)
    val textSecondary = Color(0xFF9E9E9E)
    val accent       = Color(0xFF00BCD4)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current

    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val routePoints by viewModel.routePoints.collectAsStateWithLifecycle()
    val isNavigating by viewModel.isNavigating.collectAsStateWithLifecycle()
    val routingAvailable by viewModel.routingAvailable.collectAsStateWithLifecycle()
    val canRoute by viewModel.canRoute.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val offlineMode by viewModel.offlineMode.collectAsStateWithLifecycle()

    // Debug: log routing state on every recomposition
    LaunchedEffect(canRoute, routingAvailable, offlineMode) {
        com.aegisnav.app.util.AppLog.i("NavigationUI", "canRoute=$canRoute routingAvailable=$routingAvailable offlineMode=$offlineMode")
    }
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    val savedLocations by viewModel.savedLocations.collectAsStateWithLifecycle()
    // Phase 5: alternative routes + preference
    val alternativeRoutes by viewModel.alternativeRoutes.collectAsStateWithLifecycle()
    val routePreference by viewModel.routePreference.collectAsStateWithLifecycle()
    val activeRouteResult by viewModel.routeResult.collectAsStateWithLifecycle()

    // IME padding — static read to avoid imePadding() crash
    val imeBottom = WindowInsets.ime.getBottom(density)

    var addressQuery by remember { mutableStateOf("") }
    var geocoderResults by remember { mutableStateOf<List<GeocoderResult>>(emptyList()) }
    var geocoderError by remember { mutableStateOf<String?>(null) }
    var showLatLonFallback by remember(offlineMode) { mutableStateOf(offlineMode) }
    var isGeocoding by remember { mutableStateOf(false) }
    val offlineGeocoderAvailable = remember { viewModel.isOfflineGeocoderAvailable("fl") }
    var selectedDest by remember { mutableStateOf<LatLon?>(null) }
    var destLat by remember { mutableStateOf("") }
    var destLon by remember { mutableStateOf("") }
    val profile = "car" // foot profile removed from graph
    var parseError by remember { mutableStateOf<String?>(null) }

    // Dialog/snackbar state for saved locations
    val snackbarHostState = remember { SnackbarHostState() }
    var showSavePresetDialog by remember { mutableStateOf<String?>(null) }
    var showBookmarkDialog by remember { mutableStateOf(false) }
    var bookmarkNameInput by remember { mutableStateOf("") }
    var deletePresetType by remember { mutableStateOf<String?>(null) }
    var deleteRecentSearch by remember { mutableStateOf<RecentSearch?>(null) }

    // As-you-type search: debounce 350ms, min 3 chars
    LaunchedEffect(addressQuery) {
        if (addressQuery.length < 3) return@LaunchedEffect
        kotlinx.coroutines.delay(350L)
        val query = addressQuery.trim()
        if (query.isBlank()) return@LaunchedEffect
        isGeocoding = true
        geocoderError = null
        geocoderResults = emptyList()
        val results = withContext(Dispatchers.IO) {
            val offlineGeocoderResults = if (offlineGeocoderAvailable) {
                val offline = viewModel.searchOffline(query, "fl", currentLat, currentLon)
                offline
                    .sortedBy { r -> haversineDistance(currentLat, currentLon, r.lat, r.lon) }
                    .take(3)
                    .map { r -> GeocoderResult(r.displayName, r.lat, r.lon) }
            } else emptyList()

            val recentMatches = recentSearches.filter { it.name.contains(query, ignoreCase = true) }
                .take(3).map { recent -> GeocoderResult(recent.name, recent.lat, recent.lon) }

            val onlineResults = if (!offlineMode && Geocoder.isPresent()) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        withTimeoutOrNull(8_000L) {
                            suspendCancellableCoroutine<List<GeocoderResult>> { cont ->
                                Geocoder(context).getFromLocationName(query, 3) { addresses ->
                                    cont.resumeWith(Result.success(addresses.mapNotNull { addr ->
                                        if (addr.hasLatitude() && addr.hasLongitude())
                                            GeocoderResult(buildAddressLine(addr, query), addr.latitude, addr.longitude)
                                        else null
                                    }))
                                }
                            }
                        } ?: emptyList()
                    } else {
                        withTimeoutOrNull(8_000L) {
                            @Suppress("DEPRECATION")
                            Geocoder(context).getFromLocationName(query, 3)
                                ?.mapNotNull { addr ->
                                    if (addr.hasLatitude() && addr.hasLongitude())
                                        GeocoderResult(buildAddressLine(addr, query), addr.latitude, addr.longitude)
                                    else null
                                } ?: emptyList()
                        } ?: emptyList()
                    }
                }.getOrElse { emptyList() }
            } else emptyList()

            val filteredOnline = onlineResults.filter { r ->
                haversineDistance(currentLat, currentLon, r.lat, r.lon) < 200_000.0
            }

            (recentMatches + offlineGeocoderResults + filteredOnline)
                .distinctBy { it.lat to it.lon }
                .sortedBy { r -> haversineDistance(currentLat, currentLon, r.lat, r.lon) }
                .take(6)
        }
        isGeocoding = false
        geocoderResults = results
        if (results.isEmpty()) geocoderError = if (offlineMode) "No results in offline database - use coordinates" else "No results found"
    }

    // Pre-fill destination if set from map long-press
    LaunchedEffect(preselectedDest) {
        if (preselectedDest != null) {
            selectedDest = preselectedDest
            destLat = preselectedDest.lat.toString()
            destLon = preselectedDest.lon.toString()
            showLatLonFallback = true
        }
    }

    // ── Dialog + Surface replaces ModalBottomSheet (crash-safe for compose 1.5.4 + m3 1.1.2) ──
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
            color = sheetBg,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = (imeBottom / density.density).dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Title ──────────────────────────────────────────────────────────
            Text(
                "Navigate",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = textPrimary
            )

            Text(
                "From: ${String.format("%.5f, %.5f", currentLat, currentLon)}",
                style = MaterialTheme.typography.bodySmall,
                color = textSecondary
            )

            // ── Address search field ───────────────────────────────────────────
            OutlinedTextField(
                value = addressQuery,
                onValueChange = {
                    addressQuery = it
                    geocoderResults = emptyList()
                    geocoderError = null
                    selectedDest = null
                },
                label = { Text(
                    if (offlineGeocoderAvailable) "Type destination (offline search)" else "Type destination address",
                    color = textSecondary
                ) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent,
                    unfocusedBorderColor = textSecondary,
                    focusedLabelColor = accent,
                    unfocusedLabelColor = textSecondary,
                    focusedTextColor = textPrimary,
                    unfocusedTextColor = textPrimary,
                    cursorColor = accent
                ),
                trailingIcon = {
                    if (isGeocoding) {
                        Text("...", color = accent, fontSize = 14.sp)
                    }
                }
            )

            // ── Geocoder results ───────────────────────────────────────────────
            if (geocoderResults.isNotEmpty()) {
                Text("Select destination:", style = MaterialTheme.typography.labelMedium, color = textSecondary)
                LazyColumn(modifier = Modifier.heightIn(max = 140.dp)) {
                    items(geocoderResults, key = { "${it.lat},${it.lon}" }) { result ->
                        val isSelected = selectedDest?.lat == result.lat && selectedDest?.lon == result.lon
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clickable {
                                    selectedDest = LatLon(result.lat, result.lon)
                                    addressQuery = result.displayName
                                    geocoderResults = emptyList()
                                    geocoderError = null
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                    viewModel.addRecentSearch(result.displayName, result.lat, result.lon)
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) accent.copy(alpha = 0.2f) else cardBg
                            )
                        ) {
                            Text(
                                result.displayName,
                                modifier = Modifier.padding(10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSelected) accent else textPrimary,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // Geocoder error
            geocoderError?.let { msg ->
                Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            // ── Recent searches ────────────────────────────────────────────────
            if (recentSearches.isNotEmpty()) {
                Text("Recent", style = MaterialTheme.typography.labelMedium, color = textSecondary)
                LazyColumn(modifier = Modifier.heightIn(max = 120.dp)) {
                    items(recentSearches, key = { it.name }) { recent ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .combinedClickable(
                                    onClick = {
                                        selectedDest = LatLon(recent.lat, recent.lon)
                                        addressQuery = recent.name
                                        geocoderResults = emptyList()
                                        geocoderError = null
                                        keyboardController?.hide()
                                        focusManager.clearFocus()
                                    },
                                    onLongClick = { deleteRecentSearch = recent }
                                ),
                            colors = CardDefaults.cardColors(containerColor = cardBg)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text(recent.name, style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium, color = textPrimary)
                                Text(
                                    String.format("%.5f, %.5f", recent.lat, recent.lon),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = textSecondary
                                )
                            }
                        }
                    }
                }
            }

            // ── Saved locations (presets + custom bookmarks) ───────────────────
            val presets = listOf(
                Triple("HOME",  Icons.Default.Home,            "Home"),
                Triple("WORK",  Icons.Default.Business,        "Work"),
                Triple("STORE", Icons.Default.Store,           "Store"),
                Triple("GAS",   Icons.Default.LocalGasStation, "Gas")
            )
            val customLocations = savedLocations.filter { it.type == "CUSTOM" }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                presets.forEach { (type, icon, label) ->
                    val saved = savedLocations.firstOrNull { it.type == type }
                    Surface(
                        modifier = Modifier
                            .size(44.dp)
                            .combinedClickable(
                                onClick = {
                                    if (saved != null) {
                                        selectedDest = LatLon(saved.lat, saved.lon)
                                        addressQuery = saved.name
                                        geocoderResults = emptyList()
                                    } else {
                                        if (selectedDest != null) {
                                            showSavePresetDialog = type
                                        } else {
                                            scope.launch { snackbarHostState.showSnackbar("Search for a location first") }
                                        }
                                    }
                                },
                                onLongClick = { if (saved != null) deletePresetType = type }
                            ),
                        shape = RoundedCornerShape(50),
                        color = cardBg,
                        tonalElevation = 2.dp
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(icon, contentDescription = label,
                                tint = if (saved != null) accent else textSecondary)
                        }
                    }
                }

                // Bookmark button
                Surface(
                    modifier = Modifier
                        .size(44.dp)
                        .clickable {
                            if (selectedDest != null) {
                                bookmarkNameInput = addressQuery
                                showBookmarkDialog = true
                            } else {
                                scope.launch { snackbarHostState.showSnackbar("Search for a location first") }
                            }
                        },
                    shape = RoundedCornerShape(50),
                    color = cardBg,
                    tonalElevation = 2.dp
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(Icons.Default.BookmarkAdd, contentDescription = "Save bookmark", tint = accent)
                    }
                }
            }

            // Custom bookmarks list
            if (customLocations.isNotEmpty()) {
                Text("Bookmarks", style = MaterialTheme.typography.labelMedium, color = textSecondary)
                LazyColumn(modifier = Modifier.heightIn(max = 120.dp)) {
                    items(customLocations, key = { it.id }) { loc ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clickable {
                                    selectedDest = LatLon(loc.lat, loc.lon)
                                    addressQuery = loc.name
                                    geocoderResults = emptyList()
                                },
                            colors = CardDefaults.cardColors(containerColor = cardBg)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    loc.name,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = textPrimary
                                )
                                IconButton(
                                    onClick = { viewModel.deleteLocation(loc.id) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Delete bookmark",
                                        modifier = Modifier.size(18.dp),
                                        tint = textSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Dialogs ────────────────────────────────────────────────────────

            showSavePresetDialog?.let { type ->
                val label = presets.firstOrNull { it.first == type }?.third ?: type
                AlertDialog(
                    onDismissRequest = { showSavePresetDialog = null },
                    title = { Text("Save as $label?") },
                    text = { Text("Save \"${addressQuery.ifBlank { "current location" }}\" as $label?") },
                    confirmButton = {
                        TextButton(onClick = {
                            selectedDest?.let { dest ->
                                viewModel.saveLocation(addressQuery.ifBlank { label }, dest.lat, dest.lon, type)
                            }
                            showSavePresetDialog = null
                        }) { Text("Save") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSavePresetDialog = null }) { Text("Cancel") }
                    }
                )
            }

            if (showBookmarkDialog) {
                AlertDialog(
                    onDismissRequest = { showBookmarkDialog = false },
                    title = { Text("Save Bookmark") },
                    text = {
                        OutlinedTextField(
                            value = bookmarkNameInput,
                            onValueChange = { bookmarkNameInput = it },
                            label = { Text("Name") },
                            singleLine = true
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val name = bookmarkNameInput.trim().ifBlank { addressQuery }
                            selectedDest?.let { dest ->
                                viewModel.saveLocation(name, dest.lat, dest.lon, "CUSTOM")
                            }
                            showBookmarkDialog = false
                        }) { Text("Save") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showBookmarkDialog = false }) { Text("Cancel") }
                    }
                )
            }

            deletePresetType?.let { type ->
                val label = presets.firstOrNull { it.first == type }?.third ?: type
                val saved = savedLocations.firstOrNull { it.type == type }
                AlertDialog(
                    onDismissRequest = { deletePresetType = null },
                    title = { Text("Delete $label?") },
                    text = { Text("Remove \"${saved?.name ?: label}\" from saved locations?") },
                    confirmButton = {
                        TextButton(onClick = {
                            saved?.let { viewModel.deleteLocation(it.id) }
                            deletePresetType = null
                        }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { deletePresetType = null }) { Text("Cancel") }
                    }
                )
            }

            deleteRecentSearch?.let { recent ->
                AlertDialog(
                    onDismissRequest = { deleteRecentSearch = null },
                    title = { Text("Delete recent?") },
                    text = { Text("Remove \"${recent.name}\" from recent searches?") },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.removeRecentSearch(recent.name, recent.lat, recent.lon)
                            deleteRecentSearch = null
                        }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { deleteRecentSearch = null }) { Text("Cancel") }
                    }
                )
            }

            // ── Snackbar host ──────────────────────────────────────────────────
            SnackbarHost(hostState = snackbarHostState)

            // ── Coordinates fallback ───────────────────────────────────────────
            if (showLatLonFallback || offlineMode) {
                Text("Enter coordinates manually:", style = MaterialTheme.typography.labelMedium, color = textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = destLat,
                        onValueChange = { destLat = it; parseError = null },
                        label = { Text("Latitude", color = textSecondary) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accent,
                            unfocusedBorderColor = textSecondary,
                            focusedLabelColor = accent,
                            unfocusedLabelColor = textSecondary,
                            focusedTextColor = textPrimary,
                            unfocusedTextColor = textPrimary,
                            cursorColor = accent
                        )
                    )
                    OutlinedTextField(
                        value = destLon,
                        onValueChange = { destLon = it; parseError = null },
                        label = { Text("Longitude", color = textSecondary) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accent,
                            unfocusedBorderColor = textSecondary,
                            focusedLabelColor = accent,
                            unfocusedLabelColor = textSecondary,
                            focusedTextColor = textPrimary,
                            unfocusedTextColor = textPrimary,
                            cursorColor = accent
                        )
                    )
                }
            }

            // Car only - foot profile removed from graph

            if (!canRoute) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        "No routing available - enable online mode or download a routing graph in Settings.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            (parseError ?: errorMessage)?.let { msg ->
                Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            // ── Route Preference toggle ────────────────────────────────────────
            Text("Route type:", style = MaterialTheme.typography.labelMedium, color = textSecondary)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                RoutePreference.values().forEach { pref ->
                    val label = when (pref) {
                        RoutePreference.FASTEST           -> "⚡ Fastest"
                        RoutePreference.SHORTEST_DISTANCE -> "📏 Shortest"
                        RoutePreference.AVOID_ALPR        -> "📷🚫 Avoid Cameras"
                    }
                    val selected = pref == routePreference
                    Surface(
                        modifier = Modifier.clickable {
                            viewModel.setRoutePreference(pref)
                            if (selectedDest != null && !isLoading) {
                                val dest = selectedDest
                                if (dest != null) {
                                    viewModel.requestRoute(
                                        from = LatLon(currentLat, currentLon),
                                        to = dest,
                                        profile = profile,
                                        routePreference = pref
                                    )
                                }
                            }
                        },
                        shape = RoundedCornerShape(50),
                        color = if (selected) accent else cardBg,
                        tonalElevation = if (selected) 4.dp else 1.dp
                    ) {
                        Text(
                            label,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) Color(0xFF1A1A2E) else textSecondary,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // ── Route selection cards ──────────────────────────────────────────
            if (alternativeRoutes.isNotEmpty() && !isNavigating) {
                Text("Select route:", style = MaterialTheme.typography.labelMedium, color = textSecondary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    items(minOf(alternativeRoutes.size, 3)) { idx ->
                        val route = alternativeRoutes[idx]
                        val isSelected = activeRouteResult?.points == route.points
                        val distMi = route.distanceMeters / 1609.34
                        val durationMin = route.durationSeconds / 60L
                        val isAvoidAlpr = routePreference == RoutePreference.AVOID_ALPR && idx == 0
                        val isShortest = routePreference == RoutePreference.SHORTEST_DISTANCE && idx == 0
                        val cardLabel = when {
                            isAvoidAlpr -> "📷🚫 Avoid Cameras"
                            isShortest  -> "📏 Shortest"
                            idx == 0    -> "⚡ Fastest"
                            else        -> "Alternative $idx"
                        }
                        Surface(
                            modifier = Modifier
                                .width(120.dp)
                                .clickable { viewModel.selectAlternativeRoute(idx) }
                                .then(
                                    if (isSelected)
                                        Modifier.border(2.dp, accent, RoundedCornerShape(12.dp))
                                    else Modifier
                                ),
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) accent.copy(alpha = 0.15f) else cardBg,
                            tonalElevation = if (isSelected) 4.dp else 1.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    cardLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) accent else textSecondary
                                )
                                Text(
                                    "%.1f mi".format(distMi),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = textPrimary
                                )
                                Text(
                                    "$durationMin min",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textSecondary
                                )
                            }
                        }
                    }
                }
            }

            // ── Cancel / Route buttons ─────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, accent),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)
                ) { Text("Cancel") }
                Button(
                    onClick = {
                        val dest = selectedDest ?: run {
                            if (showLatLonFallback || offlineMode) {
                                val lat = destLat.trim().toDoubleOrNull()
                                val lon = destLon.trim().toDoubleOrNull()
                                if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                                    parseError = "Enter valid latitude (-90..90) and longitude (-180..180)"
                                    return@Button
                                }
                                LatLon(lat, lon)
                            } else {
                                parseError = "Search for an address first"
                                return@Button
                            }
                        }
                        viewModel.requestRoute(
                            from = LatLon(currentLat, currentLon),
                            to = dest,
                            profile = profile,
                            routePreference = routePreference
                        )
                    },
                    enabled = !isLoading && canRoute,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = Color(0xFF1A1A2E),
                        disabledContainerColor = accent.copy(alpha = 0.4f),
                        disabledContentColor = Color(0xFF1A1A2E).copy(alpha = 0.5f)
                    )
                ) {
                    if (isLoading) {
                        Text("...", color = Color(0xFF1A1A2E), fontWeight = FontWeight.Bold)
                    } else {
                        Text("Route", fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (routePoints != null && !isNavigating) {
                Button(
                    onClick = { viewModel.startNavigation(); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF43A047))
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start Navigation", fontWeight = FontWeight.Bold)
                }
            }

        } // Column
        } // Surface
    } // Dialog
}

// ── Active Navigation HUD ─────────────────────────────────────────────────────

/**
 * Navigation HUD shown at the top and bottom of the screen when navigating.
 * Overlays the map; does not interfere with ALPR/Flock/tracker overlays.
 */
@Composable
fun NavigationHud(
    viewModel: NavigationViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val isNavigating by viewModel.isNavigating.collectAsStateWithLifecycle()
    val currentInstruction by viewModel.currentInstruction.collectAsStateWithLifecycle()
    val distanceToNext by viewModel.distanceToNext.collectAsStateWithLifecycle()
    val routeResult by viewModel.routeResult.collectAsStateWithLifecycle()

    if (!isNavigating) return

    Column(modifier = modifier.fillMaxWidth()) {
        // ── Top banner: current instruction ───────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1565C0).copy(alpha = 0.93f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Turn arrow
            Text(
                text = turnArrow(currentInstruction?.sign ?: 0),
                fontSize = 32.sp,
                color = Color.White
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = currentInstruction?.text ?: "Follow the route",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge
                )
                if (distanceToNext > 0) {
                    Text(
                        text = "In ${formatDistance(distanceToNext)}",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // ── Bottom bar: total distance + ETA ──────────────────────────────────
        routeResult?.let { result ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D47A1).copy(alpha = 0.88f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Total: ${formatDistance(result.distanceMeters)}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "ETA: ${formatDuration(result.durationSeconds)}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(
                    onClick = { viewModel.stopNavigation() },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF9A9A))
                ) {
                    Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ── Snackbar helper ───────────────────────────────────────────────────────────

/**
 * Show routing-unavailable snackbar if graph is not loaded.
 * Call this once in a LaunchedEffect keyed on routingAvailable.
 */
@Composable
fun RoutingUnavailableSnackbar(
    snackbarHostState: SnackbarHostState,
    viewModel: NavigationViewModel = hiltViewModel()
) {
    val routingAvailable by viewModel.routingAvailable.collectAsStateWithLifecycle()
    var snackbarShown by remember { mutableStateOf(false) }

    LaunchedEffect(routingAvailable) {
        if (!routingAvailable && !snackbarShown) {
            snackbarShown = true
            snackbarHostState.showSnackbar(
                message = "Routing data not loaded - see Settings for setup instructions",
                duration = SnackbarDuration.Long
            )
        }
    }
}

// ── Distance helper ───────────────────────────────────────────────────────

/** Haversine distance in meters between two lat/lon pairs. Used to geo-rank search results. */
private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2).let { it * it }
    return 2 * r * kotlin.math.asin(kotlin.math.sqrt(a))
}

// ── Geocoder helpers ──────────────────────────────────────────────────────────

private fun buildAddressLine(addr: Address, fallback: String): String = buildString {
    if (!addr.thoroughfare.isNullOrBlank()) append(addr.thoroughfare)
    if (!addr.locality.isNullOrBlank()) { if (isNotEmpty()) append(", "); append(addr.locality) }
    if (!addr.adminArea.isNullOrBlank()) { if (isNotEmpty()) append(", "); append(addr.adminArea) }
    if (isEmpty()) append(addr.getAddressLine(0) ?: fallback)
}

// ── Formatters ────────────────────────────────────────────────────────────────

@androidx.annotation.VisibleForTesting
internal fun formatDistance(meters: Double): String {
    val feet = meters * 3.28084
    val miles = meters / 1609.344
    return when {
        miles >= 0.1 -> if (miles < 10) String.format("%.1f mi", miles) else "${miles.roundToInt()} mi"
        else -> "${feet.roundToInt()} ft"
    }
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0  -> "${h}h ${m}m"
        m > 0  -> "${m} min"
        else   -> "<1 min"
    }
}
