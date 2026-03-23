package com.aegisnav.app.tracker

/**
 * Identifies the commercial tracker product type detected from BLE manufacturer data.
 *
 * [riskWeight] is used by [BeaconHistoryManager] for enhanced risk scoring (3.6/3.7):
 *   AirTag=3.0, SmartTag=2.5, Tile=2.0, Apple FindMy=2.0, generic types=1.0–1.5.
 */
enum class TrackerType {
    /** Apple AirTag — company 0x004C, subtype byte 0x12 */
    AIR_TAG,
    /** Apple Find My Network accessory — company 0x004C, subtype byte 0x07 */
    APPLE_FIND_MY,
    /** Samsung SmartTag — company 0x0075 */
    SAMSUNG_SMART_TAG,
    /** Tile — company 0x01DA */
    TILE,
    /** Chipolo — company 0x0278 or service UUID 0xFE24 */
    CHIPOLO,
    /** PebbleBee — company 0x0177 */
    PEBBLE_BEE,
    /** Google Find My Device — company 0x00E0 */
    GOOGLE_FIND_MY,
    /** Unknown / unclassified tracker */
    UNKNOWN;

    /** Per-type risk weight for enhanced scoring (Feature 3.7). */
    val riskWeight: Float
        get() = when (this) {
            AIR_TAG           -> 3.0f
            SAMSUNG_SMART_TAG -> 2.5f
            TILE              -> 2.0f
            APPLE_FIND_MY     -> 2.0f
            CHIPOLO           -> 1.5f
            PEBBLE_BEE        -> 1.5f
            GOOGLE_FIND_MY    -> 1.5f
            UNKNOWN           -> 1.0f
        }

    /** Human-readable display name. */
    val displayName: String
        get() = when (this) {
            AIR_TAG           -> "AirTag"
            APPLE_FIND_MY     -> "Apple Find My"
            SAMSUNG_SMART_TAG -> "Samsung SmartTag"
            TILE              -> "Tile"
            CHIPOLO           -> "Chipolo"
            PEBBLE_BEE        -> "PebbleBee"
            GOOGLE_FIND_MY    -> "Google Find My"
            UNKNOWN           -> "Unknown Tracker"
        }
}
