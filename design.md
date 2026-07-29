# Per-provider, per-state design

What each provider actually renders in Compact/PS and Expanded/ES, so this
doesn't have to be re-derived from `PillView.kt` every time it changes.
Update this alongside any rendering change — it's the design source of
truth, `PillView.kt` is the implementation.

Shared pill shapes (`overlay/PillView.kt`, `core/state/PillState.kt`):
- **Circle (CS)** — `HIDDEN`. Nothing active. Resting circle over the cutout.
- **Pill (PS)** — `COMPACT`. A live event exists but hasn't been tapped open.
- **Expanded (ES)** — `EXPANDED`. Tapped open from Compact.

---

## Gestures (shared across providers)

Nothing is hardcoded: `core/gesture/GestureBindings.kt` maps
`(state, gesture) → action`, and `PillAccessibilityService` just executes
whatever it resolves to. Phase 5's settings UI edits this table; adding a
binding should never require touching the touch layer again.

Keyed by state *and* gesture because a gesture already means different
things at different levels — tap reveals from the circle, expands from
compact, opens the app from expanded.

| | CS | PS | ES |
|---|---|---|---|
| Tap | Expand | Expand | Open app, then collapse to PS |
| Swipe down | Expand | Expand | — |
| Swipe up | — | Collapse | Collapse |
| Tap outside pill | — | — | Collapse |
| Double-tap | — | — | — |
| Long-press | — | — | — |
| Swipe left / right | — | — | — |

The vertical axis is symmetric: swipe down steps up a level, swipe up steps
back down, one level per gesture. `DISMISS` (straight to CS from anywhere)
exists as an action but nothing is bound to it by default.

**Why double-tap, long-press and horizontal swipes ship unbound:** running
double-tap detection makes every single tap in that state wait to see
whether a second follows, and running horizontal-fling detection lets a
slightly-sideways press be swallowed as a fling instead of reaching the tap
handler. Both costs are real, so they're opt-in — `PillTouchView` skips
detection entirely unless `GestureBindings.isBound` says something is
listening. Per-state keying means enabling one only costs in the states it
was enabled for.

**Tap-outside** is delivered via `FLAG_WATCH_OUTSIDE_TOUCH` on the touch
window, which hands us a *copy* of the event while the real touch still
reaches the app underneath — a full-screen catcher window would have
swallowed it and broken CLAUDE.md rule 6. The flag is only set in states
that bind `TAP_OUTSIDE`. Note it fires on the initial **down**, so starting
a scroll under the pill collapses it too.

---

## Spotify (`providers/spotify/SpotifyProvider.kt`)

**Compact/PS** — `[Spotify logo | camera cutout | bars/pause]`:
- Spotify app icon, left-aligned.
- Middle deliberately empty: the physical punch-hole sits there, so
  anything drawn in it would be obscured.
- Right-aligned indicator: 3-bar animated equalizer while playing; static
  "||" (two equal bars, no middle one) while paused. This is the only place
  play-vs-pause is readable without expanding.
- No text, no album art.

**Expanded/ES — two cards.** Modelled on `references/img21.jpeg`. The
previous single tall card stacked everything vertically and only stopped
overlapping by growing taller; splitting the scrubber into its own card
lets the main card stay one row high.

*Main card* (what the pill shape actually morphs into, 380×140dp):
- **Header row** across the top, *split around the punch-hole*: source app
  icon + name on the left, album on the right, nothing drawn within 26dp of
  centre. This turns the dead space either side of the camera into useful
  space instead of just clearance. Header text truncates with an ellipsis
  rather than marqueeing — it's secondary, and two moving lines would fight
  the title.
- **Content row** below the header: `[album art + badge] [title / artist]
  [prev · play-pause · next]`.
- Source identity (the badge icon and app name) comes off the winning
  `PillEvent`, not from provider state latched at startup — so when Calls or
  Messages wins the arbiter, the badge follows.
- **Accent blob**: a solid tint of the album art's dominant color filling
  the card's lower portion, with a wavy top edge that scrolls sideways while
  playing and freezes when paused (`references/img21.jpeg`). Drawn first,
  clipped to the card's rounded rect, at low alpha so title/artist keep
  their contrast — the reason the earlier full-card gradient was dropped.
- Album art thumbnail (downscaled + cached per CLAUDE.md rule 9), with the
  Spotify logo as a small badge riding the art's own bottom-right corner
  rather than occupying a separate slot.
- Title + artist, marquee-scrolled if a line overflows its column.
- Card background stays black; the album art's dominant color (via
  `androidx.palette`) tints the **title text**, the accent blob and the
  scrubber wave.
- Transport controls: prev / play-pause / next, right-aligned in the row.
  Real `MediaController.transportControls` calls, routed through the winner
  `PillEvent`'s action closures (not a direct provider reference — see
  DEFERRED.md item 1). Their geometry lives in shared helpers on
  `PillView`'s companion so drawing and `PillTouchView`'s hit-testing can't
  drift apart.

*Scrubber card* (detached, 380×52dp, 8dp below the main card):
- Elapsed left, `-remaining` right, progress track between.
- The played portion draws as a **sine wave** that phase-scrolls while
  playing and freezes when paused; the remainder is a flat line, with a dot
  at the boundary. Path is pre-allocated and `rewind()`-ed per frame, never
  allocated in `onDraw`.
- Position comes from extrapolation of `PlaybackState` (position + speed +
  last-update time), never polled (CLAUDE.md rule 10).
- Takes no part in the size morph — it fades and slides out from behind the
  main card as the expand spring runs.

**Auto-behavior:** Compact and Expanded both collapse straight to Circle
(HIDDEN) when the event ends. Expanding an empty Circle/Pill does nothing —
there's no manual placeholder-expand anymore (see `PillStateMachine`).
Expanding a Circle *with* a still-live event (e.g. after a manual dismiss
while a song plays on) reopens Compact. Metadata churn — track change,
play/pause — never closes an open card.

**Paused-session expiry:** a paused session stays live (and tappable) for
up to 2 minutes of no further activity, then auto-clears as if the event
ended. This exists because Android doesn't distinguish "you tapped pause,
still engaged" from "the app was swiped from recents and its session just
lingers paused indefinitely" — MediaSession deliberately survives task
removal so media controls keep working, so a paused-forever zombie session
is otherwise indistinguishable from a real pause. See
`SpotifyProvider.PAUSED_EXPIRY_MS`.

---

## Calls (`providers/calls` — not yet built)

Not designed yet. Content shape (Compact vs Expanded) should be filled in
here when the provider is actually built (Phase 4 remainder), not
speculated now — see DEFERRED.md's reasoning for why Calls/Messages design
was deliberately deferred past the Spotify pass.

## Messages (`providers/messages` — not yet built)

Not designed yet — see above.
