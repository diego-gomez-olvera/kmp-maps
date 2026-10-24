package com.swmansion.kmpmaps.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TileResponseTest {

    @Test
    fun okWithBodyIsATile() {
        assertEquals(TileResponseKind.TILE, classifyTileResponse(200, bodyIsEmpty = false))
    }

    @Test
    fun okWithEmptyBodyIsNoTile() {
        assertEquals(TileResponseKind.NO_TILE, classifyTileResponse(200, bodyIsEmpty = true))
    }

    @Test
    fun noContentIsNoTile() {
        assertEquals(TileResponseKind.NO_TILE, classifyTileResponse(204, bodyIsEmpty = true))
    }

    @Test
    fun notFoundIsNoTile() {
        assertEquals(TileResponseKind.NO_TILE, classifyTileResponse(404, bodyIsEmpty = false))
    }

    @Test
    fun goneIsNoTile() {
        assertEquals(TileResponseKind.NO_TILE, classifyTileResponse(410, bodyIsEmpty = false))
    }

    @Test
    fun serverErrorsAreRetried() {
        listOf(500, 502, 503, 504).forEach { status ->
            assertEquals(TileResponseKind.RETRY, classifyTileResponse(status, bodyIsEmpty = false))
        }
    }

    @Test
    fun tooManyRequestsIsRetried() {
        assertEquals(TileResponseKind.RETRY, classifyTileResponse(429, bodyIsEmpty = false))
    }

    @Test
    fun otherClientErrorsAreRetried() {
        listOf(400, 401, 403, 408).forEach { status ->
            assertEquals(TileResponseKind.RETRY, classifyTileResponse(status, bodyIsEmpty = true))
        }
    }
}
