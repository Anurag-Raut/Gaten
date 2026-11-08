/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The proactive support popup. It draws the same sheet Settings opens, and adds exactly two
 * dismissals: "Not now" postpones it, "Don't show again" is the one thing that silences it for good.
 * The backdrop and Android Back are the app's standard dismissal and behave as "Not now", never as
 * the permanent answer — nothing but the explicit button may silence the popup.
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
        setVisible(onboardingStep >= ONBOARDING_DONE_STEP && isEligible(state));
      })
      .catch(() => {
        if (seq === readSeq.current) {
          setVisible(false);
        }
      });
    return () => {
      readSeq.current += 1;
    };
  }, []);

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

export function SupportPrompt() {
  const {visible, dismissTemporarily, dismissPermanently, openLink} = useSupportPrompt();
  if (!visible) {
    return null;
  }
  return (
    <SupportSheet
      onDismiss={dismissTemporarily}
      onOpenLink={openLink}
      dismissalActions={
        <>
          <Button label="Not now" variant="text" onPress={dismissTemporarily} />
          <Button label="Don’t show again" variant="text" onPress={dismissPermanently} />
        </>
      }
    />
  );
}
