package com.aegisnav.app.worker

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aegisnav.app.security.SecureDataStore
import com.aegisnav.app.security.editBlocking
import kotlinx.coroutines.flow.first
import com.aegisnav.app.util.AppLog
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.google.gson.stream.JsonReader
import com.aegisnav.app.data.dao.ALPRBlocklistDao
import com.aegisnav.app.data.model.ALPRBlocklist
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Weekly WorkManager task that syncs the bundled ALPR camera dataset into Room.
 *
 * Current behaviour:
 *  - Phase 2: Re-reads the bundled `us_alpr_cameras.geojson` from assets
 *    and upserts all entries into the Room DB (idempotent - uses REPLACE
 *    on lat/lon unique index).
 *  - P2P hook: logged but no-op until Phase 3.
 *
 * Scheduled weekly with no network requirement (fully offline).
 */
@HiltWorker
class AlprDataSyncWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val alprBlocklistDao: ALPRBlocklistDao
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME = "alpr_data_sync_weekly"
        private const val TAG = "AlprDataSyncWorker"
        private const val ASSET_NAME = "us_alpr_cameras.geojson"

        fun buildPeriodicRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<AlprDataSyncWorker>(7, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED) // fully offline
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .addTag(WORK_NAME)
                .build()
    }

    override suspend fun doWork(): Result {
        AppLog.i(TAG, "AlprDataSyncWorker started")

        // ── Asset checksum - skip reimport if asset unchanged ────────────────
        val dataStore = SecureDataStore.get(applicationContext, "alpr_prefs")
        val checksumKey = stringPreferencesKey("alpr_asset_checksum_v1")
        val storedChecksum = dataStore.data.first()[checksumKey] ?: ""
        val currentChecksum = getAssetChecksum(applicationContext, ASSET_NAME)
        if (currentChecksum.isNotEmpty() && currentChecksum == storedChecksum) {
            AppLog.i(TAG, "ALPR asset unchanged (checksum match) - skipping reimport")
            return Result.success()
        }

        // ── P2P hook (future) ────────────────────────────────────────────────
        AppLog.i(TAG, "P2P sync not yet implemented - skipping network check")

        // ── Bundled asset re-sync ────────────────────────────────────────────
        return try {
            val count = upsertBundledAsset()
            AppLog.i(TAG, "ALPR sync complete: $count cameras upserted from bundled asset")
            dataStore.editBlocking { this[checksumKey] = currentChecksum }
            Result.success(
                workDataOf("cameras_synced" to count)
            )
        } catch (e: java.io.IOException) {
            AppLog.e(TAG, "Transient error during ALPR sync, will retry: ${e.message}")
            Result.retry()
        } catch (e: Exception) {
            AppLog.e(TAG, "Permanent error during ALPR sync: ${e.message}")
            Result.failure()
        }
    }

    private fun getAssetChecksum(context: Context, assetName: String): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            context.assets.open(assetName).use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    md.update(buffer, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) { "" }
    }

    private suspend fun upsertBundledAsset(): Int {
        var count = 0
        appContext.assets.open(ASSET_NAME).bufferedReader().use { reader ->
            val jr = JsonReader(reader)
            jr.beginObject()
            while (jr.hasNext()) {
                if (jr.nextName() == "features") {
                    jr.beginArray()
                    while (jr.hasNext()) {
                        parseFeature(jr)?.let { alpr ->
                            alprBlocklistDao.upsert(alpr)
                            count++
                        }
                    }
                    jr.endArray()
                } else {
                    jr.skipValue()
                }
            }
            jr.endObject()
        }
        return count
    }

    private fun parseFeature(jr: JsonReader): ALPRBlocklist? {
        var lat = Double.NaN
        var lon = Double.NaN
        var desc = "ALPR Camera"
        var ssid: String? = null
        var mac: String? = null
        var source = "OSM"
        var state = ""

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
                            "mac" -> mac = jr.nextString()
                            "source" -> source = jr.nextString()
                            "state" -> state = jr.nextString()
                            else -> jr.skipValue()
                        }
                    }
                    jr.endObject()
                }
                else -> jr.skipValue()
            }
        }
        jr.endObject()

        return if (!lat.isNaN() && !lon.isNaN()) {
            ALPRBlocklist(
                lat = lat,
                lon = lon,
                ssid = ssid,
                mac = mac,
                desc = desc,
                reported = System.currentTimeMillis(),
                verified = true,
                source = source,
                state = state
            )
        } else null
    }
}
