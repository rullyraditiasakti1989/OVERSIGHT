# Unified design doctrine — OVERSIGHT

Merged from five installed design skill packages (`.claude/skills/`):
`frontend-design` (Anthropic skills), `better-design` (Moddingflow),
`designed` (n1th1n-19), `design-md-styles` (bergside, 67 styles), and
`ux-skills` (tommyjepsen). This file is the single source of truth for the
redesign; `DESIGN.md` stays the token reference.

## What survived the merge

| Source | Rule kept |
|---|---|
| frontend-design | Spend boldness in one place. One leading element per screen. No generic AI-default treatment. Motion answers a user action, never ambient decoration. |
| better-design | Read `DESIGN.md` before touching UI; take values by token role. Measured geometry: shared keylines, equal sibling gaps, 48dp controls. Real loading, empty, error and success states. |
| designed | `designed.md` discipline: every new value is a token decision. Reduced motion, 48dp touch targets, plain copy, no em dashes in UI text. |
| design-md-styles | Use the `mono` style as the nearest match to the CIA dossier brief: monospace-driven, high contrast, compact density. Reject `glassmorphism`, `claymorphism`, `neon` and `gradient` as off-brief. |
| ux-skills | Accessibility is not a pass: contrast, semantics, focus, and recovery from error are part of the design, not a follow-up. |

## Decisions for this redesign

1. **Total redesign inside the CIA brief.** The interface is now a dark
   operations deck: graphite steel, amber signal, red classification. More
   importantly the interaction model changed: top folder-style dossier rail
   replaces the bottom tabs, HOME is a file index with a metric band and
   timeline instead of a tile grid, and CCTV is a two-column camera wall.
2. **One signature moment.** A 180ms fade-and-lift on first content appearance
   per screen, multiplied by the system animator scale. Camera tiles 0 to 5
   reuse that entrance on first composition only; no hover effects and no
   animation on the scroll path. Scale 0 disables all movement.
3. **Hierarchy by dominance.** One leading element per screen: the wordmark on
   the shell, the primary metric on HOME, the camera title in CCTV detail.
   Section labels are supporting, never competing.
4. **Sentence case for explanatory copy.** Reserve ALL CAPS for the
   classification line, indices, and status tokens. Long captions become plain
   sentences with commas and periods, not middle-dot chains.
5. **Geometry, not eyeballing.** 16dp gutters, 8dp card gaps, 48dp controls,
   4dp radius, one hairline weight. Rows on a shared keyline.
6. **Real states.** Every data surface has loading, empty, error and ready
   states. A dead camera keeps its place and explains the failure.
7. **Accessibility as acceptance.** Content descriptions on all imagery and
   controls, 48dp targets, text labels paired with signal colors, reduced
   motion respected.

## Anti-patterns rejected

- Template all-caps eyebrow above every heading (kept only where it is the
  classification or an index).
- Identical rounded cards with soft shadows and gradient washes.
- Numbered markers used as decoration (kept because our 00-07 are a real
  navigation sequence).
- Middle-dot meta strings in long copy; reserved for compact status rows.
- Non-user-triggered motion scattered across sections.
- Em dashes in UI copy; replaced with a colon or a dash-free sentence.

## Changelog

- 2026-11-20: merged five skill packages, applied to HOME, shell, and CCTV.
- 2026-11-20: full redesign to a dark operations deck. Retired the navy/gold
  terminal and the paper dossier. Tokens in `Color.kt`, type in `Type.kt`, dark
  theme in `Theme.kt`, dark window colors in `themes.xml`.
- 2026-11-20: v5 taxonomy and rebrand. `LANGLEY` -> `OVERSIGHT`, hierarchy over
  flat chips, `docs/data/cctv_taxonomy.json`, `docs/adr/0001`, research in
  `docs/DESIGN_RESEARCH.md`. Supersedes the paper direction.
