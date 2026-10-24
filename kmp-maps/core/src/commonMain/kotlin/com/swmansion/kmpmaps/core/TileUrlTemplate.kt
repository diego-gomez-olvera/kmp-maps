package com.swmansion.kmpmaps.core

/** Placeholders a [TileSource.UrlTemplate] has to contain. */
private val TILE_URL_PLACEHOLDERS = listOf("{x}", "{y}", "{z}")

/** How much longer a resolved tile URL can get than its template, in characters. */
private const val TILE_URL_LENGTH_ALLOWANCE = 16

/**
 * Checks that [template] can be resolved without producing a broken tile URL.
 *
 * @param template Tile URL to check
 * @throws IllegalArgumentException when it is blank or misses a placeholder
 */
internal fun requireValidTileUrl(template: String) {
    require(template.isNotBlank()) { "template must not be blank." }
    TILE_URL_PLACEHOLDERS.forEach { placeholder ->
        require(template.contains(placeholder)) {
            "template must contain $placeholder, was $template."
        }
    }
}

/**
 * Builds the URL of one tile by walking [template] once.
 *
 * `replace` is the obvious way to do this, but every call returns a new string, so three of them
 * allocate four strings per tile; on the JVM that costs about 125-150 ns against 37-56 ns here, and
 * the map SDKs resolve a URL for every visible tile while the map is panned.
 *
 * Only the placeholders above are substituted: any other braces are literal text, so a template may
 * carry a query value such as `?filter={foo}` untouched.
 *
 * @param zoom Zoom level of the tile
 * @param x Column of the tile within [zoom]
 * @param y Row of the tile within [zoom]
 * @return The URL of that tile
 */
internal fun String.resolveTileUrl(zoom: Int, x: Int, y: Int): String =
    buildString(length + TILE_URL_LENGTH_ALLOWANCE) {
        var index = 0
        while (index < this@resolveTileUrl.length) {
            val brace = this@resolveTileUrl.indexOf('{', index)
            if (brace < 0) {
                append(this@resolveTileUrl, index, this@resolveTileUrl.length)
                return@buildString
            }
            append(this@resolveTileUrl, index, brace)
            when {
                this@resolveTileUrl.startsWith("{x}", brace) -> {
                    append(x)
                    index = brace + PLACEHOLDER_LENGTH
                }
                this@resolveTileUrl.startsWith("{y}", brace) -> {
                    append(y)
                    index = brace + PLACEHOLDER_LENGTH
                }
                this@resolveTileUrl.startsWith("{z}", brace) -> {
                    append(zoom)
                    index = brace + PLACEHOLDER_LENGTH
                }
                else -> {
                    append('{')
                    index = brace + 1
                }
            }
        }
    }

/** Length of the placeholders, all of which are three characters long. */
private const val PLACEHOLDER_LENGTH = 3
