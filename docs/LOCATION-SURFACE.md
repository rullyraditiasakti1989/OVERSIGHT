# LOCATION SURFACE — what a smartphone actually leaks

Companion to `TrackStore` / `TrackFeeds` / `DeviceLocation`. That code answers *"how do I
keep a track once I have a position source"*. This document answers *"where do position
sources come from"* — all of them, classified by who holds the data and what it costs to
get it.

Read this before adding a `TrackKind`. The architecture question is never the store; it is
which channel feeds it.

---

## 1. The structural fact

There are exactly **four** places a phone's position exists:

| Surface | Who holds it | Reachable by |
|---|---|---|
| **ON_DEVICE** | the handset itself | code running on it, with permission |
| **CARRIER** | the mobile operator's core network | lawful process, or SS7 access |
| **PLATFORM** | Google / Apple account + crowd-sourced beacon network | account credentials, or a paired accessory |
| **RF** | anyone with a receiver in range | physical proximity + radio hardware |

That is the whole map. Every technique people name — triangulation, IMSI catcher, Find My,
"exploit the phone number" — is one of those four wearing a different hat.

**Persistence without the target's cooperation exists only on CARRIER and PLATFORM.**
Everything on ON_DEVICE requires installing or running something on the handset.
Everything on RF requires being physically near it. No API changes that.

A phone number is a CARRIER-surface key. It gets you an HLR lookup (which MSC is the
subscriber attached to — a city, sometimes a district) and nothing continuous. That is
why `TrackKind.PHONE` has no public poller: there is no public feed. It is not a gap in
this app, it is the shape of the network.

---

## 2. ON_DEVICE channels

Runs on the handset. Ordered by how much permission it needs, least first — because the
interesting ones are the ones that need nothing.

### 2.1 IP address — `IP`
- **Precision** 5 000–25 000 m (city / ISP head-end)
- **Permission** none
- **Cadence** every request
- **Keyless** yes — `api.beacondb.net/v1/geolocate` with `considerIp: true`, verified
  2026-11-22 (`accuracy: 25000`, `fallback: "ipf"`, DB-IP Lite under CC BY 4.0)
- **What it actually tells you** which ISP egress the device is behind. Defeated by any
  VPN, and it is the *only* channel that survives location being switched off.
- **In this app** `LocationResolver.parse()` refuses an `ipf` fallback unless the caller
  explicitly asks; `isUsable()` rejects anything coarser than 5 km. A city is not a fix.

### 2.2 Wi-Fi BSSID fingerprint — `WIFI`
- **Precision** 20–60 m outdoors, 10–30 m indoors
- **Permission** `ACCESS_FINE_LOCATION` **and** the system location toggle on
- **Cadence** 15–30 s, cheap (a few hundred bytes)
- **Keyless** yes — same beaconDB endpoint, `wifiAccessPoints[]`
- **Why it wins indoors** a phone spends most of its life inside buildings where GNSS has
  no sky view. This is the channel that keeps producing fixes when GPS does not.
- **What it costs** you are telling a third party the MAC addresses around you. Android
  randomises *your* MAC per-SSID since 10, but it does not randomise the APs you can see.

### 2.3 Cell sector — `CELL`
- **Precision** 300 m–2 km urban, 5–30 km rural
- **Permission** `ACCESS_FINE_LOCATION`
- **Cadence** 15–60 s, cheapest of all (a few towers per request)
- **Keyless** yes — same beaconDB endpoint, `cellTowers[]`
- **The trap** `radioType` belongs on each tower object, not the request envelope.
  Getting this wrong returns `400 Json deserialize error: missing field radioType` — I hit
  it live. `LocationResolver.buildRequest()` puts it where it belongs.
- **Second trap** LTE identity is TAC + CI, GSM/WCDMA is LAC + CID. Mixing them produces a
  plausible-looking cell ID that resolves to the wrong continent.

### 2.4 GNSS — `GNSS`
- **Precision** 3–10 m open sky, useless indoors
- **Permission** `ACCESS_FINE_LOCATION`
- **Cadence** 1 s if you ask for it; battery is the limit, not the API
- **Keyless** yes — `FusedLocationProviderClient`, no service, no key
- **Note** the only channel that gives velocity and heading for free. That is why aircraft
  tracking works off reported ADS-B velocity, and why a phone fix without GNSS has no
  heading at all.

### 2.5 BLE beacon — `BLE`
- **Precision** 1–10 m, indoor only
- **Permission** `BLUETOOTH_SCAN` (API 31+) / `ACCESS_FINE_LOCATION` below
- **Cadence** 5 s, near-zero battery with a filtered scan
- **Keyless** yes, when the beacon broadcasts its own position. iBeacon carries
  `major`/`minor`; Eddystone-UID carries a namespace + instance. Eddystone-**EID** is
  rotating and deliberately unreadable without the resolver key.
- **What it is good for** fixed installations — malls, transit, venues. Useless as a
  general tracker finding a phone in the wild, because you must be within ~50 m to hear it.

### 2.6 Wi-Fi RTT (802.11mc) — `RTT`
- **Precision** 1–2 m
- **Permission** `ACCESS_FINE_LOCATION`, hardware `FEATURE_WIFI_RTT` (API 28+)
- **Cadence** 1 s while ranging
- **Keyless** yes, but gated hard: needs FTM-capable APs *and* FTM-capable silicon. Most
  consumer APs do not answer ranging requests.
- **Honest verdict** impressive where it works, unavailable almost everywhere. Do not build
  a plan on it.

### 2.7 Sensor dead reckoning — `IMU`
- **Precision** 5 m for the first ~30 s, then drift compounds without bound
- **Permission** **none** — `Sensor.TYPE_ROTATION_VECTOR`, accelerometer, gyro
- **Cadence** 50–200 Hz
- **Keyless** yes
- **This is the uncomfortable one** motion sensors need no permission, keep working with
  location switched off, and are enough to classify movement (still / walking / vehicle)
  and to count steps and turns. It cannot give a coordinate on its own, but combined with
  any single anchor fix it reconstructs a path through a building.
- **Mitigation that actually exists** Android 12+ allows `HIGH_SAMPLING_RATE_SENSORS` to be
  restricted, and the rate is capped at 200 Hz without it. It does not stop inference.

### 2.8 Known-network history — `KNOWN_NETS`
- **Precision** n/a directly; identifies *places*, not coordinates
- **Permission** `ACCESS_FINE_LOCATION` (and heavily restricted since Android 10)
- **Cadence** on demand
- **Keyless** yes, locally
- **What it gives** the saved-network list is a home / office / campus fingerprint. Even
  without coordinates it answers "is the target at home", which is often the actual
  question behind "where is it".

### 2.9 Photo EXIF — `EXIF`
- **Precision** whatever the camera had at capture: 3–30 m
- **Permission** `READ_MEDIA_IMAGES` (already in this app's manifest)
- **Cadence** per capture
- **Keyless** yes
- **What it gives** a timestamped, high-precision position history with zero polling —
  every photo taken with location tagging on is a fix the device already recorded. Strip
  on share is common now, but the original file keeps it.

---

## 3. CARRIER channels

Not reachable from an app. Listed because they are the honest answer to "persistent
tracking of a phone number".

| Channel | What it yields | Precision | Access |
|---|---|---|---|
| **HLR / HSS lookup** | which MSC/VLR the subscriber is attached to | city → district | operator API, or SS7 |
| **ATI (Any Time Interrogation)** | forces a paging cycle, returns cell ID + timing advance | 100–500 m urban | SS7 / diameter, operator-grade |
| **Cell ID + TA** | ranging off the serving sector | ~100 m urban | operator |
| **Silent SMS** | triggers a location update without alerting the user | cell-level | operator / SS7 |
| **IMSI catcher** | IMSI + coarse position of every handset in range | 100–1 000 m | physical radio, not software |
| **Emergency location (AML/ELS)** | position pushed at call time | 5–50 m | PSAP only, per-call |

No amount of HTTP gets you any of these. `ss7` access is a criminal market, not an API
directory, and the lookup itself is a one-shot — continuous tracking means repeated queries,
which is exactly what gets noticed.

---

## 4. PLATFORM channels

The ones that genuinely deliver persistence, because somebody else's hardware does the
reporting.

| Channel | Mechanism | Precision | Requires |
|---|---|---|---|
| **Find My / Find Hub network** | a BLE tag or paired device is heard by *strangers'* phones, which upload their own encrypted location | 10–100 m | a tag or an enrolled device, and account access |
| **Account location history** | Google Timeline / Apple Significant Locations | 10–100 m | account credentials, or a data export |
| **Find My Device / Find Hub** | last-known position of a signed-in handset | 10–1 000 m | account credentials |
| **MDM / device-admin agent** | an installed management profile reports on a schedule | 5–50 m | enrolment, and the user accepting it |
| **Cloud photo backup** | EXIF of every backed-up capture | capture-time | account access |
| **Google/Apple Wi-Fi survey** | the platform's own AP database is the same source beaconDB mirrors | — | already used keylessly |

The Find My network is the only mainstream mechanism that tracks a *thing* without the
thing running your code. It is also the only one where the reporting devices belong to
uninvolved third parties. Everything else on this list is account access.

---

## 5. What this means for OVERSIGHT

`TrackKind` stays four values, and each maps to one channel above:

| `TrackKind` | Channel | Poller | Status |
|---|---|---|---|
| `ICAO24` | RF (ADS-B receiver network) | `TrackFeeds.pollAdsb` batched 40 hexes | **working** |
| `MOVE` | `WIFI` + `CELL` radio fingerprint | `DeviceLocation.fix` → `LocationResolver` | **working** |
| `MMSI` | RF (AIS) | AISStream WebSocket | deferred, needs key |
| `PHONE` | CARRIER | none exists | **not buildable** — see §3 |

Two additions are worth making and neither needs a new service:

1. **IP fallback as a liveness anchor.** When `WIFI` and `CELL` both return nothing (doze,
   radio off), an `ipf` fix still proves the device is somewhere in one city. Store it as a
   fix with `accuracyM = 25000` and let `TrackSummary.coveragePct` show it as low-confidence
   rather than as a gap. Right now `isUsable()` throws it away — correct for a track point,
   wrong for a heartbeat.
2. **EXIF ingestion.** `READ_MEDIA_IMAGES` is already granted. A pass that pulls
   `TAG_GPS_LATITUDE`/`TAG_GPS_LONGITUDE` out of the media store backfills a real position
   history for free, with timestamps, without polling anything.

`data/LocationSurface.kt` codifies the whole table — precision, cadence, permission,
keyless flag, cooperation requirement — and audits which channels are live on the actual
handset. That is the module to consult before adding a fifth `TrackKind`.

---

## 6. Retention math (unchanged)

| Channel | Cadence | Rows / day / target | 30-day size |
|---|---|---|---|
| `WIFI`/`CELL` | 30 s | 2 880 | ~4 MB |
| `GNSS` | 5 s | 17 280 | ~24 MB |
| `IMU` | 50 Hz | 4 320 000 | not storable — summarise to events |

Retention is 30 days, pruned on service start (`TrackService.RETENTION_MS`). Do not raise
it without raising `prune()`'s default with it, or the database grows without bound and
`summary()` linear scans start to hurt at a few hundred thousand rows.

---

## 7. Verification log

Live probes, 2026-11-21/22, all from this network:

- `POST api.beacondb.net/v1/geolocate` `{considerIp:true}` → `200`
  `{"accuracy":25000,"fallback":"ipf","location":{"lat":-6.2375,"lng":106.696}}`
- Same endpoint, `wifiAccessPoints[]` with two invented MACs → `404 notFound`
  (unknown BSSIDs are honestly refused rather than guessed)
- Same endpoint, `cellTowers[]` without per-tower `radioType` → `400`
  `Json deserialize error: missing field radioType`
- `location.services.mozilla.com/v1/geolocate` → `404` (MLS is gone)
- `opencellid.org/cell/get?key=test` → `401 API Key not known`
- `api.wigle.net/api/v2/network/search` → `401 Not Authorized`
- `www.googleapis.com/geolocation/v1/geolocate?key=` → `403 Permission denied on the service`

One keyless provider survives. That is why `LocationResolver` has exactly one upstream.