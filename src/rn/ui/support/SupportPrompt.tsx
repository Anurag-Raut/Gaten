/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The proactive support popup. It draws the same sheet Settings opens, with softer, thanks-first
 * wording, and adds exactly two dismissals: "Maybe later" postpones it, "Don't ask again" is the one
 * thing that silences it for good. The backdrop and Android Back behave as "Maybe later".
 *
 * It is offered only at a good moment, never on arrival: right after the reader finishes a study
 * session (see support-moments), or when they are about to leave — Back at Home's root, the one
 * point where the app would close. At most once per launch, and "Maybe later" holds it back for a
 * week.
 */
import React, {useCallback, useEffect, useRef, useState} from "react";
import {BackHandler} from "react-native";
import * as GatenAppInfo from "../../native/GatenAppInfo";
import {Button} from "../components";
import {openSupportLink} from "./support-links";
import {consumeSupportMoment, hasSupportMoment} from "./support-moments";
import {SupportSheet} from "./SupportSheet";

/** How many app opens pass before the popup is offered: enough to have used the app, not one look. */
const MIN_APP_OPENS = 5;

/** The first-run step that means setup is finished; the popup is never offered before it. */
const ONBOARDING_DONE_STEP = 3;

/** The beat after a finished session before the offer, so it follows the result rather than covers it. */
const MOMENT_DELAY_MS = 1_500;

/** Whether the offer was already made this launch; "Maybe later" counts, so it asks once per launch. */
let offeredThisLaunch = false;

/**
 * The count is raised once per launch, not once per mount: Home can remount inside one launch and
 * must not inflate it. It lives here rather than in the hook's state so the guard survives a
 * remount, which is exactly what it is for.
 */
let countedThisLaunch = false;

/** Whether the stored state says the popup may be offered now. */
function isEligible(state: GatenAppInfo.SupportPromptState): boolean {
  return !state.never && state.openCount >= MIN_APP_OPENS && Date.now() >= state.nextEligibleAt;
}

/**
 * Owns the popup's eligibility: it raises the app-open count on first use in a launch, reads the
 * stored state and the first-run step, and decides when to show. The read is sequence-guarded so a
 * read that started before a dismissal cannot put the popup back, and the timer is cleared when Home
 * loses focus, so the offer only ever lands while Home is in front. A reader still in the setup flow
 * is never offered it: the shell's tabs mount under that overlay.
 */
export function useSupportPrompt(): {
  visible: boolean;
  dismissTemporarily: () => void;
  dismissPermanently: () => void;
  openLink: (url: string) => void;
} {
  const [visible, setVisible] = useState(false);
  const readSeq = useRef(0);
  /** Whether the stored state allows an offer at all; Back may spend it without a pending moment. */
  const eligible = useRef(false);

  useEffect(() => {
    if (offeredThisLaunch) {
      return undefined;
    }
    readSeq.current += 1;
    const seq = readSeq.current;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const counted = countedThisLaunch
      ? Promise.resolve()
      : GatenAppInfo.recordSupportAppOpen().then(() => {
          countedThisLaunch = true;
        });
    void counted
      .then(() =>
        Promise.all([
          GatenAppInfo.getSupportPromptState(),
          GatenAppInfo.getOnboardingStep(),
        ]),
      )
      .then(([state, onboardingStep]) => {
        if (seq !== readSeq.current) {
          return;
        }
        if (onboardingStep < ONBOARDING_DONE_STEP || !isEligible(state)) {
          return;
        }
        eligible.current = true;
        // Only a finished session schedules an offer on its own; Back asks for the rest.
        if (!hasSupportMoment()) {
          return;
        }
        timer = setTimeout(() => {
          if (seq !== readSeq.current || offeredThisLaunch) {
            return;
          }
          offeredThisLaunch = true;
          consumeSupportMoment();
          setVisible(true);
        }, MOMENT_DELAY_MS);
      })
      .catch(() => {
        if (seq === readSeq.current) {
          setVisible(false);
        }
      });
    return () => {
      readSeq.current += 1;
      if (timer != null) {
        clearTimeout(timer);
      }
    };
  }, []);

  /**
   * Back at Home's root is the point where the app would close, so that press is the last chance to
   * ask. This component only mounts while Home is in front, so the listener can never steal Back
   * from a page, a tab, or an open sheet; when the reader has already been asked this launch, or is
   * not eligible, it is left alone and the app closes as usual.
   */
  useEffect(() => {
    const subscription = BackHandler.addEventListener("hardwareBackPress", () => {
      if (offeredThisLaunch || !eligible.current) {
        return false;
      }
      offeredThisLaunch = true;
      consumeSupportMoment();
      setVisible(true);
      return true;
    });
    return () => subscription.remove();
  }, []);

  /** "Maybe later": hides it and starts the week-long wait on the native side. */
  const dismissTemporarily = useCallback(() => {
    readSeq.current += 1;
    setVisible(false);
    void GatenAppInfo.dismissSupportPrompt(false);
  }, []);

  const dismissPermanently = useCallback(() => {
    readSeq.current += 1;
    setVisible(false);
    void GatenAppInfo.dismissSupportPrompt(true);
  }, []);

  return {visible, dismissTemporarily, dismissPermanently, openLink: openSupportLink};
}

/** Thanks first, the ask second, and nothing that reads as an obligation. */
const POPUP_BODY =
  "Thanks for studying with Gaten. It’s built by one person, so a quick rating or a coffee goes a long way. No pressure at all.";

export function SupportPrompt() {
  const {visible, dismissTemporarily, dismissPermanently, openLink} = useSupportPrompt();
  if (!visible) {
    return null;
  }
  return (
    <SupportSheet
      title="Enjoying Gaten?"
      body={POPUP_BODY}
      onDismiss={dismissTemporarily}
      onOpenLink={openLink}
      dismissalActions={
        <>
          <Button label="Maybe later" variant="text" onPress={dismissTemporarily} />
          <Button label="Don’t ask again" variant="text" onPress={dismissPermanently} />
        </>
      }
    />
  );
}
