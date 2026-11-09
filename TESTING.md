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
- [x] Moving around Nuvio's Settings from the phone, fixed (2026-11-09); was broken (2026-11-08): neither the touchpad nor the D-pad moves there, and a controller does not either, so Settings ignores the arrow and OK presses the pad layer sends
  - [ ] **BROKEN** Re-check after the first fix (2026-11-08): still broken
  - [ ] **BROKEN** Re-check after the second fix (2026-11-09): nothing highlighted and Down seemed to do nothing, with all three inputs. Focus was moving (OK after two Downs opened Screensaver); Settings simply draws no focus ring
  - [ ] **BROKEN** Re-check after the third fix (2026-11-09): no ring
  - [x] Re-check after the fourth fix: a ring in the theme's focus colour sits on the open category when Settings opens, and moves with the phone's touchpad and D-pad, a controller's D-pad and the keyboard's arrows over categories, rows, switches and buttons; OK opens or toggles what has the ring; the ring hides when the mouse moves (2026-11-09)
- [x] Touchpad / D-pad switch (the tag in the panel's corner, or More) is remembered after closing the app (2026-11-08)
- [x] Remote during a film: Now playing card, Subtitles and Audio tiles show the current track and tap to the next, back 10s / play-pause / ahead 10s, Back / Sources / More (2026-11-08)
- [x] Remote during music: cover, title, artist, seek, previous / play-pause / next; the Touchpad tag switches to the pad and back (2026-11-08)
- [x] More sheet: Mute, Vol −, Vol +, Type, D-pad/Touchpad, Other PC (2026-11-08)
- [x] Watch and Music tabs in the glass style: header label, glass search, pill chips, section labels with counts, glass album sheet (2026-11-08)

### Phone remote: Dark mode and the demo TV (phase 2)

- [ ] Dark mode: the Dark key (film or music screen, or More) turns the phone black and dims it (Medium, 15%, to start), hides the status and navigation bars, and the screen stays on
- [ ] Dark mode hints (tap, swipe, hold) show for a few seconds and fade; Back brings them back instead of leaving
- [x] Dark mode brightness: Low / Medium / High under the hints (Back shows them again) changes the phone's brightness at once, is kept for next time, and tapping one does not also pause the film (2026-11-09)
- [ ] **BROKEN** Dark mode, a film: tap is play/pause, swipe left/right is 10 s back/ahead per step with a tick each, swipe up/down is volume; each shows faintly in the middle (2026-11-09): tap works, but no swipe does anything, in any direction
  - [ ] Re-check after the fix: swipes left/right (10 s, or songs for music) and up/down (volume) work, each with a tick; hold for a second leaves
- [ ] Dark mode, music: swipe left/right is the previous/next song (one per swipe)
- [ ] Dark mode: holding a finger anywhere for a second draws a faint ring and leaves; brightness and the bars come back
- [x] Demo TV: Try the demo TV (on the PC list) asks for the code 123456, then connects as "Demo TV" and stays in the paired list (2026-11-09)
- [x] Demo TV: the "Demo TV received" strip shows each key, swipe and typed text (2026-11-09, from Nick's screenshots)
- [ ] Demo TV: More > Demo TV plays a film or an album, stops it, and changes the theme (the app follows)
- [ ] **BROKEN** Film screen with the demo strip (2026-11-09, from Nick's screenshots): the D-pad / touchpad panel is squeezed down to its title bar, with no pad left to use
  - [~] Re-check after the fix (2026-11-09, from Nick's screenshots): the keys stay whole at the bottom, but the pad was still pushed off the screen
  - [ ] Re-check after the second fix: the film screen has no pad on it any more; the Touchpad tag in its header (as on the music screen) swaps to the full-size pad and Now playing swaps back; Sources brings the pad up by itself
- [ ] **BROKEN** Music screen (2026-11-09, from Nick's screenshot): the Back / Search / Dark / More keys are cut in half by the tab bar
  - [x] Re-check after the fix: the cover shrinks to fit, and the keys show whole above the tab bar (2026-11-09, from Nick's screenshot)
- [x] Demo TV: Watch and Music tabs show the pretend library (with photos when online); opening a title plays it after 2 s; an album plays, shuffles, next and previous work (2026-11-09, from Nick's screenshots)
- [ ] Demo TV: the lock-screen controls show the pretend film or song and work

### Phone remote: Continue watching (phase 3)

- [x] The Watch tab opens with a Continue watching row (landscape pictures, a progress line, the episode and time left), the same titles as Nuvio's Continue Watching (2026-11-09)
- [ ] The "Opening … on the TV" note shows at the top of the remote, clear of the keys (it covered them at the bottom)
- [ ] Tapping one opens a sheet: poster, title, episode, how far in, and the source it last played from
- [x] Resume on the TV plays it straight away from the same source, from where it stopped (with a film already playing, that one closes first) (2026-11-09)
- [ ] Resume on a title whose last link no longer plays opens its source list on the TV instead
- [ ] Details on the TV opens the title's page on the TV
- [x] Demo TV: Resume plays a pretend title from its saved place (2026-11-09, from Nick's screenshots)

### Phone remote: QR pairing (phase 4)

- [x] Nuvio's Settings › Keyboard shortcuts › Phone remote shows a QR code ("Or scan to pair") under the pairing code, while Phone remote is on (2026-11-09)
- [x] In the app, "Scan the TV's code" on the PC list opens Google's scanner (no camera permission asked); scanning the TV's code pairs and connects with no typing (2026-11-09)
- [ ] The phone's own camera app, pointed at the code, offers to open Nuvio Remote, which then pairs
- [ ] "Scan the TV's code instead" on the code screen works the same
- [ ] New code on the PC changes the QR code too; an old QR then says the code isn't right
- [ ] Demo TV: scanning the demo QR picture (sent in the chat) pairs with Demo TV

### Phone remote: pick the source on the phone (phase 5)

- [x] When a film or episode's source list opens on the TV, the remote shows the same list in the same order: name, addon, size, an Instant tag for cached ones, and the file details (2026-11-09)
- [ ] It fills in as the TV finds sources ("Finding sources… N so far" with a thin bar), then shows the total
- [x] Tapping a source plays that one on the TV; greyed-out rows (the ones the TV greys) do nothing (2026-11-09)
- [ ] The Instant tag at the top shows only the cached sources
- [ ] The header tag (or the Pad key) swaps to the touchpad and back, for anything else on the TV's list
- [ ] Sources during a film (the player's sources panel) shows its list on the phone too
- [ ] Back closes the list on the TV and the phone goes back to the remote
- [ ] Demo TV: opening a title shows four pretend sources (the last greyed); picking one plays it

### Phone remote: search everything and voice search (phase 6)

- [x] Watch tab: typing a search (and pausing) adds "From your addons" under your library's matches: what Nuvio's Search tab finds, with posters, filling in while it searches (2026-11-09)
- [x] Tapping a result opens its page on the TV, and the phone says its name (2026-11-09)
- [x] The mic in the Watch and Music search boxes (while empty) opens the phone's voice typing; what you say becomes the search (2026-11-09)
- [ ] Remote tab: Search, then the mic beside the text box, types what you say into the TV's search box
- [ ] A search on the phone while the TV is on its own Search tab does not upset it
- [ ] Demo TV: searching "signal" shows The Long Signal and two pretend catalog results

### Phone remote: wake the PC (phase 7)

- [ ] Once connected with this Nuvio, the remote remembers the PC's network card (no setting needed on the phone)
- [ ] With the PC asleep, the remote shows "Asleep or off?" with a Wake tag; Wake sends the wake-up, shows "Waking it up", and connects once the PC is up
- [ ] On the PC: Wake on Magic Packet on for the network card (Device Manager) and Wake-on-LAN in the BIOS, as Settings › Phone remote now says
- [ ] If it does not wake within about 90 s, the remote says so
- [ ] Demo TV: More › Demo TV › Sleep drops the phone; Wake brings it back after a few seconds

### Phone remote: music queue and lyrics (phase 8)

- [ ] Music screen keys are Back, Queue, Lyrics, Dark, More (Search is on the pad view now)
- [ ] Queue opens a sheet of the TV's queue (cover, title, artist, length), the playing song highlighted and scrolled to
- [ ] **BROKEN** Music tab (2026-11-09): tapping an album only plays the whole album; there is no way to play one of its songs
  - [ ] Re-check after the fix: the album sheet lists the album's songs under Play / Shuffle / Add to queue; tapping one plays the album from that song on the TV
- [ ] Tapping a song in the queue plays it on the TV; Play next and Remove (on songs still to come) change the queue, and the sheet shows the change
- [x] Lyrics (or a tap on the cover) shows the song's lyrics in the cover's place; timed ones follow the song with the sung line bright and kept in view (2026-11-09)
- [ ] Lyrics follow to the next song by themselves; a song with none says so
- [ ] Demo TV: an album's queue and pretend timed lyrics

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
