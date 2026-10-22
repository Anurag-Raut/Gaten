#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Build the on-device example-sentence asset used by Gaten's review answer.

Downloads the Tanaka Corpus (`examples.utf.gz`, the example-sentence file published with JMdict by
the Electronic Dictionary Research and Development Group), verifies its SHA-256, and converts it
into a compact SQLite database at `app/src/main/assets/db/examples.sqlite`.

Every source line is a Japanese sentence, its English translation, and a word-level annotation of
the sentence:

    A: 同じことを何回繰り返すの。…	You're wasting your time… #ID=280278_123717
    B: 同じ[01] 事(こと){こと} を 何回 繰り返す の … 説明 は 勘弁~ 為る(する){して} よ[01]

The `B:` line's tokens carry the dictionary form the reader's card is spelled with (the part before
`(`/`[`/`{`, with a trailing `~` dropped) and, in parentheses, the reading. Both are indexed so a
card can be answered from either its expression or its reading. Single-kana tokens are particles and
auxiliaries, so they are left out; that keeps the index to content words without needing a
hand-maintained stop list.

The database ships uncompressed as `assets/db/examples.sqlite`, the same convention the dictionary
asset follows (AGP expands `.gz` assets during asset merge, so the packaged name is the plain one).
`ExampleRepository` copies it verbatim on first use.

Stdlib only (gzip, sqlite3, hashlib, urllib) — no pip installs.

Usage:
    python3 tools/build_examples_db.py
"""

import gzip
import hashlib
import os
import re
import shutil
import sqlite3
import sys
import tempfile
import urllib.request

# ---------------------------------------------------------------------------
# Frozen source
# ---------------------------------------------------------------------------

SOURCE_URL = "http://ftp.edrdg.org/pub/Nihongo/examples.utf.gz"
ARCHIVE_FILENAME = "examples.utf.gz"
#: SHA-256 of the downloaded corpus, pinned so corruption (or a silent upstream swap) is caught.
#: The corpus is a living file, so an upstream update is a deliberate, reviewable change here.
ARCHIVE_SHA256 = "c1283036f130e0459bcc3df7ff245d09dc789210671dfd5bd07b549069ed37a9"

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSET_PATH = os.path.join(REPO_ROOT, "app", "src", "main", "assets", "db", "examples.sqlite")
DATA_MD_PATH = os.path.join(REPO_ROOT, "docs", "jp-prototype", "dictionary", "EXAMPLES.md")
DOWNLOAD_CACHE = os.path.join(tempfile.gettempdir(), "gaten-examples-build-cache")

LICENSE_LINE = (
    "Tanaka Corpus example sentences © Electronic Dictionary Research and Development Group "
    "(EDRDG), CC BY-SA 4.0"
)

SCHEMA_VERSION = 1

SCHEMA = """
CREATE TABLE sentences(
  id       INTEGER PRIMARY KEY,
  japanese TEXT NOT NULL,
  english  TEXT NOT NULL
);
CREATE TABLE sentence_terms(
  term        TEXT NOT NULL,
  sentence_id INTEGER NOT NULL
);
CREATE INDEX idx_sentence_terms ON sentence_terms(term);
"""

#: The query the device runs: an exact term, shortest sentence first (a short example reads as an
#: example; a long one reads as a paragraph). `ExampleRepository` holds the same statement.
LOOKUP_SQL = """
SELECT s.japanese, s.english
FROM sentence_terms t JOIN sentences s ON s.id = t.sentence_id
WHERE t.term = ?
ORDER BY length(s.japanese) ASC, s.id ASC
LIMIT ?
"""

#: Words a build must be able to answer. The corpus stores the dictionary form while the sentence
#: shows the inflected one (食べる is indexed, 食べたい is displayed), so the probe is that the term
#: has rows at all — an empty index is the failure worth catching.
PROBES = ("勘弁", "猫", "水", "学校", "食べる", "ありがとう")

#: Longest sentence stored. A handful of corpus lines run to hundreds of characters; they are not
#: usable as a review example and only bloat the asset.
MAX_SENTENCE_CHARS = 220

#: Tokens in the `B:` line are separated by whitespace.
TOKEN_SPLIT = re.compile(r"\s+")
#: A token's dictionary form is everything before its first reading/sense/inflection marker.
BASE_SPLIT = re.compile(r"[\(\[\{]")
#: The reading inside a token, e.g. 会う(あう).
READING = re.compile(r"\(([^)]+)\)")
#: The spelling used in the sentence, e.g. 有難う{ありがとう}. A kanji word the sentence writes in
#: kana only carries its kana here, so a card spelled ありがとう needs this to be reachable.
INFLECTION = re.compile(r"\{([^}]*)\}")

KANA_RANGE = ((0x3040, 0x30FF), (0x31F0, 0x31FF))


def is_kana(text):
    return bool(text) and all(
        char == "\u30fc" or any(lo <= ord(char) <= hi for lo, hi in KANA_RANGE)
        for char in text
    )


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_archive():
    """Return the path of the corpus, downloading and verifying it once into a temp cache."""
    os.makedirs(DOWNLOAD_CACHE, exist_ok=True)
    path = os.path.join(DOWNLOAD_CACHE, ARCHIVE_FILENAME)
    if os.path.exists(path) and sha256_file(path) == ARCHIVE_SHA256:
        print(f"archive cache hit: {path}")
        return path
    print(f"downloading {SOURCE_URL}")
    tmp_path = path + ".part"
    with urllib.request.urlopen(SOURCE_URL) as response, open(tmp_path, "wb") as out:
        shutil.copyfileobj(response, out)
    actual = sha256_file(tmp_path)
    if actual != ARCHIVE_SHA256:
        os.remove(tmp_path)
        raise SystemExit(f"SHA-256 mismatch: expected {ARCHIVE_SHA256}, got {actual}")
    os.replace(tmp_path, path)
    print(f"verified SHA-256 {actual}")
    return path


def sentence_terms(annotation):
    """The content-word forms a sentence's `B:` line carries, as a set.

    A token is `surface(reading)[sense]{inflection}`, `surface(#entryid)`, `surface{inflection}` or
    just `surface`. The dictionary form is the surface before the first marker; a trailing `~` means
    the corpus used it as a prefix (勘弁~ for 勘弁する) and is dropped. Both the form and its reading
    are indexed. Tokens whose form is a single kana are particles or auxiliaries — indexing them
    would put nearly every sentence under は/を/に — so they are left out.
    """
    terms = set()
    for raw in TOKEN_SPLIT.split(annotation):
        token = raw.rstrip("~")
        if not token:
            continue
        base = BASE_SPLIT.split(token, 1)[0]
        if base and not (len(base) == 1 and is_kana(base)):
            terms.add(base)
        match = READING.search(token)
        if match:
            reading = match.group(1).rstrip("~")
            if reading and not (len(reading) == 1 and is_kana(reading)):
                terms.add(reading)
        inflection = INFLECTION.search(token)
        if inflection:
            spelling = inflection.group(1).rstrip("~")
            # Only a kana spelling: an inflected kanji form (会う{会えない}) is not a dictionary form
            # and would add noise, while a kana spelling of a kanji word is the only way a card
            # spelled that way can be found.
            if len(spelling) >= 2 and is_kana(spelling):
                terms.add(spelling)
    return terms


def parse_corpus(gz_path):
    """Yield (japanese, english, terms) for every complete A/B pair in the corpus."""
    pending = None
    with gzip.open(gz_path, "rt", encoding="utf-8") as fh:
        for line in fh:
            line = line.rstrip("\n")
            if line.startswith("A: "):
                body = line[3:]
                if "\t" not in body:
                    pending = None
                    continue
                japanese, rest = body.split("\t", 1)
                english = rest.split("#ID=", 1)[0].strip()
                japanese = japanese.strip()
                if not japanese or not english or len(japanese) > MAX_SENTENCE_CHARS:
                    pending = None
                    continue
                pending = [japanese, english]
            elif line.startswith("B: ") and pending is not None:
                terms = sentence_terms(line[3:])
                if terms:
                    yield pending[0], pending[1], terms
                pending = None


def build_sqlite(gz_path, db_path):
    if os.path.exists(db_path):
        os.remove(db_path)
    conn = sqlite3.connect(db_path)
    try:
        conn.execute("PRAGMA journal_mode=OFF")
        conn.execute("PRAGMA synchronous=OFF")
        conn.executescript(SCHEMA)
        sentence_count = 0
        term_count = 0
        for japanese, english, terms in parse_corpus(gz_path):
            cursor = conn.execute(
                "INSERT INTO sentences(japanese, english) VALUES(?,?)", (japanese, english)
            )
            sentence_id = cursor.lastrowid
            conn.executemany(
                "INSERT INTO sentence_terms(term, sentence_id) VALUES(?,?)",
                [(term, sentence_id) for term in sorted(terms)],
            )
            sentence_count += 1
            term_count += len(terms)
            if sentence_count % 20000 == 0:
                print(f"  {sentence_count} sentences…")
        conn.commit()
        # Rebuild the file compactly: the page churn from 1.2M index rows leaves the file ~2 MB
        # larger than it needs to be, and the asset is copied verbatim to every device.
        conn.execute("VACUUM")
    finally:
        conn.close()
    return sentence_count, term_count


def set_user_version(db_path, version):
    """Stamp the schema version as `user_version`, so a stale extracted copy is replaced."""
    conn = sqlite3.connect(db_path)
    try:
        conn.execute(f"PRAGMA user_version = {version}")
        conn.commit()
    finally:
        conn.close()


def probe(db_path):
    """Run the probe lookups and return {probe: [rows]}; a probe with no rows fails the build."""
    conn = sqlite3.connect(db_path)
    try:
        return {term: conn.execute(LOOKUP_SQL, (term, 3)).fetchall() for term in PROBES}
    finally:
        conn.close()


def copy_asset(src, dst):
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copyfile(src, dst)


def write_data_md(db_path, sentence_count, term_count):
    os.makedirs(os.path.dirname(DATA_MD_PATH), exist_ok=True)
    size_mb = os.path.getsize(db_path) / (1024 * 1024)
    lines = [
        "# Example-sentence data",
        "",
        "Generated by `tools/build_examples_db.py` from the Tanaka Corpus.",
        "",
        f"- Source: `{SOURCE_URL}` (SHA-256 `{ARCHIVE_SHA256}`)",
        f"- Licence: {LICENSE_LINE}",
        f"- Asset: `app/src/main/assets/db/examples.sqlite` ({size_mb:.1f} MB, schema v{SCHEMA_VERSION})",
        f"- Sentences: {sentence_count:,}",
        f"- Indexed word rows: {term_count:,}",
        "",
        "The device queries `sentence_terms` by the card's expression or reading and joins the",
        "sentence, shortest first. Nothing is fetched at runtime; the asset is the whole source.",
        "",
        "## Probes",
        "",
        "| Word | First example |",
        "| --- | --- |",
    ]
    rows = probe(db_path)
    for term in PROBES:
        hits = rows[term]
        if not hits:
            raise SystemExit(f"probe {term} has no rows")
        japanese, english = hits[0]
        lines.append(f"| {term} | {japanese} — {english} |")
    lines.append("")
    with open(DATA_MD_PATH, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))


def main():
    gz_path = download_archive()
    work_dir = tempfile.mkdtemp(prefix="gaten-examples-")
    try:
        built = os.path.join(work_dir, "examples.sqlite")
        sentence_count, term_count = build_sqlite(gz_path, built)
        set_user_version(built, SCHEMA_VERSION)
        rows = probe(built)
        for term in PROBES:
            if not rows[term]:
                raise SystemExit(f"probe {term} has no rows")
        copy_asset(built, ASSET_PATH)
        write_data_md(built, sentence_count, term_count)
    finally:
        shutil.rmtree(work_dir, ignore_errors=True)
    print(f"wrote {ASSET_PATH}")
    print(f"{sentence_count:,} sentences, {term_count:,} indexed rows")
    print(f"licence: {LICENSE_LINE}")


if __name__ == "__main__":
    sys.exit(main())
