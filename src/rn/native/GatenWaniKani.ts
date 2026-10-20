/**
 * The WaniKani bridge: the token's state, the content behind a tapped character, and the account's own
 * numbers for the analytics dashboard.
 *
 * Everything is resolved on the Kotlin side, from the same repository the Compose detail screen
 * used — the token and the cache stay where they always were, and the token never crosses this
 * boundary in either direction. Rejections carry the failure's own name as the error code:
 * `WK_OFFLINE`, `WK_TIMEOUT`, `WK_RATE_LIMITED`, `WK_SERVER`, `WK_TOO_LARGE`, `WK_MALFORMED`,
 * `WK_STORAGE`. The stats reads are the exception: a failure there is one of their own answers
 * (`status: "failed"`), because the dashboard shows it beside the numbers it already has.
 */
import { NativeModules } from "react-native";

/** What checking a token answered. */
export type WaniKaniConnectionResult =
  | {
      status: "connected";
      /** The highest subject level the account may be shown: 3 free, 60 paid. */
      maxLevelGranted: number;
    }
  | { status: "invalidToken" };

/** One subject as the screens render it: a kanji or a radical — the two kinds a reader can tap. */
export type WaniKaniSubject = KanjiSubject | RadicalSubject;

export interface WaniKaniComponent {
  id: number;
  /** Null when the component is not cached yet; the chip still knows what to look up. */
  character: string | null;
  name: string | null;
}

export interface KanjiSubject {
  kind: "kanji";
  id: number;
  character: string;
  level: number;
  onyomi: string[];
  kunyomi: string[];
  /** The radicals the kanji is built from, in WaniKani's order. */
  components: WaniKaniComponent[];
  meanings: string[];
  primaryMeaning: string;
  meaningMnemonic: string;
  readingMnemonic: string;
  documentUrl?: string;
}

export interface RadicalSubject {
  kind: "radical";
  id: number;
  /** Null when the radical exists only as an image. */
  character: string | null;
  level: number;
  meanings: string[];
  primaryMeaning: string;
  meaningMnemonic: string;
  /** The radical's image as raw SVG text, ready for a locked-down WebView. */
  svg?: string;
  documentUrl?: string;
}

/** What a tap answered. The failure-shaped states reject; these are answers, not errors. */
export type WaniKaniLookupResult =
  | {
      status: "available";
      subject: WaniKaniSubject;
      /** True when the answer came from the local cache. */
      cached: boolean;
      /** True when the refresh that was asked for failed and the cache answered instead. */
      stale: boolean;
      /** Set when the answer could not be saved: the content is for this session only. */
      cacheFailure?: WaniKaniFailure;
    }
  | { status: "noEntry" }
  | { status: "restricted" }
  | { status: "needsToken" }
  | { status: "authRejected" };

export type WaniKaniFailure =
  | "OFFLINE"
  | "TIMEOUT"
  | "RATE_LIMITED"
  | "SERVER"
  | "TOO_LARGE"
  | "MALFORMED"
  | "STORAGE";

/** The account's own numbers, as the dashboard reads them: one snapshot, no history and no estimates. */
export interface WaniKaniStatsSnapshot {
  /** The account's WaniKani username, which is what the dashboard calls the source of these numbers. */
  username: string;
  /** The level the reader is on. */
  level: number;
  /** What the subscription covers: 3 for a free account, 60 for a paid one — the API's own values. */
  maxLevelGranted: number;
  /** `free`, `recurring`, `lifetime` or `unknown`, as WaniKani reports it. */
  subscriptionType: string;
  /** Reviews available in the current hour — the first bucket of the summary. */
  dueNow: number;
  /** Epoch milliseconds of the next review, or null when the account has none scheduled. */
  nextReviewAt: number | null;
  /** The next 24 hours by the hour: 25 buckets, the first being the current one. */
  forecast: {availableAt: number; count: number}[];
  /** The SRS spread, one entry per bucket, labelled as WaniKani's own guide names the stages. */
  stages: {key: string; label: string; count: number}[];
  /** Epoch milliseconds when this snapshot was read from WaniKani. */
  fetchedAt: number;
}

/** What a stats read answered: numbers, or the one thing that is missing. */
export type WaniKaniStatsResult =
  | {
      status: "available";
      snapshot: WaniKaniStatsSnapshot;
      /** True when the numbers came from the on-device snapshot rather than a fresh read. */
      cached: boolean;
      /** True when a refresh was attempted and failed: these are the last complete numbers. */
      stale: boolean;
      /** Why a refresh failed, when one did — a note beside the numbers, never instead of them. */
      failure?: WaniKaniFailure;
      /** Seconds WaniKani asked us to wait before asking again (a rate limit). */
      retryAfterSeconds?: number;
      /** Set when the snapshot could not be written: these numbers are for this session only. */
      cacheFailure?: WaniKaniFailure;
    }
  | {status: "needsToken"}
  | {status: "authRejected"}
  | {status: "failed"; failure: WaniKaniFailure; retryAfterSeconds?: number};

interface GatenWaniKaniNative {
  isConfigured(): Promise<boolean>;
  connect(token: string): Promise<WaniKaniConnectionResult>;
  reconnect(): Promise<WaniKaniConnectionResult>;
  disconnect(): Promise<boolean>;
  lookupKanji(character: string, refresh: boolean): Promise<WaniKaniLookupResult>;
  lookupRadical(id: number, refresh: boolean): Promise<WaniKaniLookupResult>;
  stats(): Promise<WaniKaniStatsResult>;
  refreshStats(): Promise<WaniKaniStatsResult>;
}

const GatenWaniKani = NativeModules.GatenWaniKani as GatenWaniKaniNative;

/** True when a token is stored; cheap enough to call while a screen renders. */
export function isConfigured(): Promise<boolean> {
  return GatenWaniKani.isConfigured();
}

/** Checks the token with WaniKani and stores it when it works; a rejected or unreachable token is not stored. */
export function connect(token: string): Promise<WaniKaniConnectionResult> {
  return GatenWaniKani.connect(token);
}

/** Checks the stored token again — the "recheck subscription" action. */
export function reconnect(): Promise<WaniKaniConnectionResult> {
  return GatenWaniKani.reconnect();
}

/** Forgets the token and every cached subject, image and entitlement. */
export function disconnect(): Promise<boolean> {
  return GatenWaniKani.disconnect();
}

/** The content behind a kanji character (one code point, possibly outside the basic plane). */
export function lookupKanji(
  character: string,
  refresh = false
): Promise<WaniKaniLookupResult> {
  return GatenWaniKani.lookupKanji(character, refresh);
}

/** The content behind a radical, by its WaniKani subject id — a kanji component tapped. */
export function lookupRadical(
  id: number,
  refresh = false
): Promise<WaniKaniLookupResult> {
  return GatenWaniKani.lookupRadical(id, refresh);
}

/**
 * The account's own numbers for the dashboard. The token is read on the Kotlin side and never reaches
 * this layer: no argument carries it, and no answer contains it.
 */
export function stats(): Promise<WaniKaniStatsResult> {
  return GatenWaniKani.stats();
}

/** Asks WaniKani again whatever the cache says — the dashboard's refresh action. */
export function refreshStats(): Promise<WaniKaniStatsResult> {
  return GatenWaniKani.refreshStats();
}
