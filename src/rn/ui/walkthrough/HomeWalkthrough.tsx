/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Interactive, skippable post-onboarding walkthrough anchored to Home's real UI regions.
 *
 * Rendered as a single-window absolute overlay inside AppShell (matching OnboardingFlow) rather
 * than a separate Android Modal window, so step transitions stay flicker- and ghost-free and
 * accessibility/back-button behavior stays deterministic.
 */
import React, {useEffect, useState} from "react";
import {
  BackHandler,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
} from "react-native";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import Svg, {Path} from "react-native-svg";
import {Button, Icon, IconBadge} from "../components";
import {useTheme} from "../theme";
import {BOTTOM_BAR_HEIGHT, shape, spacing, typeScale} from "../tokens";
import {
  WALKTHROUGH_STEPS,
  clampStepIndex,
  nextWalkthroughStep,
  prevWalkthroughStep,
  walkthroughProgressLabel,
  type WalkthroughPointer,
  type WalkthroughStep,
} from "./walkthrough-steps";

const POINTER_WIDTH = 22;
const POINTER_HEIGHT = 11;

function PointerNotch({
  pointer,
  color,
}: {
  pointer: WalkthroughPointer;
  color: string;
}) {
  const pointingDown = pointer === "bottom-center";
  const alignSelf = pointer === "top-right" ? "flex-end" : "center";
  const marginRight = pointer === "top-right" ? spacing.xl : 0;

  return (
    <View
      accessible={false}
      importantForAccessibility="no-hide-descendants"
      style={{
        alignSelf,
        marginRight,
        marginBottom: pointingDown ? 0 : -1,
        marginTop: pointingDown ? -1 : 0,
      }}
    >
      <Svg width={POINTER_WIDTH} height={POINTER_HEIGHT} viewBox="0 0 22 11">
        <Path
          d={pointingDown ? "M0 0 L11 11 L22 0 Z" : "M0 11 L11 0 L22 11 Z"}
          fill={color}
        />
      </Svg>
    </View>
  );
}

export function HomeWalkthrough({
  visible,
  initialStep = 0,
  onComplete,
  onSkip,
  onOpenLookupSettings,
  onTrySampleSearch,
}: {
  visible: boolean;
  initialStep?: number;
  onComplete: () => void;
  onSkip: () => void;
  onOpenLookupSettings?: () => void;
  onTrySampleSearch?: () => void;
}) {
  const {colors} = useTheme();
  const {width, height} = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const [stepIndex, setStepIndex] = useState(() => clampStepIndex(initialStep));

  useEffect(() => {
    if (visible) {
      setStepIndex(clampStepIndex(initialStep));
    }
  }, [initialStep, visible]);

  const total = WALKTHROUGH_STEPS.length;
  const isFirst = stepIndex === 0;
  const isLast = stepIndex === total - 1;

  useEffect(() => {
    if (!visible) {
      return;
    }
    const sub = BackHandler.addEventListener("hardwareBackPress", () => {
      if (stepIndex === 0) {
        onSkip();
      } else {
        setStepIndex((idx) => prevWalkthroughStep(idx, total));
      }
      return true;
    });
    return () => sub.remove();
  }, [onSkip, stepIndex, total, visible]);

  if (!visible) {
    return null;
  }

  const step: WalkthroughStep = WALKTHROUGH_STEPS[clampStepIndex(stepIndex)];
  const progress = walkthroughProgressLabel(stepIndex, total);
  const narrow = width < 360;
  const horizontalInset = narrow ? spacing.l : spacing.xl;

  // Position the card near the Home feature it introduces without overlapping system insets.
  const justifyContent =
    step.anchor === "top"
      ? "flex-start"
      : step.anchor === "bottom"
        ? "flex-end"
        : "center";
  const anchorTopPadding =
    step.id === "lookup"
      ? insets.top + 76
      : step.id === "search"
        ? insets.top + 150
        : insets.top + spacing.xl;
  const anchorBottomPadding =
    step.anchor === "bottom"
      ? insets.bottom + BOTTOM_BAR_HEIGHT + spacing.l
      : insets.bottom + spacing.xl;

  const handleBack = () => {
    if (isFirst) {
      onSkip();
    } else {
      setStepIndex((idx) => prevWalkthroughStep(idx, total));
    }
  };

  const handleNext = () => {
    const next = nextWalkthroughStep(stepIndex, total);
    if (next == null) {
      onComplete();
    } else {
      setStepIndex(next);
    }
  };

  const handleStepAction = () => {
    if (step.id === "lookup" && onOpenLookupSettings) {
      onComplete();
      onOpenLookupSettings();
    } else if (step.id === "search" && onTrySampleSearch) {
      onComplete();
      onTrySampleSearch();
    }
  };

  const hasInteractiveAction =
    (step.id === "lookup" && onOpenLookupSettings != null) ||
    (step.id === "search" && onTrySampleSearch != null);

  return (
    <View
      style={[
        StyleSheet.absoluteFill,
        styles.backdrop,
        {
          justifyContent,
          paddingTop: anchorTopPadding,
          paddingBottom: anchorBottomPadding,
          paddingHorizontal: horizontalInset,
        },
      ]}
      pointerEvents="box-none"
    >
      <View
        style={StyleSheet.absoluteFill}
        pointerEvents="auto"
        accessible={false}
      />
      <View style={styles.cardWrap}>
        {step.pointer !== "bottom-center" && (
          <PointerNotch pointer={step.pointer} color={colors.surface} />
        )}

        <View
          style={[
            styles.card,
            {
              backgroundColor: colors.surface,
              borderColor: colors.outlineVariant,
              maxHeight: height - insets.top - insets.bottom - spacing.xxl * 2,
            },
          ]}
          accessibilityViewIsModal
          accessibilityLiveRegion="polite"
          accessibilityLabel={`Walkthrough step ${progress}. ${step.title}. ${step.body}`}
        >
          {/* Header row: badge, step progress pill, and Skip action always within reach. */}
          <View style={styles.headerRow}>
            <IconBadge
              name={step.icon}
              size={40}
              background={colors.accentSubtle}
              color={colors.accent}
            />
            <View style={styles.headerMeta}>
              <Text style={[typeScale.labelMedium, {color: colors.accent}]}>
                Quick tour · {progress}
              </Text>
            </View>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Skip walkthrough"
              hitSlop={8}
              onPress={onSkip}
              style={({pressed}) => [
                styles.skipTouch,
                {opacity: pressed ? 0.6 : 1},
              ]}
            >
              <Text style={[typeScale.labelLarge, {color: colors.onSurfaceVariant}]}>
                Skip
              </Text>
            </Pressable>
          </View>

          <ScrollView
            showsVerticalScrollIndicator={false}
            showsHorizontalScrollIndicator={false}
            style={styles.bodyScroll}
          >
            <Text style={[typeScale.titleLarge, {color: colors.onSurface}]}>
              {step.title}
            </Text>
            <Text
              style={[
                typeScale.bodyMedium,
                {color: colors.onSurfaceVariant, marginTop: spacing.s},
              ]}
            >
              {step.body}
            </Text>

            <View
              style={[
                styles.tipBox,
                {
                  backgroundColor: colors.surfaceContainerLow,
                  borderColor: colors.outlineVariant,
                },
              ]}
            >
              <Text style={[typeScale.bodySmall, {color: colors.onSurface}]}>
                {step.tip}
              </Text>
            </View>

            {hasInteractiveAction && step.actionLabel != null && (
              <View style={{marginTop: spacing.m}}>
                <Button
                  label={step.actionLabel}
                  variant="outlined"
                  fullWidth
                  leading={
                    <Icon
                      name={step.id === "search" ? "search" : "open_in_new"}
                      size={18}
                      color={colors.accent}
                    />
                  }
                  onPress={handleStepAction}
                />
              </View>
            )}
          </ScrollView>

          {/* Progress dots */}
          <View
            style={styles.dotsRow}
            accessible
            accessibilityLabel={`Step ${progress}`}
          >
            {WALKTHROUGH_STEPS.map((item, idx) => {
              const active = idx === stepIndex;
              return (
                <View
                  key={item.id}
                  style={[
                    styles.dot,
                    {
                      width: active ? 20 : 7,
                      backgroundColor: active ? colors.accent : colors.outlineVariant,
                    },
                  ]}
                />
              );
            })}
          </View>

          {/* Navigation controls */}
          <View style={styles.footerRow}>
            <View style={styles.footerLeft}>
              {!isFirst && (
                <Button label="Back" variant="text" onPress={handleBack} />
              )}
            </View>
            <Button
              label={isLast ? "Get started" : "Next"}
              variant="filled"
              onPress={handleNext}
            />
          </View>
        </View>

        {step.pointer === "bottom-center" && (
          <PointerNotch pointer={step.pointer} color={colors.surface} />
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    position: "absolute",
    top: 0,
    right: 0,
    bottom: 0,
    left: 0,
    backgroundColor: "rgba(0,0,0,0.52)",
    alignItems: "center",
    zIndex: 20,
    elevation: 20,
  },
  cardWrap: {
    width: "100%",
    maxWidth: 380,
  },
  card: {
    width: "100%",
    borderRadius: shape.medium,
    borderWidth: StyleSheet.hairlineWidth,
    padding: spacing.xl,
  },
  headerRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: spacing.m,
  },
  headerMeta: {
    flex: 1,
  },
  skipTouch: {
    minHeight: 40,
    minWidth: 48,
    alignItems: "flex-end",
    justifyContent: "center",
  },
  bodyScroll: {
    marginTop: spacing.m,
    flexShrink: 1,
  },
  tipBox: {
    marginTop: spacing.m,
    paddingHorizontal: spacing.m,
    paddingVertical: spacing.s,
    borderRadius: shape.small,
    borderWidth: StyleSheet.hairlineWidth,
  },
  dotsRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "center",
    gap: spacing.xs,
    marginTop: spacing.l,
  },
  dot: {
    height: 7,
    borderRadius: 4,
  },
  footerRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    marginTop: spacing.m,
    gap: spacing.s,
  },
  footerLeft: {
    flexDirection: "row",
    alignItems: "center",
  },
});
