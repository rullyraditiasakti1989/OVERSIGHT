# GLOBE — Integration Plan

Porting the useful half of **God's Eye View** (`bilawalsidhu/gods-eye-view`, MIT) into
OVERSIGHT as file `08`. Not a port of the web app. A port of its **keyless open
sources** plus a globe renderer that speaks this app's design language.

Written after reading the repo at `main` (clone at `%TEMP%\gev`, 115 MB, MIT) and
probing every upstream feed live on 2026-11-22.

---

## 1. Scope decision

God's Eye View is a Vue 3 + CesiumJS browser app with 31 layers, 481 data modules, an
MCP server, voice, cockpit and shaders. Almost none of that belongs on a phone.

**What we take:** the keyless feed layer and a globe.
**What we leave:** everything that needs a server, a key, or a GPU the phone does not have.

| GEV capability | Verdict | Why |
|---|---|---|
| Live flights (adsb.lol) | **TAKE** | Keyless, 54 KB, verified 129 aircraft |
| Satellites (CelesTrak TLE) | **TAKE** | Keyless, native HTTP has no CORS problem |
| Earthquakes (USGS) | **TAKE** | Keyless, 231 events, already half-ported in `PublicIntel` |
| CCTV cameras | **TAKE — ours** | We already ship 22,036 Indonesian entries; GEV has 7,350 global |
| Rocket launches (Launch Library 2) | **TAKE** | Keyless bonus ledger |
| Cyclones (NHC) | **TAKE** | Keyless bonus ledger |
| Active fires (NASA FIRMS) | **DEFER** | Needs `FIRMS_MAP_KEY` — wire to Settings key store, off by default |
| Vessels (AISStream) | **DEFER** | Needs key + persistent WebSocket |
| Wind (GFS/IFS GRIB2) | **SKIP** | Byte-range GRIB2 decoder is a project of its own |
| News conflicts (GDELT zip) | **SKIP** | Zip inflate + CAMEO coding, low value on a phone |
| Rain radar / lightning (nowCOAST WMS) | **SKIP** | Raster WMS overlays fight the globe renderer |
| Traffic simulation, transit GTFS-RT | **SKIP** | Protobuf + per-feed admission control |
| Street Level, radio playback, ALPR | **SKIP** | Out of character for a dossier console |
| 3D fleet models, cockpit, FLIR shaders | **SKIP** | Phone GPU budget |
| MCP server, voice, Director | **SKIP** | No server, no mic in scope |
| Natural Earth / datacenters / dams / cables | **MAYBE LATER** | 2.5 MB datacenters is a nice static layer; not phase 1 |

**Net:** six live layers, one of which (cameras) we already own and GEV does not have
for Indonesia. That is the differentiator — a globe that shows Indonesian CCTV on the
same plate as global aircraft.

---

## 2. Verified upstreams

Every URL below returned `200` from this machine today, keyless, with a descriptive
User-Agent. No guessing.

| Layer | URL | Bytes | Note |
|---|---|---|---|
| Aircraft (military) | `https://api.adsb.lol/v2/mil` | 54 KB | 129 aircraft, ODbL |
| Aircraft (radius) | `https://api.adsb.lol/v2/lat/{lat}/lon/{lon}/dist/250` | ~8 MB cap | 250 NM |
| Earthquakes | `https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_day.geojson` | 166 KB | 231 features |
| Satellites | `https://celestrak.org/NORAD/elements/gp.php?GROUP={g}&FORMAT=tle` | 2–26 KB/group | TLE, needs UA |
| Launches | `https://ll.thespacedevs.com/2.3.0/launches/?limit=20&mode=list` | 1 KB | 8,155 total |
| Cyclones | `https://www.nhc.noaa.gov/CurrentStorms.json` | 24 B | empty = no storms |
| Basemap tiles | `https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}` | JPEG | `Access-Control-Allow-Origin: *` |

CelesTrak sends no CORS headers. **Irrelevant here** — the fetches happen in Kotlin
over `HttpURLConnection`, not in the WebView. Native HTTP has no same-origin policy.
That single fact removes GEV's entire Node proxy tier from the port.

CelesTrak groups to load: `stations, visual, gps-ops, galileo, geo, resource, weather, science`.
Skip `active` (2.7 MB) and `starlink` (thousands of objects, no value on a phone).

---

## 3. Architecture

```
app/src/main/java/com/vector/console/
├── data/
│   ├── Store.kt              # cases, keys, globe layer mask/cadence/stats
│   ├── TrackStore.kt         # persistent watch state
│   └── globe/
│       ├── GlobeModel.kt      # LayerId, Contact, Field, LayerSnapshot, GlobeSnapshot
│       ├── GlobeGeo.kt        # NM/degree, bbox snapping, antimeridian, formatting
│       ├── GlobeFeeds.kt      # six fetchers over Net, each returning a parsed snapshot
│       ├── Sgp4.kt            # SGP4/SDP4 propagator (port of satellite.js, MIT)
│       ├── TleStore.kt        # disk cache, 6 h TTL, serve-stale on failure
│       ├── Json.kt            # strict non-throwing JSON reader (org.json is a test stub)
│       ├── GlobeGeoJson.kt    # hand-built FeatureCollection for the bridge
│       └── GlobeCredits.kt    # attribution strings, one per source
├── ui/screens/
│   ├── GlobeScreen.kt        # head, well, layer rail, selection card, ledger, states
│   └── SettingsScreen.kt     # KEYS, GLOBE SOURCES, GLOBE CREDITS, Phone-X pool
└── ui/components/
    └── GlobeCanvas.kt        # AndroidView + asset-origin WebViewClient + JS bridge
app/src/main/assets/globe/
├── index.html
├── globe.js                  # our renderer script, ~400 lines
├── maplibre-gl.mjs           # 1.10 MB, v6.19.0
├── maplibre-gl-worker.mjs    # 0.55 MB
└── maplibre-gl.css           # 0.07 MB
```

Total new asset weight: **1.72 MB**. No native `.so`, no Maven dependency, so the
offline Gradle build stays intact.

### 3.1 Why MapLibre GL JS in a WebView, not Cesium, not MapLibre Native

| Option | Globe | Size | Risk |
|---|---|---|---|
| **MapLibre GL JS 6.19** (chosen) | `projection: "globe"` confirmed in the style spec | **1.72 MB** | WebView WebGL2 |
| CesiumJS 1.147 trimmed | Best globe | 8.1 MB | WebView WebGL2, thermals |
| MapLibre Native 13.7.1 | Mercator only in stable | 12.4 MB `.so` | none |
| MapLibre Native `-globe` pre-release | Globe, style-JSON only | 12.4 MB | alpha, no terrain |
| Mapbox v11 | Globe | ~14 MB | per-MAU billing |

Cesium is 4.7× the weight for atmosphere and 3D tiles we will not use. MapLibre
Native stable cannot do a sphere at all. MapLibre GL JS gives a real globe, Esri
satellite raster, GeoJSON point layers, and globe-aware bearing in 1.7 MB.

The WebView is **not** a web page in a box — §4 of the design section forces frame
ticks, a coordinate plate, a scale bar and a live stamp over it, and the canvas
resizes rather than being overlaid.

### 3.2 Bridge protocol

Kotlin drives, JS renders. Nothing is fetched from inside the WebView.

Kotlin → JS (`evaluateJavascript`, JSON):
```
{ "t": "source", "id": "aircraft", "geojson": {...} }   // replace a source
{ "t": "layer",  "id": "fires", "on": false }           // visibility
{ "t": "fly",    "lat": -6.2, "lon": 106.8, "z": 6 }
{ "t": "focus",  "id": "sat:25544" }                    // highlight one feature
{ "t": "theme",  "ink": "#060708", "bone": "#F3F5F4", "seal": "#E05A48" }
```

JS → Kotlin (via `WebViewCompat.addWebMessageListener` on the asset origin):
```
{ "t": "ready" }
{ "t": "camera", "lat": 51.5074, "lon": -0.1278, "z": 4.2, "b": 0, "p": 0 }
{ "t": "pick",   "layer": "aircraft", "id": "a1b2c3", "lat": 20.19, "lon": -158.55 }
{ "t": "error",  "message": "webgl2 unavailable" }
```

`error` with `webgl2 unavailable` flips the screen to the native fallback plate.

### 3.3 Native fallback

If `canvas.getContext('webgl2')` is null (blocklisted GPU, hardware acceleration off),
`GlobeScreen` renders a **flat plate**: `tacticalGrid()` background, an equirectangular
dot plot of the same snapshot, the coordinate plate, and the full ledger. No globe, no
apology — a mono line reads `SPHERE UNAVAILABLE · PLATE ONLY`. The ledger is the real
product; the globe is the view.

---

## 4. Screen design (from `better-design` pass)

Registered as `Screen.GLOBE("GLOBE", "09")`, rail cell after `LOAD`.

**Anatomy** — head 88dp / map well weight-clamped 240–420dp / layer rail 69dp /
selection card 0 or 152dp / ledger weight. The card never overlays the well; the map
resizes.

**Canvas framing** — 1dp `Hairline` border, `frameTicks(ControlLine, 18dp)` corner
brackets, top-left coordinate plate (`LAT … LON …` / `Z 04`), bottom-left scale bar
(3 × 8dp alternating segments), top-right `Seal` stamp `LIVE RASTER` / `04:12 UTC`.
Inset 16dp by default; full-bleed only on `EXPAND` with no card open.

**Layer rail** — reuse `ChoiceChip`, horizontally scrolling, six chips with
zero-padded counts: `AIRCRAFT 214` `SATELLITES 088` `QUAKES 006` `FIRES 041`
`CAMERAS 112` `VESSELS 000`.

**Selection card** — `PanelCard(header = callsign, trailing = "11 S AGO")` then
`DataRow` per field, fixed order per layer type, then `COPY COORD` / `CLOSE`.

**Copy** — `File 09 · Globe` / `Globe` / `Live tracks from open sources, drawn on one plate.`

Three anti-slop rules that bind:
1. No hero globe. The head is a ruled form **above** the well; nothing floats over
   the imagery except four mono plates.
2. No HUD cosplay. One bracketed surface, one ambient animation, zero motion at idle.
3. `Seal` appears exactly twice: the canvas stamp and an M5.0+ quake. A third use is drift.

---

## 5. Phases

Each phase ends in a build that compiles, lints clean, and passes tests. No phase
leaves a stub.

### F0 — Renderer proof (no features) — **DONE**

Shipped: `assets/globe/{maplibre-gl.mjs, maplibre-gl-worker.mjs, maplibre-gl.css,
LICENSE-maplibre.txt, index.html, globe.js}`, `GlobeCanvas.kt`, `GlobeScreen.kt`,
`Screen.GLOBE("GLOBE", "09")`. Gate passed on `LivinPixel`: sphere renders
(`SPHERE READY · 540–649 MS`), drag rotates (`LAT 2.5000 S` → `LAT 0.8520 N`), no white
flash, camera survives rotation and backgrounding, landscape full-bleed correct.

**Asset origin — `WebViewAssetLoader` was not usable.** `androidx.webkit` is not on the
classpath and cannot be fetched under `--offline`, so `GlobeCanvas` hand-rolls the same
semantics: `shouldInterceptRequest` answers `https://appassets.androidplatform.net/globe/*`
from `assets/globe/*` with an explicit MIME table. `.mjs` **must** return
`text/javascript; charset=utf-8` — anything else and the module is refused with no useful
error. `file://` and `loadDataWithBaseURL` both reject ES modules and workers, so a real
https origin is mandatory, not a preference. `maplibregl.config.WORKER_URL` is set
explicitly to the sibling asset rather than letting `import.meta.url` inference guess.

**Sphere fit — MapLibre scales the globe off viewport *width*.** A wide-short window clips
the limb, and there is no fit-the-sphere API. Measured on device: `limb = 117 × 2^zoom`
(zoom 1.84 → 419 px). `fitZoom()` inverts that constant to place the limb; `refit()` then
measures the true limb with `map.project({lng: c.lng + 90})` and corrects in a rAF, because
the constant drifts with the aspect ratio. Operator zoom is stored as an offset from the fit
(`zoomOffset`) so rotation preserves their scale. `minZoom` is `-2` so the whole earth fits a
short landscape plate.

**Two resize paths, both required.** `GlobeCanvas.onSizeChanged` re-measures and relayouts
the `WebView` (fixes the GL surface) and a JS `ResizeObserver` fixes the map inside it.
Neither alone is sufficient — the first alone leaves the map at the old size, the second
alone leaves a stale GL surface.

**Compose identity is call-site position.** Dropping a composable call shifts every
following sibling and destroys its state; for a `WebView` that means a page reload and a
lost camera. Two consequences, both load-bearing:
1. The tree is **identical** in portrait and landscape. An earlier version branched into a
   row and a column, which recreated the `AndroidView` and reset the globe. Only the header
   compacts now (`short = maxHeight < 320.dp` drops it, leaving the plate and status line).
2. Conditional chrome is wrapped in `ChromeSlot` / `TelemetrySlot` so removing it does not
   shift the siblings after it.

`MainActivity` declares `configChanges` for orientation so rotation does not recreate the
activity at all. GLOBE goes full-bleed in landscape (`screen == GLOBE && maxWidth >
maxHeight`): classification bar, top bar and telemetry strip drop, the rail stays.

**Bridge must hop to the main thread.** A `JavascriptInterface` method runs on a WebView
thread; a snapshot written off-main was not observed by Compose, which showed as a rendered
globe still reading `ACQUIRING RASTER`. `Bridge.post` now posts through
`Handler(Looper.getMainLooper())`.

**Scale bar.** `scaleMetres` originally stopped at 100 km — it drew a 0.6 px bar labelled
`100 KM` across the whole planet — and its 1/2.5/5 steps let the bar swing 60–150 px. Rewritten
to six steps per decade reaching millions of metres, locked by `GlobeScaleTest` (5 tests).

### F1 — Data layer — **DONE**

Shipped: `Json.kt`, `GlobeModel.kt`, `GlobeGeo.kt`, `Sgp4.kt`, `TleStore.kt`, `GlobeFeeds.kt`,
`GlobeCredits.kt`, plus `app/src/test/resources/globe/{adsb-mil.json, usgs-day.geojson,
celestrak-stations.tle}` recorded live on 2026-11-22 with the app's own User-Agent.

**68 tests across eight classes, all green** (`Sgp4Test` 10, `LiveFixtureTest` 3,
`GlobeGeoTest` 13, `AircraftParseTest` 9, `QuakeParseTest` 9, `FireParseTest` 7,
`TleParseTest` 7, `GlobeLayerTest` 10). Full suite: 189 tests, 24 suites, 0 failures.

**`org.json` is a stub on the JVM unit-test classpath.** Every other data module uses it
(`CctvCatalog`, `IndonesiaCctv`) and is therefore untestable; the globe parsers sit on a
hand-written `Json` reader instead — strict, non-throwing, `null` on truncation. That one
decision is what makes the parsers testable at all.

**SGP4 port — three findings, all load-bearing.**

1. `satellite.js` has two entry points and they are not interchangeable:
   `sgp4(satrec, minutesSinceEpoch)` and `propagate(satrec, date)`. Golden vectors must be
   generated with `sgp4`; calling `propagate` with a number produces `NaN` for every
   satellite because it runs the number through `jday()`. This cost two wrong generator
   runs before the source was read.
2. `dscom` keeps `sz12`, `sz22`, `sz32` and `z12`, `z22`, `z32` live across both passes of
   the solar/lunar loop, and the lunar pass is what survives to the return. An early draft
   aliased them into helper functions and lost the values; they are plain variables now.
3. Failure behaviour is part of the contract. Of the thirteen live station element sets,
   **two decay when propagated backward** from a July epoch to a November one and
   satellite.js reports error 6 for exactly those two. The Kotlin port drops the same two,
   and `LiveFixtureTest` pins that count — a stronger check than comparing positions,
   because it proves the error paths match too.

Golden vectors: four records × six timestamps, ECI to 2e-3 km and geodetic to 1e-6°.
The four cover near-earth full drag (`isimp = 0`), near-earth simplified (`isimp = 1`),
deep-space one-day resonance (`irez = 1`) and deep-space without resonance (`irez = 0`).

**Live payloads contradicted the predictions.** Recorded fixtures caught two assumptions
that would have shipped wrong:
- adsb.lol's military feed names the aircraft type `type`, not `t` (the point feed uses
  `t`; the parser now reads either), and **41 of 70 contacts carry no position at all** —
  they are dropped rather than placed at 0,0.
- `alt_baro` really does arrive as the string `"ground"` for 8 of the 70.

**`bboxFor` rounded inward in the southern hemisphere.** A single sign-based "snap
outward" helper is wrong: the north edge of a box below the equator is negative, so
`floor` moves it inward and silently clips the contacts nearest that edge. Split into
`snapDown` / `snapUp`, with the southern-hemisphere case pinned by `GlobeGeoTest`.

**Vessels has no transport.** AISStream is a websocket subscription and this app has no
websocket client. Rather than ship half a transport, `GlobeFeeds.vessels` returns an empty
snapshot carrying `AISStream websocket not enabled`; F4's SOURCES row renders that note
verbatim. Fires is real (FIRMS area CSV, header read rather than assumed) and needs only
a `MAP_KEY`.

**Gate met:** all tests green, and each keyless fetcher was exercised against a live
response — recorded as a fixture so the check keeps running offline.

### F2 — Screen — **DONE**

Shipped: `GlobeScreen.kt` wired to `GlobeFeeds` snapshots, `GlobeGeoJson.kt`,
`GlobeCanvas.features/focus/layerVisible`, one circle layer per source in `globe.js`,
and a focus ring as its own one-feature source. Gate passed on `LivinPixel`: six chips
toggle, counts match the dots, ledger rows open a field card, landscape keeps the full
bleed. Screenshots: `shots/f2-final.png`, `f2-cameras.png`, `f2-fires.png`,
`f2-sel.png`, `f2-cached.png`, `f2-retry.png`, `f2-land.png`.

**The bridge payload is hand-written JSON.** `JSONObject(geojson)` parses and re-encodes
the entire document on the main thread; with the camera dataset that is 20,532 features.
`GlobeHandle.features` sends the FeatureCollection as a JSON *string* and the page parses
it once. Coordinates are fixed at 5 dp (1.1 m) and non-finite/out-of-range points are
dropped — `NaN` is not valid JSON and MapLibre throws inside `setData`, killing every
layer. `GlobeGeoJsonTest` (8 cases) pins longitude-first order, escaping and the caps.

**One source, one layer.** A `source` message that arrives before its `layer` exists
draws nothing, which looks exactly like an empty feed. `ensureLayer(id, color)` now runs
in the same message, with severity as a paint expression so `Seal` has one home.

**Stale means keep the last known contacts, not zero.** Every fetcher catches its own
network error and returns an empty, stale snapshot. Left as-is, one timed-out adsb.lol
request turned 18 aircraft into `00` — indistinguishable from an actually empty sky.
`LayerSnapshot.withPrevious` carries the last good contacts forward, keeps `stale = true`
and the ledger header says `CACHED`. Verified by airplane-mode toggle: 18 aircraft held
through the outage, and `RETRY` restored live altitudes. A `CancellationException` is
re-thrown, never caught — catching it marks a layer stale on the way out and writes
Compose state from a dead scope.

**First sight of a layer must fetch immediately.** `toggle()` set a `pending` flag for the
next refresh, but the loop was already asleep in a 60 s `delay`, so the chip read `--`
for up to a minute. Both `toggle()` (on first sight) and `RETRY` bump a restart key that
re-launches the resumed-only loop.

**Only changed snapshots cross the bridge.** The first cut pushed all six layers on every
tick — several megabytes a minute once CAMERAS is on. A `pushed` map keyed by snapshot
identity skips unchanged sources; it is cleared on `ready` so a reloaded page still gets
the full set. Visibility is tracked separately so a chip toggle stays a one-message flip.

**Camera centre and aircraft anchor are one value.** The radius query starts at Jakarta
(`-6.2, 106.8`), which lands on real traffic (14–18 aircraft inside 150 nm). The earlier
default of `-2.5, 118` sat in the Makassar Strait and opened the screen on a truthful but
useless `No contacts`. The camera reports its centre on every move and that value is
stored, so a drag never races the fetch coroutine.

**`RETRY` is a text key, not a 46 dp button.** The status line is one ruled strip; a full
button broke it in two and the label wrapped to `RETR/Y`. `softWrap = false` and a weighted
label keep the line intact.

**Gate met:** 200 tests / 25 suites / 0 failures, debug + release APKs, lint clean.

### F3 — Interaction — **DONE**

Shipped: JS `pick` → `GlobeEvent.Picked` → ledger card, one-feature `focus` ring,
`COPY COORD`, `EXPAND` / `COLLAPSE`, and `clear` on an empty-water tap. Gate passed on
`LivinPixel`: tapping AXM384 opened TYPE / REG / ALT / GS / HDG / SQUAWK / HEX with the
ring on the sphere; tapping the black corner of the well closed the card and cleared the
ring; `EXPAND` gave a full-bleed plate with the rail intact and `COLLAPSE` restored the
head, rail and ledger. Screenshots: `shots/f3-card.png`, `f3-clear.png`, `f3-expand.png`.

**`clear` is a page message, not a Compose guess.** The page knows whether a tap hit a
vector feature; Kotlin does not. Without `post({t:'clear'})` on a miss, the card survived
a tap anywhere on the sphere and read as a stuck control. `Bridge.decode` maps it to
`GlobeEvent.Cleared`, which only clears the selection.

**`EXPAND` is a real state, not a rotation fake.** The flag is saved in `VectorApp` and
feeds `plate` alongside the landscape rule; `GlobeScreen` uses the same flag to drop its
head, rail and ledger. The status line stays outside that gate so `COLLAPSE` is always
reachable. The first cut gave the status text a `weight`, which squeezed
`SPHERE READY · 722 MS · BEARING 0°` into `BEAR...`; the fixed keys now take their natural
width and only the `CACHED` label flexes.

**One more layer rule found.** A source arriving before its layer draws nothing, which is
indistinguishable from an empty feed. `ensureLayer` now creates the circle layer in the
same `source` message; severity is a paint expression, so `Seal` stays in one place.

**Gate met:** all six chips toggle, ledger counts match the dots, focus ring follows the
selection, empty water closes it, `COPY COORD` writes `lat, lon`, expand/collapse round-trips.

### F4 — Persistence, cadence, keys — **DONE**

Shipped: `Store` keys `globe_layers` / `globe_cadence` / `firms_key` / `ais_key`,
resumed-only refresh loop driven by the stored cadence, and Settings **GLOBE SOURCES**
(layer switches, per-source status, cadence, key fields) plus **GLOBE CREDITS** from
`GlobeCredits.ALL`. Gate passed on `LivinPixel`: after a visit to GLOBE the SOURCES rows
read `AIRCRAFT LIVE · 17 · 9.4 KB`, `SATELLITES LIVE · 800 · 141.2 KB`,
`QUAKES LIVE · 220 · 154.3 KB`; turning QUAKES off and cadence to 30 s survived a
force-stop and relaunch; a dummy FIRMS key produced a real request and
`Request failed (HTTP 400). Invalid MAP_KEY.` on the plate instead of the keyless note.
Screenshots: `shots/f4-stats.png`, `f4-toggled.png`, `f4-firms.png`, `f4-sources.png`.

**Layer state lives in `Store`, not in the screen.** `GlobeSession.enabled` is a getter
over `store.globeLayers`; the chip writes straight through. Reading a local copy made
Settings changes invisible until the screen was recreated. An empty stored mask is a real
choice ("everything off"), so only an *unreadable* value falls back to the default.

**Cadence is a `LaunchedEffect` key.** Changing 60 s → 30 s in Settings restarts the
resumed loop on the next visit, no app restart. Stats are in-memory only — after a cold
start the honest first row is `NOT FETCHED`, not a stale timestamp pretending to be live.

**Byte counts are source bytes, not plate bytes.** `LayerSnapshot.bytes` is set from the
HTTP body length in each network fetcher; bundled TLE/camera sources report their
serialised record length, and VESSELS reports `LOCAL`. `sourceStatus()` formats B/KB/MB
and prints the fetch time in UTC so it can be compared with the plate stamp.

**AISStream keeps its key but not a transport.** A key without a websocket client is not a
layer, so VESSELS now reads Digitraffic instead — see the addendum below.

**Gate met:** 200 tests / 25 suites / 0 failures, debug + release APKs, lint clean.

### F4.1 - Both "keyed" layers go keyless - **DONE**

Shipping a layer that never draws because it wants a key is a promise, not a feature. Both were
rewritten to work with no key at all, and the key became an accelerator rather than a gate.

**FIRES without a MAP_KEY uses NASA's own global export.**
`firms.modaps.eosdis.nasa.gov/data/active_fire/modis-c6.1/csv/MODIS_C6_1_Global_24h.csv` is the
same detection product the area API serves, whole planet, last 24 h, no key. Measured: 870 KB,
`HEAD` 200, header `latitude,longitude,brightness,...`. The area API wants a key per account and
answers `Invalid MAP_KEY` otherwise; the file wants nothing. The trade is printed in the note:
`MODIS global 24 h`, because a fire that started ten minutes ago is not in a 24 h snapshot yet and
saying otherwise would be a lie in a small font.

**It is cached for ten minutes, not re-fetched per poll.** `globalFiresCache` holds
(arrival millis, parsed contacts, byte length) for the process lifetime and is filtered to the
view on every call, so a 60 s cadence costs six downloads an hour instead of sixty. On a failed
refresh the cache is served with `stale = true` — the same carry-forward rule as every other
layer, applied to a file rather than a feed.

**VESSELS reads Digitraffic, keyless and CC BY 4.0.**
`https://meri.digitraffic.fi/api/ais/v1/locations` is Finnish open AIS as GeoJSON. Verified live
from the device: **781 contacts**, `MMSI 231930000 · SOG 0.0 KN · COG 036°`. Coverage is Finnish
waters only, so the stamp reads `Baltic · Finnish AIS` — an empty Atlantic must read as "out of
coverage", never as "no ships".

**Digitraffic answers `406 Not Acceptable` without `Accept-Encoding: gzip`.** Android's
`HttpURLConnection` negotiates gzip on its own and decodes transparently, but only while the app
does not set the header itself. `Net.getGzip` asks outright and unwraps the stream by hand, because
setting the header disables the platform's decode. `Digitraffic-User: OVERSIGHT/1.0` rides along —
the service documents it as required. Two `Invoke-WebRequest` attempts failed 406 for exactly this
reason before the header was added; the curl probe with `--compressed` was the one that worked.

**`navStat` is a real field, not a flag.** `navStat 5` renders `MOORED`, `0` renders
`UNDER WAY · ENGINE`, and 15 renders `UNDEFINED` rather than blank — an AIS transmitter saying
"undefined" is information.

**Credits follow the sources.** `GlobeCredits` grew a Digitraffic line and the FIRMS line now says
the key narrows the query instead of gating it. `REQUIRES_KEY` still lists both, now as optional.

**Gate met:** 207 tests / 26 suites / 0 failures.

### F5 — Verify & ship — **DONE**

Full gate green: `testDebugUnitTest assembleDebug assembleRelease lintDebug --rerun-tasks`
→ `BUILD SUCCESSFUL`, **200 tests / 25 suites / 0 failures**, only the two pre-existing
`Theme.kt` `statusBarColor` / `navigationBarColor` deprecation warnings. APKs:
`app-debug.apk` 63,331,363 B, `app-release.apk` 55,754,773 B. Release installed on
`LivinPixel` (uninstall first — debug and release signatures differ).

Walked: home index (9 entries) → GLOBE → six-layer rail → live ledger → field card →
focus ring → `COPY COORD` → empty-water clear → `EXPAND`/`COLLAPSE` → portrait/landscape
rotation → airplane-mode stale/cached → `RETRY` recovery → Settings SOURCES, cadence,
keys, CREDITS → force-stop/relaunch persistence. Screenshot set in `shots/`.

**Critique pass — squint test:** at arm's length the hierarchy still reads in four bands
(head, plate, rail, ledger). No band competes; the plate is the only image and the only
non-square surface. `Seal` stays confined to the stamp, the severity bar and the retry key.

**Swap test:** mono carries every label/index/metric, sans carries prose and callsigns.
Swapping either would erase the form-vs-data distinction — `AIRCRAFT 24` reads as an
index, `Tambun Selatan` reads as a name. No icon tiles, no rounded cards, no glow.

**Slop scan:** no gradients, no glassmorphism, no tracked-caps eyebrow above the title,
no "welcome" copy, no helper text narrating a control. Every button names its action.
The only ambient animation is the 2dp acquire sweep, and it stops when the data lands.

**Left on the bench:** FIRMS `MAP_KEY` (optional now) and the AISStream websocket (optional now).
The Identity → Phone walkthrough is done — see `docs/IDENTITY.md`. None of them block this phase.

---

## 5B. F6 - RAIL - **DONE**

Layer seven. Indonesian rail on the plate: trains where the timetable says they are, plus the
service-alert ledger. The spec below is kept alongside the measured result so the decisions stay
auditable. Measured on device: `RAIL 228`, `TIMETABLE + ALERTS 4 · 228 RUNS · SNAPSHOT 2026-11-20 ·
BUNDLED 23 MS` (23 ms warm, 110 ms on the first cold decode).

### 5B.1 Source, measured

`KAISTREAM` — `kaivyy/KAIStream`, **MIT**, © 2026 Kaivy and contributors. A Java-wide rail
tracking and schedule platform. Its data directory is the whole dependency:

| File | Size | Use |
|---|---|---|
| `metadata.json` | 8.7 KB | snapshot date, provider list, decree (`KP-DJKA 116/2026`, GAPEKA Jawa 2026) |
| `timetable.json` | 2.3 MB | every train, every stop, seconds and cumulative km |
| `stations.json` | 65 KB | `stationCode` → name and coordinates |
| `alerts.json` | 8.6 KB | active service disruptions, Indonesian text |
| `routes.json` | 6.5 MB | per-shape geometry (not needed for v1) |
| `rail-network.json` | 4.3 MB | the whole graph (not needed for v1) |

**Keyless, and CDN-served.** `https://cdn.jsdelivr.net/gh/kaivyy/KAIStream@main/public/data/<file>`
answers 200 with no key, no token, no cookie. `metadata.json` reports `autoSync` daily at 00:05 WIB
and `coverage: Pulau Jawa (6 Provinsi)` with `timezone: Asia/Jakarta`, `utcOffsetHours: 7`.

**Verified dead ends, again:** `www.kci.id/api/krl/*` answers `403 cf-mitigated: challenge`;
`api.kai.id/*` is gone. `mufid/krl-realtime` is marked obsolete in its own README — KAI closed the
API it wrapped. The schedule file is the only honest source left, because the operator publishes the
timetable and does not publish positions.

### 5B.2 Data shape, as served

```
{"trains":[{"id":"krl_2263","number":"2263","name":"Commuter Line Tanjungpriuk",
  "category":"commuter","operatingPattern":"daily","shapeId":"shp_7b0bb5c204a5",
  "originCode":"TPK","destCode":"JAKK","startSec":73800,"endSec":74760,"totalDistKm":8.09,
  "stops":[{"stationCode":"TPK","arrSec":null,"depSec":73800,"distKm":0.0},
           {"stationCode":"KPB","arrSec":74495,"depSec":74520,"distKm":6.4311},
           {"stationCode":"JAKK","arrSec":74760,"depSec":null,"distKm":8.0852}]}]}
```

- **Seconds since midnight, WIB (UTC+7).** `73800` is 20:30 local. Convert once at the boundary:
  `wibSeconds = (utcMillis/1000 + 7*3600) % 86400`. Do not do this arithmetic per train.
- `arrSec` is null at the origin, `depSec` null at the terminus — a stop is a dwell, and the dwell
  is `depSec - arrSec`. Position inside a dwell is `distKm` of that stop, not an interpolation.
- `distKm` is cumulative along the trip, so between two stops the fraction is
  `(t - depSec(a)) / (arrSec(b) - depSec(a))` and the distance is
  `distKm(a) + f * (distKm(b) - distKm(a))`. Linear in distance is the right model: the timetable
  publishes no speed profile.
- `passPoints` (Whoosh only) are `[second, distKm]` pairs for through-movements without a stop —
  optional in v1, listed here so the parser does not choke on the key.
- `category` carries `commuter`, `highspeed`, and the KA classes. The rail chip counts trains, and
  the card names the category; no filtering by category in v1.

### 5B.3 Position needs coordinates, and coordinates need a join

`timetable.json` carries `stationCode` only. `stations.json` is the join table, and the position of
a train between two stops is the linear interpolation **between the two station coordinates**, not
along `routes.json`. That is a straight line across a curve, and it is wrong by up to a few hundred
metres on a sharp bend — acceptable for a dot on a phone-sized sphere, and honest because the layer
is already stamped as a schedule, not a GPS fix.

If a station code is missing from the join table, drop the leg rather than invent a point. Count
those drops and put the number in the note, the way the camera layer reports "without coordinates".

### 5B.4 The stamp rule: `SCHEDULE`, never `LIVE`

This is the whole honesty question of the phase, and it gets one rule:

> **Every RAIL point carries `SCHEDULE`. The stamp reads `TIMETABLE` and is never allowed to read
> `LIVE RASTER`, `LIVE`, or a bare contact count.**

Concretely:

- Canvas stamp: `RAIL · TIMETABLE · {n}`. The existing `LIVE RASTER` stamp belongs to the basemap,
  which *is* live imagery; mixing the two words on one plate is the lie.
- Card stamp line: `SCHEDULE · GAPEKA 2026`, sourced from `metadata.json.officialDjakaDecree`.
- If the device clock is outside the snapshot date by more than a day, the stamp becomes
  `TIMETABLE · SNAPSHOT {date}` and the note says so. A schedule that is a week stale still draws
  trains, but it must not pretend to be today's.
- Alerts carry their own timestamp (`timestampWib`) and are never merged into the position model.

### 5B.5 Alerts as the ledger

`alerts.json` is an array of:

```
{"id":"alert-1687","title":"KA 12 Turangga Gangguan Lokomotif",
 "description":"...", "location":"Stasiun Cicalengka - Nagreg, KM184+6",
 "category":"keterlambatan","severity":"warning",
 "timestampWib":"2026-11-20 18:18:18","active":true,"source":"Dzikri Raihan Akbar"}
```

- `active: true` only. Inactive entries are history and the ledger is not a history view.
- Ledger rows for RAIL are **alerts first, trains second**: an alert is the reason a train is not
  where the timetable says, so it earns the top of the list.
- `severity: "warning"` earns the `Seal` bar; `"critical"` earns `Seal` **and** the severity ring on
  the sphere. Nothing else in this layer may use `Seal`.
- Description is Indonesian and stays Indonesian. Title and location are shown as served — the
  console transcribes, it does not translate.
- Row stamp: `TIMETABLE + ALERTS {n}` when `n > 0`, otherwise `TIMETABLE` alone. This is the exact
  string the phase was deferred with, and it is now a rule rather than a name.

### 5B.6 Architecture, and what gets bundled

```
data/rail/
  RailModel.kt      # Train, Stop, Alert, RailSnapshot, tripSeconds()
  RailFeeds.kt      # timetable() / stations() / alerts(), pure parsers + interpolation
  RailStore.kt      # disk cache, 12 h TTL, serve-stale (the TleStore pattern)
assets/rail/
  stations.json     # bundled, trimmed to code/name/lat/lon (~40 KB)
```

- `stations.json` is bundled because it is small and the join is needed on the first frame.
- `timetable.json` and `alerts.json` are fetched and cached to disk, 12 h TTL: the upstream syncs
  daily, so a 12 h cache costs two downloads a day and never serves a snapshot older than a day.
- `routes.json` and `rail-network.json` are **not** bundled and not fetched. 11 MB of geometry to
  draw lines the plate does not need.
- `RailStore` is process-independent: a cold start reads the cache and draws immediately, then
  refreshes. Same carry-forward rule as every other layer: `stale = true` keeps the last contacts.
- Positions are recomputed **every tick from the cached timetable**, not stored — the file is the
  schedule, the clock is the thing that moves. No network call is needed to advance a train.

### 5B.7 Costs, measured against the current build

- Download: 2.3 MB timetable + 8.6 KB alerts, twice a day.
- Parse: 2.3 MB of JSON through the hand-written `Json` reader. This is the one place where `Json`
  will be slow — measure it, and if it exceeds ~400 ms on `LivinPixel` move it to a one-time
  conversion into a compact binary/`SharedPreferences`-free flat file rather than parsing per tick.
  The mitigation is written down before the build, because a 2.3 MB reparse every 60 s would be a
  battery bug wearing a rail uniform.
- Contacts: one per train currently inside its `startSec..endSec` window. Expect a few hundred in
  the morning peak; cap at `RAIL_CAP = 400` and say so in the note.

### 5B.8 Gate — met

- `RAIL` renders on the plate; the top-right stamp reads `TIMETABLE` and counts only rail contacts
  (230), not the 2,046 actors from every layer. Zero `LIVE` wording anywhere in the rail layer.
- Positions come from `wibSeconds(now)` against the in-memory parsed schedule. A tick makes no
  network call and no disk read; the only thing that moves a dot is the clock.
- `alerts.json` has 10 rows, 4 active. They appear above the trains in the ledger with `Seal` bars,
  Indonesian text untranslated, and the row stamp `TIMETABLE + ALERTS 4`. `upstream` severity values
  are `warning`, `emergency` and `info` — the spec guessed `critical`, so the code marks
  `warning`/`emergency`/`critical` and leaves `info` neutral.
- `RailStore` uses a 12 h TTL on three files and serves the last good copy with `stale = true`; the
  status strip then shows `CACHED` and the note keeps the snapshot date.
- New `RailLayerTest` covers interpolation, dwell, null origin/terminus, missing joins, caps, JSON
  parsers, the compact round trip, alert filtering and the stamp seam. No device test for airplane
  mode was run this pass; the stale path is covered by `withPrevious` and `RailStore.document`.

### 5B.9 The 2.3 MB parse, measured and fixed

The first build parsed `timetable.json` through the hand-written `Json` reader once per cached file:
**3,486 ms on LivinPixel** (visible in the layer note as `PARSE 3486 MS`). That is the single worst
cold-start cost on the plate, even at once per process.

Fixed with a flat little-endian form, `RailModel.encodeTimetable` / `decodeTimetable`:
`magic, version, snapshot key, trip count`, then six strings and three numbers per trip and four
numbers per stop. Strings are an int byte count plus raw UTF-8, so the writer can be a build-time
script and the reader is 25 lines of `ByteBuffer`. The 2,490 trips compress to **729,092 bytes**.
`assets/rail/timetable.ovs` is built from the 2026-11-20 upstream snapshot; on device the same
schedule now loads in **23 ms** warm / **110 ms** cold from the bundled form. When the snapshot changes the
code falls back to the raw JSON once and writes a compact copy to `files/rail/timetable.ovs`, after
which that snapshot is a `DISK` load. The note names which path ran, so a slow first parse is never
hidden.

**Gate:** 224 tests / 28 suites / 0 failures, debug + release APKs, lint clean.
`shots/f6-rail.png`.

---

## 6. Risks

| Risk | Mitigation |
|---|---|
| WebGL2 missing / GPU blocklisted | Probe before init; native flat plate fallback (§3.3) |
| CelesTrak 403/429 | Descriptive UA, 6 h disk TTL, serve stale, one request per group per cycle |
| adsb.lol 429 | 12 s in-memory cache, cooldown clamp 30 s–5 min |
| `/v2/lat/…/dist/250` returns megabytes | Cap read at 8 MB, drop fields we do not render, 5 dp coordinates |
| SGP4 port diverges | Golden vectors from `satellite.js`, 1e-6 rad tolerance |
| Globe drains battery | RAF gated on interaction, no idle animation, render on data change only |
| Esri tile terms | Attribution line on the canvas + CREDITS in Settings; OSM fallback if Esri 404s |
| 1.7 MB assets in APK | Acceptable; release APK goes 52.5 → ~54 MB |
| GEV code drift | We port maths and URLs, not files. No upstream merge obligation |

---

## 7. Licences and attribution

- **God's Eye View** — MIT, © 2026 Bilawal Sidhu. Ported SGP4 logic and feed URLs carry a notice in `GlobeCredits.kt`.
- **maplibre-gl-js** — BSD-3-Clause. Licence text shipped in `assets/globe/LICENSE-maplibre.txt`.
- **satellite.js** — MIT, © Shashwat Kandadai / Dinamicka. SGP4 port credited.
- **Esri World Imagery** — attribution required on the canvas: `Imagery © Esri, Maxar, Earthstar Geographics`.
- **adsb.lol** — ODbL 1.0.
- **CelesTrak** — free, credit required.
- **USGS / NASA / NOAA / Launch Library 2** — public domain or open.
- **OpenStreetMap** — ODbL, fallback tiles.

A **CREDITS** block in Settings lists every one. That is the whole legal surface.

---

## 8. Definition of done

- `Screen.GLOBE` in the rail, index `09` (after `LOAD`).
- Six layers live: aircraft, satellites, quakes, fires (key-gated), cameras (ours), vessels (key-gated).
- Globe renders on device, falls back to the plate without crashing.
- Selection card, expand mode, coordinate plate, scale bar, stamp, ledger.
- 15 new unit tests green, lint 0 errors, debug + signed release APKs built.
- Credits visible in Settings.
- No new Maven dependency, offline Gradle build intact.
