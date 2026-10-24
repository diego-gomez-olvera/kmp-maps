package com.swmansion.kmpmaps.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.MapKit.MKCircle
import platform.MapKit.MKMapView
import platform.MapKit.MKOverlayProtocol
import platform.MapKit.MKOverlayRenderer
import platform.MapKit.addOverlay
import platform.MapKit.overlays

@OptIn(ExperimentalForeignApi::class)
class UpdateAppleMapsTileOverlaysTest {

    private val scope = newTestScope()
    private val map = MKMapView()
    private val styles = mutableMapOf<MKOverlayProtocol, TileOverlay>()
    private val renderers = mutableMapOf<MKOverlayProtocol, MKOverlayRenderer>()

    private fun layer(provider: TileProvider, opacity: Float = 1f, visible: Boolean = true) =
        TileOverlay(provider, opacity = opacity, visible = visible)

    private fun update(vararg layers: TileOverlay) =
        map.updateAppleMapsTileOverlays(layers.toList(), scope, styles, renderers)

    private fun nativeOverlayOf(layer: TileOverlay): MKOverlayProtocol =
        styles.entries.first { it.value == layer }.key

    @Test
    fun anOpacityOnlyChangeKeepsTheNativeOverlay() {
        val provider = CountingTileProvider()
        update(layer(provider, opacity = 1f))
        val native = styles.keys.single()

        update(layer(provider, opacity = 0.5f))

        assertSame(native, styles.keys.single())
        assertEquals(1, map.overlays.size)
    }

    @Test
    fun anInvisibleLayerIsRemovedFromTheMap() {
        val provider = CountingTileProvider()
        update(layer(provider))
        assertEquals(1, map.overlays.size)

        update(layer(provider, visible = false))

        assertEquals(0, map.overlays.size)
        assertEquals(0, styles.size)
    }

    @Test
    fun reorderingLayersReordersTheMapOverlaysAndKeepsTheirNativeObjects() {
        val first = layer(CountingTileProvider())
        val second = layer(CountingTileProvider())
        update(first, second)
        val nativeFirst = nativeOverlayOf(first)
        val nativeSecond = nativeOverlayOf(second)
        assertEquals(listOf(nativeFirst, nativeSecond), map.overlays)

        update(second, first)

        assertEquals(listOf(nativeSecond, nativeFirst), map.overlays)
    }

    @Test
    fun aNonTileOverlayKeepsItsIndexWhenTileLayersAreReordered() {
        val circle = MKCircle.circleWithCenterCoordinate(CLLocationCoordinate2DMake(0.0, 0.0), 10.0)
        map.addOverlay(circle)
        val first = layer(CountingTileProvider())
        val second = layer(CountingTileProvider())
        update(first, second)
        assertEquals(circle, map.overlays.first())

        update(second, first)

        assertEquals(circle, map.overlays.first())
        assertEquals(listOf(nativeOverlayOf(second), nativeOverlayOf(first)), map.overlays.drop(1))
    }
}
