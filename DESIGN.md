# OVERSIGHT — Design System

`OVERSIGHT` / `SITUATIONAL AWARENESS SYSTEM`
Android, Compose Material 3, dark only.

## Direction

**The redaction room.** A reading room at three in the morning: black glass, bone
paper, ruled forms, and one ink that carries colour — the classification red.
Nothing is a rounded card. Every surface is a form field. Every section heading is
a stamped rule. Every action is a physical key that sinks when pressed.

The previous build was graphite-and-amber with serif headlines and rounded panels.
This is its opposite: achromatic, square, ruled, monospaced, and stamped.

### Target qualities
1. **Classified** — the interface looks like something stamped, filed, and read under a lamp.
2. **Exact** — numbers and identifiers are set in mono, aligned, never decorative.
3. **Physical** — controls sink, invert, and hold. No floating, no glow, no gradient.

### Anti-qualities
1. Cyan-on-dark "hacker terminal" (the reflex sci-fi console).
2. Glassmorphism, glow, gradient text, neon.
3. Rounded card stacks with icon tiles above headings.

## Palette

Roles only. Values live in `ui/theme/Color.kt`; the names are the tokens.

| Token | Value | Role |
|---|---|---|
| `Ink` | `#060708` | Ground. The room. |
| `PanelDeep` | `#030405` | Recessed wells, input fills. |
| `Panel` | `#0C0E10` | Panel and bar fill. |
| `PanelRaised` | `#15191C` | Pressed cells, active wells. |
| `SurfaceHigh` | `#1F2529` | Highest surface. |
| `HairlineSoft` | `#1B2124` | Faint rule between list rows. |
| `Hairline` | `#3A444A` | Decorative rule, section edges. |
| `ControlLine` | `#78847F` | Border that identifies an interactive control (≥3:1). |
| `Bone` | `#F3F5F4` | Paper. Primary ink, primary fill. |
| `BoneDim` | `#C9CFCD` | Secondary ink. |
| `Muted` | `#8C9593` | Body support text. |
| `MutedDeep` | `#7A8380` | Micro labels. Never below 4.5:1 on `Panel`. |
| `Seal` | `#E05A48` | Classification marks, critical state, destructive. |
| `Alert` | `#E05A48` | Error state. |
| `Green` | `#6FAF92` | Verified / present. |
| `Blue` | `#93A9BD` | Neutral information. |
| `Gold` | `#F3F5F4` | Legacy alias of `Bone` — accent = paper. |

**State never rides on hue alone.** Present/absent is carried by the word, the rule
weight, and inversion; colour is a second cue.

## Type

Two families, closed set.

- **Mono** (`FontFamily.Monospace`) — every label, code, index, metric, button, and status.
- **Sans** (`FontFamily.SansSerif`) — body prose and headings, set heavy and tight.

| Style | Spec | Use |
|---|---|---|
| `displayLarge` | Sans Black 40 / 40, −0.03 | Screen hero |
| `displayMedium` | Sans Black 30 / 32, −0.02 | Wordmark |
| `headlineLarge` | Sans Black 26 / 30, −0.02 | Section hero |
| `headlineMedium` | Sans Bold 20 / 26 | Sub-hero |
| `headlineSmall` | Mono Bold 15 / 20, 0.06 | Numbers in a row |
| `titleLarge` | Sans SemiBold 16 / 22 | Row title |
| `titleMedium` | Sans Medium 14.5 / 21 | Row title small |
| `titleSmall` | Mono Medium 12 / 17, 0.10 | Row title mono |
| `bodyLarge` | Sans 16 / 24 | Long prose |
| `bodyMedium` | Sans 14 / 21 | Default body |
| `bodySmall` | Sans 12.5 / 19 | Support body |
| `labelLarge` | Mono SemiBold 12 / 18, 0.12 | Button |
| `labelMedium` | Mono Medium 10.5 / 15, 0.14 | Section label |
| `labelSmall` | Mono Medium 9.5 / 14, 0.16 | Micro label, index |

Uppercase is applied at the call site for labels and buttons only.

## Geometry

- Spacing scale: **4 / 8 / 12 / 16 / 20 / 28 / 36**.
- Radii: **0**. Every shape is a rectangle. (`Shapes` is 0dp throughout.)
- Panel padding: 16. Bar height: 28–30. Header height: 60. Nav cell: 84 × 56.
- Rules: 1dp `Hairline` decorative, 1dp `ControlLine` on controls, 2dp `Bone` under the masthead.
- Siblings in a row share one gap token. Labels and values sit on two keylines
  (96dp label column, value at 96 + 12).
- Corner ticks (`Modifier.frameTicks()`) bracket the content area — the one signature frame device.

## Motion

One physics, three durations, all interruptible.

| Token | Value | Role |
|---|---|---|
| `instant` | 60 ms | press sink |
| `quick` | 140 ms | inversion, fill |
| `enter` | 180 ms | content swap (`Modifier.enter`) |

- **Press** — a filled control sinks 2dp and its 2dp offset block collapses to 0.
  Outline controls invert to `Bone` border and `PanelRaised` fill. Every control
  answers on touch-down, before the action resolves.
- **Enter** — one orchestrated fade + 12dp lift per content swap. No stagger.
- **Ambient** — one sweeping bar while a request is in flight, and nothing else.
- `ANIMATOR_DURATION_SCALE` is read as a multiplier; 0 means instant, never absent.

## Components

| Component | Contract |
|---|---|
| `ClassificationBar` | Red seal tick + `OVERSIGHT // EYES ONLY`, `NOFORN` right, 1dp seal rule below. |
| `VectorTopBar` | Wordmark `displayMedium`, system line beneath, `LIVE` chip, settings key. 2dp bone rule. |
| Nav cell | Mono index over mono label; active inverts to bone fill + ink text. |
| `SectionLabel` | 2 × 12 bone tick, mono label, rule running to the trailing value. |
| `PanelCard` | Square, `Panel` fill, 1dp `Hairline`, optional stamped header. |
| `GoldButton` | Bone fill, ink text, mono caps, 48dp, 2dp offset block that collapses on press. |
| `OutlineActionButton` | 1dp `ControlLine`, bone text, inverts on press. |
| `VectorTextField` | Square, `PanelDeep` fill, 1dp `ControlLine` → `Bone` on focus, mono value. |
| `SegmentedTabs` | Ruled cells, no gaps; active inverts. |
| `Tag` | 1dp border, mono 9sp caps. |
| `LoadingLine` | Sweeping bone bar + mono label. No spinner. |
| `EmptyState` | Bracket frame, `—`, title, one sentence. |

## Copy rules

- A label restates nothing. If the value says it, the sentence goes.
- Sentence case for prose; uppercase only for stamped labels and buttons.
- No exclamation, no emoji, no marketing. The tone is a duty officer's.
