package com.aegisnav.app

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import com.aegisnav.app.util.AppLog
import com.aegisnav.app.util.NotificationHelper
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.aegisnav.app.crash.CrashReporter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Downloads a PMTiles map file (and optionally a geocoder DB) for a given state.
 *
 * Input data keys:
 *   STATE_ABBR    - state abbreviation (e.g. "FL")
 *   TILES_URL     - HTTPS URL for the .pmtiles file
 *   GEOCODER_URL  - HTTPS URL for the geocoder .db file (optional, blank = skip)
 *   ROUTING_URL   - HTTPS URL for the routing graph .zip file (optional, blank = skip)
 *
 * Progress data keys (reported via setProgress):
 *   PROGRESS_PHASE   - "tiles", "geocoder", or "routing"
 *   PROGRESS_PERCENT - 0–100
 *   PROGRESS_DONE    - true when fully complete
 *
 * On success: files saved to:
 *   context.filesDir/tiles/<abbr>.pmtiles
 *   context.filesDir/geocoder/<abbr>.db          ← geocoder reads this exact path
 *   context.filesDir/routing/<abbr>/             ← routing graph extracted here
 * Downloads to a .tmp file first, renamed/extracted atomically on completion.
 */
@HiltWorker
class TileDownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val crashReporter: CrashReporter
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME_PREFIX = "tile_download_"
        const val KEY_STATE_ABBR   = "state_abbr"
        const val KEY_TILES_URL    = "tiles_url"
        const val KEY_GEOCODER_URL = "geocoder_url"
        const val KEY_ROUTING_URL  = "routing_url"
        const val KEY_PHASE        = "progress_phase"
        const val KEY_PERCENT      = "progress_percent"
        const val KEY_DONE         = "progress_done"
        const val KEY_ERROR        = "error_message"
        private const val TAG = "TileDownloadWorker"
        private const val NOTIF_ID_DOWNLOAD = 50  // distinct from ScanService IDs (1–6)
        private const val CHANNEL_ID = "SCAN_CHANNEL"
        private const val MAX_ZIP_ENTRY_SIZE = 500L * 1024 * 1024        // 500 MB per entry
        private const val MAX_ZIP_TOTAL_SIZE = 2L * 1024 * 1024 * 1024  // 2 GB total uncompressed

        fun workName(abbr: String) = "$WORK_NAME_PREFIX${abbr.uppercase()}"

        fun buildRequest(
            abbr: String,
            tilesUrl: String,
            geocoderUrl: String = "",
            routingUrl: String = ""
        ): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<TileDownloadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_STATE_ABBR   to abbr,
                        KEY_TILES_URL    to tilesUrl,
                        KEY_GEOCODER_URL to geocoderUrl,
                        KEY_ROUTING_URL  to routingUrl
                    )
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(workName(abbr))
                .addTag("tile_download")  // common tag for observing all download completions
                .build()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    /** Build a progress notification for the given phase + percent. */
    private fun buildProgressNotification(abbr: String, phase: String, percent: Int): Notification =
        NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading map data: ${abbr.uppercase()}")
            .setContentText("${phase.replaceFirstChar { it.uppercase() }}: $percent%")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()

    /** Post the foreground notification and update WorkManager progress together. */
    private suspend fun reportProgress(abbr: String, phase: String, percent: Int) {
        setProgress(workDataOf(KEY_PHASE to phase, KEY_PERCENT to percent))
        // Update the visible system notification
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.content.pm.PackageManager.PERMISSION_GRANTED ==
            androidx.core.content.ContextCompat.checkSelfPermission(
                appContext, android.Manifest.permission.POST_NOTIFICATIONS)) {
            nm.notify(NOTIF_ID_DOWNLOAD + abbr.hashCode().and(0xFFFF), buildProgressNotification(abbr, phase, percent))
        }
    }

    override suspend fun doWork(): Result {
        val rawAbbr     = inputData.getString(KEY_STATE_ABBR)   ?: return Result.failure()
        // Sanitize stateAbbr: only letters allowed (security: path traversal prevention)
        val abbr        = rawAbbr.replace(Regex("[^A-Za-z]"), "")
        val tilesUrl    = inputData.getString(KEY_TILES_URL)    ?: return Result.failure()
        val geocoderUrl = inputData.getString(KEY_GEOCODER_URL) ?: ""
        val routingUrl  = inputData.getString(KEY_ROUTING_URL)  ?: ""

        AppLog.i("WizardFlow", "TileDownloadWorker.doWork: abbr=$abbr tilesUrl=$tilesUrl")
        if (tilesUrl.isBlank()) return Result.failure(workDataOf(KEY_ERROR to "No tiles URL configured"))

        // Show foreground notification so download is visible when app is backgrounded.
        // Use setForeground() which WorkManager wraps in startForeground() correctly.
        try {
            val foregroundInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(
                    NOTIF_ID_DOWNLOAD + abbr.hashCode().and(0xFFFF),
                    buildProgressNotification(abbr, "tiles", 0),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                ForegroundInfo(
                    NOTIF_ID_DOWNLOAD + abbr.hashCode().and(0xFFFF),
                    buildProgressNotification(abbr, "tiles", 0)
                )
            }
            setForeground(foregroundInfo)
        } catch (e: Exception) {
            AppLog.w(TAG, "Could not set foreground for download worker: ${e.message}")
        }

        // Download tiles
        reportProgress(abbr, "tiles", 0)
        val tilesDir  = appContext.filesDir.resolve("tiles").also { it.mkdirs() }
        val tilesDest = tilesDir.resolve("${abbr.lowercase()}.pmtiles")
        val tilesResult = downloadFile(tilesUrl, tilesDest, "tiles", abbr)
        AppLog.i("WizardFlow", "TileDownloadWorker: tiles download result=$tilesResult")
        if (!tilesResult) return Result.failure(workDataOf(KEY_ERROR to "Tiles download failed"))

        // Download geocoder (optional) - saved as <abbr>.db to match OfflineGeocoderRepository path
        if (geocoderUrl.isNotBlank()) {
            reportProgress(abbr, "geocoder", 0)
            val geocoderDir  = appContext.filesDir.resolve("geocoder").also { it.mkdirs() }
            val geocoderDest = geocoderDir.resolve("${abbr.lowercase()}.db")
            downloadFile(geocoderUrl, geocoderDest, "geocoder", abbr) // non-fatal
        }

        // Download routing graph zip (optional) - extracted to routing/<abbr>/
        if (routingUrl.isNotBlank()) {
            reportProgress(abbr, "routing", 0)
            val routingDir = appContext.filesDir.resolve("routing/${abbr.lowercase()}").also { it.mkdirs() }
            val zipTmp     = appContext.filesDir.resolve("routing_${abbr.lowercase()}.zip.tmp")
            val downloaded = downloadFile(routingUrl, zipTmp, "routing", abbr)
            if (downloaded) {
                try {
                    extractZip(zipTmp, routingDir)
                    // Verify routing graph integrity: GraphHopper always writes a 'properties' file
                    val propsFile = File(routingDir, "properties")
                    if (!propsFile.exists() || propsFile.length() == 0L) {
                        AppLog.e(TAG, "Routing graph integrity check FAILED for $abbr: no 'properties' file after extraction — deleting corrupt data")
                        routingDir.deleteRecursively()
                        routingDir.mkdirs()
                    } else {
                        AppLog.i(TAG, "Routing graph integrity OK for $abbr: properties=${propsFile.length()}B, files=${routingDir.listFiles()?.size ?: 0}")
                    }
                } finally { zipTmp.delete() }
            } // non-fatal
        }

        reportProgress(abbr, "done", 100)
        setProgress(workDataOf(KEY_PHASE to "done", KEY_PERCENT to 100, KEY_DONE to true))
        // Cancel the progress notification — download complete
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.cancel(NOTIF_ID_DOWNLOAD + abbr.hashCode().and(0xFFFF))
        AppLog.i(TAG, "Download complete for $abbr")
        return Result.success()
    }

    private fun extractZip(zip: File, destDir: File) {
        var totalExtracted = 0L
        try {
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(destDir, entry.name)

                    // Zip Slip guard — wrap canonicalPath in try/catch (can throw IOException)
                    val isSafe = try {
                        outFile.canonicalPath.startsWith(destDir.canonicalPath + File.separator)
                    } catch (e: java.io.IOException) {
                        AppLog.w(TAG, "canonicalPath IOException for entry '${entry.name}': ${e.message} — skipping")
                        false
                    }
                    if (!isSafe) {
                        AppLog.w(TAG, "Skipping suspicious zip entry: ${entry.name}")
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }

                    // Zip bomb protection: per-entry size check
                    if (entry.size > MAX_ZIP_ENTRY_SIZE) {
                        AppLog.w(TAG, "Skipping oversized zip entry: ${entry.name} (${entry.size} bytes)")
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }

                    if (entry.isDirectory) { outFile.mkdirs() }
                    else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out ->
                            val copied = zis.copyTo(out)
                            totalExtracted += copied
                            // Zip bomb protection: cumulative size check
                            if (totalExtracted > MAX_ZIP_TOTAL_SIZE) {
                                AppLog.e(TAG, "Zip extraction aborted: exceeded max total size (${MAX_ZIP_TOTAL_SIZE} bytes)")
                                throw java.io.IOException("Zip bomb detected: total uncompressed size exceeded limit")
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            AppLog.i(TAG, "Extracted routing zip to ${destDir.absolutePath} (${totalExtracted} bytes)")
        } catch (e: Exception) {
            AppLog.e(TAG, "Zip extraction error: ${e.message} — cleaning up partial extraction")
            destDir.deleteRecursively()
            throw e  // re-throw so caller's finally block can clean up zipTmp
        }
    }

    private suspend fun downloadFile(url: String, dest: File, phase: String, abbr: String = ""): Boolean {
        val tmp = File("${dest.absolutePath}.tmp")
        return try {
            var expectedSize = -1L
            var downloadedSize = 0L
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLog.e(TAG, "HTTP ${response.code} downloading $url")
                    return false
                }
                val body  = response.body ?: return false
                val total = body.contentLength()
                expectedSize = total
                // Disk space pre-flight: ensure 10% headroom beyond expected file size
                if (total > 0) {
                    val available = dest.parentFile?.usableSpace ?: 0L
                    if (available < (total * 1.1).toLong()) {
                        AppLog.e(TAG, "Insufficient disk space for $phase: need ${total}B, have ${available}B")
                        return false
                    }
                }
                var downloaded = 0L
                var lastReportedPct = -1
                tmp.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            if (isStopped) { tmp.delete(); return false }
                            out.write(buf, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val pct = (downloaded * 100 / total).toInt()
                                // Throttle progress updates to whole-percent changes only
                                if (pct != lastReportedPct) {
                                    lastReportedPct = pct
                                    if (abbr.isNotBlank()) {
                                        reportProgress(abbr, phase, pct)
                                    } else {
                                        setProgress(workDataOf(KEY_PHASE to phase, KEY_PERCENT to pct))
                                    }
                                }
                            }
                        }
                    }
                }
                downloadedSize = downloaded
            }
            // Verify downloaded size matches Content-Length to detect truncated downloads
            // (e.g. from GitHub rate-limiting or network interruptions)
            if (expectedSize > 0 && downloadedSize != expectedSize) {
                AppLog.e(TAG, "Download size mismatch for $url: expected ${expectedSize}B, got ${downloadedSize}B — deleting corrupt file")
                tmp.delete()
                return false
            }
            if (downloadedSize == 0L) {
                AppLog.e(TAG, "Download produced 0 bytes for $url — deleting")
                tmp.delete()
                return false
            }
            tmp.renameTo(dest)
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "Download error for $url: ${e.message}")
            AppLog.e("WizardFlow", "Download failed: ${e::class.simpleName}: ${e.message}", e)
            // Also send to CrashReporter
            crashReporter.captureException(e)
            tmp.delete()
            false
        }
    }
}
