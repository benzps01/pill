# Deferred decisions

Things we consciously chose not to build/fix yet, with the reasoning, so
they don't get silently forgotten or re-litigated from scratch later. Add
an entry whenever a "we should really do X properly, but not now" decision
gets made; mark it resolved (don't delete it) once it's actually fixed.

---

## 1. Provider actions leaking into overlay/touch layer — RESOLVED

**Was:** `PillAccessibilityService` (in `overlay`) and `PillTouchView`
called `spotifyProvider?.togglePlayPause()` / `skipNext()` /
`skipPrevious()` directly — a concrete `providers.spotify` import inside
`overlay`, violating CLAUDE.md rule 7 ("no concrete cross-boundary
references") and the module table (`overlay` should only depend on
`core`).

**Fix:** `PillEvent` (`core/model/PillEvent.kt`) now carries its own
optional action closures (`onPlayPause`, `onSkipPrevious`, `onSkipNext`).
`SpotifyProvider` populates them when building its event; `overlay` only
ever calls whatever's on the current winner `PillEvent`, never a concrete
provider method. `PillTouchView` and `PillAccessibilityService` no longer
need to know Spotify exists to route a tap to a transport action.

**Still open:** provider *construction* (item 2 below) wasn't part of this
fix — `PillAccessibilityService` still directly builds `SpotifyProvider`
for lifecycle (`start()`/`stop()`) and its static `appIcon`. That's a
narrower, still-tracked exception.

---

## 2. Concrete provider construction lives in `overlay`, not `app` — OPEN

**What:** `PillAccessibilityService.setUpEventEngine()` directly does
`SpotifyProvider(this, notificationListenerComponent)`, calls
`spotify.start()`/`stop()`, and reads `spotify.appIcon`. Per the module
table, constructing concrete provider classes is `app`'s job ("wiring/DI
only, may depend on all") — `overlay` is supposed to only ever see
`List<EventProvider>`, never a named provider class.

**Why deferred:** there's exactly one provider right now. Building a
registry/DI seam against a single data point risks guessing the wrong
shape (does Calls need the same start/stop lifecycle? Same construction
params?). `AccessibilityService` is also system-instantiated, so "DI into
it" needs its own seam (e.g. a custom `Application` class holding a
provider list PillAccessibilityService reads from at `onServiceConnected`)
— worth designing once there's a second provider to prove it against, not
speculatively now.

**Revisit:** when Calls or Messages (Phase 4 remainder) actually gets
built — that's the point where this stops being a rule-7 technicality and
starts being real duplicated wiring code.

**Touches:** `overlay/PillAccessibilityService.kt` (`setUpEventEngine`,
`spotifyProvider` field), eventually a new `app`-layer wiring class.
