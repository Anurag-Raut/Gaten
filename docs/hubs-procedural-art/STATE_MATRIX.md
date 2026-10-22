# Queue and completion state matrix

## Anki

| State | Display | User action | Completion truth |
|---|---|---|---|
| Ready | Current Anki deck picker; abstract thumbnail per deck; actual new/learning/review counts; accent due number | Tap a due deck row | Do not claim completion |
| One deck clear | Same deck row + same artwork, check and `Clear for now`; other due decks remain active | Open other available decks | Zero due != studied today |
| Returned after completed session | Brief `Deck complete · [name]` acknowledgement if session receipt exists; row remains clear | Continue other decks | Completion only if confirmed |
| All decks clear | Compact `You're all caught up` acknowledgement; all rows remain and show check | Wait for future due or browse if supported | Show only after a successful authoritative provider refresh confirms all zero; a cached zero during refresh is not confirmation |
| Loading | Neutral skeleton rows including art placeholders | Wait | Unknown never zero |
| Refreshing | Keep cached deck list/art, subtle refresh label | Normal safe navigation | Do not reset artwork |
| Offline cache | Cached rows and stale message | Only if provider allows | No false 'all clear' |
| No cache | Offline or permission-specific state | Retry / Anki settings | Never guess zero |
| No decks | `No decks found` | Open AnkiDroid/help | Distinct from all clear and from an explicitly empty study-deck selection |

## WaniKani

| Reviews | Lessons | Display |
|---|---|---|
| Ready | Ready | Two borderless open vertical sections, split metrics and two neutral CTAs |
| Clear | Ready | Compact violet/accent check in Reviews and next-review time if known; Lessons remains actionable |
| Ready | Clear | Reviews actionable; New cards gets accent check and `No new lessons available` |
| Clear | Clear | Both sections stay visible; calm `You're all caught up` note, no false buttons |
| Loading | Loading | Structure-preserving skeletons |
| Error/offline | Ready | Sections independent; preserve available section and report error in affected section |
| Ready | Entitlement locked | Reviews CTA stays; Lessons shows grounded locked/no-access explanation |

**Never** use demo counts `5/14/23/77` as app defaults. Never treat fetched `0` as proof of a just-finished session. Never report `77 lessons studied` merely because that many were available. Verify WaniKani API writes and Anki provider state separately.

## Completion screen vs hub

The separate full-screen moment-of-completion design (`references/SESSION-completion-selected.png`) is shown only after actual completed sessions. When the user returns to a hub, use refreshed authoritative counts and a quiet persistent status. Avoid forced delay or making the hub itself look like an unrelated completion screen.
