package com.aegisnav.app

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aegisnav.app.routing.LatLon
import com.aegisnav.app.routing.RouteResult
import com.aegisnav.app.signal.SignalTriangulator
import com.aegisnav.app.util.AppLog
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.json.JSONArray
import org.json.JSONObject

// ── Triangulation symbol layer ──────────────────────────────────────────────
private const val TRIANGULATION_SOURCE_ID = "triangulation-circles-source"
private const val TRIANGULATION_LAYER_ID  = "triangulation-circles-layer"
private const val TRIANGULATION_ICON_POLICE = "icon-triangulation-police"
private const val TRIANGULATION_ICON_ALPR   = "icon-triangulation-alpr"

// ── Alternative route layer (Task 2) ────────────────────────────────────────
/** Source ID for alternative (non-primary) route polylines. */
const val ALTERNATIVE_ROUTE_SOURCE = "alternative-route-source"
/** Layer ID for alternative route gray-dashed polylines. */
const val ALTERNATIVE_ROUTE_LAYER  = "alternative-route-layer"



// ── Bitmap helpers ────────────────────────────────────────────────────────────

/** Create a simple police-badge bitmap (blue circle with ✦ star) for the map icon. */
private fun createPoliceBitmap(density: Float = 3f): android.graphics.Bitmap {
    val size = (28 * density).toInt().coerceAtLeast(8)
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val cx = size / 2f

    // Blue filled circle
    val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#1565C0")
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawCircle(cx, cx, cx * 0.92f, fillPaint)

    // White star / badge shape via text
    val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textAlign = android.graphics.Paint.Align.CENTER
        textSize = size * 0.55f
    }
    val yOffset = (textPaint.descent() - textPaint.ascent()) / 2 - textPaint.descent()
    canvas.drawText("🚔", cx, cx + yOffset, textPaint)
    return bmp
}

/** Create a camera-icon bitmap (dark gray) for ALPR map icons. */
private fun createAlprBitmap(density: Float = 3f): android.graphics.Bitmap {
    val size = (28 * density).toInt().coerceAtLeast(8)
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val cx = size / 2f

    // Dark circle background
    val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#B71C1C")
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawCircle(cx, cx, cx * 0.92f, fillPaint)

    // Camera emoji
    val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textAlign = android.graphics.Paint.Align.CENTER
        textSize = size * 0.55f
    }
    val yOffset = (textPaint.descent() - textPaint.ascent()) / 2 - textPaint.descent()
    canvas.drawText("📷", cx, cx + yOffset, textPaint)
    return bmp
}

// ── GeoJSON builders ─────────────────────────────────────────────────────────

/**
 * Build GeoJSON for triangulation results — POLICE and ALPR devices only.
 * Each feature includes "category" and "icon" properties for SymbolLayer filtering.
 */
private fun triangulationGeoJson(
    results: Collection<SignalTriangulator.TriangulationResult>,
    dismissedMacs: Set<String> = emptySet()
): String {
    val features = JSONArray()
    for (r in results) {
        if (r.mac in dismissedMacs) continue
        if (r.deviceCategory != "POLICE" && r.deviceCategory != "ALPR") continue
        val feature = JSONObject().apply {
            put("type", "Feature")
            put("geometry", JSONObject().apply {
                put("type", "Point")
                put("coordinates", JSONArray().apply {
                    put(r.estimatedLon)
                    put(r.estimatedLat)
                })
            })
            put("properties", JSONObject().apply {
                put("mac", r.mac)
                put("radius", r.radiusMeters)
                put("gdop", r.gdop)
                put("category", r.deviceCategory ?: "")
                put("icon", if (r.deviceCategory == "POLICE") TRIANGULATION_ICON_POLICE else TRIANGULATION_ICON_ALPR)
            })
        }
        features.put(feature)
    }
    return JSONObject().apply {
        put("type", "FeatureCollection")
        put("features", features)
    }.toString()
}



/** Build a GeoJSON MultiLineString of alternative route polylines. */
private fun alternativeRoutesGeoJson(routes: List<RouteResult>): String {
    val features = JSONArray()
    routes.forEach { route ->
        if (route.points.size < 2) return@forEach
        val coords = JSONArray()
        route.points.forEach { pt ->
            coords.put(JSONArray().apply { put(pt.lon); put(pt.lat) })
        }
        val feature = JSONObject().apply {
            put("type", "Feature")
            put("geometry", JSONObject().apply {
                put("type", "LineString")
                put("coordinates", coords)
            })
            put("properties", JSONObject())
        }
        features.put(feature)
    }
    return JSONObject().apply {
        put("type", "FeatureCollection")
        put("features", features)
    }.toString()
}

@Composable
fun MapLibreMapView(
    modifier: Modifier = Modifier,
    isDark: Boolean = true,
    /** Reactive tile URI - pass from ViewModel so map updates after download/delete. */
    pmtilesUri: String? = null,
    /** Phase 2B (2.6): Triangulation results to render as POLICE/ALPR symbol icons. */
    triangulationResults: Collection<SignalTriangulator.TriangulationResult> = emptyList(),
    /** MACs the user has dismissed — excluded from the triangulation overlay. */
    dismissedTriangulationMacs: Set<String> = emptySet(),
    /** Phase 5 Task 2: Alternative routes to render as gray dashed polylines behind the primary. */
    alternativeRoutes: List<RouteResult> = emptyList(),

    onMapReady: (MapLibreMap, MapView) -> Unit = { _, _ -> },
    onThemeSwitch: ((MapLibreMap, Style) -> Unit)? = null,
    /**
     * Called whenever the MapLibre style finishes (re-)loading — including after the app
     * returns from background and the GL surface is recreated.  Use this to re-apply all
     * dynamic GeoJSON sources and layers so they are visible again after a resume.
     */
    onStyleReloaded: ((Style) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Build both style JSONs from templates (or fallbacks)
    val darkStyleJson: String = remember(pmtilesUri) {
        if (pmtilesUri != null) {
            val template = context.assets.open("style.json")
                .bufferedReader()
                .use { it.readText() }
            template.replace("__PMTILES_URL__", pmtilesUri)
        } else {
            """
            {
              "version": 8,
              "name": "AegisNav Fallback Dark",
              "sources": {},
              "layers": [
                {
                  "id": "background",
                  "type": "background",
                  "paint": { "background-color": "#1a1a2e" }
                }
              ]
            }
            """.trimIndent()
        }
    }

    val lightStyleJson: String = remember(pmtilesUri) {
        if (pmtilesUri != null) {
            val template = context.assets.open("style_light.json")
                .bufferedReader()
                .use { it.readText() }
            template.replace("__PMTILES_URL__", pmtilesUri)
        } else {
            """
            {
              "version": 8,
              "name": "AegisNav Fallback Light",
              "sources": {},
              "layers": [
                {
                  "id": "background",
                  "type": "background",
                  "paint": { "background-color": "#f8f5f0" }
                }
              ]
            }
            """.trimIndent()
        }
    }

    val mapView = remember { MapView(context) }

    // Track whether the first style has been fully loaded
    var mapInitialized by remember { mutableStateOf(false) }
    var mapLibreRef by remember { mutableStateOf<MapLibreMap?>(null) }

    // Device pixel density for bitmap creation
    val density = context.resources.displayMetrics.density

    // React to isDark or pmtilesUri changes after first load - swap style and re-init sources
    LaunchedEffect(isDark, mapInitialized, pmtilesUri) {
        if (!mapInitialized) return@LaunchedEffect
        val map = mapLibreRef ?: return@LaunchedEffect
        val newStyleJson = if (isDark) darkStyleJson else lightStyleJson
        map.setStyle(Style.Builder().fromJson(newStyleJson)) { newStyle ->
            onThemeSwitch?.invoke(map, newStyle)
        }
    }

    // Phase 2B / Task 4: Update triangulation icons when results change
    // Only POLICE and ALPR results are rendered; others are silently skipped.
    LaunchedEffect(triangulationResults, dismissedTriangulationMacs, mapInitialized) {
        if (!mapInitialized) return@LaunchedEffect
        val map = mapLibreRef ?: return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect
        val geoJson = triangulationGeoJson(triangulationResults, dismissedTriangulationMacs)

        try {
            val existing = style.getSource(TRIANGULATION_SOURCE_ID) as? GeoJsonSource
            if (existing != null) {
                existing.setGeoJson(geoJson)
            } else {
                // Add icon images on first setup — addImage is idempotent with same name
                try { style.addImage(TRIANGULATION_ICON_POLICE, createPoliceBitmap(density)) }
                    catch (e: Exception) { AppLog.w("MapLibreMapView", "Failed to add police icon: ${e.message}") }
                try { style.addImage(TRIANGULATION_ICON_ALPR, createAlprBitmap(density)) }
                    catch (e: Exception) { AppLog.w("MapLibreMapView", "Failed to add ALPR icon: ${e.message}") }
                style.addSource(GeoJsonSource(TRIANGULATION_SOURCE_ID, geoJson))
                style.addLayer(
                    SymbolLayer(TRIANGULATION_LAYER_ID, TRIANGULATION_SOURCE_ID).withProperties(
                        PropertyFactory.iconImage(Expression.get("icon")),
                        PropertyFactory.iconSize(1.2f),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true)
                    )
                )
            }
        } catch (e: Exception) {
            AppLog.w("MapLibreMapView", "Triangulation layer update failed (style not ready?): ${e.message}")
        }
    }

    // Task 2: Render alternative route polylines as gray dashed lines
    LaunchedEffect(alternativeRoutes, mapInitialized) {
        if (!mapInitialized) return@LaunchedEffect
        val map = mapLibreRef ?: return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect
        val geoJson = alternativeRoutesGeoJson(alternativeRoutes)

        try {
            val existing = style.getSource(ALTERNATIVE_ROUTE_SOURCE) as? GeoJsonSource
            if (existing != null) {
                existing.setGeoJson(geoJson)
            } else {
                style.addSource(GeoJsonSource(ALTERNATIVE_ROUTE_SOURCE, geoJson))
                style.addLayer(
                    LineLayer(ALTERNATIVE_ROUTE_LAYER, ALTERNATIVE_ROUTE_SOURCE).withProperties(
                        PropertyFactory.lineColor("#888888"),
                        PropertyFactory.lineWidth(3f),
                        PropertyFactory.lineOpacity(0.7f),
                        PropertyFactory.lineDasharray(arrayOf(4f, 3f))
                    )
                )
            }
        } catch (e: Exception) { AppLog.w("MapLibreMapView", "Alternative route layer update failed: ${e.message}") }
    }



    // Forward lifecycle events to MapView
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE  -> mapView.onCreate(Bundle())
                Lifecycle.Event.ON_START   -> mapView.onStart()
                Lifecycle.Event.ON_RESUME  -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE   -> mapView.onPause()
                Lifecycle.Event.ON_STOP    -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }

        val memCallbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) { mapView.onLowMemory() }
            override fun onLowMemory() { mapView.onLowMemory() }
            override fun onConfigurationChanged(newConfig: Configuration) {}
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        context.registerComponentCallbacks(memCallbacks)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.unregisterComponentCallbacks(memCallbacks)
            if (lifecycleOwner.lifecycle.currentState != Lifecycle.State.DESTROYED) {
                mapView.onDestroy()
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { _ ->
            mapView.also { mv ->
                // Re-apply all dynamic sources/layers whenever the style reloads.
                // This fires on first load AND every time the GL surface is recreated
                // (e.g. returning from background), ensuring the route polyline and all
                // other overlays are always present after a resume.
                mv.addOnDidFinishLoadingStyleListener {
                    mapLibreRef?.getStyle { style ->
                        onStyleReloaded?.invoke(style)
                    }
                }
                mv.getMapAsync { map ->
                    mapLibreRef = map
                    val initialStyle = if (isDark) darkStyleJson else lightStyleJson
                    map.setStyle(
                        Style.Builder().fromJson(initialStyle)
                    ) { _ ->
                        mapInitialized = true
                        onMapReady(map, mv)
                    }
                }
            }
        }
    )
}
