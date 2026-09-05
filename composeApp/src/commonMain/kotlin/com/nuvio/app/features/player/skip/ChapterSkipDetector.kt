package com.nuvio.app.features.player.skip

import com.nuvio.app.features.player.PlayerChapter

/**
 * Converts explicitly labelled media chapters into conservative skip intervals. Chapter labels
 * are authored by the stream provider rather than a shared database, so only unambiguous intro
 * labels are accepted and overly long chapter spans are rejected.
 */
internal object ChapterSkipDetector {
    private const val MAX_INTRO_START_SECONDS = 10 * 60.0
    private const val MIN_INTRO_DURATION_SECONDS = 2.0
    private const val MAX_INTRO_DURATION_SECONDS = 3 * 60.0
    private const val MIN_OUTRO_DURATION_SECONDS = 2.0
    private const val MAX_OUTRO_DURATION_SECONDS = 10 * 60.0
    private const val MIN_OUTRO_START_FRACTION = 0.60

    fun findIntervals(
        chapters: List<PlayerChapter>,
        durationSeconds: Double? = null,
    ): List<SkipInterval> {
        val ordered = chapters
            .asSequence()
            .filter { it.startTime.isFinite() && it.startTime >= 0.0 }
            .sortedBy(PlayerChapter::startTime)
            .distinctBy { it.startTime }
            .toList()

        val endMarkedOpenings = ordered.mapNotNull { chapter ->
            // Some release groups place an `Opening` marker at the *end* of the opening rather
            // than at its start. Treat that common convention as a 0 → marker interval.
            if (!isOpeningEndMarker(chapter.title)) return@mapNotNull null
            if (chapter.startTime !in MIN_INTRO_DURATION_SECONDS..MAX_INTRO_DURATION_SECONDS) return@mapNotNull null
            SkipInterval(
                startTime = 0.0,
                endTime = chapter.startTime,
                type = "intro",
                provider = CHAPTER_SKIP_PROVIDER,
            )
        }

        val introSegments = ordered.mapIndexedNotNull { index, chapter ->
            val endTime = ordered.getOrNull(index + 1)?.startTime ?: return@mapIndexedNotNull null
            val duration = endTime - chapter.startTime
            if (
                !isIntroTitle(chapter.title) ||
                (isOpeningEndMarker(chapter.title) && chapter.startTime > 0.0) ||
                chapter.startTime > MAX_INTRO_START_SECONDS ||
                duration !in MIN_INTRO_DURATION_SECONDS..MAX_INTRO_DURATION_SECONDS
            ) {
                return@mapIndexedNotNull null
            }
            SkipInterval(
                startTime = chapter.startTime,
                endTime = endTime,
                type = "intro",
                provider = CHAPTER_SKIP_PROVIDER,
            )
        }

        val validDuration = durationSeconds?.takeIf { it.isFinite() && it > 0.0 }
        val outroSegments = ordered.mapIndexedNotNull { index, chapter ->
            if (!isOutroTitle(chapter.title)) return@mapIndexedNotNull null
            val endTime = ordered.getOrNull(index + 1)?.startTime ?: validDuration ?: return@mapIndexedNotNull null
            val segmentDuration = endTime - chapter.startTime
            if (
                chapter.startTime < (validDuration ?: endTime) * MIN_OUTRO_START_FRACTION ||
                segmentDuration !in MIN_OUTRO_DURATION_SECONDS..MAX_OUTRO_DURATION_SECONDS
            ) {
                return@mapIndexedNotNull null
            }
            SkipInterval(
                startTime = chapter.startTime,
                endTime = endTime,
                type = "outro",
                provider = CHAPTER_SKIP_PROVIDER,
            )
        }

        return (endMarkedOpenings + introSegments + outroSegments)
            .distinctBy { interval -> Triple(interval.startTime, interval.endTime, interval.type) }
            .sortedBy(SkipInterval::startTime)
    }

    private fun isIntroTitle(title: String): Boolean {
        val normalized = normalizedTitle(title)
        return normalized in setOf(
            "intro",
            "op",
            "opening",
            "opening theme",
            "opening credits",
            "theme song",
            "title sequence",
            "main titles",
        )
    }

    private fun isOpeningEndMarker(title: String): Boolean =
        normalizedTitle(title) == "opening"

    private fun isOutroTitle(title: String): Boolean {
        val normalized = normalizedTitle(title)
        return normalized in setOf(
            "outro",
            "ed",
            "ending",
            "ending theme",
            "ending credits",
            "end credits",
            "credits",
            "closing credits",
        )
    }

    private fun normalizedTitle(title: String): String =
        title.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
}
