package com.swmansion.kmpmaps.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TileUrlTest {

    @Test
    fun rejectsBlankTemplate() {
        assertFailsWith<IllegalArgumentException> { TileUrl.of("   ") }
        assertFailsWith<IllegalArgumentException> { TileSource.UrlTemplate("") }
    }

    @Test
    fun rejectsTemplateWithoutEachPlaceholder() {
        listOf(
                "https://tiles.test/{y}/{z}.png",
                "https://tiles.test/{x}/{z}.png",
                "https://tiles.test/{x}/{y}.png",
            )
            .forEach { template ->
                assertFailsWith<IllegalArgumentException>(template) { TileUrl.of(template) }
                assertFailsWith<IllegalArgumentException>(template) {
                    TileSource.UrlTemplate(template)
                }
            }
    }

    @Test
    fun acceptsTemplateWithAllPlaceholders() {
        TileSource.UrlTemplate("https://tiles.test/{z}/{x}/{y}.png")
    }

    @Test
    fun resolvesCoordinatesIntoTheTemplate() {
        assertEquals(
            "https://tiles.test/12/2103/1346.png",
            TileUrl.of("https://tiles.test/{z}/{x}/{y}.png").resolve(12, 2103, 1346),
        )
    }

    @Test
    fun resolvesEveryOccurrenceAndLeavesTheRestUntouched() {
        assertEquals(
            "https://tiles.test/7/5/9.png?z=7&x=5&y=9&layer=brt%20standaard",
            TileUrl.of("https://tiles.test/{z}/{x}/{y}.png?z={z}&x={x}&y={y}&layer=brt%20standaard")
                .resolve(7, 5, 9),
        )
    }

    @Test
    fun keepsQueryStringsAndAmpersandsIntact() {
        assertEquals(
            "https://tiles.test/1.png?a=1&b=2&tile=3/4/5",
            TileUrl.of("https://tiles.test/1.png?a=1&b=2&tile={z}/{x}/{y}").resolve(3, 4, 5),
        )
    }

    @Test
    fun leavesUnknownBracesAsLiteralText() {
        assertEquals(
            "https://tiles.test/9/1/2.png?filter={foo}&empty={}",
            TileUrl.of("https://tiles.test/{z}/{x}/{y}.png?filter={foo}&empty={}").resolve(9, 1, 2),
        )
    }

    @Test
    fun resolvesNumbersOfAnyLength() {
        assertEquals(
            "https://tiles.test/22/4194303/4194303.png",
            TileUrl.of("https://tiles.test/{z}/{x}/{y}.png").resolve(22, 4_194_303, 4_194_303),
        )
    }

    @Test
    fun matchesTheStraightforwardThreeReplacementResolveForEveryCase() {
        val templates =
            listOf(
                "https://tiles.test/{z}/{x}/{y}.png",
                "https://tiles.test/{x}/{y}/{z}.png?a=1&b=2",
                "https://tiles.test/{z}?x={x}&y={y}&z={z}",
                "https://tiles.test/tile-{z}-{x}-{y}.png?filter={foo}",
            )
        val cases =
            listOf(Triple(0, 0, 0), Triple(13, 4207, 2693), Triple(22, 4_194_303, 4_194_303))
        templates.forEach { template ->
            val url = TileUrl.of(template)
            cases.forEach { (zoom, x, y) ->
                assertEquals(
                    template
                        .replace("{x}", x.toString())
                        .replace("{y}", y.toString())
                        .replace("{z}", zoom.toString()),
                    url.resolve(zoom, x, y),
                    "template $template at $zoom/$x/$y",
                )
            }
        }
    }
}
