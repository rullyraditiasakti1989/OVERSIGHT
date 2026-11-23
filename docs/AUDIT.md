# Production audit — OVERSIGHT

**Production audit: 72/100, launchable with caveats.** No crash-risk lint errors
remain and the launch-critical CCTV path was verified on a real emulator, but
there is no version control, no CI, and zero automated tests.

## Evidence checked

- `gradle assembleDebug` — PASS
- `gradle lintDebug` — initially **8 errors / 17 warnings**, now **0 errors / 17 warnings**
- `gradle test` — PASS but `NO-SOURCE` (no test sources exist)
- Local emulator `LivinPixel` (API 35, 1080x2400): HOME → CCTV → filter `Indramayu` → live HLS playback; dead stream error state; no FATAL in logcat
- Static review of `Store`, `GeoStore`, `Net`, `CctvScreen`, `IntelScreen`, `SettingsScreen`, `GeoScreen`, `FaceSearch`, `IdentityIntel`, and the CCTV harvesters
- `git status` — not a git repository, so no diff/history evidence

## Fixed during this audit

- `GeoScreen` called `getCurrentLocation` without a runtime permission check — added explicit guard.
- `GeoWatch.sync` called `addGeofences` without a proven permission path for lint — added a justified suppression after the existing `hasPermission()` check.
- `themes.xml` used `windowLightNavigationBar` below API 27 — removed; the dark navigation bar is already set.
- Media3 HLS calls lacked the upstream `@UnstableApi` marker — annotated at the owner and suppressed at the public screen boundary.
- App copy translated to English as the default language (12 new constants in the translation map, 266 literal replacements overall).
- Catalog generation now strips control characters and deduplicates on a normalized URL key: 3.626 → 3.621 cameras, 0 duplicates.

## Blockers before public release

1. **No version control or CI.** The working directory is not a git repo. Initialize git, add a remote, and run build + lint on every push.
2. **Zero automated tests.** Add at least unit tests for `IdentityIntel` NIK parsing, `PublicIntel` normalization, `Osiris.normalizeBase/normalizePath`, and the catalog dedupe, plus one Compose navigation smoke test.
3. **API keys in plaintext `SharedPreferences`.** `hibp`, `face_key` and `osiris_token` are stored unencrypted. Move to `EncryptedSharedPreferences` backed by Android Keystore.
4. **Bundled catalog goes stale.** `cctv_indonesia.json` is a snapshot. Add a background refresh from a maintained public source with last-known-good fallback and a visible "updated at" badge.

## High-value fixes

- Raise `targetSdk` to 36 and `compileSdk` to 36, then upgrade Media3 from 1.5.1 to 1.11.1 (currently pinned because 1.11.1 requires SDK 36). Resolves 9 `GradleDependency` warnings.
- Add `android:dataExtractionRules` for Android 12+ and a monochrome launcher icon; both are lint warnings today.
- Replace `READ_MEDIA_IMAGES` with the Android 14 photo picker (`SelectedPhotoAccess` warning) for the face module.
- Add crash/ANR reporting with an opt-out toggle. Current privacy posture is good (no analytics) but failures are invisible.
- Bound the CCTV list further: 3.621 rows render 40 at a time, but search filters the entire list on every keystroke. Debounce the query.
- `Net` has no certificate pinning and no per-host timeout policy; the OSIRIS base URL is user-supplied. Validate and rate-limit that bridge explicitly.
- Release signing is not configured (`isMinifyEnabled = false`, debug APK only). Add a release keystore, R8 config, and a signed build task.
- Document the 70 CCTV sources, their refresh cadence, and the ones that are geo-restricted so offline/dead cameras are expected rather than reported as bugs.

## Click-path audit

State stores mapped: `Store` (cases, keys, audit), `GeoStore` (zones, events).
No sequential-undo, async-race, stale-closure or missing-transition bugs found in
the audited handlers. `clearAll()` intentionally resets every store it owns, and
`deleteZone()` also prunes events by design. The previously misleading CCTV
refresh button now recreates the HLS player and clears the stream error.

## Scope boundary

Only public, operator-published feeds were collected. No credential guessing,
account bypass, or discovery of exposed private cameras was performed. ARTEX's
own `api-recon` boundaries were followed.

## Next action

Initialize git and commit the current tree, then add the first four unit tests
(`IdentityIntel`, `PublicIntel`, `Osiris`, catalog dedupe) so the score can move
above the launch threshold.
