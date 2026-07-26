# CLAUDE.md — Dynamic Pill

Guardrails for developing this project. The full spec lives in
`dynamic-pill-build-plan.md` — read it before starting any phase of work.
This file is the enforceable subset: the rules that must never be quietly
violated while writing code.

## What this is

A personal "Dynamic Island"-style overlay for a Pixel 8 Pro (single device,
sideloaded, architected so more devices/providers can be added later without
rewrites). Kotlin. Classic Views for the overlay, Compose for the settings
app. minSdk 34.

## Non-negotiable rules

1. **Never poll.** Every code path must be event-driven
   (`NotificationListenerService`, `MediaSessionManager`, broadcast
   receivers). No timers/handlers that periodically check state.
2. **No wakelocks.**
3. **Freeze on screen-off.** On `ACTION_SCREEN_OFF`, pause the view and kill
   every running animator. Idle animation must never run with the screen off.
4. **Draw, don't re-layout.** The overlay window stays fixed at max-expanded
   bounds. Never call `WindowManager.updateViewLayout()` or trigger a
   measure/layout pass per animation frame. Animate width/height/corner-radius
   inside `onDraw`, or animate translation/scale/alpha — nothing that forces
   re-measure.
5. **Physics springs, not fixed-duration animations**, for pill morphing —
   `androidx.dynamicanimation.SpringAnimation`, retargetable mid-flight so a
   new tap redirects current motion instead of restarting it.
6. **Touch pass-through must be exact.** Anything outside the pill's drawn
   bounds must reach the app underneath untouched.
7. **No concrete cross-boundary references.** Every capability sits behind an
   interface (`EventProvider`, `DeviceProfile`, etc). See the module table in
   the build plan §4 for what may depend on what. If you're about to import a
   concrete class from another layer, stop and add/use the interface instead.
8. **Detect CallStyle actions semantically, not by string** — action titles
   are localized.
9. **Downscale and cache album art** — never hold full-size bitmps from
   Spotify metadata.
10. **Guard the Spotify scrubber** against missing position data, and drive it
    via extrapolation (`lastPositionUpdateTime` + elapsed × speed), never by
    polling `getPlaybackState()` on a timer.
11. **Re-verify Notification Access on boot** — it can silently drop after
    system updates; a revoked permission must be surfaced to the user, never
    fail silently.

## Module boundaries (build plan §4)

| Package | Owns | May depend on |
|---|---|---|
| `core` | Models, `PillState` machine, `EventProvider`/`DeviceProfile` interfaces, `Arbiter` — pure Kotlin, no Android UI | nothing |
| `data` | `NotificationListenerService`, `MediaSessionManager` access, raw event stream | `core` |
| `providers.calls` | `CallStyle` detection + Answer/Decline | `core`, `data` |
| `providers.messages` | Google Messages filter + tap-to-open | `core`, `data` |
| `providers.spotify` | MediaSession metadata + transport | `core`, `data` |
| `overlay` | `PillView`, WindowManager host, `AccessibilityService`, animations | `core` |
| `settings` | Compose UI + DataStore config | `core` |
| `app` | Wiring/DI only | all |

Start as layered packages in one Gradle module; keep the seams clean enough
that any package can be lifted into its own module later with zero rewrites.
`core` must stay free of Android UI dependencies so the state machine and
arbiter remain unit-testable in isolation.

## Working through phases

The build plan (§9) defines phases 0–6, each with an explicit **Verify**
gate. Do not start the next phase until every Verify item for the current one
passes on the real Pixel 8 Pro — there is no simulator/emulator substitute
for animation smoothness, cutout alignment, or reboot persistence checks.
When asked to "move to the next phase," confirm the current phase's Verify
checklist is actually satisfied first; don't assume.

## Performance bar

Target 120Hz (~8.3ms/frame budget). "Smooth" means: zero dropped frames in
the profiler during expand/collapse, and a mid-animation tap retargets
cleanly with no visible snap. When in doubt about an animation approach,
prefer the one that avoids re-layout even if it's more code.

## Scope discipline

v1 is exactly three providers: Calls, Messages (Google Messages only),
Spotify. Do not add quick-reply, split-island, more message apps,
torch/quick actions, or widgets unless the user explicitly asks — these are
listed as deferred in the plan for a reason. If a task seems to call for one
of these, flag it against the plan rather than building it silently.
