package com.aegisnav.app.ui

import com.aegisnav.app.data.model.ALPRBlocklist
import com.aegisnav.app.data.model.RedLightCamera
import com.aegisnav.app.data.model.SpeedCamera
import com.aegisnav.app.flock.FlockSighting
import org.maplibre.android.maps.MapLibreMap

/**
 * Registers the map single-tap listener that identifies tapped features and
 * routes them to the appropriate detail bottom-sheet by calling the provided
 * selection callbacks.
 *
 * Handles (in priority order):
 *  1. Flock Safety camera sightings ([LAYER_FLOCK])
 *  2. ALPR cameras ([LAYER_ALPR])
 *  3. Red-light cameras ([LAYER_REDLIGHT])
 *  4. Speed cameras ([LAYER_SPEED])
 *
 * @param map                     The live MapLibreMap instance.
 * @param flockSightings          Current list of Flock sightings.
 * @param alprList                Current ALPR camera blocklist.
 * @param redLightCameras         Current red-light camera list.
 * @param speedCameras            Current speed camera list.
 * @param onFlockSelected         Invoked with the matching [FlockSighting] when one is tapped.
 * @param onAlprSelected          Invoked with the matching [ALPRBlocklist] entry when one is tapped.
 * @param onRedLightSelected      Invoked with the matching [RedLightCamera] when one is tapped.
 * @param onSpeedCameraSelected   Invoked with the matching [SpeedCamera] when one is tapped.
 */
internal fun registerMapTapHandler(
    map: MapLibreMap,
    flockSightings: List<FlockSighting>,
    alprList: List<ALPRBlocklist>,
    redLightCameras: List<RedLightCamera>,
    speedCameras: List<SpeedCamera>,
    onFlockSelected: (FlockSighting) -> Unit,
    onAlprSelected: (ALPRBlocklist) -> Unit,
    onRedLightSelected: (RedLightCamera) -> Unit,
    onSpeedCameraSelected: (SpeedCamera) -> Unit,
) {
    map.addOnMapClickListener { latLng ->
        val screenPoint = map.projection.toScreenLocation(latLng)
        val ptF = android.graphics.PointF(screenPoint.x, screenPoint.y)

        // Check Flock layer first
        val flockFeatures = map.queryRenderedFeatures(ptF, LAYER_FLOCK)
        if (flockFeatures.isNotEmpty()) {
            val flockId = flockFeatures.firstOrNull()?.getStringProperty("flock_id")
            if (flockId != null) {
                val sighting = flockSightings.firstOrNull { it.id == flockId }
                if (sighting != null) {
                    onFlockSelected(sighting)
                    return@addOnMapClickListener true
                }
            }
        }

        val features = map.queryRenderedFeatures(ptF, LAYER_ALPR)
        val alprFeature = features.firstOrNull()
        if (alprFeature != null) {
            val alprId = alprFeature.getNumberProperty("alpr_id")?.toInt()
            if (alprId != null) {
                val cam = alprList.firstOrNull { it.id == alprId }
                if (cam != null) {
                    onAlprSelected(cam)
                    return@addOnMapClickListener true
                }
            }
        }

        // Red light camera tap
        val rlcFeatures = map.queryRenderedFeatures(ptF, LAYER_REDLIGHT)
        val rlcFeature = rlcFeatures.firstOrNull()
        if (rlcFeature != null) {
            val rlcDesc = rlcFeature.getStringProperty("rlc_desc") ?: ""
            val rlcSource = rlcFeature.getStringProperty("rlc_source") ?: "OSM"
            val rlcPoint = rlcFeature.geometry() as? org.maplibre.geojson.Point
            if (rlcPoint != null) {
                val cam = redLightCameras.firstOrNull {
                    Math.abs(it.lat - rlcPoint.latitude()) < 0.0001 &&
                    Math.abs(it.lon - rlcPoint.longitude()) < 0.0001
                } ?: RedLightCamera(
                    lat = rlcPoint.latitude(), lon = rlcPoint.longitude(),
                    desc = rlcDesc, source = rlcSource
                )
                onRedLightSelected(cam)
                return@addOnMapClickListener true
            }
        }

        // Speed camera tap
        val speedFeatures = map.queryRenderedFeatures(ptF, LAYER_SPEED)
        val speedFeature = speedFeatures.firstOrNull()
        if (speedFeature != null) {
            val spdDesc = speedFeature.getStringProperty("spd_desc") ?: ""
            val spdSource = speedFeature.getStringProperty("spd_source") ?: "OSM"
            val spdPoint = speedFeature.geometry() as? org.maplibre.geojson.Point
            if (spdPoint != null) {
                val cam = speedCameras.firstOrNull {
                    Math.abs(it.lat - spdPoint.latitude()) < 0.0001 &&
                    Math.abs(it.lon - spdPoint.longitude()) < 0.0001
                } ?: SpeedCamera(
                    lat = spdPoint.latitude(), lon = spdPoint.longitude(),
                    desc = spdDesc, source = spdSource
                )
                onSpeedCameraSelected(cam)
                return@addOnMapClickListener true
            }
        }

        false
    }
}
