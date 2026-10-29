package com.nuvio.app.features.music.library

import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Ladder phase 7: feedback and progress, as pure data plus a small store (so it is unit-tested).
 *
 * After a rung the person can say it is too far from their taste, too close, skip it, or love it. Too far and too
 * close move the familiarity dial one [DIAL_STEP] (toward Known, toward Unknown). A rung that got feedback (other than
 * skip) is *climbed*: it stays where it is, up to [MAX_CLIMBED] of them, and only the rungs after it are rebuilt, at
 * the new dial, so the rest of the ladder re-ranks. A skipped artist never comes back. All of it is kept per ladder
 * ([progressKey]) and per profile, so the ladder is where it was after a restart.
 */

@Serializable
internal enum class RungFeedback { TooFar, TooClose, Skip, Love }

/** One dial step per "too far" or "too close". Four steps cross the whole dial. */
internal const val DIAL_STEP = 0.25

/** Marked rungs kept at the top; the ladder always has at least one rung left to build. */
internal const val MAX_CLIMBED = LADDER_RUNGS - 1

/** A rung the person has marked, with what the screen needs to draw it again without any network. */
@Serializable
internal data class ClimbedRung(
    val id: String,
    val name: String,
    val why: String,
    val purity: Double,
    val proximity: Double,
    val anchorName: String? = null,
    val playable: Boolean = true,
    val feedback: RungFeedback,
)

@Serializable
internal data class LadderProgress(
    /** 0.0 Known to 1.0 Unknown. */
    val dial: Double = 0.0,
    val climbed: List<ClimbedRung> = emptyList(),
    /** Candidate ids the person skipped. */
    val skipped: List<String> = emptyList(),
    /** What was asked, kept so the Search screen can offer to continue this ladder. Null on ladders saved before it existed. */
    val request: LadderRequest? = null,
) {
    val isFresh: Boolean get() = dial == 0.0 && climbed.isEmpty() && skipped.isEmpty()
}

/** Candidate ids that must not stand on a fresh rung: marked ones are already shown, skipped ones are gone. */
internal fun LadderProgress.excluded(): Set<String> = (climbed.map { it.id } + skipped).toSet()

/** How many rungs are left to build after the marked ones: at least one. */
internal fun remainingRungs(progress: LadderProgress): Int = (LADDER_RUNGS - progress.climbed.size).coerceAtLeast(1)

private fun LadderRung.snapshot(feedback: RungFeedback) = ClimbedRung(
    id = candidate.id,
    name = candidate.name,
    why = why,
    purity = purity,
    proximity = proximity,
    anchorName = anchorName,
    playable = candidate.playable,
    feedback = feedback,
)

/** The progress after [feedback] on [rung]. Feedback on a rung already marked replaces its mark. */
internal fun applyFeedback(progress: LadderProgress, rung: LadderRung, feedback: RungFeedback): LadderProgress {
    val id = rung.candidate.id
    val others = progress.climbed.filterNot { it.id == id }
    return when (feedback) {
        RungFeedback.Skip -> progress.copy(climbed = others, skipped = (progress.skipped - id) + id)
        else -> {
            val dial = when (feedback) {
                RungFeedback.TooFar -> progress.dial - DIAL_STEP
                RungFeedback.TooClose -> progress.dial + DIAL_STEP
                else -> progress.dial
            }.coerceIn(0.0, 1.0)
            progress.copy(
                dial = dial,
                climbed = (others + rung.snapshot(feedback)).takeLast(MAX_CLIMBED),
                skipped = progress.skipped - id,
            )
        }
    }
}

/**
 * The ladder to show: the marked rungs first, in the order they were marked, then the [fresh] rungs built for the
 * rest. Steps are renumbered from 1. Purity rises within each part, not necessarily across the join, because the dial
 * may have moved.
 */
internal fun mergeClimbed(progress: LadderProgress, fresh: Ladder): Ladder {
    if (progress.climbed.isEmpty()) return fresh
    val marked = progress.climbed.mapIndexed { i, c ->
        LadderRung(
            step = i + 1,
            candidate = LadderCandidate(id = c.id, name = c.name, votes = emptyMap(), playable = c.playable),
            purity = c.purity,
            proximity = c.proximity,
            anchorName = c.anchorName,
            why = c.why,
            mark = c.feedback,
        )
    }
    val rest = fresh.rungs.mapIndexed { i, r -> r.copy(step = marked.size + i + 1) }
    return fresh.copy(rungs = marked + rest)
}

/** What a ladder is stored under: the kind of request and what was asked, spelling ignored. */
internal fun progressKey(request: LadderRequest): String = when (request) {
    is LadderRequest.Genre -> "genre:${genreKey(request.genre)}"
    is LadderRequest.Artist -> when (request.mode) {
        LadderMode.Into -> "into:${genreKey(request.name)}"
        LadderMode.OutFrom -> "out:${genreKey(request.name)}:${request.destination?.let(::genreKey).orEmpty()}"
    }
    is LadderRequest.Album -> when (request.mode) {
        LadderMode.Into -> "album-into:${genreKey(request.artist)}:${genreKey(request.title)}"
        LadderMode.OutFrom ->
            "album-out:${genreKey(request.artist)}:${genreKey(request.title)}:${request.destination?.let(::genreKey).orEmpty()}"
    }
}

@Serializable
internal data class StoredLadders(
    val ladders: Map<String, LadderProgress> = emptyMap(),
    /** Artists skipped on any ladder: they never stand on a rung of any ladder of this profile. */
    val skipped: List<String> = emptyList(),
)

/** Progress by ladder key over a payload store. [load] and [save] read and write the one JSON payload. */
internal class LadderProgressBook(
    private val load: () -> String?,
    private val save: (String) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun readStored(): StoredLadders =
        load()?.let { runCatching { json.decodeFromString<StoredLadders>(it) }.getOrNull() } ?: StoredLadders()

    private fun read(): Map<String, LadderProgress> = readStored().ladders

    fun get(key: String): LadderProgress = read()[key] ?: LadderProgress()

    /** Every artist skipped on any ladder. Starting a ladder over does not bring them back. */
    fun skippedEverywhere(): Set<String> = readStored().skipped.toSet()

    /** Every stored ladder, least recently saved first (a save moves its ladder to the end). */
    fun all(): List<LadderProgress> = read().values.toList()

    /** Stores [progress]; a fresh one removes the entry, so starting over leaves nothing behind. */
    fun put(key: String, progress: LadderProgress) {
        val stored = readStored()
        val all = stored.ladders.toMutableMap()
        all.remove(key)
        if (!progress.isFresh) all[key] = progress
        val skipped = (stored.skipped + progress.skipped).distinct()
        save(json.encodeToString(StoredLadders(all, skipped)))
    }
}

/** The live book: the active profile's ladders, on disk. */
internal object LadderProgressStore {
    private fun book(): LadderProgressBook {
        val profile = ProfileRepository.activeProfileId
        return LadderProgressBook(
            load = { MusicLibraryStorage.loadLadders(profile) },
            save = { MusicLibraryStorage.saveLadders(profile, it) },
        )
    }

    fun get(request: LadderRequest): LadderProgress = book().get(progressKey(request))

    /** [progress] with every artist skipped on any ladder added, for building: a skip is the listener's, not one ladder's. */
    fun forBuilding(progress: LadderProgress): LadderProgress =
        progress.copy(skipped = (progress.skipped + book().skippedEverywhere()).distinct())

    fun put(request: LadderRequest, progress: LadderProgress) =
        book().put(progressKey(request), progress.copy(request = request))

    /** Ladders with at least one climbed rung that can be reopened, most recently saved first. */
    fun resumable(): List<LadderProgress> = book().all().asReversed()
}
