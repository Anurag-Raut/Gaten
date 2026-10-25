/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * WaniKani's two queues, drawn as the very rows Anki's decks are: what the queue is called, how much
 * of it is waiting, and — once it is clear — the success check in the reader's accent. A tap anywhere
 * on a row is the whole action: it opens the review or lesson flow, retries the read that failed, or
 * takes the reader to the connection screen. The header's own Refresh control is the one button the
 * page carries; there is no summary line and no count the hub did not confirm.
 *
 * While the row the reader acted on is still working, that row says what is happening — Opening
 * reviews…, Opening lessons…, Refreshing — with a spinner, every row goes quiet, and the row's
 * accessibility state is busy. A background re-read does not lock anything: it only adds a quiet
 * "Updating…" line under the heading.
 */
import React, {useState} from "react";
import {ScrollView, StyleSheet, Text, View, useWindowDimensions} from "react-native";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import {DialogShell} from "../settings/settings-components";
import {Icon, IconButton} from "../components";
import {PressableScale} from "../PressableScale";
import {MAX_CONTENT_WIDTH, spacing, typeScale} from "../tokens";
import {useTheme} from "../theme";
import {MetricPair, useAnalyticsEntrance} from "../analytics/ink";
import type {WaniKaniHubState} from "./review-store";
import type {WaniKaniLearningPreferences} from "../../native/GatenWaniKani";
import {deriveWaniKaniHub, type HubModuleModel} from "./wanikani-hub-model";
import {SectionCaption, StudyRow, SuccessCheck} from "./review-ui";

/**
 * Today's activity fields are optional in the bridge type: an older native side (or a test bridge)
 * may not carry them at all, in which case nothing is claimed about today. `activityDate` stamps the
 * local calendar day the counts belong to, as `YYYY-MM-DD`.
 */

/** The device's local calendar day, in the same `YYYY-MM-DD` form the activity stamp uses. */
function localDayKey(now: Date): string {
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  return `${now.getFullYear()}-${month}-${day}`;
}

/**
 * The next time a row already states in its own extra line, said the way a sentence is read out:
 * `Next review · In 2 hours` is heard as "next review in 2 hours". Rows whose extra is not about a
 * time — a batch, a progress count, an updating queue — add no clause.
 */
function nextReviewClause(extra: string | null): string | null {
  if (extra == null) return null;
  const [label, value] = extra.split(" · ");
  if (value == null) return null;
  if (label !== "Next review" && label !== "No reviews due") return null;
  // The cleared row states the time as "Next in 2 hours"; only the value is spoken.
  const said = label === "No reviews due" ? value.replace(/^Next /, "") : value;
  return said === "" ? null : `next review ${said[0].toLowerCase()}${said.slice(1)}`;
}

/**
 * What activating the row really does. A stale row retries the read rather than opening a queue, so
 * it must not be announced as opening one.
 */
function destinationClause(model: HubModuleModel): string | null {
  switch (model.tap) {
    case "reviews":
      return "open reviews";
    case "lessons":
      return "open lessons";
    case "sync":
      return "refresh to update";
    default:
      return null;
  }
}

/** How one row is spoken: its count or its state, the next time it knows of, and where it leads. */
function rowLabel(model: HubModuleModel): string {
  if (model.loading) return `${model.title}, loading`;
  if (model.trailing != null) {
    const clause = nextReviewClause(model.extra);
    const destination = destinationClause(model);
    return `${model.title}, ${model.trailing.value} ${model.trailing.label}${
      clause == null ? "" : `, ${clause}`
    }${destination == null ? "" : `, ${destination}`}`;
  }
  if (model.complete) {
    const clause = nextReviewClause(model.extra);
    return `${model.title}, all caught up${clause == null ? "" : `, ${clause}`}`;
  }
  const said = model.subline === "" ? "" : model.subline[0].toLowerCase() + model.subline.slice(1);
  return said === "" ? model.title : `${model.title}, ${said}`;
}

/** The row's own shape while its queue is being read: the geometry, with no number in it yet. */
function HubRowSkeleton({title, minHeight}: {title: string; minHeight: number}) {
  const {colors} = useTheme();
  return (
    <View
      accessible
      accessibilityLabel={`${title}, loading`}
      style={[styles.row, {minHeight, borderBottomColor: colors.outlineVariant}]}
    >
      <View style={[styles.holder, {backgroundColor: colors.surfaceVariant}]} />
      <View style={styles.skeletonMain}>
        <View style={[styles.skeletonTitle, {backgroundColor: colors.surfaceVariant}]} />
        <View style={[styles.skeletonSubline, {backgroundColor: colors.surfaceVariant}]} />
      </View>
      <View style={[styles.skeletonCount, {backgroundColor: colors.surfaceVariant}]} />
    </View>
  );
}

export function WaniKaniHome({
  hub,
  busy,
  onBack,
  onStartReviews,
  onStartLessons,
  onRetrySync,
  onConnect,
}: {
  hub: WaniKaniHubState;
  busy: boolean;
  onBack: () => void;
  onStartReviews: () => void;
  onStartLessons: () => void;
  onRetrySync: () => void;
  onConnect: () => void;
}) {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  const {width, fontScale} = useWindowDimensions();
  const inset = width < 360 ? spacing.l : spacing.contentInset;
  // Narrow screens and large type get a taller floor for the row: its lines wrap instead of clipping.
  const stacked = width < 340 || fontScale >= 1.5;
  const [reviews, lessons] = deriveWaniKaniHub(hub);
  const allClear = reviews.complete && lessons.complete;
  const [todayHelpOpen, setTodayHelpOpen] = useState(false);
  // The one row action the reader is waiting on. Every row disables while it is in flight, and the
  // row it belongs to shows the spinner and what is happening instead of its standing words.
  const pending = hub.pendingAction ?? null;
  const pendingText =
    pending === "reviews" ? "Opening reviews…"
    : pending === "lessons" ? "Opening lessons…"
    : pending === "sync" ? "Refreshing"
    : null;
  const locked = busy || pending != null;

  // Today's two device-local figures. Each is shown only when the read that produced it succeeded
  // and is stamped with today's own date; anything else is a dash with the reason beside it.
  const prefs: WaniKaniLearningPreferences | null = hub.learningPreferences ?? null;
  const prefsFailed = hub.learningPreferencesFailed === true;
  const storageFailed = prefs?.activityFailure === "STORAGE";
  const datedToday = prefs?.activityDate === localDayKey(new Date());
  const reviewed =
    prefs != null && !prefsFailed && datedToday && !storageFailed && typeof prefs.reviewedToday === "number"
      ? prefs.reviewedToday
      : null;
  const learned =
    prefs != null && !prefsFailed && datedToday && !storageFailed ? prefs.learnedToday : null;
  const hintFor = (known: boolean): string | null => {
    if (prefs == null) return prefsFailed ? "Couldn't read your settings." : "Checking…";
    if (storageFailed) return "Couldn't read today's activity.";
    if (!datedToday) return "Not available for today.";
    return known ? null : "Not tracked yet on this device.";
  };
  // The analytics page's one-shot entrance: the Today figures sweep in once, when the first of
  // them is actually known, and never replay on a refresh.
  const entrance = useAnalyticsEntrance(reviewed != null || learned != null);

  const pressFor = (model: HubModuleModel) => {
    switch (model.tap) {
      case "reviews":
        return onStartReviews;
      case "lessons":
        return onStartLessons;
      case "sync":
        return onRetrySync;
      case "connect":
        return onConnect;
      default:
        return null;
    }
  };

  // One caveat belongs to one snapshot: where both rows carry the same words, the page says them
  // once, under both rows, the way the design draws its own footnote. A caveat only one queue has —
  // a lock reason, a daily-limit read failure — stays with the row it belongs to.
  const sharedCaveat =
    reviews.notice != null && reviews.notice === lessons.notice ? reviews.notice : null;

  const rowFor = (model: HubModuleModel) => {
    // The spinner belongs to the row the reader acted on; a sync in flight shows on the row that
    // asked for the read. Every other row just goes quiet.
    const pendingRow =
      pending != null && (pending === model.id || (pending === "sync" && model.tap === "sync"));
    return (
      <StudyRow
        art={<Icon name={model.icon} size={24} color={colors.accent} />}
        holderSurface
        title={model.title}
        titleSize={17}
        subline={pendingRow && pendingText != null ? pendingText : model.subline}
        extra={model.extra}
        trailing={model.trailing}
        complete={model.complete}
        loading={pendingRow}
        minHeight={stacked ? 118 : 110}
        accessibilityLabel={rowLabel(model)}
        disabled={locked}
        onPress={pressFor(model)}
      />
    );
  };

  const sectionFor = (model: HubModuleModel) => {
    if (model.loading) {
      return <HubRowSkeleton title={model.title} minHeight={stacked ? 118 : 110} />;
    }
    const notice = model.notice;
    return (
      <>
        {rowFor(model)}
        {notice != null && notice !== sharedCaveat && (
          <Text style={[styles.notice, {color: colors.onSurfaceVariant}]}>{notice}</Text>
        )}
      </>
    );
  };

  return (
    <View style={[styles.screen, {backgroundColor: colors.background, paddingTop: insets.top}]}>
      <ScrollView
        showsVerticalScrollIndicator={false}
        contentContainerStyle={[
          styles.body,
          {
            paddingTop: spacing.s,
            paddingBottom: insets.bottom + spacing.xl,
            paddingLeft: insets.left + inset,
            paddingRight: insets.right + inset,
          },
        ]}
      >
        <View style={styles.content}>
          <View style={styles.top}>
            <PressableScale
              accessibilityRole="button"
              accessibilityLabel="Back"
              onPress={onBack}
              style={styles.back}
            >
              <Icon name="arrow_back" size={24} color={colors.onSurface} />
            </PressableScale>
            <Text style={[styles.topTitle, {color: colors.onSurface}]}>WaniKani</Text>
            <PressableScale
              accessibilityRole="button"
              accessibilityLabel="Refresh"
              accessibilityState={{disabled: locked}}
              disabled={locked}
              onPress={onRetrySync}
              style={[styles.refresh, {backgroundColor: colors.surfaceVariant}]}
            >
              <Icon
                name="refresh"
                size={22}
                color={locked ? colors.onSurfaceVariant : colors.accent}
              />
            </PressableScale>
          </View>
          <Text accessibilityRole="header" style={[typeScale.headlineSmall, {color: colors.onSurface}]}>
            Your study
          </Text>
          <Text style={[styles.subtitle, {color: colors.onSurfaceVariant}]}>
            {allClear ? "Everything available is clear for now." : "Choose what to work on next."}
          </Text>
          {pending === "sync" && (
            <Text
              accessibilityLiveRegion="polite"
              style={[styles.subtitle, styles.updating, {color: colors.onSurfaceVariant}]}
            >
              Refreshing
            </Text>
          )}
          {pending !== "sync" && hub.refreshing && !busy && (
            <Text
              accessibilityLiveRegion="polite"
              style={[styles.subtitle, styles.updating, {color: colors.onSurfaceVariant}]}
            >
              Updating…
            </Text>
          )}
          {allClear && (
            <View
              style={styles.caughtUp}
              accessible
              accessibilityLabel="You're all caught up. No reviews or new lessons are waiting."
            >
              <SuccessCheck />
              <Text style={[styles.caughtUpText, {color: colors.onSurfaceVariant}]}>
                You’re all caught up — no reviews or new lessons are waiting.
              </Text>
            </View>
          )}
          {/* Nothing done today says nothing: the block stands only while one of its figures is a
              real count, and an unread figure (a dash) keeps it up so its reason stays visible. */}
          {!(reviewed === 0 && learned === 0) && (
            <>
              <View style={[styles.todayHeading, {borderBottomColor: colors.outlineVariant}]}>
                <SectionCaption style={styles.todayCaption}>Today</SectionCaption>
                <IconButton
                  name="help"
                  size={18}
                  accessibilityLabel="About today’s activity"
                  onPress={() => setTodayHelpOpen(true)}
                />
              </View>
              <View style={styles.todayMetrics}>
                <MetricPair
                  left={{
                    value: reviewed == null ? "—" : String(reviewed),
                    count: reviewed == null ? null : {value: reviewed},
                    caption: "Items reviewed",
                    note: hintFor(reviewed != null),
                  }}
                  right={{
                    value: learned == null ? "—" : String(learned),
                    count: learned == null ? null : {value: learned},
                    caption: "Lessons learned",
                    note: hintFor(learned != null),
                  }}
                  entrance={entrance}
                />
              </View>
            </>
          )}
          <SectionCaption>Available study</SectionCaption>
          {sectionFor(reviews)}
          {sectionFor(lessons)}
          {sharedCaveat != null && (
            <Text style={[styles.caveat, {color: colors.onSurfaceVariant}]}>{sharedCaveat}</Text>
          )}
        </View>
      </ScrollView>
      {todayHelpOpen && (
        <DialogShell
          title="About today’s activity"
          onDismiss={() => setTodayHelpOpen(false)}
          actions={
            <Text
              accessibilityRole="button"
              onPress={() => setTodayHelpOpen(false)}
              style={[styles.dialogAction, {color: colors.accent}]}
            >
              Close
            </Text>
          }
        >
          <Text style={[styles.dialogParagraph, {color: colors.onSurfaceVariant}]}>
            Counts include distinct items reviewed and lessons learned in Gaten on this device today.
            Repeated reviews of an item count once.
          </Text>
          <Text style={[styles.dialogParagraph, {color: colors.onSurfaceVariant}]}>
            Today resets at local midnight. Review tracking starts with this version; activity from
            WaniKani or other devices is not included.
          </Text>
        </DialogShell>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {flex: 1},
  body: {flexGrow: 1},
  content: {width: "100%", maxWidth: MAX_CONTENT_WIDTH, alignSelf: "center"},
  top: {flexDirection: "row", alignItems: "center", minHeight: 48, marginBottom: 29},
  back: {width: 48, height: 48, alignItems: "flex-start", justifyContent: "center"},
  // The header's one button, drawn as the analytics page draws its own: a quiet circular chip, not a
  // bare icon.
  refresh: {width: 44, height: 44, borderRadius: 22, marginLeft: "auto", alignItems: "center", justifyContent: "center"},
  topTitle: {...typeScale.titleLarge, flexShrink: 1},
  subtitle: {fontFamily: "Inter", fontSize: 12, lineHeight: 17, marginTop: spacing.s},
  updating: {marginTop: 4},
  // The all-clear state is one flat line, not a surface: the accent check and a quiet sentence.
  caughtUp: {flexDirection: "row", alignItems: "center", gap: spacing.s, marginTop: 22},
  caughtUpText: {fontFamily: "Inter", fontSize: 12, lineHeight: 17, flex: 1, minWidth: 0},
  // The Today heading row: the caption's own text stretches, the help target stands beside it, and
  // the row carries the one hairline the SectionCaption used to draw alone. The caption sits on the
  // rule's own baseline — bottom-aligned — so the text keeps the 12 dp gap every other caption has
  // instead of floating in a taller row, and the figures start one step under the rule.
  todayHeading: {
    flexDirection: "row",
    alignItems: "flex-end",
    marginTop: 22,
    paddingBottom: 12,
    borderBottomWidth: 1,
  },
  todayCaption: {flex: 1, marginTop: 0, marginBottom: 0, borderBottomWidth: 0, paddingBottom: 0},
  todayMetrics: {marginTop: 12},
  dialogAction: {fontSize: 15, fontWeight: "600", lineHeight: 20, paddingHorizontal: spacing.m, paddingVertical: 14},
  dialogParagraph: {fontFamily: "Inter", fontSize: 14, lineHeight: 20, marginBottom: spacing.m},
  notice: {fontFamily: "Inter", fontSize: 11, lineHeight: 15, marginTop: spacing.m, marginBottom: spacing.xs},
  // The page's own footnote: the design's 11 dp muted line, one spacing step under the last row.
  caveat: {fontFamily: "Inter", fontSize: 11, lineHeight: 15, marginTop: spacing.m},
  // The skeleton is the shared row's own geometry: 48 dp holder, 12 dp gap, one 1 dp divider.
  row: {
    flexDirection: "row",
    alignItems: "center",
    gap: spacing.m,
    paddingVertical: spacing.m,
    borderBottomWidth: 1,
    width: "100%",
  },
  holder: {width: 48, height: 48, borderRadius: 12, flexShrink: 0},
  skeletonMain: {flex: 1, minWidth: 0, gap: spacing.s},
  skeletonTitle: {width: "58%", height: 15, borderRadius: 5},
  skeletonSubline: {width: "82%", height: 12, borderRadius: 5},
  skeletonCount: {width: 24, height: 23, borderRadius: 5},
});
