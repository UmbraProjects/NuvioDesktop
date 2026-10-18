package com.nuvio.app.features.mdblist

import com.nuvio.app.features.mdblist.MdbListRatingsFreshness.MoviePhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MdbListRatingsFreshnessTest {
    private val day = 24L * 60L * 60L * 1000L
    private val now = MdbListRatingsFreshness.parseDateMs("2026-10-18")!! + 12L * 60L * 60L * 1000L

    private fun date(value: String) = MdbListRatingsFreshness.parseDateMs(value)!!

    /** The TTL before jitter: jitter only ever adds, by at most [MdbListRatingsFreshness.JITTER_FRACTION]. */
    private fun assertTtlDays(expectedDays: Double, facts: MdbListReleaseFacts?, found: Boolean = true) {
        val ttl = MdbListRatingsFreshness.ttlMs(facts, found, now, cacheKey = "k")
        val base = expectedDays * day
        val jitter = 1.0 + MdbListRatingsFreshness.JITTER_FRACTION * MdbListRatingsFreshness.jitterUnit("k")
        assertEquals(base * jitter, ttl.toDouble(), absoluteTolerance = 2.0)
    }

    private fun movie(released: String?, digital: String? = null, status: String? = "Released") = MdbListReleaseFacts(
        isShow = false,
        status = status,
        releasedMs = released?.let(::date),
        digitalMs = digital?.let(::date),
    )

    @Test
    fun `parses plain and timestamped dates`() {
        assertEquals(date("2026-10-19"), MdbListRatingsFreshness.parseDateMs("2026-10-19T00:00:00.000Z"))
        assertEquals(null, MdbListRatingsFreshness.parseDateMs("soon"))
        assertEquals(null, MdbListRatingsFreshness.parseDateMs(null))
    }

    @Test
    fun `film phases follow the release`() {
        assertEquals(MoviePhase.Unreleased, MdbListRatingsFreshness.moviePhase(movie("2026-12-16", status = "post production"), now))
        assertEquals(MoviePhase.Unreleased, MdbListRatingsFreshness.moviePhase(movie(null, status = "planned"), now))
        assertEquals(MoviePhase.CinemaFirstWeek, MdbListRatingsFreshness.moviePhase(movie("2026-10-15"), now))
        assertEquals(MoviePhase.Cinema, MdbListRatingsFreshness.moviePhase(movie("2026-07-15", "2026-11-17"), now))
        assertEquals(MoviePhase.EarlyStreaming, MdbListRatingsFreshness.moviePhase(movie("2026-07-29", "2026-10-06"), now))
        assertEquals(MoviePhase.Streaming, MdbListRatingsFreshness.moviePhase(movie("2026-05-01", "2026-08-01"), now))
        assertEquals(MoviePhase.Settled, MdbListRatingsFreshness.moviePhase(movie("2024-02-27", "2024-04-15"), now))
        assertEquals(MoviePhase.Settled, MdbListRatingsFreshness.moviePhase(movie("1994-09-23"), now))
        assertEquals(MoviePhase.Settled, MdbListRatingsFreshness.moviePhase(movie(null), now))
    }

    @Test
    fun `unreleased film sleeps until its cinema date, capped`() {
        // Released 2026-10-25: 6.5 days away, plus a day for the first ratings.
        assertTtlDays(7.5, movie("2026-10-25", status = "post production"))
        assertTtlDays(30.0, movie("2026-12-16", status = "post production"))
        assertTtlDays(30.0, movie(null, status = "planned"))
    }

    @Test
    fun `cinema run waits for the digital date`() {
        assertTtlDays(2.0, movie("2026-10-15"))
        // Digital 2026-11-17: 29.5 days away, +1 day, capped at 30.
        assertTtlDays(30.0, movie("2026-07-15", "2026-11-17"))
        assertTtlDays(8.5, movie("2026-09-01", "2026-10-26"))
        assertTtlDays(14.0, movie("2026-09-01"))
    }

    @Test
    fun `streaming and settled films`() {
        assertTtlDays(3.0, movie("2026-07-29", "2026-10-06"))
        assertTtlDays(14.0, movie("2026-05-01", "2026-08-01"))
        assertTtlDays(60.0, movie("2024-02-27", "2024-04-15"))
    }

    private fun show(
        last: String? = null,
        lastSeason: Int? = null,
        next: String? = null,
        nextSeason: Int? = null,
        released: String? = "2020-01-01",
        status: String = "Returning Series",
    ) = MdbListReleaseFacts(
        isShow = true,
        status = status,
        releasedMs = released?.let(::date),
        lastEpisodeMs = last?.let(::date),
        lastEpisodeSeason = lastSeason,
        nextEpisodeMs = next?.let(::date),
        nextEpisodeSeason = nextSeason,
    )

    @Test
    fun `airing shows refresh fastest in their first season`() {
        assertTtlDays(3.0, show(last = "2026-10-12", lastSeason = 1, next = "2026-10-26", nextSeason = 1, released = "2026-09-28"))
        assertTtlDays(7.0, show(last = "2026-10-15", lastSeason = 29, next = "2026-10-29", nextSeason = 29))
        // Premiere of season 1 is a week out: airing window, first season.
        assertTtlDays(3.0, show(next = "2026-10-25", nextSeason = 1, released = "2026-10-25", status = "In Production"))
    }

    @Test
    fun `idle shows sit for a month unless an episode is dated sooner`() {
        assertTtlDays(30.0, show(last = "2013-09-29", lastSeason = 5, status = "Ended"))
        assertTtlDays(30.0, show(last = "2025-03-20", lastSeason = 2))
        // Season 3 premieres in 25 days: outside the airing window, so wake for it.
        assertTtlDays(25.5, show(last = "2025-03-20", lastSeason = 2, next = "2026-11-12", nextSeason = 3))
        assertTtlDays(30.0, show(released = "2027-02-01", status = "Planned"))
    }

    @Test
    fun `not found and missing facts keep a week`() {
        assertTtlDays(7.0, null)
        assertTtlDays(7.0, movie("2024-02-27", "2024-04-15"), found = false)
    }

    @Test
    fun `presets scale every interval and custom overrides one`() {
        val streaming = movie("2026-07-29", "2026-10-06")
        val faster = MdbListSettings(refreshPreset = MdbListRefreshPreset.Faster)
        assertEquals(1.5, faster.refreshDays(MdbListRefreshTier.MovieEarlyStreaming))
        val fasterTtl = MdbListRatingsFreshness.ttlMs(streaming, true, now, "k", faster::refreshDays)
        val standardTtl = MdbListRatingsFreshness.ttlMs(streaming, true, now, "k")
        assertEquals(standardTtl / 2.0, fasterTtl.toDouble(), absoluteTolerance = 2.0)

        val custom = MdbListSettings(
            refreshPreset = MdbListRefreshPreset.Custom,
            customRefreshDays = mapOf(MdbListRefreshTier.MovieEarlyStreaming to 1.0),
        )
        assertEquals(1.0, custom.refreshDays(MdbListRefreshTier.MovieEarlyStreaming))
        assertEquals(60.0, custom.refreshDays(MdbListRefreshTier.MovieSettled))
        // A custom value set while a preset is active doesn't apply until Custom is chosen.
        assertEquals(3.0, custom.copy(refreshPreset = MdbListRefreshPreset.Standard).refreshDays(MdbListRefreshTier.MovieEarlyStreaming))
    }

    @Test
    fun `unreleased cap follows its interval`() {
        val days = { tier: MdbListRefreshTier -> if (tier == MdbListRefreshTier.MovieUnreleased) 5.0 else tier.defaultDays }
        val ttl = MdbListRatingsFreshness.ttlMs(movie("2026-12-16", status = "post production"), true, now, "k", days)
        assertTrue(ttl < 6L * day && ttl >= 5L * day)
    }

    @Test
    fun `custom intervals round-trip through storage text`() {
        val days = mapOf(MdbListRefreshTier.ShowAiring to 2.0, MdbListRefreshTier.NotFound to 0.5)
        assertEquals(days, MdbListRefreshIntervalsCodec.decode(MdbListRefreshIntervalsCodec.encode(days)))
        assertEquals(
            mapOf(MdbListRefreshTier.ShowIdle to 10.0),
            MdbListRefreshIntervalsCodec.decode("show_idle=10.0,bogus=3,show_airing=-1,movie_settled=x"),
        )
        assertEquals(MdbListRefreshPreset.Auto, MdbListRefreshPreset.fromStorage("nonsense"))
        assertEquals(MdbListRefreshPreset.Auto, MdbListRefreshPreset.fromStorage(null))
    }

    @Test
    fun `jitter spreads titles and is stable per title`() {
        val keys = (1..200).map { "v4:imdb:movie:tt$it" }
        val units = keys.map(MdbListRatingsFreshness::jitterUnit)
        assertTrue(units.all { it >= 0.0 && it < 1.0 })
        assertTrue(units.distinct().size > 190)
        assertTrue(units.count { it < 0.5 } in 60..140)
        assertEquals(units, keys.map(MdbListRatingsFreshness::jitterUnit))
    }

    @Test
    fun `extras refresh with ratings while a film waits for its digital date`() {
        assertEquals(0L, MdbListRatingsFreshness.extrasTtlMs(movie("2026-10-01"), true, now, "k"))
        assertEquals(0L, MdbListRatingsFreshness.extrasTtlMs(movie("2026-10-15"), true, now, "k"))
        assertEquals(0L, MdbListRatingsFreshness.extrasTtlMs(null, true, now, "k"))
        assertEquals(0L, MdbListRatingsFreshness.extrasTtlMs(movie("2024-02-27", "2024-04-15"), false, now, "k"))
    }

    @Test
    fun `extras are weekly near release and quarterly after`() {
        val jitter = 1.0 + MdbListRatingsFreshness.JITTER_FRACTION * MdbListRatingsFreshness.jitterUnit("k")
        val recent = MdbListRatingsFreshness.extrasTtlMs(movie("2026-09-10", "2026-10-10"), true, now, "k")
        assertEquals(7.0 * day * jitter, recent.toDouble(), absoluteTolerance = 2.0)
        val old = MdbListRatingsFreshness.extrasTtlMs(movie("2024-02-27", "2024-04-15"), true, now, "k")
        assertEquals(90.0 * day * jitter, old.toDouble(), absoluteTolerance = 2.0)
        // Unreleased, release still months off: nothing is settling yet.
        val unreleased = MdbListRatingsFreshness.extrasTtlMs(movie("2027-03-01", status = "post production"), true, now, "k")
        assertEquals(90.0 * day * jitter, unreleased.toDouble(), absoluteTolerance = 2.0)
    }

    @Test
    fun `bulk facts keep a film's digital date`() {
        val old = movie("2026-07-15", "2026-11-17")
        val merged = MdbListRatingsFreshness.mergeBulkFacts(old, false, "Released", date("2026-07-15"), emptyList(), now)
        assertEquals(date("2026-11-17"), merged.digitalMs)
        assertEquals("Released", merged.status)
    }

    @Test
    fun `bulk seasons estimate whether a show is airing`() {
        // Season 29 started 2026-09-16 with 6 episodes: weekly, the last lands 2026-10-21 → estimate
        // is "now", inside the airing window.
        val airing = MdbListRatingsFreshness.mergeBulkFacts(
            old = show(last = "2025-03-20", lastSeason = 28),
            isShow = true,
            status = "Returning Series",
            releasedMs = date("1997-08-13"),
            seasons = listOf(
                MdbListSeasonFact(0, date("1999-01-01"), 40),
                MdbListSeasonFact(28, date("2025-02-01"), 8),
                MdbListSeasonFact(29, date("2026-09-16"), 6),
            ),
            nowMs = now,
        )
        assertEquals(now, airing.lastEpisodeMs)
        assertEquals(29, airing.lastEpisodeSeason)
        assertTtlDays(7.0, airing)

        // Long finished season, next one dated: idle, waking for the premiere.
        val idle = MdbListRatingsFreshness.mergeBulkFacts(
            old = null,
            isShow = true,
            status = "Returning Series",
            releasedMs = date("2022-02-17"),
            seasons = listOf(MdbListSeasonFact(2, date("2025-01-17"), 10), MdbListSeasonFact(3, date("2026-11-12"), 10)),
            nowMs = now,
        )
        assertEquals(date("2025-01-17") + 9 * 7 * day, idle.lastEpisodeMs)
        assertEquals(date("2026-11-12"), idle.nextEpisodeMs)
        assertEquals(3, idle.nextEpisodeSeason)
        assertTtlDays(25.5, idle)

        // A first season that started last week reads as first-season airing.
        val fresh = MdbListRatingsFreshness.mergeBulkFacts(
            null, true, "Returning Series", date("2026-10-08"),
            listOf(MdbListSeasonFact(1, date("2026-10-08"), 8)), now,
        )
        assertTtlDays(3.0, fresh)
    }

    @Test
    fun `parses release facts from a real mdblist show answer`() {
        val payload = """
            {"title":"South Park","type":"show","status":"Returning Series","released":"1997-08-13",
             "released_digital":null,"ratings":[{"source":"imdb","value":8.7}],
             "last_episode":{"season":29,"episode":3,"air_date":"2026-10-15T02:00:00.000Z"},
             "next_episode":{"season":29,"episode":4,"air_date":"2026-10-29T02:00:00.000Z"}}
        """.trimIndent()
        val facts = MdbListMetadataService.parseEnrichment(payload, "show").releaseFacts!!
        assertEquals(true, facts.isShow)
        assertEquals(date("2026-10-15"), facts.lastEpisodeMs)
        assertEquals(29, facts.nextEpisodeSeason)
        assertEquals(null, facts.digitalMs)
    }
}
