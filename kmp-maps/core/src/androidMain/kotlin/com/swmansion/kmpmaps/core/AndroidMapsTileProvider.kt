package com.swmansion.kmpmaps.core

import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider as GoogleTileProvider
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking

/**
 * Maps a [TileSource] to the tile provider the Google Maps SDK expects.
 *
 * @param applicationPackageName Package of the host app, sent in the `User-Agent` of URL template
 *   requests
 */
internal fun TileOverlay.toGoogleTileProvider(applicationPackageName: String): GoogleTileProvider =
    when (val source = source) {
        is TileSource.Bytes -> AndroidMapsTileProvider(source.provider)
        is TileSource.UrlTemplate ->
            AndroidUrlTileProvider(source.template, tileSize, applicationPackageName)
    }

/**
 * Bridges a [TileProvider] to the Google Maps SDK, which asks for tiles synchronously from its own
 * worker thread.
 *
 * A missing tile returns `null` instead of [GoogleTileProvider.NO_TILE]: the SDK may retry `null`
 * later, while `NO_TILE` is cached and never asked for again.
 */
internal class AndroidMapsTileProvider(private val provider: TileProvider) : GoogleTileProvider {
    override fun getTile(x: Int, y: Int, zoom: Int): Tile? {
        val bytes = runBlocking { provider.bytesOrNull(zoom, x, y) } ?: return null
        return Tile(DEFAULT_TILE_SIZE, DEFAULT_TILE_SIZE, bytes)
    }
}

/**
 * Fetches the tiles of a URL template for the Google Maps SDK.
 *
 * Downloaded here rather than by the SDK's `UrlTileProvider` because several public tile services
 * reject requests without a meaningful `User-Agent`, and the SDK's downloader sends none. A
 * definitive "no such tile" answer returns [GoogleTileProvider.NO_TILE] (cached by the SDK), a
 * failed download returns `null` (retried later); see [classifyTileResponse].
 *
 * @param template Tile URL containing `{x}`, `{y}` and `{z}`
 * @param tileSize Width and height of a tile, in pixels
 * @param applicationPackageName Package of the host app, sent in the `User-Agent`
 */
internal class AndroidUrlTileProvider(
    private val template: String,
    private val tileSize: Int,
    applicationPackageName: String,
) : GoogleTileProvider {
    private val userAgent = "$USER_AGENT_PREFIX ($applicationPackageName)"

    override fun getTile(x: Int, y: Int, zoom: Int): Tile? {
        val tileUrl = template.resolveTileUrl(zoom = zoom, x = x, y = y)
        return try {
            val connection = URL(tileUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", userAgent)
            try {
                val status = connection.responseCode
                val bytes =
                    if (status == HttpURLConnection.HTTP_OK) {
                        connection.inputStream.use { it.readBytes() }
                    } else {
                        ByteArray(0)
                    }
                when (classifyTileResponse(status, bodyIsEmpty = bytes.isEmpty())) {
                    TileResponseKind.TILE -> Tile(tileSize, tileSize, bytes)
                    TileResponseKind.NO_TILE -> GoogleTileProvider.NO_TILE
                    TileResponseKind.RETRY -> null
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            null
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000

        const val USER_AGENT_PREFIX = "kmp-maps"
    }
}
