# Nuvio Remote: ideas and build order

From the UX review of Nov 9 2026 (nine demo screenshots, so nothing here says how it feels on the real TV). Tick a line when built and move its checks to `TESTING.md`. Mockups: the "Nuvio Remote: Feature Mockups" canvas in the project's Claude artifacts.

## Build order

1. [x] **Sources cards**: badges for resolution, HDR / Dolby Vision, codec and size; release name demoted; filter chips. Phone only (Nov 9)
2. [ ] **Film Remote controls**: volume, speed, sleep timer and context buttons ("Skip intro", "Next episode") in the empty space under the transport row. Needs new protocol lines, change both sides together. Check first what the player exposes for speed
3. [ ] **Music Remote extras**: shuffle, repeat, like and Start radio beside the transport; keep Loud lyrics as the hero. The desktop actions exist
4. [ ] **More sheet regrouped**: Playback, Display, Device
5. [ ] **Second-screen music mode**: facts, credits and Wikipedia text for the playing song; Other versions and Cut to fit. Largest piece, last

## Smaller ideas

- [ ] Show the "Tap for next, hold for list" hint only until it has been used once
- [ ] Library: an "Unwatched" and a "Recently added" rail
- [ ] Voice-search mic always visible in the search bar
- [ ] Smart shelves and the play log on the phone (Recently played, Most played, Not played in a year)
- [ ] Genre and mood chips with Start radio on the TV, using the genre card's detection
- [ ] Preferred-language subtitles first in the subtitle list
- [ ] Previously on: a recap card when resuming a series

## Caveats

- Badges rely on add-ons naming their streams consistently; cards fall back to the plain name.
- Volume and speed depend on what the player exposes to the remote.
