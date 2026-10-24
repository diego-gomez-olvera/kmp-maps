package com.swmansion.kmpmaps.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

class AppleMapsTileOverlayTest {

    private val tileSizeForcingMinimumCache = 2048

    private fun overlay(
        provider: TileProvider,
        scope: CoroutineScope,
        tileSize: Int = 256,
        timeSource: TimeSource = TimeSource.Monotonic,
    ) = AppleMapsTileOverlay(provider, tileSize, scope, timeSource)

    private fun AppleMapsTileOverlay.loadAndWait(x: Int) {
        val loaded = CallbackCounter()
        loadTileIfNeeded(1, x, 0) { loaded.record() }
        waitUntil("tile $x to load") { loaded.count == 1 }
    }

    @Test
    fun aLoadedTileIsCachedAndNotRequestedAgain() {
        val provider = CountingTileProvider()
        val overlay = overlay(provider, newTestScope())

        overlay.loadAndWait(0)
        assertNotNull(overlay.cachedTile(1, 0, 0))

        val again = CallbackCounter()
        overlay.loadTileIfNeeded(1, 0, 0) { again.record() }
        waitUntil("the cached tile callback") { again.count == 1 }

        assertEquals(1, provider.calls)
    }

    @Test
    fun duplicateRequestsWhileATileIsLoadingCallTheProviderOnce() {
        val release = gate()
        val provider = CountingTileProvider { _, _, _ ->
            release.await()
            onePixelPng
        }
        val overlay = overlay(provider, newTestScope())
        val loaded = CallbackCounter()

        repeat(3) { overlay.loadTileIfNeeded(1, 0, 0) { loaded.record() } }
        waitUntil("the provider to be called") { provider.calls >= 1 }
        release.complete(Unit)
        waitUntil("the tile callback") { loaded.count >= 1 }

        assertEquals(1, provider.calls)
        assertEquals(1, loaded.count)
    }

    @Test
    fun theLeastRecentlyUsedTileIsEvictedWhenTheCacheIsFull() {
        val overlay = overlay(CountingTileProvider(), newTestScope(), tileSizeForcingMinimumCache)
        repeat(8) { overlay.loadAndWait(it) }

        assertNotNull(overlay.cachedTile(1, 0, 0))
        overlay.loadAndWait(8)

        assertNotNull(overlay.cachedTile(1, 0, 0))
        assertNull(overlay.cachedTile(1, 1, 0))
        assertNotNull(overlay.cachedTile(1, 8, 0))
    }

    @Test
    fun aFailedTileIsNotRequestedAgainBeforeTheRetryDelay() {
        val time = TestTimeSource()
        val scope = newTestScope()
        val provider = CountingTileProvider { _, _, _ -> null }
        val overlay = overlay(provider, scope, timeSource = time)

        overlay.loadTileIfNeeded(1, 0, 0) {}
        scope.settle()
        time += 29.seconds
        overlay.loadTileIfNeeded(1, 0, 0) {}
        scope.settle()

        assertEquals(1, provider.calls)
    }

    @Test
    fun aFailedTileIsRequestedAgainAfterTheRetryDelay() {
        val time = TestTimeSource()
        val scope = newTestScope()
        val provider = CountingTileProvider { _, _, _ -> null }
        val overlay = overlay(provider, scope, timeSource = time)

        overlay.loadTileIfNeeded(1, 0, 0) {}
        scope.settle()
        time += 31.seconds
        overlay.loadTileIfNeeded(1, 0, 0) {}
        scope.settle()

        assertEquals(2, provider.calls)
    }

    @Test
    fun aTileWhoseProviderThrowsIsNotRequestedAgainBeforeTheRetryDelay() {
        val time = TestTimeSource()
        val scope = newTestScope()
        val provider = CountingTileProvider { _, _, _ -> throw IllegalStateException("down") }
        val overlay = overlay(provider, scope, timeSource = time)

        overlay.loadTileIfNeeded(1, 0, 0) {}
        scope.settle()
        overlay.loadTileIfNeeded(1, 0, 0) {}
        scope.settle()

        assertEquals(1, provider.calls)
        assertNull(overlay.cachedTile(1, 0, 0))
    }

    @Test
    fun onTileLoadedRunsOnTheMainThreadAfterASuccessfulLoad() {
        val overlay = overlay(CountingTileProvider(), newTestScope())
        val loaded = CallbackCounter()

        overlay.loadTileIfNeeded(1, 0, 0) { loaded.record() }
        waitUntil("the tile callback") { loaded.count == 1 }

        assertFalse(loaded.ranOffMainThread)
    }

    @Test
    fun onTileLoadedRunsOnTheMainThreadWhenTheTileWasAlreadyCached() {
        val overlay = overlay(CountingTileProvider(), newTestScope())
        overlay.loadAndWait(0)
        val again = CallbackCounter()

        overlay.loadTileIfNeeded(1, 0, 0) { again.record() }
        waitUntil("the cached tile callback") { again.count == 1 }

        assertFalse(again.ranOffMainThread)
    }

    @Test
    fun cancellingTheScopeWhileATileLoadsLeavesNoCallback() {
        val release = gate()
        val provider = CountingTileProvider { _, _, _ ->
            release.await()
            onePixelPng
        }
        val scope = newTestScope()
        val overlay = overlay(provider, scope)
        val loaded = CallbackCounter()

        overlay.loadTileIfNeeded(1, 0, 0) { loaded.record() }
        waitUntil("the provider to be called") { provider.calls == 1 }
        scope.cancel()
        scope.settle()

        assertEquals(0, loaded.count)
        assertNull(overlay.cachedTile(1, 0, 0))
    }

    @Test
    fun aCancelledScopeNeverCallsTheProviderOrTheCallback() {
        val provider = CountingTileProvider()
        val scope = newTestScope()
        val overlay = overlay(provider, scope)
        val loaded = CallbackCounter()

        scope.cancel()
        overlay.loadTileIfNeeded(1, 0, 0) { loaded.record() }
        scope.settle()

        assertEquals(0, provider.calls)
        assertEquals(0, loaded.count)
        assertNull(overlay.cachedTile(1, 0, 0))
    }
}
