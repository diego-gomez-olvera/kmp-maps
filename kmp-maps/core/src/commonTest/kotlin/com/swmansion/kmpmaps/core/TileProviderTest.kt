package com.swmansion.kmpmaps.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class TileProviderTest {

    @Test
    fun returnsTheBytesOfASuccessfulRequest() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3)
        assertContentEquals(bytes, TileProvider { _, _, _ -> bytes }.bytesOrNull(1, 2, 3))
    }

    @Test
    fun aFailedRequestMeansNoTile() = runBlocking {
        val failing = TileProvider { _, _, _ -> throw IllegalStateException("tiles are down") }
        assertNull(failing.bytesOrNull(1, 2, 3))
    }

    @Test
    fun aMissingTileStaysMissing() = runBlocking {
        assertNull(TileProvider { _, _, _ -> null }.bytesOrNull(1, 2, 3))
    }

    @Test
    fun cancellationIsRethrown() {
        val cancelled = TileProvider { _, _, _ -> throw CancellationException("scope gone") }
        assertFailsWith<CancellationException> { runBlocking { cancelled.bytesOrNull(1, 2, 3) } }
    }

    @Test
    fun errorsAreNotSwallowed() {
        val broken = TileProvider { _, _, _ -> throw AssertionError("out of memory, really") }
        assertFailsWith<AssertionError> { runBlocking { broken.bytesOrNull(1, 2, 3) } }
    }

    @Test
    fun theCoordinatesOfTheRequestReachTheProvider() = runBlocking {
        var requested: Triple<Int, Int, Int>? = null
        val recording = TileProvider { zoom, x, y ->
            requested = Triple(zoom, x, y)
            null
        }
        recording.bytesOrNull(zoom = 13, x = 4207, y = 2693)
        assertEquals(Triple(13, 4207, 2693), requested)
    }
}
