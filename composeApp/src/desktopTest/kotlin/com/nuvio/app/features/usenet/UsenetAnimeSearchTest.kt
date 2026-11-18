package com.nuvio.app.features.usenet

import com.nuvio.app.features.tmdb.TmdbSeasonEpisodeCount
import org.junit.Assert.*
import org.junit.Test

/** Anime and other-name searches: Kitsu ids, absolute episode numbers and fansub release names. */
class UsenetAnimeSearchTest {
    private val indexer = UsenetIndexer(id = "one", name = "Indexer", apiUrl = "https://indexer.test/api", apiKey = "key")
    private val caps = NewznabCapabilities(tvParams = setOf("q", "tvdbid", "season", "ep"))

    /** Jujutsu Kaisen season 2 episode 5: TVDB S02E05, the show's 29th, Kitsu entry episode 5. */
    private val jjk = UsenetSearchRequest(
        tvdbId = "377543",
        title = "Jujutsu Kaisen",
        otherTitles = listOf("Sorcery Fight"),
        series = true,
        season = 2,
        episode = 5,
        anime = UsenetAnimeNumbering(
            entryEpisode = 5,
            absoluteEpisode = 29,
            entryTitles = listOf("Jujutsu Kaisen 2nd Season", "Jujutsu Kaisen Season 2"),
            otherCoordinates = listOf(1 to 29),
        ),
    )

    private fun passes(name: String, request: UsenetSearchRequest = jjk, idSearch: Boolean = false) =
        NewznabProtocol.matches(UsenetRelease(name, "https://indexer.test/$name"), request, idSearch)

    @Test fun `anime entry ids number the episode without a season`() {
        assertEquals(null to 5, episodeNumbers("kitsu:42765:5"))
        assertEquals(3 to 5, episodeNumbers("kitsu:42765:3:5"))
        assertEquals(null to null, episodeNumbers("kitsu:42765"))
        assertEquals(null to null, episodeNumbers("tvdb:377543:2"))
        assertEquals(2 to 5, episodeNumbers("tvdb:377543:2:5"))
        assertTrue(builtInUsenetCanSearch("anime", "kitsu:42765:5"))
        assertTrue(builtInUsenetCanSearch("series", "mal:51009:5"))
        assertFalse(builtInUsenetCanSearch("series", "kitsu:42765"))
    }

    @Test fun `an absolute search asks Title 05 by text alone`() {
        val url = NewznabProtocol.searchUrl(indexer, jjk.copy(absoluteQuery = 29), caps, byTitle = true)!!
        assertEquals("Jujutsu Kaisen 29", url.queryParameter("q"))
        assertNull(url.queryParameter("season"))
        assertNull(url.queryParameter("ep"))
        assertNull(url.queryParameter("tvdbid"))
        assertEquals("Jujutsu Kaisen 2nd Season 05",
            NewznabProtocol.searchUrl(indexer, jjk.copy(queryTitle = "Jujutsu Kaisen 2nd Season", absoluteQuery = 5), caps, byTitle = true)!!
                .queryParameter("q"))
        assertNull(NewznabProtocol.searchUrl(indexer, jjk.copy(absoluteQuery = 29), caps, byTitle = true, wholeSeason = true))
        assertNull(NewznabProtocol.searchUrl(indexer, jjk.copy(absoluteQuery = 29), caps))
    }

    @Test fun `anime is asked under its other names and numbers`() {
        val queries = NewznabProtocol.titleRequests(jjk).map {
            NewznabProtocol.searchUrl(indexer, it, caps, byTitle = true)?.queryParameter("q")
        }
        assertEquals(listOf(
            "Jujutsu Kaisen S02E05",
            "Jujutsu Kaisen 29",
            "Sorcery Fight 29",
            "Sorcery Fight S02E05",
            "Jujutsu Kaisen 2nd Season 05",
            "Jujutsu Kaisen Season 2 05",
        ), queries)
        assertEquals(emptyList<UsenetSearchRequest>(), NewznabProtocol.fallbackTitleRequests(jjk))
    }

    @Test fun `other titles are asked when the title found nothing`() {
        val movie = UsenetSearchRequest(title = "Spirited Away", originalTitle = "千と千尋の神隠し",
            otherTitles = listOf("Sen to Chihiro no Kamikakushi", "spirited away"))
        assertEquals(listOf(movie), NewznabProtocol.titleRequests(movie))
        assertEquals(listOf("Sen to Chihiro no Kamikakushi"), NewznabProtocol.fallbackTitleRequests(movie).map { it.queryTitle })
        // The name it was asked under counts when its results are matched.
        val asked = NewznabProtocol.fallbackTitleRequests(movie).single()
        assertTrue(NewznabProtocol.matches(UsenetRelease("Sen.to.Chihiro.no.Kamikakushi.2001.1080p.BluRay", "u"), asked, idSearch = false))
    }

    @Test fun `fansub names number by the show or by the entry`() {
        assertTrue(passes("[SubsPlease] Jujutsu Kaisen - 29 (1080p) [A1B2C3D4].mkv"))
        assertTrue(passes("[SubsPlease] Jujutsu Kaisen - 29v2 (1080p).mkv"))
        assertFalse(passes("[SubsPlease] Jujutsu Kaisen - 05 (1080p) [A1B2C3D4].mkv"))
        assertTrue(passes("[Erai-raws] Jujutsu Kaisen 2nd Season - 05 [1080p][Multiple Subtitle]"))
        assertFalse(passes("[Erai-raws] Jujutsu Kaisen 2nd Season - 06 [1080p]"))
        assertTrue(passes("[Judas] Jujutsu Kaisen S2 - 05 [1080p][HEVC x265 10bit]"))
        assertTrue(passes("[Judas] Jujutsu Kaisen Season 2 - 05 [1080p]"))
        assertFalse(passes("[Judas] Jujutsu Kaisen S1 - 05 [1080p]"))
        assertTrue(passes("[Sorcery] Sorcery Fight - 29 [720p]"))
    }

    @Test fun `scene names number by season either way`() {
        assertTrue(passes("Jujutsu.Kaisen.S02E05.1080p.CR.WEB-DL.AAC2.0.H.264-VARYG"))
        assertTrue(passes("Jujutsu.Kaisen.S01E29.1080p.WEB.H264-SKYANIME"))
        assertFalse(passes("Jujutsu.Kaisen.S01E05.1080p.WEB.H264-SKYANIME"))
        assertTrue(passes("Jujutsu.Kaisen.S02.1080p.BluRay.x264-GROUP"))
    }

    @Test fun `batches hold the episode`() {
        assertTrue(passes("[Group] Jujutsu Kaisen 2nd Season (01-23) [BD 1080p]"))
        assertFalse(passes("[Group] Jujutsu Kaisen 2nd Season (06-23) [BD 1080p]"))
        assertTrue(passes("[Group] Jujutsu Kaisen (25-47) [Batch]"))
    }

    @Test fun `another show containing the name is not the show`() {
        val naruto = UsenetSearchRequest(title = "Naruto", series = true, season = 1, episode = 5,
            anime = UsenetAnimeNumbering(entryEpisode = 5, absoluteEpisode = 5, entryTitles = listOf("Naruto")))
        assertTrue(passes("[Group] Naruto - 005 [480p]", naruto))
        assertFalse(passes("[SubsPlease] Boruto - Naruto Next Generations - 05 (1080p)", naruto))
        assertFalse(passes("Boruto.Naruto.Next.Generations.S01E05.1080p", naruto))
        // An ID search vouches for the title; its fansub number still has to be the episode's.
        assertTrue(passes("[ASW] Some Romaji Name - 29 [1080p HEVC]", idSearch = true))
        assertFalse(passes("[ASW] Some Romaji Name - 30 [1080p HEVC]", idSearch = true))
    }

    @Test fun `episodes are counted from the start of the show`() {
        val counts = listOf(TmdbSeasonEpisodeCount(1, 24), TmdbSeasonEpisodeCount(2, 23))
        assertEquals(29, absoluteOf(2, 5, counts))
        assertEquals(5, absoluteOf(1, 5, counts))
        // TMDB numbers One Piece on within its seasons: a number beyond the season is already the count.
        assertEquals(150, absoluteOf(2, 150, counts))
        assertEquals(2 to 5, seasonOf(29, counts))
        assertNull(seasonOf(60, counts))
        assertTrue(animeEntryStartsShow(null, null, null, null))
        assertTrue(animeEntryStartsShow(1, null, 0, null))
        assertFalse(animeEntryStartsShow(2, 2, 0, 0))
        assertFalse(animeEntryStartsShow(1, null, 24, null))
        assertEquals(1089, animeAbsoluteEpisode(native = true, startsShow = true, entryEpisode = 1089, coordinates = null, counts = null))
        assertEquals(29, animeAbsoluteEpisode(native = true, startsShow = false, entryEpisode = 5, coordinates = 2 to 5, counts = counts))
        assertNull(animeAbsoluteEpisode(native = true, startsShow = false, entryEpisode = 5, coordinates = 2 to 5, counts = null))
    }
}
