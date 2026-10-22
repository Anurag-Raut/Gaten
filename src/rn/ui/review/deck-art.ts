/** Deterministic palette and composition assignment for Anki deck artwork. */
export type DeckArtColors = {base: string; primary: string; secondary: string};
export type DeckArtSpec = {
  id: string;
  paletteId: string;
  paletteName: string;
  palette: DeckArtColors;
  motif: number;
  tilt: number;
  phase: number;
};

export const DECK_ART_PALETTES = [
  {id: "mist-blue", name: "Mist blue", light: {base: "#E9F1FC", primary: "#C7DDF8", secondary: "#DAE8FA"}, dark: {base: "#1C2A3B", primary: "#344D6C", secondary: "#263C55"}},
  {id: "soft-blush", name: "Soft blush", light: {base: "#FCECF0", primary: "#F6CAD6", secondary: "#F8DDE5"}, dark: {base: "#38232D", primary: "#644051", secondary: "#4C2C39"}},
  {id: "warm-sand", name: "Warm sand", light: {base: "#F9F2E6", primary: "#EEDDC0", secondary: "#F5E7D0"}, dark: {base: "#352D20", primary: "#655037", secondary: "#493B29"}},
  {id: "mint", name: "Soft mint", light: {base: "#E9F6EF", primary: "#C9E9DA", secondary: "#D9F0E4"}, dark: {base: "#1D332C", primary: "#335C4B", secondary: "#26483C"}},
  {id: "lilac", name: "Soft lilac", light: {base: "#F0EAFD", primary: "#DCCCFB", secondary: "#E8DDFB"}, dark: {base: "#2F2641", primary: "#554274", secondary: "#403257"}},
  {id: "powder-teal", name: "Powder teal", light: {base: "#E7F2F4", primary: "#C5E1E5", secondary: "#D6EAED"}, dark: {base: "#20343A", primary: "#386069", secondary: "#2B4B52"}},
] as const;

/** Hash UTF-16 code units with FNV-1a so each stable key keeps its composition. */
export function hashDeckId(deckId: string): number {
  let hash = 2166136261;
  for (let i = 0; i < deckId.length; i++) {
    hash ^= deckId.charCodeAt(i);
    hash = Math.imul(hash, 16777619);
  }
  return hash >>> 0;
}

/** Queue state, names, ordering and app accent never affect this visual assignment. */
export function deckArtSpec(deckId: string, dark = false): DeckArtSpec {
  const hash = hashDeckId(deckId);
  const palette = DECK_ART_PALETTES[hash % DECK_ART_PALETTES.length];
  return {
    id: deckId,
    paletteId: palette.id,
    paletteName: palette.name,
    palette: dark ? palette.dark : palette.light,
    motif: (hash >>> 6) % 5,
    tilt: (hash >>> 11) % 3 - 1,
    phase: (hash >>> 14) % 4,
  };
}
