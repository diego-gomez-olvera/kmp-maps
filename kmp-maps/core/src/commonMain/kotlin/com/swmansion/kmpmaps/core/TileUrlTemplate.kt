package com.swmansion.kmpmaps.core

/**
 * The `{x}`, `{y}` and `{z}` replacement rules of a [TileSource.UrlTemplate].
 *
 * Kept out of the platform code so the contract is written once, tested once, and shared by the
 * targets that build a URL themselves (Android) and the ones that hand the template to a map engine
 * (Apple Maps, desktop).
 */
internal object TileUrlTemplate {
    /** Placeholders a template has to contain. */
    val placeholders: List<String> = listOf("{x}", "{y}", "{z}")

    /**
     * Checks that [template] can be resolved without producing a broken URL.
     *
     * @param template Tile URL to check
     * @throws IllegalArgumentException when it is blank or misses a placeholder
     */
    fun requireValid(template: String) {
        require(template.isNotBlank()) { "template must not be blank." }
        placeholders.forEach { placeholder ->
            require(template.contains(placeholder)) {
                "template must contain $placeholder, was $template."
            }
        }
    }

    /**
     * Returns [template] with the tile coordinates substituted.
     *
     * Every occurrence is replaced, and nothing else is touched: query strings such as
     * `?key=a&layer=b` and percent-encoded values survive unchanged.
     *
     * @param template Tile URL containing the placeholders
     * @param zoom Zoom level of the tile
     * @param x Column of the tile within [zoom]
     * @param y Row of the tile within [zoom]
     * @return The URL of that tile
     */
    fun resolve(template: String, zoom: Int, x: Int, y: Int): String =
        template
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
            .replace("{z}", zoom.toString())
}
