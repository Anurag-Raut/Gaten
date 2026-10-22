# WaniKani hub state matrix

| Demo state | Reviews | New cards | Behavior |
|---|---|---|---|
| Both available | 23 ready | 77 ready | Both CTAs active |
| Reviews done | Calm complete | 77 ready | Lesson CTA only |
| Lessons done | 23 ready | Calm complete | Review CTA only |
| Both complete | Calm complete | Calm complete | No CTA; known next time optional |
| Initial loading | Skeleton | Skeleton | No CTA, no fake zeros |
| Refreshing cache | Show confirmed 23 | Show confirmed 77 | Mark refreshing; preserve layout |
| Disconnected | Unknown | Unknown | Connect account |
| Token expired | Unknown | Unknown | Reauthorize |
| Offline with cache | Stale 23 | Stale 77 | No false live claim, action depends on supported offline workflow |
| Offline without cache | Unknown | Unknown | Retry; no zeros |
| Sync error | Unknown | Unknown | Retry |
| Lessons locked | 23 ready | Locked | Reviews still available |
| Partial data | 23 ready | Unknown | Review CTA preserved, lesson Retry |
| Paused session | 17 remaining | 77 ready | Resume saved review with correct session data |

Use state-specific error copy; omit unavailable metadata. Fake counts in demo are illustrative only. If no real next-hour count, omit it.
