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
 * @return The SDK provider for this layer
 */
internal fun TileOverlay.toGoogleTileProvider(): GoogleTileProvider =
    when (val source = source) {
        is TileSource.Bytes -> AndroidMapsTileProvider(source.provider)
        is TileSource.UrlTemplate -> AndroidUrlTileProvider(source.template, tileSize)
    }

/**
 * Bridges a [TileProvider] to the Google Maps SDK, which asks for tiles synchronously from its own
 * worker thread.
 *
 * The encoded bytes are handed to the SDK as they are, so it decodes each tile itself. A missing
 * tile returns `null` instead of [GoogleTileProvider.NO_TILE]: the SDK treats "no tile" as a
 * request it may retry later, while `NO_TILE` is cached and never asked for again.
 *
 * @param provider Supplies the encoded tile images
 */
internal class AndroidMapsTileProvider(private val provider: TileProvider) : GoogleTileProvider {
    override fun getTile(x: Int, y: Int, zoom: Int): Tile? {
        val bytes =
            try {
                runBlocking { provider.tile(zoom, x, y) }
            } catch (_: Throwable) {
                null
            } ?: return null

        return Tile(DEFAULT_TILE_SIZE, DEFAULT_TILE_SIZE, bytes)
    }
}

/**
 * Fetches the tiles of a URL template for the Google Maps SDK.
 *
 * The bytes are downloaded here rather than through the SDK's own `UrlTileProvider` so that the
 * request can identify itself: several public tile services reject requests without a meaningful
 * `User-Agent`, and the SDK's downloader sends none. A failed download returns `null` ("no tile",
 * which the SDK may retry) instead of an empty tile, which it would cache.
 *
 * @param template Tile URL containing `{x}`, `{y}` and `{z}`
 */
internal class AndroidUrlTileProvider(private val template: String, private val tileSize: Int) :
    GoogleTileProvider {
    override fun getTile(x: Int, y: Int, zoom: Int): Tile? {
        val url = TileUrlTemplate.resolve(template, zoom = zoom, x = x, y = y)
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                val bytes = connection.inputStream.use { it.readBytes() }
                if (bytes.isEmpty()) null else Tile(tileSize, tileSize, bytes)
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            null
        }
    }

    private companion object {
        /** How long a tile request may take before it is abandoned and retried later. */
        const val TIMEOUT_MS = 10_000

        /** Sent with every tile request, because some services reject requests without one. */
        const val USER_AGENT = "kmp-maps"
    }
}
