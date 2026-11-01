package com.nuvio.app.features.preroll

import co.touchlab.kermit.Logger
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Which pre-show trailers have already played, so the same one is not shown twice.
 *
 * Without this every pre-show walked the film's "More Like This" list from the top, so putting the same film on
 * twice gave the same trailers, and two films with overlapping lists repeated each other. The list is kept per
 * profile and across launches. It never runs dry: once every candidate has played, the oldest play comes back first.
 */

/** Every field needs a default, for the same reason as [TriviaCacheFile]. */
@Serializable
internal data class TrailerHistoryFile(
    val version: Int = VERSION,
    /** `type:id` of a title whose trailer played -> when (epoch ms). */
    val played: Map<String, Long> = emptyMap(),
) {
    companion object {
        /** Bump on any change to this file's shape. */
        const val VERSION = 1
    }
}

internal fun trailerHistoryKey(type: String, id: String): String = "$type:$id"

/** Titles kept per profile; the oldest are forgotten first. */
internal const val TRAILER_HISTORY_MAX = 400

/**
 * [candidates] with the trailers not yet played first, in their own order, then the ones that have, oldest play first.
 * Nothing is removed, so a short list still fills the pre-show once everything has been seen.
 */
internal fun orderByHistory(candidates: List<MetaPreview>, played: Map<String, Long>): List<MetaPreview> {
    if (played.isEmpty()) return candidates
    val (seen, fresh) = candidates.partition { trailerHistoryKey(it.type, it.id) in played }
    return fresh + seen.sortedBy { played.getValue(trailerHistoryKey(it.type, it.id)) }
}

/** [played] with [key] set to [atEpochMs], trimmed to [max] entries by dropping the oldest. */
internal fun recordPlayed(played: Map<String, Long>, key: String, atEpochMs: Long, max: Int = TRAILER_HISTORY_MAX): Map<String, Long> {
    val next = LinkedHashMap(played)
    next.remove(key)
    next[key] = atEpochMs
    if (next.size <= max) return next
    val drop = next.entries.sortedBy { it.value }.take(next.size - max).map { it.key }
    drop.forEach { next.remove(it) }
    return next
}

internal object TrailerHistory {
    private val log = Logger.withTag("TrailerHistory")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val byProfile = mutableMapOf<Int, Map<String, Long>>()

    private fun loaded(profileId: Int): Map<String, Long> =
        byProfile.getOrPut(profileId) {
            runCatching {
                TrailerHistoryStorage.load(profileId)
                    ?.let { json.decodeFromString<TrailerHistoryFile>(it) }
                    ?.takeIf { it.version == TrailerHistoryFile.VERSION }
                    ?.played
            }.onFailure { log.w(it) { "trailer history unreadable; starting empty" } }
                .getOrNull()
                .orEmpty()
        }

    /** The active profile's played trailers. */
    fun snapshot(): Map<String, Long> = synchronized(lock) { loaded(ProfileRepository.activeProfileId) }

    /** Notes that [type]/[id]'s trailer played, for the active profile. */
    fun record(type: String, id: String, nowEpochMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        val profileId = ProfileRepository.activeProfileId
        val next = recordPlayed(loaded(profileId), trailerHistoryKey(type, id), nowEpochMs)
        byProfile[profileId] = next
        runCatching { TrailerHistoryStorage.save(profileId, json.encodeToString(TrailerHistoryFile(played = next))) }
            .onFailure { log.w(it) { "could not persist trailer history" } }
    }
}

internal expect object TrailerHistoryStorage {
    fun load(profileId: Int): String?
    fun save(profileId: Int, json: String?)
}
