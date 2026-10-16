#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Build the on-device dictionary asset used by Gaten (CONTRACTS.md F2).

Downloads two frozen jmdict-simplified releases, verifies their SHA-256, converts the English
JMdict JSON into a compact SQLite database with the exact F2 schema (`entries`, `terms`,
`idx_terms_term`) plus a compact `names` table built from JMnedict (proper nouns that JMdict does
not carry) and one indexed row per reading in `loose` (the reader's own spelling with its long
vowels collapsed, so romaji like "tokyo" reaches 東京), writes it uncompressed to
`app/src/main/assets/db/jmdict.sqlite`, verifies the probe lookups and the romaji probes, and
writes `docs/jp-prototype/dictionary/DATA.md`.

The `loose` normalisation below is one half of a pair: `DictionarySearch.looseForm` (Kotlin) folds
the query the same way, so the two must stay identical.

The asset is shipped uncompressed on purpose: AGP expands `.gz` assets during
asset merge anyway, so gzipping in the repo only hides which file the app must
open at runtime. `JmdictRepository` copies `db/jmdict.sqlite` verbatim.

Stdlib only (tarfile, json, sqlite3, hashlib, shutil, urllib) — no pip installs.

Usage:
    python3 tools/build_jmdict_db.py
"""

import hashlib
import json
import os
import shutil
import sqlite3
import sys
import tarfile
import tempfile
import urllib.request

# ---------------------------------------------------------------------------
# Frozen source (CONTRACTS.md F2)
# ---------------------------------------------------------------------------

RELEASE_TAG = "3.6.2+20261005200550"
DICT_VERSION = "3.6.2"
DICT_DATE = "2026-10-05"
ARCHIVE_FILENAME = "jmdict-eng-3.6.2+20261005200550.json.tgz"
SOURCE_URL = (
    "https://github.com/scriptin/jmdict-simplified/releases/download/"
    "3.6.2%2B20261005200550/jmdict-eng-3.6.2+20261005200550.json.tgz"
)
# SHA-256 of the downloaded tarball, pinned so corruption is caught.
ARCHIVE_SHA256 = "081f5059d02d28e2d4e31f2afe9b9c0a56bb8c7003c33dc9420fea6329cb2550"
JSON_MEMBER = "jmdict-eng-3.6.2.json"

# Proper nouns live in JMnedict, not JMdict: 池袋, 新宿 and 秋葉原 are absent from JMdict itself
# (verified against the produced database), so a screen lookup of 「池袋弁」 used to fall back to
# the bare kanji 池 ("pond"). Same frozen release, same licence, own compact table.
NAMES_URL = (
    "https://github.com/scriptin/jmdict-simplified/releases/download/"
    "3.6.2%2B20261005200550/jmnedict-all-3.6.2+20261005200550.json.tgz"
)
NAMES_ARCHIVE_FILENAME = "jmnedict-all-3.6.2+20261005200550.json.tgz"
NAMES_ARCHIVE_SHA256 = "9a6a36209eb400ef0bd2704445d383de3834c7a7e883cd959f409f644b1d2e98"

#: Rows asserted to exist in `names` after the build.
NAME_PROBES = ("池袋", "新宿", "秋葉原")

# Schema version stamped as `PRAGMA user_version`, and checked by `app/build.gradle.kts` before an
# APK is built. 1 was `entries`/`terms`/`names`, 2 added an `examples` table while the Anki export
# carried an example sentence, 3 removed it again, and 4 added `loose` (one indexed row per reading,
# long vowels collapsed). The number only ever moves forward: an asset built for another schema is
# refused by the build rather than reaching a device whose queries expect something else — and a copy
# already extracted into an installed app is replaced, because `JmdictRepository` compares the SQLite
# header (which holds this field) with the packaged asset's.
SCHEMA_VERSION = 4

#: Longest stored name gloss; keeps the table at ~21 MB for ~624k terms.
NAME_GLOSS_CHARS = 40

NAME_KINDS = (
    ("place name", ("place", "station")),
    ("surname", ("surname",)),
    ("given name", ("given", "person")),
    ("name", ()),
)


def name_order(types, entry_id):
    """Sort key for one name row: merged senses first, then kind, then JMnedict id.

    JMnedict splits a spelling across one entry per reading, and the reading everybody knows
    carries every type it is used as: 新宿 has five entries (あらじゅく, しんしく, しんしゅく,
    しんじゅく, にいじゅく) and only しんじゅく = Shinjuku is tagged place *and* surname, exactly
    like 池袋 いけぶくろ = Ikebukuro next to the surname-only いけふくろ = Ikefukuro. Ordering by
    type count therefore floats the established reading to the top, which id order alone does not
    (5407284 あらじゅく comes first otherwise).
    """
    return (0 if len(types) > 1 else 1) * 100_000_000 + name_priority(types) * 10_000_000 + entry_id

# A form or entry is "common" when it carries any of these JMdict frequency tags.
# jmdict-simplified collapses them into a precomputed boolean (`form["common"]`),
# so both the raw tags and the boolean are honoured.
COMMON_TAGS = frozenset({"news1", "ichi1", "spec1", "gai1"})

# gloss: every sense in the entry. Coverage beats brevity here — the panel decides how much of it
# to show, and a word whose only sense was cut is a word the user cannot learn.
MAX_SENSES = None

PROBES = ("猫", "水", "学校", "食べる", "ありがとう", "コンピュータ")

#: The romaji probes: the query as a reader types it, the kana `Wanakana.toHiragana` makes of it
#: (the app's converter; `DictionarySearchTest` pins the same conversions), the loose form `loose()`
#: makes of that kana, and a spelling the answer must carry — the headword of a kanji entry, the
#: reading of a kana-headed one. The shortened spellings (tokyo, ramen, ohayo, gakko, arigato) are
#: the ones the exact lookup cannot answer; kirei, saikou and byouin are answered exactly, and pin
#: that the loose pass adds nothing that displaces them.
ROMAJI_PROBES = (
    ("tokyo", "ときょ", "ときょ", "東京"),
    ("ramen", "らめん", "らめん", "ラーメン"),
    ("ohayo", "おはよ", "おはよ", "お早う"),
    ("gakko", "がっこ", "がっこ", "学校"),
    ("arigato", "ありがと", "ありがと", "有難う"),
    ("kirei", "きれい", "きれい", "綺麗"),
    ("saikou", "さいこう", "さいこ", "最高"),
    ("byouin", "びょういん", "びょいん", "病院"),
)

#: Forms `loose()` must fold to, pinned: the romaji study's verified pairs, the voiced rows ("judo"
#: needs どう to lose its う like とう), and the doubled and row vowels. Both sides of the fold are
#: pinned here — `DictionarySearchTest` holds the same table in Kotlin — so a change to one language
#: without the other is caught before a database is built from it.
LOOSE_PROBES = (
    ("ときょ", "ときょ"),
    ("らめん", "らめん"),
    ("おはよ", "おはよ"),
    ("がっこ", "がっこ"),
    ("ありがと", "ありがと"),
    ("さいこう", "さいこ"),
    ("きれい", "きれい"),  # えい is not collapsed
    ("びょういん", "びょいん"),
    ("とうきょう", "ときょ"),
    ("ラーメン", "らめん"),
    ("らーめん", "らめん"),
    ("らあめん", "らめん"),
    ("おはよう", "おはよ"),
    ("がっこう", "がっこ"),
    ("ありがとう", "ありがと"),
    ("サイコー", "さいこ"),
    ("じゅうどう", "じゅど"),
    ("じゅど", "じゅど"),
    ("おおきい", "おき"),
    ("ええ", "え"),
)

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSET_PATH = os.path.join(REPO_ROOT, "app", "src", "main", "assets", "db", "jmdict.sqlite")
DATA_MD_PATH = os.path.join(REPO_ROOT, "docs", "jp-prototype", "dictionary", "DATA.md")
DOWNLOAD_CACHE = os.path.join(tempfile.gettempdir(), "jmdict-build-cache")

LICENSE_LINE = "JMdict © Electronic Dictionary Research and Development Group (EDRDG), CC BY-SA 4.0"

SCHEMA = """
CREATE TABLE entries(
  id     INTEGER PRIMARY KEY,
  kanji  TEXT NOT NULL DEFAULT '',
  kana   TEXT NOT NULL DEFAULT '',
  gloss  TEXT NOT NULL DEFAULT '',
  pos    TEXT NOT NULL DEFAULT '',
  common INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE terms(
  term     TEXT NOT NULL,
  entry_id INTEGER NOT NULL,
  is_kanji INTEGER NOT NULL,
  common   INTEGER NOT NULL
);
CREATE INDEX idx_terms_term ON terms(term);
CREATE TABLE names(
  term  TEXT NOT NULL,
  rank  INTEGER NOT NULL,
  kind  TEXT NOT NULL,
  gloss TEXT NOT NULL
);
CREATE INDEX idx_names_term ON names(term);
CREATE TABLE loose(
  reading  TEXT NOT NULL,
  entry_id INTEGER NOT NULL,
  common   INTEGER NOT NULL
);
CREATE INDEX idx_loose_reading ON loose(reading);
"""

NAME_LOOKUP_SQL = "SELECT kind, gloss FROM names WHERE term = ? ORDER BY rank ASC LIMIT 2"

# The app's romaji fallback: `DictionarySearch.lookupLoose` asks exactly this, so the probe below
# exercises the query the device runs. `loose` is one row per reading, so the same entry can appear
# once per reading that folds onto the probe — the app deduplicates by id.
LOOSE_LOOKUP_SQL = """
SELECT e.id, e.kanji, e.kana, e.gloss, e.pos, e.common
FROM loose l JOIN entries e ON e.id = l.entry_id
WHERE l.reading = ?
ORDER BY l.common DESC, (e.kanji = '') DESC, e.id ASC
LIMIT 8
"""

LOOKUP_SQL = """
SELECT e.id, e.kanji, e.kana, e.gloss, e.pos, e.common
FROM terms t JOIN entries e ON e.id = t.entry_id
WHERE t.term = ?
ORDER BY e.common DESC, e.id ASC
LIMIT 8
"""

# ---------------------------------------------------------------------------
# Loose readings (the romaji fallback)
# ---------------------------------------------------------------------------

#: Kana a dropped vowel may follow. Voiced and half-voiced forms are in the row they belong to by
#: sound, not by spelling: without them じゅうどう would keep its last う while the reader's own
#: じゅど never drops anything, and "judo" would miss 柔道. Row u takes う (とうきょう -> ときょ),
#: row o takes う (サイコー -> さいこ), row a takes あ (らあめん -> らめん), row i takes い.
ROW_U = frozenset("うくすつぬふむゆるゅゔぐずづぶぷ")
ROW_O = frozenset("こそとのほもよろをぉょごぞどぼぽ")
ROW_A = frozenset("かさたなはまやらわゃがざだばぱ")
ROW_I = frozenset("きしちにひみりゅぎじぢびぴ")

#: Vowels that collapse when written twice: おお -> お, ええ -> え, and the bare pairs alike.
VOWELS = frozenset("あいうえお")


def loose(kana):
    """The reading as the reader tends to spell it: katakana folded, ー gone, long vowels collapsed.

    The reader types "tokyo" for とうきょう, "ramen" for ラーメン and "gakko" for がっこう, and no
    dictionary stores those spellings, so both the query and every indexed reading go through this
    one function and are then compared exactly. Dropping a vowel after a vowel is what does the
    work: う after a u-row or o-row kana (とうきょう -> ときょ), あ after an a-row kana
    (らあめん -> らめん), い after an i-row kana, and a doubled vowel (おお -> お). Each character
    is judged against the last one kept, so a dropped vowel never stands in for its own
    predecessor.

    Nothing here is linguistically exact — えい is not collapsed, so "kirei" and きれい still agree
    — and it does not have to be: the same lossy fold on both sides only ever has to be consistent.
    `DictionarySearch.looseForm` is the same function in Kotlin, so the two must stay in step.
    """
    out = []
    for char in kana:
        code = ord(char)
        if 0x30A1 <= code <= 0x30F6:  # katakana (ァ to ヶ) down to hiragana
            char = chr(code - 0x60)
        if char == "ー":  # the prolonged sound mark, and the halfwidth one NFKC maps to it
            continue
        if out:
            previous = out[-1]
            if char == "う" and (previous in ROW_U or previous in ROW_O):
                continue
            if char in VOWELS and char == previous:
                continue
            if char == "あ" and previous in ROW_A:
                continue
            if char == "い" and previous in ROW_I:
                continue
        out.append(char)
    return "".join(out)


def loose_failures():
    """The pinned forms `loose()` no longer folds to, so a bad fold is caught before the build."""
    return [
        f"loose({kana}) is {loose(kana)}, pinned {form}"
        for kana, form in LOOSE_PROBES
        if loose(kana) != form
    ]


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
    """Return the path of the tarball, downloading and verifying it once into a temp cache."""
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


def extract_json(tgz_path, work_dir):
    """Extract the single JSON member from the tarball and return its path."""
    with tarfile.open(tgz_path, mode="r:gz") as tar:
        members = [m for m in tar.getmembers() if m.isfile() and m.name.endswith(".json")]
        if len(members) != 1:
            raise SystemExit(f"expected exactly one .json member, found {len(members)}")
        tar.extract(members[0], path=work_dir, filter="data")
        return os.path.join(work_dir, members[0].name)


# ---------------------------------------------------------------------------
# Conversion
# ---------------------------------------------------------------------------

def form_is_common(form):
    return bool(form.get("common")) or any(tag in COMMON_TAGS for tag in form.get("tags", []))


def entry_is_common(word):
    if any(tag in COMMON_TAGS for tag in word.get("tags", [])):
        return True
    return any(form_is_common(f) for f in word.get("kanji", []) + word.get("kana", []))


def sense_gloss(sense):
    texts = [g["text"] for g in sense.get("gloss", []) if g.get("text")]
    return ", ".join(texts)


def build_rows(words, pos_map):
    """Yield (entry_row, term_rows, loose_rows) for every word in the JSON."""
    for word in words:
        entry_id = int(word["id"])
        kanji_forms = word.get("kanji", [])
        kana_forms = word.get("kana", [])

        kanji = " ".join(f["text"] for f in kanji_forms)
        kana = " ".join(f["text"] for f in kana_forms)

        senses = word.get("sense", [])
        selected = senses if MAX_SENSES is None else senses[:MAX_SENSES]
        gloss = " | ".join(g for g in (sense_gloss(s) for s in selected) if g)

        pos_tags = []
        for sense in senses:
            for tag in sense.get("partOfSpeech", []):
                if tag not in pos_tags:
                    pos_tags.append(tag)
        pos = " | ".join(pos_map.get(tag, tag) for tag in pos_tags)

        common = 1 if entry_is_common(word) else 0

        entry_row = (entry_id, kanji, kana, gloss, pos, common)

        term_rows = []
        for form in kanji_forms:
            term_rows.append((form["text"], entry_id, 1, 1 if (form_is_common(form) or common) else 0))
        for form in kana_forms:
            term_rows.append((form["text"], entry_id, 0, 1 if (form_is_common(form) or common) else 0))

        # One row per distinct loose reading: ラーメン, らーめん and らあめん are all らめん and all
        # mean the same entry, so the reader asking for らめん gets it once however it was spelled.
        # The row keeps the kana form's commonness, which is what the exact lookup ranks by.
        loose_rows = {}
        for form in kana_forms:
            reading = loose(form["text"])
            if not reading:
                continue
            is_common = 1 if (form_is_common(form) or common) else 0
            loose_rows[reading] = max(loose_rows.get(reading, 0), is_common)

        yield entry_row, term_rows, [(reading, entry_id, flag) for reading, flag in sorted(loose_rows.items())]


def name_kind(types):
    """Every type the name is used as, best first: 秋葉原 is a place *and* a surname in JMnedict."""
    labels = [label for label, tags in NAME_KINDS if any(tag in types for tag in tags)]
    return " / ".join(labels) if labels else "name"


def name_priority(types):
    """Index of the most place-like type this name carries; drives the row's sort key."""
    for index, (label, tags) in enumerate(NAME_KINDS):
        if any(tag in types for tag in tags):
            return index
    return len(NAME_KINDS) - 1


def add_names(db_path, names_json_path):
    """Add JMnedict proper nouns as a compact `names` table; returns the row count.

    Only entries with a kanji form, an English gloss and no JMdict coverage are kept (a name that
    is already a dictionary word would otherwise duplicate it). One row per kanji form *and* name
    type, so 池袋 keeps both its place reading (Ikebukuro) and its surname one.
    """
    with open(names_json_path, "r", encoding="utf-8") as fh:
        words = json.load(fh)["words"]

    conn = sqlite3.connect(db_path)
    try:
        known = {row[0] for row in conn.execute("SELECT DISTINCT term FROM terms")}
        rows = set()
        for word in words:
            kanji_forms = [f["text"] for f in word.get("kanji", [])]
            if not kanji_forms or all(form in known for form in kanji_forms):
                continue
            types = set()
            gloss = None
            for translation in word.get("translation", []):
                types.update(translation.get("type", []))
                for gloss_entry in translation.get("translation", []):
                    if gloss_entry.get("lang") == "eng" and gloss is None:
                        gloss = (gloss_entry.get("text") or "").strip()
            if not gloss:
                continue
            kind = name_kind(types)
            for form in kanji_forms:
                rows.add((form, name_order(types, int(word["id"])), kind,
                          gloss[:NAME_GLOSS_CHARS]))
        conn.executemany("INSERT INTO names(term, rank, kind, gloss) VALUES(?,?,?,?)", sorted(rows))
        conn.commit()
    finally:
        conn.close()
    return len(rows)


def set_user_version(db_path, version):
    """Stamp the schema version as `user_version`.

    Two readers depend on it: `app/build.gradle.kts` refuses to build against an asset of another
    version, and the app compares the whole 100-byte SQLite header (which holds this field) with the
    packaged asset's, so a copy extracted from an older APK is replaced instead of queried.
    """
    conn = sqlite3.connect(db_path)
    try:
        conn.execute(f"PRAGMA user_version = {version}")
        conn.commit()
    finally:
        conn.close()


def probe_names(db_path):
    """Return {probe: rows} for the name probes, so a build without them fails loudly."""
    conn = sqlite3.connect(db_path)
    try:
        return {term: conn.execute(NAME_LOOKUP_SQL, (term,)).fetchall() for term in NAME_PROBES}
    finally:
        conn.close()


def build_sqlite(json_path, db_path):
    with open(json_path, "r", encoding="utf-8") as fh:
        data = json.load(fh)
    words = data["words"]
    pos_map = data["tags"]

    if os.path.exists(db_path):
        os.remove(db_path)
    conn = sqlite3.connect(db_path)
    try:
        conn.execute("PRAGMA journal_mode=OFF")
        conn.execute("PRAGMA synchronous=OFF")
        conn.executescript(SCHEMA)
        entry_count = 0
        term_count = 0
        loose_count = 0
        for entry_row, term_rows, loose_rows in build_rows(words, pos_map):
            conn.execute(
                "INSERT INTO entries(id, kanji, kana, gloss, pos, common) VALUES(?,?,?,?,?,?)",
                entry_row,
            )
            conn.executemany(
                "INSERT INTO terms(term, entry_id, is_kanji, common) VALUES(?,?,?,?)",
                term_rows,
            )
            conn.executemany(
                "INSERT INTO loose(reading, entry_id, common) VALUES(?,?,?)",
                loose_rows,
            )
            entry_count += 1
            term_count += len(term_rows)
            loose_count += len(loose_rows)
        conn.commit()
    finally:
        conn.close()
    return entry_count, term_count, loose_count


def copy_asset(src, dst):
    """Copy the built sqlite to the asset path.

    Shipped uncompressed: AGP expands `.gz` assets during asset merge, so the
    packaged path would not match the on-disk name. The APK deflates the asset
    itself (55.8 MB -> ~24.5 MB inside the zip).
    """
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copyfile(src, dst)


# ---------------------------------------------------------------------------
# Verification + docs
# ---------------------------------------------------------------------------

def probe(db_path):
    """Run the six probe lookups and return {probe: [rows]}."""
    conn = sqlite3.connect(db_path)
    try:
        results = {}
        for term in PROBES:
            results[term] = conn.execute(LOOKUP_SQL, (term,)).fetchall()
        return results
    finally:
        conn.close()


def probe_romaji(db_path):
    """Run the romaji probes and return {query: (kana, loose, expected, exact_rows, loose_rows)}.

    Exactly what the app does with a romaji query: the converted kana looked up, then the loose
    reading looked up, both against the tables the device queries. A probe that cannot answer says
    so loudly — a `loose` column without the index, or one built from a different fold, would
    otherwise only show up on a device.
    """
    conn = sqlite3.connect(db_path)
    try:
        results = {}
        for romaji, kana, loose_form, expected in ROMAJI_PROBES:
            exact = conn.execute(LOOKUP_SQL, (kana,)).fetchall()
            fallback = conn.execute(LOOSE_LOOKUP_SQL, (loose_form,)).fetchall()
            results[romaji] = (kana, loose_form, expected, exact, fallback)
        return results
    finally:
        conn.close()


def romaji_failures(results):
    """The romaji probes that did not answer, each with what was wrong with it."""
    failures = []
    for romaji, (kana, loose_form, expected, exact, fallback) in results.items():
        folded = loose(kana)
        if folded != loose_form:
            failures.append(f"{romaji}: loose({kana}) is {folded}, pinned {loose_form}")
        elif not any(expected in row[1] + row[2] for row in exact + fallback):
            failures.append(f"{romaji}: no row carries {expected}")
    return failures


def _size(n):
    return f"{n:,}"


def _cell(value):
    """Escape pipes so multi-POS / multi-gloss values stay inside the table cell."""
    return str(value).replace("|", "\\|")


def write_data_md(archive_path, archive_sha, names_archive_path, names_archive_sha,
                  asset_path, asset_sha, db_size, entry_count, term_count, loose_count,
                  name_count, results, name_results, romaji_results):
    lines = []
    lines.append("# JMdict data (`jmdict.sqlite`)")
    lines.append("")
    lines.append("On-device Japanese dictionary consumed by the F3 lookup layer. "
                 "Generated by `tools/build_jmdict_db.py` (Python 3 stdlib only).")
    lines.append("")
    lines.append("| Field | Value |")
    lines.append("|---|---|")
    lines.append(f"| Source | jmdict-simplified ({RELEASE_TAG}) |")
    lines.append(f"| Source URL | {SOURCE_URL} |")
    lines.append(f"| Names source URL | {NAMES_URL} |")
    lines.append(f"| Release tag | {RELEASE_TAG} |")
    lines.append(f"| Dictionary version | {DICT_VERSION} |")
    lines.append(f"| Dictionary date | {DICT_DATE} |")
    lines.append(f"| Schema version (`PRAGMA user_version`) | {SCHEMA_VERSION} |")
    lines.append(f"| Archive file | {os.path.basename(archive_path)} |")
    lines.append(f"| Archive size | {_size(os.path.getsize(archive_path))} bytes |")
    lines.append(f"| Archive SHA-256 | `{archive_sha}` |")
    lines.append(f"| Names archive file | {os.path.basename(names_archive_path)} |")
    lines.append(f"| Names archive SHA-256 | `{names_archive_sha}` |")
    lines.append(f"| Asset | `{asset_path}` |")
    lines.append(f"| Asset SHA-256 | `{asset_sha}` |")
    lines.append(f"| Asset size (uncompressed on disk) | {_size(db_size)} bytes |")
    lines.append(f"| `entries` rows | {_size(entry_count)} |")
    lines.append(f"| `terms` rows | {_size(term_count)} |")
    lines.append(f"| `loose` rows (indexed readings, long vowels collapsed) | {_size(loose_count)} |")
    lines.append(f"| `names` rows (proper nouns, JMnedict) | {_size(name_count)} |")
    lines.append(f"| Glosses | every sense of every entry |")
    lines.append(f"| License | {LICENSE_LINE} |")
    lines.append("")
    lines.append("## Probe lookups")
    lines.append("")
    lines.append("Actual rows returned by querying the produced database with the sqlite3 module:")
    lines.append("")
    for term in PROBES:
        rows = results[term]
        lines.append(f"### `{term}`")
        lines.append("")
        if not rows:
            lines.append("_(no rows)_")
            lines.append("")
            continue
        lines.append("| id | kanji | kana | pos | common | gloss |")
        lines.append("|---|---|---|---|---|---|")
        for entry_id, kanji, kana, gloss, pos, common in rows:
            lines.append(
                f"| {entry_id} | {_cell(kanji)} | {_cell(kana)} | {_cell(pos)} | "
                f"{common} | {_cell(gloss)} |"
            )
        lines.append("")
    lines.append("## Name probes (`names` table)")
    lines.append("")
    lines.append("Proper nouns that JMdict itself does not carry — the reason a lookup of "
                 "「池袋弁」 used to resolve to the bare kanji 池.")
    lines.append("")
    for term in NAME_PROBES:
        rows = name_results[term]
        lines.append(f"### `{term}`")
        lines.append("")
        if not rows:
            lines.append("_(no rows)_")
            lines.append("")
            continue
        lines.append("| term | kind | gloss |")
        lines.append("|---|---|---|")
        for kind, gloss in rows:
            lines.append(f"| {_cell(term)} | {_cell(kind)} | {_cell(gloss)} |")
        lines.append("")
    lines.append("## Romaji probes (`loose` table)")
    lines.append("")
    lines.append("What a romaji query resolves to, through both lookups the app runs. The kana is "
                 "what the app's converter (`Wanakana.toHiragana`) makes of the query — the same "
                 "conversions are pinned by `DictionarySearchTest` — and the loose form is "
                 "`loose()` of it, the key the `loose` index is asked for. The build fails unless "
                 "every probe returns a row carrying its expected spelling (the headword of a kanji "
                 "entry, or a reading of a kana-headed one).")
    lines.append("")
    for romaji, kana, loose_form, expected in ROMAJI_PROBES:
        _, _, _, exact, fallback = romaji_results[romaji]
        lines.append(f"### `{romaji}` \u2192 {kana} (loose {loose_form}), must carry {expected}")
        lines.append("")
        lines.append("| pass | id | kanji | kana | pos | common | gloss |")
        lines.append("|---|---|---|---|---|---|---|")
        for pass_name, rows in (("exact", exact), ("loose", fallback)):
            if not rows:
                lines.append(f"| {pass_name} | _(no rows)_ | | | | | |")
            for entry_id, kanji_cell, kana_cell, gloss, pos, common in rows:
                lines.append(f"| {pass_name} | {entry_id} | {_cell(kanji_cell)} | "
                             f"{_cell(kana_cell)} | {_cell(pos)} | {common} | {_cell(gloss)} |")
        lines.append("")
    os.makedirs(os.path.dirname(DATA_MD_PATH), exist_ok=True)
    with open(DATA_MD_PATH, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines).rstrip() + "\n")


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main():
    broken = loose_failures()
    if broken:
        raise SystemExit("loose() no longer folds as pinned: " + "; ".join(broken))

    archive_path = download_archive(SOURCE_URL, ARCHIVE_FILENAME, ARCHIVE_SHA256)
    names_archive_path = download_archive(NAMES_URL, NAMES_ARCHIVE_FILENAME, NAMES_ARCHIVE_SHA256)
    archive_sha = sha256_file(archive_path)
    assert archive_sha == ARCHIVE_SHA256, archive_sha
    names_archive_sha = sha256_file(names_archive_path)
    assert names_archive_sha == NAMES_ARCHIVE_SHA256, names_archive_sha

    work_dir = tempfile.mkdtemp(prefix="jmdict-json-")
    try:
        json_path = extract_json(archive_path, work_dir)
        names_json_path = extract_json(names_archive_path, work_dir)
        db_path = os.path.join(work_dir, "jmdict.sqlite")
        print(f"building sqlite from {os.path.basename(json_path)} ...")
        entry_count, term_count, loose_count = build_sqlite(json_path, db_path)
        print(f"entries: {entry_count}  terms: {term_count}  loose: {loose_count}")

        print(f"adding proper nouns from {os.path.basename(names_json_path)} ...")
        name_count = add_names(db_path, names_json_path)
        print(f"names: {name_count}")

        set_user_version(db_path, SCHEMA_VERSION)

        copy_asset(db_path, ASSET_PATH)
        asset_sha = sha256_file(ASSET_PATH)
        db_size = os.path.getsize(db_path)
        assert os.path.getsize(ASSET_PATH) == db_size, "asset copy is truncated"

        results = probe(db_path)
        missing = [t for t, rows in results.items() if not rows]
        if missing:
            raise SystemExit(f"probe lookups returned nothing: {missing}")

        name_results = probe_names(db_path)
        missing_names = [t for t, rows in name_results.items() if not rows]
        if missing_names:
            raise SystemExit(f"name probes returned nothing: {missing_names}")

        romaji_results = probe_romaji(db_path)
        failures = romaji_failures(romaji_results)
        if failures:
            raise SystemExit("romaji probes did not answer: " + "; ".join(failures))
        print("romaji probes: " + ", ".join(
            f"{romaji}->{results[2]}" for romaji, results in romaji_results.items()))

        write_data_md(archive_path, archive_sha, names_archive_path, names_archive_sha,
                      ASSET_PATH, asset_sha, db_size, entry_count, term_count, loose_count,
                      name_count, results, name_results, romaji_results)
    finally:
        shutil.rmtree(work_dir, ignore_errors=True)

    print(f"wrote {ASSET_PATH}")
    print(f"  sha256          {asset_sha}")
    print(f"  size            {_size(db_size)} bytes")
    print(f"  entries         {_size(entry_count)}")
    print(f"  terms           {_size(term_count)}")
    print(f"  loose           {_size(loose_count)}")
    print(f"  names           {_size(name_count)}")
    print(f"wrote {DATA_MD_PATH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
