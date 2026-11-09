/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The proactive support popup. It draws the same sheet Settings opens, with softer, thanks-first
 * wording, and adds exactly two dismissals: "Maybe later" hides it until the next launch, "Don't ask
 * again" is the one thing that silences it for good. The backdrop and Android Back are the app's
 * standard dismissal and behave as "Maybe later", never as the permanent answer.
 *
 * It is offered at most once per launch, and never as the first thing a reader sees: on a cold
 * launch it waits until they have settled on Home, and when they come back to Home from elsewhere
 * (typically after a review or lesson session, a good moment) it appears after a short beat.
 */
import React, {useCallback, useEffect, useRef, useState} from "react";
import * as GatenAppInfo from "../../native/GatenAppInfo";
import {Button} from "../components";
import {openSupportLink} from "./support-links";
import {SupportSheet} from "./SupportSheet";

/** How many app opens pass before the popup is offered: enough to have used the app, not one look. */
const MIN_APP_OPENS = 4;

/** The first-run step that means setup is finished; the popup is never offered before it. */
const ONBOARDING_DONE_STEP = 3;

/** How long a reader must stay on Home, on the first visit of a launch, before the offer appears. */
const FIRST_VISIT_DELAY_MS = 15_000;

/** The beat before the offer on a return to Home, so it never lands on top of the navigation itself. */
const RETURN_VISIT_DELAY_MS = 1_200;

/** Whether the offer was already made this launch; "Maybe later" counts, so it asks once per launch. */
let offeredThisLaunch = false;

/** How many times Home has come into focus this launch; the first visit is the cold-launch one. */
let homeVisitsThisLaunch = 0;

/**
 * The count is raised once per launch, not once per mount: Home can remount inside one launch and
 * must not inflate it. It lives here rather than in the hook's state so the guard survives a
 * remount, which is exactly what it is for.
 */
let countedThisLaunch = false;

/** Whether the stored state says the popup may be offered now. */
function isEligible(state: GatenAppInfo.SupportPromptState): boolean {
  return !state.never && state.openCount >= MIN_APP_OPENS;
}

/**
 * Owns the popup's eligibility: it raises the app-open count on first use in a launch, reads the
 * stored state and the first-run step, and answers whether to show. The read is sequence-guarded the
 * way the lookup page's reads are, so a read that started before a dismissal cannot put the popup
 * back on screen. A reader still in the setup flow is never offered it: the shell's tabs mount under
 * that overlay, so a dialog here would rise above it.
 */
export function useSupportPrompt(): {
  visible: boolean;
  dismissTemporarily: () => void;
  dismissPermanently: () => void;
  openLink: (url: string) => void;
} {
  const [visible, setVisible] = useState(false);
  const readSeq = useRef(0);

  useEffect(() => {
    if (offeredThisLaunch) {
      return undefined;
    }
    homeVisitsThisLaunch += 1;
    const delay = homeVisitsThisLaunch === 1 ? FIRST_VISIT_DELAY_MS : RETURN_VISIT_DELAY_MS;
    let timer: ReturnType<typeof setTimeout> | undefined;
    readSeq.current += 1;
    const seq = readSeq.current;
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
        // Leaving Home before the delay ends clears this timer, so the offer only ever lands while
        // the reader is actually looking at Home.
        timer = setTimeout(() => {
          if (seq === readSeq.current && !offeredThisLaunch) {
            offeredThisLaunch = true;
            setVisible(true);
          }
        }, delay);
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

  /** Hides it for the rest of this launch; the once-per-launch flag is already set, nothing is stored. */
  const dismissTemporarily = useCallback(() => {
    readSeq.current += 1;
    setVisible(false);
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
