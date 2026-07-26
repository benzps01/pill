# Dynamic Pill — Build Plan (Pixel 8 Pro)

A personal "Dynamic Island" style overlay for the Pixel 8 Pro. Sideloaded, single-device
first, architected to add more phones later. This doc is the working reference — check items
off as we go and add notes inline.

---

## 1. Locked decisions

| Area | Decision | Why |
|---|---|---|
| Target device | Pixel 8 Pro (centered top punch-hole) | Symmetric pill, simplest cutout |
| Modularity | Everything behind interfaces; layered packages, extractable to Gradle modules | See §4 — a hard requirement |
| Language / UI | Kotlin. Classic **Views** for the overlay, **Compose** for the settings app | Compose-in-overlay lifecycle isn't worth fighting on the pill |
| minSdk | 34 (Android 14) | Own device; unlocks newer torch + cutout APIs |
| Overlay host | Foreground service early → migrate to **AccessibilityService** | Status-bar overlap + auto-restart after reboot |
| Data backbone | `NotificationListenerService` (+ `MediaSessionManager` for Spotify) | One permission covers all three v1 features |
| Calls | Read the incoming-call **notification** and fire its Answer/Decline actions | Avoids building a full replacement dialer |
| Messages | **Google Messages only** (`com.google.android.apps.messaging`) for now | Simple, configurable list later |
| Animation | Physics springs, drawn (not laid out) per frame, 120Hz target | See §3 — must feel smooth |
| Orientation | Portrait first; landscape = fast-follow | Landscape overlays are fiddly |
| Idle animation | An explicit **state**, screen-on + interactive only, low frequency | Reconciles with the freeze-when-idle battery rule |

---

## 2. v1 feature scope (the three providers)

| Provider | Source API | Shows | Interaction |
|---|---|---|---|
| **Calls** | `Notification.CallStyle` via listener (P8P Google Phone → CallStyle on A14+) | Caller name/number, photo | **Answer / Decline on the pill** (fire notif action `PendingIntent`) |
| **Messages** | `NotificationListenerService`, filtered to Google Messages | Sender + message preview | **Tap → fire `contentIntent`** → opens Google Messages to that thread |
| **Spotify** | `MediaSessionManager` → `MediaController` | Title, artist, album art, live scrubber | Play / pause / skip / seek via `transportControls` |

Deferred: inline quick-reply, split-island, more message apps, downloads, torch/quick actions, widgets.

---

## 3. Animation smoothness (Pixel 8 Pro is 120 Hz)

Smoothness is a requirement, not a nice-to-have. How we guarantee it:

- **Physics springs, interruptible.** `androidx.dynamicanimation.SpringAnimation` is
  Choreographer-driven and can *retarget mid-flight* — a new tap redirects the current
  motion instead of restarting it. This single property is the biggest contributor to "feels smooth."
- **Draw, don't re-layout.** The pill shape is rendered in `onDraw` at an animated
  width / height / corner-radius. We **never** call `WindowManager.updateViewLayout()` or
  trigger a measure/layout pass per frame — the window stays fixed at max-expanded bounds.
- **Cheap properties only.** Animate translation / scale / alpha / custom-draw; nothing that
  forces re-measure. Content alpha crossfades against size so text never stretches.
- **Frame budget.** Target the 120 fps budget (~8.3 ms/frame). Verify with GPU rendering bars
  and the Energy/CPU profiler on the real device.
- **Tunable feel.** `dampingRatio` (~0.45–0.55) and stiffness surfaced in settings as a single
  "bounce intensity" slider.

Acceptance for "smooth": no dropped frames in the profiler during expand/collapse, and a
mid-animation tap redirects cleanly with no visible snap.

---

## 4. Modularity (hard requirement)

**Principle:** every capability sits behind an interface; no concrete class is referenced across
a boundary. Start as layered packages in one module; each can be lifted into its own Gradle
module later with zero rewrites because the seams already exist.

| Package | Owns | May depend on |
|---|---|---|
| `core` | Models, `PillState` machine, `EventProvider` + `DeviceProfile` **interfaces**, `Arbiter` — pure Kotlin, no Android UI | nothing |
| `data` | `NotificationListenerService`, `MediaSessionManager` access, raw event stream | `core` |
| `providers.calls` | `CallStyle` detection + Answer/Decline | `core`, `data` |
| `providers.messages` | Google Messages filter + tap-to-open | `core`, `data` |
| `providers.spotify` | MediaSession metadata + transport | `core`, `data` |
| `overlay` | `PillView`, WindowManager host, `AccessibilityService`, animations | `core` |
| `settings` | Compose UI + DataStore config | `core` |
| `app` | Wiring / DI only | all |

**Modularity wins this buys us:**
- Providers are registered in a list; the arbiter just iterates it. Adding or removing a
  provider touches *nothing else*.
- `DeviceProfile` behind an interface → a new phone = one new implementation.
- `core` has no Android UI deps → the state machine and arbiter are unit-testable in isolation.
- Swapping Views→Compose in the overlay later only touches the `overlay` package.

---

## 5. Architecture layers

```
AccessibilityService (overlay host, survives reboot)
        │
   PillView (WindowManager overlay)  ← classic Views + SpringAnimation
        │
   Renderer  ── maps winning event → pill state + content
        │
   Arbiter  ── picks ONE event by priority (split-island later)
        │
   EventProviders  ── Calls | Messages | Spotify (each behind EventProvider)
        │
   data: NotificationListenerService + MediaSessionManager

DeviceProfile ── cutout geometry + pill anchor (runtime + 8 Pro fallback)
Settings (Compose + DataStore) ── config out
```

---

## 6. Pill state machine

| State | Look | Enter when |
|---|---|---|
| Hidden | nothing | fullscreen/immersive app, screen off, (v1) landscape |
| Idle | thin pill hugging the cutout | default resting |
| Compact | small info beside the hole | minimal live event |
| Expanded | full capsule, rich content | tap, or event needing detail (media, call) |
| TransientPop | expands briefly, settles | unlock greeting, charge connected |

Transitions = springs (see §3). Animate the **inner view**, not the window.

---

## 7. Permissions & onboarding

| Permission | For | Type |
|---|---|---|
| `SYSTEM_ALERT_WINDOW` | draw the overlay | special access screen |
| Notification Access | calls, messages, Spotify metadata | special access screen |
| Accessibility Service | status-bar overlap + reboot persistence | special access screen |
| `READ_PHONE_STATE` *(optional)* | reliable ring detection to supplement the notif | runtime |
| `POST_NOTIFICATIONS` | the foreground-service notification | runtime |

Several special-access screens → build a **guided first-run**.

---

## 8. Battery rules (non-negotiable)

- 100% event-driven — **never poll**.
- **No wakelocks.**
- On `ACTION_SCREEN_OFF`: pause the view, kill all animators.
- Stop every spring once settled — nothing runs at rest.
- Spotify scrubber uses **extrapolated** position (`lastPositionUpdateTime` + elapsed × speed).
- Verify with Android Studio Energy Profiler.

---

## 9. Milestone roadmap — with step-by-step verification

Each phase has a **Verify** gate. Do not start the next phase until every Verify item passes on the real Pixel 8 Pro.

### Phase 0 — Overlay pipeline
- [ ] Project setup (Kotlin, minSdk 34, Gradle)
- [ ] `SYSTEM_ALERT_WINDOW` request flow
- [ ] Foreground service adds a black rounded pill, top-center, via `WindowManager`
- [ ] Touch pass-through outside the pill bounds
- [ ] **✅ Verify:** pill is visible over the launcher *and* over another app; tapping *outside* it operates the app underneath; it survives leaving/returning to the app; no crash on permission-denied path.

### Phase 1 — Pill state machine + spring  ← **starting here**
- [ ] Define states (Hidden / Idle / Compact / Expanded)
- [ ] Rounded background + two size targets
- [ ] Tap toggles Idle ↔ Expanded (snap first, no animation)
- [ ] Replace snap with `SpringAnimation` on width/height/radius
- [ ] Content crossfade vs size
- [ ] Swipe-up to dismiss
- [ ] **✅ Verify:** expand/collapse shows **zero dropped frames** in the profiler; a tap *mid-animation* retargets with no snap; swipe-up dismisses cleanly; text never stretches during morph.

### Phase 2 — Cutout anchoring + accessibility migration
- [ ] `DeviceProfile` interface + `DisplayCutout` reader
- [ ] Pixel 8 Pro fallback profile
- [ ] Migrate host to `AccessibilityService`
- [ ] Position pill exactly over the hole
- [ ] **✅ Verify:** pill is centered on the physical camera hole to the pixel; it **reappears automatically after a reboot** with no manual launch; status bar clock/icons no longer render on top of it.

### Phase 3 — Event engine
- [ ] `EventProvider` interface
- [ ] Priority arbiter (single winner)
- [ ] Renderer maps winner → pill state
- [ ] `NotificationListenerService` + Notification Access onboarding
- [ ] **✅ Verify:** feed two fake events → the higher-priority one wins and renders; revoking Notification Access is detected and surfaced, not a silent failure.

### Phase 4 — The three providers
- [ ] **Spotify**: metadata, album art, play/pause/skip, extrapolated scrubber
- [ ] **Calls**: detect `CallStyle`, show caller, wire Answer/Decline
- [ ] **Messages**: Google Messages filter, sender + preview, tap-to-open
- [ ] **✅ Verify (each):**
  - Spotify — controls actually drive playback; scrubber tracks without polling; art loads and is cached.
  - Calls — real incoming call shows the caller; **Answer connects, Decline hangs up** from the pill.
  - Messages — a Google Messages text shows sender + preview; **tapping opens that exact conversation**; other apps' messages are ignored.

### Phase 5 — Settings app
- [ ] Compose Activity + DataStore
- [ ] Per-event toggles, bounce-intensity slider, (later) message-app picker, Material You color
- [ ] **✅ Verify:** toggling a provider off removes it live; bounce slider changes feel immediately; settings persist across restart.

### Phase 6 — Polish
- [ ] Idle-personality animations (screen-on only)
- [ ] Gestures (double-tap / long-press)
- [ ] Landscape (relocate to side edge + rotate content)
- [ ] Split island (two simultaneous events)
- [ ] Battery-exemption prompt
- [ ] **✅ Verify:** idle animation never runs with screen off (confirm in profiler); overnight idle drain is negligible.

---

## 10. Open items to revisit
- **Idle animation motions** — what they look like + frequency (design later)
- **More message apps** — extend the package filter when wanted
- **Landscape** — relocate + rotate vs. hide (leaning: relocate, fast-follow)

---

## 11. Known gotchas
- `CallStyle` action titles are localized → detect Answer/Decline **semantically**, not by string.
- Album art can be a large bitmap → downscale + cache.
- Notification Access can silently drop after some system updates → re-verify on boot.
- Touch pass-through must be exact or you'll block the app underneath.
- Spotify occasionally reports no position → guard the scrubber against it.
