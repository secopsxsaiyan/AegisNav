package com.aegisnav.app.util

object MacUtils {
    /** Redacts last 3 octets for display: AA:BB:CC:??:??:?? */
    fun redactMac(mac: String): String {
        val parts = mac.split(":")
        if (parts.size != 6) return "??:??:??:??:??:??"
        return "${parts[0]}:${parts[1]}:${parts[2]}:??:??:??"
    }

    /** Redacts last 3 octets for storage: AA:BB:CC:XX:XX:XX */
    fun redactMacForStorage(mac: String): String {
        val parts = mac.split(":")
        if (parts.size != 6) return "XX:XX:XX:XX:XX:XX"
        return "${parts[0]}:${parts[1]}:${parts[2]}:XX:XX:XX"
    }
}
