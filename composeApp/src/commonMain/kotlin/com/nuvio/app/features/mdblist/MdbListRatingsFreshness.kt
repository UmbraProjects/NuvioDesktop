package com.nuvio.app.features.mdblist

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * How long one MDBList title answer may stand, from where the title is in its release.
 *
 * A flat week spent the daily quota evenly on titles whose ratings were not moving (a film on disc
 * for years) and too little on the ones that were (a show's first season, a film's first streaming
 * weeks). The answer itself carries what this needs (`status`, the cinema and digital dates, and a
 * show's last/next episode), so no extra call decides it.
 *
 * Films: unreleased → until the cinema date. First cinema week, while the first ratings settle.
 * Rest of the cinema run → until the digital date, since nothing can be streamed before it. First
 * four streaming weeks, then the rest of streaming. Past [MOVIE_SETTLED_AFTER_DIGITAL_DAYS] (by when
 * the disc is normally out; MDBList has no physical date) → settled.
 *
 * Shows: first season airing, a later season airing, off-season or ended. A known premiere or next
 * episode inside the idle interval moves the expiry up to it.
 *
 * How long each phase lasts is [MdbListRefreshTier]: defaults for a free key, user-settable on the
 * MDBList settings page. Every TTL gets a per-title jitter of up to [JITTER_FRACTION] on top, so a
 * browse that filled the cache in one evening doesn't expire together on one later day: MDBList's
 * limit is daily.
 */
internal object MdbListRatingsFreshness {
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    const val MOVIE_FIRST_CINEMA_WEEK_DAYS = 7L
    const val MOVIE_EARLY_STREAMING_DAYS = 28L
    const val MOVIE_SETTLED_AFTER_DIGITAL_DAYS = 120L
    /** A film this long past its cinema date with no digital date is old, not waiting for one. */
    const val MOVIE_NO_DIGITAL_SETTLED_DAYS = 365L

    /** An episode this recent, or this close, means the show is airing. */
    const val SHOW_AIRING_WINDOW_DAYS = 21L

    /** Floor, so a date just ahead never asks again within hours. */
    private const val MIN_TTL_MS = 12L * 60L * 60L * 1000L
    const val JITTER_FRACTION = 0.2

    /** The defaults: what a free key is tuned for. */
    val DEFAULT_DAYS: (MdbListRefreshTier) -> Double = MdbListRefreshTier::defaultDays

    fun ttlMs(
        facts: MdbListReleaseFacts?,
        found: Boolean,
        nowMs: Long,
        cacheKey: String,
        days: (MdbListRefreshTier) -> Double = DEFAULT_DAYS,
    ): Long {
        val ms = { tier: MdbListRefreshTier -> (days(tier) * DAY_MS).toLong() }
        val base = if (!found || facts == null) {
            ms(MdbListRefreshTier.NotFound)
        } else if (facts.isShow) {
            showTtlMs(facts, nowMs, ms)
        } else {
            movieTtlMs(facts, nowMs, ms)
        }
        val clamped = base.coerceAtLeast(MIN_TTL_MS)
        return clamped + (clamped * JITTER_FRACTION * jitterUnit(cacheKey)).toLong()
    }

    /** What phase a film is in, for the log and tests. */
    internal enum class MoviePhase { Unreleased, CinemaFirstWeek, Cinema, EarlyStreaming, Streaming, Settled }

    internal fun moviePhase(facts: MdbListReleaseFacts, nowMs: Long): MoviePhase {
        val cinema = facts.releasedMs
        val digital = facts.digitalMs
        if (digital != null && digital <= nowMs) {
            val sinceDigital = nowMs - digital
            return when {
                sinceDigital < MOVIE_EARLY_STREAMING_DAYS * DAY_MS -> MoviePhase.EarlyStreaming
                sinceDigital < MOVIE_SETTLED_AFTER_DIGITAL_DAYS * DAY_MS -> MoviePhase.Streaming
                else -> MoviePhase.Settled
            }
        }
        if (cinema == null || cinema > nowMs) {
            // Old films MDBList has no dates for at all are released, not upcoming.
            return if (cinema == null && facts.isReleasedStatus) MoviePhase.Settled else MoviePhase.Unreleased
        }
        val sinceCinema = nowMs - cinema
        return when {
            sinceCinema < MOVIE_FIRST_CINEMA_WEEK_DAYS * DAY_MS -> MoviePhase.CinemaFirstWeek
            digital == null && sinceCinema >= MOVIE_NO_DIGITAL_SETTLED_DAYS * DAY_MS -> MoviePhase.Settled
            else -> MoviePhase.Cinema
        }
    }

    private fun movieTtlMs(facts: MdbListReleaseFacts, nowMs: Long, ms: (MdbListRefreshTier) -> Long): Long =
        when (moviePhase(facts, nowMs)) {
            MoviePhase.Unreleased -> untilDate(facts.releasedMs, nowMs, ms(MdbListRefreshTier.MovieUnreleased))
            MoviePhase.CinemaFirstWeek -> ms(MdbListRefreshTier.MovieCinemaFirstWeek)
            MoviePhase.Cinema -> facts.digitalMs
                ?.let { untilDate(it, nowMs, ms(MdbListRefreshTier.MovieCinemaAwaitingStreaming)) }
                ?: ms(MdbListRefreshTier.MovieCinemaNoStreamingDate)
            MoviePhase.EarlyStreaming -> ms(MdbListRefreshTier.MovieEarlyStreaming)
            MoviePhase.Streaming -> ms(MdbListRefreshTier.MovieStreaming)
            MoviePhase.Settled -> ms(MdbListRefreshTier.MovieSettled)
        }

    private fun showTtlMs(facts: MdbListReleaseFacts, nowMs: Long, ms: (MdbListRefreshTier) -> Long): Long {
        val window = SHOW_AIRING_WINDOW_DAYS * DAY_MS
        val next = facts.nextEpisodeMs?.takeIf { it > nowMs }
        val last = facts.lastEpisodeMs?.takeIf { it <= nowMs }
        val airing = (last != null && nowMs - last < window) || (next != null && next - nowMs < window)
        if (airing) {
            val season = (if (last != null && nowMs - last < window) facts.lastEpisodeSeason else null)
                ?: facts.nextEpisodeSeason
            return ms(if (season == 1) MdbListRefreshTier.ShowFirstSeason else MdbListRefreshTier.ShowAiring)
        }
        // Off-season, ended, or not yet premiered: wake for the next episode (or the premiere) if
        // one is dated inside the idle interval.
        val upcoming = next ?: facts.releasedMs?.takeIf { it > nowMs }
        return untilDate(upcoming, nowMs, ms(MdbListRefreshTier.ShowIdle))
    }

    /** Released (or first aired) this recently: RT certification and the like are still settling. */
    const val EXTRAS_RECENT_RELEASE_DAYS = 60L
    const val EXTRAS_RECENT_TTL_DAYS = 7L
    const val EXTRAS_SETTLED_TTL_DAYS = 90L

    /**
     * How long the extras (keywords, digital date, next/last episode) stand; only the single lookup
     * carries them, while ratings refresh in bulk. 0 means "with every ratings refresh": a title
     * with nothing found yet, and a film in cinemas with no digital date known, since that date is
     * what moves it to Streaming.
     */
    fun extrasTtlMs(facts: MdbListReleaseFacts?, found: Boolean, fetchedAtMs: Long, cacheKey: String): Long {
        if (!found || facts == null) return 0L
        if (!facts.isShow && facts.digitalMs == null) {
            val phase = moviePhase(facts, fetchedAtMs)
            if (phase == MoviePhase.CinemaFirstWeek || phase == MoviePhase.Cinema) return 0L
        }
        val released = facts.releasedMs
        val recent = released != null &&
            kotlin.math.abs(fetchedAtMs - released) < EXTRAS_RECENT_RELEASE_DAYS * DAY_MS
        val base = (if (recent) EXTRAS_RECENT_TTL_DAYS else EXTRAS_SETTLED_TTL_DAYS) * DAY_MS
        return base + (base * JITTER_FRACTION * jitterUnit(cacheKey)).toLong()
    }

    /**
     * [old] (from the last single lookup) updated with what a bulk answer carries: `status` and
     * `released` as given; for a show, the episode dates estimated from its seasons, since the bulk
     * answer has no `next_episode`/`last_episode` and no aired counts. A season's episodes are
     * assumed weekly from its start, so a season-wide drop reads as airing a little longer than it
     * is: a few extra refreshes, never a missed one. The digital date is kept from [old].
     */
    fun mergeBulkFacts(
        old: MdbListReleaseFacts?,
        isShow: Boolean,
        status: String?,
        releasedMs: Long?,
        seasons: List<MdbListSeasonFact>,
        nowMs: Long,
    ): MdbListReleaseFacts {
        val base = (old ?: MdbListReleaseFacts(isShow = isShow)).copy(
            isShow = isShow,
            status = status ?: old?.status,
            releasedMs = releasedMs ?: old?.releasedMs,
        )
        if (!isShow) return base
        val numbered = seasons.filter { it.number > 0 && it.airDateMs != null }
        val current = numbered.filter { it.airDateMs!! <= nowMs }.maxByOrNull { it.airDateMs!! }
        val upcoming = numbered.filter { it.airDateMs!! > nowMs }.minByOrNull { it.airDateMs!! }
        val estimatedLast = current?.let { season ->
            val weeks = (season.episodeCount ?: 1).coerceAtLeast(1) - 1
            minOf(nowMs, season.airDateMs!! + weeks * 7L * DAY_MS)
        }
        val keptLast = old?.lastEpisodeMs?.takeIf { it <= nowMs }
        val useEstimate = estimatedLast != null && (keptLast == null || estimatedLast >= keptLast)
        val keptNext = old?.nextEpisodeMs?.takeIf { it > nowMs }
        val useUpcoming = upcoming != null && (keptNext == null || upcoming.airDateMs!! < keptNext)
        return base.copy(
            lastEpisodeMs = if (useEstimate) estimatedLast else keptLast,
            lastEpisodeSeason = if (useEstimate) current?.number else old?.lastEpisodeSeason,
            nextEpisodeMs = if (useUpcoming) upcoming?.airDateMs else keptNext,
            nextEpisodeSeason = if (useUpcoming) upcoming?.number else old?.nextEpisodeSeason.takeIf { keptNext != null },
        )
    }

    /** Until [dateMs] plus a day (so the first ratings exist), capped at [capMs]. */
    private fun untilDate(dateMs: Long?, nowMs: Long, capMs: Long): Long {
        if (dateMs == null || dateMs <= nowMs) return capMs
        return (dateMs - nowMs + DAY_MS).coerceAtMost(capMs)
    }

    /** Stable per title in [0, 1), so a title keeps its offset across refetches. */
    internal fun jitterUnit(cacheKey: String): Double {
        var hash = 0x811C9DC5.toInt()
        cacheKey.forEach { char ->
            hash = hash xor char.code
            hash *= 0x01000193
        }
        return (hash.toLong() and 0xFFFFFFFFL).toDouble() / 0x1_0000_0000L.toDouble()
    }

    /** `2024-02-27` or `2026-10-19T00:00:00.000Z` → epoch ms at UTC midnight of that day. */
    internal fun parseDateMs(value: String?): Long? {
        val day = value?.trim()?.take(10)?.takeIf { it.length == 10 } ?: return null
        return runCatching { LocalDate.parse(day).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }
}

/**
 * The release facts one MDBList title answer carries. Persisted with the cached ratings so a change
 * to the refresh intervals applies to titles already cached, not only to the next fetch.
 */
@Serializable
internal data class MdbListReleaseFacts(
    val isShow: Boolean,
    val status: String? = null,
    val releasedMs: Long? = null,
    val digitalMs: Long? = null,
    val lastEpisodeMs: Long? = null,
    val lastEpisodeSeason: Int? = null,
    val nextEpisodeMs: Long? = null,
    val nextEpisodeSeason: Int? = null,
) {
    val isReleasedStatus: Boolean get() = status?.trim()?.equals("released", ignoreCase = true) == true
}

/** One season from a bulk answer's `seasons`. */
internal data class MdbListSeasonFact(val number: Int, val airDateMs: Long?, val episodeCount: Int?)
