package com.aegisnav.app.correlation

/**
 * Represents a physical device identity that may have used multiple MAC addresses
 * (due to randomization) or been linked across BLE and WiFi scan types.
 *
 * Created by MacCorrelationEngine when devices are grouped via:
 *  - MAC rotation detection (temporal + spatial + RSSI + mfgData fingerprint)
 *  - Global MAC leak detection (randomized → globally-assigned transition)
 *  - WiFi↔BLE cross-correlation (fed from CrossCorrelationEngine)
 */
data class MacIdentityGroup(
    /** Stable UUID for this device group, assigned at creation time. */
    val groupId: String,

    /**
     * Canonical virtual identifier to use in place of raw MAC addresses.
     * - If a globally-unique (non-randomized) MAC has been observed: the global MAC.
     * - Otherwise: the groupId string (a UUID, stable across rotations).
     * Consumers (TrackerDetectionEngine, PoliceDetector) use this to correlate sightings
     * from the same physical device regardless of MAC rotation.
     */
    val virtualMac: String,

    /**
     * All MAC addresses (BLE and WiFi) ever attributed to this physical device.
     * Includes both past and current randomized MACs, plus any leaked global MAC.
     */
    val knownMacs: Set<String>,

    /**
     * The globally-unique (OUI-registered, non-locally-assigned) MAC observed for
     * this device, if any. Non-null means this device has "leaked" its real identity
     * by transmitting with a globally-assigned MAC after previously using randomized ones.
     */
    val leakedGlobalMac: String?,

    /**
     * OUI manufacturer lookup result for the leaked global MAC. Null if no leak yet.
     * Used to build the user-facing alert: "Device revealed real MAC: XX:XX:XX (Axon Inc.)"
     */
    val leakedMacManufacturer: String?,

    /**
     * Short fingerprint of the BLE manufacturer-specific advertisement data.
     * Extracted as the first 8 hex characters (= first 4 bytes: company ID + 2 payload bytes).
     * Used to link randomized MAC rotations when other signals are ambiguous.
     */
    val mfgDataFingerprint: String?,

    /** Timestamp of the first sighting that created this group. */
    val firstSeen: Long,

    /** Timestamp of the most recent scan result processed for any MAC in this group. */
    val lastSeen: Long,

    /**
     * Whether the most recently observed MAC in this group is locally-assigned (randomized).
     * False once a leaked global MAC is detected.
     */
    val isCurrentlyRandomized: Boolean,

    /**
     * Count of distinct MAC rotation events detected for this device.
     * Incremented each time a new randomized MAC is linked to the group via rotation detection.
     */
    val rotationCount: Int,

    /** Timestamp of the most recent MAC rotation event. 0L if no rotation detected yet. */
    val lastRotationMs: Long,

    /**
     * WiFi BSSIDs confirmed as belonging to the same physical device via
     * temporal co-occurrence analysis in CrossCorrelationEngine.
     */
    val linkedWifiBssids: Set<String>,

    /**
     * BLE MACs confirmed as belonging to the same physical device via
     * temporal co-occurrence analysis in CrossCorrelationEngine.
     */
    val linkedBleMacs: Set<String>
)
