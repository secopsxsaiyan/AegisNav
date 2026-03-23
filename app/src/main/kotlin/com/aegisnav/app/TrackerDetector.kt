package com.aegisnav.app

object TrackerDetector {
    /**
     * Returns true if the Apple manufacturer-specific data matches the Find My Network format.
     *
     * Find My Network payload (AirTag, AirPods case, Beats, etc.):
     *   mfgData[0] == 0x12  (type = Find My)
     *   mfgData[1] == 0x19  (length = 25 bytes)
     *
     * Note: The old mfgData[2] status-byte check was removed - it incorrectly filtered
     * out valid trackers depending on their advertising state.
     */
    fun isFindMyNetworkPayload(mfgData: ByteArray): Boolean {
        if (mfgData.size < 2) return false
        if (mfgData[0] != 0x12.toByte()) return false
        if (mfgData[1] != 0x19.toByte()) return false
        return true
    }

    /** Alias for backward compatibility with existing call sites. */
    fun isAirTagManufacturerData(mfgData: ByteArray): Boolean = isFindMyNetworkPayload(mfgData)
}
