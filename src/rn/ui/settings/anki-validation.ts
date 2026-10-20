/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The draft validation the AnkiDroid export applies, ported from `AnkiSettings.validationError` so
 * the customise screen can offer Save only when what there is would pass — and say why it would
 * not, in the reader's own sight.
 *
 * AnkiDroid joins the fields of a note with the unit separator, so no field value, field name or
 * persisted field list can carry it; templates carry the same restriction, and a broken section
 * renders nothing, so it is refused while it is still being edited.
 */
import type {AnkiSettingsError} from "../../native/GatenAnki";

/** The Gaten value that fills one field of an Anki note type, as persisted. */
export type AnkiField = "EXPRESSION" | "READING" | "MEANING" | "EMPTY";

/** The fields Gaten's own note type is created with, in Anki's order. */
export const NOTE_FIELDS = ["Expression", "Reading", "Meaning"];

/** Anki's name for the question side of a card, usable on the answer side only. */
const FRONT_SIDE = "FrontSide";

/** The label each source shows in the mapping dropdowns, verbatim from strings_anki.xml. */
export const ANKI_FIELD_LABELS: Record<AnkiField, string> = {
  EXPRESSION: "Expression",
  READING: "Reading",
  MEANING: "Meaning and part of speech",
  EMPTY: "Leave empty",
};

/** Limits the customise screen tells the user about; Anki itself is far more permissive. */
const MAX_DECK_NAME = 200;
const MAX_TAGS = 1000;
const MAX_TEMPLATE = 32000;

/** One `{{...}}` substitution body; braces Anki uses otherwise are unsupported syntax. */
const MARKER = /\{\{([^{}]*)\}\}/g;

/** Character.isISOControl: the C0 controls, plus DEL and the C1 range. */
function isIsoControl(character: string): boolean {
  const code = character.codePointAt(0) ?? 0;
  return code < 0x20 || (code >= 0x7f && code <= 0x9f);
}

function validDeckName(deckName: string): boolean {
  const name = deckName.trim();
  if (name.length === 0 || name.length > MAX_DECK_NAME) return false;
  if ([...name].some(isIsoControl)) return false;
  // "Parent::Child" nesting: an empty section is not a deck AnkiDroid would create.
  return name.split("::").every((section) => section.trim().length > 0);
}

/** Tabs and newlines may separate tags; NUL, the field separator and every other control are refused. */
function validTags(tags: string): boolean {
  return (
    tags.length <= MAX_TAGS &&
    ![...tags].some((character) => isIsoControl(character) && !" \t\n\u000b\u000c\r".includes(character))
  );
}

function validTemplate(template: string, frontSideAllowed: boolean): boolean {
  if (template.trim().length === 0 || template.length > MAX_TEMPLATE) return false;
  if (template.includes("\u0000") || template.includes("\u001f")) return false;
  // What is left after the markers: an unclosed `{{`, a stray `}}`, `{{=<% %>=}}`.
  const leftovers = template.replace(MARKER, "");
  if (leftovers.includes("{{") || leftovers.includes("}}")) return false;
  return sectionsAreBalanced(template, frontSideAllowed);
}

/**
 * Walks the `{{...}}` bodies in order, one `{{#Field}}` or `{{^Field}}` per `{{/Field}}`, nested
 * and closed in order.
 */
function sectionsAreBalanced(template: string, frontSideAllowed: boolean): boolean {
  const open: string[] = [];
  for (const match of template.matchAll(MARKER)) {
    const body = (match[1] ?? "").trim();
    const section = body.startsWith("#") || body.startsWith("^") || body.startsWith("/");
    const name = section ? body.slice(1).trim() : body;
    if (name === FRONT_SIDE) {
      if (section || !frontSideAllowed) return false;
      continue;
    }
    if (!NOTE_FIELDS.includes(name)) return false;
    if (body.startsWith("/")) {
      if (open.pop() !== name) return false;
    } else if (section) {
      open.push(name);
    }
  }
  return open.length === 0;
}

/** CSS may be blank; it cannot carry the two characters a field-carrying string never may. */
function validCss(css: string): boolean {
  return (
    css.length <= MAX_TEMPLATE && !css.includes("\u0000") && !css.includes("\u001f")
  );
}

/** A existing note type needs one source per field it reports, the first filled, Expression mapped somewhere. */
function validMapping(modelFields: string[], fieldSources: string[]): boolean {
  return (
    modelFields.length > 0 &&
    fieldSources.length === modelFields.length &&
    fieldSources[0] !== "EMPTY" &&
    fieldSources.includes("EXPRESSION") &&
    modelFields.every((name) => name.trim().length > 0)
  );
}

/**
 * What makes this draft unusable for an add, or null when it is ready, in AnkiSettingsError order.
 * Templates are only checked while Gaten's own note type is used: a chosen note type renders its
 * own templates, which Gaten never touches.
 */
export function validationError(draft: {
  deckName: string;
  tags: string;
  modelId: number | null;
  modelFields: string[];
  fieldSources: string[];
  front: string;
  back: string;
  css: string;
}): AnkiSettingsError | null {
  if (!validDeckName(draft.deckName)) return "DECK";
  if (!validTags(draft.tags)) return "TAGS";
  if (draft.modelId != null) {
    return validMapping(draft.modelFields, draft.fieldSources) ? null : "MAPPING";
  }
  if (!validTemplate(draft.front, false)) return "FRONT";
  if (!validTemplate(draft.back, true)) return "BACK";
  if (!validCss(draft.css)) return "CSS";
  return null;
}

/** The message each error shows under the form, verbatim from strings_anki.xml. */
export const VALIDATION_MESSAGES: Record<AnkiSettingsError, string> = {
  DECK: "Enter a deck name of up to 200 characters, without empty :: sections or control characters.",
  TAGS: "Use tags without control characters (up to 1,000 characters in total).",
  FRONT: "Enter a valid front template using supported fields (up to 32,000 characters).",
  BACK: "Enter a valid back template using supported fields (up to 32,000 characters).",
  CSS: "Styling must be at most 32,000 characters and contain no null characters.",
  MAPPING: "Map Expression to a field and fill the first field. Reload the note type if its fields have changed.",
};

/** Structural equality of two drafts — the `dirty` comparison, field by field. */
export function draftsEqual(
  left: {
    deckName: string;
    tags: string;
    modelId: number | null;
    modelFields: string[];
    fieldSources: string[];
    front: string;
    back: string;
    css: string;
  },
  right: {
    deckName: string;
    tags: string;
    modelId: number | null;
    modelFields: string[];
    fieldSources: string[];
    front: string;
    back: string;
    css: string;
  }
): boolean {
  return (
    left.deckName === right.deckName &&
    left.tags === right.tags &&
    left.modelId === right.modelId &&
    left.front === right.front &&
    left.back === right.back &&
    left.css === right.css &&
    left.modelFields.length === right.modelFields.length &&
    left.fieldSources.length === right.fieldSources.length &&
    left.modelFields.every((name, index) => name === right.modelFields[index]) &&
    left.fieldSources.every((name, index) => name === right.fieldSources[index])
  );
}
