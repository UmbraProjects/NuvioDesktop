package com.nuvio.app.features.watching.sync

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

object SupabaseProgressSyncAdapter : ProgressSyncAdapter {
    private val log = Logger.withTag("NuvioSyncProgress")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun pull(profileId: Int): List<ProgressSyncRecord> {
        log.d { "pull start profileId=$profileId" }
        val params = buildJsonObject { put("p_profile_id", profileId) }
        val result = SupabaseProvider.client.postgrest.rpc("sync_pull_watch_progress", params)
        val serverEntries = result.decodeList<WatchProgressSyncEntry>()
        val records = serverEntries.map { entry ->
            ProgressSyncRecord(
                contentId = entry.contentId,
                contentType = entry.contentType,
                videoId = entry.videoId,
                season = entry.season,
                episode = entry.episode,
                position = entry.position,
                duration = entry.duration,
                lastWatched = entry.lastWatched,
            )
        }
        log.d {
            "pull returned raw=${serverEntries.size} records=${records.size} " +
                "items=${records.debugProgressRecordSummary()}"
        }
        return records
    }

    override suspend fun push(
        profileId: Int,
        entries: Collection<WatchProgressEntry>,
    ) {
        log.d {
            "push start profileId=$profileId entries=${entries.size} " +
                "items=${entries.debugWatchProgressEntrySummary()}"
        }
        val syncEntries = entries.map { entry ->
            WatchProgressSyncEntry(
                contentId = entry.parentMetaId,
                contentType = entry.contentType,
                videoId = entry.videoId,
                season = entry.seasonNumber,
                episode = entry.episodeNumber,
                position = entry.lastPositionMs,
                duration = entry.durationMs,
                lastWatched = entry.lastUpdatedEpochMs,
                progressKey = progressKeyForEntry(entry),
            )
        }
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            put("p_entries", json.encodeToJsonElement(syncEntries))
        }
        SupabaseProvider.client.postgrest.rpc("sync_push_watch_progress", params)
        log.d { "push complete profileId=$profileId entries=${syncEntries.size}" }
    }

    override suspend fun delete(
        profileId: Int,
        entries: Collection<WatchProgressEntry>,
    ) {
        log.d {
            "delete start profileId=$profileId entries=${entries.size} " +
                "items=${entries.debugWatchProgressEntrySummary()}"
        }
        val progressKeys = entries.map { entry ->
            if (entry.seasonNumber != null && entry.episodeNumber != null) {
                "${entry.parentMetaId}_s${entry.seasonNumber}e${entry.episodeNumber}"
            } else {
                entry.parentMetaId
            }
        }
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            put("p_keys", json.encodeToJsonElement(progressKeys))
        }
        SupabaseProvider.client.postgrest.rpc("sync_delete_watch_progress", params)
        log.d { "delete complete profileId=$profileId keys=${progressKeys.joinToString(limit = 12)}" }
    }

    private fun progressKeyForEntry(entry: WatchProgressEntry): String =
        if (entry.seasonNumber != null && entry.episodeNumber != null) {
            "${entry.parentMetaId}_s${entry.seasonNumber}e${entry.episodeNumber}"
        } else {
            entry.parentMetaId
        }
}

@Serializable
private data class WatchProgressSyncEntry(
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("video_id") val videoId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val position: Long = 0,
    val duration: Long = 0,
    @SerialName("last_watched") val lastWatched: Long = 0,
    @SerialName("progress_key") val progressKey: String = "",
)

private fun Collection<ProgressSyncRecord>.debugProgressRecordSummary(limit: Int = 10): String =
    take(limit).joinToString(separator = " | ") { record ->
        buildString {
            append(record.contentType)
            append(":")
            append(record.contentId)
            if (record.season != null || record.episode != null) {
                append(" s=")
                append(record.season)
                append(" e=")
                append(record.episode)
            }
            append(" video=")
            append(record.videoId)
            append(" pos=")
            append(record.position)
            append(" dur=")
            append(record.duration)
            append(" last=")
            append(record.lastWatched)
        }
    }.ifBlank { "none" }

private fun Collection<WatchProgressEntry>.debugWatchProgressEntrySummary(limit: Int = 10): String =
    take(limit).joinToString(separator = " | ") { entry ->
        buildString {
            append(entry.parentMetaType)
            append(":")
            append(entry.parentMetaId)
            if (entry.seasonNumber != null || entry.episodeNumber != null) {
                append(" s=")
                append(entry.seasonNumber)
                append(" e=")
                append(entry.episodeNumber)
            }
            append(" video=")
            append(entry.videoId)
            append(" pos=")
            append(entry.lastPositionMs)
            append(" dur=")
            append(entry.durationMs)
            append(" pct=")
            append(entry.progressPercent)
            append(" completed=")
            append(entry.isCompleted)
            append(" last=")
            append(entry.lastUpdatedEpochMs)
        }
    }.ifBlank { "none" }
