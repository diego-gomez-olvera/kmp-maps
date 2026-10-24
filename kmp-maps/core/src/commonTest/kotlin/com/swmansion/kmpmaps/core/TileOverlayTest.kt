package com.swmansion.kmpmaps.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TileOverlayTest {

    private val provider = TileProvider { _, _, _ -> null }

    @Test
    fun defaultsAreFullOpacityVisibleAtTheDefaultTileSize() {
        val overlay = TileOverlay(urlTemplate = "https://tiles.test/{z}/{x}/{y}.png")
        assertEquals(1f, overlay.opacity)
        assertTrue(overlay.visible)
        assertEquals(DEFAULT_TILE_SIZE, overlay.tileSize)
        assertEquals(0f, overlay.zIndex)
    }

    @Test
    fun rejectsOpacityOutsideTheRange() {
        assertFailsWith<IllegalArgumentException> {
            TileOverlay(provider = provider, opacity = -0.1f)
        }
        assertFailsWith<IllegalArgumentException> {
            TileOverlay(provider = provider, opacity = 1.1f)
        }
    }

    @Test
    fun acceptsTheEdgeOpacities() {
        assertEquals(0f, TileOverlay(provider = provider, opacity = 0f).opacity)
        assertEquals(1f, TileOverlay(provider = provider, opacity = 1f).opacity)
    }

    @Test
    fun rejectsNonPositiveTileSize() {
        assertFailsWith<IllegalArgumentException> { TileOverlay(provider = provider, tileSize = 0) }
    }

    @Test
    fun providerConstructorWrapsAByteSource() {
        val overlay = TileOverlay(provider = provider, opacity = 0.5f)
        assertEquals(TileSource.Bytes(provider), overlay.source)
        assertEquals(0.5f, overlay.opacity)
    }

    @Test
    fun urlConstructorWrapsATemplateSource() {
        val overlay = TileOverlay(urlTemplate = "https://tiles.test/{z}/{x}/{y}.png")
        assertEquals(TileSource.UrlTemplate("https://tiles.test/{z}/{x}/{y}.png"), overlay.source)
    }

    @Test
    fun layersSharingOneProviderAreEqualSoARendererCanReuseThem() {
        val sameProvider = TileProvider { _, _, _ -> null }
        assertEquals(TileOverlay(provider = sameProvider), TileOverlay(provider = sameProvider))
        assertNotEquals(
            TileOverlay(provider = sameProvider),
            TileOverlay(provider = TileProvider { _, _, _ -> null }),
        )
    }

    @Test
    fun layersDifferingOnlyInPresentationAreNotEqual() {
        val base = TileOverlay(urlTemplate = "https://tiles.test/{z}/{x}/{y}.png", opacity = 0.5f)
        assertNotEquals(base, base.copy(opacity = 0.6f))
        assertNotEquals(base, base.copy(visible = false))
        assertNotEquals(base, base.copy(tileSize = 512))
        assertNotEquals(base, base.copy(zIndex = 1f))
    }
}
