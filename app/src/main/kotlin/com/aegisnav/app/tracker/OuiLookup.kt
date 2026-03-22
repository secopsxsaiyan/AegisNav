package com.aegisnav.app.tracker

import android.content.Context
import com.aegisnav.app.util.AppLog
import java.util.Locale

/**
 * Looks up manufacturer name from a MAC address prefix (first 3 bytes / OUI).
 *
 * Data is loaded from assets/oui_mini.csv on first access and cached in memory.
 * Format: OUI_HEX (6 hex chars, no colons),MANUFACTURER_NAME
 *
 * This is bundled, offline-only - no network access.
 */
object OuiLookup {

    private const val TAG = "OuiLookup"
    private const val ASSET_FILE = "oui_mini.csv"

    @Volatile private var cache: Map<String, String>? = null

    /**
     * Returns the manufacturer name for the given MAC address, or null if not found.
     * MAC may be in any format (colons, dashes, or raw hex).
     */
    fun lookup(context: Context, mac: String): String? {
        val ouiKey = normalizeOui(mac) ?: return null
        return getCache(context)[ouiKey]
    }

    /**
     * Returns true if the MAC belongs to a known infrastructure/AP vendor
     * (Cisco, Ubiquiti, Aruba, Netgear, TP-Link, Huawei, etc.) that should
     * be excluded from tracker detection.
     */
    fun isInfrastructureVendor(context: Context, mac: String): Boolean {
        val manufacturer = lookup(context, mac) ?: return false
        return INFRASTRUCTURE_KEYWORDS.any {
            manufacturer.contains(it, ignoreCase = true)
        }
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private val INFRASTRUCTURE_KEYWORDS = listOf(
        "Cisco", "Ubiquiti", "Aruba", "Netgear", "TP-Link", "Juniper",
        "HP", "Hewlett", "Extreme Networks", "Huawei", "ZTE", "Ruckus",
        "Aerohive", "Meraki", "Fortinet", "Brocade", "Allied Telesis",
        "D-Link", "Linksys", "Asus", "ASUS", "Belkin", "Motorola Solutions",
        "Sophos", "Palo Alto", "CheckPoint"
    )

    private fun getCache(context: Context): Map<String, String> {
        cache?.let { return it }
        return synchronized(this) {
            cache ?: loadFromAssets(context).also { cache = it }
        }
    }

    private fun loadFromAssets(context: Context): Map<String, String> {
        return try {
            context.assets.open(ASSET_FILE).bufferedReader().useLines { lines ->
                val map = HashMap<String, String>(600)
                lines.drop(1) // skip header
                    .forEach { line ->
                        val comma = line.indexOf(',')
                        if (comma > 0 && comma < line.length - 1) {
                            val oui = line.substring(0, comma).trim()
                                .replace(":", "").replace("-", "")
                                .uppercase(Locale.US)
                            val mfg = line.substring(comma + 1).trim()
                            if (oui.length == 6 && mfg.isNotEmpty()) {
                                map[oui] = mfg
                            }
                        }
                    }
                map
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to load OUI asset", e)
            emptyMap()
        }
    }

    /**
     * Normalises a MAC address to a 6-char uppercase OUI key (first 3 bytes).
     * Accepts: "AA:BB:CC:DD:EE:FF", "AA-BB-CC-DD-EE-FF", "AABBCCDDEEFF"
     */
    private fun normalizeOui(mac: String): String? {
        val clean = mac.replace(":", "").replace("-", "").uppercase(Locale.US)
        if (clean.length < 6) return null
        return clean.substring(0, 6)
    }
}
