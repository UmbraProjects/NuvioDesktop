# Music Library views: Eras and Constellation

The Library screen has three views in its header: **Shelf**, **Constellation** and **Eras**. Shelf and Eras are built. Constellation is built through C4: the logic (C0 to C2: similarity graph, layout, clusters), the drawing (C3) and the interaction (C4). Its header pill is live. The side panel's ladder buttons (part of C5) were added with the HTPC layout; the found-via ring, cluster "Ladder into" and the play buttons (C6) are still to do.

**Status of this document: the open questions were settled on 2026-10-27** (see "Decisions"). The original mockup is still not in the repo, so the decisions below are the owner's answers to the questions this document raised, plus the extras the mockup shows (legend, side panel, extra play buttons, search/filter box; see "Extras from the mockup"). Where those extras are only described by name, the details below are marked as assumptions to confirm.

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
| E3 | **Play era** | Shuffled top albums of the era's artists through `playTopAlbums`. | Play era plays; an era with nothing playable says so. | Done (untested in Gradle) |
| E4 | **Added axis** | Released / Added toggle using `addedAtEpochMs`. | Switching axis re-buckets the same library. | Done (untested in Gradle) |

## Constellation

Each saved artist is a star. Stars that sound alike sit close; stars that share a lot are joined by a line. The picture of your taste.

The maths is the ladder's. `LadderModel.kt` already turns an artist into a `TagProfile` (genres, tags, mood, style; `LibraryArtist.tagProfile()`) and compares two with `cosine`. Constellation uses the same profiles with *no* target genre removed, since here a shared genre is exactly what pulls two stars together.

### What the person sees

- **Stars**: a circle per artist, in their palette accent; size grows with release count, favourites (the shelf's gold star) get a ring.
- **Lines**: each artist is joined to their nearest few neighbours (3), when the similarity is high enough (cosine 0.35 or more), so the lines form constellations rather than a hairball.
- **Clusters**: groups of stars are labelled by their shared main genre ("shoegaze", "ambient"), drawn as quiet labels and a faint halo.
- **Hover** a star: the name, its top three genres, and the screen glow takes its accent. **Click** selects it and opens the side panel, which has **Ladder from here**, **Climb into** and **Open artist room**. **Drag** pans, **scroll** zooms, **Escape** clears the selection and resets the view.
- **Genre chips** along the top (the library's biggest clusters): choose one and the rest dims.
- **Untagged artists** (no genres or tags yet, so no profile) are not placed; they wait in a "Not placed yet" tray of cards (each with a **Fetch tags** button) and join the map once the enricher fills their tags.
- Fewer than 3 placeable artists: a message ("A constellation needs a few artists with tags") instead of a map.
- The extras from the mockup (legend, side panel for the selected star, play buttons, search/filter box): see "Extras from the mockup".

### Layout

Deterministic, so the same library always draws the same map, and adding one artist doesn't shuffle everything:

1. **Project** the tag profiles to 2D with PCA (power iteration on the artist-by-genre matrix; no library needed, fixed iteration count, signs fixed so a flip never mirrors the map).
2. **Orient** (`orientScores`): PCA fixes the plane but not which way is "x" or which side is "up", and those can flip when one artist is added, jumping the whole map. The cloud is turned until its anchor star (the first by id among those at least half as far from the centre as the farthest) lies on the +x axis, and mirrored so its lopsided side is up. In a stress run on generated libraries this cut a typical star's movement when one artist is added from 0.051 to 0.020 of the box. When two axes are nearly as strong the plane itself can still change (roughly one library in ten); that is inherent to PCA. If it shows on a real library, the fix is to remember the last layout and align the new one to it.
3. **Fit** to the unit box with margin.
4. **Relax** overlaps: up to 300 passes, every pair judged from the same positions and the pushes summed and damped, so list order does not matter; the smaller star of a pair moves further; ties go by artist id. Star radius shrinks as the library grows (`0.25 / sqrt(n)`), which keeps libraries up to about 150 artists overlap-free; around 300 two stars can touch by about a pixel.

Pan and zoom are a transform on top of the unit box.

Closeness on the map is an honest 2D squash of tag similarity, and the "nearest neighbours" lines are computed from the real similarity (not from map distance), so a line is never a lie about the sound.

### Clusters and labels (built, C2)

`ConstellationClusters.kt`, pure and tested:

- **Group** by main genre: the strongest key of the artist's tag profile (by `genreKey`), ties to the key that sorts first. A mood (`mood:...`) never names a group while the artist has a genre.
- **Merge singletons.** A group of one is not a cluster: its star joins the group of the star it is most like, by real tag similarity (`cosine`); if it shares nothing with anyone, by nearest on the map. Only groups that began with two or more stars take newcomers, so a lone star never lands on another lone star and the result does not depend on the order singletons are visited. If every group is a singleton, nothing merges.
- **Name** the cluster by the genre as its artists spell it (`normalizeGenres`), falling back to `canonicalGenre`.
- **Place** the label at the centroid of the cluster's stars; the halo radius reaches the far edge of the farthest star.
- **Chips:** the biggest 8 clusters, biggest first, ties by key. `clusterOf(id)` finds the cluster of a star, for chip dimming.

### Ladder link

The point where the two features meet:

- A star's menu offers **Climb into** and **Climb out from** that artist, opening the existing ladder screen (`LadderRequest.Artist`).
- A cluster label offers **Ladder into** that genre (`LadderRequest.Genre`).
- Artists saved from a ladder (`LibraryArtist.foundVia`, stored since ladder phase 6 but not shown anywhere yet) get a small ring and the line "Found via ladder: Into shoegaze" on hover. This is where `foundVia` first becomes visible.

Later, not in v1: hollow "unexplored" stars for neighbouring genres your library doesn't have, placed by projecting a ladder's rungs onto the same map.

### Phases

| # | Phase | What gets built | Done when | Status |
|---|---|---|---|---|
| C0 | **Similarity graph** | `LibraryConstellation.kt`: profiles for artists, pairwise cosine, nearest 3 per artist above the threshold, untagged set. | Tests: similar artists link, dissimilar don't, untagged are set aside, the graph is symmetric where mutual, same input gives the same graph. | Done (tests pass in a standalone kotlinc run; not through Gradle yet) |
| C1 | **Layout** | PCA projection, orientation, fit to a unit box, overlap relaxation. | Tests: deterministic; two similar artists end up closer than two dissimilar ones; no two stars overlap (including a 150-artist library); orientation is the same however the plane came out; sign is fixed. | Done (as C0). Review fixes: star size and passes for big libraries, orientation step |
| C2 | **Clusters** | Main-genre grouping, merge singletons, top 8 chips, label positions. | Tests on a fake library: groups, labels, singleton merged, chips ordered by size, mood never names a group, centroid and halo. | Done (as C0) |
| C3 | **Draw** | Canvas: stars, lines, cluster labels and halos, **legend**, "Not placed yet" tray, empty state. View switch shows it. | You can switch to Constellation and see your library as a map. | Done (untested in Gradle) |
| C4 | **Interact** | Hover (name, genres, glow), click selects a star, drag pan, scroll zoom, keys, genre-chip dimming, **search/filter box**, **side panel** for the selected star. | Every star can be reached by mouse and by keyboard; typing in the box dims the stars that don't match. | Done (untested in Gradle) |
| C5 | **Ladder link** | Star menu and side-panel buttons (climb into or out from), cluster "Ladder into", `foundVia` ring and line. | A star opens a ladder; a ladder-found artist shows how it was found. | Partly done (UI not compiled): the panel has Ladder from here and Climb into, and shows `foundVia`; no star menu, cluster "Ladder into" or ring yet |
| C6 | **Play buttons** | **Play** on the side panel (the star's top albums, `playTopAlbums`) and **Play cluster** (shuffled top albums of the cluster's artists), as Eras' Play era does. A star or cluster with nothing playable says so. | Playing from a star or a cluster starts audio; nothing playable says so. | |

### Built in C3 and C4

Pure and tested (tests not yet run through Gradle): `ConstellationModel.kt` (`buildConstellationModel`, `constellationKey`, `mapFrame`, `paddedFrame`, `linkAlpha`, `clusterLead`, `drawsClusterLabel`) and `ConstellationInteract.kt` (`Camera`, `ScreenMap`, `hitStar`, `stepStar`, `cycleStar`, `matchesQuery`, `focusIds`, `topGenres`). The drawing and the controls are `ConstellationView.kt`, hosted by one `// fork:music` branch in `MusicLibraryShelf.kt`. How it differs from the sketches above:

- **Model.** Built once off the main thread, rebuilt only when the key changes. The key holds id, genres, tags, style, mood and release-card count per artist, so a palette arriving or a favourite toggled does not rebuild the map; palette, favourite and name are read live when drawing.
- **Viewport.** The unit box is stretched along the longer side of the canvas by at most 1.6 and centred. Star radii follow the shorter side, so stars stay round and, since distances only grow along the stretched axis, relaxed stars still do not overlap.
- **Zoom.** 1x to 8x about the cursor. Positions scale with the zoom, star radii with its square root, so zooming in also pulls crowded stars apart. The pan is clamped so the map always covers the canvas.
- **Drawing.** Stars in the palette accent with a soft glow and a gold ring for a favourite; lines as a gradient between the two stars' colours, firmer for closer matches and for mutual picks, and bright on the selected star; halos and genre names only for groups of two or more, the name just above the halo and kept on the canvas (not hidden under the stars); a legend; the "Not placed yet" tray, whose chips open the artist room.
- **Interaction.** Hover tooltip with the name and top three genres, flipped near the right edge. Click selects; clicking empty space clears. Arrow keys move to the nearest star that way (preferring a cone round the axis), Tab and Shift+Tab walk every star in reading order (this is what guarantees keyboard reach), Enter opens the room, + and - zoom, Escape clears the selection and resets the view. Selecting a star pans it into view.
- **Filter.** The search box (name, genres, tags, style, mood) and the genre chips (the 8 biggest clusters) dim what does not match, in step: a star must be in the chosen cluster and match the text. Dimmed stars, their lines and halos are faded, never hidden, and keys skip them.
- **Side panel.** Avatar, name, release count and cluster, top genres, `foundVia` when set, the closest neighbours with their similarity (click to select), and Open artist room. The map narrows while it is open. Since the HTPC layout it also lists the top five albums and has Ladder from here and Climb into; Play (C6) is not on it yet.
- **Legend.** Size, favourite ring, line, halo, dimmed. It is now a "Key" chip in the bottom-left corner that opens on demand. The found-via-ladder ring is added with C5.

## Order

Eras first (E0 to E3): its models and visuals already exist in the artist room, the risk is low, and it delivers the time axis the year-ago strip hints at. Then Constellation (C0 to C6), which has the new maths and the most to tune. E4 (Added axis) any time after E1. Each phase leaves the app working and can be the last one done. Built so far: E0 to E4 and C0 to C4, plus the panel's ladder buttons from C5; next is the rest of C5, then C6.

## Risks

- **No mockup in the repo.** The open questions are settled (see "Decisions"), but the mockup's look is not available to build against; C3 follows the roadmap's picture plus the named extras, so expect a pass of visual adjustment once it is on screen.
- **Compiling.** The Eras Compose files and the Shelf have still not been through Gradle (the first CI run found missing `getValue` and `setValue` imports in `CoverTile.kt` and `MusicLibraryShelf.kt`). The Constellation logic (C0 to C2) was compiled and tested outside Gradle with kotlinc against the real ladder files, which does not cover the Compose files. C3 and C4 (`ConstellationModel.kt`, `ConstellationInteract.kt`, `ConstellationView.kt` and their tests) were written without any compiler available, so none of it has been compiled or run yet: expect a round of small compile fixes. Keep logic in tested pure files and build the drawing phases locally first.
- **Tag coverage.** A young or small library may have few artists with tags. Constellation's untagged tray and minimum of 3 handle it, and it improves as the enricher fills tags. Phase C0 should report how many were placed.
- **Layout feel and stability.** PCA plus orientation plus relaxation is deterministic and usually steady, but it can look flat for a narrow library (everything in one genre), and when two axes are nearly equal the plane can change when one artist is added (see Layout, step 2). Tune the relaxation distance and the line threshold with a real library; constants go at the top of the file, as in the ladder.
- **Big libraries.** Pairwise similarity is O(n squared): fine to about 1000 artists. Compute once per library change, cache it keyed by artist ids and tag content, and compute off the main thread.
- **Performance of the ruler.** One bar per band is tiny; the cover grid for a busy band must be a lazy list.

## Decisions

The open questions, answered 2026-10-27:

| # | Question | Decision |
|---|---|---|
| 1 | Eras: one library-wide timeline, or per artist? | One timeline for the whole library (as built). |
| 2 | Eras: covers, artists or both? Bands as drafted, or fixed decades? | Covers; bands scale with the library's span (as built). |
| 3 | Constellation: nodes are artists or releases? What do lines mean? | Artists are stars; a line means the two sound alike (tag similarity). |
| 4 | Constellation: what positions the map? | Sound, from tags (PCA), as built in C1. |
| 5 | Anything on the mockup beyond the draft? | Yes: a legend, a side panel for the selected item, extra play buttons, and a search/filter box. See below. |
| 6 | Ladder hooks and the `foundVia` ring in Constellation? | Included (C5). |

## Extras from the mockup

Named by the owner; the details are assumptions until the mockup is checked against them.

- **Legend** (C3): a small key for what the picture encodes: star size is release count, a gold ring is a favourite, a line is "sounds alike", the ladder ring is "found via ladder", a dimmed star is not matching the filter.
- **Search/filter box** (C4): typing a name or genre dims the stars that don't match, like the genre chips do; clearing it restores them. *To confirm:* whether it belongs to Constellation only, or is shared by Shelf, Eras and Constellation in the header.
- **Side panel** (C4): selecting a star opens a panel with the artist's name and avatar, top genres, release count, how it was found (`foundVia`), its closest neighbours, and buttons: Open artist room, Climb into, Climb out from, Play (C5, C6). *To confirm:* whether Eras has a side panel too (it already has an era header), and what the panel shows for a cluster label.
- **Extra play buttons** (C6): Play on a star (top albums) and Play cluster. *To confirm:* what the mockup's buttons play, and whether Shelf rows get one too.

## Status of the mockup extras

Built in C3 and C4 as described above, still on the roadmap's assumptions until checked against the mockup: the legend (C3), the search/filter box and the side panel (C4). Still open: whether the search box should be shared by Shelf, Eras and Constellation in the header, whether Eras gets a side panel, and what the panel shows for a cluster label. Extra play buttons are C6.

## Not in scope

- The Discover layer (unplayable releases).
- Last.fm, ListenBrainz or other listening-history sources: the Added axis uses only when an artist was saved.
- Playlist building from a view; Play era is the only new play action.

## HTPC layout notes (2026-10-27)

- **Shelf:** the year-ago banner is now a row of up to four cards (`ShelfCard` in `ShelfModel.kt`: a year ago this week, a starred artist, added recently, continue your ladder), drawn by `ShelfCardRow` in `MusicLibraryShelf.kt`. Rows have 48dp side margins. The Eras view keeps the year-ago strip.
- **Eras:** the summary header, the short-era neighbour row ("Also from nearby") and the future-date clean-up (a date more than a year ahead is undated) were already built. Not built: bars stacked by genre mix.
- **Constellation:** wider side panel with top albums, Ladder from here and Climb into; a "Thin spots" row of ladder chips; the key as a corner chip; larger chips with a focus ring; Not-placed cards with Fetch tags. Not built: ghost halos on the map, leader lines for overlapping labels, chips beside the map.
- **Artist page and Stage view:** see `Docs/HTPC_MUSIC_BLANK_SPACE.md`.

UI not compiled. The audited list of what is not built is in `Docs/HTPC_MUSIC_BLANK_SPACE.md`.
