package com.swmansion.kmpmaps.core

/** Default edge length, in points, of a tile drawn by a [TileOverlay]. */
public const val DEFAULT_TILE_SIZE: Int = 256

/** Opacity of a [TileOverlay] that is fully drawn over the base map. */
internal const val DEFAULT_TILE_OPACITY: Float = 1f

/**
 * Supplies the tiles of a [TileOverlay].
 *
 * Use it when the tiles are not plain `{z}/{x}/{y}` Web Mercator images the map can fetch itself —
 * for example a service that publishes a different tile matrix, where the caller projects the tile
 * and hands over the finished bytes. For the plain case, prefer [TileSource.UrlTemplate]: it works
 * on every target, desktop included.
 *
 * The map requests tiles in the Web Mercator XYZ scheme used by Apple Maps and Google Maps: [zoom]
 * selects the level, and [x] / [y] select the tile within that level, with [y] growing southwards.
 * Requests are made off the main thread, but they are not debounced: the map may ask for the same
 * coordinates again after the user scrolls away and back, so an implementation that downloads tiles
 * over the network is expected to cache them. A provider must never block on the main thread, and
 * it is called from a coroutine bound to the map's lifetime, so it can be cancelled.
 *
 * The returned image is drawn into a square of [TileOverlay.tileSize] **points**, so a provider
 * that serves high density pixels (512 px for a 256 point tile) stays sharp on high-density
 * screens. A tile that cannot be loaded — `null`, a thrown exception or undecodable bytes — leaves
 * the base map visible and is requested again later.
 *
 * ```kotlin
 * val historicalLayer =
 *     TileOverlay(
 *         provider =
 *             TileProvider { zoom, x, y ->
 *                 reprojectHistoricalTile(zoom, x, y)
 *             }
 *     )
 * ```
 */
public fun interface TileProvider {
    /**
     * Returns the encoded image for the tile at [zoom], [x] and [y], or `null` for "no tile here".
     *
     * @param zoom The zoom level of the requested tile
     * @param x The column of the requested tile within [zoom]
     * @param y The row of the requested tile within [zoom]
     */
    public suspend fun tile(zoom: Int, x: Int, y: Int): ByteArray?
}

/** Where the tiles of a [TileOverlay] come from. */
public sealed interface TileSource {
    /**
     * Tiles handed over as encoded bytes by [provider].
     *
     * Supported on Android and on Apple Maps (iOS). Desktop (JVM) ignores this source, because its
     * Google Maps JS backend can only point the map at a tile URL.
     *
     * @property provider Supplies the tile images
     */
    public data class Bytes(public val provider: TileProvider) : TileSource

    /**
     * Web Mercator tiles the map fetches itself from [template], where `{x}`, `{y}` and `{z}` are
     * replaced with the requested tile (Apple Maps also understands `{scale}`).
     *
     * Works on every target, desktop included, and needs no code on our side: it is the right
     * choice for standard XYZ services such as OpenStreetMap, satellite imagery or a WMS-to-XYZ
     * proxy.
     *
     * @property template Tile URL, for example `https://tile.openstreetmap.org/{z}/{x}/{y}.png`
     */
    public data class UrlTemplate(public val template: String) : TileSource {
        init {
            TileUrlTemplate.requireValid(template)
        }
    }
}

/**
 * A raster tile layer drawn over the base map.
 *
 * Use it for cartography that the base map does not ship, such as satellite imagery, cadastral maps
 * or historical series. Tiles are painted in place of the base map content they cover, so the map's
 * own labels and markers stay visible on top.
 *
 * ```kotlin
 * // A standard XYZ source, available on every target.
 * val openStreetMap = TileOverlay(urlTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png")
 *
 * // Tiles the caller produces itself, on Android and Apple Maps.
 * val reprojected = TileOverlay(provider = TileProvider { zoom, x, y -> load(zoom, x, y) })
 * ```
 *
 * @property source Where the tiles come from
 * @property opacity Layer opacity in the range `0f..1f`, so the layer can be cross-faded over the
 *   base map
 * @property visible Whether the layer is drawn. Toggling it removes and re-adds the layer, which
 *   requests the tiles again
 * @property tileSize Edge length, in points, of the square each tile is drawn into. Apple Maps
 *   accepts other sizes than [DEFAULT_TILE_SIZE] and scales larger images down, so a
 *   [TileSource.Bytes] provider can serve 512 px for a 256 point tile to stay sharp on high-density
 *   screens. Google Maps requires exactly [DEFAULT_TILE_SIZE] for a byte source
 * @property zIndex Drawing order relative to other layers, higher draws on top. Ignored by Apple
 *   Maps
 */
public data class TileOverlay(
    val source: TileSource,
    val opacity: Float = DEFAULT_TILE_OPACITY,
    val visible: Boolean = true,
    val tileSize: Int = DEFAULT_TILE_SIZE,
    val zIndex: Float = 0f,
) {
    /**
     * Creates a layer whose tiles are produced by [provider].
     *
     * @param provider Supplies the tile images
     * @param opacity Layer opacity in the range `0f..1f`
     * @param visible Whether the layer is drawn
     * @param tileSize Edge length, in points, of the square each tile is drawn into
     * @param zIndex Drawing order relative to other layers
     */
    public constructor(
        provider: TileProvider,
        opacity: Float = DEFAULT_TILE_OPACITY,
        visible: Boolean = true,
        tileSize: Int = DEFAULT_TILE_SIZE,
        zIndex: Float = 0f,
    ) : this(
        source = TileSource.Bytes(provider),
        opacity = opacity,
        visible = visible,
        tileSize = tileSize,
        zIndex = zIndex,
    )

    /**
     * Creates a layer whose tiles are fetched from [urlTemplate].
     *
     * @param urlTemplate Tile URL with `{x}`, `{y}` and `{z}` placeholders
     * @param opacity Layer opacity in the range `0f..1f`
     * @param visible Whether the layer is drawn
     * @param tileSize Edge length, in points, of each tile
     * @param zIndex Drawing order relative to other layers
     */
    public constructor(
        urlTemplate: String,
        opacity: Float = DEFAULT_TILE_OPACITY,
        visible: Boolean = true,
        tileSize: Int = DEFAULT_TILE_SIZE,
        zIndex: Float = 0f,
    ) : this(
        source = TileSource.UrlTemplate(urlTemplate),
        opacity = opacity,
        visible = visible,
        tileSize = tileSize,
        zIndex = zIndex,
    )

    init {
        require(opacity in 0f..1f) { "opacity must be in the 0f..1f range, was $opacity." }
        require(tileSize > 0) { "tileSize must be positive, was $tileSize." }
    }
}
