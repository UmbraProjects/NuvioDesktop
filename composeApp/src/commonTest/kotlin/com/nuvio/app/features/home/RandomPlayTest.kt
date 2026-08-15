package com.nuvio.app.features.home

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.watched.watchedItemKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RandomPlayTest {
    @Test
    fun candidatesRespectTypeGenreAndMinimumRating() {
        val section = section(
            genre = "Drama",
            items = listOf(
                preview(id = "movie-low", type = "movie", rating = "6.9", genres = listOf("Drama")),
                preview(id = "movie-good", type = "movie", rating = "IMDb 7.5 / 10", genres = listOf("Drama")),
                preview(id = "series", type = "series", rating = "8.4", genres = listOf("Drama")),
                preview(id = "anime", type = "anime", rating = "8.1", genres = listOf("Anime", "Drama")),
            ),
        )

        val result = randomPlayCandidates(
            sourceSections = listOf(section),
            category = RandomPlayCategory.Movie,
            allowedGenres = setOf("Drama"),
            minimumImdbRating = 7f,
        )

        assertEquals(listOf("movie-good"), result.map(MetaPreview::id))
    }

    @Test
    fun animeMovieAndAnimeSeriesStaySeparate() {
        val section = section(
            contentType = "anime",
            items = listOf(
                preview(id = "film", type = "anime_movie", rating = "8.0", genres = listOf("Anime")),
                preview(id = "show", type = "anime", rating = "8.0", genres = listOf("Anime")),
            ),
        )

        assertEquals(
            listOf("film"),
            randomPlayCandidates(
                listOf(section),
                RandomPlayCategory.AnimeMovie,
                RandomPlayGenres.toSet(),
                0f,
            ).map(MetaPreview::id),
        )
        assertEquals(
            listOf("show"),
            randomPlayCandidates(
                listOf(section),
                RandomPlayCategory.AnimeSeries,
                RandomPlayGenres.toSet(),
                0f,
            ).map(MetaPreview::id),
        )
    }

    @Test
    fun animeCatalogRowsClassifyItemsTypedAsPlainSeriesAndMovies() {
        // AIOMetadata's SIMKL anime catalogs: the manifest declares the catalog `anime`, the metas
        // come back typed `series`/`movie` with no "Anime" genre. Both used to land in the plain
        // Movie/Series buckets, leaving the anime cards empty.
        val row = section(
            contentType = "anime",
            items = listOf(
                preview(id = "show", type = "series", rating = "8.0"),
                preview(id = "film", type = "movie", rating = "8.0"),
            ),
        )

        assertEquals(listOf("show"), candidates(row, RandomPlayCategory.AnimeSeries))
        assertEquals(listOf("film"), candidates(row, RandomPlayCategory.AnimeMovie))
        assertTrue(candidates(row, RandomPlayCategory.Series).isEmpty())
        assertTrue(candidates(row, RandomPlayCategory.Movie).isEmpty())
    }

    @Test
    fun animeNativeIdsClassifyItemsFromCatalogsThatSayNothingAboutAnime() {
        val row = section(
            contentType = "series",
            items = listOf(
                preview(id = "kitsu:1376", type = "series", rating = "8.0"),
                preview(id = "mal:32281", type = "movie", rating = "8.0"),
                preview(id = "tt0903747", type = "series", rating = "9.4"),
            ),
        )

        assertEquals(listOf("kitsu:1376"), candidates(row, RandomPlayCategory.AnimeSeries))
        assertEquals(listOf("mal:32281"), candidates(row, RandomPlayCategory.AnimeMovie))
        assertEquals(listOf("tt0903747"), candidates(row, RandomPlayCategory.Series))
    }

    @Test
    fun animeTypedItemsTakeTheirFormFromTheRowAndDefaultToSeries() {
        val movieRow = section(
            contentType = "movie",
            items = listOf(preview(id = "film", type = "anime", rating = "8.0")),
        )
        val animeRow = section(
            contentType = "anime",
            items = listOf(preview(id = "show", type = "anime", rating = "8.0")),
        )

        assertEquals(listOf("film"), candidates(movieRow, RandomPlayCategory.AnimeMovie))
        assertEquals(listOf("show"), candidates(animeRow, RandomPlayCategory.AnimeSeries))
    }

    @Test
    fun mixedAnimeCatalogSplitsFilmsFromShowsPerItem() {
        // One `anime` catalog carrying both, every meta typed `anime` — the row can't discriminate,
        // so each item is read on its own: a year range is a show, a single playable video a film.
        val row = section(
            contentType = "anime",
            items = listOf(
                preview(id = "show-run", type = "anime", rating = "8.0", releaseInfo = "2013-2015"),
                preview(id = "show-ongoing", type = "anime", rating = "8.0", releaseInfo = "2019-"),
                preview(
                    id = "kitsu:11614",
                    type = "anime",
                    rating = "8.0",
                    releaseInfo = "2016",
                    // A film's hint addresses the film itself; see defaultVideoFormOrNull.
                    defaultVideoId = "kitsu:11614",
                ),
            ),
        )

        assertEquals(listOf("kitsu:11614"), candidates(row, RandomPlayCategory.AnimeMovie))
        assertEquals(
            listOf("show-run", "show-ongoing"),
            candidates(row, RandomPlayCategory.AnimeSeries),
        )
    }

    @Test
    fun isoReleaseDatesAreNotMistakenForSeriesRuns() {
        val row = section(
            contentType = "anime",
            items = listOf(
                preview(
                    id = "kitsu:11614",
                    type = "anime",
                    rating = "8.0",
                    releaseInfo = "2016-05-01",
                    defaultVideoId = "kitsu:11614",
                ),
            ),
        )

        assertEquals(listOf("kitsu:11614"), candidates(row, RandomPlayCategory.AnimeMovie))
    }

    @Test
    fun dottedAndCapitalisedAddonTypesAreUnderstood() {
        // Real AIOMetadata manifest types: `anime.movie` / `anime.series` on its search catalogs,
        // `Films` / `TV` on its Simkl rows.
        val dotted = section(
            contentType = "anime.movie",
            items = listOf(preview(id = "film", type = "anime.movie", rating = "8.0")),
        )
        val films = section(
            contentType = "Films",
            items = listOf(preview(id = "movie", type = "Films", rating = "8.0")),
        )
        val tv = section(
            contentType = "TV",
            items = listOf(preview(id = "show", type = "TV", rating = "8.0")),
        )

        assertEquals(listOf("film"), candidates(dotted, RandomPlayCategory.AnimeMovie))
        assertEquals(listOf("movie"), candidates(films, RandomPlayCategory.Movie))
        assertEquals(listOf("show"), candidates(tv, RandomPlayCategory.Series))
    }

    @Test
    fun aBareYearIsNotTreatedAsAFilm() {
        // Kitsu-sourced series carry a bare `releaseInfo` year as often as a range — "Attack on
        // Titan" is "2013" — so only an explicit range may imply a show, and never the reverse.
        val row = section(
            contentType = "anime",
            items = listOf(preview(id = "show", type = "anime", rating = "8.0", releaseInfo = "2013")),
        )

        assertEquals(listOf("show"), candidates(row, RandomPlayCategory.AnimeSeries))
    }

    @Test
    fun unrecognisedTypesStayOutOfEveryCategory() {
        // Cloud-library rows arrive typed `library`; nothing should sweep them into a category
        // just because their row declares a content type.
        val row = section(
            contentType = "series",
            items = listOf(preview(id = "aiostreams::library.42", type = "library", rating = "")),
        )

        RandomPlayCategory.entries.forEach { category ->
            assertTrue(candidates(row, category).isEmpty(), "leaked into $category")
        }
    }

    @Test
    fun animeOnlyGenresSurviveANarrowedAllowList() {
        val row = section(
            contentType = "anime",
            items = listOf(
                preview(id = "shounen", type = "series", rating = "8.0", genres = listOf("Shounen", "Isekai")),
                preview(id = "horror", type = "series", rating = "8.0", genres = listOf("Horror")),
            ),
        )

        val result = randomPlayCandidates(
            sourceSections = listOf(row),
            category = RandomPlayCategory.AnimeSeries,
            allowedGenres = setOf("Shounen"),
            minimumImdbRating = 0f,
        )

        assertEquals(listOf("shounen"), result.map(MetaPreview::id))
    }

    @Test
    fun aStoredSelectionOfEveryOldGenreStillMeansEveryGenre() {
        // The 18 standard genres were the whole list before anime joined it. Left alone, such a set
        // would start filtering — and drop every item whose catalog gives it no genres at all.
        val storedBeforeAnimeGenres = setOf(
            "Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family",
            "Fantasy", "History", "Horror", "Music", "Mystery", "Romance", "Science Fiction",
            "Thriller", "War", "Western",
        )

        assertEquals(RandomPlayGenres.toSet(), expandLegacyRandomPlayGenres(storedBeforeAnimeGenres))

        val row = section(items = listOf(preview(id = "no-genres", type = "movie", rating = "8.0")))
        val result = randomPlayCandidates(
            sourceSections = listOf(row),
            category = RandomPlayCategory.Movie,
            allowedGenres = expandLegacyRandomPlayGenres(storedBeforeAnimeGenres),
            minimumImdbRating = 0f,
        )
        assertEquals(listOf("no-genres"), result.map(MetaPreview::id))
    }

    @Test
    fun aDeliberatelyNarrowedSelectionIsLeftAlone() {
        val narrowed = setOf("Horror", "Thriller")

        assertEquals(narrowed, expandLegacyRandomPlayGenres(narrowed))
    }

    @Test
    fun catalogGenreCanQualifyItemsWithoutItemGenres() {
        val section = section(
            genre = "Science-Fiction",
            items = listOf(preview(id = "movie", type = "movie", rating = "7.0")),
        )

        assertEquals(
            listOf("movie"),
            randomPlayCandidates(
                listOf(section),
                RandomPlayCategory.Movie,
                setOf("Science Fiction"),
                0f,
            ).map(MetaPreview::id),
        )
    }

    @Test
    fun watchedTitlesAreExcludedButPartiallyWatchedSeriesRemainEligible() {
        val section = section(
            items = listOf(
                preview(id = "watched", type = "movie", rating = "8.0", genres = listOf("Drama")),
                preview(id = "unwatched", type = "movie", rating = "8.0", genres = listOf("Drama")),
                preview(id = "in-progress", type = "series", rating = "8.0", genres = listOf("Drama")),
            ),
        )
        val watchedKeys = setOf(
            watchedItemKey(type = "movie", id = "watched"),
            watchedItemKey(type = "series", id = "in-progress", season = 1, episode = 1),
        )

        val movieCandidates = randomPlayCandidates(
            sourceSections = listOf(section),
            category = RandomPlayCategory.Movie,
            allowedGenres = setOf("Drama"),
            minimumImdbRating = 0f,
            watchedKeys = watchedKeys,
        )
        val seriesCandidates = randomPlayCandidates(
            sourceSections = listOf(section),
            category = RandomPlayCategory.Series,
            allowedGenres = setOf("Drama"),
            minimumImdbRating = 0f,
            watchedKeys = watchedKeys,
        )

        assertEquals(listOf("unwatched"), movieCandidates.map(MetaPreview::id))
        assertEquals(listOf("in-progress"), seriesCandidates.map(MetaPreview::id))
    }

    @Test
    fun animeTypeNamesTheFormForCatalogsThatTypeEverythingAnime() {
        val row = section(
            contentType = "anime",
            items = listOf(
                preview(id = "film", type = "anime", rating = "8.0", animeType = "movie"),
                preview(id = "show", type = "anime", rating = "8.0", animeType = "TV"),
                // Kitsu types both of these `series` in its own metas, so they follow TV.
                preview(id = "ona", type = "anime", rating = "8.0", animeType = "ONA"),
                preview(id = "ova", type = "anime", rating = "8.0", animeType = "OVA"),
            ),
        )

        assertEquals(listOf("film"), candidates(row, RandomPlayCategory.AnimeMovie))
        assertEquals(listOf("show", "ona", "ova"), candidates(row, RandomPlayCategory.AnimeSeries))
    }

    @Test
    fun animeTypeOutranksAYearRangeAndAnUnknownValueFallsThrough() {
        // A film whose releaseInfo happens to read as a run must still be a film.
        val stated = preview(
            id = "film",
            type = "anime",
            rating = "8.0",
            releaseInfo = "2013-2015",
            animeType = "movie",
        )
        val unknown = preview(
            id = "show",
            type = "anime",
            rating = "8.0",
            releaseInfo = "2013-2015",
            animeType = "who-knows",
        )
        val row = section(contentType = "anime", items = listOf(stated, unknown))

        assertEquals(listOf("film"), candidates(row, RandomPlayCategory.AnimeMovie))
        assertEquals(listOf("show"), candidates(row, RandomPlayCategory.AnimeSeries))
    }

    @Test
    fun aResolvedEpisodeListOverrulesAnAddonThatTypedAShowAsAFilm() {
        // Verbatim from a reported pick: Takopi's Original Sin, a six-episode show, arrives from a
        // SIMKL anime row typed `movie`, with no animeType, no defaultVideoId and a bare year. No
        // field in that payload contradicts the type — only the meta's own episode list does.
        val row = section(
            contentType = "anime",
            items = listOf(
                preview(
                    id = "mal:60489",
                    type = "movie",
                    rating = "8.7",
                    releaseInfo = "2025",
                    genres = listOf("Drama", "Psychological", "Science Fiction"),
                    carriesAnimeCatalogueId = true,
                ),
            ),
        )

        assertEquals(listOf("mal:60489"), candidates(row, RandomPlayCategory.AnimeMovie))
        assertTrue(row.items.single().randomPlayAnimeMovieNeedsVerifying(row.randomPlayRow()))

        val verified = randomPlaySourceSections(
            homeSections = listOf(row),
            collectionSections = emptyList(),
            settings = HomeCatalogSettingsUiState(),
            pool = RandomPlayPoolState(
                facts = mapOf("movie:mal:60489" to RandomPlayMetaFacts(episodeCount = 6)),
            ),
        )

        assertEquals(
            listOf("mal:60489"),
            randomPlayCandidates(
                sourceSections = verified,
                category = RandomPlayCategory.AnimeSeries,
                allowedGenres = RandomPlayGenres.toSet(),
                minimumImdbRating = 0f,
            ).map(MetaPreview::id),
        )
        assertTrue(
            randomPlayCandidates(
                sourceSections = verified,
                category = RandomPlayCategory.AnimeMovie,
                allowedGenres = RandomPlayGenres.toSet(),
                minimumImdbRating = 0f,
            ).isEmpty(),
        )
    }

    @Test
    fun aSingleVideoIsNotAnEpisodeListAndAStatedFilmIsNotReVerified() {
        // A film's meta legitimately carries one video for itself, so only a real list demotes.
        val film = preview(id = "mal:1", type = "movie", rating = "8.0")
        val oneVideo = film.withRandomPlayFacts(
            mapOf("movie:mal:1" to RandomPlayMetaFacts(episodeCount = 1)),
        )
        assertEquals(RandomPlayCategory.AnimeMovie, oneVideo.randomPlayCategoryIn())

        // Titles that stated their own form are taken at their word and cost no request.
        val stated = preview(id = "mal:2", type = "anime", rating = "8.0", animeType = "movie")
        val hinted = preview(id = "mal:3", type = "anime", rating = "8.0", defaultVideoId = "mal:3")
        assertFalse(stated.randomPlayAnimeMovieNeedsVerifying())
        assertFalse(hinted.randomPlayAnimeMovieNeedsVerifying())
        // And a title already verified is not asked about twice.
        assertFalse(oneVideo.randomPlayAnimeMovieNeedsVerifying())
    }

    @Test
    fun aDefaultVideoIdPointingAtAnEpisodeMarksAShowNotAFilm() {
        // Plenty of addons set behaviorHints.defaultVideoId on shows too, at the first episode.
        // Read as mere presence it filed whole shows under Anime Movies; what it points at is the
        // part that carries the meaning.
        val row = section(
            contentType = "anime",
            items = listOf(
                preview(id = "kitsu:1", type = "anime", rating = "8.0", defaultVideoId = "kitsu:1"),
                preview(id = "kitsu:2", type = "anime", rating = "8.0", defaultVideoId = "kitsu:2:1"),
                preview(id = "tt3", type = "anime", rating = "8.0", defaultVideoId = "tt3:1:1"),
                // Unrelated to the meta's own id: not understood, so it gets no vote and the
                // anime-catalog default applies.
                preview(id = "kitsu:4", type = "anime", rating = "8.0", defaultVideoId = "tt9999"),
            ),
        )

        assertEquals(listOf("kitsu:1"), candidates(row, RandomPlayCategory.AnimeMovie))
        assertEquals(
            listOf("kitsu:2", "tt3", "kitsu:4"),
            candidates(row, RandomPlayCategory.AnimeSeries),
        )
    }

    @Test
    fun aRowThatNamesOneFormOutranksTheDefaultVideoHint() {
        // Only a bare `anime` catalog is a mixed bag. A catalog declared anime.series says every
        // entry is a show, which beats a hint the addon may be handing out liberally.
        val declared = section(
            contentType = "anime.series",
            items = listOf(preview(id = "show", type = "anime", rating = "8.0", defaultVideoId = "show")),
        )

        assertEquals(listOf("show"), candidates(declared, RandomPlayCategory.AnimeSeries))
        assertTrue(candidates(declared, RandomPlayCategory.AnimeMovie).isEmpty())
    }

    @Test
    fun animeTypeCorrectsAnAddonThatMistypesTheMeta() {
        // The form the anime catalogue states is more specific than Stremio's two-value type, so
        // it settles the disagreement rather than being ignored because `type` parsed cleanly.
        val row = section(
            contentType = "anime",
            items = listOf(
                preview(id = "show", type = "movie", rating = "8.0", animeType = "TV"),
                preview(id = "film", type = "series", rating = "8.0", animeType = "movie"),
            ),
        )

        assertEquals(listOf("film"), candidates(row, RandomPlayCategory.AnimeMovie))
        assertEquals(listOf("show"), candidates(row, RandomPlayCategory.AnimeSeries))
    }

    @Test
    fun classificationTraceNamesTheDecidingFields() {
        val row = section(
            contentType = "anime",
            items = listOf(
                preview(
                    id = "kitsu:1",
                    type = "anime",
                    rating = "8.0",
                    animeType = "movie",
                    defaultVideoId = "kitsu:1",
                    releaseInfo = "2016",
                ),
            ),
        )

        val trace = randomPlayClassificationTrace(listOf(row), RandomPlayCategory.AnimeMovie)

        assertEquals(
            "kitsu:1(type=anime,animeType=movie,defaultVideo=kitsu:1,release=2016,row=anime)",
            trace,
        )
    }

    @Test
    fun animeSurvivesWhateverIdNamespaceItArrivesUnder() {
        // The point of the side-channel signals: the same title reaches different users as an
        // imdb, tmdb, tvdb or simkl id, from a row that declares nothing about anime.
        val row = section(
            contentType = "series",
            items = listOf(
                preview(id = "tt2250192", type = "series", rating = "8.0", carriesAnimeCatalogueId = true),
                preview(id = "tmdb:45782", type = "series", rating = "8.0", animeType = "TV"),
                preview(id = "simkl:12345", type = "movie", rating = "8.0", carriesAnimeCatalogueId = true),
                preview(id = "tt0903747", type = "series", rating = "9.4"),
            ),
        )

        assertEquals(
            listOf("tt2250192", "tmdb:45782"),
            candidates(row, RandomPlayCategory.AnimeSeries),
        )
        assertEquals(listOf("simkl:12345"), candidates(row, RandomPlayCategory.AnimeMovie))
        assertEquals(listOf("tt0903747"), candidates(row, RandomPlayCategory.Series))
        assertTrue(candidates(row, RandomPlayCategory.Movie).isEmpty())
    }

    @Test
    fun aResolvedMalIdMarksATitleAsAnimeAfterTheFact() {
        val item = preview(id = "tt5544384", type = "movie", rating = "8.0")
        val facts = mapOf("movie:tt5544384" to RandomPlayMetaFacts(carriesAnimeCatalogueId = true))

        assertEquals(
            RandomPlayCategory.AnimeMovie,
            item.withRandomPlayFacts(facts).randomPlayCategoryIn(),
        )
        assertEquals(RandomPlayCategory.Movie, item.randomPlayCategoryIn())
    }

    @Test
    fun lateResolvedGenresRescueItemsTheCatalogDescribedWithNone() {
        // The public Kitsu addon sends no genres at all on its Trending and Highest Rated rows —
        // the app fills them in later. Judged on the raw row, a narrowed allow-list dropped every
        // one of those titles.
        val row = section(
            contentType = "anime",
            items = listOf(preview(id = "kitsu:1", type = "movie", rating = "8.0")),
        )
        val settings = HomeCatalogSettingsUiState(randomPlayGenres = setOf("Drama"))

        val beforeFacts = randomPlayCandidates(
            sourceSections = listOf(row),
            category = RandomPlayCategory.AnimeMovie,
            allowedGenres = settings.randomPlayGenres,
            minimumImdbRating = 0f,
        )
        assertTrue(beforeFacts.isEmpty())

        val resolved = randomPlaySourceSections(
            homeSections = listOf(row),
            collectionSections = emptyList(),
            settings = settings,
            pool = RandomPlayPoolState(
                facts = mapOf("movie:kitsu:1" to RandomPlayMetaFacts(genres = listOf("Drama"))),
            ),
        )
        assertEquals(
            listOf("kitsu:1"),
            randomPlayCandidates(
                sourceSections = resolved,
                category = RandomPlayCategory.AnimeMovie,
                allowedGenres = settings.randomPlayGenres,
                minimumImdbRating = 0f,
            ).map(MetaPreview::id),
        )
    }

    @Test
    fun aResolvedYearRangeOverridesTheCatalogsBareStartYear() {
        // Catalogs hand out a bare start year for shows and films alike, which tells the form
        // inference nothing. The run of years the metadata layer returns is the whole point of
        // asking, so it must win over the year already on the item.
        val item = preview(id = "kitsu:2", type = "anime", rating = "8.0", releaseInfo = "2013")
        val facts = mapOf("anime:kitsu:2" to RandomPlayMetaFacts(releaseInfo = "2013-2015"))

        assertEquals("2013-2015", item.withRandomPlayFacts(facts).releaseInfo)
        // A fetched bare year does not displace one the row already had.
        assertEquals(
            "2013",
            item.withRandomPlayFacts(mapOf("anime:kitsu:2" to RandomPlayMetaFacts(releaseInfo = "2016"))).releaseInfo,
        )
        // Genres and ratings are fill-only: the row keeps what it stated.
        val described = preview(id = "kitsu:3", type = "movie", rating = "9.0", genres = listOf("Horror"))
        val overwritten = described.withRandomPlayFacts(
            mapOf("movie:kitsu:3" to RandomPlayMetaFacts(genres = listOf("Comedy"), imdbRating = "1.0")),
        )
        assertEquals(listOf("Horror"), overwritten.genres)
        assertEquals("9.0", overwritten.imdbRating)
    }

    @Test
    fun onlyAGuessedAnimeFormIsWorthResolving() {
        val animeRow = RandomPlayRow(contentType = "anime")

        // Bare `anime` with nothing to read: the Series bucket is a fallback, so late metadata
        // could still move it.
        assertTrue(preview(id = "a", type = "anime", rating = "").randomPlayFormIsGuessed(animeRow))
        // Stated by the item, or by a row that names a form — nothing to learn.
        assertFalse(preview(id = "b", type = "movie", rating = "").randomPlayFormIsGuessed(animeRow))
        assertFalse(
            preview(id = "c", type = "anime", rating = "", releaseInfo = "2013-2015")
                .randomPlayFormIsGuessed(animeRow),
        )
        assertFalse(
            preview(id = "d", type = "anime", rating = "")
                .randomPlayFormIsGuessed(RandomPlayRow(contentType = "movie")),
        )
    }

    @Test
    fun extraPagesJoinTheirRowWithoutDuplicatingIt() {
        val row = section(items = listOf(preview(id = "page1", type = "movie", rating = "8.0")))
        val settings = HomeCatalogSettingsUiState()

        val widened = randomPlaySourceSections(
            homeSections = listOf(row),
            collectionSections = emptyList(),
            settings = settings,
            pool = RandomPlayPoolState(
                extraPages = mapOf(
                    row.key to listOf(
                        preview(id = "page1", type = "movie", rating = "8.0"),
                        preview(id = "page2", type = "movie", rating = "8.0"),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf("page1", "page2"),
            randomPlayCandidates(
                sourceSections = widened,
                category = RandomPlayCategory.Movie,
                allowedGenres = RandomPlayGenres.toSet(),
                minimumImdbRating = 0f,
            ).map(MetaPreview::id),
        )
    }

    @Test
    fun onlyCardsUnderTheTargetCountAsShort() {
        val plenty = section(
            items = (1..RANDOM_PLAY_TARGET_CANDIDATES).map {
                preview(id = "movie-$it", type = "movie", rating = "8.0")
            } + preview(id = "show", type = "series", rating = "8.0"),
        )
        val settings = HomeCatalogSettingsUiState(
            randomPlayCategories = setOf(RandomPlayCategory.Movie, RandomPlayCategory.Series),
        )

        assertEquals(
            setOf(RandomPlayCategory.Series),
            randomPlayShortCategories(listOf(plenty), settings, emptySet()),
        )
    }

    @Test
    fun launchCardsRoundTripTheirCategory() {
        RandomPlayCategory.entries.forEach { category ->
            val card = MetaPreview(
                id = "random:${category.name}",
                type = "random_play",
                name = category.name,
            )
            assertEquals(category, card.randomPlayCategoryOrNull())
        }
        assertNull(preview(id = "normal", type = "movie", rating = "7.0").randomPlayCategoryOrNull())
    }

    @Test
    fun virtualCatalogIsIncludedInNormalHomeRows() {
        val randomSection = section(items = listOf(preview(id = "random", type = "random_play", rating = "")))
            .copy(key = RANDOM_PLAY_SECTION_KEY, title = "Random Play", addonName = "Nuvio")
        val catalog = HomeCatalogSettingsItem(
            key = "catalog",
            defaultTitle = "Catalog",
            addonName = "Addon",
        )
        val disabled = HomeCatalogSettingsItem(
            key = "disabled",
            defaultTitle = "Disabled",
            addonName = "Addon",
            enabled = false,
        )

        val result = buildEnabledHomeItems(
            settingsItems = listOf(catalog, disabled),
            effectiveSections = listOf(randomSection),
        )

        assertEquals(listOf(RANDOM_PLAY_SECTION_KEY, "catalog"), result.map { it.key })
        assertFalse(result.first().heroSourceEnabled)
    }

    @Test
    fun collageOnlyUsesPostersFromItsCategory() {
        val categoryPosters = (1..7).map { "category-$it" }

        val categoryCollage = randomPlayPosterCollage(
            category = RandomPlayCategory.Movie,
            posters = categoryPosters,
        )
        val emptyAnimeCollage = randomPlayPosterCollage(
            category = RandomPlayCategory.AnimeSeries,
            posters = emptyList(),
        )

        assertEquals(5, categoryCollage.size)
        assertTrue(categoryCollage.all { it in categoryPosters })
        assertTrue(emptyAnimeCollage.isEmpty())
    }

    @Test
    fun windowsRotateThroughEverySourceWithoutRepeating() {
        val sources = (1..96).toList()
        val window = RANDOM_PLAY_COLLECTION_SOURCE_WINDOW

        // What ensureLoaded does across successive passes: take a window, advance by what it took,
        // and stop once the covered count reaches the end.
        var start = 70
        var covered = 0
        val seen = mutableListOf<Int>()
        while (covered < sources.size) {
            val next = sources.rotatedWindow(start, minOf(window, sources.size - covered))
            seen += next
            start = (start + next.size) % sources.size
            covered += next.size
        }

        assertEquals(sources.size, seen.size)
        assertEquals(sources.toSet(), seen.toSet())
        assertEquals(seen.size, seen.distinct().size)
    }

    @Test
    fun rotatedWindowWrapsAndNeverOverruns() {
        val sources = listOf("a", "b", "c", "d")

        assertEquals(listOf("c", "d", "a"), sources.rotatedWindow(start = 2, size = 3))
        // A window wider than the list still yields each entry once.
        assertEquals(listOf("b", "c", "d", "a"), sources.rotatedWindow(start = 1, size = 99))
        assertTrue(sources.rotatedWindow(start = 0, size = 0).isEmpty())
        assertTrue(emptyList<String>().rotatedWindow(start = 3, size = 5).isEmpty())
    }

    private fun preview(
        id: String,
        type: String,
        rating: String,
        genres: List<String> = emptyList(),
        releaseInfo: String? = null,
        defaultVideoId: String? = null,
        animeType: String? = null,
        carriesAnimeCatalogueId: Boolean = false,
    ) = MetaPreview(
        id = id,
        type = type,
        name = id,
        imdbRating = rating,
        genres = genres,
        releaseInfo = releaseInfo,
        defaultVideoId = defaultVideoId,
        animeType = animeType,
        carriesAnimeCatalogueId = carriesAnimeCatalogueId,
    )

    private fun section(
        genre: String? = null,
        contentType: String = "movie",
        items: List<MetaPreview>,
    ) = HomeCatalogSection(
        key = "test",
        title = "Test",
        subtitle = "Test",
        addonName = "Test",
        target = CatalogTarget.Addon(
            manifestUrl = "https://example.test/manifest.json",
            contentType = contentType,
            catalogId = "test",
            genre = genre,
        ),
        items = items,
    )

    private fun candidates(
        section: HomeCatalogSection,
        category: RandomPlayCategory,
    ): List<String> = randomPlayCandidates(
        sourceSections = listOf(section),
        category = category,
        allowedGenres = RandomPlayGenres.toSet(),
        minimumImdbRating = 0f,
    ).map(MetaPreview::id)
}
