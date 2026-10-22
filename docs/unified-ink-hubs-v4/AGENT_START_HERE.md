# READ FIRST — Gaten Ink unified hubs, V4

**This handoff supersedes previous WaniKani hub designs with two giant counts and black Start buttons.** The requested final design is **the same visual grammar as the Anki deck picker** in both hubs: compact clickable rows, small thumbnails or icons at left, a title and subline, right-aligned due count or green success check, and a right chevron. WaniKani has exactly TWO rows, Reviews and New cards. Anki retains its deck rows.

1. Open `index.html`. Select the Anki and WaniKani state from dropdowns; switch light/dark and accent.
2. Read `AGENT_IMPLEMENTATION_PROMPT.md`, `STATE_MATRIX.md`, `DESIGN_SPEC.md`, `docs/PROCEDURAL_DECK_ART.md`.
3. Inspect selected reference images in `references/` and the fresh screenshots in `screenshots/`.
4. Reuse existing integration code; do not rewrite review scheduling, submit fake API writes or invent counts.
5. The generated HTML is **the latest visual source of truth**. Older boards are historical and retain black buttons; do NOT reproduce them.
