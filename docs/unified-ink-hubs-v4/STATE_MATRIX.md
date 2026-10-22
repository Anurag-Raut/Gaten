# UI state matrix — latest V4

## Anki
| State | Heading/subtitle | Row layout | Navigation |
|---|---|---|---|
| ready | `Choose a deck`, real due total | Due count in accent; zero-deck green check | Due deck rows immediately open session |
| one deck just cleared | Total reflects remaining decks | Completed row optional one-time pale green wash and green check; rest unchanged | No empty review started |
| all clear | `No cards are due right now` + compact green acknowledgment | Every row remains visible with consistent green check and `Clear for now` | Browse only when supported |
| no decks | `No decks found` | No phantom completed rows | Open AnkiDroid/setup |
| loading | No fake zeros | Neutral skeleton matching real geometry | Guard taps |
| offline cached | Label as cached; no newly asserted completion | Stable rows with cache details | Verify offline capability before opening |
| permission error | Accurate actionable permission message | No zero-due masquerade | Retry/setup |

## WaniKani
| State | Reviews row | New cards row | Notes |
|---|---|---|---|
| both available | Due count + next review | Lesson count + next batch | Entire rows tappable; chevrons |
| reviews clear | Green check + `All caught up` + next if known | Lesson due row still tappable | No disabled button |
| lessons clear | Review due row still tappable | Green check + `All caught up` | Do not equate zero lessons with completion today |
| both clear | Both green checks + optional compact green notice | Still shows two rows | No `Back to hub` button inside hub |
| loading | Skeleton or real stale data labeled | Same | Do not show zeros prematurely |
| expired token | Account action + no false counts | Same | Reuse auth flow |
| API error | Retry/error instead of success | Same | Preserve valid cached snapshot only if clearly identified |
| offline cached | Last synced data labeled | Same | Prevent unsafe writes; existing policy |
| locked lessons | Reviews act normally | `Lessons unavailable` with truthful entitlement | Don't imply completed |

## Success semantics
A *cleared queue* means verified **0 actionable now**. A *completed session* means the runtime tracked a completion event. They are not synonymous. Only use completed-count statistics or congratulatory wording where real completed actions are recorded. Refresh provider data after a successful session before deriving clear queues. Avoid network writes while testing prototype fixtures.
