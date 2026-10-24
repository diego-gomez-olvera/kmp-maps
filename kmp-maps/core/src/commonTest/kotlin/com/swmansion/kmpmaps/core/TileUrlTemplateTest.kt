package com.swmansion.kmpmaps.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TileUrlTemplateTest {

    @Test
    fun rejectsBlankTemplate() {
        assertFailsWith<IllegalArgumentException> { TileSource.UrlTemplate("   ") }
    }

    @Test
    fun rejectsTemplateWithoutEachPlaceholder() {
        listOf(
                "https://tiles.test/{y}/{z}.png",
                "https://tiles.test/{x}/{z}.png",
                "https://tiles.test/{x}/{y}.png",
            )
            .forEach { template ->
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
            TileUrlTemplate.resolve(
                "https://tiles.test/{z}/{x}/{y}.png",
                zoom = 12,
                x = 2103,
                y = 1346,
            ),
        )
    }

    @Test
    fun resolvesEveryOccurrenceAndLeavesTheRestUntouched() {
        assertEquals(
            "https://tiles.test/7/5/9.png?z=7&x=5&y=9&layer=brt%20standaard",
            TileUrlTemplate.resolve(
                "https://tiles.test/{z}/{x}/{y}.png?z={z}&x={x}&y={y}&layer=brt%20standaard",
                zoom = 7,
                x = 5,
                y = 9,
            ),
        )
    }

    @Test
    fun keepsQueryStringsAndAmpersandsIntact() {
        assertEquals(
            "https://tiles.test/1.png?a=1&b=2&tile=3/4/5",
            TileUrlTemplate.resolve("https://tiles.test/1.png?a=1&b=2&tile={z}/{x}/{y}", 3, 4, 5),
        )
    }
}
