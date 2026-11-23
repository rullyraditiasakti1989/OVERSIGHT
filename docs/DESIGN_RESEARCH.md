# Design research — Oversight

Direction: dark CIA operations deck. Not a paper dossier, not a navy terminal.

## References

1. Intelligence briefing covers and classification stamps: black strip, one deliberate accent, short uppercase classification line.
2. Cargo-ship / air-traffic control displays: dense but aligned data, tabular numerals, narrow signal colors.
3. Editorial dossier typography: serif display for the leading element, system sans for reading, monospace only for coordinates and indices.
4. Installed skills: `frontend-design` (one signature detail), `better-design` (motion functions and token scale), `designed` (48dp targets, reduced motion, no AI-look tells), `ux-skills` (accessibility floor).

## Tokens (single source of truth)

| Role | Hex |
|---|---|
| Canvas | `#060910` |
| Surface | `#0D141D` |
| Raised surface | `#141E2A` |
| Signal amber | `#D6A441` |
| Classification red | `#D05046` |
| Primary text | `#E9EEF4` |

Base motion duration: **180ms**, multiplied by Android `animator_duration_scale` clamped to 0..1. Scale 0 means no movement.

## Anti-goals

No backend, no account, no new credential handling, no private-camera discovery. Existing SET keys, the INTEL OSIRIS bridge, and the anonymous Malang public session are out of scope for this redesign.

## Supersession

This document supersedes the paper-dossier direction in `DESIGN_UNIFIED.md`. The old navy/gold palette and the 120ms duration in `UI_GUIDELINES.md` are retired.
