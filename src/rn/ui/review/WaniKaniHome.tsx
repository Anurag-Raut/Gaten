/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * WaniKani's two queues, drawn as the very rows Anki's decks are: what the queue is called, how much
 * of it is waiting, and — once it is clear — the same green check. A tap anywhere on a row is the
 * whole action: it opens the review or lesson flow, retries the read that failed, or takes the reader
 * to the connection screen. The hub has no buttons of its own, no summary line and no count it did
 * not confirm.
 */
import React from "react";
import {ScrollView, StyleSheet, Text, View, useWindowDimensions} from "react-native";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import {Icon} from "../components";
import {PressableScale} from "../PressableScale";
import {MAX_CONTENT_WIDTH, spacing, typeScale} from "../tokens";
import {useTheme} from "../theme";
import type {WaniKaniHubState} from "./review-store";
import {deriveWaniKaniHub, type HubModuleModel} from "./wanikani-hub-model";
import {SectionCaption, StudyRow, SuccessCheck} from "./review-ui";

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
  const dark = Number.parseInt(colors.background.slice(1, 3), 16) < 128;
  const insets = useSafeAreaInsets();
  const {width, fontScale} = useWindowDimensions();
  const inset = width < 360 ? spacing.l : spacing.contentInset;
  // Narrow screens and large type get a taller floor for the row: its lines wrap instead of clipping.
  const stacked = width < 340 || fontScale >= 1.5;
  const [reviews, lessons] = deriveWaniKaniHub(hub);
  const allClear = reviews.complete && lessons.complete;

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

  const rowFor = (model: HubModuleModel) => (
    <StudyRow
      art={<Icon name={model.icon} size={24} color={colors.accent} />}
      holderSurface
      title={model.title}
      titleSize={17}
      subline={model.subline}
      extra={model.extra}
      trailing={model.trailing}
      complete={model.complete}
      minHeight={stacked ? 118 : 110}
      accessibilityLabel={rowLabel(model)}
      disabled={busy}
      onPress={pressFor(model)}
    />
  );

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
          </View>
          <Text accessibilityRole="header" style={[typeScale.headlineSmall, {color: colors.onSurface}]}>
            Your study
          </Text>
          <Text style={[styles.subtitle, {color: colors.onSurfaceVariant}]}>
            {allClear ? "Everything available is clear for now." : "Choose what to work on next."}
          </Text>
          {allClear && (
            <View
              style={[
                styles.caughtUp,
                {
                  backgroundColor: dark ? "#18291F" : "#ECF8F0",
                  borderColor: dark ? "#294734" : "#D8ECDC",
                },
              ]}
            >
              <SuccessCheck />
              <View style={styles.caughtUpCopy}>
                <Text style={[styles.caughtUpTitle, {color: colors.onSurface}]}>
                  You’re all caught up
                </Text>
                <Text style={[styles.caughtUpBody, {color: colors.onSurfaceVariant}]}>
                  No reviews or new lessons are waiting.
                </Text>
              </View>
            </View>
          )}
          <SectionCaption>Available study</SectionCaption>
          {sectionFor(reviews)}
          {sectionFor(lessons)}
          {sharedCaveat != null && (
            <Text style={[styles.caveat, {color: colors.onSurfaceVariant}]}>{sharedCaveat}</Text>
          )}
        </View>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {flex: 1},
  body: {flexGrow: 1},
  content: {width: "100%", maxWidth: MAX_CONTENT_WIDTH, alignSelf: "center"},
  top: {flexDirection: "row", alignItems: "center", minHeight: 48, marginBottom: 29},
  back: {width: 48, height: 48, alignItems: "flex-start", justifyContent: "center"},
  topTitle: {...typeScale.titleLarge, flexShrink: 1},
  subtitle: {fontFamily: "Inter", fontSize: 12, lineHeight: 17, marginTop: spacing.s},
  caughtUp: {
    flexDirection: "row",
    alignItems: "center",
    gap: spacing.m,
    borderRadius: 13,
    borderWidth: 1,
    padding: spacing.m,
    marginTop: 22,
  },
  caughtUpCopy: {flex: 1, minWidth: 0},
  caughtUpTitle: {fontFamily: "Inter", fontSize: 13, lineHeight: 18, fontWeight: "700"},
  caughtUpBody: {fontFamily: "Inter", fontSize: 11, lineHeight: 15, marginTop: 2},
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
