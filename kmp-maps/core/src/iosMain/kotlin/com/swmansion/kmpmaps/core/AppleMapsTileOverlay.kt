package com.swmansion.kmpmaps.core

import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGRect
import platform.CoreLocation.CLLocationCoordinate2D
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.Foundation.NSData
import platform.Foundation.NSLock
import platform.Foundation.dataWithBytes
import platform.MapKit.MKMapRect
import platform.MapKit.MKMapRectMake
import platform.MapKit.MKMapRectWorld
import platform.MapKit.MKOverlayProtocol
import platform.MapKit.MKOverlayRenderer
import platform.UIKit.UIGraphicsPopContext
import platform.UIKit.UIGraphicsPushContext
import platform.UIKit.UIImage
import platform.darwin.NSObject

/** Memory budget, in bytes, for the decoded tiles of one overlay. */
private const val TILE_CACHE_BUDGET_BYTES = 32L * 1024 * 1024

/** Lower bound on cached tiles, so very large tiles still get a working cache. */
private const val MIN_CACHED_TILES = 8

private const val MAX_TRACKED_FAILURES = 512

private val TILE_RETRY_AFTER_FAILURE = 30.seconds

/**
 * Apple Maps overlay that serves the tiles of one [TileOverlay] to [AppleMapsTileOverlayRenderer].
 *
 * `MKTileOverlay` cannot be used: MapKit declares `loadTileAtPath:result:` and `URLForTilePath:` in
 * its `CustomLoading` category, and Kotlin/Native imports category members as extension functions,
 * which cannot be overridden. A custom [MKOverlayProtocol] with its own renderer is used instead.
 *
 * Decoded tiles are cached per `"zoom/x/y"` key; a tile the provider could not deliver is retried
 * after [TILE_RETRY_AFTER_FAILURE] rather than on every repaint. MapKit may draw on several
 * background threads at once, so the bookkeeping is guarded by one lock that is never held across
 * the provider call, image decoding or drawing.
 *
 * @param provider Supplies the tile images
 * @param tileSize Edge length, in points, of the supplied tiles
 * @param scope Scope owned by the map composable, cancelled with it
 * @param timeSource Clock that times the retry of failed tiles; tests substitute a controllable one
 */
@OptIn(ExperimentalForeignApi::class)
internal class AppleMapsTileOverlay(
    private val provider: TileProvider,
    val tileSize: Int,
    private val scope: CoroutineScope,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : NSObject(), MKOverlayProtocol {

    /** Guards [tiles], [pendingTiles] and [failedTiles]; the private helpers expect it held. */
    private val lock = NSLock()

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /** Least recently used first. */
    private val tiles = linkedMapOf<String, UIImage?>()

    private val pendingTiles = mutableSetOf<String>()

    /** Tiles the provider could not deliver and when, so a repaint does not retry them at once. */
    private val failedTiles = linkedMapOf<String, TimeMark>()

    private val maxCachedTiles: Int =
        maxOf(
            MIN_CACHED_TILES,
            (TILE_CACHE_BUDGET_BYTES / (tileSize.toLong() * tileSize * 4)).toInt(),
        )

    /** The whole map world, so MapKit offers this overlay every map rect it repaints. */
    override fun boundingMapRect(): CValue<MKMapRect> = MKMapRectWorld.readValue()

    /** MapKit never shows the coordinate of an overlay that covers the world. */
    override fun coordinate(): CValue<CLLocationCoordinate2D> = CLLocationCoordinate2DMake(0.0, 0.0)

    /**
     * Returns the decoded tile, or `null` when it is unknown or the provider has no tile here. A
     * hit marks the tile as the most recently used.
     */
    fun cachedTile(zoom: Int, x: Int, y: Int): UIImage? {
        val key = tileKey(zoom, x, y)
        return locked {
            if (!tiles.containsKey(key)) return@locked null
            val image = tiles.remove(key)
            tiles[key] = image
            image
        }
    }

    /**
     * Requests the tile unless it is cached or already on its way, and calls [onTileLoaded] on the
     * main thread once it can be drawn. Safe to call from MapKit's draw threads.
     *
     * [onTileLoaded] also fires when the tile got cached between the caller's cache miss and this
     * call, so a draw pass racing the load still gets its redraw.
     */
    fun loadTileIfNeeded(zoom: Int, x: Int, y: Int, onTileLoaded: () -> Unit) {
        val key = tileKey(zoom, x, y)
        val (alreadyCached, shouldLoad) =
            locked {
                val cached = tiles.containsKey(key)
                cached to (!cached && shouldRequest(key) && pendingTiles.add(key))
            }
        if (alreadyCached) {
            scope.launch(Dispatchers.Main) { onTileLoaded() }
            return
        }
        if (!shouldLoad) return
        scope.launch {
            val image = loadTile(zoom, x, y)
            locked {
                pendingTiles.remove(key)
                if (image != null) {
                    failedTiles.remove(key)
                    cacheTile(key, image)
                } else {
                    rememberFailure(key)
                }
            }
            if (image != null) withContext(Dispatchers.Main) { onTileLoaded() }
        }
    }

    /**
     * MapKit repaints on every gesture frame, so a tile that just failed would otherwise be
     * requested hundreds of times per second. Retrying after [TILE_RETRY_AFTER_FAILURE] keeps a
     * transient provider error from blanking a tile for the lifetime of the overlay.
     */
    private fun shouldRequest(key: String): Boolean {
        val failedAt = failedTiles[key] ?: return true
        if (failedAt.elapsedNow() < TILE_RETRY_AFTER_FAILURE) return false
        failedTiles.remove(key)
        return true
    }

    private fun rememberFailure(key: String) {
        failedTiles.remove(key)
        failedTiles[key] = timeSource.markNow()
        while (failedTiles.size > MAX_TRACKED_FAILURES) {
            val oldestKey = failedTiles.keys.firstOrNull() ?: break
            failedTiles.remove(oldestKey)
        }
    }

    /**
     * Loads and decodes a single tile off the main thread. A provider failure means "no tile": the
     * base map shows through and nothing is thrown.
     *
     * @return The decoded image, or `null` when the provider has no tile or returned undecodable
     *   bytes
     */
    suspend fun loadTile(zoom: Int, x: Int, y: Int): UIImage? =
        withContext(Dispatchers.Default) {
            provider.bytesOrNull(zoom, x, y)?.let { UIImage.imageWithData(it.toNSData()) }
        }

    private fun cacheTile(key: String, image: UIImage?) {
        tiles.remove(key)
        tiles[key] = image
        while (tiles.size > maxCachedTiles) {
            val oldestKey = tiles.keys.firstOrNull() ?: break
            tiles.remove(oldestKey)
        }
    }

    private fun tileKey(zoom: Int, x: Int, y: Int): String = "$zoom/$x/$y"
}

/**
 * Draws the tiles of an [AppleMapsTileOverlay]: cached tiles are drawn, missing ones are requested
 * and only their own map rect is repainted once they arrive.
 *
 * @param tileOverlay Overlay whose tiles are drawn; held strongly because
 *   `MKOverlayRenderer.overlay` is a weak reference
 */
@OptIn(ExperimentalForeignApi::class)
internal class AppleMapsTileOverlayRenderer(private val tileOverlay: AppleMapsTileOverlay) :
    MKOverlayRenderer(overlay = tileOverlay) {

    override fun drawMapRect(
        mapRect: CValue<MKMapRect>,
        zoomScale: Double,
        inContext: CGContextRef?,
    ) {
        val context = inContext ?: return
        val overlayRect = tileOverlay.boundingMapRect()
        val originX = overlayRect.useContents { origin.x }
        val originY = overlayRect.useContents { origin.y }
        val (minX, minY, maxX, maxY) =
            mapRect.useContents {
                listOf(origin.x, origin.y, origin.x + size.width, origin.y + size.height)
            }

        val spans =
            TileGrid.spans(
                tileSize = tileOverlay.tileSize,
                zoomScale = zoomScale,
                originX = originX,
                originY = originY,
                minX = minX,
                minY = minY,
                maxX = maxX,
                maxY = maxY,
            )
        spans.forEach { span ->
            val spanRect = MKMapRectMake(span.originX, span.originY, span.size, span.size)
            val image = tileOverlay.cachedTile(span.zoom, span.column, span.row)
            if (image != null) {
                drawTile(image, rectForMapRect(spanRect), context)
            } else {
                tileOverlay.loadTileIfNeeded(span.zoom, span.column, span.row) {
                    setNeedsDisplayInMapRect(spanRect)
                }
            }
        }
    }

    /**
     * The context is pushed so that [UIImage.drawInRect] uses the flipped coordinate system the
     * image was decoded in; drawing straight into MapKit's CoreGraphics context would paint every
     * tile upside down.
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

/** Copies the bytes of this array into an [NSData] owned by the caller. */
@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { pinned -> NSData.dataWithBytes(pinned.addressOf(0), size.toULong()) }
    }
