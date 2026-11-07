/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Getting AnkiDroid and WaniKani working, behind the hubs' quiet question mark: the provider's own
 * mark beside the title, exactly three numbered steps, and — in the block whose task it performs —
 * the control that does it.
 *
 * The help hands the reader off instead of describing the hand-off: the token page opens in the
 * browser, the token form opens where the screen that raised the help keeps it, the store listing
 * and AnkiDroid itself open as AnkiDroid's own screens, and the study decks open on the settings
 * page that chooses them. Nothing here documents a connected reader's routine; what they do next
 * belongs to the screens themselves.
 *
 * Opening the help requests nothing. AnkiDroid's installation, permission and collection come from
 * the shared deck read the review flow already owns (`useAnkiDecks`), reported as it stands — a read
 * that failed is said to have failed, never read as a provider that is not installed — and the
 * shared state is read again only where the reader's own control asks for it.
 *
 * The trigger and the dialog are separate pieces because one host needs them apart. The review flow
 * replaces the screen under the dialog while the reader is away — AnkiDroid's own installation and
 * setup happen outside Gaten, and the screen comes back re-read — and a dialog mounted inside that
 * screen would be unmounted with it. The review screen keeps the dialog above the step it draws, so
 * the reader returns to the block they left.
 */
import React, {useEffect, useState} from "react";
import {StyleSheet, Text, View} from "react-native";
import {Button, Icon, IconButton} from "../components";
import {DialogShell} from "../DialogShell";
import {PressableScale} from "../PressableScale";
import {getAnkiDecks, loadAnkiDecks, useAnkiDecksState} from "../anki/useAnkiDecks";
import * as GatenAnki from "../../native/GatenAnki";
import * as GatenPlatform from "../../native/GatenPlatform";
import {downloadAnkiDroid} from "../anki/anki-actions";
import {shape, spacing, typeScale} from "../tokens";
import {useTheme} from "../theme";
import {BrandTile} from "./review-ui";

/** Where a token is created; the same page the connect screen's own link opens. */
const WANIKANI_TOKEN_URL = "https://www.wanikani.com/settings/personal_access_tokens";

type Provider = "anki" | "wanikani";

/** How AnkiDroid stands, as the three steps read it. */
type AnkiRead = "checking" | "failed" | "notInstalled" | "needsPermission" | "ready";

/**
 * One numbered step: the marker and its heading on one row, then the copy and this step's own
 * control under them, at the dialog's left content edge — no indented text column, because at 320 dp
 * every paragraph needs the width the marker would take.
 */
function SetupBlock({
  number,
  title,
  children,
}: {
  number: number;
  title: string;
  children: React.ReactNode;
}) {
  const {colors} = useTheme();
  return (
    <View>
      <View style={styles.headingRow}>
        <View style={[styles.number, {borderColor: colors.outlineVariant}]}>
          <Text style={[typeScale.labelMedium, {color: colors.accent}]}>{number}</Text>
        </View>
        <Text style={[typeScale.titleMedium, {color: colors.onSurface, flex: 1, minWidth: 0}]}>
          {title}
        </Text>
      </View>
      <View style={styles.blockBody}>{children}</View>
    </View>
  );
}

/** The hairline between two steps, with the brief's 16 dp of air on either side. */
function StepDivider() {
  const {colors} = useTheme();
  return <View style={[styles.divider, {backgroundColor: colors.outlineVariant}]} />;
}

/** A check and the words for a state the reader has reached, never a control they could press. */
function ReachedState({label}: {label: string}) {
  const {colors} = useTheme();
  return (
    <View style={styles.status}>
      <Icon name="check" size={20} color={colors.accent} />
      <Text style={[typeScale.labelLarge, {color: colors.onSurface, flexShrink: 1}]}>{label}</Text>
    </View>
  );
}

/**
 * The token page as a link rather than as a sentence. It wears the outlined control's metrics — a
 * 52 dp target, the button radius, accent ink and a leading `open_in_new` — but it is a link and says
 * so, because the shared `Button` can only announce itself as a button. A page that does not open
 * says so underneath and offers its address instead, so a tap that did nothing is never the end of
 * the step.
 */
function TokenPageLink({
  label,
  accessibilityLabel,
  url,
}: {
  label: string;
  accessibilityLabel: string;
  url: string;
}) {
  const {colors} = useTheme();
  const [openFailed, setOpenFailed] = useState(false);
  const [copyFailed, setCopyFailed] = useState(false);

  const open = () => {
    setOpenFailed(false);
    setCopyFailed(false);
    void GatenPlatform.openUrl(url)
      .then((opened) => setOpenFailed(!opened))
      .catch(() => setOpenFailed(true));
  };

  const copy = () => {
    setCopyFailed(false);
    void GatenPlatform.copyToClipboard(url)
      .then((copied) => {
        if (copied) void GatenPlatform.toast("Link copied");
        else setCopyFailed(true);
      })
      .catch(() => setCopyFailed(true));
  };

  return (
    <View>
      <PressableScale
        accessibilityRole="link"
        accessibilityLabel={accessibilityLabel}
        onPress={open}
        style={[styles.link, {borderColor: colors.accent}]}
      >
        <Icon name="open_in_new" size={20} color={colors.accent} />
        <Text style={[typeScale.labelLarge, {color: colors.accent, flexShrink: 1}]}>{label}</Text>
      </PressableScale>
      {openFailed && (
        <Text
          accessibilityLiveRegion="polite"
          style={[styles.failure, {color: colors.error}]}
        >
          Couldn't open the page. Copy the link into your browser.
        </Text>
      )}
      {openFailed && <Button variant="text" label="Copy link" onPress={copy} />}
      {copyFailed && (
        <Text
          accessibilityLiveRegion="polite"
          style={[styles.failure, {color: colors.error}]}
        >
          Couldn't copy the link.
        </Text>
      )}
    </View>
  );
}

/**
 * WaniKani's three steps: open the site's token page, create a token with every permission selected,
 * and paste it in here. The permission step has no control of its own — no Gaten action can tick a
 * box on wanikani.com — so it states the instruction and nothing more.
 */
function WaniKaniSetup({onOpenTokenForm}: {onOpenTokenForm: () => void}) {
  const {colors} = useTheme();
  return (
    <>
      <SetupBlock number={1} title="Open WaniKani">
        <View style={styles.action}>
          <TokenPageLink
            label="Open token settings"
            accessibilityLabel="Open WaniKani token settings, opens browser"
            url={WANIKANI_TOKEN_URL}
          />
        </View>
      </SetupBlock>

      <StepDivider />

      <SetupBlock number={2} title="Select all permissions">
        <Text style={[styles.paragraph, {color: colors.onSurfaceVariant}]}>
          Create the token and copy it in full.
        </Text>
      </SetupBlock>

      <StepDivider />

      <SetupBlock number={3} title="Connect Gaten">
        <Text style={[styles.body, {color: colors.onSurfaceVariant}]}>
          Paste into ‘Personal access token’, then tap ‘Connect WaniKani’.
        </Text>
        <View style={styles.action}>
          <Button variant="tonal" fullWidth label="Open token form" onPress={onOpenTokenForm} />
        </View>
      </SetupBlock>
    </>
  );
}

/**
 * AnkiDroid's three steps, each with the control the state it reads calls for. All three stay
 * visible whatever is known, because the reader is reading the whole path — only the controls and
 * the copy that answers the known state change.
 *
 * `status()` alone cannot say whether the collection is set up, so the steps read the shared deck
 * state with it: NOT_INSTALLED and PERMISSION are the provider's own refusals, a list on screen (or a
 * provider that answered NOT_SET_UP) means the reader is past installing and allowing, and anything
 * else failed to read — which is said as a failed read, never as an absent AnkiDroid.
 */
function AnkiSetup({onOpenStudyDecks}: {onOpenStudyDecks: () => void}) {
  const {colors} = useTheme();
  const anki = useAnkiDecksState();
  const [openFailed, setOpenFailed] = useState(false);
  const [permissionMessage, setPermissionMessage] = useState<string | null>(null);

  // A store nothing has read yet is read once here, so the dialog is not blank on a screen that
  // never asked for decks. Every later read — including the resume the review flow does — is the
  // host's own, and a read cannot be doubled: the store joins one already in flight.
  useEffect(() => {
    if (getAnkiDecks().status === "idle") void loadAnkiDecks();
  }, []);

  const read: AnkiRead =
    anki.failure === "NOT_INSTALLED"
      ? "notInstalled"
      : anki.failure === "PERMISSION"
        ? "needsPermission"
        : anki.status === "unavailable" && anki.failure !== "NOT_SET_UP"
          ? "failed"
          : anki.decks != null || anki.failure === "NOT_SET_UP"
            ? "ready"
            : "checking";

  // The collection itself: read now, and holding at least one deck to study.
  const readable = anki.failure == null && anki.decks != null;
  const noDecks = anki.failure == null && anki.decks?.length === 0;

  const openTheApp = () => {
    setOpenFailed(false);
    void GatenAnki.openAnkiDroid()
      .then((opened) => {
        if (!opened) setOpenFailed(true);
      })
      .catch(() => setOpenFailed(true));
  };

  const allowAccess = () => {
    setPermissionMessage(null);
    void GatenAnki.requestPermission()
      .then((granted) => {
        if (granted) {
          // The shared deck state is what knows about the collection now; read it again rather than
          // keep a state of our own. Nothing is exported and no card is saved by granting.
          void loadAnkiDecks();
        } else {
          setPermissionMessage("Access wasn't allowed.");
        }
      })
      .catch(() => setPermissionMessage("Couldn't request access."));
  };

  const prepareBody = noDecks
    ? "Create or import a deck."
    : read === "needsPermission" || read === "ready"
      ? "Finish setup in Anki, then create or import a deck."
      : "Install Anki, then create or import a deck.";

  return (
    <>
      <SetupBlock number={1} title="Set up Anki">
        <Text style={[styles.body, {color: colors.onSurfaceVariant}]}>{prepareBody}</Text>
        {read === "notInstalled" && (
          <View style={styles.action}>
            <Button
              variant="outlined"
              fullWidth
              label="Download Anki"
              onPress={() => {
                // The store page reports its own failure with a toast; a resolved promise is not
                // proof that anything opened, so nothing here is claimed on the way back.
                void downloadAnkiDroid();
              }}
            />
          </View>
        )}
        {(read === "needsPermission" || read === "ready") && (
          <View style={styles.action}>
            <Button variant="outlined" fullWidth label="Open Anki" onPress={openTheApp} />
          </View>
        )}
        {read === "failed" && (
          <>
            <Text accessibilityLiveRegion="polite" style={[styles.failure, {color: colors.error}]}>
              Couldn't check Anki.
            </Text>
            <Button
              variant="text"
              label="Try again"
              onPress={() => {
                setOpenFailed(false);
                void loadAnkiDecks();
              }}
            />
          </>
        )}
        {openFailed && (
          <Text accessibilityLiveRegion="polite" style={[styles.failure, {color: colors.error}]}>
            Couldn't open Anki.
          </Text>
        )}
      </SetupBlock>

      <StepDivider />

      <SetupBlock number={2} title="Connect Gaten">
        <Text style={[styles.body, {color: colors.onSurfaceVariant}]}>
          Let Gaten read your collection and save cards.
        </Text>
        {read === "needsPermission" && (
          <View style={styles.action}>
            <Button variant="tonal" fullWidth label="Allow access" onPress={allowAccess} />
          </View>
        )}
        {read === "ready" && <ReachedState label="Access allowed" />}
        {(read === "failed" || read === "checking") && (
          <Text style={[styles.paragraph, {color: colors.onSurfaceVariant}]}>
            Complete step 1 first.
          </Text>
        )}
        {permissionMessage != null && (
          <Text accessibilityLiveRegion="polite" style={[styles.failure, {color: colors.error}]}>
            {permissionMessage}
          </Text>
        )}
      </SetupBlock>

      <StepDivider />

      <SetupBlock number={3} title="Study decks">
        <Text style={[styles.body, {color: colors.onSurfaceVariant}]}>
          Select decks, then tap ‘Save’.
        </Text>
        {readable && !noDecks && (
          <View style={styles.action}>
            <Button
              variant="tonal"
              fullWidth
              label="Choose study decks"
              onPress={onOpenStudyDecks}
            />
          </View>
        )}
        {noDecks && (
          <Text style={[styles.paragraph, {color: colors.onSurfaceVariant}]}>
            Add a deck in Anki first.
          </Text>
        )}
        {!readable && (
          <Text style={[styles.paragraph, {color: colors.onSurfaceVariant}]}>
            Complete steps 1–2 first.
          </Text>
        )}
      </SetupBlock>
    </>
  );
}

/** The hub headers' question mark: the same bare 48 dp help target the settings pages carry. */
export function ProviderSetupHelpTrigger({
  provider,
  onPress,
}: {
  provider: Provider;
  onPress: () => void;
}) {
  return (
    <IconButton
      name="help"
      accessibilityLabel={provider === "anki" ? "Anki setup help" : "WaniKani setup help"}
      onPress={onPress}
    />
  );
}

/**
 * The help itself, for a host that holds its visibility: the provider's mark beside the title, the
 * three steps with their own controls, and the pinned Close. Dismissal and the hand-off both live
 * here, because a hand-off belongs to this dialog to finish: it runs only once the help has closed,
 * so the reader never comes back to it over the screen it asked for.
 */
export function ProviderSetupDialog({
  provider,
  open,
  onDismiss,
  onOpenTokenForm,
  onOpenStudyDecks,
}: {
  provider: Provider;
  open: boolean;
  onDismiss: () => void;
  /** WaniKani: what "Open token form" means on the screen that raised the help. */
  onOpenTokenForm?: () => void;
  /** AnkiDroid: the study-deck settings page behind "Choose study decks". */
  onOpenStudyDecks?: () => void;
}) {
  if (!open) return null;
  const anki = provider === "anki";
  return (
    <DialogShell
      title={anki ? "Set up Anki" : "Set up WaniKani"}
      titleLeading={<BrandTile brand={provider} size={40} radius={shape.brandTile} />}
      onDismiss={onDismiss}
      scrollBody
      fitToViewport
      bodyGap={spacing.l}
      actions={<Button variant="text" label="Close" onPress={onDismiss} />}
    >
      {anki ? (
        <AnkiSetup
          onOpenStudyDecks={() => {
            onDismiss();
            onOpenStudyDecks?.();
          }}
        />
      ) : (
        <WaniKaniSetup
          onOpenTokenForm={() => {
            onDismiss();
            onOpenTokenForm?.();
          }}
        />
      )}
    </DialogShell>
  );
}

/**
 * The trigger and its dialog together, for the screens that raise the help and keep it themselves:
 * the hub that has not connected, and the connect screen the help hands the reader to.
 */
export function ProviderSetupHelp({
  provider,
  onOpenTokenForm,
  onOpenStudyDecks,
}: {
  provider: Provider;
  onOpenTokenForm?: () => void;
  onOpenStudyDecks?: () => void;
}) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <ProviderSetupHelpTrigger provider={provider} onPress={() => setOpen(true)} />
      <ProviderSetupDialog
        provider={provider}
        open={open}
        onDismiss={() => setOpen(false)}
        onOpenTokenForm={onOpenTokenForm}
        onOpenStudyDecks={onOpenStudyDecks}
      />
    </>
  );
}

const styles = StyleSheet.create({
  // The heading row, its copy and this step's control, one 8 dp step apart.
  headingRow: {flexDirection: "row", alignItems: "center", gap: spacing.s},
  // A circle at 24 dp, which may grow to hold a scaled digit rather than clip it.
  number: {
    minWidth: 24,
    minHeight: 24,
    borderRadius: 999,
    borderWidth: 1,
    alignItems: "center",
    justifyContent: "center",
    paddingHorizontal: spacing.xs,
  },
  blockBody: {marginTop: spacing.s},
  divider: {height: 1, marginVertical: spacing.l},
  body: {...typeScale.bodyMedium},
  paragraph: {...typeScale.bodyMedium, marginTop: spacing.s},
  action: {marginTop: spacing.s, gap: spacing.xs},
  status: {flexDirection: "row", alignItems: "center", gap: spacing.s, marginTop: spacing.s},
  failure: {...typeScale.bodySmall, marginTop: spacing.s},
  // The outlined control's own metrics, worn by a link: 52 dp, the button radius, accent ink.
  link: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "center",
    gap: spacing.s,
    minHeight: 52,
    borderRadius: shape.button,
    borderWidth: 1,
    paddingVertical: spacing.m,
    paddingHorizontal: spacing.xl,
    alignSelf: "stretch",
  },
});
