package com.nuvio.app.features.trailer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrailerQualityTest {
    private fun hit(
        id: String,
        title: String,
        channel: String = "Some Channel",
        verified: Boolean = false,
        seconds: Int = 150,
        views: Long = 1_000,
    ) = YouTubeSearchHit(id.padEnd(11, 'x'), title, channel, verified, seconds, views)

    private fun option(key: String, height: Int, order: Int) =
        TrailerOption(key, TrailerPlaybackSource(videoUrl = "u-$key", heightPx = height), order)

    @Test
    fun theSharpestUploadWins() {
        val best = pickBestTrailer(listOf(option("listed", 720, 0), option("found", 2160, 100)))
        assertEquals("found", best?.key)
    }

    @Test
    fun equalQualityKeepsTheListedTrailer() {
        // 1072p and 1080p are the same rung, so the search result must not displace the listed one.
        val best = pickBestTrailer(listOf(option("found", 1080, 100), option("listed", 1072, 0)))
        assertEquals("listed", best?.key)
        assertNull(pickBestTrailer(emptyList()))
    }

    @Test
    fun anUploadThatCannotPlayLosesToOneThatCan() {
        val blocked = TrailerOption("blocked", TrailerPlaybackSource(videoUrl = "u", heightPx = 1080, reliable = false), 0)
        val playable = option("playable", 480, 1)
        assertEquals("playable", pickBestTrailer(listOf(blocked, playable))?.key)
        // With nothing playable the blocked one is still better than no trailer at all.
        assertEquals("blocked", pickBestTrailer(listOf(blocked))?.key)
    }

    @Test
    fun unknownHeightRanksLast() {
        assertEquals("known", pickBestTrailer(listOf(option("unknown", 0, 0), option("known", 360, 1)))?.key)
    }

    @Test
    fun qualityClassesFollowTheLadder() {
        assertEquals(2160, trailerQualityClass(2160))
        assertEquals(1440, trailerQualityClass(1440))
        assertEquals(1080, trailerQualityClass(1080))
        assertEquals(720, trailerQualityClass(798))
        assertEquals(480, trailerQualityClass(544))
        assertEquals(240, trailerQualityClass(240))
        assertEquals(0, trailerQualityClass(0))
    }

    @Test
    fun searchQueryNamesTheFilmYearAndTrailer() {
        assertEquals("Dune 2021 official trailer", trailerSearchQuery(" Dune ", "2021"))
        assertEquals("Dune official trailer", trailerSearchQuery("Dune", null))
    }

    @Test
    fun keepsTheStudiosTrailerAndDropsLookAlikes() {
        val hits = listOf(
            hit("a", "Everything Everywhere All At Once | Official Trailer HD | A24", channel = "A24", verified = true, views = 9_000),
            hit("b", "Everything Everywhere All at Once Trailer REACTION", views = 500_000),
            hit("c", "Everything Everywhere All at Once - Ending Explained", views = 900_000),
            hit("d", "Everything Everywhere All at Once (2022) Official Trailer", views = 20_000),
            hit("e", "Everything Everywhere All at Once Official Trailer", seconds = 3_600),
            hit("f", "Everything Everywhere trailer"),
            hit("g", "Everything Everywhere All at Once Trailer ASL", views = 100),
        )
        val kept = filterTrailerSearchHits(hits, "Everything Everywhere All at Once", "2022")
        assertEquals(listOf("axxxxxxxxxx", "dxxxxxxxxxx"), kept.map { it.videoId })
    }

    @Test
    fun aSequelIsNotMistakenForTheFilm() {
        val hits = listOf(
            hit("a", "Dune: Part Two | Official Trailer", channel = "Warner Bros. Pictures", verified = true),
            hit("b", "Dune (2021) Official Trailer", channel = "Warner Bros. Pictures", verified = true),
            hit("c", "DUNE Official Trailer 2", channel = "Warner Bros. Pictures", verified = true),
        )
        val kept = filterTrailerSearchHits(hits, "Dune", "2021")
        assertEquals(listOf("bxxxxxxxxxx", "cxxxxxxxxxx").toSet(), kept.map { it.videoId }.toSet())
    }

    @Test
    fun skipsVideosAlreadyTriedAndHonoursTheLimit() {
        val hits = (1..6).map { hit("v$it", "Heat Official Trailer", views = it * 10L) }
        val kept = filterTrailerSearchHits(hits, "Heat", excludeKeys = setOf(hits[5].videoId), limit = 2)
        assertEquals(listOf(hits[4].videoId, hits[3].videoId), kept.map { it.videoId })
    }

    @Test
    fun parsesSearchResultsWhereverTheyAreNested() {
        val body = """
            {"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[
              {"itemSectionRenderer":{"contents":[
                {"videoRenderer":{"videoId":"abcdefghijk",
                  "title":{"runs":[{"text":"Heat "},{"text":"Official Trailer"}]},
                  "ownerText":{"runs":[{"text":"Warner Bros."}]},
                  "ownerBadges":[{"metadataBadgeRenderer":{"style":"BADGE_STYLE_TYPE_VERIFIED"}}],
                  "lengthText":{"simpleText":"2:35"},
                  "viewCountText":{"simpleText":"12,345,678 views"}}},
                {"shelfRenderer":{"content":{"videoRenderer":{"videoId":"lmnopqrstuv","title":{"simpleText":"Other"}}}}},
                {"channelRenderer":{"channelId":"x"}}
              ]}}
            ]}}}}}
        """.trimIndent()
        val hits = parseYouTubeSearchHits(body)
        assertEquals(listOf("abcdefghijk", "lmnopqrstuv"), hits.map { it.videoId })
        val first = hits.first()
        assertEquals("Heat Official Trailer", first.title)
        assertEquals("Warner Bros.", first.channel)
        assertEquals(true, first.verified)
        assertEquals(155, first.durationSeconds)
        assertEquals(12_345_678L, first.views)
        assertEquals(emptyList(), parseYouTubeSearchHits("not json"))
    }

    @Test
    fun parsesDurationsAndViewCounts() {
        assertEquals(155, parseDurationSeconds("2:35"))
        assertEquals(3723, parseDurationSeconds("1:02:03"))
        assertEquals(0, parseDurationSeconds(""))
        assertEquals(0, parseDurationSeconds("LIVE"))
        assertEquals(1_234L, parseViewCount("1,234 views"))
        assertEquals(0L, parseViewCount("No views"))
    }
}
