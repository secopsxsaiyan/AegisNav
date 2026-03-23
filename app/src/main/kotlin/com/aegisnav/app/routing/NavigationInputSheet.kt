package com.aegisnav.app.routing

import android.location.Geocoder
import android.location.Address
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// ── Navigation Input Bottom Sheet ─────────────────────────────────────────────

/**
 * Bottom sheet for entering a destination.
 *
 * Simplified to only handle:
 * - Destination input (search bar)
 * - Recent searches list
 * - Saved locations / presets / bookmarks
 *
 * When a destination is selected: dismiss the sheet first, THEN trigger
 * calculateAllRoutes() via the ViewModel (non-blocking, off UI thread).
 * Route preference selector and Start Navigation buttons have been removed;
 * those are now in RouteSelectionOverlay.
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
    val canRoute by viewModel.canRoute.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val offlineMode by viewModel.offlineMode.collectAsStateWithLifecycle()
    val waypoints by viewModel.waypoints.collectAsStateWithLifecycle()
    val routeDestination by viewModel.routeDestinationState.collectAsStateWithLifecycle()

    // Debug: log routing state on every recomposition
    LaunchedEffect(canRoute, offlineMode) {
        com.aegisnav.app.util.AppLog.i("NavigationUI", "canRoute=$canRoute offlineMode=$offlineMode")
    }
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    val recentRoutes by viewModel.recentRoutes.collectAsStateWithLifecycle()
    val savedLocations by viewModel.savedLocations.collectAsStateWithLifecycle()
    val savedRoutes by viewModel.savedRoutes.collectAsStateWithLifecycle()

    // IME padding — static read to avoid imePadding() crash
    val imeBottom = WindowInsets.ime.getBottom(density)

    var addressQuery by remember { mutableStateOf("") }
    var geocoderResults by remember { mutableStateOf<List<GeocoderResult>>(emptyList()) }
    var geocoderError by remember { mutableStateOf<String?>(null) }
    var showLatLonFallback by remember(offlineMode) { mutableStateOf(offlineMode) }
    var isGeocoding by remember { mutableStateOf(false) }
    val activeStateCode = remember { viewModel.getActiveStateCode() }
    val offlineGeocoderAvailable = remember(activeStateCode) { viewModel.isOfflineGeocoderAvailable(activeStateCode) }
    var selectedDest by remember { mutableStateOf<LatLon?>(null) }
    var destLat by remember { mutableStateOf("") }
    var destLon by remember { mutableStateOf("") }
    var parseError by remember { mutableStateOf<String?>(null) }
    // Pending search result — shown with "Navigate Here" / "Add as Stop" choices
    var pendingResult by remember { mutableStateOf<GeocoderResult?>(null) }

    // Dialog/snackbar state for saved locations
    val snackbarHostState = remember { SnackbarHostState() }
    var showSavePresetDialog by remember { mutableStateOf<String?>(null) }
    var showDeletePresetDialog by remember { mutableStateOf<String?>(null) }
    var showBookmarkDialog by remember { mutableStateOf(false) }
    var bookmarkNameInput by remember { mutableStateOf("") }
    var deleteCustomId by remember { mutableStateOf<Int?>(null) }
    var deletePresetType by remember { mutableStateOf<String?>(null) }
    var deleteRecentSearch by remember { mutableStateOf<RecentSearch?>(null) }
    var deleteRecentRoute by remember { mutableStateOf<RecentRoute?>(null) }
    var showRoutesDialog by remember { mutableStateOf(false) }
    var showSaveRouteDialog by remember { mutableStateOf(false) }
    var saveRouteNameInput by remember { mutableStateOf("") }
    var deleteSavedRouteId by remember { mutableStateOf<Int?>(null) }

    // Helper to dismiss sheet and then calculate all routes
    fun dismissAndCalculate(dest: LatLon, name: String) {
        viewModel.addRecentSearch(name, dest.lat, dest.lon)
        onDismiss() // dismiss sheet first (avoids blocking UI thread)
        // calculateAllRoutes launches coroutines internally, safe to call after dismiss
        viewModel.calculateAllRoutes(
            from = LatLon(currentLat, currentLon),
            to = dest
        )
    }

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
                val offline = viewModel.searchOffline(query, activeStateCode, currentLat, currentLon)
                offline
                    .sortedBy { r -> haversineDistance(currentLat, currentLon, r.lat, r.lon) }
                    .take(3)
                    .map { r -> GeocoderResult(r.displayName, r.lat, r.lon) }
            } else emptyList()

            val recentMatches = recentSearches.filter { it.name.contains(query, ignoreCase = true) }
                .take(3).map { recent -> GeocoderResult(recent.name, recent.lat, recent.lon) }

            val onlineResults = if (!offlineMode && android.location.Geocoder.isPresent()) {
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

            // ── Stops list (waypoints) ─────────────────────────────────────────
            if (waypoints.isNotEmpty()) {
                Text("Stops (${waypoints.size}):", style = MaterialTheme.typography.labelMedium, color = textSecondary)
                LazyColumn(modifier = Modifier.heightIn(max = 120.dp)) {
                    items(waypoints.size) { index ->
                        val wp = waypoints[index]
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            colors = CardDefaults.cardColors(containerColor = cardBg)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Numbered circle
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .background(Color(0xFF9C27B0), shape = RoundedCornerShape(50))
                                        .padding(2.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "${index + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Stop ${index + 1}: ${String.format("%.5f, %.5f", wp.lat, wp.lon)}",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textPrimary
                                )
                                IconButton(
                                    onClick = {
                                        viewModel.removeWaypoint(index)
                                        // Recalculate if destination set
                                        val dest = routeDestination
                                        if (dest != null) {
                                            viewModel.calculateAllRoutes(
                                                from = LatLon(currentLat, currentLon),
                                                to = dest
                                            )
                                            onDismiss()
                                        }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove stop",
                                        modifier = Modifier.size(16.dp),
                                        tint = textSecondary
                                    )
                                }
                            }
                        }
                    }
                }
                // "Add Stop" hint
                Text(
                    "💡 Long-press on map to add more stops",
                    style = MaterialTheme.typography.labelSmall,
                    color = textSecondary
                )
            }

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
            if (geocoderResults.isNotEmpty() && pendingResult == null) {
                Text("Select destination:", style = MaterialTheme.typography.labelMedium, color = textSecondary)
                LazyColumn(modifier = Modifier.heightIn(max = 140.dp)) {
                    items(geocoderResults, key = { "${it.lat},${it.lon}" }) { result ->
                        val isSelected = selectedDest?.lat == result.lat && selectedDest?.lon == result.lon
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clickable {
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                    // Show "Navigate Here" / "Add as Stop" choice
                                    pendingResult = result
                                    selectedDest = LatLon(result.lat, result.lon)
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

            // ── Pending result: Navigate Here / Add as Stop ────────────────
            pendingResult?.let { pending ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.1f)),
                    border = BorderStroke(1.dp, accent),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Place,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                pending.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = textPrimary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { pendingResult = null; selectedDest = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = textSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                        // Context-aware single action button
                        val hasDestination = routeDestination != null
                        Button(
                            onClick = {
                                if (hasDestination) {
                                    // Add as stop — destination already set
                                    val stopLatLon = LatLon(pending.lat, pending.lon)
                                    viewModel.addWaypoint(stopLatLon)
                                    viewModel.addRecentSearch(pending.displayName, pending.lat, pending.lon)
                                    pendingResult = null
                                    selectedDest = null
                                    geocoderResults = emptyList()
                                    addressQuery = ""
                                    val dest = routeDestination
                                    if (dest != null) {
                                        viewModel.calculateAllRoutes(
                                            from = LatLon(currentLat, currentLon),
                                            to = dest
                                        )
                                    }
                                    onDismiss()
                                } else {
                                    // No destination yet — navigate here
                                    pendingResult = null
                                    dismissAndCalculate(LatLon(pending.lat, pending.lon), pending.displayName)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = accent)
                        ) {
                            Text(
                                if (hasDestination) "Add Stop" else "Navigate Here",
                                color = Color(0xFF1A1A2E),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
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
                                        keyboardController?.hide()
                                        focusManager.clearFocus()
                                        if (routeDestination != null) {
                                            // Adding a stop — destination already set
                                            viewModel.addWaypoint(LatLon(recent.lat, recent.lon))
                                            viewModel.addRecentSearch(recent.name, recent.lat, recent.lon)
                                            val dest = routeDestination!!
                                            viewModel.calculateAllRoutes(LatLon(currentLat, currentLon), dest)
                                            onDismiss()
                                        } else {
                                            viewModel.clearWaypoints()
                                            dismissAndCalculate(LatLon(recent.lat, recent.lon), recent.name)
                                        }
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

            // ── Recent Routes ──────────────────────────────────────────────────
            if (recentRoutes.isNotEmpty()) {
                Text("Recent Routes", style = MaterialTheme.typography.labelMedium, color = textSecondary)
                LazyColumn(modifier = Modifier.heightIn(max = 140.dp)) {
                    items(recentRoutes, key = { it.destination.name + it.stops.size }) { route ->
                        val stopNames = (route.stops.map { it.name } + route.destination.name)
                        val label = stopNames.joinToString(" → ")
                        val countLabel = if (route.stops.isNotEmpty()) " (${route.stops.size + 1} stops)" else ""
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .combinedClickable(
                                    onClick = {
                                        keyboardController?.hide()
                                        focusManager.clearFocus()
                                        viewModel.loadRecentRoute(route, LatLon(currentLat, currentLon))
                                        onDismiss()
                                    },
                                    onLongClick = { deleteRecentRoute = route }
                                ),
                            colors = CardDefaults.cardColors(containerColor = cardBg)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "🗺️ $label$countLabel",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textPrimary
                                )
                                IconButton(
                                    onClick = { deleteRecentRoute = route },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove recent route",
                                        modifier = Modifier.size(16.dp),
                                        tint = textSecondary
                                    )
                                }
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

            Box(modifier = Modifier.fillMaxWidth()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    contentPadding = PaddingValues(end = 8.dp)
                ) {
                    items(presets) { (type, icon, label) ->
                        val saved = savedLocations.firstOrNull { it.type == type }
                        Surface(
                            modifier = Modifier
                                .size(44.dp)
                                .combinedClickable(
                                    onClick = {
                                        if (saved != null) {
                                            if (routeDestination != null) {
                                                viewModel.addWaypoint(LatLon(saved.lat, saved.lon))
                                                viewModel.addRecentSearch(saved.name, saved.lat, saved.lon)
                                                val dest = routeDestination!!
                                                viewModel.calculateAllRoutes(LatLon(currentLat, currentLon), dest)
                                                onDismiss()
                                            } else {
                                                viewModel.clearWaypoints()
                                                dismissAndCalculate(LatLon(saved.lat, saved.lon), saved.name)
                                            }
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
                    item {
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

                    // Routes button
                    item {
                        Surface(
                            modifier = Modifier
                                .size(44.dp)
                                .clickable { showRoutesDialog = true },
                            shape = RoundedCornerShape(50),
                            color = cardBg,
                            tonalElevation = 2.dp
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Text("🗺️", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    // Custom bookmarks inline
                    items(customLocations, key = { it.id }) { loc ->
                        Surface(
                            modifier = Modifier
                                .size(44.dp)
                                .combinedClickable(
                                    onClick = {
                                        viewModel.clearWaypoints()
                                        dismissAndCalculate(LatLon(loc.lat, loc.lon), loc.name)
                                    },
                                    onLongClick = { viewModel.deleteLocation(loc.id) }
                                ),
                            shape = RoundedCornerShape(50),
                            color = cardBg,
                            tonalElevation = 2.dp
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Text(loc.name.take(2), style = MaterialTheme.typography.labelSmall, color = accent)
                            }
                        }
                    }
                }

                // Right fade edge indicator
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(24.dp)
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(Color.Transparent, MaterialTheme.colorScheme.surface)
                            )
                        )
                )
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

            deleteRecentRoute?.let { route ->
                AlertDialog(
                    onDismissRequest = { deleteRecentRoute = null },
                    title = { Text("Remove recent route?") },
                    text = { Text("Remove this route from recent routes?") },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.removeRecentRoute(route)
                            deleteRecentRoute = null
                        }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { deleteRecentRoute = null }) { Text("Cancel") }
                    }
                )
            }

            // Routes dialog — saved routes from Room
            if (showRoutesDialog) {
                AlertDialog(
                    onDismissRequest = { showRoutesDialog = false },
                    title = { Text("🗺️ Saved Routes") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Save current route option
                            if (waypoints.isNotEmpty() && routeDestination != null) {
                                Button(
                                    onClick = {
                                        showRoutesDialog = false
                                        saveRouteNameInput = ""
                                        showSaveRouteDialog = true
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Save Current Route", color = Color(0xFF1A1A2E), fontSize = 12.sp)
                                }
                            }
                            if (savedRoutes.isEmpty()) {
                                Text(
                                    "No saved routes yet. Plan a multi-stop route and save it here.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textSecondary
                                )
                            } else {
                                LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                                    items(savedRoutes, key = { it.id }) { route ->
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 2.dp)
                                                .combinedClickable(
                                                    onClick = {
                                                        showRoutesDialog = false
                                                        viewModel.loadSavedRoute(route, LatLon(currentLat, currentLon))
                                                        onDismiss()
                                                    },
                                                    onLongClick = { deleteSavedRouteId = route.id }
                                                ),
                                            colors = CardDefaults.cardColors(containerColor = cardBg)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        route.name,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontWeight = FontWeight.Medium,
                                                        color = textPrimary
                                                    )
                                                    // Parse stop count
                                                    val stopCount = runCatching {
                                                        org.json.JSONArray(route.stopsJson).length()
                                                    }.getOrElse { 0 }
                                                    Text(
                                                        "$stopCount stops",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = textSecondary
                                                    )
                                                }
                                                IconButton(
                                                    onClick = { deleteSavedRouteId = route.id },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Close,
                                                        contentDescription = "Delete route",
                                                        modifier = Modifier.size(16.dp),
                                                        tint = textSecondary
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { showRoutesDialog = false }) { Text("Close") }
                    }
                )
            }

            if (showSaveRouteDialog) {
                AlertDialog(
                    onDismissRequest = { showSaveRouteDialog = false },
                    title = { Text("Save Route") },
                    text = {
                        OutlinedTextField(
                            value = saveRouteNameInput,
                            onValueChange = { saveRouteNameInput = it },
                            label = { Text("Route name") },
                            singleLine = true
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val name = saveRouteNameInput.trim().ifBlank { "Route" }
                            val dest = routeDestination
                            if (dest != null) {
                                val stops = waypoints.map { wp ->
                                    RecentSearch("%.5f, %.5f".format(wp.lat, wp.lon), wp.lat, wp.lon)
                                }
                                val destSearch = RecentSearch("%.5f, %.5f".format(dest.lat, dest.lon), dest.lat, dest.lon)
                                viewModel.saveRouteToRoom(name, stops, destSearch)
                            }
                            showSaveRouteDialog = false
                        }) { Text("Save") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSaveRouteDialog = false }) { Text("Cancel") }
                    }
                )
            }

            deleteSavedRouteId?.let { routeId ->
                AlertDialog(
                    onDismissRequest = { deleteSavedRouteId = null },
                    title = { Text("Delete route?") },
                    text = { Text("Remove this saved route?") },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.deleteSavedRoute(routeId)
                            deleteSavedRouteId = null
                        }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { deleteSavedRouteId = null }) { Text("Cancel") }
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
                // Manual coordinate submit button
                Button(
                    onClick = {
                        val lat = destLat.trim().toDoubleOrNull()
                        val lon = destLon.trim().toDoubleOrNull()
                        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                            parseError = "Enter valid latitude (-90..90) and longitude (-180..180)"
                        } else {
                            val coordLabel = "%.5f, %.5f".format(lat, lon)
                            dismissAndCalculate(LatLon(lat, lon), coordLabel)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Text("Go to Coordinates", fontWeight = FontWeight.Bold, color = Color(0xFF1A1A2E))
                }
            }

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

            // ── Share destination button (when dest is selected) ───────────────
            if (selectedDest != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val shareLabel = buildString {
                        val d = selectedDest
                        if (d != null) {
                            append("%.5f, %.5f".format(d.lat, d.lon))
                            if (addressQuery.isNotBlank()) append(" — $addressQuery")
                        }
                    }
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Destination", shareLabel))
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy destination to clipboard",
                            tint = accent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        "Copy",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent
                    )
                }
            }

            // ── Cancel button ──────────────────────────────────────────────────
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, accent),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)
            ) { Text("Cancel") }

        } // Column
        } // Surface
    } // Dialog
}
