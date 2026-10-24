package com.swmansion.kmpmaps.sample

import com.swmansion.kmpmaps.core.TileOverlay
import com.swmansion.kmpmaps.core.TileProvider
import kmp_maps.sample.generated.resources.Res

/**
 * Offline demo layer for the sample app: returns the same bundled parchment grid for every tile, so
 * the sample does not depend on a tile server. A real layer would fetch its tiles for the requested
 * zoom/x/y.
 */
internal val demoTileOverlay: TileOverlay =
    TileOverlay(
        provider = TileProvider { _, _, _ -> Res.readBytes("files/tile_overlay_demo.png") },
        opacity = 0.9f,
    )

/**
 * Online demo layer for the sample app, using the Dutch national basemap (PDOK BRT
 * achtergrondkaart), a public XYZ service that needs no API key. © Kadaster / PDOK, CC BY 4.0.
 */
internal val demoUrlTileOverlay: TileOverlay =
    TileOverlay(
        urlTemplate =
            "https://service.pdok.nl/brt/achtergrondkaart/wmts/v2_0/standaard/EPSG:3857/{z}/{x}/{y}.png",
        opacity = 0.85f,
    )
