# docs

## Design reference

- [Gaten / Ink v1](Gaten_Ink_Design_Language_v1.md) — approved specification supplied by the user.
- [Implementation map](design-language.md) — shared code sources, native accessibility choices,
  and screen-specific hub precedence.
- [Unified Ink hubs V4](unified-ink-hubs-v4/README.md) — the user-supplied package that is the
  current screen-specific authority for both provider hubs, with `DESIGN_SPEC.md`, `STATE_MATRIX.md`,
  the `index.html` prototype, and the spec-to-code map. Also available here:
  [design spec](unified-ink-hubs-v4/DESIGN_SPEC.md) and
  [state matrix](unified-ink-hubs-v4/STATE_MATRIX.md).
- [Anki + WaniKani hub handoff](hubs-procedural-art/README.md) — historical: selected A references,
  completion states, and deterministic Anki deck-art requirements.
- [WaniKani Ink V3](wanikani-ink-v3/README.md) — preserved historical reference package. Its
  screen visuals are older than the selected A hub handoff; its nonvisual lesson-flow documentation
  remains useful where it agrees with current implementation and API behavior.

## Historical studies

The following documents are the **historical feasibility study** that Gaten grew out of, kept
for the record. The current state of the project is described in the root `README.md`.

- `jp-prototype/` — the prototype reports and frozen build contracts (`REPORT.md`,
  `CONTRACTS.md`) plus the raw on-device evidence (OCR output, emulator/device captures, the
  generated `dictionary/DATA.md`).
- `research/` — desk research on the existing solutions the project was compared against.
- `image_search.md` — an early reverse-image-search design that Gaten does not implement.

The reports mention the old package name `com.akslabs.circletosearch`. That is not a typo: they
predate the rename to `com.gaten.app`, and were left as written.
