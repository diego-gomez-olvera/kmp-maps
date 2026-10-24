package com.swmansion.kmpmaps.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidMapsTileProviderTest {

    @Test
    fun theProvidedBytesBecomeADefaultSizedTile() {
        val bytes = byteArrayOf(9, 8, 7)

        val tile = AndroidMapsTileProvider { _, _, _ -> bytes }.getTile(x = 1, y = 2, zoom = 3)

        assertContentEquals(bytes, tile?.data)
        assertEquals(DEFAULT_TILE_SIZE, tile?.width)
        assertEquals(DEFAULT_TILE_SIZE, tile?.height)
    }

    @Test
    fun theProviderIsAskedWithZoomColumnAndRowInThatOrder() {
        var asked: Triple<Int, Int, Int>? = null

        AndroidMapsTileProvider { zoom, x, y ->
                asked = Triple(zoom, x, y)
                null
            }
            .getTile(x = 1, y = 2, zoom = 3)

        assertEquals(Triple(3, 1, 2), asked)
    }

    @Test
    fun aMissingTileIsReportedAsNull() {
        assertNull(AndroidMapsTileProvider { _, _, _ -> null }.getTile(0, 0, 0))
    }

    @Test
    fun aFailingProviderIsReportedAsNull() {
        val provider = AndroidMapsTileProvider { _, _, _ -> error("no network") }

        assertNull(provider.getTile(0, 0, 0))
    }
}
