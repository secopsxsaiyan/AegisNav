package com.aegisnav.app.routing

/**
 * A single turn instruction during navigation.
 *
 * [sign] values follow GraphHopper conventions:
 *   -3 = sharp left, -2 = left, -1 = slight left, 0 = straight,
 *    1 = slight right, 2 = right, 3 = sharp right,
 *    4 = finish, 5 = via reached, 6 = roundabout
 */
data class TurnInstruction(
    val text: String,            // Human-readable: "Turn left onto Main St"
    val distanceMeters: Double,  // Distance until this instruction
    val sign: Int,               // GraphHopper sign constant
    val streetName: String,
    val point: LatLon,           // Location of this instruction waypoint
    // Phase 1.1 — highway pre-announcement fields
    val ref: String? = null,             // Route reference (e.g. "I-95", "SR-826")
    val exitNumber: String? = null,      // Exit number for motorway_link maneuvers
    val isHighwayManeuver: Boolean = false,  // true for ramp/exit maneuvers
    val durationMillis: Long = 0L        // Segment duration in ms (from GraphHopper instr.time)
)
