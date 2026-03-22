package com.aegisnav.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.aegisnav.app.security.SecureDataStore
import com.aegisnav.app.util.AppLog
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.aegisnav.app.scan.BackgroundScanWorker
import com.aegisnav.app.worker.AlprDataSyncWorker
import com.aegisnav.app.worker.RedLightDataSyncWorker
import com.aegisnav.app.worker.SpeedDataSyncWorker
import com.aegisnav.app.crash.CrashHandler
import com.google.gson.stream.JsonReader
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.aegisnav.app.data.dao.ALPRBlocklistDao
import com.aegisnav.app.data.model.ALPRBlocklist

@HiltAndroidApp
class AegisNavApplication : Application(), Configuration.Provider {

    @Inject lateinit var alprBlocklistDao: ALPRBlocklistDao
    @Inject lateinit var workerFactory: HiltWorkerFactory

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // WorkManager configuration - use HiltWorkerFactory for @HiltWorker injection
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Local crash handler — writes to files/crash_log.txt for all builds
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))

        // Load SQLCipher native library (required by sqlcipher-android 4.6+)
        System.loadLibrary("sqlcipher")

        // MapLibre initialization - must happen before any MapView is created
        org.maplibre.android.MapLibre.getInstance(this)

        // Register PMTiles protocol handler via OkHttp interceptor
        // This intercepts pmtiles://asset:// URLs and serves tiles from bundled assets.
        val pmTilesInterceptor = com.aegisnav.app.map.PmTilesHttpInterceptor(this)
        val okHttpClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor(pmTilesInterceptor)
            .build()
        org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(okHttpClient)

        // Notification channel (created once at startup)
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel("SCAN_CHANNEL", "Scan Alerts", NotificationManager.IMPORTANCE_HIGH)
            )
        }

        // Register process lifecycle observer to track foreground/background state.
        // ProcessLifecycleOwner.onStop() fires when ALL activities are stopped (true background).
        // ProcessLifecycleOwner.onStart() fires when the first activity becomes visible.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                AppLifecycleTracker.onForeground()
            }
            override fun onStop(owner: LifecycleOwner) {
                AppLifecycleTracker.onBackground()
            }
        })

        verifyBundledAlprIntegrity()
        preloadALPRData()
        enqueueFirstRunSync()
        // 3.5 — Schedule periodic background BLE scanning (every 15 min via WorkManager)
        BackgroundScanWorker.schedule(this)
        enqueueTilePreCache()
        enqueueAlprWeeklySync()
        enqueueRedLightWeeklySync()
        enqueueSpeedWeeklySync()
    }

    // ── Tile pre-cache ──────────────────────────────────────────────────────

    private fun enqueueTilePreCache() {
        // Tile pre-cache is now a no-op - tiles are downloaded on-demand via Settings screen.
        AppLog.i("TilePreCache", "Tile pre-cache skipped (use Settings > Map Tiles to download)")
    }

    // ── Bundled GeoJSON integrity check ────────────────────────────────────

    /**
     * Verifies that the bundled us_alpr_national.geojson is parseable and
     * has > 0 features. Logs camera counts by source. Shows a one-time
     * system notification if the file is corrupt or empty.
     */
    private fun verifyBundledAlprIntegrity() {
        appScope.launch {
            try {
                val assetName = "us_alpr_cameras.geojson"
                val sourceCounts = mutableMapOf<String, Int>()
                var totalCount = 0

                assets.open(assetName).bufferedReader().use { reader ->
                    val jr = JsonReader(reader)
                    jr.beginObject()
                    while (jr.hasNext()) {
                        if (jr.nextName() == "features") {
                            jr.beginArray()
                            while (jr.hasNext()) {
                                var source = "UNKNOWN"
                                jr.beginObject()
                                while (jr.hasNext()) {
                                    when (jr.nextName()) {
                                        "properties" -> {
                                            jr.beginObject()
                                            while (jr.hasNext()) {
                                                if (jr.nextName() == "source") {
                                                    source = jr.nextString()
                                                } else jr.skipValue()
                                            }
                                            jr.endObject()
                                        }
                                        else -> jr.skipValue()
                                    }
                                }
                                jr.endObject()
                                totalCount++
                                // For multi-source values, count each part
                                source.split(",").forEach { s ->
                                    val key = s.trim().ifEmpty { "UNKNOWN" }
                                    sourceCounts[key] = (sourceCounts[key] ?: 0) + 1
                                }
                            }
                            jr.endArray()
                        } else jr.skipValue()
                    }
                    jr.endObject()
                }

                if (totalCount == 0) {
                    AppLog.e("AlprIntegrity", "ALPR data empty - bundled geojson has 0 features!")
                    showAlprCorruptNotification()
                } else {
                    AppLog.i("AlprIntegrity", "ALPR bundle OK: $totalCount total cameras")
                    sourceCounts.forEach { (src, cnt) ->
                        AppLog.i("AlprIntegrity", "  Source $src: $cnt cameras")
                    }
                }
            } catch (e: Exception) {
                AppLog.e("AlprIntegrity", "ALPR bundle parse failed - possible corruption", e)
                showAlprCorruptNotification()
            }
        }
    }

    private fun showAlprCorruptNotification() {
        // Pattern B: move entire block into a coroutine — the corrupt check doesn't need
        // to be synchronous; it only suppresses a duplicate notification.
        val alprDataStore = SecureDataStore.get(this, "alpr_prefs")
        val alprCorruptKey = booleanPreferencesKey("alpr_corrupt_notified")
        appScope.launch {
            val alreadyNotified = alprDataStore.data.first()[alprCorruptKey] == true
            if (alreadyNotified) return@launch
            alprDataStore.edit { it[alprCorruptKey] = true }

            val notification = android.app.Notification.Builder(this@AegisNavApplication, "SCAN_CHANNEL")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("AegisNav")
                .setContentText("ALPR data may be corrupted - reinstall app")
                .setAutoCancel(true)
                .build()
            com.aegisnav.app.util.NotificationHelper.notify(this@AegisNavApplication, 9999, notification)
        }
    }

    // ── Weekly ALPR sync ────────────────────────────────────────────────────

    /**
     * On first install (or after a full uninstall), red light and speed camera tables are empty
     * because their periodic workers only fire on a 7-day schedule. Run a one-time immediate
     * sync for each so cameras appear right away. KEEP policy skips the one-time work if
     * it was already completed (idempotent across app restarts).
     */
    /**
     * On first install (or after a full uninstall), red light and speed camera tables are empty
     * because their periodic workers only fire on a 7-day schedule. Run a one-time immediate
     * sync for each so cameras appear right away.
     * Workers skip re-import if the bundled asset checksum is unchanged (idempotent).
     */
    private fun enqueueFirstRunSync() {
        val wm = WorkManager.getInstance(this)
        wm.enqueueUniqueWork(
            "redlight_first_run_sync",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RedLightDataSyncWorker>().addTag("first_run").build()
        )
        wm.enqueueUniqueWork(
            "speed_first_run_sync",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SpeedDataSyncWorker>().addTag("first_run").build()
        )
        wm.enqueueUniqueWork(
            "alpr_first_run_sync",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AlprDataSyncWorker>().addTag("first_run").build()
        )
        AppLog.i("FirstRunSync", "Red light + speed + ALPR first-run syncs enqueued")
    }

    private fun enqueueRedLightWeeklySync() {
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            RedLightDataSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            RedLightDataSyncWorker.buildPeriodicRequest()
        )
        AppLog.i("RedLightSync", "Weekly red light sync worker enqueued")
    }

    private fun enqueueSpeedWeeklySync() {
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SpeedDataSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            SpeedDataSyncWorker.buildPeriodicRequest()
        )
        AppLog.i("SpeedSync", "Weekly speed camera sync worker enqueued")
    }

    private fun enqueueAlprWeeklySync() {
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            AlprDataSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            AlprDataSyncWorker.buildPeriodicRequest()
        )
        AppLog.i("AlprSync", "Weekly ALPR sync worker enqueued")
    }

    // ── ALPR data preload ───────────────────────────────────────────────────

    fun preloadALPRData() {
        val alprDataStore = SecureDataStore.get(this, "alpr_prefs")
        val preloadedKey = booleanPreferencesKey("alpr_preloaded_v5")
        // Pattern B: move entire block into coroutine — reading preloaded flag doesn't need
        // to be synchronous; at worst we do a redundant duplicate preload check in the coroutine.
        appScope.launch {
            val alreadyLoaded = alprDataStore.data.first()[preloadedKey] == true
            if (alreadyLoaded) return@launch

            val selectedStates = loadSelectedStates(this@AegisNavApplication)
            if (selectedStates.isEmpty()) {
                AppLog.d("Preload", "No states selected yet — skipping ALPR preload")
                return@launch
            }
            val stateBounds = USStates.forAbbrs(selectedStates)
            AppLog.d("Preload", "Loading ALPR for states: ${selectedStates.joinToString()}")

            try {
                var count = 0
                streamGeoJsonInsertFiltered("us_alpr_cameras.geojson", stateBounds) { count++ }
                // Mark global flag AND per-state flags so importAlprForStateIfNeeded
                // knows these states are already done and skips them on next switch.
                alprDataStore.edit { prefs ->
                    prefs[preloadedKey] = true
                    selectedStates.forEach { abbr ->
                        prefs[booleanPreferencesKey("alpr_imported_$abbr")] = true
                    }
                }
                AppLog.d("Preload", "ALPR preloaded: $count cameras for states: ${selectedStates.joinToString()}")
            } catch (e: Exception) {
                AppLog.e("Preload", "Error preloading ALPR data", e)
            }
        }
    }

    /**
     * Imports ALPR cameras for [code] (e.g. "GA") if they haven't been imported yet.
     * Idempotent — guarded by a per-state DataStore flag "alpr_imported_{code}".
     * Called from [MainViewModel.setActiveState] whenever the user switches active state,
     * so cameras for the new state are available in the Room DB for the map overlay.
     *
     * Uses [OnConflictStrategy.IGNORE] at the DAO level, so re-running is safe but slow.
     * The flag prevents re-scanning the 78K-entry GeoJSON on every state switch.
     */
    suspend fun importAlprForStateIfNeeded(code: String) {
        val state = USStates.byAbbr(code) ?: run {
            AppLog.d("AlprImport", "Unknown state code: $code — skipping ALPR import")
            return
        }
        val alprDataStore = SecureDataStore.get(this, "alpr_prefs")
        val flagKey = booleanPreferencesKey("alpr_imported_$code")
        val alreadyDone = alprDataStore.data.first()[flagKey] == true
        if (alreadyDone) {
            AppLog.d("AlprImport", "ALPR already imported for $code — skipping")
            return
        }
        AppLog.i("AlprImport", "Importing ALPR cameras for state $code...")
        var count = 0
        try {
            streamGeoJsonInsertFiltered("us_alpr_cameras.geojson", listOf(state)) { count++ }
            alprDataStore.edit { it[flagKey] = true }
            AppLog.i("AlprImport", "ALPR import complete for $code: $count cameras inserted")
        } catch (e: Exception) {
            AppLog.e("AlprImport", "ALPR import failed for $code", e)
        }
    }

    private suspend fun streamGeoJsonInsertFiltered(
        assetName: String,
        stateBounds: List<USState>,
        onInserted: () -> Unit = {}
    ) {
        assets.open(assetName).bufferedReader().use { reader ->
            val jr = JsonReader(reader)
            jr.beginObject()
            while (jr.hasNext()) {
                if (jr.nextName() == "features") {
                    jr.beginArray()
                    while (jr.hasNext()) {
                        parseFeature(jr)?.let { alpr ->
                            val inBounds = stateBounds.isEmpty() || stateBounds.any { s ->
                                alpr.lat in s.minLat..s.maxLat && alpr.lon in s.minLon..s.maxLon
                            }
                            if (inBounds) { alprBlocklistDao.insert(alpr); onInserted() }
                        }
                    }
                    jr.endArray()
                } else jr.skipValue()
            }
            jr.endObject()
        }
    }

    private suspend fun streamGeoJsonInsert(assetName: String) {
        assets.open(assetName).bufferedReader().use { reader ->
            val jr = JsonReader(reader)
            jr.beginObject()
            while (jr.hasNext()) {
                if (jr.nextName() == "features") {
                    jr.beginArray()
                    while (jr.hasNext()) {
                        parseFeature(jr)?.let { alprBlocklistDao.insert(it) }
                    }
                    jr.endArray()
                } else {
                    jr.skipValue()
                }
            }
            jr.endObject()
        }
    }

    private fun parseFeature(jr: JsonReader): ALPRBlocklist? {
        var lat = Double.NaN; var lon = Double.NaN
        var desc = "ALPR Camera"; var ssid: String? = null; var mac: String? = null
        var source = "OSM"

        jr.beginObject()
        while (jr.hasNext()) {
            when (jr.nextName()) {
                "geometry" -> {
                    jr.beginObject()
                    while (jr.hasNext()) {
                        if (jr.nextName() == "coordinates") {
                            jr.beginArray()
                            if (jr.hasNext()) lon = jr.nextDouble()
                            if (jr.hasNext()) lat = jr.nextDouble()
                            while (jr.hasNext()) jr.skipValue()
                            jr.endArray()
                        } else jr.skipValue()
                    }
                    jr.endObject()
                }
                "properties" -> {
                    jr.beginObject()
                    while (jr.hasNext()) {
                        when (jr.nextName()) {
                            "description", "desc", "name" -> desc = jr.nextString()
                            "ssid" -> ssid = jr.nextString()
                            "mac"  -> mac  = jr.nextString()
                            "source" -> source = jr.nextString()
                            else   -> jr.skipValue()
                        }
                    }
                    jr.endObject()
                }
                else -> jr.skipValue()
            }
        }
        jr.endObject()

        return if (!lat.isNaN() && !lon.isNaN()) {
            ALPRBlocklist(lat = lat, lon = lon, ssid = ssid, mac = mac,
                desc = desc, reported = System.currentTimeMillis(), verified = true, source = source)
        } else null
    }
}
