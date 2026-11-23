# OVERSIGHT

[![ci](https://github.com/rullyraditiasakti1989/OVERSIGHT/actions/workflows/ci.yml/badge.svg)](https://github.com/rullyraditiasakti1989/OVERSIGHT/actions/workflows/ci.yml)

A personal Android console for open-source intelligence, device awareness and link-based location
reporting. It runs entirely on the device: no server, no account system, no sync. Everything the
app shows comes from public feeds, bundled catalogues, or values you enter in Settings.

The design is documented in [DESIGN.md](DESIGN.md): black glass, bone paper, ruled forms, one red
ink, square corners, mono for indices and metrics.

## Screens

| # | Screen | What it does |
|---|---|---|
| 00 | HOME | Duty board and case entry |
| 01 | INTEL | Public index sweeping and result triage |
| 02 | IDENT | NIK decode on device, phone/email lookups, keyless breach probes |
| 03 | FACE | On-device face detection over a gallery or camera frame |
| 04 | GEO | Device location, radio geolocation, CCTV catalogue |
| 05 | CASE | Local case files and exports |
| 06 | CCTV | Public camera catalogue and HLS previews |
| 07 | SET | Keys, feed cadence, credits, Phone-X pool |
| 08 | LOAD | Payload builder: capability gates, APK packaging, Telegram reporting |
| 09 | GLOBE | Seven layers on a MapLibre sphere plus the contact ledger |

## What it is not

- Not a hosted service. There is no backend to create an account against.
- Not a wireless attack tool. Radio geolocation reads what the device can see and queries public
  geolocation APIs; it does not inject or spoof.
- Phone-X is a client for an account you own. The app reads your balance and calls your modules; it
  does not bypass wallet checks or mint credits.
- LOCATE is one static HTTPS page. It works only while the browser tab stays open, and the URL is
  visible to whoever opens it.

## Build

Requirements: JDK 17, Android SDK platform 35, and the Android Gradle plugin dependencies resolved
from Google Maven and Maven Central.

```powershell
# Windows
copy local.properties.example local.properties
# edit sdk.dir to your Android SDK, then:
.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease lintDebug
```

```bash
# macOS / Linux
cp local.properties.example local.properties
./gradlew testDebugUnitTest assembleDebug assembleRelease lintDebug
```

APKs land in `app/build/outputs/apk/debug/` and `app/build/outputs/apk/release/`. Release signing
is optional: copy `keystore.properties.example` to `keystore.properties`, point it at your own
keystore, and the release build is signed with it. Without that file the release variant builds
unsigned.

The payload template is a separate Gradle root:

```powershell
copy payload-template\local.properties.example payload-template\local.properties
.\gradlew.bat -p payload-template assembleRelease
copy payload-template\build\outputs\apk\release\payload-template-release.apk app\src\main\assets\payload\template.apk
```

`app/src/main/assets/payload/template.apk` is committed because the payload tests build real APKs
from it and Gradle cannot regenerate it as part of the app build.

## Quality gate

Current gate: **224 tests / 28 suites / 0 failures**, lint clean, debug and release APKs built.
CI repeats the same four tasks on every push. `payload-template` is rebuilt separately when its
sources change.

## Configure

- **Settings → KEYS** holds optional API keys. `FIRMS` and `AIS` are accelerators; both GLOBE
  layers work without them. `tg_token`, `tg_chat` and `tg_webhook` belong to your own bot.
- **Settings → GLOBE SOURCES** stores the enabled-layer mask and cadence locally.
- **File 08 → REPORTING** verifies a Telegram bot with `getMe` then `getUpdates`, and fills the
  chat id from the first `/start`. A bot cannot open a conversation, so send `/start` first.
- **LOCATE** needs `locate/index.html` edited before publishing: replace `YOUR_TOPIC` with your own
  ntfy topic. See [docs/LOCATE.md](docs/LOCATE.md) and [docs/HOSTING.md](docs/HOSTING.md).

## Repository layout

```
app/                  Android application (Compose UI, data sources, payload packager)
benchmark/            Macrobenchmark module
payload-template/     Standalone Gradle root that builds the packaged payload APK
locate/               Static location page, published as-is
docs/                 Design, threat models, and per-feature engineering notes
tools/                Local audit helpers
shots/                Device screenshots (not tracked)
```

## Docs

- [DESIGN.md](DESIGN.md) — visual system
- [UI_GUIDELINES.md](docs/UI_GUIDELINES.md) — screen anatomy and controls
- [GLOBE-PLAN.md](docs/GLOBE-PLAN.md) — feed selection, SGP4 port, F0–F6 decisions
- [PAYLOAD.md](docs/PAYLOAD.md) — packaging, manifest surface, signing, C2 verification
- [IDENTITY.md](docs/IDENTITY.md) — phone probes and the three-valued verdict
- [LOCATION-SURFACE.md](docs/LOCATION-SURFACE.md) — device and radio location
- [LOCATE.md](docs/LOCATE.md), [HOSTING.md](docs/HOSTING.md) — link-based location
- [THREAT-MODELS.md](docs/THREAT-MODELS.md), [AUDIT.md](docs/AUDIT.md) — review notes

## Licence

MIT — see [LICENSE](LICENSE). Third-party attribution for vendored and fetched data lives in
Settings → GLOBE CREDITS and in the source headers.
