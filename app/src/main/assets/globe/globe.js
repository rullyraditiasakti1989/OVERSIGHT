// GLOBE renderer. Kotlin drives, this file draws. Nothing is fetched from here
// except raster tiles — every vector layer is pushed in over the bridge as
// GeoJSON, because the app's HTTP stack is the one that knows the sources.
//
// Contract (see docs/GLOBE-PLAN.md §3.2):
//   Kotlin -> JS  window.ovsApply('<json>')      source / layer / fly / focus / theme
//   JS -> Kotlin  OVSBridge.post('<json>')       ready / camera / pick / error

import * as maplibregl from './maplibre-gl.mjs'

// The worker is a sibling asset. Setting it explicitly avoids relying on
// import.meta.url inference inside a WebView origin.
maplibregl.config.WORKER_URL = new URL('./maplibre-gl-worker.mjs', import.meta.url).href

const ESRI_TILES =
  'https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}'

// Defaults match the app's ink tokens; Kotlin overrides them with a theme
// message as soon as the bridge is up.
const theme = {
  ink: '#060708',
  panel: '#0C0E10',
  line: '#3A444A',
  bone: '#F3F5F4',
  seal: '#E05A48'
}

const started = performance.now()
let map = null
let lastCamera = 0

/**
 * Zoom that makes the sphere fill the plate.
 *
 * MapLibre scales the globe off the viewport **width**, so a wide-and-short
 * window draws a sphere far taller than the plate and clips it. There is no
 * fit-the-sphere API. Measured on device at 378×489 CSS: zoom 1.84 renders a
 * 419 px limb, so `limb = 117 × 2^zoom` and the inverse below is exact enough
 * to land within a few pixels. `refit` then measures the limb for real and
 * corrects, so the constant only has to be close.
 */
const LIMB_PX_AT_ZOOM_0 = 117.0

function fitZoom() {
  const short = Math.min(window.innerWidth, window.innerHeight)
  if (!short) return 1.84
  const target = Math.max(140, short * 0.92)
  return Math.log2(target / LIMB_PX_AT_ZOOM_0)
}

// The operator's zoom as an offset from the fit zoom, so rotating keeps their
// scale instead of resetting it.
let zoomOffset = 0
let applyingZoom = false

function post(payload) {
  try {
    window.OVSBridge.post(JSON.stringify(payload))
  } catch (e) {
    // Bridge absent: nothing to tell, and throwing here would break the map.
  }
}

function fail(message) {
  const fault = document.getElementById('fault')
  if (fault) {
    fault.textContent = message
    fault.style.display = 'flex'
  }
  post({ t: 'error', message })
}

function styleFor() {
  return {
    version: 8,
    // The sphere. Style-level, so it survives a style reload.
    projection: { type: 'globe' },
    // A raster source has no geometry of its own; this is what shows while
    // tiles are in flight, and it is the room's ground colour, never white.
    sources: {
      imagery: {
        type: 'raster',
        tiles: [ESRI_TILES],
        tileSize: 256,
        minzoom: 0,
        maxzoom: 19,
        attribution: 'Imagery © Esri, Maxar, Earthstar Geographics'
      }
    },
    layers: [
      { id: 'ground', type: 'background', paint: { 'background-color': theme.ink } },
      { id: 'imagery', type: 'raster', source: 'imagery', paint: { 'raster-opacity': 1 } }
    ],
    // Dark atmosphere: the globe sits in the room instead of glowing out of it.
    sky: {
      'sky-color': theme.ink,
      'horizon-color': theme.panel,
      'fog-color': theme.ink,
      'sky-horizon-blend': 0.6,
      'horizon-fog-blend': 0.12,
      'fog-ground-blend': 0.85,
      'atmosphere-blend': 0.55
    }
  }
}

function applyTheme(next) {
  Object.assign(theme, next || {})
  document.body.style.background = theme.ink
  const globe = document.getElementById('globe')
  if (globe) globe.style.background = theme.ink
  if (!map) return
  try {
    map.setSky({
      'sky-color': theme.ink,
      'horizon-color': theme.panel,
      'fog-color': theme.ink,
      'sky-horizon-blend': 0.6,
      'horizon-fog-blend': 0.12,
      'fog-ground-blend': 0.85,
      'atmosphere-blend': 0.55
    })
    if (map.getLayer('ground')) {
      map.setPaintProperty('ground', 'background-color', theme.ink)
    }
  } catch (e) {
    // Style not ready yet; the message is applied again on load.
  }
}

/**
 * One circle layer per pushed source, created on first use and reused after.
 *
 * A source with no layer draws nothing, which is why `source` has to do this in
 * the same message: a missed layer looks exactly like a layer with no contacts.
 * Severity is a paint expression rather than a second layer, so the seal ink has
 * exactly one place to live.
 */
function ensureLayer(id, color) {
  const layerId = 'lyr-' + id
  if (map.getLayer(layerId)) return
  const ink = typeof color === 'string' && color ? color : theme.bone
  map.addLayer({
    id: layerId,
    type: 'circle',
    source: id,
    paint: {
      'circle-radius': ['interpolate', ['linear'], ['zoom'], -1, 2, 2, 2.6, 5, 4, 9, 6.5],
      'circle-color': ['case', ['==', ['get', '__sev'], true], theme.seal, ink],
      'circle-opacity': 0.92,
      'circle-stroke-width': 1,
      'circle-stroke-color': theme.ink
    }
  })
}

function emitCamera() {  if (!map) return
  const now = performance.now()
  // 120 ms is enough to drive a plate and a scale bar without flooding the
  // bridge during a drag.
  if (now - lastCamera < 120) return
  lastCamera = now
  const c = map.getCenter()
  post({
    t: 'camera',
    lat: Number(c.lat.toFixed(4)),
    lon: Number(c.lng.toFixed(4)),
    z: Number(map.getZoom().toFixed(2)),
    b: Number((map.getBearing() || 0).toFixed(1)),
    p: Number((map.getPitch() || 0).toFixed(1))
  })
}

function start() {
  // Probe before init: a blocklisted GPU gives a null context and MapLibre
  // throws deep inside the style loader, which is a bad place to learn it.
  const probe = document.createElement('canvas')
  const gl = probe.getContext('webgl2')
  if (!gl) {
    fail('SPHERE UNAVAILABLE · WEBGL2 MISSING')
    return
  }

  map = new maplibregl.Map({
    container: 'globe',
    style: styleFor(),
    center: [106.8, -6.2],
    zoom: fitZoom(),
    minZoom: -2,
    maxZoom: 12,
    pitch: 0,
    bearing: 0,
    antialias: true,
    attributionControl: false,
    fadeDuration: 120,
    // No idle animation anywhere: the globe repaints on data change or
    // interaction only, which is the whole battery story.
    refreshExpiredTiles: true
  })

  map.on('load', () => {
    map.resize()
    refit()
    // The focus ring is a second source with at most one feature, so selecting a
    // contact never has to re-encode the layer it belongs to.
    map.addSource('focus', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } })
    map.addLayer({
      id: 'focus-ring',
      type: 'circle',
      source: 'focus',
      paint: {
        'circle-radius': ['interpolate', ['linear'], ['zoom'], -1, 7, 5, 11, 9, 16],
        'circle-color': 'rgba(0,0,0,0)',
        'circle-stroke-width': 1.5,
        'circle-stroke-color': theme.bone
      }
    })
    emitCamera()
    post({ t: 'ready', ms: Math.round(performance.now() - started) })
  })

  // The viewport changes under us on rotation, on expand, and whenever the
  // keyboard or a card changes the plate's height. MapLibre does not watch the
  // DOM, and a stale drawing buffer renders the sphere at the old scale and
  // clips it. A ResizeObserver is the only thing that reacts to every one of
  // those without Kotlin having to know about it.
  if (typeof ResizeObserver === 'function') {
    let pending = 0
    new ResizeObserver(() => {
      if (pending) return
      pending = requestAnimationFrame(() => {
        pending = 0
        refit()
      })
    }).observe(document.getElementById('globe'))
  }

  map.on('move', emitCamera)
  map.on('moveend', () => {
    // Track the operator's scale as an offset from the fit, so a later resize
    // restores their zoom rather than the default.
    if (!applyingZoom) zoomOffset = map.getZoom() - fitZoom()
    lastCamera = 0
    emitCamera()
  })

  map.on('click', (event) => {
    // Only our own vector layers are pickable; the raster imagery and the focus
    // ring are not.
    const layers = (map.getStyle().layers || [])
      .map((l) => l.id)
      .filter((id) => id !== 'ground' && id !== 'imagery' && id !== 'focus-ring')
    if (layers.length === 0) return
    const hits = map.queryRenderedFeatures(event.point, { layers })
    if (hits.length === 0) {
      // Empty water is a real answer: it closes whatever card is open. Without
      // this the card survives a tap anywhere on the sphere, which reads as a
      // stuck control.
      post({ t: 'clear' })
      return
    }
    const hit = hits[0]
    const props = hit.properties || {}
    post({
      t: 'pick',
      layer: String(props.__layer || hit.layer.id),
      id: String(props.__id || ''),
      lat: Number(event.lngLat.lat.toFixed(5)),
      lon: Number(event.lngLat.lng.toFixed(5))
    })
  })

  map.on('error', (event) => {
    const message = event && event.error ? event.error.message : 'renderer error'
    // Tile 404s are routine at the poles and must not flip the screen.
    if (/tile|404|Failed to fetch/i.test(message)) return
    post({ t: 'error', message: String(message).slice(0, 200) })
  })

  window.addEventListener('resize', refit)
}

// ---- bridge in -------------------------------------------------------------

/**
 * Compose moves the WebView frame but the WebGL drawing buffer keeps its old
 * size, so the globe is drawn at the wrong scale until told. Kotlin calls this
 * from `onSizeChanged`; a ResizeObserver inside the page does the same job when
 * only the DOM box changed.
 */
function refit() {
  if (!map) return
  map.resize()

  const apply = () => {
    // Re-apply the operator's scale against the new short side, so a rotation
    // keeps the sphere inside the plate instead of clipping it.
    const short = Math.min(window.innerWidth, window.innerHeight)
    const target = Math.max(140, short * 0.92)
    let zoom = Math.max(-2, Math.min(12, fitZoom() + zoomOffset))
    applyingZoom = true
    map.jumpTo({ zoom })
    applyingZoom = false

    // Correct against the measured limb. `project` is reliable once a frame has
    // been drawn at the new size, which is why this runs in a rAF.
    const measured = limbPx()
    if (measured > 0) {
      const ratio = target / measured
      if (ratio < 0.94 || ratio > 1.06) {
        zoom = Math.max(-2, Math.min(12, zoom + Math.log2(ratio)))
        applyingZoom = true
        map.jumpTo({ zoom })
        applyingZoom = false
      }
    }
  }
  requestAnimationFrame(() => requestAnimationFrame(apply))
}

/**
 * Measured diameter of the visible sphere, in CSS pixels.
 *
 * The calibration constant above is an estimate; this is the truth. On a globe
 * the point 90° east of centre sits on the limb, so the distance from centre to
 * that point is the radius. Used to correct the fit rather than trust a guess.
 */
function limbPx() {
  const c = map.getCenter()
  const centre = map.project(c)
  const east = map.project({ lng: c.lng + 90, lat: c.lat })
  return Math.hypot(east.x - centre.x, east.y - centre.y) * 2
}

window.ovsResize = refit

window.ovsApply = function (raw) {
  let message
  try {
    message = JSON.parse(raw)
  } catch (e) {
    return
  }
  if (!map) return

  switch (message.t) {
    case 'theme':
      applyTheme(message)
      break

    case 'source': {
      const id = String(message.id || '')
      if (!id) return
      // `data` is a pre-serialised FeatureCollection: the payload can be 800
      // features, and handing MapLibre a string avoids a second encode.
      let data = message.geojson
      if (typeof message.data === 'string') {
        try {
          data = JSON.parse(message.data)
        } catch (e) {
          return
        }
      }
      if (!data) data = { type: 'FeatureCollection', features: [] }
      const existing = map.getSource(id)
      if (existing && existing.setData) {
        existing.setData(data)
      } else {
        map.addSource(id, { type: 'geojson', data })
      }
      ensureLayer(id, message.color)
      break
    }

    case 'layer': {
      const id = String(message.id || '')
      const target = 'lyr-' + id
      if (id && map.getLayer(target)) {
        map.setLayoutProperty(target, 'visibility', message.on === false ? 'none' : 'visible')
      }
      break
    }

    case 'fly': {
      const options = { center: [message.lon, message.lat], zoom: message.z }
      if (message.ms === 0) {
        map.jumpTo(options)
      } else {
        options.duration = message.ms || 900
        map.flyTo(options)
      }
      break
    }

    case 'view': {
      // Restore a camera without animating: used when the page has just loaded
      // and the app remembers where the operator was looking.
      map.jumpTo({ center: [message.lon, message.lat], zoom: message.z })
      zoomOffset = map.getZoom() - fitZoom()
      break
    }

    case 'focus': {
      const id = String(message.id || '')
      const source = map.getSource('focus')
      if (!source || !source.setData) return
      if (!id || typeof message.lat !== 'number' || typeof message.lon !== 'number') {
        source.setData({ type: 'FeatureCollection', features: [] })
        return
      }
      source.setData({
        type: 'FeatureCollection',
        features: [
          {
            type: 'Feature',
            geometry: { type: 'Point', coordinates: [message.lon, message.lat] },
            properties: { __id: id }
          }
        ]
      })
      break
    }
  }
}

start()
