# Romaji search research

Validated 2026-10-16 against `wanakana-kt` 1.1.1 (MIT, `dev.esnault.wanakana:wanakana-core`) and the shipped `jmdict.sqlite` (499,343 rows in `terms`).

## Verified conversion behavior (`Wanakana.toHiragana`)

| Input | Output | Note |
|---|---|---|
| neko, taberu, sushi, sumimasen | ねこ, たべる, すし, すみません | clean |
| gakkou, arigatou, ohayou, toukyou | がっこう, ありがとう, おはよう, とうきょう | doubled vowels correct |
| si/shi, ti/chi, tu/tsu, hu/fu, jyugyou/jugyou | し, ち, つ, ふ, じゅぎょう | Kunrei and Hepburn both accepted |
| shin'you / shinyougo (apostrophe or `nn`) | しんよう | ん disambiguation needs a separator |
| shinyou (no separator) | しにょう | **wrong** — parse ambiguity, accepted limitation |
| wo, n | を, ん | |
| konnichiwa | こんにちわ | matches JMdict's alternate reading こんにちわ |
| tokyo | ときょ | **long vowel omitted** |
| ramen | らめん | **long vowel omitted** |
| ohayo, gakko, arigato | おはよ, がっこ, ありがと | **long vowel omitted** |

The most common user spellings are exactly the shortened ones, so exact kana matching alone fails.

## Decision: dual-form matching

1. **Query pipeline.** If the query is ASCII romaji, convert with `Wanakana.toHiragana`, then search with both the exact kana form and a "loose" form.
2. **Loose normalization** (same pure function applied to query and to every indexed reading):

```text
loose(kana):
  katakana → hiragana          # fixed Unicode offset
  remove ー
  drop う after row-u kana (くすつぬふむゆるゆ ゅ う)
  drop う after row-o kana (こそとのほもよろをぉょ), おお→お, ええ→え
  drop あ after row-a kana (かさたなはまやらわゃ)
  drop い after row-i kana (きしちにひみりゅ)
```

Verified matches on real dictionary data:

| Query | Converted | Loose | Dictionary reading → loose |
|---|---|---|---|
| tokyo | ときょ | ときょ | 東京 とうきょう → ときょ |
| ramen | らめん | らめん | ラーメン / らーめん / らあめん → らめん |
| ohayo | おはよ | おはよ | おはよう → おはよ |
| gakko | がっこ | がっこ | がっこう → がっこ |
| arigato | ありがと | ありがと | ありがとう → ありがと |
| saikou | さいこう | さいこ | さいこう / サイコー → さいこ |
| kirei | きれい | きれい | きれい (kept; えい not collapsed) |
| byouin | びょういん | びょいん | びょういん → びょいん (consistent both sides) |

Both sides use the identical function, so imperfect linguistic collapses stay consistent.

3. **Index.** `entries` stores multiple space-separated readings (`ねこ ネコ`). The build script must emit one loose-normalized reading per reading into a new `kana_loose` column (or a separate index table) so prefix search can use it; loose search ranks below exact kanji/kana matches.

## Rejected options
- `jaconv` (Python only, would need a port) — wanakana-kt is already JVM/Kotlin.
- Inserting ん before `y` clusters to rescue `shinyou` — cannot disambiguate しにょう vs しんよう in general; apostrophe and `nn` work today and the app will hint this.
- Normalizing only the query and not the index — prefix search misses entirely (とうきょう does not start with ときょ).
