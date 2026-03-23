package com.aegisnav.app

/**
 * FeatureFlags — compile-time toggles for experimental / partially-implemented features.
 *
 * Findings addressed:
 *  - 2.1 / 1.4: P2P feature guard (FEATURE_P2P_ENABLED)
 *  - 3.2: P2P setup screen guard (FEATURE_P2P_SETUP_ENABLED)
 *  - 4.5: Encrypted P2P SharedPreferences guard (FEATURE_P2P_ENCRYPTED_PREFS)
 */
object FeatureFlags {
    const val FEATURE_P2P_ENABLED = false
    const val FEATURE_P2P_SETUP_ENABLED = false
    const val FEATURE_P2P_ENCRYPTED_PREFS = false

    /** Offline mode is forced ON until relay servers are built (Phase 9). */
    const val FEATURE_ONLINE_MODE_AVAILABLE = false
}
