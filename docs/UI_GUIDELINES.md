# Oversight — UI/UX Guidelines

Companion to `DESIGN.md`. This document describes behavior, not appearance.
Read `DESIGN.md` for tokens and component styling.

## Information architecture

Eight screens, fixed order, reachable from the bottom navigation:

| Index | Label | Purpose |
|---|---|---|
| 00 | HOME | Situation summary and jumps |
| 01 | INTEL | Public feeds and the OSIRIS bridge |
| 02 | IDENT | Local identity parsing and self-checks |
| 03 | FACE | Local face detection and reverse-image handoff |
| 04 | GEO | Geofence zones and local event log |
| 05 | CASE | Case files |
| 06 | CCTV | Camera catalogs and HLS player |
| 07 | SET | Keys, bridge URL, local data |

The settings icon in the top bar always jumps to 07. Back from settings returns
to HOME. There is no drawer, no floating action button, and no modal navigation.

## Navigation rules

- One tap changes screen; no confirmation, 180ms base entrance scaled by the system animator setting.
- Selected state is the gold index and 2dp rule. Previous screen state is kept in
  memory while the app lives; scroll position may reset.
- Deep links are not used. Share links are generated as plain text/URLs from the
  relevant panel.

## Screen behavior

**HOME** — cards lead with a kicker, a count, and a single action. No marketing
copy. Empty state names the missing thing and the screen to go fix it.

**INTEL** — five tabs. Tabs load their data on first selection, not all at once.
A failing source writes one signal-red line under the tab and leaves other tabs
untouched. Path input accepts `/api/...` only; everything else is rejected at
the boundary.

**CCTV** — the Indonesia chip is first. Selecting a catalog loads it immediately.
Tapping a camera opens the detail view in place with a `KEMBALI` action. Snapshot
feeds refresh every 60 seconds via a cache-busting query parameter. HLS feeds
send their session headers through `DefaultHttpDataSource`. A dead camera keeps
its place and shows an error line; it is never silently removed.

**IDENT / FACE / GEO** — inputs validate locally before any network call. Masks
are applied to identifying segments on display. Geofence state is read from the
device and never inferred.

**CASE / SET** — destructive actions require a deliberate second tap on the same
button. Keys are saved only on explicit `SAVE`. `HAPUS SEMUA DATA` clears every
local store in one ordered pass.

## States

Every data surface has four explicit states:

1. **Loading** — a thin gold progress line with mono label. Never a full-screen spinner.
2. **Empty** — a gold `—` glyph, a short title, and one sentence. No illustration.
3. **Error** — signal-red body line with the HTTP status or cause. Retry is a normal gold button.
4. **Ready** — data, a true count in the section trailer, and a refresh affordance.

Do not show an empty state and an error at the same time. Do not keep stale data
after a failed refresh; clear it first, then show the error.

## Motion

- No spring physics, no parallax, no bounce.
- Fade 180ms for content replacement, multiplied by animator scale; 0ms when scale is 0.
- Progress indicators are linear.
- Respect system "reduce motion" by removing fades.

## Accessibility

- Touch targets ≥ 44dp; primary buttons 46dp.
- Text contrast: ink on canvas ≥ 12:1, ink-tertiary on canvas ≥ 4.5:1.
- Color is never the only signal: every status color is paired with a text label.
- Content descriptions on camera thumbnails and the settings icon.
- Mono labels remain uppercase; screen readers read them naturally.

## Copy rules

- Indonesian for interface copy; uppercase English mono for classification,
  indices, and technical labels.
- No emoji. No "hope this helps". No disclaimers. No "please use responsibly".
- Numbers carry units: `1.195 KAMERA`, `60 DETIK`, `HTTP 403`.
- Error messages name the layer that failed: source, session, or local storage.

## Acceptance checklist for a new panel

- [ ] Uses `PanelCard` with surface-1 and hairline-soft border.
- [ ] Header uses eyebrow + gold tick + trailing count in data style.
- [ ] All four states implemented.
- [ ] Actions are 46dp gold primary or hairline outline.
- [ ] No shadow, gradient, pill radius, or emoji.
- [ ] Inputs reject malformed values at the boundary.
- [ ] Copy follows the language rule above.
