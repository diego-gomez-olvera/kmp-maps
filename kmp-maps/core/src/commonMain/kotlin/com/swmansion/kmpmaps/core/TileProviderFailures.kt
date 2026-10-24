package com.swmansion.kmpmaps.core

import kotlinx.coroutines.CancellationException

/**
 * Asks [provider] for a tile and turns a failed request into "no tile".
 *
 * A tile layer is decoration: when a provider fails, the map keeps drawing the base map and asks
 * again later instead of interrupting the renderer. Only [Exception] is caught: an [Error] is left
 * alone, and cancellation is rethrown so the caller's scope keeps working.
 *
 * @return The encoded tile, or `null` when the provider failed or has no tile here
 */
internal suspend fun TileProvider.bytesOrNull(zoom: Int, x: Int, y: Int): ByteArray? =
    try {
        tile(zoom, x, y)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }
