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
- [x] Watch tab sometimes lists the Torbox cloud files as "Your library" (no section chips, about 250 titles), and tapping one fails on the TV; other times the real library (My Shows, My Movies..., about 760) (2026-11-09) - fixed (2026-11-09)
  - [x] Re-check after the fix: the Watch tab always ends up with the real library and its section chips (it keeps asking for up to half a minute after Nuvio starts, and again when the tab is reopened); Torbox files are no longer in it (2026-11-09)
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

- [x] Dark mode: the Dark key (film or music screen, or More) turns the phone black and dims it (Medium, 15%, to start), hides the status and navigation bars, and the screen stays on (2026-11-09)
- [x] Dark mode hints (tap, swipe, hold) show for a few seconds and fade; Back brings them back instead of leaving (2026-11-09)
- [x] Dark mode brightness: Low / Medium / High under the hints (Back shows them again) changes the phone's brightness at once, is kept for next time, and tapping one does not also pause the film (2026-11-09)
- [x] Dark mode, a film: tap is play/pause, swipe left/right is 10 s back/ahead per step with a tick each, swipe up/down is volume; each shows faintly in the middle (2026-11-09): tap works, but no swipe does anything, in any direction - fixed (2026-11-09)
  - [x] Re-check after the fix: swipes left/right (10 s, or songs for music) and up/down (volume) work, each with a tick; hold for a second leaves (2026-11-09)
- [x] Dark mode, music: swipe left/right is the previous/next song (one per swipe) (2026-11-09)
- [x] Dark mode: holding a finger anywhere for a second draws a faint ring and leaves; brightness and the bars come back (2026-11-09)
- [x] Demo TV: Try the demo TV (on the PC list) asks for the code 123456, then connects as "Demo TV" and stays in the paired list (2026-11-09)
- [x] Demo TV: the "Demo TV received" strip shows each key, swipe and typed text (2026-11-09, from Nick's screenshots)
- [x] Demo TV: More > Demo TV plays a film or an album, stops it, and changes the theme (the app follows) (2026-11-09)
- [x] Film screen with the demo strip (2026-11-09, from Nick's screenshots): the D-pad / touchpad panel is squeezed down to its title bar, with no pad left to use - fixed (2026-11-09)
  - [~] Re-check after the fix (2026-11-09, from Nick's screenshots): the keys stay whole at the bottom, but the pad was still pushed off the screen
  - [x] Re-check after the second fix: the film screen has no pad on it any more; the Touchpad tag in its header (as on the music screen) swaps to the full-size pad and Now playing swaps back; Sources brings the pad up by itself (2026-11-09)
- [x] Music screen (2026-11-09, from Nick's screenshot): the Back / Search / Dark / More keys are cut in half by the tab bar - fixed (2026-11-09)
  - [x] Re-check after the fix: the cover shrinks to fit, and the keys show whole above the tab bar (2026-11-09, from Nick's screenshot)
- [x] Demo TV: Watch and Music tabs show the pretend library (with photos when online); opening a title plays it after 2 s; an album plays, shuffles, next and previous work (2026-11-09, from Nick's screenshots)
- [x] Demo TV: the lock-screen controls show the pretend film or song and work (2026-11-09)

### Phone remote: Continue watching (phase 3)

- [x] The Watch tab opens with a Continue watching row (landscape pictures, a progress line, the episode and time left), the same titles as Nuvio's Continue Watching (2026-11-09)
- [x] The "Opening … on the TV" note shows at the top of the remote, clear of the keys (it covered them at the bottom) (2026-11-09)
- [x] Tapping one opens a sheet: poster, title, episode, how far in, and the source it last played from (2026-11-09)
- [x] Resume on the TV plays it straight away from the same source, from where it stopped (with a film already playing, that one closes first) (2026-11-09)
- [x] Resume on a title whose last link no longer plays opens its source list on the TV instead (2026-11-09)
- [x] Details on the TV opens the title's page on the TV (2026-11-09)
- [x] Demo TV: Resume plays a pretend title from its saved place (2026-11-09, from Nick's screenshots)

### Phone remote: QR pairing (phase 4)

- [x] Nuvio's Settings › Keyboard shortcuts › Phone remote shows a QR code ("Or scan to pair") under the pairing code, while Phone remote is on (2026-11-09)
- [x] In the app, "Scan the TV's code" on the PC list opens Google's scanner (no camera permission asked); scanning the TV's code pairs and connects with no typing (2026-11-09)
- [x] The phone's own camera app, pointed at the code, offers to open Nuvio Remote, which then pairs (2026-11-09)
- [x] "Scan the TV's code instead" on the code screen works the same (2026-11-09)
- [x] New code on the PC changes the QR code too; an old QR then says the code isn't right (2026-11-09)
- [x] Demo TV: scanning the demo QR picture (sent in the chat) pairs with Demo TV (2026-11-09)

### Phone remote: pick the source on the phone (phase 5)

- [x] When a film or episode's source list opens on the TV, the remote shows the same list in the same order: name, addon, size, an Instant tag for cached ones, and the file details (2026-11-09)
- [x] It fills in as the TV finds sources ("Finding sources… N so far" with a thin bar), then shows the total (2026-11-09)
- [x] Tapping a source plays that one on the TV; greyed-out rows (the ones the TV greys) do nothing (2026-11-09)
- [x] The Instant tag at the top shows only the cached sources (2026-11-09)
- [x] The header tag (or the Pad key) swaps to the touchpad and back, for anything else on the TV's list (2026-11-09)
- [x] Sources during a film (the player's sources panel) shows its list on the phone too (2026-11-09)
- [x] Back closes the list on the TV and the phone goes back to the remote (2026-11-09)
- [x] Demo TV: opening a title shows four pretend sources (the last greyed); picking one plays it (2026-11-09)

### Phone remote: search everything and voice search (phase 6)

- [x] Watch tab: typing a search (and pausing) adds "From your addons" under your library's matches: what Nuvio's Search tab finds, with posters, filling in while it searches (2026-11-09)
- [x] Tapping a result opens its page on the TV, and the phone says its name (2026-11-09)
- [x] The mic in the Watch and Music search boxes (while empty) opens the phone's voice typing; what you say becomes the search (2026-11-09)
- [x] Remote tab: Search, then the mic beside the text box, types what you say into the TV's search box (2026-11-09)
- [x] A search on the phone while the TV is on its own Search tab does not upset it (2026-11-09)
- [x] Demo TV: searching "signal" shows The Long Signal and two pretend catalog results (2026-11-09)

### Phone remote: wake the PC (phase 7)

- [ ] Once connected with this Nuvio, the remote remembers the PC's network card (no setting needed on the phone)
- [ ] With the PC asleep, the remote shows "Asleep or off?" with a Wake tag; Wake sends the wake-up, shows "Waking it up", and connects once the PC is up
- [ ] On the PC: Wake on Magic Packet on for the network card (Device Manager) and Wake-on-LAN in the BIOS, as Settings › Phone remote now says
- [ ] If it does not wake within about 90 s, the remote says so
- [ ] Demo TV: More › Demo TV › Sleep drops the phone; Wake brings it back after a few seconds

### Phone remote: music queue and lyrics (phase 8)

- [x] Music screen keys are Back, Queue, Lyrics, Dark, More (Search is on the pad view now) (2026-11-09)
- [x] Queue opens a sheet of the TV's queue (cover, title, artist, length), the playing song highlighted and scrolled to (2026-11-09)
- [x] Music tab (2026-11-09): tapping an album only plays the whole album; there is no way to play one of its songs - fixed (2026-11-09)
  - [x] Re-check after the fix: the album sheet lists the album's songs under Play / Shuffle / Add to queue; tapping one plays the album from that song on the TV (2026-11-09)
- [x] Tapping a song in the queue plays it on the TV; Play next and Remove (on songs still to come) change the queue, and the sheet shows the change (2026-11-09)
- [x] Lyrics (or a tap on the cover) shows the song's lyrics in the cover's place; timed ones follow the song with the sung line bright and kept in view (2026-11-09)
- [x] Lyrics follow to the next song by themselves; a song with none says so (2026-11-09)
- [x] Lyrics on the phone look like the stage's Loud style: each line big in its own face and colour, words outlined and landing as sung (on the beat for word-timed songs); the next line small underneath (2026-11-09): works, but the words were too big: the next line went off the box and the words ran under the List tag - fits and shows whole lines after the third fix (2026-11-09)
  - [~] Re-check after the fix (2026-11-09): fits now, but long lines were cut short with … instead of shown whole
  - [~] Re-check after the second fix (2026-11-09): whole lines now, but the last row of a two- or three-row line was cut off at the bottom ("ALL FOR NOTHING", "DID YOU REALLY WANT ?")
  - [x] Re-check after the third fix: every row of a long line is fully visible, none cut at the bottom, and the next line still shows under it (2026-11-09)
- [x] The List / Loud tag in the lyrics' corner switches to the scrolling list and back (2026-11-09)
- [x] Demo TV: an album's queue and pretend timed lyrics (2026-11-09)

### Phone remote: a show's episodes on the phone

- [x] Watch tab: tapping a show (not a film) opens its sheet on the phone with season tags and the episodes (picture, number, title, how far watched), instead of only opening it on the TV (2026-11-09)
- [x] It opens on the first season with an episode not yet watched through (2026-11-09)
- [x] Tapping an episode plays it on the TV as its details page would: its source list comes up on the remote to pick from (2026-11-09)
- [x] Details on the TV in the sheet still opens the show's page on the TV; a film still opens straight on the TV (2026-11-09)
- [x] Demo TV: Harbour Lights shows two seasons of six episodes, the first three watched (2026-11-09)

### Phone remote: add-on subtitles

- [x] The Subtitles tile shows the add-on subtitle's name (English (SubSens…) once one is on (2026-11-09)
- [x] Hold the Subtitles tile: a sheet lists Off, the file's own subtitles, then each add-on's (under its name), the one in use marked "On"; with none yet it says it is asking and fills in as the add-ons answer (2026-11-10)
- [x] Tapping a row in that sheet switches the TV's subtitles to it (an add-on one loads and shows), the tile follows, and Off turns them off; a quick tap on the tile still cycles to the next (2026-11-10)
- [x] Scrolling the sheet's list does not pick a row by accident (2026-11-10)
- [ ] Demo TV: hold Subtitles shows a list of six; picking one changes the tile
- [x] Hold the Audio tile: a sheet lists the film's audio tracks (the one playing marked "On"); tapping one switches the TV's audio and the tile follows; a quick tap still cycles to the next (2026-11-10)
- [x] The audio list leaves out commentary and audio-description tracks, as the TV's own audio panel does (2026-11-10)

- [ ] A film with no built-in subtitles (the screenshot case): once the add-on subtitles are found, the Subtitles tile reads "Off" instead of "Tap for the next"; tapping it picks the first add-on subtitle and the tile shows its name; further taps go through the rest

### Phone remote: Sources cards

- [x] Open a film's or episode's source list on the phone: each source is a card with the add-on's name on top, then badges for resolution (4K, 1080p), HDR or Dolby Vision, codec (HEVC, H.264) and size (2026-11-10)
- [x] The raw release name sits on one muted line under the badges; holding the card shows all of it, holding again hides it (2026-11-10)
- [x] A quick tap on a card still plays that source on the TV (and holding does not play it); greyed cards still do nothing (2026-11-10)
- [x] The filter row under the title (All, Instant, 4K, HDR, Dolby Vision) only offers filters that match something; tapping one narrows the list, tapping it again (or All) shows everything (2026-11-10)
- [ ] A source whose name says nothing readable shows its name as before, not an empty card
- [ ] Demo TV: the first source shows 4K, Dolby Vision and HEVC; the second shows 1080p, HDR10 and H.264

### Phone remote: film extras, music extras and the More sheet

- [x] Film screen: top panel, Subtitles and Audio tiles, play buttons, volume slider and Skip / Next episode (when shown) fit without scrolling on your phone; a smaller phone may scroll a little (2026-11-10)
- [x] Under the play button a slim volume slider changes the TV's volume, and the number follows (2026-11-10)
- [x] More > Playback has Speed chips on a film (0.75x to 2x); tapping one changes the film's speed on the TV and the chip lights; the TV's own speed panel agrees; Speed is not shown for music (2026-11-10)
- [ ] During a show's intro (or recap, credits) a "Skip intro" (or "Skip credits", "Skip recap") button shows above the card; tapping it jumps the TV past it; it disappears once the part is over
- [ ] On a show with a next episode, "Next episode" shows and tapping it starts that episode on the TV; on a film it is not there
- [x] More > Playback > Sleep: 15 / 30 / 60 min starts a timer and the row says "N min left"; when it ends the TV (or music) pauses; Off cancels it (2026-11-10)
- [x] Music screen: a row of Shuffle, Repeat, Like and Radio under the play button. Shuffle and Repeat (off, all, one) change the TV's queue and light up; Radio starts a radio from the playing song (2026-11-10)
- [x] Like shows only when signed in to YouTube Music on the TV; tapping it fills or empties the heart and the TV's heart agrees (2026-11-10)
- [x] More sheet is in three groups (Playback with volume and sleep, Display with pad and dark mode, Device with Type and Other PC) and scrolls on a small phone (2026-11-10)
- [ ] Demo TV: the film shows "Skip intro" for its first minute; speed, sleep, shuffle, repeat and like all answer

### Phone remote: Tonight on the phone (Nov 10)

Needs this Nuvio and the new NuvioRemote app together.

- [ ] With the Tonight picker on the TV, the phone's Remote tab says "Choosing tonight" and shows Three Questions and Three Cards as two big buttons (the one used last marked LAST TIME)
- [ ] Tapping Three Questions starts it on the TV; the phone shows the same question, "Question 2 of 3", "214 → 41 left" and two big answers
- [ ] Tapping an answer answers it on the TV; "Either is fine" and "Back a question" do what Down and Back do on the TV; the buttons never move between questions
- [ ] After the questions, the three cards show on the phone with their posters, SAFE / NEW TO YOU / WILDCARD, the reason and "ends 23:06"; tapping a card plays it on the TV, Details opens its page
- [ ] Reshuffle on the phone deals three new cards once, then says "No reshuffles left"
- [ ] Opening Knockout, Coin Toss or another mode on the TV makes the phone say it is on the TV, with Back to the start; the header tag swaps to the pad and back
- [ ] Choosing something with the TV remote while the phone is open keeps the phone in step
- [ ] Once a title starts playing, the phone switches to the film screen as usual

## Music playback

- [x] Gapless: songs in an album run straight into each other (2026-11-08)
- [x] Volume is right from the first second, no jump a few seconds in (2026-11-08)
- [x] Constant volume for music: no gain riding during a song (2026-11-08)
- [ ] Constant volume for trailers: check they get the same treatment as music (Nick, 2026-11-08)
- [x] Lossless: an album playing from YouTube Music switches to the addon's lossless copy; tiles show the Lossless badge (2026-11-08)
- [x] Calm lyrics stay in order without jumbling (2026-11-08)
- [x] After the upstream merge (mpv 0.42, FFmpeg 9.0.2): gapless, steady volume and the Beat Grid all still work (2026-11-09)

## Mini player

- [x] Open dock: cover-tinted seek bar, beat dot, Radio tag, "Keep going?" inside the card (2026-11-08)
- [x] Heart likes the song on YouTube Music, and un-likes on a second click (2026-11-08)
- [x] Quality pill, Previous, times at both ends of the bar, Up next with Play next / Remove, Shuffle, Repeat, Clear (2026-11-08)

## Radio

- [x] "Keep going?" near a radio's end, and Keep going adds more songs (2026-11-08)
- [~] Stations and mixes play from search; Start radio on songs and artists (2026-11-08): works, but genre keyword
  detection needs to be better. Plan: remove the Ladder and move genre into search. Built (2026-11-09), see Music home
  and search below.

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
- [x] The Ladder is gone from the Music tab: no Ladder pills beside the search kinds, no "Ladder starters" or "Continue your ladder" on the old search's start screen, no "Ladder from here" or "Climb into" buttons in the preview pane (2026-11-10)
- [x] Stage: tapping a genre chip closes the stage, opens the Music tab and searches that genre (the genre card shows) (2026-11-10)
- [x] Type a genre (shoegaze, post punk, trip hop): a genre card leads Top Match, with "IN YOUR LIBRARY" artists and "TOP ARTISTS" under it; OK on the card plays a radio or playlist for it (2026-11-10)
- [x] Spelling and word order are forgiven: "postpunk", "hip hop music", "rock indie", "punks" all show the card (2026-11-10)
- [x] Moods, activities and decades: "chill", "workout music", "90s", "90s rock" show a card (and "90s rock" plays something sensible) (2026-11-10)
- [x] An artist wins a clash: with an artist as the top match (try "Soul" or "Dance" if you own one), the artist stays Top Match and the genre card sits second (2026-11-10)
- [ ] With the offline MusicBrainz copy installed, a niche tag the hand list lacks (try "vaportrap", "witch house") still gets a card; without the copy it does not
- [x] Tapping a TOP ARTISTS row searches that artist (2026-11-10)

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

## Saving settings (hardening, Nov 10)

- [ ] Change a setting (e.g. a Music setting), then close Nuvio with the X and reopen it: the change is still there (stores now force their data to disk before the swap)
- [ ] After a normal day of use, nothing about settings, library, play history or music facts has reset on its own; if something has, say which (a zero-length store now falls back to its `.bak`, and the log says "is empty; recovering")
- [ ] Phone remote after the connection limit: the phone still pairs, reconnects after the screen was off, and a second phone can connect at the same time (the PC now refuses more than 16 open connections and gives a new one 30 s to pair)

## Artist page and Previously on (Nov 10)

- [ ] Artist page, Essentials: an artist whose YouTube Music list is short (Gang Starr had 5) now shows up to 10 songs; the extra ones come after YouTube Music's own, are real songs you can play, and Play Essentials plays them too
- [ ] Artist page, Essentials: an artist with a full list of 10 looks and plays exactly as before
- [ ] Previously on: pick up a half-watched episode of a show (Continue Watching or the phone's Resume) after more than your break (3 days by default): the "Previously on" cards show before it carries on from where you stopped
- [ ] Previously on: pick up a half-watched episode you left a few hours ago: no cards, it just resumes (with "Only after a break" off, every resume gets the cards)
- [~] Artist page, Story tab (third tab beside Essentials and Discography): a timeline of a few points (DEBUT, FIRST ALBUM, BEST KNOWN, COMEBACK, LATEST), each with the year, a line saying why, and the record from then; OK on a row opens that record; More menu works on a row (2026-11-10): works, but the years did not make sense (Jamiroquai: rows evenly spaced, "back after 7 years" from an album not on the line, 16 albums in the heading vs 17 on the right, a 2025 single with no cover as LATEST)
  - [ ] Re-check (Jamiroquai): a year line above the rows runs 1992 to 2017 to scale, a tick for each of the 8 studio albums, a dot per point, the decades marked, and the 2010 to 2017 gap dashed with "7-year break" over it
  - [ ] Re-check: the heading reads "8 studio albums · ... · 1992–2025" and the right panel's tile says "Studio albums 8" (reissues, deluxe editions, best-ofs and live albums no longer count)
  - [ ] Re-check: COMEBACK reads "Back 7 years after Rock Dust Light Star (2010)"
  - [ ] Re-check: LATEST is never a release with no cover that plays nowhere (Jamiroquai's 2025 single is gone; Automaton is the last point)
- [ ] Artist page, Story tab: nothing moves or resizes between artists (every row the same height, the record slot the same width, long titles cut); an artist with almost no dated releases says so instead of showing an empty panel

## Profiles and the phone app (Nov 10)

- [ ] Set something different in two profiles (Settings, Gamepad dead zone; Games, backdrop style; Playback, anime filler badges), then switch profile from the profile picker: each profile shows its own values straight away, without restarting Nuvio
- [ ] Phone remote: pairing still works after the update (scan the QR or enter the address and PIN), and it reconnects the next day without asking again
- [ ] Home, the movie/show page and the album page (JIT flag for the biggest screens): after about a minute of use, scrolling Home and opening a title or an album feels the same or smoother than before; say if Home hitches in its first minute, or Task Manager shows Nuvio's CPU staying high after it has settled

## Settings search

- [ ] Search "subtitle size", "outline", "blur" and "vertical offset": each result opens Player settings at the Subtitle rendering section
- [ ] Search "failover", "player ui scale", "controls layout", "seek bar handle": each lands on its section of Player or Streams settings
- [ ] Search "tvdb", "hero artwork", "proxy address", "links to prepare", "minimum score": each opens the right page at the right section
- [ ] Search "music addon link", "daily allowance", "export facts", "dim to", "theme song volume": each opens its page at the right section
- [ ] Search "mpv options", "low vram", "audio passthrough", "close to notification area": each opens its page (desktop only)

## Tonight picker (phases 0 and 1, Nov 10)

Turn it on in Settings > Random Play > Tonight picker > "Open on Tonight".

- [ ] **BROKEN** Back from Three Cards (2026-11-10): Esc on the keyboard did nothing (the Android remote's Back works); fixed the same evening (Back now acts on the key coming up)
  - [ ] Re-check: the remote's Back (and a pad's B, the phone remote's Back, the mouse's back button) leaves every Tonight mode: cards and questions step back, the modes return to the launcher; on the launcher Back does what it did before
- [ ] Typing "Tonight" or "bedtime" in the Settings search finds the Tonight picker rows and opens Random Play at that section (also listed first in the Random Play page's sidebar)
- [ ] With the switch on, Nuvio opens on "How shall we choose tonight?" instead of the Home rows; with it off, Home is as before
- [ ] The launcher's eight tiles keep their size; the six unbuilt ones are dimmed with "COMING SOON", and OK on one says it is coming later
- [ ] The tile used last time has "LAST TIME" on it and is selected when the launcher opens
- [ ] "Just browse everything" goes to the normal Home rows; pressing Home again brings the picker back
- [ ] Up from the tiles reaches the bedtime chip; Left/Right on it move bedtime by 15 minutes, and the Bedtime slider in Settings matches
- [ ] Three Questions: the first question is always "A film, or a show?"; Left and Right answer at once, Down means "Either is fine", Back goes back a question
- [ ] The two questions after it differ from one night to the next, and a question is never one where every title would give the same answer
- [ ] The "214 → 41 titles left" count and the SO FAR chips follow the answers
- [ ] After the questions, three cards: SAFE (something you're watching or have seen), NEW TO YOU, WILDCARD; never the same title twice
- [ ] Each card shows the right poster and a sensible line under it (year, length, "ends 23:06" for films; "Next: S2 E4 · 30 min eps · 3 before bed" for shows)
- [ ] No film that would end well after bedtime is dealt (with bedtime still at least 20 minutes away)
- [ ] OK on a card plays it (a film from where you left it, a show's next episode); Down opens its page instead
- [ ] Reshuffle deals three different titles once; after that it says "No reshuffles left tonight" until tomorrow evening
- [ ] Three Cards straight from the launcher deals from everything that fits before bed
- [ ] Titles marked "Not interested" in Discover never appear
- [ ] A mouse works too: hovering a tile or card selects it, clicking plays or opens

### Tonight picker: the What to watch button and a way out (Nov 10 evening)

- [ ] A "What to watch" button (sparkle icon) sits beside Search in the top bar and the sidebar; it opens the Tonight launcher over Home, Library or Discover (from Music or Live TV it goes to Home first)
- [ ] Every Tonight screen has an ✕ in the top-right corner; clicking it closes Tonight and the rows behind come straight back
  - Nov 12: the ✕ is gone (user: "Just browse everything" is the way out); nothing to check here now
- [ ] Back on the launcher closes Tonight (Back inside a mode still steps back first)
- [ ] With "Open on Tonight" off, Nuvio opens on Home; with it on, Nuvio opens on Tonight once at start, and after closing it stays closed until the button is pressed
- [ ] The button can be moved in Settings > Appearance > Navigation order ("What to watch")
- [ ] Phone remote: a "What to watch?" button above the pad on the Remote tab opens Tonight on the TV, and the phone switches to the Tonight mirror

### Tonight picker: the launcher redesign with backdrops (Nov 12)

- [ ] Each of the eight launcher tiles shows a film or show backdrop, no icons, with the mode's name, its line and an arrow at the bottom left and LAST TIME at the top left; the eight backdrops are all different titles
- [ ] The backdrops fit the mode: The Pile shows something in your library you have never started, Ends By something that ends before bed
- [ ] The chosen tile glows all round (no bright border) in its own backdrop's colour: a green backdrop glows green; the glow fades as you move between tiles and lies over the gaps, not under the next tile
- [ ] The screen behind is a colour wash (no picture) of the chosen tile's backdrop colours, easing from one to the next; the header text stays readable over any colour; nothing on the screen moves or changes size
- [ ] Tonight fills the whole screen from the very top (no strip left empty for the top bar), and there is no ✕ on any Tonight screen
- [ ] The header reads "How shall we choose tonight?" ("tonight?" tinted) over "A little inspiration goes a long way.", with the day and time above it
- [ ] Before the titles have loaded (first open after start) the tiles are plain glass and fill in with backdrops when they arrive
- [ ] Under the key hint is a thin separator, and "Just browse everything →" (a rounded pill) sits straight beneath it rather than at the foot of the screen; Down from the tiles reaches it and OK closes Tonight
- [ ] The tiles' rounded corners are smooth, not stair-stepped, on every tile (with and without a picture)

### Tonight picker: the modes redesigned (Nov 12)

Every mode below has a "← Back" button in a strip under its content (hint line, separator, buttons), as the launcher does.

- [ ] Three Questions: the background is a colour wash; hovering an answer glows it in its colour (amber left, teal right) and shifts the wash toward what that answer would leave
- [ ] Three Questions: the two answer boxes mirror each other: "◀ LEFT" top left and "RIGHT ▶" top right, the left box's answer right-aligned and the right box's left-aligned; "Exciting" fits in its box
- [ ] Three Cards: the cards sit higher, the chosen card glows in its poster's colour (no accent border) and the background takes its colours
- [ ] Three Cards: Back, Its page and Reshuffle sit in a row under the cards; Down from a card reaches them (on Its page), Left/Right move along them, Up goes back to the card, OK presses one
- [ ] Knockout: the bracket on the left is small posters, eight down the first column, then four, two and the winner; the pair in play glows, losers fade back, unplayed slots are empty glass
- [ ] Knockout: the background mixes the two posters' colours; hovering a poster glows it; Undo last pick and Taste each work from the buttons
- [ ] Ends By: the rows fill the page down to the buttons (more than six on a big screen), the mouse wheel moves up and down the list, and the last hour label and BED are never cut off at the right
- [ ] Ends By: the chosen row glows in its title's colour and the background takes its colours; Bed earlier / Bed later / Play it buttons work
- [ ] Channel Flick: a "← Back" button sits at the left of the buttons and returns to the launcher
- [ ] Coin Toss: the poster and its panel are centred as one block, the poster glows, the background takes its colours, and the buttons (with Back) are in the strip below
- [ ] The Pile: a centred 3 x 3 grid of posters, each with its name and how long it has waited underneath; arrows and the wheel move (a row at a time with the wheel); the chosen poster glows and the background takes its colours
- [ ] Three Cards: the chosen card's glow hugs its rounded corners, with no faint square line round the poster at the corners
- [ ] Three Cards: SAFE / NEW TO YOU / WILD CARD are headings above each poster (brighter on the chosen one), not printed on the picture; the row of cards and the buttons below do not move
- [ ] Knockout: the bracket's posters have no glow; the pair in play has a thin edge in its own colour instead, and neighbouring posters never overlap
- [ ] Three Cards: moving from one card to the next, the old card's bloom fades all round, with no green or blue arcs left at its corners
- [ ] Three Cards: the button under the cards reads "Detail page" (Coin Toss's hint says "▼ opens the detail page")
- [ ] Knockout: the heading and its line sit where Three Cards' do, the two posters and "vs" are centred on the screen at the same height as Three Cards' cards, the buttons sit straight under them, and the bracket fills the left without touching the posters
- [ ] Settings > Random Play > Tonight picker: "Cards pick from the top" slides from 1 to 20 (5 to start); at 1 the same three cards come up every visit, at 15 or more they change from night to night
- [ ] Settings > Random Play > Tonight picker: turning on "Add Top picks for you", "Add Because you watched" and "Add More like your favourites" brings those Discover titles into Three Cards (a New to you card can say "Because you watched ..." or "A top pick for you"); all three are off to start
- [ ] With a Discover source on, Channel Flick shows the Discover reason ("BECAUSE YOU WATCHED ...") over those titles; Settings search finds the new rows by "top picks" and "pick from"
- [ ] The Pile: a small panel at the top right shows the chosen title's wait, name, length and genres; Down from the bottom row reaches Back / Finally watch it / Let it go, and Let it go needs pressing twice

### Tonight picker: Coin Toss and Knockout (phase 2, Nov 10)

- [ ] The launcher's Knockout and Coin Toss tiles are no longer "coming soon" and open their modes
- [ ] Coin Toss: one title with its poster, name and "ends 23:06" line, and a ring counting down from 10; doing nothing plays it
- [ ] Left tosses again with a new title and a fresh countdown; a vetoed title never comes back that evening
- [ ] The "What your no's said" panel lists the last three vetoes with a reason ("too long", "too heavy"...) and a lean ("leaning shorter and lighter"), and the next tosses follow it
- [ ] Up holds the countdown (the ring greys and shows II) and Up again restarts it; OK plays at once; Down opens the title's page
- [ ] Knockout: eight titles (fewer only if fewer fit before bed) in a bracket on the left; two at a time in the middle, with the pair highlighted in the bracket
- [ ] Left or Right keeps one; losers are struck through; three rounds, the last called "THE FINAL"
- [ ] Back undoes the last pick (Back with nothing picked goes to the launcher)
- [ ] The winner shows with a five-second ring and then plays; OK plays at once, Back reopens the final
- [ ] Clicking a poster with the mouse keeps it in Knockout

### Tonight picker: Channel Flick and trailer tastes (phase 3, Nov 10)

- [ ] The Channel Flick tile opens trailers one after another in a large frame, with sound at the hero trailers' volume; music pauses while they play
- [ ] Under the frame: where the title comes from (FROM YOUR LIBRARY, TRENDING THIS WEEK, YOU'RE ON S2 E4), its name and "ends 23:06"; the frame and the lines under it never move
- [ ] When a trailer ends the next channel starts by itself; a title with no trailer says so briefly and is skipped
- [ ] Right flicks to the next channel; the three UP NEXT pictures in the top corner match what comes next
- [ ] Left says "won't come up again" and the title is gone from Tonight and from Discover's rows (it is in Not interested)
- [ ] Up saves the title to your library ("saved for later") and moves on; OK plays the title
- [ ] Keys still work while a trailer plays (the trailer never takes the remote's focus)
- [ ] Knockout: Down plays a 20-second taste of the left title, Down again (or the end) the right one, then back to the posters; Left/Right still keep one while tasting; Back stops the tastes
- [ ] Three Cards: holding OK on a card plays its trailer in place of the cards; a quick OK still plays the title; Back returns to the cards

### Tonight picker: Ends By and The Pile (phase 4, Nov 10)

- [ ] Ends By opens on "It's 21:10. Bed at 23:30." with hour marks, a white NOW line and a BED line
- [ ] Films are bars from now ending in "ends 22:52"; shows are a block per episode that fits ("3 eps · 22:40") with one faded block past bed
- [ ] Rows that fit come first (the one that fills the evening best on top); ones that run past bedtime are faded at the bottom
- [ ] Up/Down move through the rows (six at a time; the list moves, rows never resize); Left/Right move bedtime and the bars and order update; OK plays the row
- [ ] The Pile lists library titles you have never started, newest on top, each spine with "added 3 days ago" ... "added 8 months ago"; older spines are dustier and fainter
- [ ] The chosen spine slides out and the panel shows how long it has waited, its poster, length and genres
- [ ] OK plays it; Left once asks "again to let it go", Left again removes it from the pile and from Discover's suggestions (Not interested)
- [ ] With nothing on the pile it says so instead of showing an empty panel

### Tonight picker: The Map and the AI (phase 5, Nov 10)

- [ ] The Map tile opens a field with no rows: LIGHT left, HEAVY right, SOMETHING NEW top, COMFORT bottom, and a ring (the lens)
- [ ] The lens starts over the kind of thing you keep; the arrows move it; titles near it grow and show their posters, the rest stay small and dim
- [ ] The side panel shows the title nearest the lens (picture, name, "ends 23:06", "Light, and new to you", up to two nearby titles) and its lines never move
- [ ] Light things (comedies, family) sit left and heavy ones (war, drama) right; things you're watching sit low, trending ones you've never seen high
- [ ] OK plays the title under the lens; clicking a small tile moves the lens to it, clicking the one under the lens plays it
- [ ] The bedtime chip at the top says "Ends before 23:30" and nothing on the field runs past it
- [ ] Settings > Random Play > Tonight picker > "Let the AI refine The Map": turning it on asks for AI consent if not given yet
- [ ] With it on and The Map open, the panel's footer counts titles "placed with the AI's reading of their tone" and some tiles shift sideways; a dark comedy moves right of a family comedy
- [ ] With it off, nothing is sent to the AI (no "Tonight map" lines in the log)


## Top bar search predictions (Nov 11)

- [ ] Typing in the top bar's Search (films and shows): the panel under the box swaps the recent searches for predictions, recent searches that match first (clock icon), then TMDB titles (film icon), with the typed part in bold
- [ ] Enter with nothing highlighted searches exactly what was typed
- [ ] Down/Up walk the predictions and put each one in the box; Up from the top one gives back what was typed; Enter runs the one shown
- [ ] Tab puts the top prediction in the box without searching; Tab again moves to the next and wraps round; Tab never jumps to Discover from the search box
- [ ] Mouse: hovering a prediction lights it; clicking runs it; "Clear history" shows only under the recent searches (empty box)
- [ ] Escape hides the panel first (and gives back the typed text if you were walking it); a second Escape leaves the search
- [ ] The same panel works on the Music tab (music note, YouTube Music completions), YouTube tab (YouTube autocomplete) and Live TV (channel names)
- [ ] The search box no longer has the Discover (compass) button; Tab outside search still opens Discover

## Album and artist page polish (Nov 12)

- [ ] Album page song list: a small record icon marks a song released as a single, just left of an always-visible Add to playlist icon; no SINGLE text tag; the lengths still line up under TIME
- [ ] Add to playlist icon brightens on hover and when reached with the keyboard (Right from the row), and opens the playlist picker
- [ ] New artist page hero: round icon buttons (Play Essentials, Shuffle all, Play latest, Radio, Add to library, Links, Refresh) with their names on hover; Play Essentials still takes focus when the page opens
- [ ] New artist page hero: the genre tags are a little dimmer than before
- [ ] Artist page left panel: Facts is now a short line, the years active, the release span and quiet tags (mood in the accent), with no FACTS heading or label column
- [ ] Release covers on the artist page and shelves show the album page's lossless icon (record with sound wave) instead of HQ
- [ ] Album page panels: no icon/label line or AI/Library pills at the top, centred titles and headings like the artist page, no box around the story, Why it matters or Other versions rows, no "Written by AI" small print; Edit/Save/Cancel are plain text
- [ ] Credits (was Who made it): the chosen person's songs have only a thin edge mark, no coloured wash behind the song list
- [ ] Hits tab (second tab, was Best of): the artist's best songs (one on this album plays here, others open their album) and best albums as covers; the side panels are Song by song's

## Album tabs, Stage side panel and video lyrics (Nov 12)

- [ ] Album page tabs read: Song List, Hits, History, Cut to Fit, Sonic Map, Lineage, Credits
- [ ] Stage side panel: the artist on one line and the album and year on the next, cut short with an ellipsis rather than wrapped into pieces (try a long album name)
- [ ] Stage controls: volume at the left, then previous, play/pause, next and Add to playlist spread evenly across the row
- [ ] Stage controls: the time (1:19 / 3:36) sits after the seek bar; nothing else is on the seek bar's line
- [ ] Queue heading reads "QUEUE  •  Track 2 of 21"; with no queue there is no track count anywhere
- [ ] Video layout, Scroll style: the sung line stays visible under the video and does not vanish
- [ ] Video layout, Loud style: the words fit under the video and are not cut off at the top or bottom
- [ ] Lyric sync is a glass pill in the top bar, left of Stage / Video and the same height; the offset or source note sits centred under it; it works on the Stage and under a video, and is gone from under the lyrics

## Album page follow-ups (Nov 12, later)

- [ ] The lines under the album's buttons never run under the tabs: the addon line, then one line joining the rest (video skips, unavailable tracks), cut short with an ellipsis
- [ ] Hits tab is wider: best songs in a 3 by 3 grid (nine), best albums five across
- [ ] Lineage rows: no -yr / +yr chips, no purple ring or edge; records not in your library still say so in their third line
- [ ] Lyric sync pill sits right beside Stage / Video, with its note (e.g. "Word-synced · LRCMux") centred under the pill
- [ ] Video layout, Scroll style: the sung line sits about a third of the way down the lyrics under the video, not at the foot; Stage view (no video) looks as before
- [ ] Seek bar (Stage, dock, mini player): a drag or click lands where you let go, also after the song has changed a few times
- [ ] Stage seek bar keeps one length while the time counts (1:19 / 3:36 and 10:05 / 12:00 alike)
- [ ] Music search waits for Enter, like the films and shows search: typing shows predictions only, the Library stays until Enter; a voice search or a genre chip on the Stage still searches straight away; clearing the box goes back to the Library
- [ ] Music search hero (TV Mode / Adaptive): more results have a description, each its own: an artist shows their story (saved artist's, else TheAudioDB, else Wikipedia); an album or single its own (TheAudioDB, else its Wikipedia page); a song or video the song's own; none of them falls back to the artist's story
- [ ] Music search hero: an artist result shows their name logo in the logo slot like a film's (when one exists); songs and albums keep artwork with the name under it

## TV hero and album page layout (Nov 12, later)

- [ ] TV Mode hero on Home: the logo and description sit a little higher and the description's last lines are no longer hidden under the first shelf row
- [ ] Album page: with a white or very bright cover, the left side stays dark enough that the left panel and its text read clearly
- [ ] Album page: the new layout is the only one (Settings > Music has no "New album page" switch); narrow the window and the left panel goes first, then the right, while the songs stay
- [ ] Artist page: the new page is the only one (Settings > Music has no "New artist page" switch); narrow the window and the left panel goes first, then the right, while the middle (Essentials, Albums, Singles, Story) stays

## Strings, music facts and the music hero (Nov 12, later)

- [ ] Settings > Music > Word-by-word lyrics: descriptions read "the song's title" with no backslashes (also the Gemini and Last.fm key descriptions)
- [ ] Settings > Playback: the custom shader paths and custom mpv options hints show "e.g." and each example on its own line
- [ ] The P2P consent text (first P2P stream) shows its paragraphs and bullet points on separate lines
- [ ] French and the other languages: no stray backslash before apostrophes or quotes
- [ ] Settings > Music > Music facts: the daily token limit is a dropdown (50k to 600k), like the app's other choices
- [ ] Music search in TV Mode: the logo and description sit clearly higher above the shelf, and a long description shows up to about nine lines before it starts scrolling
- [ ] TV Mode hero on Home is back where it was (the column a little lower, short synopses no longer look stranded); music search keeps its higher column
- [ ] Music search hero, songs: several songs by one artist each get a different artist photo behind them (when the artist has more than one); a song with no artist photo gets a gradient of its cover's colours, not a small picture blown up
- [ ] Music search hero, albums and singles: a large cover (1000 px or more) stands whole and uncropped at the right of the hero, full height, its left edge fading into a gradient of its own colours behind the text; a smaller cover gives the gradient alone
- [ ] Music search hero, albums: when TheAudioDB has disc art, the disc peeks out to the right of the cover
- [ ] Music search hero, facts line under the genres: a song shows e.g. "2013 · 128 BPM · No. 3 of CHVRCHES's most played · Explicit"; an album e.g. "20 Sep 2013 · Virgin · 12 tracks · 49 min · ★ 8.4"; a live or remix result is not given the studio version's facts
- [ ] Music search hero, videos: the picture beside the name is the video's own large thumbnail
- [ ] Music search: browse quickly through many results; descriptions, facts and photos keep filling in (no stretch where everything comes back empty); people-panel photos may fill in a little later when browsing fast
- [ ] Music search hero, album gradients: brighter than before, still dark enough for the text
- [ ] Music search hero, disc: shows for more than the first result: albums with "(Deluxe)" style titles, and songs (their album's disc), when TheAudioDB or fanart.tv has disc art
- [ ] Music search guesses what you want: "Eminem" puts Artists first; "Eminem without me" puts Songs first with that song leading the row; "Eminem encore" puts Albums first with Encore leading; an ordinary search keeps the usual Songs, Albums, EPs, Singles, Artists, YouTube order
- [ ] "Against the current gravity": the Gravity (The Acoustic Sessions Volume II) record shows in the EPs row (YouTube Music calls it a single) and EPs come first, ahead of the song Gravity; a release YouTube Music calls a single but your library (saved artist) knows as an EP is always in EPs
- [ ] When an EP and a song (or single) share the search's title, EPs come first; an album of that name still beats them
- [ ] Music search rows put well-known artists ahead of near-unknown uploads among equally good matches: "love is dead" shows CHVRCHES' album before Gofun's (7 fans); rows don't reshuffle after they appear (they may take up to about 1.5 s longer to show)
- [ ] Music search: move quickly off an album before its details load, then come back: it fills in (description, genres, facts) instead of staying with the plain cover and no words (Kerli's Love Is Dead in "love is dead" was one)
- [ ] Artwork that was half grey (Momento's Love Is Dead (Extended Mix) and the other song tiles in "love is dead") shows whole after its next load, in the row and in the hero; posters and other artwork across the app are no longer kept when a download is cut short

## Logging build for the optimization audit (2026-11-13)

Nothing here changes how the app behaves; these runs fill the log (`Docs/plans/optimization-audit-2026-11.md`).
Perf logging must be on (an empty file named `perf-log` in the Nuvio data folder) for the first check.

- [x] One normal launch on the HTPC, left on Home for a minute, then closed (records where the launch freeze sits and what the first requests are) (2026-11-13): JVM and native both run; the freeze did not happen on either
- [~] An evening driven only by the controller; each time the arrows or OK stop doing anything, note the time and the screen before fixing it by revisiting the tab (2026-11-13): done with the keyboard; the dropped-keys bug hardly showed (Nick: it may need mouse and keyboard together), but these did:
  - [ ] **BROKEN** Search: open a result, go Back, press Right: an empty "Movies Search - Movies" catalog opens, and the arrows do nothing on it (Escape leaves)
  - [ ] **BROKEN** Discover, 00:47: going too far Right loses navigation; every arrow is then ignored, and hovering a poster does not bring it back (a shortcut to another tab and back does)
  - [ ] **BROKEN** Artist page: the mouse scroll guard makes it impossible to go back up the page with the keyboard; the album page's order of stops is odd
  - [ ] **BROKEN** Tonight: where Left means "never show me this again", the Back button cannot be reached with the keyboard; Escape works but is not shown as its key
  - [ ] **BROKEN** Music player panel, 01:02: B does not leave it and neither does Escape; only the mouse does (may be the same bug as the panel sometimes not minimising)
  - [ ] **BROKEN** Player: the on-screen controls cannot be reached with the keyboard, only shortcuts; Nick wants Enter (the pad's OK) to focus the bar and the arrows to move along it
- [x] The app left idle on Home for ten minutes with Library auto-download on (records why its check runs every minute) (2026-11-13): answered by the launch logs, one failed grab's retry has been due for 4.7 days and wakes the check each minute
- [ ] Nothing about focus, keys or the controller feels different from before this build
