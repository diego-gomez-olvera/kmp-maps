package com.swmansion.kmpmaps.core

import com.google.android.gms.maps.model.TileProvider as GoogleTileProvider
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class AndroidUrlTileProviderTest {
    private lateinit var server: HttpServer
    private var status = 200
    private var body = ByteArray(0)
    private var requestedPath: String? = null
    private var requestedUserAgent: String? = null

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            requestedPath = exchange.requestURI.path
            requestedUserAgent = exchange.requestHeaders.getFirst("User-Agent")
            if (body.isEmpty()) {
                exchange.sendResponseHeaders(status, -1)
            } else {
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.start()
    }

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    private fun provider(
        tileSize: Int = 256,
        template: String = "http://127.0.0.1:${server.address.port}/{z}/{x}/{y}.png",
    ) = AndroidUrlTileProvider(template, tileSize, "com.example.app")

    @Test
    fun theRequestedUrlResolvesZoomColumnAndRowFromTheSdkArguments() {
        status = 404

        provider().getTile(x = 5, y = 7, zoom = 3)

        assertEquals("/3/5/7.png", requestedPath)
    }

    @Test
    fun theUserAgentIdentifiesTheHostApplication() {
        status = 404

        provider().getTile(x = 0, y = 0, zoom = 0)

        assertEquals("kmp-maps (com.example.app)", requestedUserAgent)
    }

    @Test
    fun anOkResponseBecomesATileWithTheBodyAndTheConfiguredSize() {
        status = 200
        body = byteArrayOf(1, 2, 3, 4)

        val tile = provider(tileSize = 512).getTile(x = 1, y = 1, zoom = 1)

        assertEquals(512, tile?.width)
        assertEquals(512, tile?.height)
        assertContentEquals(body, tile?.data)
    }

    @Test
    fun notFoundTilesAreReportedAsNoTile() {
        status = 404

        assertSame(GoogleTileProvider.NO_TILE, provider().getTile(x = 1, y = 1, zoom = 1))
    }

    @Test
    fun serverErrorsAreReportedAsNullSoTheSdkRetriesLater() {
        status = 500

        assertNull(provider().getTile(x = 1, y = 1, zoom = 1))
    }

    @Test
    fun aConnectionFailureIsReportedAsNull() {
        val closedPort = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }

        val tile = provider(template = "http://127.0.0.1:$closedPort/{z}/{x}/{y}").getTile(0, 0, 0)

        assertNull(tile)
    }
}
