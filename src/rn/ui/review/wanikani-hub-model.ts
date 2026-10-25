/**
 * The small, render-ready description of each WaniKani queue. Keeping these decisions pure makes
 * cached, partial and paused states consistent without coupling the view to the review store.
 *
 * Each queue becomes one row of the hub: a name, one status line, at most one further line in the
 * `Label · value` grammar, the count with its caption — or the green check once the queue is clear —
 * and the single action the whole row carries. A count is only ever stated when the read that
 * produced it succeeded; a cached snapshot is stated together with the caveat that says so, and a
 * cached zero never reads as a finished queue.
 */
import type {IconName} from "../icons";
import type {
  WaniKaniHubState,
  WaniKaniLessonSession,
  WaniKaniSession,
  WaniKaniSummary,
} from "./review-store";
import {LESSON_BATCH_SIZE} from "./review-store";

export type HubTap = "reviews" | "lessons" | "sync" | "connect";

export interface HubModuleModel {
  id: "reviews" | "lessons";
  title: string;
  icon: IconName;
  subline: string;
  extra: string | null;
  trailing: {value: string; label: string} | null;
  complete: boolean;
  /** The whole row's action; null when there is nothing to open, retry or connect. */
  tap: HubTap | null;
  loading: boolean;
  refreshing: boolean;
  stale: boolean;
  /** The one small note under the row: why it is locked, or that its numbers are the saved ones. */
  notice: string | null;
}

/** The caveat a snapshot cached while offline is stated with. */
const OFFLINE_NOTE = "Offline · displaying last known values.";

/** The caveat a snapshot whose refresh failed is stated with. */
const FAILED_NOTE = "Refresh failed · showing saved count";

/**
 * The caveat a cached snapshot is stated with: the device is offline, a rate limit is still asking
 * for a wait, or the refresh simply failed. The wait is named because it is the one caveat with
 * something to do about it — the reader can act when the seconds run out, and not before.
 */
function staleNote(summary: WaniKaniSummary): string {
  if (summary.sync === "offline") return OFFLINE_NOTE;
  if (summary.retryAfterSeconds != null) return `Try again in ${summary.retryAfterSeconds} seconds.`;
  return FAILED_NOTE;
}

function countLabel(count: number, singular: string, plural: string): string {
  return count === 1 ? singular : plural;
}

function failureCopy(failure: WaniKaniHubState["failure"]): string {
  if (failure?.failure === "RATE_LIMITED" && failure.retryAfterSeconds != null) {
    return `Try again in ${failure.retryAfterSeconds} seconds.`;
  }
  if (failure?.failure === "OFFLINE") return "You're offline. Try again when connected.";
  return "We couldn't load this queue. Try again.";
}

/** When the next reviews come up, as the row says it: due now, in a while, or a named time. */
function nextReview(value: number, now: number): string {
  const minutes = Math.max(0, Math.round((value - now) / 60_000));
  if (minutes === 0) return "Due now";
  if (minutes < 60) return `In ${minutes} min`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (hours < 24) {
    const hourPart = `In ${hours} ${hours === 1 ? "hour" : "hours"}`;
    return rest === 0 ? hourPart : `${hourPart} ${rest} min`;
  }
  return new Date(value).toLocaleString([], {weekday: "short", hour: "numeric", minute: "2-digit"});
}

/** Counts only the subjects already in a paused batch, so an unavailable queue never gets an invented breakdown. */
function summarizeBatch(session: WaniKaniLessonSession): string {
  const counts: Record<string, number> = {"radical": 0, "kanji": 0, "vocab": 0, "kana vocab": 0};
  for (const item of session.batch) {
    const label = item.type === "radical" ? "radical" : item.type === "kanji" ? "kanji" : item.type === "kana_vocabulary" ? "kana vocab" : "vocab";
    counts[label] += 1;
  }
  return Object.entries(counts)
    .filter(([, count]) => count > 0)
    .map(([label, count]) => `${count} ${label}${count === 1 || label !== "radical" ? "" : "s"}`)
    .join(" · ");
}

/**
 * What the row says while a finished session's queue is being read again. Both queues come from the
 * one summary read, so both rows say it — and once that read lands the store clears the flag.
 */
function sessionUpdate(hub: WaniKaniHubState, stale: boolean): string | null {
  if (!hub.sessionFinished) return null;
  if (hub.refreshing) return "Updating queue…";
  if (hub.failed || stale) return "Session finished · queue update unavailable.";
  return null;
}

/** A reviews row at rest: nothing counted, nothing extra to say, nothing to open. */
function reviewsRow(subline: string): HubModuleModel {
  return {
    id: "reviews",
    title: "Reviews",
    icon: "history",
    subline,
    extra: null,
    trailing: null,
    complete: false,
    tap: null,
    loading: false,
    refreshing: false,
    stale: false,
    notice: null,
  };
}

/** A lessons row at rest: nothing counted, nothing extra to say, nothing to open. */
function lessonsRow(subline: string): HubModuleModel {
  return {
    id: "lessons",
    title: "Lessons",
    icon: "library_books",
    subline,
    extra: null,
    trailing: null,
    complete: false,
    tap: null,
    loading: false,
    refreshing: false,
    stale: false,
    notice: null,
  };
}

/** Resolves review priority from paused work, connection state, per-module failure and the latest snapshot. */
function reviewsModel(hub: WaniKaniHubState, now: number): HubModuleModel {
  if (hub.connection !== "connected") {
    return {
      ...reviewsRow(hub.connection === "expired" ? "Reconnect to see reviews" : "Connect to see reviews"),
      tap: "connect",
    };
  }
  const session: WaniKaniSession | null = hub.pausedReview;
  if (session != null) {
    const remaining = Math.max(0, session.items.length - session.index);
    return {
      ...reviewsRow(
        `${remaining} ${countLabel(remaining, "review left in session", "reviews left in session")}`,
      ),
      extra: `Progress · ${session.doneParts} of ${session.totalParts} parts`,
      trailing: {value: String(remaining), label: "left"},
      tap: "reviews",
      refreshing: hub.refreshing,
    };
  }
  if (hub.reviewFailure != null) {
    // The queue's own read failed: the row retries that read, and never states a count for it.
    return {...reviewsRow("Reviews unavailable"), tap: "reviews", notice: failureCopy(hub.reviewFailure)};
  }
  if (hub.summary == null) {
    if (!hub.failed) return {...reviewsRow(""), loading: true};
    return {...reviewsRow("Reviews unavailable"), tap: "sync", notice: failureCopy(hub.failure)};
  }
  const summary = hub.summary;
  const stale = summary.sync !== "fresh";
  const update = sessionUpdate(hub, stale);
  const next = summary.nextReviewAt == null ? null : nextReview(summary.nextReviewAt, now);
  if (stale) {
    // A cached count is still a count: with work waiting, the row opens the queue it knows about and
    // the queue read is itself the validation. A cached zero is no evidence of a finished queue, so
    // that row retries the read instead — only WaniKani can say the queue is really clear. After a
    // finished session the counts predate the work just done, so neither row may open a queue the
    // session may have emptied: both offer the read instead.
    return {
      ...reviewsRow(`${summary.dueNow} ${countLabel(summary.dueNow, "review due", "reviews due")}`),
      extra: update ?? (next == null ? null : `Next review · Last known: ${next}`),
      trailing: {value: String(summary.dueNow), label: "due"},
      tap: hub.sessionFinished === true ? "sync" : summary.dueNow > 0 ? "reviews" : "sync",
      stale: true,
      notice: staleNote(summary),
    };
  }
  // A time that has already passed is not a next review: a clear queue then says only that it is clear.
  const upcoming = summary.nextReviewAt != null && summary.nextReviewAt > now ? next : null;
  if (summary.dueNow === 0) {
    return {
      ...reviewsRow("All caught up"),
      extra:
        update ??
        (upcoming == null
          ? "No reviews due"
          : `No reviews due · Next ${upcoming[0].toLowerCase()}${upcoming.slice(1)}`),
      complete: true,
      refreshing: hub.refreshing,
    };
  }
  return {
    ...reviewsRow(`${summary.dueNow} ${countLabel(summary.dueNow, "review due", "reviews due")}`),
    extra: update ?? (next == null ? null : `Next review · ${next}`),
    trailing: {value: String(summary.dueNow), label: "due"},
    tap: "reviews",
    refreshing: hub.refreshing,
  };
}

/** Resolves lesson priority from a real paused batch, access, per-module failure and cached counts. */
function lessonsModel(hub: WaniKaniHubState): HubModuleModel {
  if (hub.connection !== "connected") {
    return {
      ...lessonsRow(hub.connection === "expired" ? "Reconnect to see lessons" : "Connect to see lessons"),
      tap: "connect",
    };
  }
  if (hub.lessonsLocked) {
    return {
      ...lessonsRow("Lessons unavailable"),
      notice: "Lessons are locked by your current level or plan.",
      refreshing: hub.refreshing,
    };
  }
  if (hub.lessonFailure != null) {
    return {...lessonsRow("Lessons unavailable"), tap: "lessons", notice: failureCopy(hub.lessonFailure)};
  }
  if (hub.summary == null) {
    if (!hub.failed) return {...lessonsRow(""), loading: true};
    return {...lessonsRow("Lessons unavailable"), tap: "sync", notice: failureCopy(hub.failure)};
  }
  const preferences = hub.learningPreferences;
  const preferencesUnavailable = hub.learningPreferencesFailed === true;
  const dailyLimit = preferences?.dailyLimit ?? null;
  const learnedToday = preferences?.learnedToday ?? 0;
  const limitNotice = preferencesUnavailable ? "Daily limit unavailable. Try again." : null;
  if (dailyLimit != null && learnedToday >= dailyLimit) {
    // Nothing may be studied again today, but the queue itself is not clear: this is a plan limit, not
    // an empty queue, so it never wears the check the cleared rows wear.
    return {
      ...lessonsRow("Daily limit reached"),
      extra: `Learned today · ${learnedToday} of ${dailyLimit}`,
      refreshing: hub.refreshing,
      notice: "More lessons tomorrow. Change your limit in WaniKani settings.",
    };
  }
  const session = hub.pausedLesson;
  if (session != null) {
    const remaining = Math.max(0, session.batch.length - session.quizIndex);
    return {
      ...lessonsRow(
        `${remaining} ${countLabel(remaining, "lesson left in batch", "lessons left in batch")}`,
      ),
      extra: [`Progress · ${session.doneParts} of ${session.totalParts} parts`, summarizeBatch(session)]
        .filter((part) => part !== "")
        .join(" · "),
      trailing: {value: String(remaining), label: "left"},
      tap: "lessons",
      refreshing: hub.refreshing,
      notice: limitNotice,
    };
  }
  const summary = hub.summary;
  const stale = summary.sync !== "fresh";
  const update = sessionUpdate(hub, stale);
  const count = summary.lessonsNow;
  // The batch is only named when the reader's own settings say how many cards it would hold: an
  // unavailable daily limit means the composition is not known, so the row says nothing about it.
  const remainingDailyBudget =
    preferences == null
      ? null
      : dailyLimit == null
        ? LESSON_BATCH_SIZE
        : Math.max(0, dailyLimit - learnedToday);
  const batchSize =
    remainingDailyBudget == null ? null : Math.min(count, LESSON_BATCH_SIZE, remainingDailyBudget);
  const batch =
    batchSize == null || count === 0
      ? null
      : `Next batch · ${batchSize} ${batchSize === 1 ? "card" : "cards"}`;
  if (stale) {
    // As in the reviews row: a cached nonempty queue opens, and the read that follows validates it;
    // a cached zero offers the read instead of claiming there is nothing to learn. A finished
    // session's counts predate the work, so this row too offers the read rather than a queue the
    // session may have changed.
    return {
      ...lessonsRow(`${count} ${countLabel(count, "lesson available", "lessons available")}`),
      extra: update ?? batch,
      trailing: {value: String(count), label: "ready"},
      tap: hub.sessionFinished === true ? "sync" : count > 0 ? "lessons" : "sync",
      stale: true,
      notice: staleNote(summary),
    };
  }
  if (count === 0) {
    return {
      ...lessonsRow("All caught up"),
      extra: update ?? "No new lessons available",
      complete: true,
      refreshing: hub.refreshing,
    };
  }
  return {
    ...lessonsRow(`${count} ${countLabel(count, "lesson available", "lessons available")}`),
    extra: update ?? batch,
    trailing: {value: String(count), label: "ready"},
    tap: "lessons",
    refreshing: hub.refreshing,
    notice: limitNotice,
  };
}

/** Builds the two independent modules from one confirmed hub snapshot and its request state. */
export function deriveWaniKaniHub(hub: WaniKaniHubState, now = Date.now()): [HubModuleModel, HubModuleModel] {
  return [reviewsModel(hub, now), lessonsModel(hub)];
}
