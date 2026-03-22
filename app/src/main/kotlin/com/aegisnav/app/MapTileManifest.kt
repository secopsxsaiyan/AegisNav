package com.aegisnav.app

import android.content.Context
import com.aegisnav.app.util.AppLog
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class TileStateEntry(
    @SerializedName("abbr") val abbr: String,
    @SerializedName("name") val name: String,
    @SerializedName("tiles_url") val tilesUrl: String = "",
    @SerializedName("tiles_size_mb") val tilesSizeMb: Int = 0,
    @SerializedName("geocoder_url") val geocoderUrl: String = "",
    @SerializedName("geocoder_size_mb") val geocoderSizeMb: Int = 0,
    @SerializedName("routing_url") val routingUrl: String = "",
    @SerializedName("routing_size_mb") val routingSizeMb: Int = 0,
    @SerializedName("updated") val updated: String = ""
)

data class TileManifest(
    @SerializedName("version") val version: Int = 1,
    @SerializedName("states") val states: List<TileStateEntry> = emptyList()
)

object MapTileManifest {
    // Remote manifest URL - update this constant when hosting is configured
    const val REMOTE_MANIFEST_URL = ""  // leave empty until hosting is set up

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Load manifest: try remote first (if URL set), fall back to bundled asset. */
    fun load(context: Context): TileManifest {
        if (REMOTE_MANIFEST_URL.isNotBlank()) {
            try {
                val req = Request.Builder().url(REMOTE_MANIFEST_URL).build()
                val body = client.newCall(req).execute().use { it.body?.string() }
                if (!body.isNullOrBlank()) return Gson().fromJson(body, TileManifest::class.java)
            } catch (e: java.io.IOException) {
                AppLog.w("MapTileManifest", "Remote manifest fetch failed: ${e.message}")
            }
        }
        // Fall back to bundled manifest
        return try {
            val json = context.assets.open("tile_manifest.json").bufferedReader().use { it.readText() }
            Gson().fromJson(json, TileManifest::class.java)
        } catch (e: Exception) {
            AppLog.w("MapTileManifest", "Failed to load bundled tile manifest: ${e.message}")
            TileManifest()
        }
    }

    /** Returns the installed .pmtiles file for a given state abbr, or null.
     *  Checks both abbreviated (fl.pmtiles) and full-name (florida.pmtiles) forms. */
    fun installedTilesFile(context: Context, abbr: String): java.io.File? {
        val dir = context.filesDir.resolve("tiles")
        val byAbbr = dir.resolve("${abbr.lowercase()}.pmtiles")
        if (byAbbr.exists() && byAbbr.canRead()) return byAbbr
        val fullName = com.aegisnav.app.data.DataDownloadManager.byCode(abbr)?.name?.lowercase() ?: return null
        val byName = dir.resolve("$fullName.pmtiles")
        return if (byName.exists() && byName.canRead()) byName else null
    }

    /** Returns the installed geocoder db file for a given state abbr, or null.
     *  Checks both abbreviated (fl.db) and full-name (florida.db) forms.
     *  Path must match OfflineGeocoderRepository. */
    fun installedGeocoderFile(context: Context, abbr: String): java.io.File? {
        val dir = context.filesDir.resolve("geocoder")
        val byAbbr = dir.resolve("${abbr.lowercase()}.db")
        if (byAbbr.exists() && byAbbr.canRead()) return byAbbr
        val fullName = com.aegisnav.app.data.DataDownloadManager.byCode(abbr)?.name?.lowercase() ?: return null
        val byName = dir.resolve("$fullName.db")
        return if (byName.exists() && byName.canRead()) byName else null
    }

    /** Returns the installed routing graph directory for a given state abbr, or null. */
    fun installedRoutingDir(context: Context, abbr: String): java.io.File? {
        val d = context.filesDir.resolve("routing/${abbr.lowercase()}")
        // Routing is ready when the 'properties' file (GH metadata) exists
        val ready = d.resolve("properties")
        return if (ready.exists()) d else null
    }
}
