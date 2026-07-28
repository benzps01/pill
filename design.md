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

## Spotify (`providers/spotify/SpotifyProvider.kt`)

**Compact/PS:**
- Spotify app icon, left-aligned.
- Right-aligned indicator: 3-bar animated equalizer while playing; static
  "||" (two equal bars, no middle one) while paused.
- No text.

**Expanded/ES:**
- Small Spotify logo badge, fixed top-left corner (persists visually from
  where the Compact icon was, as a design continuity cue through the
  PS→ES expand).
- Album art thumbnail (downscaled + cached per CLAUDE.md rule 9).
- Title + artist text, marquee-scrolled if a line overflows its column.
- Background tint derived from the album art's dominant color (via
  `androidx.palette`), not flat black.
- Scrubber: elapsed/remaining time + progress track, driven by
  extrapolation from `PlaybackState` (position + speed + last-update time),
  never polled (CLAUDE.md rule 10).
- Transport controls: prev / play-pause / next, bottom row. Real
  `MediaController.transportControls` calls, routed through the winner
  `PillEvent`'s action closures (not a direct provider reference — see
  DEFERRED.md item 1).

**Auto-behavior:** Compact and Expanded both collapse straight to Circle
(HIDDEN) when the event ends. Tapping an empty Circle/Pill does nothing —
there's no manual placeholder-expand anymore (see `PillStateMachine`).
Tapping a Circle *with* a still-live event (e.g. after a manual swipe-up
dismiss while a song plays on) reopens Compact.

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
