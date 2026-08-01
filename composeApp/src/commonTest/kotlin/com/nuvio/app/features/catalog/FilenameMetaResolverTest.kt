package com.nuvio.app.features.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilenameMetaResolverTest {

    @Test
    fun detectsReleaseFilenames() {
        listOf(
            "The.Matrix.1999.2160p.UHD.BluRay.x265-GROUP.mkv",
            "Severance.S02E05.1080p.WEB-DL.DDP5.1.H.264-NTb",
            "Dune Part Two 2024 REMUX 2160p HDR",
            "Show.Name.S01E02.mp4",
        ).forEach { name ->
            assertTrue(FilenameMetaResolver.looksLikeReleaseFilename(name), "expected filename: $name")
        }
    }

    @Test
    fun leavesRealTitlesAlone() {
        listOf(
            "The Matrix",
            "Dune: Part Two",
            "Breaking Bad",
            "Mission: Impossible - Dead Reckoning Part One",
            "9-1-1",
        ).forEach { name ->
            assertFalse(FilenameMetaResolver.looksLikeReleaseFilename(name), "expected title: $name")
        }
    }

    @Test
    fun parsesMovieFilename() {
        val query = assertNotNull(
            FilenameMetaResolver.parseFilenameQuery(
                "The.Matrix.1999.2160p.UHD.BluRay.x265-GROUP.mkv",
                catalogType = "movie",
            ),
        )
        assertEquals("The Matrix", query.title)
        assertEquals(1999, query.year)
        assertEquals("movie", query.mediaType)
        assertNull(query.season)
        assertEquals("The Matrix", query.displayName("The Matrix"))
    }

    @Test
    fun parsesEpisodeFilenameAndKeepsCoordinates() {
        val query = assertNotNull(
            FilenameMetaResolver.parseFilenameQuery(
                "Severance.S02E05.1080p.WEB-DL.DDP5.1.H.264-NTb.mkv",
                catalogType = "series",
            ),
        )
        assertEquals("Severance", query.title)
        assertEquals("tv", query.mediaType)
        assertEquals(2, query.season)
        assertEquals(5, query.episode)
        assertEquals("Severance S02E05", query.displayName("Severance"))
    }

    @Test
    fun readsYearFromInFrontOfTheEpisodeMarker() {
        val query = assertNotNull(
            FilenameMetaResolver.parseFilenameQuery(
                "The.Bear.2022.S03E01.1080p.WEB.h264-GROUP",
                catalogType = "series",
            ),
        )
        assertEquals("The Bear", query.title)
        assertEquals(2022, query.year)
    }

    @Test
    fun infersSeriesFromEpisodeMarkerWhenCatalogTypeIsUnhelpful() {
        val query = assertNotNull(
            FilenameMetaResolver.parseFilenameQuery(
                "Andor.S01E04.2160p.WEB-DL.mkv",
                catalogType = "other",
            ),
        )
        assertEquals("tv", query.mediaType)
    }

    @Test
    fun searchesBothEndpointsWhenNothingIndicatesTheType() {
        val query = assertNotNull(
            FilenameMetaResolver.parseFilenameQuery("Arrival.2016.1080p.BluRay.x264", catalogType = "other"),
        )
        assertNull(query.mediaType)
        assertEquals("Arrival", query.title)
        assertEquals(2016, query.year)
    }

    @Test
    fun rejectsNamesThatCleanDownToNothingSearchable() {
        assertNull(FilenameMetaResolver.parseFilenameQuery("1080p.WEB-DL.mkv", catalogType = "movie"))
        assertNull(FilenameMetaResolver.parseFilenameQuery("2160p", catalogType = "movie"))
        // A bare date stamp is not a title.
        assertNull(FilenameMetaResolver.parseFilenameQuery("20250419.1080p.WEB-DL", catalogType = "movie"))
    }

    @Test
    fun keepsANumericSeriesTitle() {
        val query = assertNotNull(
            FilenameMetaResolver.parseFilenameQuery("9-1-1.S01E01.1080p.WEB-DL.x264", catalogType = "series"),
        )
        assertEquals("9 1 1", query.title)
        assertEquals(1, query.episode)
    }

    @Test
    fun stripsTrackerSitePrefix() {
        val query = assertNotNull(
            FilenameMetaResolver.parseFilenameQuery(
                "www.UIndex.org   -   Stuart Fails to Save the Universe S01E01 Spoiler Gary Dies " +
                    "2160p HMAX WEB-DL DDP5 1 Atmos DV HDR HEVC-VARYG",
                catalogType = "other",
            ),
        )
        assertEquals("Stuart Fails to Save the Universe", query.title)
        assertEquals("tv", query.mediaType)
        assertEquals(1, query.season)
        assertEquals(1, query.episode)
    }

    @Test
    fun stripsTrackerSitePrefixFromEpisodeWithoutSxxExx() {
        val query = assertNotNull(
            FilenameMetaResolver.parseFilenameQuery(
                "www.UIndex.org   -   Solo Leveling S01E01 Im Used to It PROPER 1080p FLAC 2 0 AVC " +
                    "HYBRID REMUX-FraMeSToR",
                catalogType = "other",
            ),
        )
        assertEquals("Solo Leveling", query.title)
    }

    @Test
    fun readsSeasonPackWithYearBehindTheSeasonToken() {
        val query = assertNotNull(
            FilenameMetaResolver.parseFilenameQuery("From.S01.2022.WEB-DL.2160p", catalogType = "other"),
        )
        assertEquals("From", query.title)
        assertEquals(2022, query.year)
        assertEquals("tv", query.mediaType)
        assertEquals(1, query.season)
        assertNull(query.episode)
        assertEquals("From S01", query.displayName("From"))
    }

    @Test
    fun readsSeasonPackWithoutAYear() {
        val query = assertNotNull(
            FilenameMetaResolver.parseFilenameQuery(
                "From.S01.2160p.WEB-DL.DDP5.1.x265-TEPES[rartv]",
                catalogType = "other",
            ),
        )
        assertEquals("From", query.title)
        assertNull(query.year)
        assertEquals("tv", query.mediaType)
        assertEquals(1, query.season)
    }

    @Test
    fun leavesAMultiSeasonPackWithoutASeasonNumber() {
        val query = assertNotNull(
            FilenameMetaResolver.parseFilenameQuery("Breaking.Bad.S01-S05.1080p.BluRay.x265", catalogType = "other"),
        )
        assertEquals("Breaking Bad", query.title)
        assertEquals("tv", query.mediaType)
        assertNull(query.season)
        assertEquals("Breaking Bad", query.displayName("Breaking Bad"))
    }

    @Test
    fun offersTheInternationalTitleAsASecondAttempt() {
        val query = assertNotNull(
            FilenameMetaResolver.parseFilenameQuery(
                "Midsommar - Il villaggio dei dannati (2019) [BluRay Rip 2160p HEVC 10bit-HDR " +
                    "ITA-ENG DTS-AC3-SUBS] [M@HD]",
                catalogType = "other",
            ),
        )
        assertEquals(2019, query.year)
        assertEquals("Midsommar", query.alternateTitle)
    }

    @Test
    fun keepsASingleTitleWithoutAnAlternate() {
        val query = assertNotNull(
            FilenameMetaResolver.parseFilenameQuery("Arrival.2016.1080p.BluRay.x264", catalogType = "movie"),
        )
        assertNull(query.alternateTitle)
    }

    @Test
    fun doesNotReadAnApostropheSAsASeason() {
        val query = assertNotNull(
            FilenameMetaResolver.parseFilenameQuery("Ocean's.11.2001.1080p.BluRay.x264", catalogType = "movie"),
        )
        assertEquals("movie", query.mediaType)
        assertNull(query.season)
    }

    @Test
    fun dropsALanguageStampFromTheTitle() {
        val query = assertNotNull(
            FilenameMetaResolver.parseFilenameQuery(
                "Pans.Labyrinth.2006.SPANISH.2160p.BluRay.HEVC.DTS-HD.MA.5.1",
                catalogType = "movie",
            ),
        )
        assertEquals("Pans Labyrinth", query.title)
        assertEquals(2006, query.year)
    }

    @Test
    fun parsesAnEpisodeWithTheYearAfterTheMarker() {
        val query = assertNotNull(
            FilenameMetaResolver.parseFilenameQuery(
                "Widows.Bay.S01E01.2026.2160p.ATVP.WEB-DL.DDP5.1.Atmos.DV.H.265-HHWEB",
                catalogType = "series",
            ),
        )
        assertEquals("Widows Bay", query.title)
        assertEquals(2026, query.year)
        assertEquals(1, query.season)
        assertEquals(1, query.episode)
    }

    @Test
    fun detectsAndParsesAnArchiveStyleFullSeriesDump() {
        val name = "friends-1994-2004-full-series_20250419"
        assertTrue(FilenameMetaResolver.looksLikeReleaseFilename(name))
        val query = assertNotNull(FilenameMetaResolver.parseFilenameQuery(name, catalogType = "other"))
        assertEquals("friends", query.title)
        // The run's start year, not its end — TMDB holds the first air date.
        assertEquals(1994, query.year)
        assertEquals("tv", query.mediaType)
        assertNull(query.season)
    }

    @Test
    fun keepsATitleThatIsOnlyAYear() {
        val query = assertNotNull(
            FilenameMetaResolver.parseFilenameQuery("1917.2019.2160p.UHD.BluRay.x265", catalogType = "movie"),
        )
        assertEquals("1917", query.title)
        assertEquals(2019, query.year)
    }

    @Test
    fun cacheKeyCollapsesEpisodesOfTheSameShow() {
        val first = assertNotNull(
            FilenameMetaResolver.parseFilenameQuery("Severance.S02E05.1080p.WEB-DL.mkv", catalogType = "series"),
        )
        val second = assertNotNull(
            FilenameMetaResolver.parseFilenameQuery("Severance.S02E06.2160p.WEB-DL.mkv", catalogType = "series"),
        )
        assertEquals(first.cacheKey, second.cacheKey)
    }
}
