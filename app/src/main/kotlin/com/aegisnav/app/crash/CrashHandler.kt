package com.aegisnav.app.crash

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Local crash handler that writes stack traces to files/crash_log.txt
 * for all build types.
 */
class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val crashFile = File(context.filesDir, "crash_log.txt")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val entry = buildString {
                appendLine("=== CRASH $timestamp ===")
                appendLine("Thread: ${thread.name}")
                appendLine(throwable.stackTraceToString())
                appendLine()
            }
            crashFile.appendText(entry)
        } catch (_: Exception) {
            // Can't do anything if writing fails
        }
        // Chain to default handler (shows crash dialog / kills process)
        defaultHandler?.uncaughtException(thread, throwable)
    }
}
