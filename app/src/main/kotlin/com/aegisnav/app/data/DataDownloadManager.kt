package com.aegisnav.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.aegisnav.app.TileDownloadWorker
import com.aegisnav.app.BuildConfig
import com.aegisnav.app.security.SecureDataStore
import kotlinx.coroutines.flow.first
import com.aegisnav.app.util.AppLog

/** Base URL for all state data releases. Defined in BuildConfig (app/build.gradle.kts defaultConfig). */
val DOWNLOAD_BASE_URL: String = BuildConfig.DOWNLOAD_BASE_URL

/**
 * Metadata for a US state's downloadable data package.
 *
 * @param code         Two-letter state abbreviation (e.g. "FL")
 * @param name         Full state name
 * @param tileSizeMb   Approximate size of the .pmtiles map file in MB
 * @param geocoderSizeMb Approximate size of the geocoder .db file in MB
 * @param routingSizeMb Approximate size of the routing .zip file in MB
 */
data class StateInfo(
    val code: String,
    val name: String,
    val tileSizeMb: Int,
    val geocoderSizeMb: Int,
    val routingSizeMb: Int
) {
    val totalSizeMb: Int get() = tileSizeMb + geocoderSizeMb + routingSizeMb

    val tilesUrl: String
        get() = "${DOWNLOAD_BASE_URL}${code.lowercase()}_tiles.pmtiles"
    val geocoderUrl: String
        get() = "${DOWNLOAD_BASE_URL}${code.lowercase()}_geocoder.db"
    val routingUrl: String
        get() = "${DOWNLOAD_BASE_URL}${code.lowercase()}_routing.zip"
}

/**
 * Manages in-app download of state map tiles, geocoder databases, and routing graphs.
 *
 * Downloads are dispatched to [TileDownloadWorker] (WorkManager-backed) so they survive
 * process death and respect network constraints.  This object provides helpers for:
 *  - Starting / cancelling downloads
 *  - Checking whether data is already installed
 *  - Deleting installed data
 *  - Checking available device storage
 *  - Checking offline-mode preference (downloads blocked when offline mode is ON)
 */
object DataDownloadManager {

    private const val TAG = "DataDownloadManager"

    /**
     * Single serial queue name for all state downloads.
     * WorkManager runs UNIQUE work with APPEND_OR_REPLACE so each new download
     * is chained after any in-progress download, preventing concurrent GitHub
     * connections that cause throttling and corrupt/partial files.
     */
    private const val DOWNLOAD_QUEUE = "state_download_queue"

    /** Hardcoded size estimates (MB) for all 50 US states + DC. */
    val ALL_STATES: List<StateInfo> = listOf(
        StateInfo("AL", "Alabama",        320, 120, 180),
        StateInfo("AK", "Alaska",         700, 150, 250),
        StateInfo("AZ", "Arizona",        580, 180, 300),
        StateInfo("AR", "Arkansas",       280, 100, 160),
        StateInfo("CA", "California",     800, 300, 400),
        StateInfo("CO", "Colorado",       520, 160, 280),
        StateInfo("CT", "Connecticut",    210,  70, 110),
        StateInfo("DE", "Delaware",       200,  50, 100),
        StateInfo("FL", "Florida",        450, 200, 240),
        StateInfo("GA", "Georgia",        380, 150, 200),
        StateInfo("HI", "Hawaii",         220,  60, 110),
        StateInfo("ID", "Idaho",          480, 140, 240),
        StateInfo("IL", "Illinois",       420, 180, 220),
        StateInfo("IN", "Indiana",        320, 120, 170),
        StateInfo("IA", "Iowa",           300, 110, 160),
        StateInfo("KS", "Kansas",         350, 120, 190),
        StateInfo("KY", "Kentucky",       310, 120, 170),
        StateInfo("LA", "Louisiana",      330, 130, 180),
        StateInfo("ME", "Maine",          260,  80, 130),
        StateInfo("MD", "Maryland",       250,  90, 130),
        StateInfo("MA", "Massachusetts",  240,  90, 120),
        StateInfo("MI", "Michigan",       410, 160, 210),
        StateInfo("MN", "Minnesota",      430, 160, 220),
        StateInfo("MS", "Mississippi",    290, 100, 160),
        StateInfo("MO", "Missouri",       360, 140, 200),
        StateInfo("MT", "Montana",        620, 150, 270),
        StateInfo("NE", "Nebraska",       370, 120, 200),
        StateInfo("NV", "Nevada",         520, 140, 260),
        StateInfo("NH", "New Hampshire",  220,  70, 110),
        StateInfo("NJ", "New Jersey",     240, 100, 130),
        StateInfo("NM", "New Mexico",     560, 150, 280),
        StateInfo("NY", "New York",       490, 210, 260),
        StateInfo("NC", "North Carolina", 380, 150, 200),
        StateInfo("ND", "North Dakota",   330, 100, 180),
        StateInfo("OH", "Ohio",           380, 160, 200),
        StateInfo("OK", "Oklahoma",       370, 130, 200),
        StateInfo("OR", "Oregon",         550, 170, 280),
        StateInfo("PA", "Pennsylvania",   410, 170, 220),
        StateInfo("RI", "Rhode Island",   200,  50, 100),
        StateInfo("SC", "South Carolina", 290, 110, 160),
        StateInfo("SD", "South Dakota",   340, 110, 185),
        StateInfo("TN", "Tennessee",      330, 130, 180),
        StateInfo("TX", "Texas",          780, 300, 400),
        StateInfo("UT", "Utah",           490, 140, 250),
        StateInfo("VT", "Vermont",        210,  65, 105),
        StateInfo("VA", "Virginia",       360, 150, 195),
        StateInfo("WA", "Washington",     530, 180, 280),
        StateInfo("WV", "West Virginia",  260, 100, 145),
        StateInfo("WI", "Wisconsin",      390, 150, 205),
        StateInfo("WY", "Wyoming",        480, 130, 240),
        StateInfo("DC", "Washington DC",  200,  60, 100)
    )

    private val stateByCode: Map<String, StateInfo> = ALL_STATES.associateBy { it.code }

    fun byCode(code: String): StateInfo? = stateByCode[code.uppercase()]

    // ── Download dispatch ──────────────────────────────────────────────────

    /**
     * Enqueue a download for [state] via [TileDownloadWorker].
     * URLs are sourced from the tile manifest (if available) with fallback to StateInfo computed URLs.
     * @return false if insufficient storage.
     */
    fun startDownload(context: Context, state: StateInfo): Boolean {
        val requiredBytes = state.totalSizeMb.toLong() * 1024 * 1024
        val freeBytes = getAvailableStorageBytes(context)
        if (freeBytes < requiredBytes) {
            AppLog.w(TAG, "startDownload blocked: insufficient storage " +
                    "(need ${requiredBytes / 1_048_576} MB, have ${freeBytes / 1_048_576} MB)")
            return false
        }

        // Resolve URLs: prefer manifest entry, fall back to computed StateInfo URLs
        val manifest = com.aegisnav.app.MapTileManifest.load(context)
        val manifestEntry = manifest.states.firstOrNull { it.abbr.equals(state.code, ignoreCase = true) }
        val tilesUrl    = manifestEntry?.tilesUrl?.takeIf { it.isNotBlank() }    ?: state.tilesUrl
        val geocoderUrl = manifestEntry?.geocoderUrl?.takeIf { it.isNotBlank() } ?: state.geocoderUrl
        val routingUrl  = manifestEntry?.routingUrl?.takeIf { it.isNotBlank() }  ?: state.routingUrl

        AppLog.i("WizardFlow", "startDownload: state=${state.code} manifestEntry=${manifestEntry != null} tilesUrl=$tilesUrl")
        AppLog.i(TAG, "startDownload ${state.code}: tilesUrl=$tilesUrl")

        val request = TileDownloadWorker.buildRequest(
            abbr        = state.code,
            tilesUrl    = tilesUrl,
            geocoderUrl = geocoderUrl,
            routingUrl  = routingUrl
        )

        // Serialize all state downloads into a single queue to prevent concurrent
        // GitHub connections that cause throttling, partial downloads, and corrupt files.
        // APPEND_OR_REPLACE chains this after any in-progress download.
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniqueWork(
            DOWNLOAD_QUEUE,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
        AppLog.i(TAG, "Enqueued download for ${state.code} (serialized queue)")
        return true
    }

    /** Cancel an in-progress or enqueued download for [stateCode]. */
    fun cancelDownload(context: Context, stateCode: String) {
        val wm = WorkManager.getInstance(context)
        // Cancel by tag (state-specific) — works whether queued individually or in serial chain
        wm.cancelAllWorkByTag(TileDownloadWorker.workName(stateCode))
        AppLog.i(TAG, "Cancelled download for $stateCode")
    }

    /** Cancel all pending and in-progress state downloads. */
    fun cancelAllDownloads(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(DOWNLOAD_QUEUE)
        AppLog.i(TAG, "Cancelled all state downloads")
    }

    // ── Installation checks ────────────────────────────────────────────────

    fun isTilesInstalled(context: Context, stateCode: String): Boolean {
        val dir = context.filesDir.resolve("tiles")
        val abbr = stateCode.lowercase()
        val fullName = byCode(stateCode)?.name?.lowercase() ?: abbr
        return dir.resolve("$abbr.pmtiles").exists() || dir.resolve("$fullName.pmtiles").exists()
    }

    fun isGeocoderInstalled(context: Context, stateCode: String): Boolean {
        val dir = context.filesDir.resolve("geocoder")
        val abbr = stateCode.lowercase()
        val fullName = byCode(stateCode)?.name?.lowercase() ?: abbr
        return dir.resolve("$abbr.db").exists() || dir.resolve("$fullName.db").exists()
    }

    fun isRoutingInstalled(context: Context, stateCode: String): Boolean {
        val abbr = stateCode.lowercase()
        val routingDir = context.filesDir.resolve("routing")
        // Standard layout: routing/<abbr>/properties (per-state subdirectory)
        if (routingDir.resolve("$abbr/properties").exists()) return true
        // Legacy flat layout: routing/properties (no subdirectory) — only valid for single-state sideload
        // Attribute to this state only if no other state subdirectory exists
        val flatProps = routingDir.resolve("properties")
        if (flatProps.exists()) {
            val hasAnySubdir = routingDir.listFiles()?.any { it.isDirectory } == true
            if (!hasAnySubdir) return true
        }
        return false
    }

    /** True only when tiles, geocoder, and routing are all present. */
    fun isFullyInstalled(context: Context, stateCode: String): Boolean =
        isTilesInstalled(context, stateCode) &&
        isGeocoderInstalled(context, stateCode) &&
        isRoutingInstalled(context, stateCode)

    // ── Deletion ───────────────────────────────────────────────────────────

    /**
     * Delete all downloaded data for [stateCode] (tiles, geocoder, routing dir).
     * Cancels any active download first.
     */
    fun deleteStateData(context: Context, stateCode: String) {
        cancelDownload(context, stateCode)
        val abbr = stateCode.lowercase()
        val fullName = byCode(stateCode)?.name?.lowercase() ?: abbr
        // Delete both abbreviated and full-name variants
        context.filesDir.resolve("tiles/$abbr.pmtiles").delete()
        context.filesDir.resolve("tiles/$fullName.pmtiles").delete()
        context.filesDir.resolve("geocoder/$abbr.db").delete()
        context.filesDir.resolve("geocoder/$fullName.db").delete()
        context.filesDir.resolve("routing/$abbr").deleteRecursively()
        // Flat routing layout (sideloaded without subdirectory) — delete all non-directory files
        // as a fallback for flat sideloads with arbitrary filenames
        val routingDir = context.filesDir.resolve("routing")
        val hasAnySubdir = routingDir.listFiles()?.any { it.isDirectory } == true
        if (!hasAnySubdir) {
            routingDir.listFiles()?.filter { !it.isDirectory }?.forEach { it.delete() }
        }
        AppLog.i(TAG, "Deleted all data for $stateCode")
    }

    // ── Storage & mode checks ──────────────────────────────────────────────

    /** Free bytes available in the app's files directory. */
    fun getAvailableStorageBytes(context: Context): Long =
        context.filesDir.freeSpace

    /** True when the user has enabled offline mode (downloads should be blocked). */
    suspend fun isOfflineModeOn(context: Context): Boolean {
        return SecureDataStore.get(context, "app_prefs")
            .data.first()[booleanPreferencesKey("offline_mode")] ?: true
    }
}
