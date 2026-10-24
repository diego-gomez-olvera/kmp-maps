package com.swmansion.kmpmaps.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class TileOverlaysJsonTest {

    private val bytes = TileOverlay(provider = TileProvider { _, _, _ -> null })

    @Test
    fun serializesUrlTemplateLayersOnly() {
        val json = listOf(bytes, TileOverlay(urlTemplate = "https://tiles.test/{z}/{x}/{y}.png"))
        val layers = json.toTileOverlaysJson().parse()
        assertEquals(1, layers.size)
        assertEquals(
            "https://tiles.test/{z}/{x}/{y}.png",
            layers.single()["template"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun ordersLayersByZIndexSoLaterOnesDrawOnTop() {
        val layer = { name: String, zIndex: Float ->
            TileOverlay(urlTemplate = "https://tiles.test/$name/{z}/{x}/{y}.png", zIndex = zIndex)
        }
        val templates =
            listOf(layer("mid", 0f), layer("top", 2f), layer("bottom", -1f))
                .toTileOverlaysJson()
                .parse()
                .map { it["template"]?.jsonPrimitive?.content }
        assertEquals(
            listOf(
                "https://tiles.test/bottom/{z}/{x}/{y}.png",
                "https://tiles.test/mid/{z}/{x}/{y}.png",
                "https://tiles.test/top/{z}/{x}/{y}.png",
            ),
            templates,
        )
    }

    @Test
    fun carriesPresentationValues() {
        val layer =
            TileOverlay(
                urlTemplate = "https://tiles.test/{z}/{x}/{y}.png",
                opacity = 0.25f,
                visible = false,
                tileSize = 512,
                zIndex = 3f,
            )
        val json = listOf(layer).toTileOverlaysJson().parse().single()
        assertEquals(0.25f, json["opacity"]?.jsonPrimitive?.content?.toFloat())
        assertEquals("false", json["visible"]?.jsonPrimitive?.content)
        assertEquals(512, json["tileSize"]?.jsonPrimitive?.content?.toInt())
        assertEquals(3f, json["zIndex"]?.jsonPrimitive?.content?.toFloat())
    }

    @Test
    fun escapesQueryStringsAndQuotesSoTheJavaScriptCallCannotBreak() {
        val template = "https://tiles.test/{z}/{x}/{y}.png?a=1&b=\"two\"\\three"
        val json = listOf(TileOverlay(urlTemplate = template)).toTileOverlaysJson()
        assertTrue(json.startsWith("[") && json.endsWith("]"))
        assertEquals(template, json.parse().single()["template"]?.jsonPrimitive?.content)
    }

    @Test
    fun emptyListBecomesAnEmptyArray() {
        assertEquals("[]", emptyList<TileOverlay>().toTileOverlaysJson())
    }

    private fun String.parse(): List<JsonObject> =
        Json.parseToJsonElement(this).jsonArray.map { it as JsonObject }
}
