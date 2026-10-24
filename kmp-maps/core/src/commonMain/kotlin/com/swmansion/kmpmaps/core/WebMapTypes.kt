package com.swmansion.kmpmaps.core

import androidx.compose.ui.graphics.Color

/**
 * Desktop-only map configuration.
 *
 * @property mapId Map ID the Google Maps JS API renders. A map ID selects the vector renderer.
 * @property useRasterRendering Render the map as raster tiles. A [TileOverlay] can only be drawn by
 *   the raster renderer: Google's vector renderer ignores custom map types, so a URL-template layer
 *   added to a vector map never appears. Raster rendering also fixes the map to the light color
 *   scheme, and tilt, rotation and vector styling are unavailable while it is on.
 * @property gestureHandling How gestures are handled
 * @property disableDoubleClickZoom Whether double click zooms
 * @property keyboardShortcuts Whether keyboard shortcuts are enabled
 * @property minZoom Minimum zoom level
 * @property maxZoom Maximum zoom level
 * @property clickableIcons Whether map icons are clickable
 * @property restriction Area the map is restricted to
 * @property styles Custom map styling. Ignored when [useRasterRendering] is set
 * @property backgroundColor Colour drawn behind the map tiles
 */
public data class WebMapProperties(
    val mapId: String? = null,
    val useRasterRendering: Boolean = false,
    val gestureHandling: WebMapGesture = WebMapGesture.AUTO,
    val disableDoubleClickZoom: Boolean = false,
    val keyboardShortcuts: Boolean = true,
    val minZoom: Float? = null,
    val maxZoom: Float? = null,
    val clickableIcons: Boolean = true,
    val restriction: WebMapRestriction? = null,
    val styles: GoogleMapsMapStyleOptions? = null,
    val backgroundColor: Color? = null,
)

public data class WebUISettings(
    val zoomControl: Boolean = true,
    val mapTypeControl: Boolean = false,
    val streetViewControl: Boolean = false,
    val rotateControl: Boolean = false,
    val disableDefaultUI: Boolean = false,
    val zoomControlPosition: WebControlPosition? = WebControlPosition.LEFT_TOP,
    val mapTypeControlPosition: WebControlPosition? = null,
    val streetViewControlPosition: WebControlPosition? = null,
    val rotateControlPosition: WebControlPosition? = null,
)

public data class WebMapRestriction(
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
    val strictBounds: Boolean = false,
)

public enum class WebMapGesture {
    COOPERATIVE,
    AUTO,
    GREEDY,
    NONE,
}

public enum class WebControlPosition {
    TOP_LEFT,
    TOP_CENTER,
    TOP_RIGHT,
    LEFT_TOP,
    LEFT_CENTER,
    LEFT_BOTTOM,
    RIGHT_TOP,
    RIGHT_CENTER,
    RIGHT_BOTTOM,
    BOTTOM_LEFT,
    BOTTOM_CENTER,
    BOTTOM_RIGHT,
}
