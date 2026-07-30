# Dynamic Pill

A personal "Dynamic Island"-style overlay for a Pixel 8 Pro. It draws a pill
over the camera cutout that reacts to Spotify playback, calls, and Google
Messages — tap to expand, swipe to collapse, gestures configurable.

Single device, sideloaded, but architected so more devices and providers drop
in without rewrites.

**Kotlin · Classic Views (overlay) · Compose (settings) · minSdk 34**

| Doc | What it's for |
|---|---|
| `dynamic-pill-build-plan.md` | Full spec and the phase roadmap with Verify gates |
| `CLAUDE.md` | Enforceable guardrails — never poll, no wakelocks, freeze on screen-off, … |
| `design.md` | Per-provider, per-state rendering: the design source of truth |
| `DEFERRED.md` | Decisions consciously postponed, with reasoning |

Read `CLAUDE.md` and the build plan before picking this up again.

---

## Progress

| Phase | Status |
|---|---|
| 0 · Overlay pipeline | ✅ Done |
| 1 · State machine + springs | 🟡 Built, frame-timing gate unverified |
| 2 · Cutout anchoring + accessibility | 🟡 Built, reboot gate unverified |
| 3 · Event engine | ✅ Done |
| 4 · Providers — Spotify | ✅ Done |
| 4 · Providers — Calls | ⬜ Not started |
| 4 · Providers — Messages | ⬜ Not started |
| 5 · Settings app | ⬜ Not started |
| 6 · Polish | ⬜ Not started |

**Two Verify gates are outstanding** and block calling Phases 1–2 closed:

- **Phase 1** — "zero dropped frames in the profiler during expand/collapse,
  mid-animation tap retargets with no snap." Never formally measured, and the
  expanded state has since gained two per-frame `Path` rebuilds (accent blob,
  scrubber wave) against an 8.3ms budget.
- **Phase 2** — "reappears automatically after a reboot with no manual launch."

Everything else below has been exercised on-device.

---

## What's built

### The pill itself

Three shapes, all drawn by `overlay/PillView.kt` — one view, no layout passes:

- **Circle (CS)** — `HIDDEN`. Nothing active; a resting dot over the cutout.
- **Pill (PS)** — `COMPACT`. A live event exists but hasn't been opened.
- **Expanded (ES)** — `EXPANDED`. Two cards: a 380×140dp main card plus a
  detached 380×52dp scrubber card below it.

Motion is `androidx.dynamicanimation.SpringAnimation`, retargetable mid-flight
so a new tap redirects the current motion instead of restarting it (rule 5).
Two independent springs: `progress` (PS↔ES, critically damped, settles exactly
on size) and `presence` (CS↔PS, keeps a little bounce).

### Touch architecture

Two windows, because the renderer must never re-layout (rule 4):

- **Render window** — fixed forever at max-expanded bounds, `FLAG_NOT_TOUCHABLE`.
  Never calls `updateViewLayout()`; size and radius animate inside `onDraw`.
- **Touch window** (`overlay/PillTouchView.kt`) — a small hit-target that
  resizes only at state-transition boundaries.

Hosted by an **`AccessibilityService`**, not a plain `SYSTEM_ALERT_WINDOW`
service. This is load-bearing: `TYPE_APPLICATION_OVERLAY` windows sit below
system windows like the status bar and never receive touches there — confirmed
on-device, zero touch events reached a window over the cutout.
`TYPE_ACCESSIBILITY_OVERLAY` windows from a bound service are trusted and
exempt, which is what lets the pill sit *on* the cutout and stay tappable.

Tap-outside-to-collapse uses `FLAG_WATCH_OUTSIDE_TOUCH`, which hands us a copy
of the event while the real touch still reaches the app underneath — a
full-screen catcher window would have broken rule 6.

### Gestures

Nothing is hardcoded. `core/gesture/GestureBindings.kt` maps
`(PillState, Gesture) → GestureAction`; the service just executes whatever
resolves. Keyed by state *and* gesture because tap already means three
different things by level.

| | CS | PS | ES |
|---|---|---|---|
| Tap | Expand | Expand | Open app, then collapse |
| Swipe down | Expand | Expand | — |
| Swipe up | — | Collapse | Collapse |
| Tap outside | — | — | Collapse |
| Double-tap / long-press / horizontal | — | — | — |

The vertical axis is symmetric — one level per gesture, either direction.
Double-tap, long-press and horizontal swipes ship **unbound on purpose**:
double-tap detection makes every single tap wait to see if a second follows,
and horizontal-fling detection can swallow a slightly-sideways tap.
`PillTouchView` skips that detection entirely unless a binding is listening.
Phase 5's settings UI edits this table; the touch layer won't need to change.

### Event engine

`core/event/Arbiter.kt` picks a single winner by priority across all
`EventProvider`s and pushes it to the renderer. Providers are composed in
`app/PillApplication.kt` and reach the service through
`core/event/ProviderRegistry` — an `Application` is the DI seam because an
`AccessibilityService` is system-instantiated and can't take constructor args.

Source identity (badge icon, app name) travels **on the winning `PillEvent`**,
so the pill shows the right app when Calls or Messages eventually wins.

### Spotify provider

`MediaSessionManager` + `MediaController.Callback` — fully event-driven, never
polled. Metadata, album art (downscaled and cached, rule 9), real transport
controls, `androidx.palette` accent extraction, and a scrubber driven by
extrapolation (`positionUpdateTime` + elapsed × speed, rule 10).

Two behaviours worth knowing, both hard-won from on-device logs:

- **`hasEverPlayed` gating** — Spotify *restarts itself* ~1.2s after being
  swiped from recents and registers a fresh PAUSED session. Treating PAUSED as
  active only once playback has actually been seen kills that zombie. It also
  covers BUFFERING/CONNECTING, which otherwise blanked the event for ~40ms on
  every track change and blinked an open card shut.
- **Paused-session expiry** — a paused session stays live and tappable for 2
  minutes, then clears. Android can't distinguish "user pressed pause" from "the
  app is gone and its session lingers."

---

## Layout

Layered packages in one Gradle module, seams clean enough to lift any package
into its own module later. `core` stays free of Android UI so the state
machine and arbiter are unit-testable in isolation.

```
core/       models, PillState machine, Arbiter, gesture bindings,
            EventProvider / DeviceProfile / ProviderRegistry     → depends on nothing
data/       NotificationListenerService, notification access      → core
providers/  spotify (built) · calls · messages                    → core, data
overlay/    PillView, PillTouchView, PillAccessibilityService     → core only
settings/   Compose UI + DataStore config                         → core
app/        wiring / DI only                                      → all
```

`overlay` imports `core` and nothing else — verified by walking every
cross-package import. Full table and rationale in `CLAUDE.md`.

---

## Build & run

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

24 unit tests cover the arbiter, state machine, gesture bindings and the
Spotify session logic — including regressions for the self-resurrection and
buffering-flicker bugs above.

Two permissions must be granted by hand after install:

1. **Accessibility** → Settings ▸ Accessibility ▸ Dynamic Pill
2. **Notification Access** — required for `getActiveSessions()`, not just
   notifications. Re-verified on boot, since it can silently drop after system
   updates (rule 11).

There is no emulator substitute for this project. Animation smoothness, cutout
alignment and reboot persistence only mean anything on the real device.

---

## Next

1. Close the two outstanding Verify gates (frame timing, reboot persistence).
2. **Calls provider** — `CallStyle` detection (semantic, not by action title —
   they're localized), Answer/Decline from the pill. Design gets discussed
   before it gets built.
3. **Messages provider** — Google Messages only, tap-to-open the exact thread.
4. **Phase 5 settings** — gesture editor, size/position tuner, appearance, feel.
5. **Phase 6 polish** — idle personality, landscape, split island.

### Scope

v1 is exactly three providers: **Calls, Messages (Google Messages only),
Spotify.** No quick-reply, split-island, extra message apps, torch/quick
actions or widgets unless explicitly asked — see `DEFERRED.md` for what was
turned down and why. `references/` holds screenshots of Material Capsule, a
shipping app in this category; roughly 60% of what it does is deliberately
outside our line.
