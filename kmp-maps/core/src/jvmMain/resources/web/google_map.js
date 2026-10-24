let map;
let markers = [];

let jsCircles = [];
let jsPolygons = [];
let jsPolylines = [];

let geoJsonMarkers = [];

// Keyed by "tileSize|template"; reused so an opacity change does not reload tiles.
let tileLayerCache = new Map();
let pendingTileLayers = null;

// Only the raster renderer draws overlayMapTypes (a map ID selects the vector one, which ignores
// them), so the mode is fixed when the map is created.
let rasterRendering = false;

let markerCluster;
let trafficLayer = null;

let AdvancedMarkerElement;
let PinElement;
let ColorScheme;

const clusterCache = new Map();

async function initMap() {
    const { Map } = await google.maps.importLibrary("maps");
    const { AdvancedMarkerElement: MarkerClass, PinElement: PinClass } = await google.maps.importLibrary("marker");
    const { ColorScheme: ColorSchemeLib } = await google.maps.importLibrary("core");

    AdvancedMarkerElement = MarkerClass;
    PinElement = PinClass;
    ColorScheme = ColorSchemeLib;

    const selectedMapId = "{{INITIAL_MAP_ID}}";
    const selectedRenderingType = "{{INITIAL_RENDERING_TYPE}}";
    const rasterRenderingRequested = selectedRenderingType === "raster";
    const selectedLat = "{{INITIAL_LAT}}";
    const selectedLng = "{{INITIAL_LNG}}";
    const selectedZoom = "{{INITIAL_ZOOM}}";
    const selectedColorScheme = mapColorScheme("{{INITIAL_COLOR_SCHEME}}", ColorSchemeLib);

    map = new Map(document.getElementById("map"), {
        mapId: selectedMapId,
        // The raster renderer has no dark style: a dark scheme would keep the map vector.
        colorScheme: rasterRenderingRequested ? "LIGHT" : selectedColorScheme,
        ...(rasterRenderingRequested ? { renderingType: "raster" } : {}),
        center: {
            lat: Number(selectedLat),
            lng: Number(selectedLng)
        },
        zoom: Number(selectedZoom),
        disableDefaultUI: true,
    });
    rasterRendering = rasterRenderingRequested;

    {{FIT_BOUNDS_CALL}}

    if (window.markerClusterer) {
        markerCluster = new markerClusterer.MarkerClusterer({ map, markers: [] });
    }

    google.maps.event.addListenerOnce(map, 'tilesloaded', function() {
        window.kmpJsBridge.callNative("onMapLoaded", "{}");
    });

    map.addListener("click", (e) => {
        if (e.placeId) {
            window.kmpJsBridge.callNative("onPOIClick", JSON.stringify({
                latitude: e.latLng.lat(),
                longitude: e.latLng.lng()
            }));
        } else {
            window.kmpJsBridge.callNative("onMapClick", JSON.stringify({
                latitude: e.latLng.lat(),
                longitude: e.latLng.lng()
            }));
        }
    });

    map.addListener("idle", () => {
        const center = map.getCenter();
        const zoom = map.getZoom();
        const mapBounds = map.getBounds();

        if (center) {
            const ne = mapBounds ? mapBounds.getNorthEast() : null;
            const sw = mapBounds ? mapBounds.getSouthWest() : null;

            const cameraState = {
                coordinates: {
                    latitude: center.lat(),
                    longitude: center.lng(),
                },
                zoom,
                bounds: (ne && sw) ? {
                    northeast: { latitude: ne.lat(), longitude: ne.lng() },
                    southwest: { latitude: sw.lat(), longitude: sw.lng() }
                } : null
            };

            if (window.kmpJsBridge) {
                window.kmpJsBridge.callNative(
                    "onCameraMove",
                    JSON.stringify(cameraState)
                );
            }
        }
    });

    if (pendingTileLayers) {
        const layers = pendingTileLayers;
        pendingTileLayers = null;
        updateTileOverlays(layers);
    }
}

function mapColorScheme(themeName, ColorSchemeLib) {
    switch (themeName) {
        case 'DARK':
            return ColorSchemeLib.DARK;
        case 'LIGHT':
            return ColorSchemeLib.LIGHT;
        case 'SYSTEM':
        default:
            return ColorSchemeLib.FOLLOW_SYSTEM;
    }
}

function updateMarkers(data, clusterEnabled, hasCustomClusterContent) {
    if (!map || !AdvancedMarkerElement) {
        console.warn("Map not ready yet.");
        return;
    }

    clearMarkers();
    clusterCache.clear();

    try {
        const newMarkers = [];

        data.forEach(item => {
            let contentNode = null;
            if (item.renderedHtml) {
                const div = document.createElement('div');
                div.innerHTML = item.renderedHtml;
                div.className = `kmp-marker-wrapper ${item.contentId || ''}`;

                contentNode = div;
            }

            const marker = new AdvancedMarkerElement({
                map,
                position: item.position,
                title: item.title || "",
                content: contentNode
            });

            marker._kmpData = {
                id: item.id,
                title: item.title,
                coordinates: {
                    latitude: item.position.lat,
                    longitude: item.position.lng
                },
                contentId: item.contentId,
            };

            marker.addListener("click", () => {
                sendToKotlin("onMarkerClick", String(marker._kmpData.id));
            });

            newMarkers.push(marker);
        });

        markers = newMarkers;

        if (clusterEnabled && markerCluster) {
            markerCluster.clearMarkers();

            if (hasCustomClusterContent) {
                markerCluster.renderer = {
                    render: (cluster) => {
                        const markerIds = cluster.markers.map(m => m._kmpData.id).sort().join(',');
                        const clusterId = `cluster-${btoa(markerIds)}`;
                        const container = document.createElement("div");
                        container.id = clusterId;

                        if (clusterCache.has(clusterId)) {
                            container.innerHTML = clusterCache.get(clusterId);
                        } else {
                            container.innerHTML = `<div style="background: #4285F4; color: white; border-radius: 50%; width: 30px; height: 30px; display: flex; align-items: center; justify-content: center;">${cluster.count}</div>`;

                            const clusterParams = {
                                id: clusterId,
                                coordinates: { latitude: cluster.position.lat(), longitude: cluster.position.lng() },
                                size: cluster.count,
                                items: cluster.markers.map(m => m._kmpData)
                            };
                            sendToKotlin("renderCluster", JSON.stringify(clusterParams));
                        }

                        return new AdvancedMarkerElement({
                            position: cluster.position,
                            content: container
                        });
                    }
                };
            }

            markerCluster.addMarkers(markers);

            google.maps.event.clearListeners(markerCluster, "click");

            google.maps.event.addListener(markerCluster, "click", (cluster) => {
                const mappedItems = cluster.markers.map(m => m._kmpData);

                const clusterInfo = {
                    coordinates: {
                        latitude: cluster.position.lat(),
                        longitude: cluster.position.lng()
                    },
                    size: cluster.count,
                    items: mappedItems
                };

                sendToKotlin("onClusterClick", JSON.stringify(clusterInfo));
            });
        }
    } catch (e) {
        console.error("Error during updateMarkers:", e);
    }
}

function applyClusterHtml(clusterId, html) {
    clusterCache.set(clusterId, html);
    const element = document.getElementById(clusterId);
    if (element) {
        element.innerHTML = html;
    }
}

function updateMapProperties(props) {
    if (!map) return;

    if (props.isTrafficEnabled) {
        if (!trafficLayer) {
            trafficLayer = new google.maps.TrafficLayer();
        }
        trafficLayer.setMap(map);
    } else {
        if (trafficLayer) {
            trafficLayer.setMap(null);
        }
    }

    let typeId;
    switch (props.mapType) {
        case "SATELLITE": typeId = google.maps.MapTypeId.SATELLITE; break;
        case "TERRAIN": typeId = google.maps.MapTypeId.TERRAIN; break;
        case "HYBRID": typeId = google.maps.MapTypeId.HYBRID; break;
        case "NORMAL": default: typeId = google.maps.MapTypeId.ROADMAP; break;
    }
    map.setMapTypeId(typeId);

    const web = props.web;
    if (web) {
        const options = {
            gestureHandling: web.gestureHandling,
            disableDoubleClickZoom: web.disableDoubleClickZoom,
            keyboardShortcuts: web.keyboardShortcuts,
            clickableIcons: web.clickableIcons,
        };

        if (web.mapId !== null) options.mapId = web.mapId;
        if (web.minZoom !== null) options.minZoom = web.minZoom;
        if (web.maxZoom !== null) options.maxZoom = web.maxZoom;
        if (web.restriction !== null) options.restriction = web.restriction;
        if (web.backgroundColor !== null) options.backgroundColor = web.backgroundColor;
        if (web.styles !== null) options.styles = web.styles;

        map.setOptions(options);
    }
}

function updateMapUISettings(settings) {
    if (!map) return;

    const web = settings.web;

    const options = {
        disableDefaultUI: web.disableDefaultUI,
        draggable: settings.scrollEnabled,
        scrollwheel: settings.zoomEnabled,
    };

    if (web) {
        options.disableDefaultUI = web.disableDefaultUI;

        if (!web.disableDefaultUI) {
            const getPos = (posName) => {
                if (!posName) return null;
                return { position: google.maps.ControlPosition[posName] };
            };

            options.zoomControl = web.zoomControl;
            if (web.zoomControl && web.zoomControlPosition) {
                options.zoomControlOptions = getPos(web.zoomControlPosition);
            }

            options.mapTypeControl = web.mapTypeControl;
            if (web.mapTypeControl && web.mapTypeControlPosition) {
                options.mapTypeControlOptions = getPos(web.mapTypeControlPosition);
            }

            options.streetViewControl = web.streetViewControl;
            if (web.streetViewControl && web.streetViewControlPosition) {
                options.streetViewControlOptions = getPos(web.streetViewControlPosition);
            }

            options.rotateControl = web.rotateControl;
            if (web.rotateControl && web.rotateControlPosition) {
                options.rotateControlOptions = getPos(web.rotateControlPosition);
            }

            options.fullscreenControl = web.fullscreenControl;
        }
    } else {
        options.zoomControl = settings.zoomEnabled;
    }

    map.setOptions(options);
}

function updateCircles(data) {
    if (!map) return;

    jsCircles.forEach(c => c.setMap(null));
    jsCircles = [];

    try {
        data.forEach((item) => {
            const circle = new google.maps.Circle({
                map: map,
                center: item.center,
                radius: item.radius,
                fillColor: item.fillColor || "#000000",
                fillOpacity: item.fillOpacity !== undefined ? item.fillOpacity : 0.0,
                strokeColor: item.strokeColor || "#000000",
                strokeOpacity: item.strokeOpacity !== undefined ? item.strokeOpacity : 1.0,
                strokeWeight: item.strokeWeight || 1,
                clickable: true,
                zIndex: 2
            });

            circle.addListener("click", () => {
                window.kmpJsBridge.callNative("onCircleClick", String(item.id));
            });

            jsCircles.push(circle);
        });
    } catch (e) { console.error("Error updating circles:", e); }
}

function updatePolygons(data) {
    if (!map) return;

    jsPolygons.forEach(p => p.setMap(null));
    jsPolygons = [];

    try {
        data.forEach((item) => {
            const polygon = new google.maps.Polygon({
                map: map,
                paths: item.paths,
                fillColor: item.fillColor || "#000000",
                fillOpacity: item.fillOpacity !== undefined ? item.fillOpacity : 0.0,
                strokeColor: item.strokeColor || "#000000",
                strokeOpacity: item.strokeOpacity !== undefined ? item.strokeOpacity : 1.0,
                strokeWeight: item.strokeWeight || 1,
                clickable: true,
                zIndex: 2
            });

            polygon.addListener("click", () => {
                window.kmpJsBridge.callNative("onPolygonClick", String(item.id));
            });

            jsPolygons.push(polygon);
        });
    } catch (e) { console.error("Error updating polygons:", e); }
}


function updatePolylines(data) {
    if (!map) return;

    jsPolylines.forEach(p => p.setMap(null));
    jsPolylines = [];

    try {
        data.forEach((item) => {
            const polyline = new google.maps.Polyline({
                map: map,
                path: item.path,
                strokeColor: item.strokeColor || "#000000",
                strokeOpacity: item.strokeOpacity !== undefined ? item.strokeOpacity : 1.0,
                strokeWeight: item.strokeWeight || 1,
                clickable: true,
                zIndex: 3
            });

            polyline.addListener("click", () => {
                window.kmpJsBridge.callNative("onPolylineClick", String(item.id));
            });

            jsPolylines.push(polyline);
        });
    } catch (e) { console.error("Error updating polylines:", e); }
}

function updateGeoJsonLayers(layersData, clusterEnabled) {
    if (!map) return;

    map.data.forEach(f => map.data.remove(f));
    geoJsonMarkers.forEach(m => {
        m.setMap(null);
        if (markerCluster) markerCluster.removeMarker(m);
    });
    geoJsonMarkers = [];

    layersData.forEach((layer, layerIndex) => {
        try {
            const features = map.data.addGeoJson(JSON.parse(layer.geoJson));

            features.forEach((feature, featureIndex) => {
                feature.setProperty('_layerIndex', layerIndex);

                const geometry = feature.getGeometry();
                if (!geometry) {
                    applyStyleToFeature(feature, layer);
                    return;
                }

                if (geometry.getType() === 'Point' && clusterEnabled) {
                    const coords = geometry.get();

                    const marker = new google.maps.Marker({
                        position: coords,
                        map: null,
                    });

                    const featureId = feature.getId() !== undefined
                        ? feature.getId()
                        : `layer-${layerIndex}-f-${featureIndex}`;

                    marker._kmpData = {
                       id: String(featureId),
                        title: feature.getProperty('title') ?? "",
                        coordinates: {
                            latitude: coords.lat(),
                            longitude: coords.lng()
                        },
                        contentId: null
                    };

                    geoJsonMarkers.push(marker);

                    map.data.overrideStyle(feature, { visible: false });
                } else {
                    applyStyleToFeature(feature, layer);
                }
            });
        } catch (e) { console.error("GeoJSON Cluster Error:", e); }
    });

    if (clusterEnabled && markerCluster) {
        markerCluster.addMarkers(geoJsonMarkers);
    }
}

function applyStyleToFeature(feature, config) {
    map.data.overrideStyle(feature, {
        visible: config.visible,
        zIndex: config.zIndex,
        clickable: config.isClickable,
        ...(config.polygonStyle && {
            fillColor: config.polygonStyle.fillColor,
            fillOpacity: config.polygonStyle.fillOpacity,
            strokeColor: config.polygonStyle.strokeColor,
            strokeWeight: config.polygonStyle.strokeWeight
        }),
        ...(config.lineStringStyle && {
            strokeColor: config.lineStringStyle.strokeColor,
            strokeWeight: config.lineStringStyle.strokeWeight
        }),
        ...(config.pointStyle && {
            title: config.pointStyle.title,
            opacity: config.pointStyle.opacity,
            draggable: config.pointStyle.draggable,
        })
    });
}

// Compiled once per layer: getTileUrl runs for every visible tile while panning.
function compileTileUrlTemplate(template) {
    const parts = [];
    const placeholders = ["{x}", "{y}", "{z}"];
    let index = 0;
    while (index < template.length) {
        const brace = template.indexOf("{", index);
        if (brace < 0) {
            parts.push(template.slice(index));
            break;
        }
        if (brace > index) parts.push(template.slice(index, brace));
        const placeholder = placeholders.find((candidate) => template.startsWith(candidate, brace));
        if (!placeholder) {
            parts.push("{");
            index = brace + 1;
            continue;
        }
        parts.push(placeholder);
        index = brace + placeholder.length;
    }
    return parts;
}

function renderTileUrl(parts, coords, zoom) {
    let url = "";
    for (const part of parts) {
        if (part === "{x}") url += coords.x;
        else if (part === "{y}") url += coords.y;
        else if (part === "{z}") url += zoom;
        else url += part;
    }
    return url;
}

function updateTileOverlays(layersData) {
    if (!map) {
        console.warn("Map not ready yet, deferring tile layers.");
        pendingTileLayers = layersData;
        return;
    }

    const requested = Array.isArray(layersData) ? layersData : [];

    const hasLayers = requested.some(
        (layer) => layer && typeof layer.template === "string" && layer.template !== "" && layer.visible !== false
    );
    if (hasLayers && !rasterRendering) {
        // Switching renderer at runtime does not work: getRenderingType reports RASTER but the
        // vector renderer keeps drawing and drops custom map types.
        console.warn(
            "Tile layers are drawn only by the Google Maps raster renderer. Create the map with " +
                "WebMapProperties(useRasterRendering = true), otherwise this layer will not appear."
        );
    }

    const previous = tileLayerCache;
    const next = new Map();
    const desired = [];

    requested.forEach((layer) => {
        if (!layer || typeof layer.template !== "string" || layer.template === "") return;
        if (layer.visible === false) return;

        const tileSize = Number.isFinite(layer.tileSize) && layer.tileSize > 0
            ? layer.tileSize
            : 256;
        const opacity = Number.isFinite(layer.opacity)
            ? Math.min(1, Math.max(0, layer.opacity))
            : 1;
        // Tile size is fixed at ImageMapType creation, so it is part of the identity.
        const key = `${tileSize}|${layer.template}`;
        if (next.has(key)) return;

        try {
            const cached = previous.get(key);
            if (cached) {
                cached.mapType.setOpacity(opacity);
                next.set(key, cached);
                desired.push(cached.mapType);
                return;
            }

            const compiledTemplate = compileTileUrlTemplate(layer.template);
            const mapType = new google.maps.ImageMapType({
                getTileUrl: (coords, zoom) => renderTileUrl(compiledTemplate, coords, zoom),
                tileSize: new google.maps.Size(tileSize, tileSize),
                opacity: opacity
            });
            next.set(key, { mapType });
            desired.push(mapType);
        } catch (e) {
            console.error("Error during updateTileOverlays:", e);
        }
    });

    try {
        syncOverlayMapTypes(previous, desired);
        tileLayerCache = next;
    } catch (e) {
        console.error("Error during updateTileOverlays:", e);
    }
}

// `desired` is in ascending zIndex order because a later overlay map type is drawn on top. Overlays
// we do not own are left in place.
function syncOverlayMapTypes(previous, desired) {
    const overlays = map.overlayMapTypes;
    const owned = new Set();
    previous.forEach((entry) => owned.add(entry.mapType));

    for (let i = overlays.getLength() - 1; i >= 0; i--) {
        const current = overlays.getAt(i);
        if (owned.has(current) && desired.indexOf(current) === -1) {
            overlays.removeAt(i);
        }
    }

    desired.forEach((mapType, index) => {
        if (overlays.getAt(index) === mapType) return;

        for (let j = overlays.getLength() - 1; j >= index; j--) {
            if (overlays.getAt(j) === mapType) {
                overlays.removeAt(j);
                break;
            }
        }

        overlays.insertAt(index, mapType);
    });
}

function clearMarkers() {
    if (markerCluster) {
        markerCluster.clearMarkers();
    }
    markers.forEach(m => m.setMap(null));
    markers = [];
}

function sendToKotlin(method, data) {
    if (window.kmpJsBridge?.callNative) {
        window.kmpJsBridge.callNative(method, data);
    } else {
        console.error(`Bridge not found for method: ${method}`);
    }
}
