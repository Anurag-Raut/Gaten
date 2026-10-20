/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The names the AppShell wires for the screens that live in `ui/search`: the word detail, and the
 * WaniKani kanji and radical reference pages the word opens. The implementations are in their own
 * files; there is no Search tab any more, and Home shows the search inline.
 */
export {SearchWordScreen, type SearchWordScreenProps} from "./search/WordDetailScreen";
export {SearchKanjiScreen, type SearchKanjiScreenProps} from "./search/ReferenceScreen";
export {SearchRadicalScreen, type SearchRadicalScreenProps} from "./search/ReferenceScreen";
