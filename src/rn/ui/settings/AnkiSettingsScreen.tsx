/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Edits a local draft and saves it only on Save. Collection/field reads happen through the
 * bridge; selecting an existing note type never changes its templates or any notes in AnkiDroid.
 *
 * Save is offered only when there is something to save and what there is would pass validation, so
 * the button and the reason it cannot be pressed never disagree: the moment the draft turns dirty
 * and invalid, the reason is on the page below it rather than behind a toast that had to be
 * provoked.
 */
import React, {useEffect, useRef, useState} from "react";
import {
  BackHandler,
  KeyboardAvoidingView,
  Modal,
  PermissionsAndroid,
  Pressable,
  ScrollView,
  Text,
  View,
  useWindowDimensions,
  type GestureResponderEvent,
  type Permission,
} from "react-native";
import * as GatenAnki from "../../native/GatenAnki";
import type {
  AnkiDeckResult,
  AnkiPreferencesResult,
  AnkiSettingsError,
  AnkiStatus,
} from "../../native/GatenAnki";
import * as GatenPlatform from "../../native/GatenPlatform";
import {Button, Icon, IconButton} from "../components";
import {PressableScale} from "../PressableScale";
import {reviewStore} from "../review/review-store";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import {useTheme} from "../theme";
import {spacing, typeScale} from "../tokens";
import {
  DialogShell,
  CONTENT_COLUMN,
  FieldLabel,
  HelpAction,
  SettingsGroup,
  SettingsTile,
  SwitchRow,
  TextField,
} from "./settings-components";
import {
  ANKI_FIELD_LABELS,
  VALIDATION_MESSAGES,
  draftsEqual,
  validationError,
  type AnkiField,
} from "./anki-validation";

const HELP_PARAGRAPHS = [
  "Without AnkiDroid access, Add to Anki is unavailable. Grant access here to add cards.",
  "Choose a deck or enter a new name. New decks are created on export.",
  "Separate tags with spaces. Leave blank for none.",
  "Map Expression to at least one field. Duplicates check the first mapped field across all decks for this note type. Select a note type again to reload its fields.",
  "AnkiDroid note types keep their own templates and styling. Cloze note types are not supported.",
  "Use {{Expression}}, {{Reading}}, and {{Meaning}} in HTML. {{FrontSide}} repeats the front on the back; conditional sections such as {{#Reading}}…{{/Reading}} are supported. {{Meaning}} includes glosses and part of speech. Layout edits affect future exports only; existing cards keep their note type.",
];

/** The AnkiDroid permission the database reads and writes live behind. */
const ANKI_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE";

export function AnkiSettingsScreen({
  onClose,
  isCurrentDestination,
}: {
  onClose: () => void;
  isCurrentDestination: boolean;
}) {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  const {width} = useWindowDimensions();
  const horizontalInset = width < 360 ? 16 : 22;
  const [draft, setDraft] = useState<AnkiPreferencesResult | null>(null);
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [status, setStatus] = useState<AnkiStatus>("notInstalled");
  const [collection, setCollection] = useState<GatenAnki.AnkiCollectionResult | null>(null);
  const [studyDecks, setStudyDecks] = useState<AnkiDeckResult[]>([]);
  const [studyDeckIds, setStudyDeckIds] = useState<number[] | null>(null);
  const [preferencesRefresh, setPreferencesRefresh] = useState(0);
  const [preferencesLoading, setPreferencesLoading] = useState(true);
  const [preferencesLoadFailed, setPreferencesLoadFailed] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const [loading, setLoading] = useState(false);
  const [loadingFields, setLoadingFields] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveFailed, setSaveFailed] = useState(false);
  const [saveValidationError, setSaveValidationError] = useState<AnkiSettingsError | null>(null);
  const [readFailed, setReadFailed] = useState(false);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  // What is on disk, to tell an edited draft from an untouched one.
  const savedRef = useRef<AnkiPreferencesResult | null>(null);
  const savedStudyDeckIdsRef = useRef<number[] | null>(null);

  // The saved configuration arrives once, and the draft is seeded from it: the page never argues
  // with a form it has not read.
  useEffect(() => {
    let cancelled = false;
    setPreferencesLoading(true);
    setPreferencesLoadFailed(false);
    savedRef.current = null;
    savedStudyDeckIdsRef.current = null;
    setDraft(null);
    setStudyDeckIds(null);
    void Promise.all([GatenAnki.getPreferences(), GatenAnki.getStudyDeckIds()]).then(
      ([saved, savedDeckIds]) => {
        if (cancelled) return;
        savedRef.current = saved;
        savedStudyDeckIdsRef.current = normalizeDeckIds(savedDeckIds);
        setStudyDeckIds(normalizeDeckIds(savedDeckIds));
        setDraft(saved);
      },
      () => {
        if (!cancelled) setPreferencesLoadFailed(true);
      }
    ).finally(() => {
      if (!cancelled) setPreferencesLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, [preferencesRefresh]);

  useEffect(() => {
    let cancelled = false;
    void GatenAnki.status().then((v) => {
      if (!cancelled) setStatus(v);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  // An outgoing entry may still be composed while the next page is arriving. Re-registered on
  // every render, so the guard always answers with this render's `dirty`.
  useEffect(() => {
    const subscription = BackHandler.addEventListener("hardwareBackPress", () => {
      if (dirty && isCurrentDestination) {
        leave();
        return true;
      }
      return false;
    });
    return () => subscription.remove();
  });

  useEffect(() => {
    let cancelled = false;
    setReadFailed(false);
    setLoading(false);
    if (status === "ready") {
      setLoading(true);
      void Promise.all([GatenAnki.collection(), GatenAnki.decks()]).then(
        ([result, decks]) => {
          if (cancelled) return;
          setCollection(result);
          setStudyDecks(decks);
          setLoading(false);
        },
        () => {
          if (cancelled) return;
          setReadFailed(true);
          setLoading(false);
        }
      );
    } else {
      setCollection(null);
      setStudyDecks([]);
    }
    return () => {
      cancelled = true;
    };
  }, [status, refresh]);

  const saved = savedRef.current;
  const draftDirty = draft != null && saved != null && !draftsEqual(draft, saved);
  const studyDecksDirty =
    studyDeckIds != null &&
    savedStudyDeckIdsRef.current != null
      ? !sameDeckIds(studyDeckIds, savedStudyDeckIdsRef.current)
      : studyDeckIds !== savedStudyDeckIdsRef.current;
  const dirty = draftDirty || studyDecksDirty;
  // Shown beside the disabled Save, and only for a draft the reader has actually changed: an
  // untouched configuration is never argued with.
  const error = draftDirty && draft != null ? validationError(draft) : null;

  /** Leaves, asking first only when the draft differs from what is saved. */
  const leave = () => {
    if (dirty) setConfirmDiscard(true);
    else onClose();
  };

  if (draft == null || saved == null) {
    return (
      <View style={{flex: 1, backgroundColor: colors.background, padding: spacing.xl, justifyContent: "center"}}>
        <View style={{alignSelf: "flex-start", position: "absolute", top: insets.top + spacing.s, left: spacing.l}}>
          <IconButton name="arrow_back" accessibilityLabel="Back" onPress={onClose} color={colors.onSurface} />
        </View>
        <Text style={[typeScale.titleMedium, {color: colors.onSurface}]}>
          {preferencesLoadFailed ? "Could not read Anki settings" : "Reading Anki settings…"}
        </Text>
        {preferencesLoadFailed && (
          <View style={{alignSelf: "flex-start", marginTop: spacing.m}}>
            <Button
              label="Retry"
              onPress={() => setPreferencesRefresh((value) => value + 1)}
              disabled={preferencesLoading}
            />
          </View>
        )}
      </View>
    );
  }

  const save = () => {
    setSaving(true);
    setSaveFailed(false);
    setSaveValidationError(null);
    void (async () => {
      try {
        if (draftDirty) {
          const nativeError = await GatenAnki.savePreferences({...draft, validationError: error});
          if (nativeError != null) {
            setSaveValidationError(nativeError);
            return;
          }
          savedRef.current = draft;
        }
        if (studyDecksDirty) {
          await GatenAnki.saveStudyDeckIds(studyDeckIds);
          savedStudyDeckIdsRef.current = normalizeDeckIds(studyDeckIds);
          reviewStore.studyDeckSelectionChanged(savedStudyDeckIdsRef.current);
        }
        void GatenPlatform.toast("Anki settings saved");
        onClose();
      } catch {
        setSaveFailed(true);
      } finally {
        setSaving(false);
      }
    })();
  };

  const toggleStudyDeck = (deckId: number, selected: boolean) => {
    setStudyDeckIds((currentIds) => {
      const current = currentIds ?? studyDecks.map((deck) => deck.id);
      const next = selected ? [...current, deckId] : current.filter((id) => id !== deckId);
      return [...new Set(next)].sort((left, right) => left - right);
    });
  };

  const requestPermission = async () => {
    const permission = ANKI_PERMISSION as Permission;
    const granted = await PermissionsAndroid.request(permission);
    const nextStatus = await GatenAnki.status();
    setStatus(nextStatus);
    setRefresh((n) => n + 1);
    if (granted !== PermissionsAndroid.RESULTS.GRANTED) {
      void GatenPlatform.toast("AnkiDroid did not allow writing to its database");
    }
  };

  const selectModel = (modelId: number | null) => {
    if (loadingFields) return;
    if (modelId == null) {
      setDraft({...draft, modelId: null, modelFields: [], fieldSources: []});
      return;
    }
    const models = collection?.models ?? {};
    if (models[String(modelId)] == null) return;
    setLoadingFields(true);
    void GatenAnki.modelFields(modelId).then(
      (fields) => {
        // The previous mapping, by field name, is reused when it already named this model's
        // fields; the rest fall back to the field whose name matches a source, else EMPTY.
        const previousByName = new Map<string, string>();
        if (draft.modelId === modelId) {
          draft.modelFields.forEach((name, index) =>
            previousByName.set(name, draft.fieldSources[index] ?? "EMPTY")
          );
        }
        setDraft({
          ...draft,
          modelId,
          modelFields: fields,
          fieldSources: fields.map((name) => {
            const previousSource = previousByName.get(name);
            if (previousSource != null) return previousSource;
            const matched = (Object.keys(ANKI_FIELD_LABELS) as AnkiField[]).find(
              (field) =>
                field !== "EMPTY" && field.toLowerCase() === name.toLowerCase()
            );
            return matched ?? "EMPTY";
          }),
        });
        setLoadingFields(false);
      },
      () => {
        void GatenPlatform.toast("Could not read AnkiDroid. Open AnkiDroid, then try Refresh.");
        setLoadingFields(false);
      }
    );
  };

  // The keyboard is turned into layout here, as it is on the search screen: from targetSdk 35 on,
  // the window is displayed edge-to-edge and is not resized for the IME (measured — the app's root
  // view still spans the full height with the keyboard up), so `adjustResize` alone would leave the
  // deck, tag and template fields under it. `padding` rather than `height`, because the scroller
  // below is `flex: 1`: padding shrinks its box without a second measurement. No
  // `keyboardVerticalOffset`: this view is the scene's top child, so its frame is already the
  // rectangle the calculation expects.
  return (
    <KeyboardAvoidingView behavior="padding" style={{flex: 1, backgroundColor: colors.background}}>
      <View
        style={{
          // 64 dp below the status bar, as the Kotlin's TopAppBar is — see SettingsPageScaffold.
          minHeight: 64 + insets.top,
          flexDirection: "row",
          alignItems: "center",
          paddingTop: insets.top,
        }}
      >
        <IconButton name="arrow_back" accessibilityLabel="Back" onPress={leave} color={colors.onSurface} />
        <Text style={[typeScale.titleLarge, {color: colors.onSurface, flex: 1}]} numberOfLines={1}>
          Anki cards
        </Text>
        <HelpAction paragraphs={HELP_PARAGRAPHS} />
        <Button
          variant="text"
          label="Save"
          disabled={!dirty || (draftDirty && error != null) || preferencesLoading || loading || loadingFields || saving}
          onPress={save}
        />
      </View>
      <ScrollView
        showsVerticalScrollIndicator={false}
        showsHorizontalScrollIndicator={false}
        // With the keyboard up, a tap on Save, Refresh or a row is the row's own: the keyboard is
        // dismissed by a tap that hits nothing, not by the first tap on a control.
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={{
          ...CONTENT_COLUMN,
          maxWidth: 560,
          paddingLeft: insets.left + horizontalInset,
          paddingRight: insets.right + horizontalInset,
          paddingTop: spacing.xl,
          paddingBottom: spacing.l + insets.bottom,
        }}
      >
        <SettingsGroup title="AnkiDroid" style={{marginTop: spacing.s}}>
          <View style={{paddingVertical: spacing.s}}>
            {status === "notInstalled" && (
              <Text style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant}]}>
                Install AnkiDroid to add cards.
              </Text>
            )}
            {status === "needsPermission" && (
              <>
                <Text style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant}]}>
                  Allow access to choose decks and note types and add cards directly.
                </Text>
                <View style={{alignSelf: "flex-start"}}>
                  <Button label="Allow AnkiDroid access" onPress={() => void requestPermission()} />
                </View>
              </>
            )}
            {status === "ready" && (
              <View style={{alignSelf: "flex-start"}}>
                <Button
                  variant="text"
                  label="Refresh from AnkiDroid"
                  disabled={loading || loadingFields || saving || dirty || preferencesLoading}
                  onPress={() => {
                    setRefresh((n) => n + 1);
                    setPreferencesRefresh((value) => value + 1);
                  }}
                />
              </View>
            )}
            {(loading || loadingFields) && (
              <Text style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant}]}>
                Reading AnkiDroid…
              </Text>
            )}
            {readFailed && (
              <Text style={[typeScale.bodyMedium, {color: colors.error}]}>
                Could not read AnkiDroid. Open AnkiDroid, then try Refresh.
              </Text>
            )}
          </View>
        </SettingsGroup>

        <SettingsGroup title="Study decks" style={{marginTop: spacing.l}}>
          <View style={{paddingVertical: spacing.xs}}>
            {status !== "ready" ? (
              <Text style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant}]}>
                Allow AnkiDroid access to choose study decks.
              </Text>
            ) : loading ? (
              <Text style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant}]}>
                Reading AnkiDroid decks…
              </Text>
            ) : studyDecks.length === 0 ? (
              <Text style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant}]}>
                No decks are available.
              </Text>
            ) : (
              <>
                {studyDecks.map((deck, index) => (
                  <SettingsTile
                    key={deck.id}
                    style={index === studyDecks.length - 1 ? {borderBottomWidth: 0} : undefined}
                  >
                    <SwitchRow
                      icon="library_books"
                      title={deck.name}
                      value={studyDeckIds == null || studyDeckIds.includes(deck.id)}
                      onValueChange={(selected) => toggleStudyDeck(deck.id, selected)}
                      disabled={saving}
                    />
                  </SettingsTile>
                ))}
                <View style={{alignSelf: "flex-start"}}>
                  <Button
                    variant="text"
                    label="Include all decks"
                    disabled={saving}
                    onPress={() => setStudyDeckIds(null)}
                  />
                </View>
              </>
            )}
            {status === "ready" && studyDecks.length > 0 && (
              <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant, marginTop: spacing.xs}]}>
                For review; export deck is separate. Parent decks include subdecks.
              </Text>
            )}
          </View>
        </SettingsGroup>

        <SettingsGroup title="Export" style={{marginTop: spacing.l}}>
          <View style={{paddingVertical: spacing.xs}}>
            <FieldLabel label="Deck" />
            <TextField
              value={draft.deckName}
              onChangeText={(deckName) => setDraft({...draft, deckName})}
            />
            {(collection?.decks.length ?? 0) > 0 && (
              <View style={{marginTop: spacing.m}}>
                <ChoiceRow
                  label="Choose existing deck"
                  value={draft.deckName}
                  options={(collection?.decks ?? []).map((name) => ({id: name, name}))}
                  onSelect={(name) => setDraft({...draft, deckName: String(name)})}
                />
              </View>
            )}
          </View>
          <View style={{paddingVertical: spacing.xs}}>
            <FieldLabel label="Tags" />
            <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant, marginBottom: spacing.xs}]}>Separate with spaces.</Text>
            <TextField
              value={draft.tags}
              onChangeText={(tags) => setDraft({...draft, tags})}
            />
          </View>
        </SettingsGroup>

        <SettingsGroup title="Card layout" style={{marginTop: spacing.l}}>
          <View style={{paddingVertical: spacing.xs}}>
            <PressableScale
              accessibilityRole="button"
              accessibilityState={{expanded: showAdvanced}}
              onPress={() => setShowAdvanced(!showAdvanced)}
              style={{flexDirection: "row", alignItems: "center", minHeight: 48, paddingVertical: spacing.xs}}
            >
              <Text style={[typeScale.labelLarge, {color: colors.onSurface, flex: 1}]}>
                Edit front, back, and styling
              </Text>
              <Icon
                name={showAdvanced ? "keyboard_arrow_up" : "keyboard_arrow_down"}
                color={colors.onSurfaceVariant}
              />
            </PressableScale>
            {showAdvanced && (
              <View style={{marginTop: spacing.m}}>
                <ChoiceRow
                  label="Note type"
                  value={
                    draft.modelId == null
                      ? "Gaten cards (customizable)"
                      : collection?.models[String(draft.modelId)] ??
                        "Saved note type (unavailable)"
                  }
                  options={[
                    {id: null, name: "Gaten cards (customizable)"},
                    ...Object.entries(collection?.models ?? {}).map(([id, name]) => ({
                      id: Number(id),
                      name,
                    })),
                  ]}
                  enabled={!loadingFields}
                  onSelect={(modelId) => selectModel(modelId)}
                />
                {draft.modelId != null
                  ? draft.modelFields.map((name, index) => (
                      <View key={`${name}:${index}`} style={{marginTop: spacing.m}}>
                        <ChoiceRow
                          label={name}
                          value={
                            ANKI_FIELD_LABELS[
                              (draft.fieldSources[index] ?? "EMPTY") as AnkiField
                            ]
                          }
                          options={(Object.keys(ANKI_FIELD_LABELS) as AnkiField[]).map((field) => ({
                            id: field,
                            name: ANKI_FIELD_LABELS[field],
                          }))}
                          enabled={!loadingFields}
                          onSelect={(selected) => {
                            const mapping = draft.modelFields.map((_, position) =>
                              position === index
                                ? String(selected)
                                : draft.fieldSources[position] ?? "EMPTY"
                            );
                            setDraft({...draft, fieldSources: mapping});
                          }}
                        />
                      </View>
                    ))
                  : (
                    <>
                      <TemplateEditor
                        label="Front (HTML)"
                        value={draft.front}
                        onChange={(front) => setDraft({...draft, front})}
                      />
                      <TemplateEditor
                        label="Back (HTML)"
                        value={draft.back}
                        onChange={(back) => setDraft({...draft, back})}
                      />
                      <TemplateEditor
                        label="Styling (CSS)"
                        value={draft.css}
                        onChange={(css) => setDraft({...draft, css})}
                      />
                      <Button
                        variant="text"
                        label="Restore default layout"
                        style={{alignSelf: "flex-start"}}
                        onPress={() =>
                          setDraft({
                            ...draft,
                            front: "{{Expression}}",
                            back: "{{FrontSide}}<hr id=answer>{{Reading}}<br>{{Meaning}}",
                            css:
                              ".card { font-family: sans-serif; font-size: 24px; text-align: center; }\nsmall { font-size: 16px; }",
                          })
                        }
                      />
                    </>
                  )}
              </View>
            )}
          </View>
        </SettingsGroup>

        {error != null && (
          <Text style={[typeScale.bodyMedium, {color: colors.error, marginTop: spacing.l}]}>
            {VALIDATION_MESSAGES[error]}
          </Text>
        )}
        {saveValidationError != null && (
          <Text style={[typeScale.bodyMedium, {color: colors.error, marginTop: spacing.l}]}>
            {VALIDATION_MESSAGES[saveValidationError]}
          </Text>
        )}
        {saveFailed && (
          <Text style={[typeScale.bodyMedium, {color: colors.error, marginTop: spacing.l}]}>
            Could not save Anki settings. Your changes are still here; try Save again.
          </Text>
        )}
      </ScrollView>

      {confirmDiscard && (
        <DiscardDialog
          onDiscard={() => {
            setConfirmDiscard(false);
            onClose();
          }}
          onStay={() => setConfirmDiscard(false)}
        />
      )}
    </KeyboardAvoidingView>
  );
}

/** One field or section of the form: what fills it, and the way to change it. */
function ChoiceRow<T extends string | number | null>({
  label,
  value,
  options,
  enabled = true,
  onSelect,
}: {
  label: string;
  value: string;
  options: {id: T; name: string}[];
  enabled?: boolean;
  onSelect: (id: T) => void;
}) {
  const {colors} = useTheme();
  const [open, setOpen] = useState(false);
  return (
    <View>
      <FieldLabel label={label} />
      <PressableScale
        accessibilityRole="button"
        accessibilityLabel={`${label}: ${value}`}
        accessibilityState={{expanded: open}}
        onPress={() => setOpen(true)}
        disabled={!enabled}
        style={{
          borderWidth: 1,
          borderColor: colors.outlineVariant,
          borderRadius: 14,
          minHeight: 48,
          paddingVertical: spacing.s,
          paddingHorizontal: spacing.l,
          flexDirection: "row",
          alignItems: "center",
          justifyContent: "space-between",
        }}
      >
        <Text style={[typeScale.bodyLarge, {color: colors.onSurface, flex: 1, flexShrink: 1}]}>
          {value}
        </Text>
        <Icon name="arrow_drop_down" color={colors.onSurfaceVariant} />
      </PressableScale>
      {open && (
        <ChoiceDialog
          options={options}
          onSelect={(id) => {
            setOpen(false);
            onSelect(id);
          }}
          onDismiss={() => setOpen(false)}
        />
      )}
    </View>
  );
}

/** The picker's own dialog: one row per option, on the surface the other dialogs use. */
function ChoiceDialog<T extends string | number | null>({
  options,
  onSelect,
  onDismiss,
}: {
  options: {id: T; name: string}[];
  onSelect: (id: T) => void;
  onDismiss: () => void;
}) {
  const {colors} = useTheme();
  return (
    <Modal transparent animationType="fade" onRequestClose={onDismiss}>
      <Pressable
        style={{
          flex: 1,
          backgroundColor: "rgba(0,0,0,0.5)",
          alignItems: "center",
          justifyContent: "center",
          padding: spacing.xl,
        }}
        onPress={onDismiss}
      >
        <Pressable
          style={{
            backgroundColor: colors.surface,
            borderRadius: 20,
            paddingVertical: spacing.s,
            width: "100%",
            maxWidth: 360,
            maxHeight: 480,
          }}
          onPress={(event: GestureResponderEvent) => event.stopPropagation()}
        >
          <ScrollView showsVerticalScrollIndicator={false} showsHorizontalScrollIndicator={false}>
            {options.map((option, index) => (
              <PressableScale
                key={`${option.name}:${index}`}
                accessibilityRole="button"
                onPress={() => onSelect(option.id)}
                style={{
                  minHeight: 48,
                  paddingHorizontal: spacing.xl,
                  paddingVertical: spacing.s,
                  flexDirection: "row",
                  alignItems: "center",
                  borderBottomWidth: index < options.length - 1 ? 1 : 0,
                  borderBottomColor: colors.outlineVariant,
                }}
              >
                <Text style={[typeScale.titleMedium, {color: colors.onSurface}]}>{option.name}</Text>
              </PressableScale>
            ))}
          </ScrollView>
        </Pressable>
      </Pressable>
    </Modal>
  );
}

function normalizeDeckIds(ids: number[] | null): number[] | null {
  return ids == null ? null : [...new Set(ids.filter((id) => Number.isFinite(id) && id > 0))]
    .sort((left, right) => left - right);
}

function sameDeckIds(left: number[], right: number[]): boolean {
  return left.length === right.length && left.every((id, index) => id === right[index]);
}

/** One monospace template editor, three lines tall and eight at most. */
function TemplateEditor({
  label,
  value,
  onChange,
}: {
  label: string;
  value: string;
  onChange: (next: string) => void;
}) {
  return (
    <View style={{marginBottom: spacing.m}}>
      <FieldLabel label={label} />
      <TextField
        value={value}
        onChangeText={onChange}
        multiline
        style={{
          minHeight: 60,
          maxHeight: 120,
          textAlignVertical: "top",
          fontFamily: "monospace",
        }}
      />
    </View>
  );
}

/** The draft is lost on leaving, so an edited one asks first. */
function DiscardDialog({
  onDiscard,
  onStay,
}: {
  onDiscard: () => void;
  onStay: () => void;
}) {
  const {colors} = useTheme();
  return (
    <DialogShell
      title="Discard changes?"
      onDismiss={onStay}
      actions={
        <>
          <Button variant="text" label="Discard" onPress={onDiscard} />
          <Button variant="text" label="Keep editing" onPress={onStay} />
        </>
      }
    >
      <Text style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant}]}>
        Your deck visibility, export destination, tags, field mapping and template edits are not saved yet.
      </Text>
    </DialogShell>
  );
}
