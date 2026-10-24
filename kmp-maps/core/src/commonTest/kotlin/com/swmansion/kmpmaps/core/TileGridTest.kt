package com.swmansion.kmpmaps.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TileGridTest {

    @Test
    fun zoomLevelOfTheWholeWorldAtScaleOne() {
        // 2^28 map points wide, 256 point tiles: 2^20 tiles per axis at zoomScale 1.
        assertEquals(20, TileGrid.zoomLevel(tileSize = 256, zoomScale = 1.0))
    }

    @Test
    fun everyDoublingOfTheScaleAddsOneZoomLevel() {
        assertEquals(21, TileGrid.zoomLevel(tileSize = 256, zoomScale = 2.0))
        assertEquals(22, TileGrid.zoomLevel(tileSize = 256, zoomScale = 4.0))
        assertEquals(19, TileGrid.zoomLevel(tileSize = 256, zoomScale = 0.5))
    }

    @Test
    fun biggerTilesCoverTheWorldInFewerLevels() {
        assertEquals(19, TileGrid.zoomLevel(tileSize = 512, zoomScale = 1.0))
    }

    @Test
    fun zoomLevelIsMonotonicInTheScale() {
        val levels = listOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0).map { TileGrid.zoomLevel(256, it) }
        assertEquals(levels.sorted(), levels)
    }

    @Test
    fun zoomLevelIsNeverNegative() {
        assertTrue(TileGrid.zoomLevel(tileSize = 256, zoomScale = 1e-9) >= 0)
    }

    @Test
    fun rejectsUnusableInputs() {
        assertFailsWith<IllegalArgumentException> {
            TileGrid.zoomLevel(tileSize = 0, zoomScale = 1.0)
        }
        assertFailsWith<IllegalArgumentException> {
            TileGrid.zoomLevel(tileSize = 256, zoomScale = 0.0)
        }
        assertFailsWith<IllegalArgumentException> { TileGrid.zoomLevel(256, Double.NaN) }
        assertFailsWith<IllegalArgumentException> {
            TileGrid.zoomLevel(256, Double.POSITIVE_INFINITY)
        }
    }

    @Test
    fun oneTileViewportYieldsOneSpan() {
        val spans = spans(minX = 0.0, minY = 0.0, maxX = 255.0, maxY = 255.0)
        assertEquals(1, spans.size)
        assertEquals(0, spans.single().column)
        assertEquals(0, spans.single().row)
        assertEquals(0.0, spans.single().originX, ABSOLUTE_TOLERANCE)
        assertEquals(0.0, spans.single().originY, ABSOLUTE_TOLERANCE)
        assertEquals(256.0, spans.single().size, ABSOLUTE_TOLERANCE)
    }

    @Test
    fun twoByTwoViewportYieldsFourSpans() {
        val spans = spans(minX = 0.0, minY = 0.0, maxX = 511.0, maxY = 511.0)
        assertEquals(listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1), spans.map { it.column to it.row })
    }

    @Test
    fun spansStartAtTheRequestedTile() {
        val spans = spans(minX = 512.0, minY = 768.0, maxX = 767.0, maxY = 1023.0)
        assertEquals(1, spans.size)
        assertEquals(2, spans.single().column)
        assertEquals(3, spans.single().row)
        assertEquals(512.0, spans.single().originX, ABSOLUTE_TOLERANCE)
        assertEquals(768.0, spans.single().originY, ABSOLUTE_TOLERANCE)
    }

    @Test
    fun spansCoverTheViewportWithoutGapsOrOverlaps() {
        val minX = 1000.0
        val minY = 2000.0
        val maxX = 3543.0
        val maxY = 4211.0
        val spans = spans(minX = minX, minY = minY, maxX = maxX, maxY = maxY)

        val covered =
            spans.sumOf { span ->
                val width = (minOf(span.originX + span.size, maxX) - maxOf(span.originX, minX))
                val height = (minOf(span.originY + span.size, maxY) - maxOf(span.originY, minY))
                width * height
            }
        assertEquals((maxX - minX) * (maxY - minY), covered, (maxX - minX) * (maxY - minY) * 0.0001)
    }

    @Test
    fun tilesNeverLeaveTheWorldAtTheLeftEdge() {
        val spans = spans(minX = -1000.0, minY = -1000.0, maxX = 1000.0, maxY = 1000.0)
        assertTrue(spans.isNotEmpty())
        spans.forEach { span ->
            assertTrue(span.column >= 0, "column ${span.column}")
            assertTrue(span.row >= 0, "row ${span.row}")
        }
    }

    @Test
    fun tilesNeverLeaveTheWorldAtTheRightEdge() {
        val world = TileGrid.MAP_WORLD_WIDTH
        val spans =
            spans(
                minX = world - 100.0,
                minY = world - 100.0,
                maxX = world + 100.0,
                maxY = world + 100.0,
            )
        val tilesPerAxis = 1L shl TileGrid.zoomLevel(tileSize = 256, zoomScale = 1.0)
        assertTrue(spans.isNotEmpty())
        spans.forEach { span ->
            assertTrue(span.column < tilesPerAxis, "column ${span.column}")
            assertTrue(span.row < tilesPerAxis, "row ${span.row}")
        }
    }

    @Test
    fun capsTheNumberOfSpansForAnAbsurdViewport() {
        val world = TileGrid.MAP_WORLD_WIDTH
        val spans = spans(minX = 0.0, minY = 0.0, maxX = world, maxY = world)
        assertEquals(TileGrid.MAX_SPANS, spans.size)
    }

    @Test
    fun capsTheZoomLevel() {
        assertEquals(TileGrid.MAX_ZOOM_LEVEL, TileGrid.zoomLevel(tileSize = 256, zoomScale = 1e30))
    }

    @Test
    fun returnsNothingForAnUnusableScale() {
        assertTrue(
            spans(minX = 0.0, minY = 0.0, maxX = 100.0, maxY = 100.0, zoomScale = 0.0).isEmpty()
        )
        assertTrue(spans(0.0, 0.0, 100.0, 100.0, zoomScale = Double.NaN).isEmpty())
    }

    private fun spans(
        minX: Double,
        minY: Double,
        maxX: Double,
        maxY: Double,
        zoomScale: Double = 1.0,
    ): List<TileSpan> =
        TileGrid.spans(
            tileSize = 256,
            zoomScale = zoomScale,
            originX = 0.0,
            originY = 0.0,
            minX = minX,
            minY = minY,
            maxX = maxX,
            maxY = maxY,
        )

    private companion object {
        const val ABSOLUTE_TOLERANCE = 1e-9
    }
}
