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
- [ ] The header tag swaps the Tonight mirror for the pad and back (Nov 11: the modes are mirrored too, see below)
- [ ] Choosing something with the TV remote while the phone is open keeps the phone in step
- [ ] Once a title starts playing, the phone switches to the film screen as usual

### Phone remote: every Tonight mode on the phone (Nov 11)

Needs this Nuvio and the new NuvioRemote app together.

- [ ] The phone's Tonight launcher lists all eight ways to choose in the TV's order (the one used last marked LAST TIME); tapping any of them opens it on the TV and on the phone
- [ ] Coin Toss: the phone shows the picked title and "Plays by itself in 7 s" counting down; Not this tosses again on the TV, Play now plays, Hold stops the clock (and Start the clock restarts it), Its page opens the page; the last three noes and the lean show under the buttons
- [ ] Knockout: the phone shows "Round 1 of 3" and the two titles; tapping one keeps it on the TV; Undo undoes the last pick (and on the first match goes back to the launcher); "A 20 second taste of each" plays the tastes on the TV and the phone says which; the winner shows with its countdown and Play now
  - [ ] **BROKEN** (2026-11-11): after a pick the names changed but the posters stayed the old ones
  - [ ] Re-check: after each pick both posters on the phone change with the names (and the same for Three Cards' reshuffle and Coin Toss' next title)
- [ ] Channel Flick: the phone shows the channel playing, "CH 3 of 40", and the next three; Never show me this, Play it, Save for later and Next channel do what Left, OK, Up and Right do on the TV; the note ("… saved for later") shows on the phone too
- [ ] Ends By: the phone shows "Bed at 23:30" with −15 and +15 (they move bedtime on the TV too) and the list, the ones past bed faded; tapping a title plays it
- [ ] The Pile: tapping a title plays it; Let go asks "Let go for good?" and a second tap takes it off the pile on the TV
- [ ] The Map: dragging on the phone's touchpad moves the lens on the TV; the title under the lens (and what is nearby) shows above the pad, a small square shows where the lens is, a tap plays the title under the lens
- [ ] Back on the phone does what Back does on the TV in every mode, and the buttons never move as titles change
- [ ] With an older phone app, the modes still show "… is on the TV" and the two-button launcher still works

### Phone remote: long-press menus on the Watch tab (Nov 11)

Needs this Nuvio and the new NuvioRemote app together. Hold anything for about half a second.

- [ ] Holding a library poster buzzes and opens a glass sheet with its poster and name straight away; the rows fill in a moment later without anything jumping
- [ ] A film's sheet: Mark as watched / unwatched, Add to or Remove from library, Choose a source on the TV, Add to a playlist, Not interested, Details on the TV (plus Start from the beginning when it is half-watched, Add to all libraries with Trakt or another library connected, and Start a rewatch with Simkl rewatches on and the film watched)
- [ ] Mark as watched on a poster: the phone says so, the green tick appears on the poster, and the TV's Library shows it watched (and Trakt/Simkl get it as they do from the TV)
- [ ] Mark as watched on a show's poster marks every released episode (the TV's toast says how many); Mark as unwatched clears them
- [ ] Remove from library takes the poster off the phone's list and the TV's Library; Add to library on a search result adds it
- [ ] Holding a Continue watching card: Resume, Start from the beginning, Choose a source, Mark episode (or film) as watched, Add to a playlist, Details, Remove from Continue watching; Remove takes the card off the phone and the TV
- [ ] Start from the beginning plays it from 0:00 on the TV; Choose a source opens the TV's source list (and the phone's Sources cards)
- [ ] In a show's season list, holding an episode: Mark as watched, Mark earlier episodes as watched, Mark Season N as watched, Choose a source, Recap the story so far (with AI recaps on), Add episode / Add Season N to a playlist; the episode list ticks over without closing the show sheet
- [ ] Holding a season tag (shows with more than one season): Mark Season N as watched, Mark earlier seasons as watched, Recap before Season N, Add Season N to a playlist, Details
- [ ] Add to a playlist opens a picker on the phone with the TV's playlists and New playlist; adding says "Added ... to <playlist>" and the TV's playlist has it. A whole show from its poster offers Every season or one season first
- [ ] Recap shows "Writing the recap…" then the recap text on the phone (scrolls if long); with recaps off it says so
- [ ] Not interested on a poster: the title no longer appears in Discover on the TV
- [ ] Demo TV: holding a poster, card, episode or season opens the sheet; marking an episode watched ticks it; the playlist picker and the pretend recap appear

### Phone remote: the "Ends at" clock on the film screen (Nov 11)

Needs the new NuvioRemote app (no change on the PC).

- [ ] Playing a film: the gap between the volume slider and Back / Sources / Dark / More is now a glass card with "ENDS AT" and the finishing time in big type (in your phone's 12 or 24 hour format), and the film's length ("Runs 1h 50m") under it
- [ ] The time is right: it matches the clock plus the time left on the seek bar, and jumps at once when you seek or skip 10 s
- [ ] Pause: the label reads "Ends at · paused" and the time creeps forward with the clock (it is when it would end if you played now)
- [ ] Speed 1.5x (More sheet): the finishing time comes forward and "1.5x" shows on the line under it; the sleep timer shows "Sleep in N min" there too
- [ ] On a show, Next episode sits in the card's bottom row; during an intro, recap or credits Skip appears beside it. The big time does not move when they come and go
- [ ] Nothing on the film screen scrolls on your phone; the card fills the gap with no blank space under it
- [ ] **BROKEN** (2026-11-11): on a show the screen scrolled and the card was cut off under the keys
  - [ ] **BROKEN** Re-check (2026-11-11): no scroll, but the time was tiny and Next episode filled the card: on a show (taller top panel) nothing scrolls; the card shrinks to fit and its big time gets smaller, with Next episode fully visible inside it
  - [~] Re-check: on a show the card lays out sideways: "ENDS AT" and a big time on the left, Next episode on the right (Skip stacked above it during an intro or credits); nothing scrolls, and the time keeps its size when Skip comes and goes (2026-11-11): works, but the time was cut off ("00:1" for 00:16)
    - [ ] Re-check: the whole time shows on a show (try a late one like 00:16 and a wide one like 23:58); it shrinks a little if it has to, never cut off

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

## Live TV: Decide for me (Nov 13)

Settings > Live TV > Decide for me (needs On Now). Four sections appear under Films: Three things, I have 25 minutes, Earlier today, Following.

- [ ] Three things shows three cards the same size: SAFE, STARTS SOON, WILDCARD, each with its channel; a card with nothing says so in words instead of vanishing
- [ ] SAFE is something on now from the channels you usually watch; STARTS SOON begins within half an hour; WILDCARD is a film from outside those; no two cards share a channel
- [ ] The Reshuffle tile changes the cards once; after that it says "used tonight" and does nothing until tomorrow (after 05:00)
- [ ] OK on a card goes to the channel (or its page, when it has not started)
- [ ] "I have N minutes" has five choices (15, 25, 45, 60, 90); OK on one selects it, the sidebar label follows, and the three rows change
- [ ] Nothing in the three rows runs over the minutes, except a dimmed tile that says how many minutes over (at most 10)
- [ ] Earlier today lists finished programmes from your lineup or usual channels that the provider still has, films and followed first; OK opens a page with Start from the beginning
- [ ] Earlier today explains itself when the provider keeps no catch-up
- [ ] On a programme's page "Follow ..." adds it; the Following section then lists the channels that have it next, soonest first, and a lineup channel before others
- [ ] A followed thing that is not in the guide is shown dimmed with "Not in the guide yet"; "Stop following" on a page removes it
- [ ] Everything in these sections works with arrows, OK and Back, and the mouse
- [ ] With Decide for me off none of these appear

## Live TV: Starting soon (Nov 13)

Settings > Live TV > Starting soon (needs On Now). It needs a playlist with a programme guide.

- [ ] Live TV's sidebar has "Starting soon" under Films, with a count; it opens three titled groups: Within 15 minutes, 15 to 30 minutes, 30 to 60 minutes
- [ ] Every tile is something that has not started yet, shows "in N min" at the top and its start and end below; nothing already on is there
- [ ] With Your 40 on, only the lineup's channels appear; with it off, any channel with a guide can
- [ ] OK on a tile opens its page with "Remind me when it starts" and "Switch to it at HH:MM" above "Go to the channel"
- [ ] After Remind me, the same page offers "Cancel the reminder" instead; cancelling brings the two choices back
- [ ] Two minutes before the start a glass card (bottom left, fixed size) says the programme and "BBC One · starts in 2 min" and goes after about 20 seconds
- [ ] While a channel is playing, the same text shows as a pill on the player instead
- [ ] "Switch to it" puts the channel on by itself at the start, from the Live TV tab and while something else is playing, and the card says "Switching to ..."
- [ ] Quit Nuvio with a reminder set and start it again before the time: the reminder still fires
- [ ] Start Nuvio after a reminder's time has passed by more than ten minutes: nothing fires
- [ ] A coming-up sport tile inside the hour shows the countdown at the top
- [ ] With Starting soon off, there is no section, no reminder actions and the sport tiles show clock times as before

## Live TV: Your 40 (Nov 13)

Settings > Live TV > Your 40 (off by default). Turn it on, open Live TV.

- [ ] Live TV opens on "Your 40", a numbered list: the channels you watch most and starred first, then channels from what the triage kept; the numbers run 1, 2, 3 from the top
- [ ] Leave and come back (or restart Nuvio): the lineup and its numbers are exactly as they were, nothing reshuffles itself
- [ ] Right from a channel's star reaches a "..." button; OK opens a card with Move up / Move down / Take out of Your 40 / Never again (no Move up on number 1, no Move down on the last)
- [ ] Move up and Move down swap the channel's number and the highlight stays on it
- [ ] "Take out of Your 40" removes it from the lineup only: it is still in All channels and its category
- [ ] On a channel in All channels or a category, the "..." offers "Add to Your 40" (not when the lineup is full at 40), and the channel gets the next number at the end
- [ ] "Never again" removes the channel from the lineup, the favourites, the On Now wall and every list; typing its name in the search box still finds it
- [ ] Settings > Live TV > "Bring back channels thrown out" shows them again; they are not back in the lineup
- [ ] In the player, typing 1, 2, 3 on the remote or keyboard switches to lineup channels 1, 2, 3 (not the provider's numbers); channel up/down from the lineup steps through it in order
- [ ] A channel with several copies (FHD, HD, SD) is one row in the lineup and plays the next copy when one fails
- [ ] Back and Escape close the "..." card; the mouse works too (click the "...", click an action)
- [ ] With Your 40 off, Live TV looks and numbers channels as before

## Live TV: Triage (Nov 13)

The sliders button in the Live TV header, beside Refresh.

- [ ] The Triage button opens "Which countries do you watch?": a grid of countries with flags and channel counts, biggest first, "No country marked" last, between two equal side panels (So far, What is left)
- [ ] Ticking countries moves the big number on the right ("1,280 of 12,014 channels") and the list on the left; Next is dimmed until one is ticked
- [ ] The second question lists only kinds your countries have, with sensible ones already ticked (not adult, shopping, religious, radio or 24/7 loops), and the count on the right follows
- [ ] The kinds are right for your playlist: no sport category filed under Entertainment and so on; if any are wrong, say which category names
- [ ] "Your first ten" offers channels from what is left, the ones you watch most first; ticking stars them (ten at most) and they are in Favourites afterwards
- [ ] After Done the sidebar has only the kept categories, All channels counts only those, and with On Now on the wall only draws from them
- [ ] Typing in the search box under All channels still finds a channel the triage left out, and it plays
- [ ] Favourites and Recently watched still show channels from countries or kinds you left out
- [ ] The whole thing works with arrows and OK (and the pad): the first press shows the highlight, Down from the last row reaches the buttons, Back goes to the previous question and leaves from the first without saving
- [ ] The Triage button is lit while a triage is in force; opening it again starts from your answers, and "Show everything" brings every category back
- [ ] The answers are still there after restarting Nuvio

## Live TV: On Now (Nov 13)

Settings > Live TV > On Now (off by default). Best tried in the evening with a match you follow under Live scores.

- [ ] Settings > Live TV has an "On Now" switch under "Show in navigation", off; Settings search finds it by typing "on now"; with it off the Live TV tab is exactly as before
- [ ] With it on, the sidebar starts with On now, Sport and Films, and the tab opens on On now: rows of glass tiles titled Live sport, Films and Your channels (a row with nothing in it is left out)
- [ ] Arrow keys and the pad move a highlight tile to tile, Left from the first tile goes to the sidebar, Up from the top row reaches the header buttons, and a mouse click on a tile does the same as OK
- [ ] Films: the tiles are films that are really on now or start within half an hour, each with how far in it is, a progress bar and the channel; with a TMDB key they show posters
- [ ] OK on a film opens its page (picture, blurb, "Started 18:30 · 71 min in · ends 20:15"); Watch live plays the channel; Back (Esc or the pad's B) returns to the wall, not out of Live TV
- [ ] Start over: on a channel your provider records, the film page has "Start from the beginning" and it plays the film from its start, not live
- [ ] Start over: left running to the end, the recording carries on into the live channel instead of stopping or starting again
- [ ] Start over: the "Earlier on <channel>" lines on the page play that finished programme
- [ ] Start over: on a channel without catch-up the page says it can only be joined live and has no Start from the beginning
- [ ] Sport, with Live scores on and a followed match in play: its tile shows both teams, the score and the minute, names the right channel, and OK plays that channel
- [ ] Sport, a followed match the guide has no channel for says "Not found in the guide" and does nothing on OK
- [ ] Sport with Live scores off still lists sport programmes from the guide
- [ ] Your channels after a few days: the row leads with what you usually watch at that time of day
- [ ] All channels and the categories: a channel your playlist lists several times (FHD / HD / SD / backup) is one row marked like "FHD · 4 sources"; +1 channels and 4K copies are still their own rows
- [ ] No two different channels have been merged into one row; if any have, say which names
- [ ] Starring a merged row stars it once (Favourites shows one row); unstarring removes it
- [ ] A merged channel whose first copy is dead still plays: it moves to the next copy by itself within a few seconds (nuvio.log says "trying")
- [ ] Channel up/down in the player steps through merged channels (BBC One, then BBC Two), not through the copies of one
- [ ] The header's search box narrows the wall by a film, team or channel name

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
- [ ] **BROKEN** (2026-11-11): the winner (The White Lotus) opened "Failed to load: could not load details from any addon", though it opens fine from Home
  - [ ] Re-check: a trending winner (one not in your library) plays; the same for Three Cards, Coin Toss, Channel Flick, Ends By and The Map, and for Down = details

### Tonight picker: Channel Flick and trailer tastes (phase 3, Nov 10)

- [ ] The Channel Flick tile opens trailers one after another in a large frame, with sound at the hero trailers' volume; music pauses while they play
- [ ] Under the frame: where the title comes from (FROM YOUR LIBRARY, TRENDING THIS WEEK, YOU'RE ON S2 E4), its name and "ends 23:06"; the frame and the lines under it never move
- [ ] When a trailer ends the next channel starts by itself; a title with no trailer says so briefly and is skipped
  - [ ] **BROKEN** (2026-11-12): every channel stuck on "Finding the trailer…"; YouTube answered every trailer lookup "Sign in to confirm you're not a bot"
  - [ ] Re-check after the fix (signed-in fallback, ed0e022): Channel Flick plays trailers again while YouTube is asking for a sign-in; `nuvio.log` shows `TrailerSignedIn … trailer opened signed in` for the ones it rescued
  - [ ] The same while YouTube is asking for a sign-in: a new Home hero trailer, a details page trailer and the pre-show trailers play
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


## Film player (from upstream, Nov 11)

- [ ] Subtitles carry over to the next episode: play an episode with your preferred subtitle on, let it go to the next one (or start the next from the list), and the same subtitle comes on by itself

## Live scores (Nov 11)

Settings > Live TV > Live scores (off by default). Best tried while Man Utd or a followed competition is playing.

- [ ] Settings > Live TV has a "Live scores" section with "Live score pop-ups" off; Settings search finds it by typing "live score"
- [ ] Turn it on, type "Man Utd" under "Add a favourite team": Manchester United is offered; Add puts it in the list with Remove
- [ ] "Follow: Football / Rugby / Fight nights" start empty; ticking Premier League and Six Nations keeps them after closing and reopening Nuvio
- [ ] "Show a sample" shows the card for the chosen look, with the chime (favourites only by default)
- [ ] Look: Corner card sits bottom right, Top ticker runs across the top, Big flash fills the centre for a favourite; none of them changes size with long names
- [ ] A Man Utd match: kick-off, each goal (with the scorer; "pen" or "own goal" when it is one), half-time and full-time pop up within about 20 seconds of the TV coverage, and nothing pops for what had already happened when Nuvio was opened mid-match
- [ ] A red card, a missed penalty and a VAR decision ("Goal ruled out") in a Man Utd match pop up
- [ ] A followed competition's match that is not a favourite pops up too, as the corner card (no big flash, no chime by default)
- [ ] Over a playing film, an episode and a Live TV channel the card shows over the video (not hidden behind it), then fades away; the remote still controls the player while it is up
- [ ] On the music stage and in menus the card shows and does not move anything on screen
- [ ] Rugby: a followed match's tries, penalty goals and yellow or red cards pop up; conversions do not pop on their own
- [ ] Fight nights (UFC, boxing): a card when the night starts and one with the main event's result
- [ ] "Pop up for": unticking a group (for example Red cards) stops those cards
- [ ] Phone remote: with "Notify the phone remote" on and the app connected, a Man Utd goal arrives as a notification ("GOAL · Man United 1 – 0 …"); the next event replaces it rather than stacking. On Android 13+ the app asked to allow notifications on first start
- [ ] The Status row says what is being watched ("Watching 1 live match", "No followed matches today")
- [ ] Optional: with an API-Football key saved, nothing changes while ESPN works (nuvio.log tag LiveScores shows no api-sports calls)

## AIOStreams and AIOMetadata inside Nuvio (Nov 11)

- [x] Settings > Addons shows "AIOStreams and AIOMetadata on this PC" with the switch off; the Nightly includes the servers (the switch is not greyed out with "This build does not include the servers") (2026-11-11)
- [x] Turning the switch on: within about a minute AIOStreams, AIOMetadata and Cache (Redis) all say "Running at http://127.0.0.1:…" (2026-11-11)
- [x] Windows Firewall does not ask about node.exe or redis-server.exe (2026-11-11)
- [x] "Open configure page" on AIOStreams opens its configure page in the browser; add TorBox there, save, and copy the manifest address it gives (2026-11-11)
- [x] Paste that address into "Install an addon address" and press Install: it says "Installed …" and the addon is in the Addons list; a film's sources come from it (2026-11-11)
- [x] Enter a TMDB key (and TVDB if you have one) and press "Save and restart": both servers restart and come back Running (2026-11-11) (TMDB; the TVDB key was rejected as invalid, left for now)
  - [x] TMDB key: the AIOStreams configure page said "Failed to validate TMDB API Key/Access Token ... 401" on Create (2026-11-11). Nuvio now tells the 32-character API key from the long Read Access Token (starts eyJ) and passes each where it works; with the API key in Nuvio and nothing in the configure page's Metadata TMDB fields, Create succeeds (2026-11-11)
- [x] "Open configure page" on AIOMetadata opens its page; set up catalogues, save, install its address the same way; Home shows its catalogues and details pages fill (2026-11-11)
- [ ] Close Nuvio: Task Manager shows no node.exe or redis-server.exe left running; start Nuvio again and the servers come back on their own, with the same addresses (installed addons still work)
  - [ ] **BROKEN** (2026-11-11): on start the two local addons showed "Unavailable, Failed to connect to /127.0.0.1:4734x" until refreshed by hand, because Nuvio loads its addons before the servers answer. Re-check: start Nuvio and leave Settings > Addons alone; within about half a minute both show as loaded and Home has their rows (nuvio.log has "Server on port … is ready: loading its addon again")
    - [~] (2026-11-11): AIOStreams came back by itself; AIOMetadata's reload got "Request failed with HTTP 503" (it answered /health/live while still initialising) and needed a manual refresh. Re-check: after a start both load without touching them
- [ ] Memory: with both running for an evening, Task Manager's node.exe processes stay around 1.3 GB in total, and Nuvio stays smooth
- [ ] An in-app update while the servers are on installs cleanly (no "file in use" error)
- [ ] Turning the switch off stops all three (no node.exe or redis-server.exe left)
- [ ] PC build (D:\NuvioBuild\build.cmd, Nov 11): after a build the setting no longer says "This build does not include the servers"; the first build after a bundle change also builds the bundle (10 to 20 minutes, "Local addons: bundle ready" in build.log), later ones say "Local addons: bundle up to date" and keep the servers

## Upstream merge (Nov 13)

Upstream's windows-tv-adaptive (8e2adb00) merged into Spyder. These check that what the fork kept still works and that what came in did not break it.

- [ ] Nuvio starts, and Settings > About (or the update check) still looks at this fork's Nightly, not UmbraProjects'
- [ ] Music tab with "New music home" on: the music home is still the first screen, typing searches as before, and its Library tab opens the three-panel Shelf
- [ ] With the music home on, the Search key (S) and the top bar's Search on the Music tab put the cursor in the music home's own search line
- [ ] A genre chip on the Stage still opens the Music tab searching that genre
- [ ] Settings > Music still has "New album page", "New artist page" and "New music home", each as you left it; the album page and the artist page look as they did before the merge
- [ ] Settings > Music has a new "Album notes and facts" switch, on: liner notes, song stories and the facts filler work as before
- [ ] Settings > Music > Library views (new): Crates (the three-panel Shelf) and Playlists are on; Shelf (the old row-per-artist one), Constellation and Eras are off and do not show in the Library
- [ ] The Stage looks as it did before the merge: three panels, nothing moves, the liner notes / visualiser box is there, the options menu has no "Side box" row
- [ ] Stage options menu: the arrow keys stay inside the open menu (they do not jump to the page behind it)
- [ ] The dock still shows the quality pill (FLAC) and the beat dot; the mini bar's line still ends with the quality
- [ ] Release tiles still show the "Lossless" text pill (not upstream's icon)
- [ ] Loud lyrics on the Stage: no row is cut off at the top or the foot (upstream's fit fix came in)
- [ ] Music pages from the keyboard: after closing a menu or a dialog the arrows still move (upstream's focus router); Escape or B gets out of the dock and the player panel
- [ ] Live TV with On Now on opens on the wall; the top bar's Search on the Live TV tab narrows the wall and the channel list; Triage still opens from the header
- [ ] Tonight ("What to watch"): the launcher shows real backdrops and every mode has the new look; each mode still shows on the phone remote and a pick from the phone plays on the TV
- [ ] Tonight: a trending title (one with a tmdb id) still opens its details page
- [ ] Phone remote: connects as before; a live-score notification still arrives on the phone
- [ ] R (or holding LB) brings up the panel ring; O opens Settings
- [ ] Top bar Search on the YouTube tab searches YouTube

## Top bar search predictions (from upstream, Nov 11)

- [ ] Typing in the top bar's Search (films and shows): the panel under the box swaps the recent searches for predictions, recent searches that match first (clock icon), then TMDB titles (film icon), with the typed part in bold
- [ ] Enter with nothing highlighted searches exactly what was typed
- [ ] Down/Up walk the predictions and put each one in the box; Up from the top one gives back what was typed; Enter runs the one shown
- [ ] Tab puts the top prediction in the box without searching; Tab again moves to the next and wraps round; Tab never jumps to Discover from the search box
- [ ] Mouse: hovering a prediction lights it; clicking runs it; "Clear history" shows only under the recent searches (empty box)
- [ ] Escape hides the panel first (and gives back the typed text if you were walking it); a second Escape leaves the search
- [ ] The same panel works on the Music tab (music note, YouTube Music completions), YouTube tab (YouTube autocomplete) and Live TV (channel names)
- [ ] The search box no longer has the Discover (compass) button; Tab outside search still opens Discover

## Music search rows, seek bar and strings (from upstream, Nov 12)

The "Music search" lines are upstream's search, which this fork shows only while Settings > Music > New music home is **off** (with it on, the music home searches as before). Upstream's album page, artist page and Stage changes of Nov 11-12 were not taken (see CHANGELOG, Upstream merge of Nov 13), so their checks are not listed here.

- [ ] Seek bar (Stage, dock, mini player): a drag or click lands where you let go, also after the song has changed a few times
- [ ] Music search waits for Enter, like the films and shows search: typing shows predictions only, the Library stays until Enter; a voice search or a genre chip on the Stage still searches straight away; clearing the box goes back to the Library
- [ ] Music search hero (TV Mode / Adaptive): more results have a description, each its own: an artist shows their story (saved artist's, else TheAudioDB, else Wikipedia); an album or single its own (TheAudioDB, else its Wikipedia page); a song or video the song's own; none of them falls back to the artist's story
- [ ] Music search hero: an artist result shows their name logo in the logo slot like a film's (when one exists); songs and albums keep artwork with the name under it
- [ ] TV Mode hero on Home: the logo and description sit a little higher and the description's last lines are no longer hidden under the first shelf row
- [ ] Album page: with a white or very bright cover, the left side stays dark enough that the left panel and its text read clearly
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

## Live TV: Brakes (Nov 13)

Settings > Live TV > Brakes (off by default).
- [ ] With Brakes on, the switch's text shows a week line ("0 min watching, 0 channel changes, 0 programmes seen to the end"); after an evening of watching it counts up
- [ ] On a channel, press channel up five times quickly: a glass card "That's a lot of flicking" appears over the video with Keep flicking and Turn Live TV off; arrows and Enter pick, Escape dismisses
- [ ] Keep flicking: the card goes and does not come back that night, however much you flick
- [ ] Turn Live TV off leaves the player
- [ ] Stay on one programme for 15 minutes or more: at its end a card shows its title with Stop here (focused) and One more; Stop here leaves the player, One more carries on
- [ ] Flicking past channels quickly never shows the end card
- [ ] With Brakes off none of this appears and the week line is not shown as counting

## Live TV: Running order and Just ask (Nov 13)

Settings > Live TV > Running order and Just ask (needs On Now). Two sidebar items appear: Tonight's running order, Just ask.
- [ ] Tonight's running order shows up to six numbered programmes with start times, in order, and the bedtime from What to watch in its heading
- [ ] None of them runs past bedtime; with a very early bedtime the screen says nothing fits
- [ ] Approve: the button becomes "Approved"; Starting soon's reminders list does not need to be on, and at the first slot's start Nuvio switches to its channel by itself, and again at each later start
- [ ] Selecting Approved cancels them; no switching happens
- [ ] Just ask with an empty search box tells you what to type
- [ ] Type "something funny, half an hour": one answer tile marked THE ANSWER, a "Why this" tile and Not that
- [ ] Not that shows a different answer; after the last it wraps round to the first; editing the text starts again at the first
- [ ] "a film, an hour" offers a film that fits; "any bond film" finds a title with bond in it, if one is on
- [ ] A line nothing fits says "Nothing fits" and no button
- [ ] With the switch off neither item appears

## Live TV: Overnight weeding (Nov 13)

Settings > Live TV > Overnight weeding. It works only between 02:00 and 05:00 with the app running and nothing playing, so these need a night.
- [ ] Leave Nuvio open overnight with the switch on and Your 40 set up; in the morning Settings > Overnight weeding says "Now marked dead: 0" if all your channels work
- [ ] The log of the night (Windows build log) shows no flood: one channel at a time, a few seconds apart
- [ ] A channel you know is dead (change its stream in the provider, or add a broken test channel to the lineup) is still in Your 40 after one and two nights, and gone after the third; "Now marked dead" counts it
- [ ] "Bring back channels thrown out" returns it
- [ ] Starting a channel in the night stops the weeding until the next quiet check
- [ ] With the switch off nothing is opened at night

## Live TV: Stage (Nov 14)

Settings > Live TV > Stage. Live TV opens on Three things with a strip of stages under the heading.
- [ ] Every stage has the centred heading, the strip, and either two equal side panels round a centre or three equal columns; nothing changes size as you move or as the guide loads
- [ ] Up from the top of a stage goes to the strip; left and right there change stage as you go; down comes back; Back goes to the strip
- [ ] All channels (end of the strip, OK) opens the classic Live TV; its "Stages" sidebar item brings the stages back
- [ ] Three things: three cards the same size, films with a backdrop, others with the channel logo; OK plays one that is on, tunes in for one that starts soon; Deal again works once a night
- [ ] **BROKEN** Starting soon: the chips narrow the departures; the right panel follows the highlighted row; Tune in at switches to the channel at the start; Go to ... now plays it (2026-11-15): Tune in at did nothing while another channel was playing
  - [ ] Re-check after the fix (the channel is now changed inside the playing player): Tune in at on Starting soon, keep watching another channel, and at the start time it switches; the same while a film plays (the film closes and the channel opens)
- [ ] I have N minutes: the dial changes the three columns; nothing runs over except dimmed rows that say how far
- [ ] Tonight: the running order ends before the bedtime chip; Swap one replaces the highlighted slot; Approve and start switches at each start; Approved: cancel and Not tonight undo it
- [ ] Earlier today: OK or Play from the start plays a programme from its beginning (Xtream catch-up)
- [ ] Following: type a name in the box and press Enter to follow it; Follow on a suggestion; hold OK on a followed one to stop; one on now plays, one later sets a reminder
- [ ] Just ask: type "something funny, half an hour" (or use the mic): one answer with artwork, why this on the right, Not that for the next; the line is kept under Asked before
- [ ] Your 40: the numbered grid with a progress bar per channel; OK plays; type 1 2 to go to 12; hold OK for move / take out / Never again; Redo the triage opens the questions

## Live TV: Stage v2 (Nov 14)

Settings > Live TV > Stage. The first start after updating reads the guide again (it takes a little longer once).
- [ ] The whole stage glows softly in the colours of the highlighted programme's picture, and the glow fades over as the highlight moves; nothing moves or changes size
- [ ] Three things: each card has a picture (film backdrop, series art or the channel logo) with LIVE / STARTS / FILM marks, chips (genre, year, length, episode), three lines of description, why it was picked and a button
- [ ] No 24/7 loop channel (SpongeBob, American Pie, Luke Wilson), radio or Teleshopping appears in Three things, Starting soon, minutes, Tonight or Just ask unless it is on Your 40; the stages say how many were hidden
- [ ] Titles show without "- S02E01" and without ᴺᵉʷ / ᴸⁱᵛᵉ; those show as badges instead
- [ ] Starting soon, Earlier today: the right panel shows a picture, marks, a description and what comes on after
- [ ] I have N minutes: rows have pictures; things on now have LIVE and a progress line
- [ ] Tonight, opened in the morning: plans from 18:00, the ribbon shows the evening up to bedtime, Starts chips (Now, 18:00, 20:00) change it, and something that runs past bed is shown dimmed as left for another night
- [ ] Your 40: the band at the top shows the highlighted channel's programme with its picture and what is next; tiles show the logo, what is on and progress; placeholder logos ("Content not viewable") are replaced by the channel name
- [ ] Just ask: the three example lines under the answer fill in the question; the answer card shows the picture, marks and description

## Live TV: stages on the phone remote (Nov 14)

Needs the new Nuvio Remote build (android-remote workflow) and Settings > Live TV > Stage on.
- [ ] Opening Live TV on the TV: the phone's Remote tab shows the same stage (heading, the strip of stages as chips, the programmes as cards with a picture, a LIVE slot and a progress bar)
- [ ] Tapping a stage chip changes the stage on the TV; changing it on the TV changes it on the phone
- [ ] Three things: tapping a card does what OK does (watch, or tune in at its start); Deal again works once a night
- [ ] I have N minutes: the minute chips change the board; Tonight: Approve and start / Not tonight work from the phone
- [ ] Your 40: tapping a channel plays it; Following: Remind me on something not yet on sets the reminder
- [ ] The header tag swaps to the pad and back; All channels opens the classic list and the mirror goes away
- [ ] Leaving Live TV (or starting something) puts the phone back on its usual screens

## Live TV: From the start, on demand (Nov 14)

Settings > Live TV > From the start, on demand (and Stage) on; add-ons with TorBox (or another source) set up.
- [ ] OK on a film that started more than 3 minutes ago asks: From the start, on demand / Join live, N min in / Close; it says what TMDB found
- [ ] From the start plays the film from 0:00 through your add-ons (the source they pick), not the live channel
- [ ] It starts playing by itself, with no source list, even with Settings' auto-play set to choose manually
- [ ] Friends (S3 E22 in the description, not the guide's episode field) plays S3 E22 from the start instead of opening the series page
- [ ] An episode with no numbers but a description starting with its title ("The One With ...") finds and plays that episode
- [ ] An episode with S/E numbers in the guide plays that episode from the start; one without opens the series' page
- [ ] A programme TMDB doesn't know says so and offers only Join live; news, sport and Live-marked programmes join live straight away
- [ ] Up/Down, OK and Back work on the choice; tapping a card on the phone mirror opens the same choice on the TV
- [ ] On Now (stages off): a film or episode well under way opens its page with "From the start, on demand" (it says what TMDB found) above Watch live
- [ ] The classic channel list, Your 40 and the guide: picking a channel whose programme is well under way opens the same page; a channel with news, sport or nothing worth it just plays

## Live TV: Just ask finds more (Nov 14)

- [ ] "news, ten minutes" answers with a news programme on now (Live: dip in), even when the guide has no categories
- [ ] "the football" answers with sport, a football match first when there is one
- [ ] Asking for more time than anything fits ("a film, 15 minutes") gives the closest and says "Nothing fits 15 minutes, so this is the closest"
- [ ] When truly nothing fits, Why this shows what it looked for

## Live TV: Reminder card (Nov 14)

Set a plain reminder (Remind me, not Tune in at) on something starting in a few minutes.
- [ ] Two minutes before, away from the player: the card bottom left asks Switch now / At the start / Not now, with At the start highlighted; left and right move, OK picks, Back is Not now; the screen behind keeps its place once it closes
- [ ] At the start: nothing happens until it starts, then Nuvio switches to the channel
- [ ] Switch now goes to the channel straight away; Not now (or leaving it 20 seconds) just closes it
- [ ] While watching something: the same question appears on the player's HUD, and the answers work the same
- [ ] With Brakes on, a programme reached through Switch now or At the start gets the end card

## Live TV: Just ask with your AI (Nov 14)

Needs an AI key under Settings > Discover. Settings > Live TV > Let the AI answer Just ask.
- [ ] Turning the switch on without having agreed before shows the AI privacy dialog; agreeing turns on only this switch
- [ ] Just ask: type "something to cheer me up" and leave the box: "Asking your AI" shows under the answer, then the label reads ONE ANSWER · FROM YOUR AI with the AI's reason in Why this
- [ ] Not that steps through the AI's picks (up to three), then on to the rules' answers
- [ ] With no key (or the switch off) Just ask answers by the rules as before; if the AI fails, it says so and shows the rules' answer
- [ ] Nothing on the Ask stage moves or changes size while the AI is asked

## Live TV: Brakes finished (Nov 14)

Settings > Live TV > Brakes and Stage on.
- [ ] Five channel changes inside two minutes: the card has three answers (Keep flicking, Pick something for me, Turn Live TV off) on one line each; Pick something for me leaves the player for Live TV's Three things stage
- [ ] With Stage off, the zap card still has two answers
- [ ] A programme picked on a stage (or switched to by a reminder, or tuned in to before it was 10 minutes in) and watched 15 minutes: the end card shows as it ends
- [ ] Landing on something half-way through while flicking and staying: no end card at its end
- [ ] Approve Tonight's running order and watch it through: as the last slot ends the card says "That's tonight" with Stop here focused
- [ ] Your week is on the strip of stages (only with Brakes on): tonight's numbers on the left, seven equal bars (tonight highlighted) with changes under each, the week's totals with changes an hour on the right; nothing moves or changes size

## Live TV: smarter picks (Nov 14)

Settings > Live TV > Stage. Watch a few programmes on Live TV first (each one at least 5 minutes; some to the end).
- [ ] After watching the same programme a few times, mostly to the end, Three things' SAFE card says "You finished 4 of the last 5" (or similar) before "Your usual at this hour"
- [ ] Following: a programme you watched 3 or more times in three weeks (and do not follow) is listed under "You keep watching" with how often you saw it, at its next airing
- [ ] Just ask: a programme you usually finish comes first and says "you usually finish it"; its reasons include "You usually finish it"
- [ ] Where the guide marks a new episode it is picked above an episode you have already seen; Tonight and Earlier today favour programmes with a picture and a description
- [ ] Settings > Live TV > Forget what I watched clears it (the row says how many programmes it learned from, and goes away after)

## Phone remote: poster wallpaper (Nov 14)

- [x] Remote › More shows a Lock screen row: Poster off/on, My picture, and a line saying what it does (2026-11-14)
- [x] With Poster on, start a film on the TV: within a few seconds the phone's lock screen shows its poster, whole and centred on a blurred copy (home screen unchanged) (2026-11-14)
- [ ] An episode of a show: the lock screen shows the show's poster; the next episode does not make it flicker
- [ ] Music on the TV: the lock screen shows the album cover; a new album changes it
- [ ] Pausing keeps the poster
- [ ] My picture: pick a photo; about 20 seconds after stopping playback on the TV, that photo is back on the lock screen
- [ ] Without My picture: after stopping, the lock screen goes back to matching the home screen
- [ ] Turning Poster off while something plays puts the normal picture back straight away
- [x] The poster is sharp, not blurry, on the phone's screen (2026-11-14); layout and darkness kept as they are (the clock and media card sit over it)

## Live TV: triage languages and countries (Nov 15)

- [ ] Redo the triage: countries written out ("Germany | TV", "Costa Rica | TV", "Czech | TV") have their own tiles, not "No country marked"
- [ ] A second question asks about languages; with United Kingdom and United States ticked only English starts ticked
- [ ] With English only: Univision, Telemundo and ESPN Deportes are gone from the US categories, and an all-Spanish US category leaves the sidebar
- [ ] The sidebar counts and All channels match what is left; search still finds a Spanish channel
- [ ] Three things never offers a channel from a country or language the triage left out (the Wildcard included)

## Live TV: Tonight never runs dry (Nov 15)

- [ ] On Tonight, press Swap ten times or more: the running order keeps filling, never stopping half way
- [ ] A programme swapped out comes back only after the others are used up

## Live TV: learned teams (Nov 15)

- [ ] After watching three matches of one team in six weeks (two to the end), Following shows "Follow <team>?" under Learned from what you watch
- [ ] OK follows it and the toast says it was added to Live Scores; the team is in Settings > Live scores favourites
- [ ] Hold OK on it: it goes and is not offered again
- [ ] A team followed as "Manchester United" also finds "Man Utd v ..." listings

## Live TV: sport badges and merged copies (Nov 15)

- [ ] Sport tiles show the competition and round on top, "Team v Team" as the title and both badges beside it
- [ ] Premier League, rugby, NHL and NBA teams get badges; teams not found show their initials, never a wrong sport's badge
- [ ] A boxing live-score tile shows the fight night on top and the fighters' names (with photos where found)
- [ ] A match listed twice (once with "ᴸⁱᵛᵉ") is one tile, with "also on" when the other channel has another name
- [ ] On the stages, a fixture with no picture shows the two badges instead of the channel logo

## Live TV: reminder banner (Nov 15)

- [ ] A reminder comes as a solid banner at the top centre: picture, title, channel, a countdown and a filling bar; nothing shows through it
- [ ] Left, right and OK pick Switch now / At the start / Not now; Back is Not now
- [ ] A reminder due while the triage questions are open waits and shows when they close

## Live TV: more views (Nov 15)

- [ ] Settings > Live TV has six "View:" switches; each adds its stage to the strip, and off takes it away
- [ ] Map: blocks by kind; OK opens one into tiles with the highlighted one on the right; Back zooms out
- [ ] For you now: big tiles are what you would pick first; OK plays or tunes in
- [ ] River: lanes by kind with the now line; left/right moves along a lane; OK on a later one sets a switching reminder
- [ ] Picture wall: tiles turn from art to real stills one by one ("waiting", then minutes); none are taken while a channel plays
- [ ] Tonight grid: darker cells where more starts; OK on a cell lists them on the right; OK on one plays or tunes in
- [ ] Dial: up/down flip one channel at a time with two peeking above and below; typing a number jumps there; OK plays
- [ ] The phone remote shows For you now, Dial and Picture wall as lists, and says the other three are on the TV

## Live TV: subtitles for films on live channels (Nov 15)

- [ ] Settings > Live TV has "Subtitles for films on live channels"; Settings search finds it
- [ ] **BROKEN** With it on, tune to a film (Jaws on Sky Cinema): the player's subtitles list shows add-on subtitles (Subsense) within about half a minute (2026-11-15): 21 Jump Street and Mission: Impossible showed nothing
  - [ ] Re-check after the fix (films the guide does not mark as films are now recognised by their channel or category, so they are not looked up as the TV series of the same name): 21 Jump Street or Mission: Impossible on a Sky Cinema channel lists Subsense subtitles
- [ ] Pick one: the HUD says "timed from the guide", and the lines are roughly right straight away
- [ ] Within a few minutes the HUD says "synced to the audio (+x.xs)" and the lines match the speech
- [ ] Pick one subtitle, then another a minute later: the second syncs within about half a minute (the speech already heard is kept), not after another few minutes
- [ ] On a channel with ads (Film4, ITV), tuned in well after the film started: it still syncs, even when the film is many minutes behind the guide (it used to give up past a minute)
- [ ] A film shown sped up (most UK film channels): still in time half an hour later
- [ ] Rewind with the buffer, then Go live: the subtitles stay in time
- [ ] On a channel with ad breaks: after a break the HUD says "re-synced" and the lines are back in time
- [ ] When the film ends and the next programme starts, the subtitle goes off; the next film offers its own
- [ ] News and sport offer no add-on subtitles; playback is never interrupted by the sync (no second connection)

### Matching live subtitles by their words (Nov 15)

- [ ] After `build.cmd`, `out\Nuvio\nuvio_whisper.dll` is there (the build fetched whisper.cpp into `D:\NuvioBuild\whisper.cpp`); Settings > Voice control no longer says the engine is missing
- [ ] Settings > Live TV has "Match live subtitles by their words" under the subtitles switch; Settings search finds it
- [ ] With a model downloaded in Settings > Voice control (voice control can stay off) and the switch on, pick an English subtitle on a film: within about a minute the HUD says "synced to the audio" and the lines match the speech
- [ ] Nativity 3 (or another film with songs): the lines are in time, not thrown off by the singing
- [ ] On a channel with ads, after a break the lines are back in time within a couple of minutes
- [ ] A subtitle for a different cut, or for another film, does not lock (stays "timed from the guide")
- [ ] With the switch off, or no model downloaded, it works as before (speech detection alone); CPU stays reasonable while it listens

## Live TV: the front door (Nov 15)

- [ ] With Stage on, Live TV opens on the front door ("Live TV", Home first on the strip): favourites on the left, one pick in the centre with Watch highlighted, scores and Next up on the right
- [ ] OK on Watch plays the pick; a pick that starts within 5 minutes puts its channel on at once ("Watch · starts 20:00")
- [ ] The pick's line says why: you follow it, starts in N min, your usual at this hour (with its Your 40 number), or a film that began N min ago
- [ ] Something else swaps the pick for the next one; the pick otherwise stays the same for 15 minutes, unless a followed match comes on
- [ ] A followed team playing live becomes the pick, and its score is on the right with the channel showing it; OK on the score plays that channel
- [ ] Hide scores empties the scores box (it says hidden); Show scores brings them back, and the choice is kept after a restart
- [ ] Favourites now lists the starred channels with what is on and a progress bar; the last channel watched sits above them as Carry on; OK plays, hold OK unstars
- [ ] Back from the player lands on the front door, whichever stage you left from
- [ ] First run: on a playlist with no triage yet, Live TV opens on the triage questions; Done (or Back out of them) leads to the front door, and they don't come back by themselves until Nuvio is restarted
- [ ] Settings > Live TV > Front door picture (with ffmpeg): the pick shows a still of its channel marked LIVE and how old it is; off, the art only
- [ ] The phone remote's Live TV shows Home with the pick first and the favourites that have something on
