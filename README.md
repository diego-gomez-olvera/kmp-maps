![KMP Maps by Software Mansion](https://github.com/software-mansion/kmp-maps/blob/main/docs/images/cover_image.png?raw=true)
[![Ad](https://swm-delivery.com/www/images/zone-gh-kmp-maps-1?n=1)](https://swm-delivery.com/www/delivery/ck-slug.php?zoneid=zone-gh-kmp-maps-1&n=1)
[![Ad](https://swm-delivery.com/www/images/zone-gh-kmp-maps-2?n=1)](https://swm-delivery.com/www/delivery/ck-slug.php?zoneid=zone-gh-kmp-maps-2&n=1)
[![Ad](https://swm-delivery.com/www/images/zone-gh-kmp-maps-3?n=1)](https://swm-delivery.com/www/delivery/ck-slug.php?zoneid=zone-gh-kmp-maps-3&n=1)

[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.20-blue.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](./LICENSE)
![Maven Central](https://img.shields.io/maven-central/v/com.swmansion.kmpmaps/core)

`kmp-maps` provides a unified map component for Compose Multiplatform applications, offering seamless integration with native map APIs on both Android and iOS platforms.

## 🎯 See It in Action

Check out the sample project in the `/sample` directory for complete usage examples.

|                                                                  iOS (Apple Maps)                                                                   |                                                                  Android (Google Maps)                                                                  |                                                                  Desktop (Google Maps)                                                                  |
| :-------------------------------------------------------------------------------------------------------------------------------------------------: | :-----------------------------------------------------------------------------------------------------------------------------------------------------: |:-------------------------------------------------------------------------------------------------------------------------------------------------------:|
| <img src="https://github.com/software-mansion/kmp-maps/blob/main/docs/images/ios_preview.png" height="600" style="display: block; margin: 0 auto;"> | <img src="https://github.com/software-mansion/kmp-maps/blob/main/docs/images/android_preview.png" height="600" style="display: block; margin: 0 auto;"> | <img src="https://github.com/software-mansion/kmp-maps/blob/main/docs/images/desktop_preview.png" height="600" style="display: block; margin: 0 auto;"> |
|                                           _Map view with circles, polygon, polylines and markers on iOS_                                            |                                           _Map view with circles, polygon, polylines and markers on Android_                                            |                                           _Map view with circles, polygon, polylines and markers on desktop_                                            |

## ✨ Features

- **Multi-Provider Support:** Android (Google Maps), iOS (Apple Maps or Google Maps), and JVM/desktop (Google Maps JS).
- **Fully Customizable Markers:** Create markers using standard Compose Multiplatform code.
- **GeoJSON Support:** Easily render GeoJSON layers with customizable styling capabilities.
- **Raster Tile Overlays:** Draw your own tile layer over the base map (satellite, cadastral, historical series) from a
  provider that returns tile bytes, with opacity and visibility control.
- **Native Rendering:** Powered by underlying native SDKs, ensuring smooth performance, correct gesture handling, and native look and feel.
- **Rich Geometry Support:** Draw interactive circles, polygons, and polylines with full control over colors, strokes, and fills.
- **Location Services:** Built-in location permission handling and user location display.
- **Interactive Callbacks:** Comprehensive event handling for map clicks, camera movements, POI interactions, and marker events.
- **Compose Multiplatform:** Built with Compose Multiplatform for modern UI development.

## 🚀 Usage

### Import

Choose the appropriate import based on your needs:

- **Native Implementation:** Uses native Google Maps on Android, native Apple Maps on iOS, and Google Maps JS API (via WebView) on desktop.

  ```kotlin
  import com.swmansion.kmpmaps.core.Map
  ```

- **Universal Google Maps:** Uses Google Maps SDK on Android and iOS, and Google Maps JS API on desktop.

  ```kotlin
  import com.swmansion.kmpmaps.googlemaps.Map
  ```

### Example

```kotlin
@Composable
fun MyMapScreen() {
    Map(
        modifier = Modifier.fillMaxSize(),
        properties = MapProperties(
            isMyLocationEnabled = true,
            mapType = MapType.NORMAL,
        ),
        uiSettings = MapUISettings(
            myLocationButtonEnabled = true,
            compassEnabled = true
        ),
        cameraPosition = CameraPosition(
            coordinates = Coordinates(latitude = 50.0619, longitude = 19.9373),
            zoom = 13f
        ),
        markers = listOf(
            Marker(
                coordinates = Coordinates(latitude = 50.0486, longitude = 19.9654),
                title = "Software Mansion",
                androidSnippet = "Software house"
            )
        ),
        onMarkerClick = { marker ->
            println("Marker clicked: ${marker.title}")
        },
        onMapClick = { coordinates ->
            println("Map clicked at: ${coordinates.latitude}, ${coordinates.longitude}")
        }
    )
}
```

### Tile overlays

A tile layer is either a URL template the map downloads itself, or a provider that hands over the encoded image
(`PNG`/`JPEG`) for a Web Mercator `z`/`x`/`y` tile.

A URL template works on every target, including desktop, where the Google Maps JS renderer draws
it as an `ImageMapType`:

```kotlin
val openStreetMap = TileOverlay(urlTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png")

Map(
    // …
    tileOverlays = listOf(openStreetMap),
)
```

A provider is for sources that are not plain `{z}/{x}/{y}` Web Mercator tiles — for example a service published in a
different tile matrix, where you reproject each tile before returning it. It is supported on Android and Apple Maps and
ignored on desktop (the Google Maps JS backend of `core` only takes a tile URL):

```kotlin
val historicalLayer =
    TileOverlay(
        provider = TileProvider { zoom, x, y -> reprojectHistoricalTile(zoom, x, y) },
        opacity = 0.7f,
    )
```

Tiles are drawn over the base map and below the map's markers, so annotations stay on top (Apple Maps places the layer
above roads and below labels). Changing `opacity` repaints the tiles the map already has instead of asking for them
again.

A layer is recognised by its provider instance, so create the `TileProvider` once (top level, view model or `remember`)
instead of inline in a composable; a new instance on every recomposition re-adds the layer and refetches its tiles.

On desktop the map has to be created with `WebMapProperties(useRasterRendering = true)`: custom map types are drawn
only by Google's raster renderer, which also means the light colour scheme, no tilt and no custom styling while the
layer is used. A URL-template layer added to a vector map logs a warning instead of appearing.

## 📦 Installation

For installation instructions, platform setup, and configuration details, visit our [dedicated document](https://github.com/software-mansion/kmp-maps/blob/main/docs/INSTALLATION_SETUP.md).

## 📚 API Reference

Check out our [dedicated documentation page](https://docs.swmansion.com/kmp-maps/) for API reference.

## 🤝 Contributing

We welcome contributions! Please feel free to submit a Pull Request.

## KMP Maps is created by Software Mansion

[![swm](https://logo.swmansion.com/logo?color=white&variant=desktop&width=150&tag=kmp-maps-github "Software Mansion")](https://swmansion.com)

Since 2012 [Software Mansion](https://swmansion.com) is a software agency with
experience in building web and mobile apps. We are Core React Native
Contributors and experts in dealing with all kinds of React Native issues. We
can help you build your next dream product –
[Hire us](https://swmansion.com/contact/projects?utm_source=typegpu&utm_medium=readme).

Made by [@software-mansion](https://github.com/software-mansion) and
[community](https://github.com/software-mansion/kmp-maps/graphs/contributors) 💛
<br><br>
<a href="https://github.com/software-mansion/kmp-maps/graphs/contributors">
<img src="https://contrib.rocks/image?repo=software-mansion/kmp-maps" />
</a>
