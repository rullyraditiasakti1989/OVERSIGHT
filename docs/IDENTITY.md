# File 02 — Identity / Phone

The phone tab is a local parser plus keyless public probes. It has no account and no backend.
Walked on `LivinPixel` on 2026-11-22 with `081234567890`. Screenshots:
`shots/id-phone-profile.png`, `shots/id-phone-presence.png`, `shots/id-handle-check.png`.

## Parse number

`IdentityIntel.parsePhone` runs entirely on the device. The test number resolves to:

```
E.164      +6281234567890
National   081234567890
Country    +62
Operator   Telkomsel
Line type  MOBILE
```

The operator map is a prefix table, so an unmapped prefix reads `Unmapped` rather than a guessed
carrier. Open-channel buttons hand off to WhatsApp, Telegram, GetContact and Truecaller; none of
them are scraped by the app.

## Presence probe — the verdict bug

The first walkthrough graded the dummy number **PRESENT on both channels**. Both were false.

- `api.whatsapp.com/send?phone=…` answers `200` with a 220 KB page for *any* syntactically valid
  number. The only keyless negative signal is the explicit invalid-number marker in the body.
- `t.me/+<number>` answers `200` with `<title>Telegram: Contact</title>` and
  `og:title "Join group chat on Telegram"` for an unregistered number. The old check rejected only
  the exact string `Telegram: Contact`, so the generic join page was read as a named profile.

`PresenceVerdict` now has three values: `PRESENT`, `ABSENT`, `UNKNOWN`. A generic page is
`UNKNOWN`, rendered as a muted tag, and the detail line says exactly why:

```
WHATSAPP   Deep link accepted - registration cannot be confirmed without WhatsApp's API.  UNKNOWN
TELEGRAM   Generic contact page - registration cannot be confirmed keylessly.             UNKNOWN
```

`IdentityIntel.whatsappVerdict` and `telegramPhoneVerdict` are pure and covered by
`PresenceVerdictTest` with the recorded bodies. `telegramByUsername` still yields a real
`PRESENT`/`ABSENT` because `t.me/<handle>` returns a distinct `tgme_page_title` and `404`.

## Handle check — the wiring bug

`CHECK USERNAME` wrote its result into the `results` list that the phone's **Presence probe** card
renders. The answer appeared in the wrong card, above the Handle section that produced it, and it
silently replaced the two phone verdicts.

The handle now owns `handleResult`; the phone keeps `results`. Both render through the shared
`PresenceVerdictRow`. Verified on device with a public Telegram handle:

```
HANDLE / TELEGRAM
Akun publik: <public display name>                        PRESENT
Not present in the infostealer index.
```

Editing the handle clears both the verdict and the stealer report, so a stale answer cannot survive
into a new lookup. Both remote calls now report their failure into the panel: a timed-out Telegram
or stealer-index request used to vanish, leaving the card blank with no explanation.

## Gate

`PresenceVerdictTest` adds three tests. Full suite 224 tests / 28 suites / 0 failures, debug +
release APKs, lint clean.
