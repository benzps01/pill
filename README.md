# Dynamic Pill

A personal "Dynamic Island"-style overlay for a Pixel 8 Pro. It draws a
pill over the camera cutout that reacts to calls, Google Messages
notifications, and Spotify playback — tap to expand, tap again to collapse.
Single device, sideloaded, but architected so more devices/providers can be
added later without rewrites.

Kotlin, Classic Views for the overlay, Compose for the settings app,
minSdk 34.

Full spec: `dynamic-pill-build-plan.md`. Enforceable guardrails (never poll,
no wakelocks, freeze on screen-off, etc.): `CLAUDE.md`. Read both before
picking this up again.

## Where we are

**Phase 0 (overlay pipeline)** and **Phase 1 (state machine + spring
animation)** are done. **Phase 2 (cutout anchoring + accessibility)** is
also done, ahead of where the build plan originally sequenced it, because
touch stopped working once the pill visually sat on the cutout — see below.

What's built:

- **Three-shape pill**, all driven by `PillView` (`overlay/PillView.kt`),
  physics-animated with `androidx.dynamicanimation.SpringAnimation`
  (retargetable mid-flight, per CLAUDE.md rule 5):
  - **Circle (CS)** — small dot resting over the cutout, the collapsed/idle
    default.
  - **Pill (PS)** — the build plan's "Idle" state, thin pill hugging the
    cutout, reached by widening out from the circle.
  - **Expanded (ES)** — full capsule, reached by tapping the pill. Grows
    downward only (top-anchored), width stays centered on the cutout.
  - Two independent springs: `progress` (PS↔ES) and `presence` (CS↔PS).
    `progress` is critically damped (settles exactly at target size, no
    overshoot); `presence` keeps a little bounce.
- **Touch pass-through**: a two-window architecture — a render-only window
  fixed forever at max-expanded bounds (so it never needs
  `WindowManager.updateViewLayout()` per frame, CLAUDE.md rule 4), plus a
  small separate hit-target window that resizes only at state-transition
  boundaries (`overlay/PillTouchView.kt`).
- **AccessibilityService-hosted overlay** (`overlay/PillAccessibilityService.kt`),
  not a plain `SYSTEM_ALERT_WINDOW` service. This turned out to be
  load-bearing, not just nice-to-have: `TYPE_APPLICATION_OVERLAY` windows
  sit below system windows like the status bar and never receive touches
  there — confirmed on-device (zero touch events reached a window
  positioned over the cutout). `TYPE_ACCESSIBILITY_OVERLAY` windows added
  by a bound `AccessibilityService` are trusted and exempt from that
  restriction, which is what makes the pill both sit on the cutout *and*
  stay fully tappable — same technique used by real "Dynamic Island for
  Android" clones (dynamicSpot, Dynamic Island Pro).
- **Cutout-aware positioning**: reads the real `DisplayCutout` at runtime
  (`resolveCutoutOffsets()` in `PillAccessibilityService`), with a
  `DeviceProfile` fallback (`core/device/DeviceProfile.kt`,
  `Pixel8ProProfile`) behind an interface per CLAUDE.md rule 7, for devices
  where the live API reports nothing.
- **Screen-off freeze**: `ACTION_SCREEN_OFF` cancels both springs
  immediately (CLAUDE.md rule 3).
- `core/state/PillState.kt` / `PillStateMachine.kt`: pure Kotlin, no Android
  deps, unit-testable state machine (`HIDDEN`/`IDLE`/`COMPACT`/`EXPANDED`/
  `TRANSIENT_POP`).

What's still placeholder, deliberately:

- There's no real event source yet. Tap and swipe-up on the pill are
  Phase-1 stand-ins for what should eventually be driven by real
  notifications/media sessions.
- `COMPACT` is a reachable state in the state machine but `PillView` never
  renders it distinctly from `IDLE` yet.
- Swipe-up currently drives `PillState.HIDDEN`, which `PillView` renders as
  the collapsed circle — but per the build plan, `HIDDEN` is supposed to
  mean true invisibility for fullscreen/immersive apps and screen-off, not
  a manual gesture. That distinction needs revisiting once the Arbiter
  exists. See the doc comment on `PillState.kt` for the full note.
- Expanded content is a hardcoded "Expanded" text label — no real call,
  message, or track info yet.

## What's next

**Phase 3 — event engine**, per the build plan:
- `EventProvider` interface in `core`
- `NotificationListenerService` in `data` (raw event stream)
- `MediaSessionManager` access for Spotify
- The `Arbiter` (pure Kotlin, `core`) that decides which event wins and
  what state/content the pill should show — this is what should finally
  replace the tap/swipe placeholders with real logic, including scenarios
  already discussed: e.g. staying in a `COMPACT`-like state while Spotify
  is actively playing instead of returning to the plain `IDLE` circle.

Then **Phase 4** (the three v1 providers — Calls via `CallStyle` detection,
Google Messages only, Spotify), **Phase 5** (Compose settings + DataStore
config), **Phase 6** (polish, per the build plan's performance bar: 120Hz,
zero dropped frames, clean mid-animation retargeting).

Per CLAUDE.md's phase-gate discipline: don't start Phase 3 until Phase 1 and
2's Verify checklists are actually confirmed on-device, not assumed.
