package com.swmansion.kmpmaps.core

import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

/**
 * One tile of a [TileOverlay], placed on the tile grid of the map it is drawn on.
 *
 * Every coordinate is a map point, the unit Compose Multiplatform map engines use for raster tiles:
 * the whole world is [TileGrid.MAP_WORLD_WIDTH] of them wide.
 *
 * @property zoom Zoom level the tile belongs to
 * @property column Column of the tile within [zoom]
 * @property row Row of the tile within [zoom]
 * @property originX Left edge of the tile in map points
 * @property originY Top edge of the tile in map points
 * @property size Edge length of the tile in map points
 */
internal data class TileSpan(
    val zoom: Int,
    val column: Int,
    val row: Int,
    val originX: Double,
    val originY: Double,
    val size: Double,
)

/**
 * Maps the area a map asks to repaint onto the tiles that cover it.
 *
 * This is the arithmetic an overlay renderer needs and the one part of the tile drawing that can be
 * checked without a device, so it lives here instead of inside a platform renderer. Tiles never
 * leave the world: indices are clamped to `0 until 2^zoom`.
 */
internal object TileGrid {
    /** Width of the world in map points, the same constant Apple Maps and Google Maps use. */
    const val MAP_WORLD_WIDTH: Double = 268_435_456.0

    /**
     * Deepest zoom level that is taken into account.
     *
     * No tile service publishes anything close to it (Apple and Google stop around 22), and it
     * keeps the tile indices of an absurd scale inside the range a `Long` shift can express.
     */
    const val MAX_ZOOM_LEVEL: Int = 30

    /**
     * Number of tiles a single call returns at most.
     *
     * A renderer asks for the area it is about to draw, so a few dozen tiles is normal. A rect that
     * covers the world at a deep scale would otherwise ask for billions of indices and stall the
     * drawing thread; the map engine paints the rest on its next pass.
     */
    const val MAX_SPANS: Int = 4096

    /**
     * Approximates the zoom level a map is drawn at.
     *
     * Map engines expose the scale (map points per screen point) rather than the zoom level, and
     * the world holds `MAP_WORLD_WIDTH / tileSize` tiles per axis at its deepest level, so every
     * doubling of [zoomScale] adds one level on top of it. The scale is rounded to the nearest
     * level, which is what a map engine does when it picks the tiles to show.
     *
     * @param tileSize Edge length of the tiles, in points
     * @param zoomScale Map points per screen point the map is drawn at
     * @return The zoom level to request tiles for, never negative
     */
    fun zoomLevel(tileSize: Int, zoomScale: Double): Int {
        require(tileSize > 0) { "tileSize must be positive, was $tileSize." }
        require(zoomScale.isFinite() && zoomScale > 0.0) {
            "zoomScale must be positive and finite, was $zoomScale."
        }
        val tilesPerAxisAtWorldWidth = MAP_WORLD_WIDTH / tileSize
        val roundedScaleExponent = floor(log2(zoomScale) + 0.5)
        val level = max(0, floor(log2(tilesPerAxisAtWorldWidth) + roundedScaleExponent).toInt())
        return level.coerceAtMost(MAX_ZOOM_LEVEL)
    }

    /**
     * Tile index that contains [coordinate], saturated instead of wrapping when the division is
     * larger than a `Long` or not a number at all.
     */
    private fun indexOf(coordinate: Double, origin: Double, mapPointsPerTile: Double): Long =
        floor((coordinate - origin) / mapPointsPerTile).toLong()

    /**
     * Lists the tiles that cover the area between `[minX]..[maxX]` and `[minY]..[maxY]`.
     *
     * @param tileSize Edge length of the tiles, in points
     * @param zoomScale Map points per screen point the map is drawn at
     * @param originX Left edge of the layer in map points, usually `0`
     * @param originY Top edge of the layer in map points, usually `0`
     * @param minX Left edge of the area to cover
     * @param minY Top edge of the area to cover
     * @param maxX Right edge of the area to cover
     * @param maxY Bottom edge of the area to cover
     * @return The covering tiles in column-major order, at most [MAX_SPANS] of them, empty when the
     *   scale is unusable
     */
    fun spans(
        tileSize: Int,
        zoomScale: Double,
        originX: Double,
        originY: Double,
        minX: Double,
        minY: Double,
        maxX: Double,
        maxY: Double,
    ): List<TileSpan> {
        if (!zoomScale.isFinite() || zoomScale <= 0.0) return emptyList()
        val mapPointsPerTile = tileSize / zoomScale
        if (!mapPointsPerTile.isFinite() || mapPointsPerTile <= 0.0) return emptyList()

        val zoom = zoomLevel(tileSize, zoomScale)
        val tilesPerAxis = 1L shl zoom
        val firstColumn = max(0L, indexOf(minX, originX, mapPointsPerTile))
        val lastColumn = min(tilesPerAxis - 1, indexOf(maxX, originX, mapPointsPerTile))
        val firstRow = max(0L, indexOf(minY, originY, mapPointsPerTile))
        val lastRow = min(tilesPerAxis - 1, indexOf(maxY, originY, mapPointsPerTile))
        if (firstColumn > lastColumn || firstRow > lastRow) return emptyList()

        val spans = mutableListOf<TileSpan>()
        for (column in firstColumn..lastColumn) {
            for (row in firstRow..lastRow) {
                spans +=
                    TileSpan(
                        zoom = zoom,
                        column = column.toInt(),
                        row = row.toInt(),
                        originX = originX + column * mapPointsPerTile,
                        originY = originY + row * mapPointsPerTile,
                        size = mapPointsPerTile,
                    )
                if (spans.size == MAX_SPANS) return spans
            }
        }
        return spans
    }
}
