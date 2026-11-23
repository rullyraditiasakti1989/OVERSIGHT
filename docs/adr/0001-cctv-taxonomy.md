# ADR-0001: Canonical CCTV taxonomy over flat source labels

**Date**: 2026-11-20
**Status**: accepted
**Deciders**: Olivia, implementation agent, council and dev-team review

## Context

The bundled camera catalog has 3.621 cameras carrying 69 flat `source` labels that mix geography (`Indonesia`, `Malang`, `Singapore`, `California`) with operators (`BPJT · JM`, `Jasa Marga`, `HK Opt2`). The flat chip row made `Malang` a sibling of `Indonesia` and made operator names compete with places. A three-level filter cannot be built on that model.

## Decision

We use an explicit taxonomy artifact `docs/data/cctv_taxonomy.json` with fields `source`, `region`, `province`, `operatorId`, `displayLabel`, `aliasOf`, `kind`. Every camera is assigned one canonical leaf. Cross-province toll operators have one canonical parent, province `Nasional`; province views are navigation shortcuts, never separate counts. Merged operator pairs share an explicit `operatorId` (`jm`, `mms`, `wbw`) with a canonical display label. `Unmapped` is a first-class reachable leaf. Malang, Singapore, California and Ireland have explicit rows; Global Webcams is retired.

## Alternatives Considered

### Infer hierarchy from label strings
- **Pros**: no mapping table to maintain.
- **Cons**: ambiguous (`BPJT · HK` vs `HK Opt2`), silently wrong for 149 Yogyakarta cameras, untestable.
- **Why not**: the mapping must be reviewable and deterministic.

### Duplicate cross-province operators under each province
- **Pros**: intuitive browsing.
- **Cons**: double-counts the same camera; breadcrumb and total disagree.
- **Why not**: breaks the count invariant asserted by AC-001.

## Consequences

### Positive
- Every camera has one canonical path; counts sum to 3.621.
- Breadcrumb strings are deterministic and testable.
- New public sources can be added as one taxonomy row.

### Negative
- A 73-row mapping table must be maintained alongside the catalog.
- Province `Nasional` is a new concept users must learn.

### Risks
- Mapping drift when new sources are harvested. Mitigation: the RED reachability test runs before UI and fails on any unmapped source.
