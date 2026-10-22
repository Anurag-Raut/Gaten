# Procedural deck thumbnails — mandatory Anki UI requirement

The user explicitly wants the small artwork to the **left of each deck name** to be created **dynamically/procedurally**, while retaining the calm, polished pastel look in the selected reference. It must **not** be a single static image repeated for all decks, manually bundled photo thumbnails, remote generative AI, or a randomized visual that changes on each render.

## Approved approach

- Render a rounded **44 × 44 dp** ornamental tile (responsive min 40, max 48, radius about 10–12dp), in every Anki deck row. The row stays an Ink row with a 1dp separator; the artwork is the only soft colored surface.
- Use the six curated repeatable palettes below. **Exactly these colors are included in the code.** Their identity is independent of selected app accent; the app accent continues to control active numeric counts, checkmarks, and focus.
- Use only **five quiet abstract geometrical motifs**: cropped sweeping arc, nested half circles, overlapping leaf forms, diagonal ribbons, and soft horizon. Two or three shapes maximum; no text, emoji, Japanese kanji or deck-content previews.
- Use `stableDeckKey = accountScope + ':' + stableDeckId` when the backend offers both. **Stable deck ID is preferred to name.** Hash the key with FNV-1a as supplied in the submitted prototype algorithm. Use the hash bits for palette, motif, orientation and phase. These are intentionally limited to curated combinations, not unrestricted randomness.
- The same ID must produce **the same artwork across rebuilds, order changes, rerenders, app restarts, queue updates, renames, theme toggles, and completion**. Dark mode changes only the tonal palette—not motif or identity.
- In user interfaces where a deck ID is temporarily unavailable, use a stable persisted key from the existing model. If no stable key exists, fall back to a namespaced normalized deck name until stable ID loads; never use list index, count, time, or `Math.random()`.
- The implementation may support per-deck palette overrides only if there's an existing customization API; otherwise keep automatic generation. Do not add UI settings for it in this project.

## Curated color system

| Name | Light base | Light main | Light secondary | Dark base | Dark main | Dark secondary |
|---|---|---|---|---|---|---|
| Mist blue | `#E9F1FC` | `#C7DDF8` | `#DAE8FA` | `#1C2A3B` | `#344D6C` | `#263C55` |
| Soft blush | `#FCECF0` | `#F6CAD6` | `#F8DDE5` | `#38232D` | `#644051` | `#4C2C39` |
| Warm sand | `#F9F2E6` | `#EEDDC0` | `#F5E7D0` | `#352D20` | `#655037` | `#493B29` |
| Soft mint | `#E9F6EF` | `#C9E9DA` | `#D9F0E4` | `#1D332C` | `#335C4B` | `#26483C` |
| Soft lilac | `#F0EAFD` | `#DCCCFB` | `#E8DDFB` | `#2F2641` | `#554274` | `#403257` |
| Powder teal | `#E7F2F4` | `#C5E1E5` | `#D6EAED` | `#20343A` | `#386069` | `#2B4B52` |

The set should visibly repeat across a long list: **repetition is intentional and healthy**. With 6 palettes × 5 motifs and slight deterministic orientation variations there are many combinations without visual chaos. Similar-looking thumbnails are not an error; do not increase saturation to force uniqueness.

## Integration steps

1. Locate the Anki deck row component; keep its current data, navigation, and Ink typography. Add a `DeckThumbnail` at the existing left/icon slot.
2. Discover existing React Native/Android SVG or Canvas facilities. Prefer a pure local vector implementation. Use the submitted prototype TypeScript algorithm for deterministic palette/motif selection, and the submitted prototype JavaScript algorithm as an algorithm+shape reference. You may keep the submitted prototype TypeScript algorithm verbatim and implement `DeckThumbnail` with `react-native-svg` *if already installed*; otherwise use the project's native drawing APIs. **Do not put SVG markup in a WebView just to render the tile.**
3. Use a memoized spec keyed by stable deck ID and theme. In list virtualization, use existing stable deck IDs as React keys; avoid generating images on every frame.
4. The art never encodes queue status, difficulty, favorite, subject, or provider identity. Place actual completion check and due count **outside** artwork on right; don't recolor/change art upon completion.
5. Since the art is decorative, hide it from TalkBack; deck row accessible label reads deck name and actual queue state. Selected accent is never used as text inside the art.
6. Test mixed-length deck lists with duplicate names, Unicode names and deep nested decks, 0/1/100+ decks, fast scrolling, 100/130/160% font scaling, narrow widths and light/dark mode.

## Determinism tests (must add in repo)

- Known key yields stable palette/motif/phase: screenshot/regression fixture.
- Reordering a deck array does not change visual assignment.
- Renaming but retaining ID does not change visual assignment.
- Changing due count, completion and selected app accent does not alter art.
- Light ↔ dark changes only palette tones; motif and identity remain.
- Two same-name decks with distinct IDs get independent stable assignments (collisions of palette/motif may still occur; that's acceptable).
- SVG/Canvas drawing never renders beyond its clipped rounded bounds.
- No network, file writes, animation loops or random calls are needed to render.

The bundled [deck-art gallery capture](examples/deck-art-gallery.png) illustrates the palette and motif range. Prototype screens use illustrative data and are not implementation evidence.
