package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Addons inject rows into a stream response that are not streams — AIOStreams' diagnostics panel, a
 * "common sense" age-rating notice, an Elfhosted "notify me on release" hyperlink. They must never be
 * scored, ranked, or auto-selected, but must also never be dropped from the list.
 */
class StreamNonStreamRowsTest {

    private fun realStream(name: String, sizeGb: Double = 3.0) = StreamItem(
        name = name,
        addonName = "addon",
        addonId = "addon:x",
        url = "https://cdn.example.invalid/${name.hashCode()}.mkv",
        behaviorHints = StreamBehaviorHints(
            filename = name,
            videoSize = (sizeGb * StreamSizeBand.BYTES_PER_GB).toLong(),
        ),
    )

    /** How an addon delivers a link row: an externalUrl web page, no url, no infohash. */
    private fun infoRow(name: String) = StreamItem(
        name = name,
        addonName = "addon",
        addonId = "addon:x",
        externalUrl = "https://example.invalid/notify-me",
    )

    private val profile = StreamScoreProfile(
        enabled = true,
        points = ScoreAnswers().toPointsMap(),
    )

    // --- classification ---

    @Test
    fun linkOnlyRowsAreNotScorableButRealStreamsAre() {
        assertFalse(infoRow("Notify me when released").isScorableStream)
        assertFalse(infoRow("Common Sense Media: age 13+").isScorableStream)
        assertFalse(
            StreamItem(name = "AIOStreams diagnostics", addonName = "a", addonId = "a").isScorableStream,
        )

        assertTrue(realStream("Movie.2024.1080p.WEB-DL.x264-NTb").isScorableStream)
        // A torrent with no direct url is still a stream.
        assertTrue(
            StreamItem(
                name = "Movie.2024.2160p.BluRay.REMUX-FraMeSToR",
                addonName = "a",
                addonId = "a",
                infoHash = "c12fe3a9b4d5e6f708192a3b4c5d6e7f80912a3b",
            ).isScorableStream,
        )
        // Badly-labelled releases must still score — the model has traits for that.
        assertTrue(realStream("some.unlabelled.thing").isScorableStream)
    }

    // --- scoring ---

    @Test
    fun aNonStreamRowScoresNeutralInsteadOfBeingPenalised() {
        val score = StreamScorer.score(infoRow("Notify me when released"), profile, StreamScoreContext.MOVIE)
        assertEquals(0, score.total)
        assertTrue(score.components.isEmpty(), "got ${score.components.map { it.trait }}")
        assertFalse(score.rejected, "a non-stream row must not be treated as a rejected stream")
    }

    // --- ranking ---

    @Test
    fun nonStreamRowsAreKeptButAlwaysSortAfterRealStreams() {
        val good = realStream("Movie.2024.2160p.WEB-DL.x265-NTb", sizeGb = 15.0)
        val weak = realStream("Movie.2024.720p.WEBRip.x264-SOMEONE", sizeGb = 1.0)
        val notify = infoRow("Notify me when released")
        val diagnostics = infoRow("AIOStreams diagnostics")

        val ranked = StreamScorer.rank(
            listOf(notify, weak, diagnostics, good),
            profile,
            StreamScoreContext.MOVIE,
        )

        assertEquals(listOf(good, weak, notify, diagnostics), ranked)
    }

    @Test
    fun nonStreamRowsSurviveAMinimumScoreThatWouldRejectThem() {
        // Scored as a release these would sit at 0 and be rejected by a minimum of 1; they are not
        // releases, so they pass through untouched instead of vanishing from the picker.
        val strict = profile.copy(minimumScore = 1)
        val notify = infoRow("Notify me when released")
        val ranked = StreamScorer.rank(listOf(notify), strict, StreamScoreContext.MOVIE)
        assertEquals(listOf(notify), ranked)
    }

    @Test
    fun aRealStreamStillOutranksEvenWhenItScoresBadly() {
        // The ordering guarantee that matters: no non-stream row can ever be picked ahead of a
        // playable one, however poorly that stream scores.
        val awful = realStream("Movie.2024.CAM.x264-JUNK", sizeGb = 1.0)
        val notify = infoRow("Notify me when released")
        val ranked = StreamScorer.rank(listOf(notify, awful), profile, StreamScoreContext.MOVIE)
        assertEquals(awful, ranked.first())
    }
}
