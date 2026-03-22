package com.aegisnav.app.routing

/**
 * Route preference selected by the user before starting navigation.
 *
 * FASTEST           – Default fastest route with no camera avoidance.
 * SHORTEST_DISTANCE – Shortest route by total distance (not time).
 * AVOID_ALPR        – Route avoids road segments within 100 m of ALPR surveillance cameras.
 *                     Implemented via GraphHopper block_area hints (offline) or waypoint
 *                     detour for OSRM (online).  Speed cameras and red-light cameras are
 *                     NOT avoided — this is ALPR-only per spec 1.6.
 */
enum class RoutePreference {
    FASTEST,
    SHORTEST_DISTANCE,
    AVOID_ALPR
}
