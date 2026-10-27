# Music Library views: Eras and Constellation

The Library screen has three views in its header: **Shelf**, **Constellation** and **Eras**. Shelf is built. The other two are dimmed placeholder pills in `MusicLibraryShelf.kt` (`ActionPill("Constellation", enabled = false)`), and the original mockup is not in the repo.

**Status of this document: a draft.** The mockup is the source of truth. Everything below is inferred from the data and models that already exist, so wherever it disagrees with the mockup, the mockup wins (see "Open questions").

## What each view is for

One library, three questions:

| View | Question it answers | Axis |
|---|---|---|
| **Shelf** (built) | "Play something from an artist I know." | Artist, one row each |
| **Eras** | "What was I into, and when? What's in my library from 1997?" | Time |
| **Constellation** | "What does my taste look like? What sits near what?" | Sound (tags) |

Shelf organises by *who*, Eras by *when*, Constellation by *what it sounds like*. All three show the same saved artists and the same covers, and all three open the same places: a cover opens the album (`onAlbumClick`), an artist name or avatar opens the artist room (`onArtistClick`).

## Shared rules

- **View switch.** The header pills become real. The choice is remembered (`rememberSaveable`), like the Library/Search choice in `MusicTabScreen`. One enum, `LibraryView { Shelf, Constellation, Eras }`, and one `when` in the host.
- **Playable first.** Like Shelf, a release that can't be played here (`ytBrowseId == null`) is dimmed, never hidden, in Eras. The Discover layer (the shelf's "what the Discover layer will show later") is out of scope here.
- **Pure first.** Each view's logic is a pure model in `music/library/` with unit tests (as `ShelfModel`, `EraTimeline` and the ladder files are). Compose files only draw it.
- **Palette and motion.** Colour comes from `LibraryRelease.palette` or `LibraryArtist.palette` (cached already). Hovering tints the screen's glow with that artist's accent, as Shelf does. Reduced motion (`state.settings.reducedMotion`) holds everything still.
- **Keyboard.** Left and Right, Up and Down move the selection; Enter opens it; Escape clears it (the same pattern as the ladder's audition keys, `moveFocus`).
- **Empty and still-adding.** No artists: the existing "Open an artist from Search..." message. Artists still being added (`state.working`) are listed as pending, not drawn.
- **Hooks.** One `// fork:music` hook in `MusicLibraryShelf.kt` (the switch) and the views in their own files, so upstream merges stay small.

## Eras

The whole library on one time axis. The artist room's Eras timeline (`EraTimeline.kt`) already does this for one artist: it clusters releases into multi-year blocks, places dots on a year ruler by `releasePosition`, sizes them with `timelineDotSize` and lends each block its lead release's palette. The library version reuses those ideas across every artist.

### What the person sees

- **Ruler** across the top: one bar per era, its height the number of releases and its colour the lead release's palette. The selected era is lit.
- **Era header**: "1990 to 1994 · 41 releases · 12 artists", and a **Play era** button (see below).
- **Covers** of the selected era, newest or oldest first (a toggle), each with artist and year; artists with several releases in the era sit together. Click a cover to open the album, an artist name to open the room.
- **Undated** releases in their own block at the end, not on the ruler.
- **A year ago this week** strip stays above the ruler (it is already time-based: `pickYearAgo`).

### Era buckets

Band width follows the library's span, so a ten-year library isn't one block and a sixty-year one isn't sixty:

| Span of dated releases | Band |
|---|---|
| 12 years or fewer | 1 year |
| 13 to 40 years | 5 years |
| over 40 years | 10 years |

Bands start on round years (1990 to 1994, not 1991 to 1995). A band with nothing in it is kept (a gap in a library is information) but drawn thin. Release year comes from `releaseDate` else `year`, as `releasePosition` reads it; demos and folded duplicates are handled as `buildDiscography` does.

### Play era

A button that plays the era: the top albums (`selectTopAlbums`) of each artist that has a release in the era, shuffled, through the existing `playTopAlbums` path. If no release in the era can be played, the button says so.

### Released or added

A second axis: **Added** places each artist at `addedAtEpochMs` (when they joined the library) instead of by release year: a history of your taste rather than the music's. Same ruler, same layout; the model takes a `date` selector. Off by default; last phase.

### Phases

| # | Phase | What gets built | Done when | Status |
|---|---|---|---|---|
| E0 | **Library eras model** | `LibraryEras.kt`: `buildLibraryEras(artists, axis)` with bucket width, bands, lead release, per-era artist and release counts, undated block. | Tests: bucket width at 3 spans, round-year bands, a gap stays, undated collected, lead picks the fullest album, demos and duplicates folded. | Done (untested in Gradle) |
| E1 | **Switch and ruler** | Header pills live (`LibraryView`); era ruler with selection and keys; era header. | You can switch to Eras and step through eras with the arrow keys. | Done (untested in Gradle) |
| E2 | **Era covers** | Cover grid for the selected era grouped by artist; open album and room; order toggle; unplayable dimmed. Extract the shelf's private `CoverTile` into a shared file. | Clicking a cover opens its album from any era. | Done (untested in Gradle) |
| E3 | **Play era** | Shuffled top albums of the era's artists through `playTopAlbums`. | Play era plays; an era with nothing playable says so. | |
| E4 | **Added axis** | Released / Added toggle using `addedAtEpochMs`. | Switching axis re-buckets the same library. | |

## Constellation

Each saved artist is a star. Stars that sound alike sit close; stars that share a lot are joined by a line. The picture of your taste.

The maths is the ladder's. `LadderModel.kt` already turns an artist into a `TagProfile` (genres, tags, mood, style; `LibraryArtist.tagProfile()`) and compares two with `cosine`. Constellation uses the same profiles with *no* target genre removed, since here a shared genre is exactly what pulls two stars together.

### What the person sees

- **Stars**: a circle per artist, in their palette accent; size grows with release count, favourites (the shelf's gold star) get a ring.
- **Lines**: each artist is joined to their nearest few neighbours (3), when the similarity is high enough (cosine 0.35 or more), so the lines form constellations rather than a hairball.
- **Clusters**: groups of stars are labelled by their shared main genre ("shoegaze", "ambient"), drawn as quiet labels and a faint halo.
- **Hover** a star: the name, its top three genres, and the screen glow takes its accent. **Click** opens the room. **Drag** pans, **scroll** zooms, **Escape** resets.
- **Genre chips** along the top (the library's biggest clusters): choose one and the rest dims.
- **Untagged artists** (no genres or tags yet, so no profile) are not placed; they wait in a small "Not placed yet" tray with the reason, and join the map once the enricher fills their tags.
- Fewer than 3 placeable artists: a message ("A constellation needs a few artists with tags") instead of a map.

### Layout

Deterministic, so the same library always draws the same map, and adding one artist doesn't shuffle everything:

1. **Project** the tag profiles to 2D with PCA (power iteration on the artist-by-genre matrix; no library needed, fixed iteration count, signs fixed so a flip never mirrors the map).
2. **Relax** overlaps: a few passes pushing stars that are closer than their radii apart, moving the smaller star first. Ties and start order go by artist id.
3. **Fit** to the view with margin; pan and zoom are a transform on top.

Closeness on the map is an honest 2D squash of tag similarity, and the "nearest neighbours" lines are computed from the real similarity (not from map distance), so a line is never a lie about the sound.

### Clusters and labels

Group by main genre key (the artist's strongest profile key, by `genreKey`), merge groups of one into their nearest neighbour group, keep the biggest 8 for chips. A cluster label sits at the centroid of its stars and names the genre by `canonicalGenre`. No clustering library: this is a few lines over data already in memory.

### Ladder link

The point where the two features meet:

- A star's menu offers **Climb into** and **Climb out from** that artist, opening the existing ladder screen (`LadderRequest.Artist`).
- A cluster label offers **Ladder into** that genre (`LadderRequest.Genre`).
- Artists saved from a ladder (`LibraryArtist.foundVia`, stored since ladder phase 6 but not shown anywhere yet) get a small ring and the line "Found via ladder: Into shoegaze" on hover. This is where `foundVia` first becomes visible.

Later, not in v1: hollow "unexplored" stars for neighbouring genres your library doesn't have, placed by projecting a ladder's rungs onto the same map.

### Phases

| # | Phase | What gets built | Done when | Status |
|---|---|---|---|---|
| C0 | **Similarity graph** | `LibraryConstellation.kt`: profiles for artists, pairwise cosine, nearest 3 per artist above the threshold, untagged set. | Tests: similar artists link, dissimilar don't, untagged are set aside, the graph is symmetric where mutual, same input gives the same graph. | |
| C1 | **Layout** | PCA projection and overlap relaxation, fitted to a unit box. | Tests: deterministic; two similar artists end up closer than two dissimilar ones; no two stars overlap; adding one artist moves the others only slightly; sign is fixed. | |
| C2 | **Clusters** | Main-genre grouping, merge singletons, top 8 chips, label positions. | Tests on a fake library: groups, labels, singleton merged, chips ordered by size. | |
| C3 | **Draw** | Canvas: stars, lines, labels, "Not placed yet" tray, empty state. View switch shows it. | You can switch to Constellation and see your library as a map. | |
| C4 | **Interact** | Hover (name, genres, glow), click opens room, drag pan, scroll zoom, keys, genre-chip dimming. | Every star can be reached by mouse and by keyboard. | |
| C5 | **Ladder link** | Star menu (climb into or out from), cluster "Ladder into", `foundVia` ring and line. | A star opens a ladder; a ladder-found artist shows how it was found. | |

## Order

Eras first (E0 to E3): its models and visuals already exist in the artist room, the risk is low, and it delivers the time axis the year-ago strip hints at. Then Constellation (C0 to C5), which has the new maths and the most to tune. E4 (Added axis) any time after E1. Each phase leaves the app working and can be the last one done.

## Risks

- **No mockup in the repo.** Pill names are all there is to go on. Biggest risk: building the wrong picture. Settle the open questions before E1 or C3 (the first drawing phases); E0, C0, C1 and C2 are pure logic and survive most answers.
- **Compiling.** The project as a whole has still not been compiled through Gradle. These views add more Compose; build locally first and keep logic in tested pure files.
- **Tag coverage.** A young or small library may have few artists with tags. Constellation's untagged tray and minimum of 3 handle it, and it improves as the enricher fills tags. Phase C0 should report how many were placed.
- **Layout feel.** PCA plus relaxation is stable but can look flat for a narrow library (everything in one genre). Tune the relaxation distance and the line threshold with a real library; constants go at the top of the file, as in the ladder.
- **Big libraries.** Pairwise similarity is O(n squared): fine to about 1000 artists. Compute once per library change, cache it keyed by artist ids and tag content, and compute off the main thread.
- **Performance of the ruler.** One bar per band is tiny; the cover grid for a busy band must be a lazy list.

## Open questions

Answer from the mockup; each changes a phase:

1. **Eras**: one library-wide timeline, as here, or the artist-room timeline picked per artist? (E0)
2. **Eras**: does the view show covers, artists, or both? Are bands as above, or fixed decades? (E0, E2)
3. **Constellation**: nodes are artists, as here, or releases? Are lines "similar to" or something else (collaborators, members, labels)? (C0)
4. **Constellation**: is the map positioned by sound (tags), as here, or by something else (genre columns, time, popularity)? (C1)
5. **Both**: anything on the mockup beyond the above: filters, play buttons, a legend, a side panel? (E1, C3)
6. **Ladder link**: do you want the ladder hooks and the `foundVia` ring in Constellation, or kept out? (C5)

## Not in scope

- The Discover layer (unplayable releases).
- Last.fm, ListenBrainz or other listening-history sources: the Added axis uses only when an artist was saved.
- Playlist building from a view; Play era is the only new play action.
