package com.swmansion.kmpmaps.core

import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGRect
import platform.CoreLocation.CLLocationCoordinate2D
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.MapKit.MKMapRect
import platform.MapKit.MKMapRectGetMaxX
import platform.MapKit.MKMapRectGetMaxY
import platform.MapKit.MKMapRectGetMinX
import platform.MapKit.MKMapRectGetMinY
import platform.MapKit.MKMapRectMake
import platform.MapKit.MKMapRectWorld
import platform.MapKit.MKMapSizeWorld
import platform.MapKit.MKOverlayProtocol
import platform.MapKit.MKOverlayRenderer
import platform.UIKit.UIGraphicsPopContext
import platform.UIKit.UIGraphicsPushContext
import platform.UIKit.UIImage
import platform.darwin.NSObject

/** Memory budget, in bytes, for the decoded tiles of a single overlay. */
private const val TILE_CACHE_BUDGET_BYTES = 32L * 1024 * 1024

/** Number of decoded tiles an overlay keeps, whatever their size. */
private const val MIN_CACHED_TILES = 8

/** Number of failed tiles an overlay remembers before it drops the oldest mark. */
private const val MAX_TRACKED_FAILURES = 512

/** How long a tile the provider could not deliver is left alone before it is requested again. */
private val TILE_RETRY_AFTER_FAILURE = 30.seconds

/**
 * Apple Maps overlay that draws the tiles of one [TileOverlay] with a custom [MKOverlayRenderer].
 *
 * `MKTileOverlay` cannot be used for this: MapKit declares `loadTileAtPath:result:` and
 * `URLForTilePath:` in the `MKTileOverlay (CustomLoading)` category, and Kotlin/Native imports
 * category members as extension functions, which cannot be overridden. A custom [MKOverlayProtocol]
 * with a custom [MKOverlayRenderer] is the documented way to draw raster tiles instead.
 *
 * The overlay covers the whole map world, so tile `(0, 0)` of a zoom level starts at map point `(0,
 * 0)` and every tile is `tileSize / zoomScale` map points wide. A decoded tile is remembered per
 * `"zoom/x/y"` key, so panning back and forth does not ask the provider for it again; a tile the
 * provider could not deliver is retried after [TILE_RETRY_AFTER_FAILURE] instead of on every
 * repaint.
 *
 * Instances are used from the main thread only, as MapKit calls the renderer there; the provider
 * itself runs on [Dispatchers.Default].
 *
 * @param provider Supplies the tile images
 * @param tileSize Edge length, in pixels, of the supplied tiles
 * @param scope Scope owned by the map composable, cancelled with it
 */
@OptIn(ExperimentalForeignApi::class)
internal class AppleMapsTileOverlay(
    private val provider: TileProvider,
    val tileSize: Int,
    private val scope: CoroutineScope,
) : NSObject(), MKOverlayProtocol {

    /** Decoded tiles by `"zoom/x/y"` key. */
    private val tiles = linkedMapOf<String, UIImage?>()

    /** Keys whose provider call is still running, so a redraw does not ask for the tile again. */
    private val pendingTiles = mutableSetOf<String>()

    /** Tiles the provider could not deliver and when, so a repaint does not retry them at once. */
    private val failedTiles = linkedMapOf<String, TimeMark>()

    /** Number of tiles kept before the oldest one is dropped. */
    private val capacity: Int =
        maxOf(
            MIN_CACHED_TILES,
            (TILE_CACHE_BUDGET_BYTES / (tileSize.toLong() * tileSize * 4)).toInt(),
        )

    /** The whole map world, so MapKit offers this overlay every map rect it repaints. */
    override fun boundingMapRect(): CValue<MKMapRect> = MKMapRectWorld.readValue()

    /** MapKit never shows the coordinate of an overlay that covers the world. */
    override fun coordinate(): CValue<CLLocationCoordinate2D> = CLLocationCoordinate2DMake(0.0, 0.0)

    /**
     * Returns the decoded tile for [zoom], [x] and [y], or `null` when it is unknown or the
     * provider has no tile here.
     */
    fun cachedTile(zoom: Int, x: Int, y: Int): UIImage? = tiles[tileKey(zoom, x, y)]

    /**
     * Requests the tile unless it is cached or already on its way, and calls [onTileLoaded] on the
     * main thread once the image can be drawn.
     *
     * Must be called from the main thread, where the renderer runs, because it reads and writes the
     * tile bookkeeping.
     *
     * @param zoom Zoom level of the tile
     * @param x Column of the tile within [zoom]
     * @param y Row of the tile within [zoom]
     * @param onTileLoaded Called on the main thread after an unknown tile was decoded
     */
    fun loadTileIfNeeded(zoom: Int, x: Int, y: Int, onTileLoaded: () -> Unit) {
        val key = tileKey(zoom, x, y)
        if (tiles.containsKey(key) || !shouldRequest(key) || !pendingTiles.add(key)) return
        scope.launch {
            val image = loadTile(zoom, x, y)
            withContext(Dispatchers.Main) {
                pendingTiles.remove(key)
                if (image != null) {
                    failedTiles.remove(key)
                    cacheTile(key, image)
                    onTileLoaded()
                } else {
                    rememberFailure(key)
                }
            }
        }
    }

    /**
     * Reports whether the tile for [key] should be requested now.
     *
     * MapKit repaints on every gesture frame, so a tile that just failed would otherwise be
     * requested hundreds of times per second. A failure is left alone for
     * [TILE_RETRY_AFTER_FAILURE] and only then retried, which keeps a transient provider error from
     * blanking a tile for the lifetime of the overlay.
     *
     * @param key Tile cache key
     * @return `true` when there is no recent failure for [key]
     */
    private fun shouldRequest(key: String): Boolean {
        val failedAt = failedTiles[key] ?: return true
        if (failedAt.elapsedNow() < TILE_RETRY_AFTER_FAILURE) return false
        failedTiles.remove(key)
        return true
    }

    /**
     * Remembers that the tile for [key] could not be loaded.
     *
     * @param key Tile cache key
     */
    private fun rememberFailure(key: String) {
        failedTiles.remove(key)
        failedTiles[key] = TimeSource.Monotonic.markNow()
        while (failedTiles.size > MAX_TRACKED_FAILURES) {
            val oldestKey = failedTiles.keys.firstOrNull() ?: break
            failedTiles.remove(oldestKey)
        }
    }

    /**
     * Loads and decodes a single tile off the main thread.
     *
     * A provider failure means "no tile" rather than an error: the map stays usable with the base
     * map showing through, and the tile is requested again only after [TILE_RETRY_AFTER_FAILURE].
     *
     * @param zoom Zoom level of the tile
     * @param x Column of the tile within [zoom]
     * @param y Row of the tile within [zoom]
     * @return The decoded image, or `null` when the provider has no tile here or returned
     *   undecodable bytes
     */
    suspend fun loadTile(zoom: Int, x: Int, y: Int): UIImage? =
        withContext(Dispatchers.Default) {
            val bytes =
                try {
                    provider.tile(zoom, x, y)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // A failing provider means "no tile": the map stays usable and MapKit keeps
                    // drawing the base map.
                    null
                }
            bytes?.let { UIImage.imageWithData(it.toNSData()) }
        }

    /** Stores [image] for [key], dropping the entries cached first once the budget is spent. */
    private fun cacheTile(key: String, image: UIImage?) {
        tiles.remove(key)
        tiles[key] = image
        while (tiles.size > capacity) {
            val oldestKey = tiles.keys.firstOrNull() ?: break
            tiles.remove(oldestKey)
        }
    }

    /** Cache key of a tile in the Web Mercator scheme MapKit and [TileProvider] share. */
    private fun tileKey(zoom: Int, x: Int, y: Int): String = "$zoom/$x/$y"
}

/**
 * Draws the tiles of an [AppleMapsTileOverlay] as raster images.
 *
 * MapKit calls [drawMapRect] with the map rect that needs repainting and the scale it is drawn at.
 * The rect is mapped back to the `zoom/column/row` indices of the tiles covering it, the ones that
 * are already decoded are drawn, and the rest are requested. As each tile arrives, only its own map
 * rect is marked for redraw.
 *
 * @param tileOverlay Overlay whose tiles are drawn. It is held strongly because
 *   `MKOverlayRenderer.overlay` is a weak reference, so MapKit does not keep the overlay alive on
 *   its own
 */
@OptIn(ExperimentalForeignApi::class)
internal class AppleMapsTileOverlayRenderer(private val tileOverlay: AppleMapsTileOverlay) :
    MKOverlayRenderer(overlay = tileOverlay) {

    /**
     * Draws every tile that intersects [mapRect].
     *
     * @param mapRect Map rect to repaint, in map points
     * @param zoomScale Points per map point the map is drawn at
     * @param inContext Graphics context MapKit draws this overlay into
     */
    override fun drawMapRect(
        mapRect: CValue<MKMapRect>,
        zoomScale: Double,
        inContext: CGContextRef?,
    ) {
        val context = inContext ?: return
        val mapPointsPerTile = tileOverlay.tileSize / zoomScale
        if (mapPointsPerTile <= 0.0 || !mapPointsPerTile.isFinite()) return

        val zoom = zoomLevel(tileOverlay.tileSize, zoomScale)
        val tilesPerAxis = 1 shl zoom
        val overlayRect = tileOverlay.boundingMapRect()
        val offsetX = overlayRect.useContents { origin.x }
        val offsetY = overlayRect.useContents { origin.y }

        val firstColumn = floor((MKMapRectGetMinX(mapRect) - offsetX) / mapPointsPerTile).toInt()
        val lastColumn = floor((MKMapRectGetMaxX(mapRect) - offsetX) / mapPointsPerTile).toInt()
        val firstRow = floor((MKMapRectGetMinY(mapRect) - offsetY) / mapPointsPerTile).toInt()
        val lastRow = floor((MKMapRectGetMaxY(mapRect) - offsetY) / mapPointsPerTile).toInt()

        for (column in firstColumn..lastColumn) {
            if (column !in 0 until tilesPerAxis) continue
            for (row in firstRow..lastRow) {
                if (row !in 0 until tilesPerAxis) continue
                val tileRect =
                    MKMapRectMake(
                        offsetX + column * mapPointsPerTile,
                        offsetY + row * mapPointsPerTile,
                        mapPointsPerTile,
                        mapPointsPerTile,
                    )
                val image = tileOverlay.cachedTile(zoom, column, row)
                if (image != null) {
                    drawTile(image, rectForMapRect(tileRect), context)
                } else {
                    // Nothing to draw yet, so repaint just this tile once the load completes.
                    tileOverlay.loadTileIfNeeded(zoom, column, row) {
                        setNeedsDisplayInMapRect(tileRect)
                    }
                }
            }
        }
    }

    /**
     * Draws one tile into [rect].
     *
     * The context is pushed for the duration of the draw so that [UIImage.drawInRect] uses the
     * flipped coordinate system the image was decoded in; drawing straight into the CoreGraphics
     * context MapKit hands over would paint every tile upside down. The push is undone even when
     * the draw throws, so the graphics stack stays balanced.
     */
    private fun drawTile(image: UIImage, rect: CValue<CGRect>, context: CGContextRef) {
        UIGraphicsPushContext(context)
        try {
            image.drawInRect(rect)
        } finally {
            UIGraphicsPopContext()
        }
    }
}

/**
 * Approximates the Web Mercator zoom level that [zoomScale] draws at, rounded to the nearest level.
 *
 * MapKit exposes no zoom level, only the number of points per map point. The map world is
 * `MKMapSizeWorld.width` map points wide and holds `width / tileSize` tiles per axis at its deepest
 * zoom (`2^20` for 256 pixel tiles), so every doubling of [zoomScale] adds one level on top.
 *
 * @param tileSize Edge length, in pixels, of the tiles being drawn
 * @param zoomScale Points per map point the map is drawn at
 * @return The zoom level to request tiles for
 */
private fun zoomLevel(tileSize: Int, zoomScale: Double): Int {
    val tilesPerAxisAtWorldWidth = MKMapSizeWorld.width / tileSize
    val roundedScaleExponent = floor(log2(zoomScale) + 0.5)
    return max(0, floor(log2(tilesPerAxisAtWorldWidth) + roundedScaleExponent).toInt())
}

/** Copies the bytes of this array into an [NSData] owned by the caller. */
@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { pinned -> NSData.dataWithBytes(pinned.addressOf(0), size.toULong()) }
    }
