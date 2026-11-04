# WaniKani hub visuals — capture provenance

## Provenance

| Field | Value |
| --- | --- |
| Head SHA (tree the APKs were built from) | `169e156` (`src/rn/ui/review/WaniKaniHome.tsx` change; this docs commit adds only the captures and this README) |
| Base | `e2a537f` (`origin/dev`) |
| Change | WaniKani hub: lesson-budget bar under the Today pair; Reviews/Lessons `StackedBar` + `CountLegend` under "Available study" |
| Build command (after) | `ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug -PreactNativeArchitectures=x86_64` → `BUILD SUCCESSFUL` |
| Build command (before) | same without the arch flag, in a throwaway worktree of `e2a537f` → `BUILD SUCCESSFUL` |
| APK (after) | `app/build/outputs/apk/debug/app-x86_64-debug.apk`, 205,469,616 bytes, sha256 `7ab7b7e78f065cbec249abef843b3b6acc2d381fdba447950c2ccca2bec766c6` |
| APK (before) | same path in the throwaway worktree, 205,468,380 bytes, sha256 `efa2e26502d2d468a3e4733d46a1da917c70f1d45ff54269b2820f1272c42b3f` |
| Device | AVD `Gaten_Analytics_Capture` → `emulator-5556`, Android 14 (API 34), x86_64, 1080×2340 @ 440 dpi, font scale 1.0 |
| Themes | Dark (`cmd uimode night yes`) and light (`night no`) |
| Services | WaniKani: **no account** — no stored token (`no_backup/wanikani` absent), Home card reads "Not connected". AnkiDroid: not installed on this AVD |
| Driver | agent-device 0.21.31 (`open` / `find` / `click` / `back` / `screenshot`), serial-scoped to `emulator-5556` |

## What the captures show

The reachable WaniKani surface without an account, before and after, same AVD, same app data,
same navigation path (Home → WaniKani card):

- `home-before-{dark,light}.png` / `home-after-{dark,light}.png` — Home with the WaniKani card in
  its "Not connected" state.
- `wk-connect-before-{dark,light}.png` / `wk-connect-after-{dark,light}.png` — the WaniKani
  connection screen the card leads to.

Before and after are pixel-identical in dark (`compare -metric AE`: 0 differing pixels on both
screens) and differ only in the status-bar clock in light (227 px of 2,524,800, 0.009%). That is
the intended result of this PR **for the states reachable without an account**: every new visual is
gated on confirmed data — a connected account with a confirmed snapshot, or a known today figure —
so a disconnected device renders exactly what it rendered before, with no empty or misleading chart.

## Not exercised / limitations

- **The hub page itself could not be opened.** Opening the hub requires a stored WaniKani
  credential; the AVD carries none, and no test account was available. The token is stored
  keystore-encrypted, so a credential cannot be seeded from the shell.
- Therefore **the new visuals — the lesson-budget bar, the Reviews/Lessons distribution bar and
  legend, their data, all-clear and paused variants, and the one-shot entrance animation — were
  not exercised on-device.** No recording was captured, since the only motion this PR adds lives
  behind the same gate.
- The change is verified at the levels that are reachable: TypeScript typecheck, the six green
  node test suites, the Kotlin unit tests, and the debug build (see the PR body's Verification
  section).
- Build note for reproducing: `node_modules` is shared between worktrees via hard links here, and
  two concurrent Gradle builds over it left non-identical duplicate `libworklets.so` intermediates
  in `react-native-gesture-handler` and `react-native-worklets`, which fails `mergeDebugNativeLibs`.
  Cleaning both modules' `android/build` directories and building serially resolves it. This is a
  local-build hazard, not a repo change.
