package com.aegisnav.app

import android.content.Context
import com.aegisnav.app.util.AppLog
import java.io.File

/**
 * Resolves the PMTiles source URI for MapLibre.
 *
 * Scans candidate directories for any readable .pmtiles file - avoids hardcoding
 * a filename so stale locked files from previous installs don't block new pushes.
 */
object TileSourceResolver {

    private const val TAG = "TileSourceResolver"

    /**
     * Directories searched in priority order.
     * First readable .pmtiles file found in any of these wins.
     */
    private fun candidateDirs(context: Context): List<File> = listOfNotNull(
        // Internal storage first - guaranteed accessible, no scoped-storage permissions needed
        context.filesDir.resolve("tiles"),
        // App-scoped external storage (no permission needed on Android 10+)
        context.getExternalFilesDir(null)?.resolve("tiles")
        // Legacy /sdcard/AegisNav/ path removed: files on shared external storage can be
        // read or modified by any other app with storage permission. Use filesDir only.
    )

    /** Returns the first readable .pmtiles file across all candidate dirs, or null. */
    private fun findReadable(context: Context): File? {
        for (dir in candidateDirs(context)) {
            if (!dir.isDirectory) continue
            val hit = try {
                dir.listFiles { f -> f.extension == "pmtiles" && f.canRead() }?.firstOrNull()
            } catch (e: SecurityException) {
                AppLog.w(TAG, "SecurityException scanning dir ${dir.absolutePath}: ${e.message}")
                null
            }
            if (hit != null) return hit
        }
        return null
    }

    fun expectedFile(context: Context): File =
        findReadable(context) ?: candidateDirs(context).first().resolve("florida.pmtiles")

    fun isAvailable(context: Context): Boolean {
        val f = findReadable(context)
        if (f != null) AppLog.i(TAG, "Tiles found at: ${f.absolutePath}")
        else AppLog.w(TAG, "No readable .pmtiles found in any candidate dir")
        return f != null
    }

    /**
     * Returns a pmtiles:// URI for MapLibre, or null if no readable tile file found.
     * Scans all candidate dirs for any .pmtiles file (legacy / single-state fallback).
     */
    fun resolveUri(context: Context): String? {
        candidateDirs(context).forEach { dir ->
            try {
                dir.listFiles { f -> f.extension == "pmtiles" }?.forEach { f ->
                    AppLog.d(TAG, "Checking: ${f.absolutePath} → exists=${f.exists()} canRead=${f.canRead()}")
                }
            } catch (e: SecurityException) {
                AppLog.w(TAG, "SecurityException scanning dir ${dir.absolutePath}: ${e.message}")
            }
        }
        val f = findReadable(context) ?: return null
        AppLog.i(TAG, "Resolved tile URI from: ${f.absolutePath}")
        return "pmtiles://file://${f.absolutePath}"
    }

    /**
     * Returns a pmtiles:// URI for a specific state code (e.g. "FL"), or null if not found.
     * Tries <abbr>.pmtiles and <fullname>.pmtiles inside the tiles dir.
     */
    fun resolveUri(context: Context, stateCode: String): String? {
        val tilesDir = context.filesDir.resolve("tiles")
        // Sanitize stateCode: only letters allowed (path traversal prevention)
        val abbr = stateCode.replace(Regex("[^A-Za-z]"), "").lowercase()
        val candidates = listOfNotNull(
            tilesDir.resolve("$abbr.pmtiles"),
            com.aegisnav.app.data.DataDownloadManager.byCode(stateCode)
                ?.name?.lowercase()
                ?.let { tilesDir.resolve("$it.pmtiles") }
        )
        val file = candidates.firstOrNull { it.exists() && it.canRead() } ?: return null
        AppLog.i(TAG, "Resolved tile URI for $stateCode from: ${file.absolutePath}")
        return "pmtiles://file://${file.absolutePath}"
    }

    /**
     * Returns all downloaded state codes that have a readable .pmtiles file.
     */
    fun downloadedStateCodes(context: Context): List<String> {
        val tilesDir = context.filesDir.resolve("tiles")
        if (!tilesDir.isDirectory) return emptyList()
        return try {
            tilesDir.listFiles { f -> f.extension == "pmtiles" && f.canRead() }
                ?.mapNotNull { f ->
                    // Match by abbr (fl.pmtiles → FL) or fullname (florida.pmtiles → FL)
                    val stem = f.nameWithoutExtension.uppercase()
                    val byAbbr = com.aegisnav.app.data.DataDownloadManager.byCode(stem)
                    if (byAbbr != null) return@mapNotNull byAbbr.code
                    // Try matching by full name
                    com.aegisnav.app.data.DataDownloadManager.ALL_STATES
                        .firstOrNull { it.name.lowercase() == f.nameWithoutExtension.lowercase() }
                        ?.code
                } ?: emptyList()
        } catch (e: SecurityException) {
            AppLog.w(TAG, "SecurityException in downloadedStateCodes: ${e.message}")
            emptyList()
        }
    }
}
