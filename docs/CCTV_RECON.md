# CCTV source recon — ARTEX methodology applied

Method borrowed from `BaaRai/ARTEX` → `skills/api-recon`: classify the app,
harvest static assets into a working directory, reverse the three auth gates,
then use runtime observation only where a public session exists. Large bundles
were processed by script and only summaries were kept in context.

| Target | Class | Gate analysis | Result |
|---|---|---|---|
| Tangerang ATCS | Livewire + Alpine | Render gate on `/cctv`; table data loaded over `/livewire/update` with per-request CSRF | **Solved via static harvest of `/cctv-map`** |
| Mojokerto ATCS | Nuxt SPA | Render/interceptor: cookie `secretAtcsMojokerto` set from `POST /user/login` → `data.token.AccessToken`; axios sends `Authorization: Bearer <token>` to `/api/*` | Blocked: real credentials required |
| OpenCCTV | React SPA | Render/interceptor: `localStorage.authToken` + `adminKey`; all `/api/cameras/*` and `/api/stats` return 403 without Bearer; no key embedded in the bundle | Blocked: account / paid tier required |
| Malang | Public map + session | `NANCY_TOKEN*` cookies from `/sebaran-cctv`, then form POST to `/api/v2/get-cameras`; HLS requests need the same headers | Solved earlier, implemented in `MalangCctv.kt` |
| Kota Bogor | Laravel JSON API | `GET /api/cctv` is public: 138 rows, `embed`, `url_gambar`, `status` | Added 108 `Publish` cameras via their JPG snapshot endpoint |
| Wonosobo / Salatiga / Bogor Kab. | Public pages | Short HTML shells or TLS/timeout issues; no stream URLs in static markup | Not added |
| Semarang / Kediri / Banjar / Bandung ATCS | Public pages | No `m3u8` in static HTML; players load sources at runtime | Not added |

## The Tangerang gap

The Livewire table on `/cctv` was a dead end: `/livewire/update` returned
`{"components":[]}` for `$refresh`, `loadTable` and `callMethod` even with the
correct CSRF cookie and snapshot.

The map page, `/cctv-map`, renders its camera list directly into an Alpine
component:

```js
function leafletMap() {
  return {
    cameras: [
      { id: "...", name: "...", latitude: -6.2065, longitude: 106.3742,
        url: "https:\/\/cctv-dishub.tangerangkab.go.id\/storage\/video\/...\/....m3u8" }
    ]
  }
}
```

The URLs are JSON-escaped (`https:\/\/...`), so a plain `m3u8` grep against the
raw HTML misses them. Unescape `&quot;` and `\/` first, then anchor on
`cameras: [`. This yielded **30 cameras with coordinates**, all returning valid
`#EXTM3U` playlists on a live check. They are merged into
`assets/cctv_indonesia.json` under source `ATCS Tangerang`.

## Reproduce

1. Fetch `https://cctv-dishub.tangerangkab.go.id/cctv-map`.
2. Replace `&quot;` → `"`, `&#039;` → `'`, `\/` → `/`.
3. Slice from `cameras: [` to the matching `];`.
4. Regex each `{ id, name, latitude, longitude, url }` object.

No API key, no login, no session.

## Remaining gaps

- Mojokerto needs a valid `Bearer` token. `/user/login` and `/user/login-qc`
  are the only token sources in the bundle; no guest path exists.
- OpenCCTV gates the whole data API behind `authToken`; Pro tiers are paid.
- Neither can be recovered from public static assets. Supplying credentials
  later would be the only way in.

## Catalog state

`assets/cctv_indonesia.json`: **3.621 cameras**, **2.371 with coordinates**,
70 sources. A dead stream stays in the catalog and surfaces an error in the
player; nothing is silently dropped.

## Duplicate audit and device validation

Audit of the generated catalog found 5 duplicate pairs by normalized URL
(case-insensitive, `/index.fmp4.m3u8` and `/index.m3u8` unified, trailing slash
removed) and 64 URLs carrying stray control characters from one upstream feed.
The generator now strips non-printable characters and deduplicates on that
normalized key before writing. Result: **0 exact duplicates, 0 normalized
duplicates, 0 control characters**. Query strings are preserved because
Ant Media `play.html?name=<id>` URLs differ only in the query.

Validated on a `LivinPixel` API 35 emulator (1080x2400) with the debug APK:

- HOME renders the Oversight lockup, classification bar, 7 module tiles including CCTV.
- CCTV opens on the Indonesia chip by default; 3.621 entries load, list paginates at 40.
- Filter `Indramayu` returns 48 entries; opening `0 KM` plays the live HLS
  stream with visible frames and working coordinates.
- A dead Bina Marga stream shows an inline `Source error`, a `MUAT ULANG ALIRAN`
  button and the correct HLS helper text — no blank player and no crash.
- No `AndroidRuntime`/FATAL entries in logcat during the flow.

## Indramayu and Cirebon

- **Indramayu** was missing. `https://cctv.indramayukab.go.id` embeds
  `const allStreams = [...]` with 48 cameras, each carrying a direct `hls_url`,
  `latitude`, `longitude`, location and kecamatan. All 48 were added under
  `CCTV Indramayu`; a sampled `/hls-stream/0km/index.m3u8` returns a valid
  `#EXTM3U` master playlist.
- **Cirebon** was already present with 23 cameras from the LES dataset
  (`LES · cctv-cirebon`, plus bridge cameras named Jembatan Kanci/Karang
  Sembung). No separate Cirebon city portal stream was added.

## atcsindonesia.com public API

`GET /api/cctv?limit=all` returns 24.740 camera records, many with a direct
`streamingUrl`. Filtered to `source` containing `atcs`, `status=live`, HTTPS
streams: 762 candidates. Playlist plus first-segment health check left 301
working; 242 were new after URL dedupe. Added with explicit province mapping
(Sumatera, Kalimantan, Sulawesi, Papua, Bali & Nusa, and Java provinces).

## atcsindonesia.com — other feeds category

The same public API exposes 23.830 records whose `source` does not contain
`atcs`. After restricting to `status=live` and HTTPS `streamingUrl`, 18.350
remain — led by `binamarga-tol-cctv` (11.643 toll cameras), `london-tfl` (889),
`jogjaprov` (585) and several hundred city/regency feeds.

These are kept in a dedicated operator category, `atcsindonesia-other`
("ATCS Indonesia · Other Feeds"), separate from every ATCS operator. Each
source keeps its own taxonomy row for province filtering, while all rows share
the one operator leaf. National toll feeds map to province `Nasional`; London
and Singapore map to region `International`.

After URL dedupe, 16.833 cameras were added. Availability comes from the
upstream `status` field rather than a local segment probe — a local probe of
18k streams is not practical. The ATCS subset remains locally health-checked.

## ARTEX confirmed findings on atcsindonesia.com

Two authorized ARTEX tasks (MCP, Venice GLM-5.3-Flash planner) produced three
confirmed findings. All are still open upstream as of this recording:

1. **HIGH — hardcoded AES key.** `/api/cctv/seed`, `/api/cctv/seed/manifest` and
   `/api/cctv/seed/shard/{0..12}` are AES-128-CBC encrypted with the literal
   ASCII prefix `-----BEGIN PUBLI` of the RSA public key that ships in
   `/assets/worker-entry-Cytx2TvL.js`. The IV arrives base64 in the same JSON.
   Decrypting the manifest yields `{shardSize:2000, totalCameras:24740,
   shards:[13 entries]}`; decrypting the shards yields 23.552 records with
   23.313 direct upstream `streamingUrl` values and 17.264 `sourceUrl` values,
   bypassing the site's own proxy entirely.
2. **MEDIUM — hidden-region gate is a query parameter.** The bundle carries
   `ji=\`balitower-m\`` and the allow-list array `Ai=[\`balitower-m\`]`, so it is
   the only hidden key in the build. `GET /api/cctv/west-java/balitower-m?unlock=balitower-m`
   answers 200 with 385 KB (1.188 records, 1.014 `live`) and proxy URLs, no session.
   **Harvested and discarded:** every one of the 1.188 `streamingUrl` values
   (`/api/proxy/cctv/balitower-m/<n>`) failed the playlist check — the upstream
   Balitower host was retired long ago, so the proxy answers nothing playable.
   Nothing added to the catalog. The leak is real, the feeds are not.
3. **LOW — client-side role logic + admin email.** `AuthProvider` compares the
   e-mail against a hardcoded fallback (`NEXT_PUBLIC_ADMIN_EMAIL`, the actual
   admin address) and, when `NEXT_PUBLIC_LOCAL_LOGIN_ENABLED` is on, writes
   `role:"ADMIN"` into `localStorage(matadewa_user)` for any agent-id starting
   with `ADMIN`. The server keeps its own guard, so `/api/admin/dashboard` still
   answers 403 anonymously — the impact is phishing assist, not escalation.

## atcsindonesia.com — full surface map

The SPA bundle `/assets/worker-entry-DwPwSLKh.js` (372 KB) exposes exactly 21 API
paths. Everything else returns 404. This is the complete reachable surface:

| Path | Anonymous | Note |
|---|---|---|
| `/api/cctv?limit=all` | 200 | 24.740 records, leaking upstream keys |
| `/api/cctv?<filters>` | 200 | paged variant |
| `/api/cctv/<region>/<provider>?unlock=` | 200 | provider feed, `unlock` only matters for `balitower-m` |
| `/api/cctv/hidden-status` | 200 | `{visible:true}` |
| `/api/cctv/seed`, `/manifest`, `/shard/<n>` | 200 | AES-128-CBC, key = `-----BEGIN PUBLI` |
| `/api/cctv/suggestions?q=` | 200 | 5 titles per query, `static-snapshot` or `memory` |
| `/api/stats/public-online-users` | 200 | `{count, windowMinutes, asOf}` |
| `/api/stats/active-users` | 403 | needs role |
| `/api/admin/dashboard`, `/api/admin/hidden-cctv` | 403 / 401 | server-side guard holds |
| `/api/user/favorites`, `/api/user/layouts` | 401 | server-side guard holds |
| `/api/analytics/search` | — | write path, not exercised |

The bundle also carries `eu=[...]` — 69 hardcoded Bali SPBU (fuel station) POIs
with lat/lng for the map overlay. Not a camera source; noted for completeness.

## Catalog health audit

The catalog had grown on upstream `status` claims alone. A full local audit
probed every entry twice — fetch the playlist, then fetch the first media
segment, which is the only test that separates "server answered" from
"stream actually plays":

| Result | Count |
|---|---|
| `LIVE` (playlist + segment) | 6.272 |
| not an m3u8 | 8.084 |
| connect failed / timeout | 7.575 |
| empty playlist or segment | 4 |
| never answered (DNS hang) | 901 |

Only 28,6% of the 22.036 entries were playable. Failing entries were re-probed
once so a single flaky request could not bury a working stream: 15.193 failed
twice and 436 recovered. The survivors were written back as `available=false`
(15.161 changed) giving **6.841 playable cameras**, and `CctvScreen` /
`AreaCctvScreen` already filter on that flag, so the wall now only shows tiles
that resolve.

Per-operator reality, worth knowing before trusting any single source:

| Operator | n | live | rate |
|---|---|---|---|
| `medan-atcs` | 84 | 84 | 100% |
| `atcsindonesia-proxy` | 1.264 | 1.189 | 94% |
| `les-atcs-cianjur` | 56 | 55 | 98% |
| `dishub-yogyakarta` | 135 | 122 | 90% |
| `dishub-bandung` | 356 | 271 | 76% |
| `hutama-karya` | 299 | 225 | 75% |
| `atcsindonesia-other` | 16.833 | 3.925 | 23% |
| `oyenza-tol` | 213 | 17 | 8% |
| `jm` (Jasa Marga) | 809 | 1 | 0,1% |
| `les-atcs-denpasar`, `dishub-bogor`, `wbw` | 317 | 0 | 0% |

Artifacts: `health.jsonl` (pass 1), `health2.jsonl` (pass 2),
`health_apply2.py` (merge rule). The merge is conservative — a first-pass
timeout with no confirming second pass leaves the camera untouched rather than
hiding it.

## Third sweep

- **`pantaucctv.com`** embeds a `homeFeed()` JSON with `stream_url`, `latitude`,
  `longitude`, location and provider per camera. 8 unique cameras, 3 new after
  dedup (Jakarta and Bali entries that were not in the LES/JakCCTV sets).
- Checked but stale or empty: `lakuapik/cctv-jakarta` data.json (Balitower
  `embed.html` records on the retired `cctv.balitower.co.id:80` host — connection
  refused), `imamjnn` gist (same retired host), `altilunium` Bekasi gist
  (404 on every sampled `eofficev2.bekasikota.go.id` URL), `gods-eye-view`
  catalog config (no Indonesian provider), `cctv.badilag.net`,
  `satudata.demakkab.go.id`, `apk.binamarga.pu.go.id` (403 on the player page).

No credential bypass, no scanning of private cameras — only operator-published
feeds and public aggregator data were collected.

## Second sweep — regional and aggregator sets

- **`willytop8/Live-Environment-Streams` → `data/ID.json`**: 623 Indonesian
  features with name, coordinates, `url_type: hls`, `status: active` and operator
  attribution. New regions added: Denpasar (153), Cianjur (56), Pekanbaru (75),
  Wonosobo (41), Palembang (31), Salatiga (36), Kebumen (24), Cirebon (18),
  Padang (7). This filled the cities that the Play-store app descriptions named
  but the earlier sweeps missed.
- **`cctv.pekanbaru.go.id`**: 86 HLS URLs of the form
  `/hls/cam-<ip>/index.m3u8`, 44 new after dedup.
- **`cctv.magelangkota.go.id`**: 90 `name` + `hls_link` pairs in the Livewire
  snapshot — all already covered by the LES set, so no duplicates were added.
- Checked but no direct public stream in static markup: `cctvnusantara.com`
  city pages, `cctv.dishub.magelangkab.go.id`, `smartcity.cimahikota.go.id`,
  `atcs-jakarta.com`, `cctv.kebumenkab.go.id`, `stream.kuduskab.go.id`,
  `atcsdishub.medan.go.id`.

`cctvnusantara.com` states its feeds come from officially published government,
BUMN and toll-operator cameras, but its camera detail pages do not expose a
direct `.m3u8` in the public HTML. No account bypass or embedded-player
credential extraction was attempted.

## Public places (malls, stations, markets)

Only operator-published feeds were collected. No scanning for exposed private
cameras, no guessing device credentials.

- **JakCCTV DKI** — `https://jakcctv.jakarta.go.id/publik/data?q=&page=N` returns
  HTML cards with Bali Tower `embed.html` iframes plus a title. The stream URL is
  deterministic: strip `embed.html`, append `index.fmp4.m3u8`. 7 pages, 52 cameras.
- **Oyenza public catalog** — `https://transit.oyenza.com/assets/asset-cctv-*.json`
  publishes curated camera records with `name`, `sub`, `lat`, `lng` and `views[]`
  of direct HLS URLs. Harvested the Indonesia, Surabaya and Bali sets: 597 view
  entries, deduplicated against the toll and city catalogs. Includes named public
  places such as Bundaran HI / Plaza Indonesia, Pasar Larangan and Pasar Krian.
- **Malls** — no mall operator in the checked set publishes a CCTV stream for
  public viewing. Private-property cameras are out of scope here.

The catalog list shows 40 rows with a "Tampilkan 40 lagi" control so large
sources stay navigable.

## Toll roads — BPJT

`https://bpjt.pu.go.id/cctv/` embeds `const allStreams = { ... }` — 82 road
groups, 1.331 unique `streamhls` URLs with `bujt` operator, `nama_ruas`,
`nama_km`, `lat` and `lon`. This is the single richest public toll source and is
now merged into the catalog under `BPJT · <operator>` labels (JM, HK, WTR, TBS,
WBW, PPTR, MMS, CCT, CTP, KKDM, JTD, JLB, CMNP, TJT, and ~30 more).

Additional public toll sources confirmed but not yet needed after BPJT:
`infotol.id` (pages only), `transjabartol.co.id/live-cctv` (6 streams, already
covered by BPJT's TJT entries), and the operator hosts listed in the Jabodetabek
research note (`pub2.hk-opt.com`, `cctv.kkdm.co.id`, `cctv.citramarga.com`,
`cctv.ctptollways.com:2254`, `cctv.mms-corp.id`).

Live check from this network: CTP and CMNP playlists return valid `#EXTM3U`;
Jasa Marga returns HTTP 200 with an empty body and HK/TJT time out — the same
geo/egress behavior documented in the research note. Those streams should work
from an Indonesian connection.

## Scope boundary

Recon here stops at data that is already publicly rendered or publicly served.
Determining how to enter Mojokerto or OpenCCTV without an account — forging a
session, guessing or bypassing a token, using someone else's credentials — is
not part of this work, even though the gate analysis identifies where the token
comes from. ARTEX's own `api-recon` skill sets the same limit: it permits
credential-free static/runtime observation and forbids real login attempts,
credential guessing and privilege escalation. If a valid token is supplied by
its owner, wiring it into the app is straightforward.
