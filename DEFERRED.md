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

---

## 3. Reference-app features deliberately outside v1 — OPEN (by design)

**What:** `references/` holds 22 screenshots of **Material Capsule (MT
Capsule)**, a shipping app in this category, captured to mine for ideas.
Its overlay layout (`img21`) and its user-assignable gestures (`img5`–`img7`)
were both adopted. A large part of the rest was not, and this entry records
which, so it doesn't get re-litigated every time someone opens the folder:

- **Mini Capsule Events** (`img2`–`img4`): charging started/stopped, low
  battery, wifi connected/disconnected, headphones connected/disconnected,
  airplane mode on/off, volume mode, brightness mode, USB mounted/unmounted,
  device unlock, third-party AirPods battery.
- **Dynamic Cards** (`img5`): flashlight control, volume control,
  timer/stopwatch, generic progress indication, charging indicator.
- **Generic per-app notification cards** (`img8`) — arbitrary apps in the
  capsule, with auto-hide delay and auto-remove-after-viewing.
- **Show Dynamic Cards on lock screen** (`img11`).

**Why deferred:** CLAUDE.md's scope discipline is explicit — v1 is exactly
three providers (Calls, Messages, Spotify), and torch/quick actions and
widgets are named as deferred in the build plan for a reason. Roughly 60% of
what MT Capsule does falls outside that line. Building any of it now would
mean Calls and Messages — the two providers v1 actually promises — slip
further.

**Revisit:** after v1 ships all three providers and Phase 6 polish lands.
Not a backlog to start pulling from opportunistically.

---

## 4. Scrubber seek-by-drag — OPEN

**What:** the scrubber card renders position but isn't draggable; you can't
seek from the pill.

**Why deferred:** build plan §2 does list "seek" among Spotify's
interactions, but Phase 4's Verify gate doesn't ("controls actually drive
playback; scrubber tracks without polling; art loads and is cached"), so it
isn't gating the phase. It also wants real design thought — a drag on a
44dp-tall card needs to not fight the swipe-up/swipe-down bindings that
share the same window, which is a gesture-arbitration problem, not just a
`transportControls.seekTo()` call.

**Revisit:** once the gesture bindings have been lived with on-device and
it's clear how a drag should coexist with the vertical swipe axis.

**Touches:** `overlay/PillView.kt` (`drawScrubberCard` — needs to expose the
track's hit rect), `overlay/PillTouchView.kt`, `core/model/PillEvent.kt`
(an `onSeekTo: ((Long) -> Unit)?` closure, same pattern as the transport
callbacks), `providers/spotify/SpotifyProvider.kt`.
