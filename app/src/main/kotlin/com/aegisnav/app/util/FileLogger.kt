package com.aegisnav.app.util

import com.aegisnav.app.util.AppLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a timestamped log entry to `filesDir/an_debug.log`.
 * Only active in debug builds; silently no-ops in release.
 * Caps the log file at 1 MB, truncating to the last 512 KB on overflow.
 */
internal fun fileLog(context: android.content.Context, tag: String, msg: String) {
    val isDebug = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (!isDebug) return
    try {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$ts [$tag] $msg\n"
        AppLog.d(tag, msg)
        val logFile = File(context.filesDir, "an_debug.log")
        // Cap at 1MB — truncate to last 512KB on overflow
        if (logFile.exists() && logFile.length() > 1_048_576L) {
            val content = logFile.readText()
            logFile.writeText(content.takeLast(524_288))
        }
        logFile.appendText(line)
    } catch (_: Exception) {}
}
