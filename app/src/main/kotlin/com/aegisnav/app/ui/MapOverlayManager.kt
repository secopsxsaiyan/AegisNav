package com.aegisnav.app.ui

import com.aegisnav.app.util.AppLog
import com.aegisnav.app.data.model.ALPRBlocklist
import com.aegisnav.app.data.model.Report
import com.aegisnav.app.p2p.IncomingReport
import com.aegisnav.app.routing.LatLon
import com.aegisnav.app.routing.RouteResult
import org.maplibre.android.maps.Style
import org.maplibre.geojson.LineString
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

// ── GeoJSON source/layer constants ────────────────────────────────────────────

internal const val SOURCE_USER_LOCATION = "source-user-location"
internal const val LAYER_USER_CAR       = "layer-user-car"

internal const val SOURCE_ALPR      = "source-alpr"
internal const val SOURCE_REPORTS   = "source-reports"
internal const val SOURCE_INCOMING  = "source-incoming"
internal const val SOURCE_ROUTE     = "route-source"
internal const val SOURCE_FLOCK     = "source-flock"
internal const val SOURCE_REDLIGHT  = "source-redlight"
internal const val SOURCE_SPEED     = "source-speed"
internal const val LAYER_ALPR       = "layer-alpr"
internal const val LAYER_REPORTS    = "layer-reports"
internal const val LAYER_INCOMING   = "layer-incoming"
internal const val LAYER_FLOCK      = "layer-flock"
internal const val LAYER_REDLIGHT   = "layer-redlight"
internal const val LAYER_SPEED      = "layer-speed"

// Phase 5 Task 2: Alternative route dashed polylines (also exported from MapLibreMapView)
internal const val SOURCE_ALT_ROUTES  = com.aegisnav.app.ALTERNATIVE_ROUTE_SOURCE
internal const val LAYER_ALT_ROUTES   = com.aegisnav.app.ALTERNATIVE_ROUTE_LAYER

// Multi-route display sources/layers (one per route option)
internal const val SOURCE_ROUTE_OPTION_PREFIX = "route-option-source-"
internal const val LAYER_ROUTE_OPTION_PREFIX  = "route-option-layer-"
internal const val LAYER_ROUTE_LABEL_PREFIX   = "route-label-layer-"
internal const val SOURCE_ROUTE_LABEL_PREFIX  = "route-label-source-"
internal const val MAX_ROUTE_OPTIONS = 3

// Turn maneuver markers on the route
internal const val SOURCE_TURN_MARKERS = "source-turn-markers"
internal const val LAYER_TURN_MARKERS  = "layer-turn-markers"

// Waypoint (stop) markers
internal const val SOURCE_WAYPOINTS    = "source-waypoints"
internal const val LAYER_WAYPOINTS     = "layer-waypoints"
internal const val LAYER_WAYPOINT_LABELS = "layer-waypoint-labels"

// Confirmed police sightings (user-verified via popup)
internal const val SOURCE_CONFIRMED_POLICE = "source-confirmed-police"
internal const val LAYER_CONFIRMED_POLICE  = "layer-confirmed-police"

// ── Bitmap helpers ─────────────────────────────────────────────────────────────

internal fun createEmojiBitmap(emoji: String, sizeDp: Int = 32, density: Float = 3f): android.graphics.Bitmap {
    val size = (sizeDp * density).toInt().coerceAtLeast(1)
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size * 0.75f
        textAlign = android.graphics.Paint.Align.CENTER
    }
    canvas.drawText(emoji, size / 2f, size * 0.8f, paint)
    return bitmap
}

/** White filled navigation arrow pointing north (0°). MapLibre rotates via iconRotate. */
private fun createWhiteCarArrow(sizeDp: Int = 40, density: Float = 3f): android.graphics.Bitmap {
    val size = (sizeDp * density).toInt().coerceAtLeast(8)
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val cx = size / 2f
    val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.FILL
    }
    val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.DKGRAY
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = size * 0.07f
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    // Arrow: tip at top, notched base - classic nav arrow pointing north
    val path = android.graphics.Path().apply {
        moveTo(cx,              size * 0.08f)   // tip
        lineTo(cx + size*0.32f, size * 0.88f)   // bottom-right
        lineTo(cx,              size * 0.65f)   // notch
        lineTo(cx - size*0.32f, size * 0.88f)   // bottom-left
        close()
    }
    canvas.drawPath(path, fillPaint)
    canvas.drawPath(path, strokePaint)
    return bmp
}

/**
 * Draws a small turn-marker circle: semi-transparent cyan dot for waypoints.
 * Used for maneuver points on the active route.
 */
private fun createTurnMarkerBitmap(sizeDp: Int = 18, density: Float = 3f): android.graphics.Bitmap {
    val size = (sizeDp * density).toInt().coerceAtLeast(8)
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val cx = size / 2f
    val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#00d4ff")
        style = android.graphics.Paint.Style.FILL
        alpha = 200
    }
    canvas.drawCircle(cx, cx, cx * 0.75f, fillPaint)
    val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = size * 0.12f
    }
    canvas.drawCircle(cx, cx, cx * 0.75f, strokePaint)
    return bmp
}

/** Draws a speed limit road sign: white circle, red border, "SPD" text. */
private fun createSpeedLimitSignBitmap(sizeDp: Int = 28, density: Float = 3f): android.graphics.Bitmap {
    val size = (sizeDp * density).toInt().coerceAtLeast(1)
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val cx = size / 2f; val cy = size / 2f; val r = size / 2f - 1f
    val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE; style = android.graphics.Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, r, fillPaint)
    val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.RED; style = android.graphics.Paint.Style.STROKE
        strokeWidth = size * 0.12f
    }
    canvas.drawCircle(cx, cy, r - size * 0.06f, borderPaint)
    val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        textSize = size * 0.30f; textAlign = android.graphics.Paint.Align.CENTER
        isFakeBoldText = true
    }
    canvas.drawText("SPD", cx, cy + textPaint.textSize * 0.38f, textPaint)
    return bitmap
}

/**
 * Draws a numbered stop marker: solid purple circle with white number text.
 * Used for waypoint (stop) markers on the route preview.
 */
internal fun createWaypointBitmap(number: Int, sizeDp: Int = 28, density: Float = 3f): android.graphics.Bitmap {
    val size = (sizeDp * density).toInt().coerceAtLeast(8)
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val cx = size / 2f
    val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#9C27B0")  // purple
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawCircle(cx, cx, cx * 0.88f, fillPaint)
    val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = size * 0.10f
    }
    canvas.drawCircle(cx, cx, cx * 0.88f, strokePaint)
    val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = size * 0.45f
        textAlign = android.graphics.Paint.Align.CENTER
        isFakeBoldText = true
    }
    canvas.drawText(number.toString(), cx, cx - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    return bmp
}

// ── Map source/layer initialization ───────────────────────────────────────────

internal fun initMapSources(style: Style, density: Float = 3f) {
    // Only add if not already present
    if (style.getSource(SOURCE_ALPR) == null) {
        style.addSource(GeoJsonSource(SOURCE_ALPR, FeatureCollection.fromFeatures(emptyList())))
        // ALPR DB cameras: color-coded by source. minZoom=10 for zoom-gating.
        val alprLayer = CircleLayer(LAYER_ALPR, SOURCE_ALPR).withProperties(
            PropertyFactory.circleColor("#E53935"),   // all ALPR cameras = red
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleOpacity(0.9f),
            PropertyFactory.circleStrokeColor("#ffffff"),
            PropertyFactory.circleStrokeWidth(1f)
        )
        alprLayer.setMinZoom(10f)
        style.addLayer(alprLayer)
        // Register bitmap icons
        if (style.getImage("icon-alpr") == null) {
            style.addImage("icon-alpr", createEmojiBitmap("📷", 24, density))
        }
        if (style.getImage("icon-car") == null) {
            style.addImage("icon-car", createWhiteCarArrow(40, density))
        }
        if (style.getImage("icon-report") == null) {
            style.addImage("icon-POLICE",       createEmojiBitmap("👮", 24, density))
            style.addImage("icon-ALPR",         createEmojiBitmap("📷", 24, density))
            style.addImage("icon-SURVEILLANCE", createEmojiBitmap("🎥", 24, density))
            style.addImage("icon-ACCIDENT",     createEmojiBitmap("🚨", 24, density))
            style.addImage("icon-HAZARD",       createEmojiBitmap("⚠️", 24, density))
            style.addImage("icon-WEATHER",      createEmojiBitmap("🌧️", 24, density))
            style.addImage("icon-CONSTRUCTION", createEmojiBitmap("🚧", 24, density))
            style.addImage("icon-ROAD_CLOSURE", createEmojiBitmap("🛑", 24, density))
            style.addImage("icon-default",      createEmojiBitmap("📍", 24, density))
        }
        val alprIconLayer = SymbolLayer("layer-alpr-icons", SOURCE_ALPR).withProperties(
            PropertyFactory.iconImage("icon-alpr"),
            PropertyFactory.iconSize(0.8f),
            PropertyFactory.iconAllowOverlap(false),
            PropertyFactory.iconIgnorePlacement(false),
            PropertyFactory.iconOffset(arrayOf(0f, -1.5f))
        )
        alprIconLayer.setMinZoom(12f)
        style.addLayer(alprIconLayer)
    }
    if (style.getSource(SOURCE_REPORTS) == null) {
        style.addSource(GeoJsonSource(SOURCE_REPORTS, FeatureCollection.fromFeatures(emptyList())))
        // Reports: color by type. Police=blue, ALPR=orange, Surveillance=grey, others=orange.
        val reportsLayer = CircleLayer(LAYER_REPORTS, SOURCE_REPORTS).withProperties(
            PropertyFactory.circleColor(
                Expression.match(
                    Expression.get("report_type"),
                    Expression.literal("#FF9800"),   // default orange
                    Expression.stop("POLICE",      "#1565C0"),  // blue
                    Expression.stop("ALPR",        "#E53935"),  // red
                    Expression.stop("SURVEILLANCE","#FF9800"),  // orange
                    Expression.stop("ACCIDENT",    "#D84315"),
                    Expression.stop("HAZARD",      "#F57F17"),
                    Expression.stop("WEATHER",     "#0277BD"),
                    Expression.stop("CONSTRUCTION","#4E342E"),
                    Expression.stop("ROAD_CLOSURE","#C62828")
                )
            ),
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleStrokeColor("#fff"),
            PropertyFactory.circleStrokeWidth(1.5f)
        )
        reportsLayer.setMinZoom(12f)
        style.addLayer(reportsLayer)
        val reportIconLayer = SymbolLayer("layer-reports-icons", SOURCE_REPORTS).withProperties(
            PropertyFactory.iconImage(Expression.get("report_icon_id")),
            PropertyFactory.iconSize(0.9f),
            PropertyFactory.iconAllowOverlap(false),
            PropertyFactory.iconIgnorePlacement(false),
            PropertyFactory.iconOffset(arrayOf(0f, -1.5f))
        )
        reportIconLayer.setMinZoom(12f)
        style.addLayer(reportIconLayer)
    }
    if (style.getSource(SOURCE_FLOCK) == null) {
        style.addSource(GeoJsonSource(SOURCE_FLOCK, FeatureCollection.fromFeatures(emptyList())))
        // Flock-detected cameras: red, minZoom=12
        val flockLayer = CircleLayer(LAYER_FLOCK, SOURCE_FLOCK).withProperties(
            PropertyFactory.circleColor("#E53935"),
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleOpacity(0.9f),
            PropertyFactory.circleStrokeColor("#ffffff"),
            PropertyFactory.circleStrokeWidth(2f)
        )
        flockLayer.setMinZoom(12f)
        style.addLayer(flockLayer)
    }
    if (style.getSource(SOURCE_REDLIGHT) == null) {
        style.addSource(GeoJsonSource(SOURCE_REDLIGHT, FeatureCollection.fromFeatures(emptyList())))
        val redlightLayer = CircleLayer(LAYER_REDLIGHT, SOURCE_REDLIGHT).withProperties(
            PropertyFactory.circleColor("#FF6F00"),   // amber
            PropertyFactory.circleRadius(6f),
            PropertyFactory.circleOpacity(0.9f),
            PropertyFactory.circleStrokeColor("#ffffff"),
            PropertyFactory.circleStrokeWidth(1f)
        )
        redlightLayer.setMinZoom(10f)
        style.addLayer(redlightLayer)
        if (style.getImage("icon-redlight") == null) {
            style.addImage("icon-redlight", createEmojiBitmap("🚦", 24, density))
        }
        val redlightIconLayer = SymbolLayer("layer-redlight-icons", SOURCE_REDLIGHT).withProperties(
            PropertyFactory.iconImage("icon-redlight"),
            PropertyFactory.iconSize(0.8f),
            PropertyFactory.iconAllowOverlap(false),
            PropertyFactory.iconIgnorePlacement(false),
            PropertyFactory.iconOffset(arrayOf(0f, -1.5f))
        )
        redlightIconLayer.setMinZoom(12f)
        style.addLayer(redlightIconLayer)
    }
    if (style.getSource(SOURCE_SPEED) == null) {
        style.addSource(GeoJsonSource(SOURCE_SPEED, FeatureCollection.fromFeatures(emptyList())))
        val speedLayer = CircleLayer(LAYER_SPEED, SOURCE_SPEED).withProperties(
            PropertyFactory.circleColor("#1565C0"),   // blue
            PropertyFactory.circleRadius(6f),
            PropertyFactory.circleOpacity(0.9f),
            PropertyFactory.circleStrokeColor("#ffffff"),
            PropertyFactory.circleStrokeWidth(1f)
        )
        speedLayer.setMinZoom(10f)
        style.addLayer(speedLayer)
        if (style.getImage("icon-speed") == null) {
            style.addImage("icon-speed", createSpeedLimitSignBitmap(24, density))
        }
        val speedIconLayer = SymbolLayer("layer-speed-icons", SOURCE_SPEED).withProperties(
            PropertyFactory.iconImage("icon-speed"),
            PropertyFactory.iconSize(0.8f),
            PropertyFactory.iconAllowOverlap(false),
            PropertyFactory.iconIgnorePlacement(false),
            PropertyFactory.iconOffset(arrayOf(0f, -1.5f))
        )
        speedIconLayer.setMinZoom(12f)
        style.addLayer(speedIconLayer)
    }
    if (style.getSource(SOURCE_INCOMING) == null) {
        style.addSource(GeoJsonSource(SOURCE_INCOMING, FeatureCollection.fromFeatures(emptyList())))
        style.addLayer(CircleLayer(LAYER_INCOMING, SOURCE_INCOMING).withProperties(
            PropertyFactory.circleColor("#4caf50"),
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleOpacity(0.8f)
        ))
    }

    // Phase 5 Task 2: Alternative route dashed polylines (behind primary route)
    if (style.getSource(SOURCE_ALT_ROUTES) == null) {
        style.addSource(GeoJsonSource(SOURCE_ALT_ROUTES, FeatureCollection.fromFeatures(emptyList())))
        style.addLayer(LineLayer(LAYER_ALT_ROUTES, SOURCE_ALT_ROUTES).withProperties(
            PropertyFactory.lineColor("#888888"),
            PropertyFactory.lineWidth(3f),
            PropertyFactory.lineOpacity(0.7f),
            PropertyFactory.lineDasharray(arrayOf(4f, 3f))
        ))
    }
    if (style.getSource(SOURCE_ROUTE) == null) {
        style.addSource(GeoJsonSource(SOURCE_ROUTE, FeatureCollection.fromFeatures(emptyList())))
        if (style.getLayer("route-line") == null) {
            style.addLayer(LineLayer("route-line", SOURCE_ROUTE).withProperties(
                PropertyFactory.lineColor("#00d4ff"),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineOpacity(0.9f)
            ))
        }
    }
    // Confirmed police sightings - rendered below user location arrow
    if (style.getSource(SOURCE_CONFIRMED_POLICE) == null) {
        style.addSource(GeoJsonSource(SOURCE_CONFIRMED_POLICE, FeatureCollection.fromFeatures(emptyList())))
        if (style.getImage("icon-confirmed-police") == null) {
            style.addImage("icon-confirmed-police", createEmojiBitmap("👮", 32, density))
        }
        val confirmedPoliceLayer = SymbolLayer(LAYER_CONFIRMED_POLICE, SOURCE_CONFIRMED_POLICE).withProperties(
            PropertyFactory.iconImage("icon-confirmed-police"),
            PropertyFactory.iconSize(1.0f),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true)
        )
        style.addLayer(confirmedPoliceLayer)
    }
    // Turn maneuver markers on active route (below user location)
    if (style.getSource(SOURCE_TURN_MARKERS) == null) {
        style.addSource(GeoJsonSource(SOURCE_TURN_MARKERS, FeatureCollection.fromFeatures(emptyList())))
        if (style.getImage("icon-turn-marker") == null) {
            style.addImage("icon-turn-marker", createTurnMarkerBitmap(18, density))
        }
        style.addLayer(
            SymbolLayer(LAYER_TURN_MARKERS, SOURCE_TURN_MARKERS).withProperties(
                PropertyFactory.iconImage("icon-turn-marker"),
                PropertyFactory.iconSize(1.0f),
                PropertyFactory.iconAllowOverlap(false),
                PropertyFactory.iconIgnorePlacement(false)
            )
        )
    }
    // Waypoint (stop) markers - rendered above routes, below user location
    if (style.getSource(SOURCE_WAYPOINTS) == null) {
        style.addSource(GeoJsonSource(SOURCE_WAYPOINTS, FeatureCollection.fromFeatures(emptyList())))
        // Pre-add icons for numbers 1..9 (enough for any reasonable number of stops)
        for (n in 1..9) {
            val iconId = "icon-waypoint-$n"
            if (style.getImage(iconId) == null) {
                style.addImage(iconId, createWaypointBitmap(n, 28, density))
            }
        }
        val waypointLayer = SymbolLayer(LAYER_WAYPOINTS, SOURCE_WAYPOINTS).withProperties(
            PropertyFactory.iconImage(Expression.get("icon_id")),
            PropertyFactory.iconSize(1.0f),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true)
        )
        try { style.addLayer(waypointLayer) } catch (e: Exception) {
            AppLog.w("Waypoints", "Failed to add waypoint layer: ${e.message}")
        }
    }

    // User location - rendered on top of all other layers
    if (style.getSource(SOURCE_USER_LOCATION) == null) {
        style.addSource(GeoJsonSource(SOURCE_USER_LOCATION, FeatureCollection.fromFeatures(emptyList())))
        // White arrow car - all zoom levels, rotates via "bearing" feature property
        val carLayer = SymbolLayer(LAYER_USER_CAR, SOURCE_USER_LOCATION).withProperties(
            PropertyFactory.iconImage("icon-car"),
            PropertyFactory.iconSize(1.0f),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconRotate(Expression.get("bearing")),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP)
        )
        style.addLayer(carLayer)
    }
}

internal fun updateUserLocationOverlay(
    style: Style, lat: Double, lon: Double,
    bearing: Float = 0f, @Suppress("UNUSED_PARAMETER") courseUp: Boolean = false
) {
    try {
        val source = style.getSource(SOURCE_USER_LOCATION) as? GeoJsonSource ?: return
        val feature = Feature.fromGeometry(Point.fromLngLat(lon, lat))
        // iconRotate is in geographic (map) space with ICON_ROTATION_ALIGNMENT_MAP.
        // Always set it to the raw GPS bearing so the arrow tip points in the real-world
        // direction of travel.  The camera bearing (set elsewhere) handles rotating the
        // map viewport for course-up mode - that is independent of icon orientation.
        feature.addNumberProperty("bearing", bearing)
        source.setGeoJson(FeatureCollection.fromFeatures(listOf(feature)))
    } catch (e: Exception) {
        AppLog.w("UserLocation", "Failed to update user location overlay: ${e.message}")
    }
}

internal fun updateMapOverlays(
    style: Style,
    reports: List<Report>,
    alprList: List<ALPRBlocklist>,
    incomingReports: List<IncomingReport>,
    redLightCameras: List<com.aegisnav.app.data.model.RedLightCamera> = emptyList(),
    speedCameras: List<com.aegisnav.app.data.model.SpeedCamera> = emptyList(),
    bounds: org.maplibre.android.geometry.LatLngBounds? = null
) {
    try {
        val filteredAlpr = if (bounds != null) {
            alprList.filter { cam ->
                cam.lat in bounds.latitudeSouth..bounds.latitudeNorth &&
                cam.lon in bounds.longitudeWest..bounds.longitudeEast
            }.take(200)
        } else {
            alprList.take(200)
        }
        (style.getSource(SOURCE_ALPR) as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeatures(filteredAlpr.map { cam ->
                Feature.fromGeometry(Point.fromLngLat(cam.lon, cam.lat)).also { f ->
                    val mapSource = if (cam.source.contains(",")) "MULTI" else cam.source
                    f.addStringProperty("alpr_source", mapSource)
                    f.addStringProperty("alpr_desc", cam.desc)
                    f.addNumberProperty("alpr_id", cam.id)
                }
            })
        )
        (style.getSource(SOURCE_REPORTS) as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeatures(reports.map { r ->
                Feature.fromGeometry(Point.fromLngLat(r.longitude, r.latitude)).also { f ->
                    f.addStringProperty("report_type", r.type)
                    f.addStringProperty("report_icon_id",
                        if (r.type in listOf("POLICE","ALPR","SURVEILLANCE","ACCIDENT","HAZARD","WEATHER","CONSTRUCTION","ROAD_CLOSURE"))
                            "icon-${r.type}" else "icon-default"
                    )
                }
            })
        )
        (style.getSource(SOURCE_INCOMING) as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeatures(incomingReports.map { r ->
                Feature.fromGeometry(Point.fromLngLat(r.lon, r.lat))
            })
        )

        // Red light cameras
        val filteredRlc = if (bounds != null) {
            redLightCameras.filter { cam ->
                cam.lat in bounds.latitudeSouth..bounds.latitudeNorth &&
                cam.lon in bounds.longitudeWest..bounds.longitudeEast
            }.take(300)
        } else {
            redLightCameras.take(300)
        }
        (style.getSource(SOURCE_REDLIGHT) as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeatures(filteredRlc.map { cam ->
                Feature.fromGeometry(Point.fromLngLat(cam.lon, cam.lat)).also { f ->
                    f.addStringProperty("rlc_desc", cam.desc)
                    f.addStringProperty("rlc_source", cam.source)
                }
            })
        )

        // Speed cameras
        val filteredSpeed = if (bounds != null) {
            speedCameras.filter { cam ->
                cam.lat in bounds.latitudeSouth..bounds.latitudeNorth &&
                cam.lon in bounds.longitudeWest..bounds.longitudeEast
            }.take(300)
        } else {
            speedCameras.take(300)
        }
        (style.getSource(SOURCE_SPEED) as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeatures(filteredSpeed.map { cam ->
                Feature.fromGeometry(Point.fromLngLat(cam.lon, cam.lat)).also { f ->
                    f.addStringProperty("spd_desc", cam.desc)
                    f.addStringProperty("spd_source", cam.source)
                }
            })
        )
    } catch (e: Exception) {
        AppLog.w("MapOverlays", "Failed to update overlays: ${e.message}")
    }
}

internal fun updateConfirmedPoliceOverlay(
    style: Style,
    sightings: List<com.aegisnav.app.police.PoliceSighting>,
    officerUnits: List<com.aegisnav.app.police.OfficerUnit> = emptyList()
) {
    try {
        val oneHourAgo = System.currentTimeMillis() - 3_600_000L
        val unitMap = officerUnits.associateBy { it.unitId }
        val confirmed = sightings.filter { sighting ->
            if (sighting.userVerdict != "confirmed") return@filter false
            val unitId = sighting.officerUnitId ?: return@filter true
            val unit = unitMap[unitId] ?: return@filter true
            // Only show if this is a locked-confirmed unit whose confirm timestamp is fresh (within 1 hr)
            if (unit.verdictLocked && unit.lockedVerdict == "confirmed") {
                unit.lastConfirmTimestamp > 0L && unit.lastConfirmTimestamp >= oneHourAgo
            } else {
                true
            }
        }
        (style.getSource(SOURCE_CONFIRMED_POLICE) as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeatures(confirmed.map { s ->
                Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat)).also { f ->
                    f.addStringProperty("police_id", s.id)
                    f.addStringProperty("category", s.detectionCategory ?: "UNKNOWN")
                }
            })
        )
    } catch (e: Exception) {
        AppLog.w("ConfirmedPolice", "Failed to update overlay: ${e.message}")
    }
}

internal fun updateFlockOverlay(style: Style, sightings: List<com.aegisnav.app.flock.FlockSighting>) {
    try {
        (style.getSource(SOURCE_FLOCK) as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeatures(sightings.map { s ->
                Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat)).also { f ->
                    f.addStringProperty("flock_id", s.id)
                    f.addNumberProperty("confidence", s.confidence)
                    f.addNumberProperty("timestamp", s.timestamp)
                }
            })
        )
    } catch (e: Exception) {
        AppLog.w("FlockOverlay", "Failed to update flock overlay: ${e.message}")
    }
}

/**
 * Update the route polyline layer on the map.
 * The route layer is intentionally below the marker layers (ALPR, reports, incoming)
 * so it never occludes surveillance overlays.
 */
internal fun updateRoutePolyline(
    style: Style,
    points: List<LatLon>?
) {
    try {
        val source = style.getSource(SOURCE_ROUTE) as? GeoJsonSource ?: return
        if (points.isNullOrEmpty()) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return
        }
        val linePoints = points.map { Point.fromLngLat(it.lon, it.lat) }
        val feature = Feature.fromGeometry(LineString.fromLngLats(linePoints))
        source.setGeoJson(FeatureCollection.fromFeatures(listOf(feature)))
    } catch (e: Exception) {
        AppLog.w("RoutePolyline", "Failed to update route polyline: ${e.message}")
    }
}

/**
 * Update turn maneuver marker symbols on the map.
 * Shows a cyan dot at each instruction waypoint (except the final destination and the start).
 * Pass null or empty list to clear all markers.
 */
internal fun updateTurnMarkersOverlay(
    style: Style,
    instructions: List<com.aegisnav.app.routing.TurnInstruction>?
) {
    try {
        val source = style.getSource(SOURCE_TURN_MARKERS) as? GeoJsonSource ?: return
        if (instructions.isNullOrEmpty()) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return
        }
        // Skip first (start) and last (destination) instructions — only show intermediate turns
        val markers = instructions.drop(1).dropLast(1).map { instr ->
            Feature.fromGeometry(Point.fromLngLat(instr.point.lon, instr.point.lat)).also { f ->
                f.addStringProperty("instr_text", instr.text)
                f.addNumberProperty("sign", instr.sign)
            }
        }
        source.setGeoJson(FeatureCollection.fromFeatures(markers))
    } catch (e: Exception) {
        AppLog.w("TurnMarkers", "Failed to update turn markers: ${e.message}")
    }
}

/**
 * Phase 5 Task 2: Render alternative routes as gray dashed lines behind the primary route.
 * Skips the primary route (first in list matching [primaryPoints]) to avoid duplicating it.
 */
internal fun updateAlternativeRoutes(
    style: Style,
    alternatives: List<RouteResult>,
    primaryPoints: List<LatLon>?
) {
    try {
        val source = style.getSource(SOURCE_ALT_ROUTES) as? GeoJsonSource ?: return
        if (alternatives.isEmpty()) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return
        }
        // Exclude the route that matches primaryPoints (already drawn in blue)
        val altFeatures = alternatives
            .filter { it.points != primaryPoints }
            .mapNotNull { route ->
                if (route.points.size < 2) return@mapNotNull null
                val linePoints = route.points.map { Point.fromLngLat(it.lon, it.lat) }
                Feature.fromGeometry(LineString.fromLngLats(linePoints))
            }
        source.setGeoJson(FeatureCollection.fromFeatures(altFeatures))
    } catch (e: Exception) {
        AppLog.w("AltRoutes", "Failed to update alternative routes: ${e.message}")
    }
}

/**
 * Initialize GeoJSON sources and line layers for multi-route display.
 * Adds up to [MAX_ROUTE_OPTIONS] route option sources/layers plus label symbol layers.
 * Must be called after [initMapSources] so ordering is correct (route options sit
 * below the primary route layer).
 */
internal fun initRouteOptionSources(style: Style) {
    for (i in 0 until MAX_ROUTE_OPTIONS) {
        val srcId = SOURCE_ROUTE_OPTION_PREFIX + i
        val layerId = LAYER_ROUTE_OPTION_PREFIX + i
        val labelSrcId = SOURCE_ROUTE_LABEL_PREFIX + i
        val labelLayerId = LAYER_ROUTE_LABEL_PREFIX + i

        if (style.getSource(srcId) == null) {
            style.addSource(GeoJsonSource(srcId, FeatureCollection.fromFeatures(emptyList())))
        }
        if (style.getLayer(layerId) == null) {
            val layer = LineLayer(layerId, srcId).withProperties(
                PropertyFactory.lineColor("#888888"),
                PropertyFactory.lineWidth(4f),
                PropertyFactory.lineOpacity(0.6f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
            // Insert below the primary route-line layer so primary stays on top
            try {
                if (style.getLayer("route-line") != null) {
                    style.addLayerBelow(layer, "route-line")
                } else {
                    style.addLayer(layer)
                }
            } catch (e: Exception) {
                try { style.addLayer(layer) } catch (e2: Exception) {
                    AppLog.w("RouteOptions", "Failed to add layer $layerId: ${e2.message}")
                }
            }
        }

        // Label source/layer for route midpoint annotation
        if (style.getSource(labelSrcId) == null) {
            style.addSource(GeoJsonSource(labelSrcId, FeatureCollection.fromFeatures(emptyList())))
        }
        if (style.getLayer(labelLayerId) == null) {
            val labelLayer = SymbolLayer(labelLayerId, labelSrcId).withProperties(
                PropertyFactory.textField(Expression.get("label")),
                PropertyFactory.textSize(12f),
                PropertyFactory.textColor(Expression.get("text_color")),
                PropertyFactory.textHaloColor("#000000"),
                PropertyFactory.textHaloWidth(1.5f),
                PropertyFactory.textAllowOverlap(false),
                PropertyFactory.textIgnorePlacement(false),
                PropertyFactory.textFont(arrayOf("Open Sans Bold", "Arial Unicode MS Regular")),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_CENTER)
            )
            try { style.addLayer(labelLayer) } catch (e: Exception) {
                AppLog.w("RouteOptions", "Failed to add label layer $labelLayerId: ${e.message}")
            }
        }
    }
}

/**
 * Update waypoint (stop) markers on the map.
 * Each waypoint gets a numbered purple circle icon.
 * Pass empty list to clear all waypoint markers.
 */
internal fun updateWaypointMarkers(
    style: Style,
    waypoints: List<com.aegisnav.app.routing.LatLon>
) {
    try {
        val source = style.getSource(SOURCE_WAYPOINTS) as? GeoJsonSource ?: return
        if (waypoints.isEmpty()) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return
        }
        val features = waypoints.mapIndexed { index, waypoint ->
            val number = (index + 1).coerceIn(1, 9)
            Feature.fromGeometry(Point.fromLngLat(waypoint.lon, waypoint.lat)).also { f ->
                f.addStringProperty("icon_id", "icon-waypoint-$number")
                f.addNumberProperty("stop_number", number)
            }
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    } catch (e: Exception) {
        AppLog.w("Waypoints", "Failed to update waypoint markers: ${e.message}")
    }
}

/**
 * Draw all route options on the map simultaneously.
 *
 * - Selected route: drawn thicker (6dp) and fully opaque.
 * - Other routes: drawn thinner (3.5dp) and dimmer (0.5 opacity).
 * - Each route gets a color derived from [RouteOption.color].
 * - A text label at the route midpoint shows "Label • Xm • Y min".
 * - The primary route-source (SOURCE_ROUTE) is also updated to the selected route's points
 *   so existing HUD/turn-marker logic stays correct.
 * - When [routeOptions] is empty, all route-option layers are cleared.
 */
internal fun updateRouteOptions(
    style: Style,
    routeOptions: List<com.aegisnav.app.routing.NavigationViewModel.RouteOption>,
    selectedIndex: Int
) {
    try {
        if (routeOptions.isEmpty()) {
            // Clear all route option layers
            for (i in 0 until MAX_ROUTE_OPTIONS) {
                val srcId = SOURCE_ROUTE_OPTION_PREFIX + i
                val labelSrcId = SOURCE_ROUTE_LABEL_PREFIX + i
                (style.getSource(srcId) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
                (style.getSource(labelSrcId) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            }
            return
        }

        for (i in 0 until MAX_ROUTE_OPTIONS) {
            val srcId = SOURCE_ROUTE_OPTION_PREFIX + i
            val layerId = LAYER_ROUTE_OPTION_PREFIX + i
            val labelSrcId = SOURCE_ROUTE_LABEL_PREFIX + i

            val option = routeOptions.getOrNull(i)
            val routeSource = style.getSource(srcId) as? GeoJsonSource
            val labelSource = style.getSource(labelSrcId) as? GeoJsonSource
            val routeLayer = style.getLayer(layerId)

            if (option == null || routeSource == null) {
                // Clear unused slots
                routeSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
                labelSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
                continue
            }

            val isSelected = (i == selectedIndex)
            val points = option.result.points
            if (points.size < 2) {
                routeSource.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
                labelSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
                continue
            }

            // Convert color int to hex string (ARGB → RGB)
            val colorInt = option.color
            val r = (colorInt shr 16) and 0xFF
            val g = (colorInt shr 8) and 0xFF
            val b = colorInt and 0xFF
            val colorHex = "#%02X%02X%02X".format(r, g, b)

            // Route feature with routeIndex property for tap detection
            val linePoints = points.map { pt -> Point.fromLngLat(pt.lon, pt.lat) }
            val feature = Feature.fromGeometry(LineString.fromLngLats(linePoints))
            feature.addNumberProperty("routeIndex", i)
            feature.addStringProperty("routeLabel", option.label)
            routeSource.setGeoJson(FeatureCollection.fromFeatures(listOf(feature)))

            // Update layer properties (color, width, opacity)
            routeLayer?.let { layer ->
                layer.setProperties(
                    PropertyFactory.lineColor(colorHex),
                    PropertyFactory.lineWidth(if (isSelected) 7f else 4f),
                    PropertyFactory.lineOpacity(if (isSelected) 0.95f else 0.5f)
                )
            }

            // Label at midpoint
            val midIdx = points.size / 2
            val midPt = points[midIdx]
            val distMi = option.result.distanceMeters / 1609.34
            val durationMin = option.result.durationSeconds / 60L
            val labelText = "${option.label}\n${if (distMi >= 0.1) "%.1f mi".format(distMi) else "${(option.result.distanceMeters * 3.281).toInt()} ft"} • ${durationMin}m"
            val labelFeature = Feature.fromGeometry(Point.fromLngLat(midPt.lon, midPt.lat))
            labelFeature.addStringProperty("label", labelText)
            labelFeature.addStringProperty("text_color", if (isSelected) colorHex else "#AAAAAA")
            labelSource?.setGeoJson(FeatureCollection.fromFeatures(listOf(labelFeature)))
        }
    } catch (e: Exception) {
        AppLog.w("RouteOptions", "Failed to update route options: ${e.message}")
    }
}
