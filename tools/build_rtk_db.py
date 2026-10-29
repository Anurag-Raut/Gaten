#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Build the offline Remembering the Kanji (RTK) reference asset used by Gaten.

Downloads one frozen revision of Arthur944/remembering-the-kanji-stories `final.csv`, verifies its
SHA-256, and converts the semicolon-delimited rows into the compact SQLite database the app reads:
one row per kanji keyed by the character itself, with the RTK keyword, the Heisig story, the Heisig
primitive comment and the five Koohii community mnemonics (parsed into structured rows so the app
can show one story and expand the rest without shipping a parser to the device).

The asset is shipped uncompressed on purpose: AGP expands `.gz` assets during asset merge anyway, so
gzipping in the repo only hides which file the app must open at runtime. `RtkRepository` copies
`db/rtk.sqlite` verbatim and compares the SQLite header before trusting an extracted copy.

Two things this script deliberately does not do:

* **No frame numbers.** The source has no RTK frame column; the row order is not the book's order
  (frame 1 is 日 and it is the twelfth row here). Book-edition numbering cannot be derived from this
  dataset, so the app shows none rather than guessing.
* **No normalisation of the prose.** Unicode, emphasis markers (`**bold**`, `_italic_`) and the
  Koohii attribution live in the database verbatim; the React Native renderer is what interprets
  the emphasis. Only the field's surrounding whitespace is trimmed.

Stdlib only (csv, hashlib, json, sqlite3, urllib) — no pip installs.

Usage:
    python3 tools/build_rtk_db.py [path-to-final.csv]

Without an argument the pinned revision is downloaded. A local copy is verified against the same
SHA-256, so a hand-edited CSV cannot silently reach the asset.
"""

import csv
import hashlib
import io
import json
import os
import re
import sqlite3
import sys
import tempfile
import urllib.request

# ---------------------------------------------------------------------------
# Frozen source
# ---------------------------------------------------------------------------

SOURCE_REPO = "https://github.com/Arthur944/remembering-the-kanji-stories"
#: The revision `final.csv` was read at. Pinned so the asset is reproducible.
SOURCE_COMMIT = "029d0e5bb1a33c436a27aa46dda4cca784b48129"
SOURCE_URL = (
    "https://raw.githubusercontent.com/Arthur944/remembering-the-kanji-stories/"
    f"{SOURCE_COMMIT}/final.csv"
)
#: SHA-256 of that revision's `final.csv`.
SOURCE_SHA256 = "018d54648371a7597eb33ccc4e3ec295ee1730cf4d01d2e6021c76e7a5ff1791"

ASSET_PATH = "app/src/main/assets/db/rtk.sqlite"
DATA_DOC = "docs/jp-prototype/rtk/DATA.md"

#: Stamped as `PRAGMA user_version` and checked by `app/build.gradle.kts` before an APK is built.
SCHEMA_VERSION = 1

#: The source is semicolon-delimited with no header. Columns, in order:
#: kanji, keyword, Heisig story, Heisig primitive comment, Koohii story 1..5.
COLUMNS = ("kanji", "keyword", "story", "primitive", "koohii1", "koohii2", "koohii3", "koohii4", "koohii5")

#: `N) [[author](profile_url)] D-M-YYYY(score): body`. The leading N is authoritative: nine rows
#: drop a middle story (`半` carries 1, 2, 3, 5), so the cell position alone would misnumber it.
MNEMONIC = re.compile(
    r"^(\d+)\)\s*\[\[([^\]]*)\]\(([^)]*)\)\]\s*([0-9\-]*)\((\d*)\):\s?(.*)$",
    re.DOTALL,
)

#: Rows asserted to exist after the build, with the keyword the asset must carry for them.
KEYWORD_PROBES = (("日", "day"), ("一", "one"), ("鬱", "gloom"))
#: A kanji the dataset does not carry (an astral-plane variant absent from RTK).
MISSING_PROBE = "𠮷"
#: A row that dropped a middle Koohii story: the retained slots must stay what they say they are.
SPARSE_MNEMONIC_PROBE = ("半", (1, 2, 3, 5))

#: What the source actually holds, asserted so a changed upstream CSV fails the build instead of
#: silently producing a different asset.
EXPECTED_ROWS = 2991
EXPECTED_WITH_STORY = 2182
EXPECTED_WITH_PRIMITIVE = 132
EXPECTED_WITH_MNEMONICS = 2991


def download() -> bytes:
    """The pinned `final.csv`, or the local path given on the command line; both are hash-checked."""
    if len(sys.argv) > 1:
        with open(sys.argv[1], "rb") as handle:
            payload = handle.read()
    else:
        with urllib.request.urlopen(SOURCE_URL, timeout=60) as response:
            payload = response.read()
    actual = hashlib.sha256(payload).hexdigest()
    if actual != SOURCE_SHA256:
        raise SystemExit(
            f"final.csv SHA-256 is {actual}, expected {SOURCE_SHA256}. "
            "The pinned revision changed or the file is corrupt."
        )
    return payload


def parse_mnemonics(cells) -> list:
    """The Koohii stories of one row, parsed and ordered by their own leading number.

    An empty cell is skipped; a cell that does not match the expected shape is kept with its slot
    number and the raw prose, so nothing is silently dropped.
    """
    stories = []
    for slot, cell in enumerate(cells, start=1):
        text = (cell or "").strip()
        if not text:
            continue
        match = MNEMONIC.match(text)
        if match is None:
            stories.append(
                {"index": slot, "author": "", "profileUrl": "", "date": "", "score": 0, "body": text}
            )
            continue
        number, author, profile, date, score, body = match.groups()
        stories.append(
            {
                "index": int(number),
                "author": author,
                "profileUrl": profile,
                "date": date,
                "score": int(score) if score else 0,
                "body": body,
            }
        )
    stories.sort(key=lambda story: story["index"])
    return stories


def read_rows(payload: bytes) -> list:
    """The CSV as one dict per kanji, validated hard enough that a bad row fails the build."""
    text = payload.decode("utf-8-sig")
    rows = []
    for line_number, cells in enumerate(csv.reader(io.StringIO(text), delimiter=";"), start=1):
        if not cells or not "".join(cells).strip():
            continue
        character = cells[0].strip()
        if len(character) != 1:
            raise SystemExit(f"line {line_number}: expected one kanji in column 1, got {character!r}")
        if len(cells) > len(COLUMNS):
            raise SystemExit(f"line {line_number}: {len(cells)} columns, at most {len(COLUMNS)} supported")
        cells = cells + [""] * (len(COLUMNS) - len(cells))
        rows.append(
            {
                "character": character,
                "keyword": cells[1].strip(),
                "story": cells[2].strip(),
                "primitive": cells[3].strip(),
                "mnemonics": parse_mnemonics(cells[4:9]),
            }
        )
    return rows


def build(rows: list) -> sqlite3.Connection:
    """Creates the asset at [ASSET_PATH] and returns the open connection for verification."""
    os.makedirs(os.path.dirname(ASSET_PATH), exist_ok=True)
    if os.path.exists(ASSET_PATH):
        os.remove(ASSET_PATH)
    database = sqlite3.connect(ASSET_PATH)
    # An ordinary rowid table with a primary-key index, not WITHOUT ROWID: these rows are ~1.5 KB,
    # and a WITHOUT ROWID B-tree packed measured ~14 MB against ~6 MB this way for the same content.
    database.executescript(
        """
        PRAGMA user_version = %d;
        CREATE TABLE rtk (
            character TEXT PRIMARY KEY,
            keyword   TEXT NOT NULL,
            story     TEXT NOT NULL,
            primitive TEXT NOT NULL,
            mnemonics TEXT NOT NULL
        );
        CREATE TABLE meta (
            key   TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );
        """
        % SCHEMA_VERSION
    )
    database.executemany(
        "INSERT INTO rtk (character, keyword, story, primitive, mnemonics) VALUES (?, ?, ?, ?, ?)",
        [
            (
                row["character"],
                row["keyword"],
                row["story"],
                row["primitive"],
                json.dumps(row["mnemonics"], ensure_ascii=False, separators=(",", ":")),
            )
            for row in rows
        ],
    )
    with_story = sum(1 for row in rows if row["story"])
    with_primitive = sum(1 for row in rows if row["primitive"])
    with_mnemonics = sum(1 for row in rows if row["mnemonics"])
    database.executemany(
        "INSERT INTO meta (key, value) VALUES (?, ?)",
        [
            ("sourceRepo", SOURCE_REPO),
            ("sourceUrl", SOURCE_URL),
            ("sourceCommit", SOURCE_COMMIT),
            ("sourceSha256", SOURCE_SHA256),
            ("schemaVersion", str(SCHEMA_VERSION)),
            ("rows", str(len(rows))),
            ("uniqueKanji", str(len({row["character"] for row in rows}))),
            ("withStory", str(with_story)),
            ("withPrimitive", str(with_primitive)),
            ("withMnemonics", str(with_mnemonics)),
        ],
    )
    database.commit()
    return database


def verify(database: sqlite3.Connection, rows: list) -> None:
    """Fails the build unless the produced asset answers every probe the app depends on."""
    version = database.execute("PRAGMA user_version").fetchone()[0]
    if version != SCHEMA_VERSION:
        raise SystemExit(f"asset is schema version {version}, expected {SCHEMA_VERSION}")

    count = database.execute("SELECT COUNT(*) FROM rtk").fetchone()[0]
    if count != len(rows) or count != EXPECTED_ROWS:
        raise SystemExit(f"asset holds {count} kanji, expected {EXPECTED_ROWS}")

    unique = database.execute("SELECT COUNT(DISTINCT character) FROM rtk").fetchone()[0]
    if unique != EXPECTED_ROWS:
        raise SystemExit(f"asset holds {unique} unique characters, expected {EXPECTED_ROWS}")

    with_story = database.execute("SELECT COUNT(*) FROM rtk WHERE story <> ''").fetchone()[0]
    with_primitive = database.execute("SELECT COUNT(*) FROM rtk WHERE primitive <> ''").fetchone()[0]
    with_mnemonics = database.execute("SELECT COUNT(*) FROM rtk WHERE mnemonics <> '[]'").fetchone()[0]
    if with_story != EXPECTED_WITH_STORY:
        raise SystemExit(f"{with_story} rows carry a Heisig story, expected {EXPECTED_WITH_STORY}")
    if with_primitive != EXPECTED_WITH_PRIMITIVE:
        raise SystemExit(f"{with_primitive} rows carry a primitive, expected {EXPECTED_WITH_PRIMITIVE}")
    if with_mnemonics != EXPECTED_WITH_MNEMONICS:
        raise SystemExit(f"{with_mnemonics} rows carry a community story, expected {EXPECTED_WITH_MNEMONICS}")

    for character, keyword in KEYWORD_PROBES:
        row = database.execute("SELECT keyword, mnemonics FROM rtk WHERE character = ?", (character,)).fetchone()
        if row is None or row[0] != keyword:
            raise SystemExit(f"probe {character!r} did not read back as {keyword!r}: {row!r}")
        if not json.loads(row[1]):
            raise SystemExit(f"probe {character!r} carries no community story")

    if database.execute("SELECT 1 FROM rtk WHERE character = ?", (MISSING_PROBE,)).fetchone() is not None:
        raise SystemExit(f"probe {MISSING_PROBE!r} unexpectedly exists; the lookup miss path is untested")

    sparse_character, expected_indices = SPARSE_MNEMONIC_PROBE
    sparse = database.execute(
        "SELECT mnemonics FROM rtk WHERE character = ?", (sparse_character,)
    ).fetchone()
    if sparse is None:
        raise SystemExit(f"sparse-story probe {sparse_character!r} is missing")
    indices = tuple(story["index"] for story in json.loads(sparse[0]))
    if indices != expected_indices:
        raise SystemExit(
            f"sparse-story probe {sparse_character!r} read as {indices}, expected {expected_indices}"
        )


def write_data_doc(rows: list, size_bytes: int) -> None:
    """The asset's provenance page, regenerated with every build."""
    with_story = sum(1 for row in rows if row["story"])
    with_primitive = sum(1 for row in rows if row["primitive"])
    with_mnemonics = sum(1 for row in rows if row["mnemonics"])
    os.makedirs(os.path.dirname(DATA_DOC), exist_ok=True)
    with open(DATA_DOC, "w", encoding="utf-8") as handle:
        handle.write(
            f"""# Remembering the Kanji asset (DATA)

Generated by `tools/build_rtk_db.py`. Do not edit by hand.

| | |
| --- | --- |
| Source | [{SOURCE_REPO}]({SOURCE_REPO}) |
| File | `final.csv` at commit [`{SOURCE_COMMIT}`]({SOURCE_REPO}/commit/{SOURCE_COMMIT}) |
| SHA-256 | `{SOURCE_SHA256}` |
| Asset | `{ASSET_PATH}` (`db/rtk.sqlite` inside the APK) |
| Schema version | `{SCHEMA_VERSION}` (`PRAGMA user_version`) |
| Asset size | {size_bytes:,} bytes |
| Kanji | {len(rows):,} unique |
| With a Heisig story | {with_story:,} |
| With a Heisig primitive comment | {with_primitive:,} |
| With at least one Koohii story | {with_mnemonics:,} |

## What is stored

One `rtk` row per Unicode kanji character, keyed by the character itself:

| Column | Content |
| --- | --- |
| `character` | The kanji, one code point |
| `keyword` | The RTK English keyword |
| `story` | The Heisig story or mnemonic hint, emphasis markers intact |
| `primitive` | The Heisig primitive comment, when the edition carries one |
| `mnemonics` | JSON array of `{{index, author, profileUrl, date, score, body}}` |

The `meta` table repeats the provenance above inside the asset itself.

## Decisions

* **No frame numbers.** The source has no frame column, and row order is not the book's order
  (frame 1, 日, is the twelfth row). Showing a frame would mean guessing, so none is stored.
* **The leading `N)` of a Koohii cell is authoritative.** Nine rows drop a middle story; parsing by
  cell position would renumber them. The importer sorts by the number the story itself carries.
* **Prose is not normalised.** Unicode, `**bold**`/`_italic_` emphasis and the Koohii attribution
  are stored verbatim; the React Native renderer interprets the emphasis. The Heisig primitive
  comment's leading `* ` list marker is the one piece of presentation the renderer drops.
* **Missing data stays missing.** Empty story, primitive or mnemonic columns are stored as empty and
  the app hides those sections; nothing is synthesised.

## Redistribution checkpoint (release blocker)

This asset repackages third-party prose: Heisig's *Remembering the Kanji* text and
Kanji Koohii community stories. The source repository's public availability does **not** by itself
grant redistribution rights. Before publishing a store release that bundles `rtk.sqlite`, the
project must confirm it may redistribute this text (or ship the feature without the bundled data).
The asset is generated at build time and is gitignored, never committed.
"""
        )


def main() -> None:
    payload = download()
    rows = read_rows(payload)
    database = build(rows)
    try:
        verify(database, rows)
    finally:
        database.close()
    size_bytes = os.path.getsize(ASSET_PATH)
    write_data_doc(rows, size_bytes)
    print(
        f"Wrote {ASSET_PATH}: {len(rows)} kanji, {size_bytes:,} bytes, "
        f"schema {SCHEMA_VERSION}, source commit {SOURCE_COMMIT[:12]}"
    )


if __name__ == "__main__":
    main()
