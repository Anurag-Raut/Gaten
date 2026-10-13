#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Merge the Living Japanese Slang dictionary into the on-device dictionary asset.

Companion to `build_jmdict_db.py`: downloads the frozen compiled Yomitan zip of the
Living Japanese Slang dictionary (Scripting Japan), verifies its SHA-256, and appends its
entries to `app/src/main/assets/db/jmdict.sqlite` — the asset the F3 lookup layer already
opens. JMdict covers formal vocabulary well but daily modern slang (streaming chat, otaku
speech, net slang) only patchily; this fills that gap with a small purpose-built slang list.

Every slang entry is stored with `pos = 'slang (Living Japanese)'` and `common = 0`, so the
app's ranking (`ORDER BY t.common DESC, (e.kanji = '') DESC, e.id ASC`) always shows the
JMdict match first and the slang gloss below it. Entry ids start at 10,000,000 — one above
the highest JMdict id in the asset (9,999,999) — so they stay numeric-looking, sort after
every JMdict entry and can never collide.

Idempotent: previous slang rows (matched by `pos LIKE 'slang%'`) are dropped before the new
ones are inserted, so the script can be re-run after a source bump.

Stdlib only (zipfile, json, sqlite3, hashlib, shutil, urllib) — no pip installs.

Usage:
    python3 tools/build_slang_db.py
"""

import hashlib
import json
import os
import shutil
import sqlite3
import sys
import tempfile
import urllib.request
import zipfile

# ---------------------------------------------------------------------------
# Frozen source
# ---------------------------------------------------------------------------

# Compiled Yomitan zip distributed from the MarvNC/yomitan-dictionaries list
# (https://github.com/MarvNC/yomitan-dictionaries — "Living Japanese Slang" section,
# hosted on GitHub's user-attachments CDN). Built from Wesley Robertson's (Scripting Japan)
# Living Japanese Slang dictionary, converted by Selxo; revision `scriptingjapan-slang-dictionary-636-v12`,
# 636 term rows in classic `term_bank_1.json` format (format 3, no compact banks).
DICTIONARY_TITLE = "Living Japanese Slang Dictionary (Scripting Japan)"
DICT_REVISION = "scriptingjapan-slang-dictionary-636-v12"
ARCHIVE_FILENAME = "Living_Japanese_Slang_Dictionary_Scripting_Japan.zip"
SOURCE_URL = (
    "https://github.com/user-attachments/files/21926553/"
    "Living_Japanese_Slang_Dictionary_Scripting_Japan.zip"
)
# SHA-256 of the downloaded zip, pinned so corruption is caught.
ARCHIVE_SHA256 = "a69b026bfe2230d1a919dffe19e5846a1fc74a831d22853658aafcd64d09443a"

TERM_BANK_PREFIX = "term_bank_"

#: POS label stored on every merged entry; also the idempotency key for re-runs.
SLANG_POS = "slang (Living Japanese)"

#: Lowest slang entry id. JMdict ent_seq values in the shipped asset top out at 9,999,999,
#: so everything at or above this offset belongs to this script (see the `terms` cleanup).
SLANG_ID_BASE = 10_000_000

#: Rows printed after the build. 神回 and 履修 exist in the source; ガチ, ドンマイ and 草
#: (in its "lol" sense) do not — they stay as probes so a future source bump that covers
#: them shows up loudly instead of silently.
PROBES = ("神回", "履修", "草", "ガチ", "ドンマイ")

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSET_PATH = os.path.join(REPO_ROOT, "app", "src", "main", "assets", "db", "jmdict.sqlite")
DATA_MD_PATH = os.path.join(REPO_ROOT, "docs", "jp-prototype", "dictionary", "DATA.md")
DOWNLOAD_CACHE = os.path.join(tempfile.gettempdir(), "slang-build-cache")

LICENSE_LINE = (
    "Living Japanese Slang © Wesley Robertson (Scripting Japan), CC BY-NC-SA 4.0; "
    "Yomitan conversion by Selxo"
)

LOOKUP_SQL = """
SELECT e.id, e.kanji, e.kana, e.gloss, e.pos, e.common
FROM terms t JOIN entries e ON e.id = t.entry_id
WHERE t.term = ?
ORDER BY e.common DESC, e.id ASC
LIMIT 8
"""


# ---------------------------------------------------------------------------
# Download / extraction
# ---------------------------------------------------------------------------

def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_archive(url, filename, sha256):
    """Return the path of the zip, downloading and verifying it once into a temp cache."""
    os.makedirs(DOWNLOAD_CACHE, exist_ok=True)
    path = os.path.join(DOWNLOAD_CACHE, filename)
    if os.path.exists(path) and sha256_file(path) == sha256:
        print(f"archive cache hit: {path}")
        return path
    print(f"downloading {url}")
    tmp_path = path + ".part"
    with urllib.request.urlopen(url) as response, open(tmp_path, "wb") as out:
        shutil.copyfileobj(response, out)
    actual = sha256_file(tmp_path)
    if actual != sha256:
        os.remove(tmp_path)
        raise SystemExit(f"SHA-256 mismatch: expected {sha256}, got {actual}")
    os.replace(tmp_path, path)
    print(f"verified SHA-256 {actual}")
    return path


def load_term_banks(zip_path):
    """Read every `term_bank_*.json` member in ascending bank order and return the rows.

    Classic Yomitan term-bank v3 format: [expression, reading, tags, rules, score,
    glosses, sequence, termTags]. The compact (single `dictionary.json`) layout some
    newer packs use would have no `term_bank_` members at all, which is a loud failure.
    """
    with zipfile.ZipFile(zip_path) as archive:
        banks = sorted(name for name in archive.namelist() if name.startswith(TERM_BANK_PREFIX))
        if not banks:
            raise SystemExit(f"no {TERM_BANK_PREFIX}*.json members in {zip_path}")
        index = json.loads(archive.read("index.json"))
        if index.get("title") != DICTIONARY_TITLE or index.get("revision") != DICT_REVISION:
            raise SystemExit(
                f"unexpected dictionary: title={index.get('title')!r} revision={index.get('revision')!r}"
            )
        print(f"source: {index['title']} revision {index['revision']}")
        rows = []
        for bank in banks:
            rows.extend(json.loads(archive.read(bank)))
        return rows


# ---------------------------------------------------------------------------
# Conversion
# ---------------------------------------------------------------------------

def has_kanji(text):
    """True when any character is a CJK ideograph (BMP blocks A + unified)."""
    return any("\u3400" <= c <= "\u9fff" for c in text)


def has_kana(text):
    """True when any character is kana (hiragana, katakana or their punctuation)."""
    return any("\u3040" <= c <= "\u30ff" for c in text)


def string_glosses(gloss_list):
    """Plain-string glosses only: the pack also ships structured-content dicts
    ({"type": "structured-content", ...} with example-sentence markup) that the panel
    cannot render and that would smuggle HTML into the gloss column."""
    return [g for g in gloss_list if isinstance(g, str)]


def build_row(row, entry_id):
    """Turn one term-bank row into (entry_row, term_rows) or None when it has no text.

    `primary` is the surface the panel shows: the expression when it carries kana/kanji,
    else the reading (romaji-only expressions like DQN keep the reading there), else the
    expression itself.
    """
    expression, reading, _tags, _rules, _score, glosses = row[0], row[1], row[2], row[3], row[4], row[5]
    if not expression and not reading:
        return None

    gloss = " | ".join(string_glosses(glosses))
    if not gloss:
        return None

    if has_kana(expression) or has_kanji(expression):
        primary = expression
    else:
        primary = reading or expression

    kanji = expression if has_kanji(expression) else ""

    if reading and reading != expression:
        kana = reading
    elif has_kana(expression) and not has_kanji(expression):
        kana = expression
    else:
        kana = ""

    entry_row = (entry_id, kanji, kana, gloss, SLANG_POS, 0)

    # Headwords: the expression and, when it differs, the reading — one row per unique
    # (term, entry_id) so a repeated surface never doubles up.
    term_rows = []
    seen = set()
    for term, is_kanji in ((expression, 1 if has_kanji(expression) else 0), (reading, 0)):
        if not term or term in seen:
            continue
        seen.add(term)
        term_rows.append((term, entry_id, is_kanji, 0))
    return entry_row, term_rows


# ---------------------------------------------------------------------------
# Merge
# ---------------------------------------------------------------------------

def merge_slang(rows, db_path):
    """Drop the previous slang rows and insert the current ones; returns (entries, terms)."""
    conn = sqlite3.connect(db_path)
    try:
        conn.execute("PRAGMA journal_mode=OFF")
        conn.execute("PRAGMA synchronous=OFF")
        # Idempotency: `pos` identifies slang entries wherever their ids land; the terms
        # rows follow their entry ids. Both deletes run even when there is nothing to
        # drop, so a re-run over a freshly built asset is a no-op.
        cursor = conn.execute("DELETE FROM terms WHERE entry_id IN "
                              "(SELECT id FROM entries WHERE pos LIKE 'slang%')")
        removed_terms = cursor.rowcount
        cursor = conn.execute("DELETE FROM entries WHERE pos LIKE 'slang%'")
        removed_entries = cursor.rowcount
        if removed_entries:
            print(f"dropped previous slang rows: {removed_entries} entries, {removed_terms} terms")

        entry_count = 0
        term_count = 0
        for index, row in enumerate(rows):
            built = build_row(row, SLANG_ID_BASE + 1 + index)
            if built is None:
                continue
            entry_row, term_rows = built
            conn.execute(
                "INSERT INTO entries(id, kanji, kana, gloss, pos, common) VALUES(?,?,?,?,?,?)",
                entry_row,
            )
            conn.executemany(
                "INSERT INTO terms(term, entry_id, is_kanji, common) VALUES(?,?,?,?)",
                term_rows,
            )
            entry_count += 1
            term_count += len(term_rows)
        conn.commit()
    finally:
        conn.close()
    return entry_count, term_count


# ---------------------------------------------------------------------------
# Verification + docs
# ---------------------------------------------------------------------------

def probe(db_path):
    """Run the probe lookups and return {probe: [rows]}."""
    conn = sqlite3.connect(db_path)
    try:
        return {term: conn.execute(LOOKUP_SQL, (term,)).fetchall() for term in PROBES}
    finally:
        conn.close()


def _cell(value):
    """Escape pipes and flatten newlines so multi-gloss / example-bearing values stay
    inside the table cell (the gloss column itself keeps its raw newlines)."""
    return str(value).replace("|", "\\|").replace("\n", "<br>")


def append_data_md(archive_path, db_size, entry_count, term_count, results):
    lines = []
    lines.append("")
    lines.append("## Slang supplement (Living Japanese Slang)")
    lines.append("")
    lines.append("Appended to the same `jmdict.sqlite` by `tools/build_slang_db.py` "
                 "(Python 3 stdlib only, re-runnable — it drops its previous rows first). "
                 "Fills the daily-slang gap JMdict leaves (streaming chat, otaku speech, "
                 "net slang); every row carries `pos = 'slang (Living Japanese)'` and "
                 "`common = 0`, so JMdict matches still rank above it in the F3 lookup.")
    lines.append("")
    lines.append("| Field | Value |")
    lines.append("|---|---|")
    lines.append(f"| Source | {DICTIONARY_TITLE} (revision `{DICT_REVISION}`) |")
    lines.append(f"| Source URL | {SOURCE_URL} |")
    lines.append(f"| Archive file | {ARCHIVE_FILENAME} |")
    lines.append(f"| Archive size | {os.path.getsize(archive_path):,} bytes |")
    lines.append(f"| Archive SHA-256 | `{ARCHIVE_SHA256}` |")
    lines.append(f"| Slang `entries` rows | {entry_count:,} |")
    lines.append(f"| Slang `terms` rows | {term_count:,} |")
    lines.append(f"| Asset size after merge | {db_size:,} bytes |")
    lines.append(f"| Entry ids | {SLANG_ID_BASE + 1:,}+ (above the JMdict max id 9,999,999, so the "
                 "`e.id ASC` tie-break still lists slang entries last) |")
    lines.append(f"| License | {LICENSE_LINE} |")
    lines.append("")
    lines.append("The probe tables below list the rows a screen lookup returns, slang entry ids "
                 "included; probes that JMdict already covers (草's \"LOL\" sense, ガチ, ドンマイ) "
                 "show JMdict rows only — the supplement is the long tail, not a re-derivation.")
    lines.append("")
    lines.append("### Slang probe lookups")
    lines.append("")
    for term, rows in results.items():
        lines.append(f"### `{term}`")
        lines.append("")
        if not rows:
            lines.append("_absent from the source dictionary_")
            lines.append("")
            continue
        lines.append("| id | kanji | kana | gloss |")
        lines.append("|---|---|---|---|")
        for row in rows:
            lines.append(f"| {row[0]} | {_cell(row[1])} | {_cell(row[2])} | {_cell(row[3])} |")
        lines.append("")
    # The slang section is the document's tail: replacing it (rather than appending) keeps
    # re-runs from stacking duplicate sections.
    with open(DATA_MD_PATH, "r", encoding="utf-8") as fh:
        doc = fh.read()
    marker = "\n## Slang supplement (Living Japanese Slang)"
    if marker in doc:
        doc = doc[:doc.index(marker)].rstrip() + "\n"
    with open(DATA_MD_PATH, "w", encoding="utf-8") as fh:
        fh.write(doc.rstrip() + "\n")
        fh.write("\n".join(lines).rstrip() + "\n")


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main():
    archive_path = download_archive(SOURCE_URL, ARCHIVE_FILENAME, ARCHIVE_SHA256)
    rows = load_term_banks(archive_path)
    print(f"term rows: {len(rows)}")

    if not os.path.exists(ASSET_PATH):
        raise SystemExit(f"missing JMdict asset {ASSET_PATH}; run tools/build_jmdict_db.py first")
    entry_count, term_count = merge_slang(rows, ASSET_PATH)
    print(f"merged slang entries: {entry_count}, slang terms: {term_count}")

    results = probe(ASSET_PATH)
    for term, hits in results.items():
        summary = "; ".join(f"{row[1]}「{row[2]}」 {row[3][:60]}" for row in hits) or "ABSENT"
        print(f"probe {term}: {summary}")

    db_size = os.path.getsize(ASSET_PATH)
    append_data_md(archive_path, db_size, entry_count, term_count, results)
    print(f"wrote {DATA_MD_PATH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
