# Recent-lookup gloss capped at two lines — rendered evidence

Home's "Recently looked up" rows, base build (`e2a537f`) against the fix (`9ea06c9`), captured on the
same emulator with the same three stored lookups.

| Item | Value |
|---|---|
| Change | `src/rn/ui/home/HomeDashboard.tsx` — `RecentLookupRow`'s gloss `<Text>` gains `numberOfLines={2}` (1 insertion, 0 deletions; the two `lookupGloss` styles are untouched) |
| Head | `9ea06c95da22649927ffa6fc8e089230e8531ed4` (`fix(ui): cap the recent-lookup gloss at two lines`) |
| Base | `e2a537f8a63799bf8d146e80d946d04b6e43d1dc` — `origin/dev`, *feat: add downloadable on-device OCR engines* |
| Build (base) | `ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL in 2m 43s` (281 tasks) with `HomeDashboard.tsx` at its `e2a537f` contents |
| Build (head) | `ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL in 34s` (43 tasks executed) on `9ea06c9` |
| APK (base) | `app/build/outputs/apk/debug/app-x86_64-debug.apk`, 205,468,244 B, SHA-256 `90f85816e7e413cd8a13f97ff2662be04f4d410d85ee8d219f7cb6352452d764` |
| APK (head) | `app/build/outputs/apk/debug/app-x86_64-debug.apk`, 208,870,283 B, SHA-256 `406e25ebdc1f6374507e5d290508200273c2b6184cca3c5a1da055e9bb42ee4b` |
| JS inside those APKs | `assets/index.android.bundle` (Hermes bytecode) — base SHA-256 `a46a87bc579b1955afed5cc87d70723004603ac6b0920f869f44942e00ed2c0c`, head `9f1f6a80cdbf6b1dd139a6bb1e0a1d33e504e6bb4521dee0cd016abe6b8c8aae` |
| Bundle diff | Disassembled with `hermesc -b -dump-bytecode`: 3 hunks over 592,670 lines — the HBC source hash, and inside `RecentLookupRow` the gloss `<Text>` props object literal (`GetById "lookupGloss"` / `"lookupGlossStacked"` / `"onSurfaceVariant"`) gains one constant entry, shifting its two computed props from slots 0,1 to 1,2 |
| Device | `emulator-5554`, AVD `Gaten_Heading_Evidence` (`sdk_gphone64_x86_64`, Android 14 / API 34, `x86_64`), 1080×2340 @ 440 dpi, **dark** theme, text scale 1.0 and 1.3 (see below) |
| Services | None connected — Anki *Not connected*, WaniKani *Not connected*, 0-day streak. The three stored lookups are the only app state that feeds the section |
| Driver | agent-device 0.21.31 (`open`, `screenshot`, `snapshot --json`); the scroll before the "bottom" stills is a plain `adb shell input swipe 540 1900 540 1000 300`, not a driver gesture |
| Restored state | `font_scale` was 1.3 on this AVD before the run and is 1.3 after it |

## What the captures show

| File | What it shows |
|---|---|
| `before-01-home-default-text.png` | Base, Home at text scale 1.0, scrolled to the top: 奥/おく draws an **8-line** gloss, 仄聞/そくぶん 3 lines, and 側/そば runs off under the floating tab bar |
| `after-01-home-default-text.png` | Head, identical state: every gloss is **2 lines with a tail ellipsis** and the three rows are the same height |
| `before-02-home-text-scale-1.3.png` | Base, text scale 1.3 (the stacked layout) scrolled to the top: 奥's gloss is 4 full-width lines |
| `after-02-home-text-scale-1.3.png` | Head, identical state: 奥 and 仄聞 are 2 lines each |
| `before-03-home-default-text-bottom.png`, `after-03-home-default-text-bottom.png` | The pair behind the first composite — both scrolled to the page bottom, so 側/そば is shown whole in both builds (the base page scrolls further, because its rows are taller) |
| `before-04-home-text-scale-1.3-bottom.png`, `after-04-home-text-scale-1.3-bottom.png` | The same pair at text scale 1.3 |
| `before-after-recent-rows-default-text.png` | **The claim in one still**, text scale 1.0: the base section (top) with 8- and 3-line glosses over the fixed section (bottom) with three uniform 2-line rows |
| `before-after-recent-rows-text-scale-1.3.png` | The same still pair at text scale 1.3, where the clamp also applies to the full-width stacked gloss |
| `before-after-home-default-text.png` | The two full Home screens side by side (base left, head right) — the surrounding screen is otherwise identical |
| `before-0*.ax.json`, `after-0*.ax.json` | The raw accessibility trees the stills were taken with; every row's rect is in them |

## Measurements

Row rects read from the accessibility tree of the bottom-scrolled stills (device px at 440 dpi; 58 dp of
`minHeight` is 159.5 px here). Values are the row's own `accessible` container, so they include its
`paddingVertical`.

| Row | text scale 1.0, base | text scale 1.0, head | text scale 1.3, base | text scale 1.3, head |
|---|---|---|---|---|
| 奥 / おく | 401 px | 159 px | 392 px | 276 px |
| 仄聞 / そくぶん | 165 px | 160 px | 277 px | 277 px |
| 側 / そば | 444 px | 159 px | 389 px | 273 px |
| **section total** | **1010 px** | **478 px** | **1058 px** | **826 px** |

At text scale 1.0 the fixed rows all sit on the 58 dp floor (159–160 px), which is the rhythm the
defect broke. 仄聞 is the control: its three-line gloss already fitted inside the floor, so its row
height barely moves (165 → 160 px) while its text still drops to two lines and gains the ellipsis.
At text scale 1.3 the stacked row is taller than the floor, so each long gloss costs real height there
too (392 → 276 px).

## Method

```sh
# base APK: the same worktree with HomeDashboard.tsx at its e2a537f contents
git checkout e2a537f -- src/rn/ui/home/HomeDashboard.tsx
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug      # → BUILD SUCCESSFUL in 2m 43s
cp app/build/outputs/apk/debug/app-x86_64-debug.apk /tmp/gaten-2line/before.apk
git checkout 9ea06c9 -- src/rn/ui/home/HomeDashboard.tsx
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug      # → BUILD SUCCESSFUL in 34s
cp app/build/outputs/apk/debug/app-x86_64-debug.apk /tmp/gaten-2line/after.apk

# the three lookups the section reads, made once through Home's own search field
# (側 → the そば entry, 奥, そくぶん → 仄聞); the history survives `install -r`, so
# both builds render the same three rows in the same order.
adb install -r -d /tmp/gaten-2line/before.apk
agent-device open com.gaten --serial emulator-5554 --foreground
# …per still: set font_scale, force-stop, reopen, optionally swipe to the bottom,
#    then agent-device screenshot --out <still>.png and snapshot --json > <still>.ax.json
adb install -r -d /tmp/gaten-2line/after.apk          # -r keeps the stored lookups
```

`capture-log.txt` lists the same steps with the values this run actually used.

## Not exercised / limitations

- **Not one of the two entries the task named.** The brief asks for 側 / そば and 次間 / そくぶん. `次間`
  matches no entry in the bundled JMdict; its reading そくぶん resolves to 仄聞 側聞
  (`id 1596500`, *hearing casually, hearing by chance, learning by hearsay*), whose gloss is short and
  cannot grow past the 58 dp floor at text scale 1.0. That entry is captured as the control row and the
  second long-gloss entry is 奥 / おく (`id 1179320`, 152-character first gloss) instead. 側 / そば
  (`id 1403830`, 140-character first gloss, the entry the report quotes) is unchanged.

  The three rows the section renders come from `shared_prefs/gaten_dictionary_history.xml`
  (`recent_lookups`, newest first): `1179320` 奥 / おく, `1596500` 仄聞 / そくぶん, `1403830` 側 / そば —
  the same file, unchanged, feeds both builds because `install -r` keeps app data.
- **A deliberate earlier fix is being reversed.** `e3412e9` (*fix(ui): hold the disabled fill and stop
  clipping lookup definitions*) removed exactly this cap — `numberOfLines={stacked ? undefined : 2}`
  became no cap at all, on the argument that 取る's definition was "cut off mid-list". This change puts
  a 2-line cap back and extends it to the stacked layout as well, on the argument that unbounded rows
  break the list rhythm. Whoever reviews this owns that call; the stills show both behaviours.
- **Only dark theme.** The emulator's current night mode is on; light theme was not captured.
- **Text scale 1.0 and 1.3 only.** 1.3 is the value that crosses the `fontScale > 1.2` stacked
  threshold; 1.25 and larger scales were not sampled.
- **Not exercised:** tapping a row, the "See all" collapsible remainder beyond three rows, TalkBack
  output (the `accessibilityLabel` still carries the full gloss — that is unchanged by this edit and is
  not visible in a still), rotation, and any other device or ABI.
- **The two stills are not pixel-identical outside the section.** The status-bar clock advances between
  cold starts and the base page scrolls further than the head page because its rows are taller, so the
  composites frame each panel on its own "Recently looked up" heading rather than on a shared offset.
