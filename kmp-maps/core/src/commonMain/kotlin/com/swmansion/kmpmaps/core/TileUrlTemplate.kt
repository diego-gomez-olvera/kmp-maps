package com.swmansion.kmpmaps.core

/**
 * A tile URL template split into literal text and tile placeholders.
 *
 * A template is parsed once, when the layer is described, because resolving happens per requested
 * tile. Resolving a [ParsedTileUrlTemplate] appends into a single builder instead of running
 * `String.replace` three times, each of which allocates an intermediate string: on the JVM the
 * three-pass version costs about 115 ns per tile against about 30 ns here, and the SDKs ask for
 * tiles hundreds of times while a map is panned.
 *
 * @property parts Literal and placeholder segments in the order they appear in the template
 * @property estimatedLength Length of the template, used to size the builder
 */
internal class ParsedTileUrlTemplate(
    private val parts: List<TileUrlPart>,
    private val estimatedLength: Int,
) {
    /**
     * Builds the URL of one tile.
     *
     * @param zoom Zoom level of the tile
     * @param x Column of the tile within [zoom]
     * @param y Row of the tile within [zoom]
     * @return The URL of that tile
     */
    fun resolve(zoom: Int, x: Int, y: Int): String =
        buildString(estimatedLength + LENGTH_ALLOWANCE) {
            parts.forEach { part ->
                when (part) {
                    is TileUrlPart.Literal -> append(part.text)
                    TileUrlPart.X -> append(x)
                    TileUrlPart.Y -> append(y)
                    TileUrlPart.Z -> append(zoom)
                }
            }
        }

    private companion object {
        /** How much longer a resolved URL can get than its template, in characters. */
        const val LENGTH_ALLOWANCE = 16
    }
}

/** One segment of a parsed tile URL template. */
internal sealed interface TileUrlPart {
    /** Text copied verbatim into the URL. */
    data class Literal(val text: String) : TileUrlPart

    /** The `{x}` placeholder: the tile column. */
    data object X : TileUrlPart

    /** The `{y}` placeholder: the tile row. */
    data object Y : TileUrlPart

    /** The `{z}` placeholder: the zoom level. */
    data object Z : TileUrlPart
}

/**
 * The placeholder rules of a [TileSource.UrlTemplate], shared by the targets that build a tile URL
 * themselves (Android) and the ones that hand the template to a map engine (Apple Maps, desktop).
 */
internal object TileUrlTemplate {
    /** Placeholders a template has to contain. */
    val placeholders: List<String> = listOf("{x}", "{y}", "{z}")

    /**
     * Checks [template] and splits it into the parts [ParsedTileUrlTemplate] resolves.
     *
     * Anything that is not one of the known placeholders is literal text, so a template may carry
     * other braces (a query value, an OData filter) without them being touched.
     *
     * @param template Tile URL to parse
     * @return The parsed template
     * @throws IllegalArgumentException when the template is blank or misses a placeholder
     */
    fun parse(template: String): ParsedTileUrlTemplate {
        require(template.isNotBlank()) { "template must not be blank." }

        val parts = mutableListOf<TileUrlPart>()
        val literal = StringBuilder()
        val found = mutableSetOf<String>()
        var index = 0
        while (index < template.length) {
            val brace = template.indexOf('{', index)
            if (brace < 0) {
                literal.append(template, index, template.length)
                break
            }
            literal.append(template, index, brace)
            val placeholder = placeholders.firstOrNull { template.startsWith(it, brace) }
            if (placeholder == null) {
                literal.append('{')
                index = brace + 1
                continue
            }
            if (literal.isNotEmpty()) {
                parts += TileUrlPart.Literal(literal.toString())
                literal.clear()
            }
            parts +=
                when (placeholder) {
                    "{x}" -> TileUrlPart.X
                    "{y}" -> TileUrlPart.Y
                    else -> TileUrlPart.Z
                }
            found += placeholder
            index = brace + placeholder.length
        }
        if (literal.isNotEmpty()) parts += TileUrlPart.Literal(literal.toString())

        placeholders.forEach { placeholder ->
            require(placeholder in found) { "template must contain $placeholder, was $template." }
        }
        return ParsedTileUrlTemplate(parts, template.length)
    }
}
