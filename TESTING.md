# Testing checklist

What has been tried on the TV, and what is left. Nick tests; Claude keeps this file.

**Rules (for every Claude session on this branch, see CLAUDE.md):**

- A feature's checks go in here in the same commit as the feature, under its area, as `- [ ]` lines. Each check is one
  thing to try on the TV, written so it can be answered Works / Broken / Not tried.
- The CHANGELOG entry may still say "Not yet tried", but the checks must be here.
- Results come from Nick, usually as clickable Works / Broken / Not tried questions. Record them as:
  - `- [x] check (2026-11-08)` when it works
  - `- [ ] **BROKEN** check (2026-11-08): what Nick said` when it does not
  - `- [~] check (2026-11-08): works, but what Nick said needs doing` for "works, with a follow-up"
- At the start of a session, offer the open checks (unticked, oldest first) as clickable questions.
- A fix for a broken or follow-up line adds a fresh `- [ ]` re-check under it rather than ticking it.

## Phone remote

- [x] Pairing with the code; D-pad, OK, Back, play/pause and typing into search drive the TV (2026-11-08)
- [x] Now playing on the phone (cover, seek bar), with lock-screen and notification controls (2026-11-08)
- [x] Send to Nuvio: a YouTube or YouTube Music link from the phone's share menu plays on the TV (2026-11-08)
- [x] Movies & TV tab: library posters show; tapping one opens its details on the TV (2026-11-08)
- [x] Music tab: albums and artists show; Play / Shuffle / Add to queue and an artist's Shuffle all / Radio play on the TV (2026-11-08)
- Remote tab bottom row of five keys: dropped, the glass redesign replaced that layout (2026-11-08)
- [x] The big key is the only play/pause: it shows Pause while playing and Play while paused, and matches the TV (2026-11-08)
- [x] Long titles and artists show in full on the film card and the music screen (they scroll slowly) (2026-11-08)
- [x] The seek bar is thin with a round marker; tapping or dragging it seeks the TV once, on release (2026-11-08)
- [x] Glass redesign: the app wears the TV's theme (change it in Nuvio's Settings and the phone follows within a second), and opens in that colour next time (2026-11-08)
- [x] Remote while browsing: touchpad, swipe moves one tile per short swipe and keeps going on a long one, tap is OK; Back, Home, Search (opens the TV's search and the phone keyboard), More (2026-11-08)
- [ ] **BROKEN** Moving around Nuvio's Settings from the phone (2026-11-08): neither the touchpad nor the D-pad moves there, and a controller does not either, so Settings ignores the arrow and OK presses the pad layer sends
  - [ ] Re-check after the fix: in Settings, the phone's touchpad and D-pad, a controller's D-pad and the keyboard's arrows move between rows and categories, OK opens or toggles the row, and Left/Right still change a slider or move in a text box
- [x] Touchpad / D-pad switch (the tag in the panel's corner, or More) is remembered after closing the app (2026-11-08)
- [x] Remote during a film: Now playing card, Subtitles and Audio tiles show the current track and tap to the next, back 10s / play-pause / ahead 10s, Back / Sources / More (2026-11-08)
- [x] Remote during music: cover, title, artist, seek, previous / play-pause / next; the Touchpad tag switches to the pad and back (2026-11-08)
- [x] More sheet: Mute, Vol −, Vol +, Type, D-pad/Touchpad, Other PC (2026-11-08)
- [x] Watch and Music tabs in the glass style: header label, glass search, pill chips, section labels with counts, glass album sheet (2026-11-08)

## Music playback

- [x] Gapless: songs in an album run straight into each other (2026-11-08)
- [x] Volume is right from the first second, no jump a few seconds in (2026-11-08)
- [x] Constant volume for music: no gain riding during a song (2026-11-08)
- [ ] Constant volume for trailers: check they get the same treatment as music (Nick, 2026-11-08)
- [x] Lossless: an album playing from YouTube Music switches to the addon's lossless copy; tiles show the Lossless badge (2026-11-08)
- [x] Calm lyrics stay in order without jumbling (2026-11-08)

## Mini player

- [x] Open dock: cover-tinted seek bar, beat dot, Radio tag, "Keep going?" inside the card (2026-11-08)
- [x] Heart likes the song on YouTube Music, and un-likes on a second click (2026-11-08)
- [x] Quality pill, Previous, times at both ends of the bar, Up next with Play next / Remove, Shuffle, Repeat, Clear (2026-11-08)

## Radio

- [x] "Keep going?" near a radio's end, and Keep going adds more songs (2026-11-08)
- [~] Stations and mixes play from search; Start radio on songs and artists (2026-11-08): works, but genre keyword
  detection needs to be better. Plan: remove the Ladder and move genre into search.

## Shelf

- [x] Three-panel browser fits one screen; Artists A to Z and All albums work with the remote and the mouse (2026-11-08)
- [x] Smart shelves (New from your artists, Recently played, Most played, Gathering dust) fill from the play log (2026-11-08)
- [x] Liked Music, Unplayed and Listen again shelves fill and play (2026-11-08)
- [x] Liked Music shows your liked songs as their own rows after the card (2026-11-08)
- [x] Play history: Import from YouTube Music and Clear (2026-11-08)
- [x] YouTube Music as you: your own mixes, playlists and Liked Music (2026-11-08)

## Music home and search

- [x] Sentence chips, the narrow row, Remix / Live panels (2026-11-08)
- [x] Top-match morph, the action row and More menus (2026-11-08)
- [x] More menu on songs and releases (right-click, Menu key, hold OK), with Play next (2026-11-08)

## Artist page

- [x] Photo hero, three panels, Essentials and Discography, six buttons on one row (2026-11-08)

## Album page

- [x] Lineage fills from open data (Wikidata, YouTube Music) instead of waiting on the AI (2026-11-08)
- [~] The Story shows text even when the AI key is out for the day (2026-11-08): works, but it only says "From
  Wikipedia", so it is not clear whether the Gemini / Google Search backup ever answered. Needs a way to tell which
  source wrote the story.
- [x] Album intro uses YouTube Music's own Wikipedia blurb (2026-11-08)
- [x] Who made it: credits for most albums, without waiting on the AI (2026-11-08)
- [x] Sonic Map and Your History tabs (2026-11-08)
- [x] Cut to fit (2026-11-08)
- [x] Albums with two editions (Ren, Vincent's Tale) stay on one (2026-11-08)
- [ ] **BROKEN** Song by song > Other versions finds the clean or explicit edition (2026-11-08): not all of the time,
  needs work
- [x] Edit pills: small card, intro, trivia and the song story edit on the album page and the Stage, and stick (2026-11-08)

## Music facts

- [ ] **BROKEN** The facts filler fills in the background within the AI key's limits (2026-11-08): on Groq's free tier
  it hits the limits within seconds
- [x] Share music facts as JSON; Fill album facts in the background (Settings > Music) (2026-11-08)

## Now Playing stage

- [x] Beat Grid drum rows read on the beat; Song Map has the right tempo and bar lines and fills the width (2026-11-08)
- [~] Beat Grid: beat one in the right place, e.g. Billie Jean (2026-11-08): much better, still needs work
- [x] Visualiser in the Liner Notes box (2026-11-08)
- Bigger album case on wide windows: dropped, the new screen replaced it (2026-11-08)

## Live TV

- [x] Stalker portals log in, load channels and play (2026-11-08)
