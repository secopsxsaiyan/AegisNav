package com.aegisnav.app.map

import android.content.Context
import com.aegisnav.app.util.AppLog
import com.github.luben.zstd.ZstdInputStream
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OkHttp interceptor that serves PMTiles v3 vector tiles from a bundled asset file.
 *
 * MapLibre Native Android uses OkHttp internally. This interceptor is registered
 * via [org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient] in
 * [AegisNavApplication].
 *
 * URL scheme handled: pmtiles://asset://<filename>/<z>/<x>/<y>
 * Example:            pmtiles://asset://florida.pmtiles/12/1152/1664
 *
 * PMTiles v3 spec: https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md
 * Implementation is from-spec; no GPL code used.
 *
 */
class PmTilesHttpInterceptor(private val context: Context) : Interceptor {

    companion object {
        private const val TAG = "PmTilesInterceptor"
        private const val SCHEME_PREFIX  = "pmtiles://asset://"
        private const val GLYPH_PREFIX   = "asset://glyphs/"

        // PMTiles v3 header offsets (all little-endian)
        private const val MAGIC = "PMTiles"
        private const val V3 = 3.toByte()
        private const val HDR_ROOT_DIR_OFFSET = 8
        private const val HDR_ROOT_DIR_LEN    = 16
        private const val HDR_METADATA_OFFSET = 24
        private const val HDR_METADATA_LEN    = 32
        private const val HDR_LEAF_DIR_OFFSET = 40
        private const val HDR_LEAF_DIR_LEN    = 48
        private const val HDR_DATA_OFFSET     = 56
        private const val HDR_DATA_LEN        = 64
        private const val HDR_TILE_TYPE       = 99   // 1=MVT, 2=PNG, 3=JPG
        private const val HDR_TILE_COMPRESSION = 98  // 1=none, 2=gzip, 3=brotli, 4=zstd
        private const val HDR_MIN_ZOOM        = 100
        private const val HDR_MAX_ZOOM        = 101
        private const val HEADER_SIZE         = 127

        // MIME types
        private val MVT_MEDIA_TYPE  = "application/x-protobuf".toMediaType()
        private val PNG_MEDIA_TYPE  = "image/png".toMediaType()
        private val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /** Empty MVT tile (valid protobuf, zero layers). */
        private val EMPTY_MVT = byteArrayOf()
    }

    /**
     * Cache of loaded asset bytes keyed by asset name.
     * ConcurrentHashMap is required - OkHttp dispatches requests on multiple threads
     * simultaneously and a plain HashMap would cause data races / ConcurrentModificationException.
     *
     * NOTE: This interceptor handles pmtiles://asset:// URLs only (small bundled assets or
     * glyphs). The primary 595 MB florida.pmtiles is served via pmtiles://file:// and is
     * handled natively by MapLibre's C++ PMTilesFileSource - it never passes through here.
     * Do NOT add large filesystem files to this cache.
     */
    private val assetCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        return when {
            url.startsWith(SCHEME_PREFIX) -> try {
                handlePmTilesRequest(request, url)
            } catch (e: Exception) {
                AppLog.e(TAG, "PMTiles error for $url", e)
                emptyTileResponse(request)
            }
            url.startsWith(GLYPH_PREFIX) -> try {
                handleGlyphRequest(request, url)
            } catch (e: Exception) {
                AppLog.w(TAG, "Glyph error for $url: ${e.message}")
                notFoundResponse(request)
            }
            else -> chain.proceed(request)
        }
    }

    // ── Glyph serving (asset://glyphs/{fontstack}/{range}.pbf) ───────────────

    private fun handleGlyphRequest(request: Request, url: String): Response {
        // Strip prefix → e.g. "Open Sans Regular/0-255.pbf"
        val relativePath = url.removePrefix(GLYPH_PREFIX)
        val assetPath = "glyphs/$relativePath"
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200).message("OK")
            .body(bytes.toResponseBody(MVT_MEDIA_TYPE))
            .build()
    }

    private fun notFoundResponse(request: Request): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(404).message("Not Found")
            .body(EMPTY_MVT.toResponseBody(MVT_MEDIA_TYPE))
            .build()

    // ── URL parsing ───────────────────────────────────────────────────────────

    /**
     * Parses pmtiles://asset://florida.pmtiles/z/x/y
     * Also handles pmtiles://asset://florida.pmtiles (metadata request).
     */
    private fun handlePmTilesRequest(request: Request, url: String): Response {
        // Strip scheme: "florida.pmtiles/12/1152/1664"
        val path = url.removePrefix(SCHEME_PREFIX)
        val parts = path.split("/")

        val assetName = parts[0]

        // Metadata request (no tile coordinates)
        if (parts.size < 4) {
            return metadataResponse(request, assetName)
        }

        val z = parts[parts.size - 3].toIntOrNull() ?: return emptyTileResponse(request)
        val x = parts[parts.size - 2].toIntOrNull() ?: return emptyTileResponse(request)
        val y = parts[parts.size - 1].toIntOrNull() ?: return emptyTileResponse(request)

        return serveTile(request, assetName, z, x, y)
    }

    // ── Asset loading ─────────────────────────────────────────────────────────

    private fun loadAsset(assetName: String): ByteArray {
        return assetCache.getOrPut(assetName) {
            context.assets.open(assetName).use { it.readBytes() }
        }
    }

    // ── PMTiles v3 tile serving ───────────────────────────────────────────────

    private fun serveTile(request: Request, assetName: String, z: Int, x: Int, y: Int): Response {
        val data = loadAsset(assetName)

        if (data.size < HEADER_SIZE) {
            AppLog.w(TAG, "PMTiles file too small: ${data.size} bytes")
            return emptyTileResponse(request)
        }

        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // Validate magic + version
        val magic = String(data, 0, 7, Charsets.US_ASCII)
        if (magic != MAGIC || data[7] != V3) {
            AppLog.w(TAG, "Invalid PMTiles magic/version: $magic / ${data[7]}")
            return emptyTileResponse(request)
        }

        val tileType        = data[HDR_TILE_TYPE].toInt() and 0xFF
        val tileCompression = data[HDR_TILE_COMPRESSION].toInt() and 0xFF
        val rootDirOffset   = buf.getLong(HDR_ROOT_DIR_OFFSET)
        val rootDirLen      = buf.getLong(HDR_ROOT_DIR_LEN)

        // Look up tile in directory
        val tileId = zxyToTileId(z, x, y)
        val entry  = findEntry(data, rootDirOffset, rootDirLen, tileId, data)
            ?: return emptyTileResponse(request)

        val dataOffset = buf.getLong(HDR_DATA_OFFSET)
        val tileStart  = (dataOffset + entry.offset).toInt()
        val tileEnd    = (tileStart + entry.length).toInt()

        if (tileEnd > data.size || tileStart < 0) {
            AppLog.w(TAG, "Tile offset out of bounds: $tileStart..$tileEnd / ${data.size}")
            return emptyTileResponse(request)
        }

        val tileBytes = data.copyOfRange(tileStart, tileEnd)

        // Decompress if needed
        val decompressed = when (tileCompression) {
            1    -> tileBytes                // no compression
            2    -> decompressGzip(tileBytes) // gzip
            4    -> decompressZstd(tileBytes)   // zstd
            else -> {
                AppLog.w(TAG, "Unsupported tile compression: $tileCompression - returning empty tile")
                EMPTY_MVT
            }
        }

        val mediaType = when (tileType) {
            1    -> MVT_MEDIA_TYPE
            2    -> PNG_MEDIA_TYPE
            3    -> JPEG_MEDIA_TYPE
            else -> MVT_MEDIA_TYPE
        }

        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(decompressed.toResponseBody(mediaType))
            .build()
    }

    // ── Directory traversal ───────────────────────────────────────────────────

    data class DirEntry(
        val tileId: Long,
        val offset: Long,
        val length: Long,
        val runLength: Int,
        val isLeaf: Boolean
    )

    /**
     * Finds a tile entry by tileId in the PMTiles v3 clustered directory.
     * Root directory is always checked first; leaf directories are followed as needed.
     * Directory entries are variable-length encoded (see spec).
     */
    private fun findEntry(
        fileData: ByteArray,
        dirOffset: Long,
        dirLen: Long,
        tileId: Long,
        fullData: ByteArray
    ): DirEntry? {
        val dirStart = dirOffset.toInt()
        val dirEnd   = (dirOffset + dirLen).toInt()

        if (dirEnd > fileData.size || dirStart < 0 || dirLen <= 0L) return null

        val dirBytes = fileData.copyOfRange(dirStart, dirEnd)

        // Decompress directory (always gzip in spec)
        val decompressedDir = try {
            decompressGzip(dirBytes)
        } catch (e: Exception) {
            // Maybe uncompressed for small dirs
            dirBytes
        }

        val entries = parseDirectory(decompressedDir)

        // Binary search for tileId
        var lo = 0; var hi = entries.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val e = entries[mid]
            when {
                e.tileId == tileId -> {
                    if (e.isLeaf) {
                        // Follow leaf directory
                        return findEntry(fullData, e.offset, e.length, tileId, fullData)
                    }
                    return e
                }
                e.tileId < tileId  -> lo = mid + 1
                else               -> hi = mid - 1
            }
        }

        // Check if run-length entry covers this tileId
        if (hi >= 0) {
            val e = entries[hi]
            if (!e.isLeaf && e.runLength > 1 && tileId < e.tileId + e.runLength) {
                val offset = e.offset + (tileId - e.tileId) * e.length
                return DirEntry(tileId, offset, e.length, 1, false)
            }
            if (e.isLeaf) {
                return findEntry(fullData, e.offset, e.length, tileId, fullData)
            }
        }

        return null
    }

    /**
     * Parse PMTiles v3 directory entries.
     * Each entry: tileId delta (varint), runLength (varint), length (varint), offset delta (varint).
     * isLeaf = runLength == 0
     */
    private fun parseDirectory(bytes: ByteArray): List<DirEntry> {
        if (bytes.isEmpty()) return emptyList()

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val numEntries = readVarLong(buf).toInt()
        val entries    = ArrayList<DirEntry>(numEntries)

        var lastTileId = 0L
        // Read tileId deltas
        val tileIds = LongArray(numEntries) { readVarLong(buf).also { lastTileId += it; it }.let { lastTileId } }
        val runLengths = IntArray(numEntries) { readVarLong(buf).toInt() }
        val lengths    = LongArray(numEntries) { readVarLong(buf) }

        var lastOffset = 0L
        for (i in 0 until numEntries) {
            val rawOffset = readVarLong(buf)
            val offset    = if (rawOffset == 0L && i > 0) lastOffset + lengths[i - 1] else rawOffset
            lastOffset = offset
            val runLen = runLengths[i]
            entries.add(DirEntry(
                tileId    = tileIds[i],
                offset    = offset,
                length    = lengths[i],
                runLength = runLen,
                isLeaf    = runLen == 0
            ))
        }

        return entries
    }

    // ── Metadata response ─────────────────────────────────────────────────────

    private fun metadataResponse(request: Request, assetName: String): Response {
        val data = loadAsset(assetName)
        val emptyMeta = """{"name":"AegisNav","format":"pbf","minzoom":0,"maxzoom":14}"""
        if (data.size < HEADER_SIZE) {
            return Response.Builder()
                .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(emptyMeta.toResponseBody(JSON_MEDIA_TYPE))
                .build()
        }

        val buf            = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val metaOffset     = buf.getLong(HDR_METADATA_OFFSET).toInt()
        val metaLen        = buf.getLong(HDR_METADATA_LEN).toInt()

        val metaJson = if (metaOffset > 0 && metaLen > 0 && metaOffset + metaLen <= data.size) {
            try {
                val metaBytes = data.copyOfRange(metaOffset, metaOffset + metaLen)
                String(decompressGzip(metaBytes), Charsets.UTF_8)
            } catch (e: Exception) { emptyMeta }
        } else emptyMeta

        return Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(metaJson.toResponseBody(JSON_MEDIA_TYPE))
            .build()
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun emptyTileResponse(request: Request): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(204)  // No Content - MapLibre will render an empty tile
            .message("No Content")
            .body(EMPTY_MVT.toResponseBody(MVT_MEDIA_TYPE))
            .build()

    private fun decompressGzip(bytes: ByteArray): ByteArray {
        val inflater = java.util.zip.GZIPInputStream(bytes.inputStream())
        return inflater.use { it.readBytes() }
    }

    private fun decompressZstd(bytes: ByteArray): ByteArray {
        // Use streaming decompression to avoid deprecated decompressedSize API
        return ZstdInputStream(bytes.inputStream()).use { it.readBytes() }
    }

    /** Read unsigned varint from ByteBuffer (LEB128). */
    private fun readVarLong(buf: ByteBuffer): Long {
        var result = 0L; var shift = 0
        while (buf.hasRemaining()) {
            val b = buf.get().toLong() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0L) break
            shift += 7
        }
        return result
    }

    /**
     * Hilbert curve ZXY → tileId conversion (PMTiles v3 standard).
     * Implements the Hilbert curve algorithm used by Planetiler.
     * Derived from the PMTiles spec pseudocode; no external code copied.
     */
    private fun zxyToTileId(z: Int, x: Int, y: Int): Long {
        if (z == 0) return 0L
        val baseId = ((1L shl (2 * z)) - 1L) / 3L  // sum of 4^0 + 4^1 + ... + 4^(z-1)
        return baseId + xyToHilbert(z, x, y)
    }

    /** Converts x,y at zoom z to Hilbert curve position. */
    private fun xyToHilbert(z: Int, x: Int, y: Int): Long {
        var rx: Int; var ry: Int
        var s: Int = 1 shl (z - 1)
        var d = 0L
        var px = x; var py = y
        while (s > 0) {
            rx = if ((px and s) > 0) 1 else 0
            ry = if ((py and s) > 0) 1 else 0
            d += s.toLong() * s.toLong() * ((3 * rx) xor ry)
            // rotate
            if (ry == 0) {
                if (rx == 1) { px = s - 1 - px; py = s - 1 - py }
                val t = px; px = py; py = t
            }
            s = s shr 1
        }
        return d
    }
}
