# docs

## Current

- [Gaten / Ink — Design Language](Gaten_Ink_Design_Language_v1.md) — the single current design
  authority: present-tense rules for Home, the study hubs, review and lessons, Analytics, Settings,
  the screen lookup and the shared tokens. The filename keeps `v1` for stable links.
- [Text recognition engines](ocr-engines.md) — the recognition engines Gaten reads a line with: the
  bundled ML Kit default and the optional PP-OCRv6 Medium recognizer, with their pinned upstream
  sources and SHA-256, graph shapes, preprocessing, limits, the Play fast-follow asset pack, the
  in-app download confirmation and the fallback behaviour.
- [Analytics plan and decisions](analytics/PLAN.md) — the single-page sections, the data contract
  behind every figure, the accent-only colour roles for all seven accents in both modes, the state
  model, the limits and the verification captures.
- [Analytics screenshots](analytics/screenshots/) — the emulator captures behind that verification.
- [React Native migration](rn-migration/PLAN.md) — the brownfield RN architecture: what stays Kotlin,
  the bridge modules, the JS stores, and the platform deviations.
- [Compose → RN screen spec](rn-migration/SCREEN-SPECS.md) — the per-screen mapping from the former
  Compose app to the ported React Native screens and shared tokens.

## Frozen reference packages

Kept for provenance. None of them is the current authority where it disagrees with the design
language.

- [Unified Ink hubs V4](unified-ink-hubs-v4/README.md) — the user-supplied package that fixed the
  shared row grammar for both provider hubs (its `index.html`, `DESIGN_SPEC.md`, `STATE_MATRIX.md`,
  prototype captures and procedural deck-art spec). The `Gaten_Ink_Design_Language_v1.md` inside it
  predates two later decisions and is **not** the authority; use the repository copy.
- [Anki + WaniKani hub handoff](hubs-procedural-art/README.md) — an earlier hub handoff with the
  A-reference boards. Its hub layout is superseded by the design language §8; kept for the state and
  completion distinctions it records.
- [WaniKani Ink V3](wanikani-ink-v3/README.md) — an earlier WaniKani hub package; its nonvisual
  lesson-flow notes remain context.
- [Dokuen UX review](dokuen-ux-review/README.md) — the reference study of Dokuen that informed the
  redesign, with its emulator captures in `shots/`.

## Historical study

The prototype study Gaten grew out of, kept for the record. The reports predate the rename to
`com.gaten.app` and still name the old package `com.akslabs.circletosearch`.

- `jp-prototype/` — `REPORT.md` and the frozen build `CONTRACTS.md`, with the raw on-device evidence
  (OCR output, emulator and device captures). `rtk/DATA.md` and `dictionary/DATA.md` are build inputs
  for `tools/build_rtk_db.py` and `tools/build_jmdict_db.py`, and `REPORT.md` is cited from
  `app/src/main/java/com/gaten/app/ocr/OcrRepair.kt`; do not remove them.

## Evidence

- `pr-evidence/` — the committed per-change captures, logs and fixtures behind merged PRs.
  `pr-evidence/tab-heading-typography/build-glyphs.py` generates `src/rn/ui/tabHeadingGlyphs.ts` and is
  cited in `NOTICE`; keep it.
