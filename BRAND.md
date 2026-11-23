# Oversight — Brand Guide

## Name

**OVERSIGHT** — a reference to Oversight, the long-standing home of American
intelligence. It is short, geometric, easy to say in every language here, and
reads like a facility name rather than a consumer app. It replaces the internal
codename VECTOR everywhere the user can see.

- **Wordmark:** `OVERSIGHT` — IBM Plex Mono Medium, uppercase, `0.22em` tracking.
- **Descriptor:** `PERSONAL INTELLIGENCE` — IBM Plex Mono Medium, `0.18em` tracking, 8sp.
- **Full lockup:** `OVERSIGHT / PERSONAL INTELLIGENCE CONSOLE`.
- **Classification line:** `EYES ONLY // PERSONAL INTELLIGENCE`.

The Java package, shared-preference keys and broadcast actions remain
`com.vector.console` / `vector_*`. They are internal identifiers; changing them
would erase existing local cases, keys and geofences on upgrade. Brand is
presentation, not storage.

## Mark

The mark is a **gold diamond frame** containing a lens and a bold `L`:

- Outer rotated square, 1.5dp gold stroke — the badge / dossier frame.
- Inner circle, 1dp gold stroke at 60% opacity — the lens / observation.
- A solid gold `L` built from two square-cap strokes — the initial.
- A single hairline gap at the top of the diamond, like a classified file tab.

Clear space: one full mark-width on every side. Minimum size 24dp digital,
16mm print. Never place the mark on a photo, on gold, or on a light surface
without the navy plate behind it.

Source of truth: `docs/brand/oversight-mark.svg`. Android launcher foreground:
`res/drawable/ic_launcher_foreground.xml`. Launcher background is navy
`#060910` (`res/values/colors.xml`). The mark is drawn in amber signal on the dark field.

## Color

| Role | Hex |
|---|---|
| Operations field / launcher | `#060910` |
| Raised surface | `#141E2A` |
| Signal amber | `#D6A441` |
| Signal amber, pressed | `#8C6B24` |
| Classification red | `#D05046` |
| Primary text | `#E9EEF4` |
| Online / verified | `#4FB08A` |
| Alert / live | `#D05046` |
| External / info | `#6EA8D8` |

Gold is the only brand color. Status colors are borrowed for meaning, never for
branding. Do not introduce a second accent.

## Voice

Dry, exact, operational. Short declaratives. No greeting, no apology, no
explanation of what the app is for. Status text uses plain words: `MASK`,
`ONLINE`, `NO SIGNAL`, `MERAH`, `LOADING`. Counts are numbers with units, not
adjectives.

The app writes in the user's language (Indonesian) for interface copy, but
keeps classification strings, indices and technical labels in uppercase English
mono — that contrast is the intelligence aesthetic.

## Usage

- Use the full lockup in the top bar and about screen.
- Use the mark alone for the launcher icon and any future splash.
- Never stretch, rotate, recolor, add a glow, or place a drop shadow on the mark.
- Never combine Oversight with an actual government seal, flag, or agency crest.
  This is an original identity inspired by a genre, not an official mark.

## Migration note

Turn 1 of a new session opens with the existing first-knock greeting; the
rename does not change behavior, navigation, data, or the order the screens
appear in. Only the visible name, mark, palette and typography change.
