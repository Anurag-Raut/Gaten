/**
 * The AnkiDroid bridge: the review session and the export settings, delegated to the same Kotlin
 * calls the Compose review screen made.
 *
 * Rejections carry the Kotlin failure's own name as the error code: `ANKI_NOT_INSTALLED`,
 * `ANKI_PERMISSION`, `ANKI_NOT_SET_UP`, `ANKI_NOT_FOUND`, `ANKI_PROVIDER`, `ANKI_MALFORMED`, with
 * the wrapped detail as the message. Export-side failures use `E_ANKI_ADD` / `E_ANKI_EXPORT` with
 * a localised message.
 */
import { NativeModules } from "react-native";

/** Whether review can run on this device. */
export type AnkiStatus = "notInstalled" | "needsPermission" | "ready";

/** One deck of the AnkiDroid collection, with the counts AnkiDroid reports for it. */
export interface AnkiDeckResult {
  id: number;
  name: string;
  newCount: number;
  learnCount: number;
  reviewCount: number;
  filtered: boolean;
}

/** What the queue answered: the next due card with its answer token, or nothing due. */
export type NextCard =
  | {
      kind: "due";
      /** The only thing grade() may answer with; spent by the first grade that reaches it. */
      token: number;
      noteId: number;
      ord: number;
      buttonLabels: string[];
      mediaFiles: string[];
    }
  | { kind: "empty" };

/** One card of the collection as AnkiDroid rendered it. */
export interface AnkiCardResult {
  noteId: number;
  /** AnkiDroid note type ID; used to apply only the mapping configured for this exact model. */
  modelId: number;
  ord: number;
  deckName: string;
  /** AnkiDroid-rendered template HTML: simplified without card CSS where the provider supports it. */
  question: string;
  answer: string;
  /** Note CSS for legacy provider output; empty when question/answer omit card CSS. */
  css: string;
  /** The note type's field names and the note's raw values, in the same order. */
  fieldNames: string[];
  fields: string[];
  typedAnswer: boolean;
}

/** What answering a card did. */
export type GradeResult = "submitted" | "notFound" | "ignored";

/** The export settings, the shape getPreferences() answers with and savePreferences() takes. */
export interface AnkiPreferencesResult {
  deckName: string;
  tags: string;
  modelId: number | null;
  modelFields: string[];
  /** One AnkiField name per field of the chosen note type: EXPRESSION, READING, MEANING or EMPTY. */
  fieldSources: string[];
  front: string;
  back: string;
  css: string;
  /** Why the stored configuration is refused, when it is: DECK, TAGS, FRONT, BACK, CSS or MAPPING. */
  validationError: AnkiSettingsError | null;
}

export type AnkiSettingsError =
  | "DECK"
  | "TAGS"
  | "FRONT"
  | "BACK"
  | "CSS"
  | "MAPPING";

/** What the customise screen can offer from the collection: deck names, and standard note types by id. */
export interface AnkiCollectionResult {
  decks: string[];
  models: Record<string, string>;
}

interface GatenAnkiNative {
  status(): Promise<AnkiStatus>;
  getConfiguredDeckName(): Promise<string>;
  decks(): Promise<AnkiDeckResult[]>;
  counts(queries: string[]): Promise<Array<number | null>>;
  nextCard(deckId: number): Promise<NextCard>;
  card(noteId: number, ord: number): Promise<AnkiCardResult>;
  grade(token: number, ease: number, timeTakenMs: number): Promise<GradeResult>;
  collection(): Promise<AnkiCollectionResult>;
  modelFields(modelId: number): Promise<string[]>;
  addNote(card: { expression: string; reading: string; meaning: string }): Promise<
    "added" | "duplicate"
  >;
  getPreferences(): Promise<AnkiPreferencesResult>;
  savePreferences(settings: AnkiPreferencesResult): Promise<AnkiSettingsError | null>;
  shareToAnkiDroid(entry: {
    primary: string;
    kana: string[];
    glosses: string[];
    pos: string[];
  }): Promise<boolean>;
  isDuplicate(card: { expression: string; reading: string; meaning: string }): Promise<boolean>;
  requestPermission(): Promise<boolean>;
  openAnkiDroid(): Promise<boolean>;
}

const GatenAnki = NativeModules.GatenAnki as GatenAnkiNative;

/** Whether review can run here. */
export function status(): Promise<AnkiStatus> {
  return GatenAnki.status();
}

/** The deck "Add to Anki" exports into, the deck picker's default selection. */
export function getConfiguredDeckName(): Promise<string> {
  return GatenAnki.getConfiguredDeckName();
}

/** Every deck AnkiDroid has. */
export function decks(): Promise<AnkiDeckResult[]> {
  return GatenAnki.decks();
}

/**
 * One count per Anki browser search, in order — a read-only count, never a card: the provider is
 * asked for the matching card ids alone, so nothing is fetched, rendered or written. A search this
 * AnkiDroid will not answer (an unknown term on an older provider) comes back as `null` for that
 * entry alone. `rated:`/`prop:rated` count *cards* over AnkiDroid's own study day (the collection's
 * rollover hour, not local midnight), so the same card graded twice in a day counts once.
 */
export function counts(queries: string[]): Promise<Array<number | null>> {
  return GatenAnki.counts(queries);
}

/** The card AnkiDroid has queued for the deck, or nothing due. */
export function nextCard(deckId: number): Promise<NextCard> {
  return GatenAnki.nextCard(deckId);
}

/** One card of the collection as AnkiDroid renders it. */
export function card(noteId: number, ord: number): Promise<AnkiCardResult> {
  return GatenAnki.card(noteId, ord);
}

/**
 * Answers the card the token was issued for with `ease` (1..the card's own button count), after
 * `timeTakenMs` spent on it. `ignored` means nothing was sent — a stale screen or a second tap.
 */
export function grade(
  token: number,
  ease: number,
  timeTakenMs: number
): Promise<GradeResult> {
  return GatenAnki.grade(token, ease, timeTakenMs);
}

/** Every deck name and standard note type the collection offers. */
export function collection(): Promise<AnkiCollectionResult> {
  return GatenAnki.collection();
}

/** The field names of the standard note type, in Anki's order. */
export function modelFields(modelId: number): Promise<string[]> {
  return GatenAnki.modelFields(modelId);
}

/** Adds one note into the configured deck; `duplicate` when the note is already there. */
export function addNote(card: {
  expression: string;
  reading: string;
  meaning: string;
}): Promise<"added" | "duplicate"> {
  return GatenAnki.addNote(card);
}

/** The saved export settings, with their validation verdict. */
export function getPreferences(): Promise<AnkiPreferencesResult> {
  return GatenAnki.getPreferences();
}

/**
 * Hands one entry to AnkiDroid's own add screen — the fallback when the database permission is
 * not granted, since the reader picks the deck and the note type there. Resolves `false` when
 * nothing on the device can take the share.
 */
export function shareToAnkiDroid(entry: {
  primary: string;
  kana: string[];
  glosses: string[];
  pos: string[];
}): Promise<boolean> {
  return GatenAnki.shareToAnkiDroid(entry);
}

/**
 * Whether [card] is already in the deck an add would write to, judged against the saved
 * settings. A provider failure, a broken configuration or a vanished note type reads as `false`
 * — never an error.
 */
export function isDuplicate(card: {
  expression: string;
  reading: string;
  meaning: string;
}): Promise<boolean> {
  return GatenAnki.isDuplicate(card);
}

/**
 * Asks the reader for AnkiDroid's database permission. Resolves `true` when they granted it;
 * nothing is exported on a grant — exporting stays the button's next tap.
 */
export function requestPermission(): Promise<boolean> {
  return GatenAnki.requestPermission();
}

/** Opens AnkiDroid itself; false when it is not visible or launches nothing, never an error. */
export function openAnkiDroid(): Promise<boolean> {
  return GatenAnki.openAnkiDroid();
}

/**
 * Saves the settings (the native side normalises the deck name and the tags) and answers with the
 * validation verdict on what was stored — an invalid configuration is saved as it is, not replaced.
 */
export function savePreferences(
  settings: AnkiPreferencesResult
): Promise<AnkiSettingsError | null> {
  return GatenAnki.savePreferences(settings);
}
