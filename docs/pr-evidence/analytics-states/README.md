# Analytics state design evidence

Captured on 25 October 2026 on a clean Android 14 x86_64 emulator with AnkiDroid absent and no WaniKani token. No account data was cleared or disconnected.

| View | Before | After |
|---|---|---|
| Dark, top | [Before](before-dark.png) | [After](after-dark-top.png) |
| Dark, scrolled | — | [After](after-dark-scrolled.png) |
| Light | — | [Top](after-light-top.png), [scrolled](after-light-scrolled.png) |
| 320 dp, 160% text | — | [Top](narrow-large-text-top.png), [scrolled](narrow-large-text-scrolled.png) |

All five supplied motifs render with the resolved accent. Setup actions appear only in the two source summaries; downstream sections retain their headings, artwork and state-specific copy. Text wraps at 320 dp/160%, and the final section scrolls above navigation.

The screenshots reflect the final source changes in this PR. `./gradlew :app:assembleDebug --console=plain` passed; the installed APK hash matched the build output (`8221fa03734fd13e9c5aee9ca57a5ce314a110747890254586c2d27395adf04b`). TypeScript and Android JS bundling passed. Display size and font scale were restored after the narrow-screen capture.

Not exercised at runtime: connected, permission, offline, cached, confirmed-zero states, other accents, and TalkBack. Connected charts and provider logic are retained; no preview sample data is included.
