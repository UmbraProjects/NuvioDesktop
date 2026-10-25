package com.nuvio.app.features.player.skip

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.resolveSeriesEpisodePosition
import com.nuvio.app.features.details.sortedPlaybackEpisodePositions
import com.nuvio.app.features.watching.domain.isShortPlaceholderDuration

object PlayerNextEpisodeRules {

    fun resolveNextEpisode(
        videos: List<MetaVideo>,
        currentSeason: Int?,
        currentEpisode: Int?,
        currentVideoId: String? = null,
        parentMetaId: String? = null,
    ): MetaVideo? {
        val current = videos.resolveSeriesEpisodePosition(
            parentMetaId = parentMetaId,
            videoId = currentVideoId,
            seasonNumber = currentSeason,
            episodeNumber = currentEpisode,
        ) ?: return null
        val sortedEpisodes = videos.sortedPlaybackEpisodePositions()
        val currentIndex = sortedEpisodes.indexOfFirst { position -> position.video == current.video }
        if (currentIndex < 0) return null
        return sortedEpisodes
            .drop(currentIndex + 1)
            .firstOrNull { candidate ->
                // Never roll from a regular episode into a specials bucket at the end of a run.
                current.seasonNumber <= 0 || candidate.seasonNumber > 0
            }
            ?.video
    }

    /**
     * [resolveNextEpisode], passing over episodes [isSkipped] names (skip-filler). Falls back to the
     * plain next episode when nothing after it is both kept and aired, so a show that ends - or is
     * still airing - in a filler run keeps going instead of stopping.
     */
    fun resolveNextEpisodeSkipping(
        videos: List<MetaVideo>,
        currentSeason: Int?,
        currentEpisode: Int?,
        currentVideoId: String? = null,
        parentMetaId: String? = null,
        isSkipped: ((MetaVideo) -> Boolean)? = null,
    ): MetaVideo? {
        val next = resolveNextEpisode(videos, currentSeason, currentEpisode, currentVideoId, parentMetaId)
            ?: return null
        if (isSkipped == null || !isSkipped(next)) return next
        val current = videos.resolveSeriesEpisodePosition(
            parentMetaId = parentMetaId,
            videoId = currentVideoId,
            seasonNumber = currentSeason,
            episodeNumber = currentEpisode,
        ) ?: return next
        val sortedEpisodes = videos.sortedPlaybackEpisodePositions()
        val currentIndex = sortedEpisodes.indexOfFirst { position -> position.video == current.video }
        if (currentIndex < 0) return next
        return sortedEpisodes
            .drop(currentIndex + 1)
            .asSequence()
            .filter { candidate -> current.seasonNumber <= 0 || candidate.seasonNumber > 0 }
            .map { it.video }
            .firstOrNull { video -> !isSkipped(video) }
            ?.takeIf { hasEpisodeAired(it.released) }
            ?: next
    }

    fun resolvePreviousEpisode(
        videos: List<MetaVideo>,
        currentSeason: Int?,
        currentEpisode: Int?,
        currentVideoId: String? = null,
        parentMetaId: String? = null,
    ): MetaVideo? {
        val current = videos.resolveSeriesEpisodePosition(
            parentMetaId = parentMetaId,
            videoId = currentVideoId,
            seasonNumber = currentSeason,
            episodeNumber = currentEpisode,
        ) ?: return null
        val sortedEpisodes = videos.sortedPlaybackEpisodePositions()
        val currentIndex = sortedEpisodes.indexOfFirst { position -> position.video == current.video }
        if (currentIndex <= 0) return null
        return sortedEpisodes
            .take(currentIndex)
            .asReversed()
            .firstOrNull { candidate ->
                current.seasonNumber <= 0 || candidate.seasonNumber > 0
            }
            ?.video
    }

    fun shouldShowNextEpisodeCard(
        positionMs: Long,
        durationMs: Long,
        skipIntervals: List<SkipInterval>,
        thresholdMode: NextEpisodeThresholdMode,
        thresholdPercent: Float,
        thresholdMinutesBeforeEnd: Float,
    ): Boolean {
        // An error clip or debrid placeholder is not the episode, so it never leads into the next.
        if (isShortPlaceholderDuration(durationMs)) return false
        val outroSegments = skipIntervals.filter { it.type in OUTRO_SEGMENT_TYPES }

        if (outroSegments.isNotEmpty()) {
            if (durationMs <= 0L) return false
            val latestOutroEndMs = (outroSegments.maxOf { it.endTime } * 1_000.0).toLong()
            val postOutroGapMs = durationMs - latestOutroEndMs

            // Calculate the user's configured threshold as milliseconds from end.
            val userThresholdMs = when (thresholdMode) {
                NextEpisodeThresholdMode.PERCENTAGE -> {
                    val clampedPercent = thresholdPercent.coerceIn(97f, 100f)
                    ((1.0 - clampedPercent / 100.0) * durationMs).toLong()
                }
                NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
                    val clampedMinutes = thresholdMinutesBeforeEnd.coerceIn(0f, 3.5f)
                    (clampedMinutes * 60_000f).toLong()
                }
            }

            return if (postOutroGapMs > userThresholdMs) {
                when (thresholdMode) {
                    NextEpisodeThresholdMode.PERCENTAGE -> {
                        val clampedPercent = thresholdPercent.coerceIn(97f, 100f)
                        (positionMs.toDouble() / durationMs.toDouble()) >= (clampedPercent / 100.0)
                    }
                    NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
                        val clampedMinutes = thresholdMinutesBeforeEnd.coerceIn(0f, 3.5f)
                        val remainingMs = durationMs - positionMs
                        remainingMs <= (clampedMinutes * 60_000f).toLong()
                    }
                }
            } else {
                // Outro ends close to the file end — fire at earliest outro start.
                positionMs / 1_000.0 >= outroSegments.minOf { it.startTime }
            }
        }

        // Fallback to the settings threshold when no outro data exists.
        if (durationMs <= 0L) return false
        return when (thresholdMode) {
            NextEpisodeThresholdMode.PERCENTAGE -> {
                val clampedPercent = thresholdPercent.coerceIn(97f, 100f)
                (positionMs.toDouble() / durationMs.toDouble()) >= (clampedPercent / 100.0)
            }
            NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
                val clampedMinutes = thresholdMinutesBeforeEnd.coerceIn(0f, 3.5f)
                val remainingMs = durationMs - positionMs
                remainingMs <= (clampedMinutes * 60_000f).toLong()
            }
        }
    }

    fun hasEpisodeAired(raw: String?): Boolean {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        val dateStr = when {
            value.length >= 10 -> value.substring(0, 10)
            else -> return true
        }
        // Parse YYYY-MM-DD
        val parts = dateStr.split("-")
        if (parts.size != 3) return true
        val year = parts[0].toIntOrNull() ?: return true
        val month = parts[1].toIntOrNull() ?: return true
        val day = parts[2].toIntOrNull() ?: return true

        val today = currentDateComponents()
        return compareDate(year, month, day, today.year, today.month, today.day) <= 0
    }

    private fun compareDate(
        y1: Int, m1: Int, d1: Int,
        y2: Int, m2: Int, d2: Int,
    ): Int {
        if (y1 != y2) return y1.compareTo(y2)
        if (m1 != m2) return m1.compareTo(m2)
        return d1.compareTo(d2)
    }

    val OUTRO_SEGMENT_TYPES = setOf("outro", "ed", "mixed-ed")
}

internal expect fun currentDateComponents(): DateComponents

data class DateComponents(val year: Int, val month: Int, val day: Int)
