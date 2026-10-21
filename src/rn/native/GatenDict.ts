/**
 * The dictionary bridge: the search screen's rows, straight from [DictionarySearch].
 *
 * Everything is resolved on the Kotlin side — ranking, kana folding, deinflection, romaji — the
 * JS side only renders. Rejections: `E_DICT_OPEN` when the database could not be opened.
 */
import { NativeModules } from "react-native";

/** How one result reached the query, most likely first: exact, then folded/deinflected, then the romaji reader's collapsed reading, then a prefix suggestion. */
export type SearchMatch = "EXACT" | "NORMALIZED" | "LOOSE" | "PREFIX";

/** One row of the search screen: the entry, how it matched, and the form of it that matched. */
export interface SearchResult {
  /** The JMdict id, or a negative stable id for a JMnedict proper noun. */
  id: number;
  /** The display form: the first kanji form when there is one, otherwise the first kana form. */
  primary: string;
  kanji: string[];
  kana: string[];
  /** The first senses' English glosses. */
  glosses: string[];
  /** Human-readable parts of speech. */
  pos: string[];
  common: boolean;
  /** How the entry was reached. `LOOSE` is the romaji reader's long-vowel collapse. */
  match: SearchMatch;
  /** The spelling that reached the entry: the query, or the folded/deinflected form. */
  form: string;
}

interface GatenDictNative {
  search(query: string): Promise<SearchResult[]>;
  isSearchable(query: string): Promise<boolean>;
  recentLookups(): Promise<SearchResult[]>;
  rememberLookup(entry: SearchResult): Promise<void>;
  recentSearches(): Promise<string[]>;
  rememberSearch(query: string): Promise<void>;
}

const GatenDict = NativeModules.GatenDict as GatenDictNative;

/** The results for `query`, most likely first. A query the dictionary cannot answer is an empty list, not an error. */
export function search(query: string): Promise<SearchResult[]> {
  return GatenDict.search(query);
}

/** True when `query` is something the dictionary can be asked about: Japanese, or full romaji. */
export function isSearchable(query: string): Promise<boolean> {
  return GatenDict.isSearchable(query);
}

/** The twelve most recently opened word entries, kept on this device. */
export function recentLookups(): Promise<SearchResult[]> {
  return GatenDict.recentLookups();
}

/** Records an entry when its word-detail page opens. */
export function rememberLookup(entry: SearchResult): Promise<void> {
  return GatenDict.rememberLookup(entry);
}

/** The six queries searched with most recently, newest first, kept on this device. */
export function recentSearches(): Promise<string[]> {
  return GatenDict.recentSearches();
}

/** Records a query the reader searched with. */
export function rememberSearch(query: string): Promise<void> {
  return GatenDict.rememberSearch(query);
}
