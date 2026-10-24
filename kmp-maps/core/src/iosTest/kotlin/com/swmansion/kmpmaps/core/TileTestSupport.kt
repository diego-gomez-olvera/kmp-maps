package com.swmansion.kmpmaps.core

import kotlin.concurrent.AtomicInt
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.fail
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate

private const val WAIT_TIMEOUT_SECONDS = 10.0
private const val RUN_LOOP_SLICE_SECONDS = 0.01

/** A valid 1x1 PNG, decodable by [AppleMapsTileOverlay]. */
@OptIn(ExperimentalEncodingApi::class)
internal val onePixelPng: ByteArray =
    Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    )

/** Tile provider that counts its calls and answers with [respond]. */
internal class CountingTileProvider(
    private val respond: suspend (zoom: Int, x: Int, y: Int) -> ByteArray? = { _, _, _ ->
        onePixelPng
    }
) : TileProvider {
    private val counter = AtomicInt(0)

    val calls: Int
        get() = counter.value

    override suspend fun tile(zoom: Int, x: Int, y: Int): ByteArray? {
        counter.incrementAndGet()
        return respond(zoom, x, y)
    }
}

/** Counts `onTileLoaded` invocations and records whether any ran off the main thread. */
internal class CallbackCounter {
    private val counter = AtomicInt(0)
    private val offMainThread = AtomicInt(0)

    val count: Int
        get() = counter.value

    val ranOffMainThread: Boolean
        get() = offMainThread.value > 0

    fun record() {
        if (!NSThread.isMainThread) offMainThread.incrementAndGet()
        counter.incrementAndGet()
    }
}

internal fun newTestScope(): CoroutineScope = CoroutineScope(Job() + Dispatchers.Default)

/** Spins the main run loop, which delivers `Dispatchers.Main` work, until [condition] holds. */
internal fun waitUntil(description: String, condition: () -> Boolean) {
    val start = TimeSource.Monotonic.markNow()
    while (!condition()) {
        if (start.elapsedNow().inWholeMilliseconds > WAIT_TIMEOUT_SECONDS * 1000) {
            fail("Timed out waiting for $description")
        }
        val slice = NSDate.dateWithTimeIntervalSinceNow(RUN_LOOP_SLICE_SECONDS)
        NSRunLoop.mainRunLoop.runUntilDate(slice)
    }
}

/** Blocks the main thread, so only for loads that never post to it (failures, cancellations). */
internal fun CoroutineScope.settle() {
    val job = coroutineContext[Job] ?: return
    runBlocking { job.children.toList().joinAll() }
}

internal fun gate(): CompletableDeferred<Unit> = CompletableDeferred()
