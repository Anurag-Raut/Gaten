/**
 * The Remembering the Kanji bridge: the offline reference behind a tapped character, and the
 * device's choice of which reference a kanji page shows.
 *
 * Everything is resolved on the Kotlin side from the bundled `assets/db/rtk.sqlite`. There is no
 * token, no account, no network call and no cache: a lookup is one indexed row on this device,
 * available immediately after installation. A rejection is the asset itself being unreadable
 * (`E_RTK`), not a reachability problem — RTK has no "offline" state to report.
 */
import { NativeModules } from "react-native";

/** One Kanji Koohii story, with the attribution the dataset carries. */
export interface RtkMnemonic {
  /** The slot number the story carries; a row can skip one, so this is the stable order. */
  index: number;
  /** The author's handle on Kanji Koohii; empty when the source did not name one. */
  author: string;
  /** The author's Koohii profile page, when the stored link points there. */
  profileUrl?: string;
  /** The posting date as the source spells it, `D-M-YYYY`. */
  date: string;
  /** The story's Koohii score; 0 when the source carries none. */
  score: number;
  /** The story text, emphasis markers (`**bold**`, `_italic_`) intact. */
  body: string;
}

/** One Remembering the Kanji row. Empty story/primitive mean the dataset has none. */
export interface RtkEntry {
  character: string;
  keyword: string;
  story: string;
  primitive: string;
  mnemonics: RtkMnemonic[];
}

/** A character lookup. Local and total: either the row or a plain "no". */
export type RtkLookupResult =
  | { status: "available"; entry: RtkEntry }
  | { status: "notAvailable" };

/** Which reference a kanji page displays. */
export type KanjiReferenceSource = "wanikani" | "rtk";

interface GatenRtkNative {
  lookupKanji(character: string): Promise<RtkLookupResult>;
  getReferenceSource(): Promise<KanjiReferenceSource>;
  setReferenceSource(source: KanjiReferenceSource): Promise<KanjiReferenceSource>;
}

const GatenRtk = NativeModules.GatenRtk as GatenRtkNative;

/** The offline row for one kanji character (one code point, possibly outside the basic plane). */
export function lookupKanji(character: string): Promise<RtkLookupResult> {
  return GatenRtk.lookupKanji(character);
}

/** The device's stored choice; `"wanikani"` when it never chose. */
export function getReferenceSource(): Promise<KanjiReferenceSource> {
  return GatenRtk.getReferenceSource();
}

/** Stores the choice and resolves what is on disk afterwards, never the request. */
export function setReferenceSource(
  source: KanjiReferenceSource
): Promise<KanjiReferenceSource> {
  return GatenRtk.setReferenceSource(source);
}
