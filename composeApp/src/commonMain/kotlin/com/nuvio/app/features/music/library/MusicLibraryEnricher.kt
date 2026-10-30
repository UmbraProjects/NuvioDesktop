package com.nuvio.app.features.music.library

import co.touchlab.kermit.Logger
import com.nuvio.app.features.music.MusicAlbumSummary
import com.nuvio.app.features.music.defaultMusicCatalog
import com.nuvio.app.features.music.MusicArtist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private val log = Logger.withTag("MusicLibraryEnricher")

/**
 * Looks an artist up across every source and builds its [LibraryArtist]. Each source is optional:
 * one that is down, or has no such artist, only leaves its fields empty. Nothing here throws except
 * cancellation.
 */
internal class MusicLibraryEnricher(
    private val settings: MusicLibrarySettings,
    /** A plain album search, used to find collaborations filed under the other artist ([withCollabReleases]). */
    private val searchReleases: suspend (String) -> List<MusicAlbumSummary> = { defaultMusicCatalog().searchReleases(it) },
) {
    private val audioDb = AudioDbClient(settings.audioDbKey)
    private val fanart = FanartTvClient(settings.fanartApiKey)

    /**
     * The artist with all its metadata and artwork. Per-release details (labels, track counts) come from [addReleaseDetails].
     * [onPartial] gets the artist early, as far as the quick sources go: once with Deezer's releases and AudioDB's artwork
     * (found by name) while MusicBrainz, which paces at a second a call and often answers 503, is still working, and once
     * more before the collaboration search. The returned artist replaces both.
     */
    suspend fun build(
        artist: MusicArtist,
        releases: List<MusicAlbumSummary>,
        addedAtEpochMs: Long,
        nowEpochMs: Long,
        onStatus: (String) -> Unit = {},
        onPartial: (LibraryArtist) -> Unit = {},
    ): LibraryArtist = coroutineScope {
        val name = artist.name
        fun assemble(sources: ArtistSources, merged: List<LibraryRelease>) = assembleArtist(
            id = artist.browseId,
            name = name,
            youTubeThumbnail = artist.thumbnailUrl,
            sources = sources,
            releases = merged,
            addedAtEpochMs = addedAtEpochMs,
            nowEpochMs = nowEpochMs,
        )

        // Deezer is a different host with its own pacing, so it runs while MusicBrainz is being polite.
        val deezerJob = async {
            try {
                val hit = pickDeezerArtist(DeezerClient.searchArtist(name), name)
                hit to hit?.let { DeezerClient.albums(it.id) }.orEmpty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                log.w { "Deezer lookup for $name failed: ${error.message}" }
                null to emptyList<DeezerAlbum>()
            }
        }

        val mbJob = async {
            val mbHit = pickMbArtist(MusicBrainzClient.searchArtist(name), name)
            val found = mbHit?.let { MusicBrainzClient.artist(it.id) }
            found to if (found != null) MusicBrainzClient.releaseGroups(found.id) else emptyList()
        }
        // AudioDB by name and fanart.tv by the MusicBrainz id AudioDB carries: the backdrop, without waiting for MusicBrainz.
        onStatus("Fetching artwork")
        val audioByName = audioDb.artistByName(name)
        val quickFanartJob = async { audioByName?.musicBrainzId?.let { fanart.artist(it) } }
        val (deezerHit, deezerAlbums) = deezerJob.await()
        val quickFanart = quickFanartJob.await()
        if (!mbJob.isCompleted) {
            onPartial(
                assemble(
                    ArtistSources(deezer = deezerHit, audioDb = audioByName, fanart = quickFanart),
                    mergeReleases(ReleaseSources(youTubeMusic = releases, deezer = deezerAlbums, fanart = quickFanart)),
                ),
            )
        }

        onStatus("Finding $name on MusicBrainz")
        val (mbArtist, groups) = mbJob.await()

        // Name search can land on a namesake. Compare the channel with MusicBrainz before trusting its facts.
        val verdict = if (mbArtist == null) {
            ChannelVerdict.Unconfirmed
        } else {
            verifyChannel(artist.browseId, mbArtist.links, releases.map { it.title }, groups.map { it.title })
        }
        if (verdict is ChannelVerdict.Mismatch) {
            log.w { "$name (${artist.browseId}) does not match the MusicBrainz artist of that name; keeping YouTube Music's data only" }
            deezerJob.cancel()
            return@coroutineScope assembleArtist(
                id = artist.browseId,
                name = name,
                youTubeThumbnail = artist.thumbnailUrl,
                sources = ArtistSources(),
                releases = mergeReleases(ReleaseSources(youTubeMusic = releases)),
                addedAtEpochMs = addedAtEpochMs,
                nowEpochMs = nowEpochMs,
            ).copy(channelMismatch = true, suggestedChannelId = verdict.betterChannelId?.takeIf { it != artist.browseId })
        }

        val audioArtist = mbArtist?.let { mb ->
            if (audioByName?.musicBrainzId == mb.id) audioByName else audioDb.artistByMusicBrainzId(mb.id)
        } ?: audioByName
        val mbid = mbArtist?.id ?: audioArtist?.musicBrainzId
        val fanartJob = async { if (mbid == audioByName?.musicBrainzId) quickFanart else mbid?.let { fanart.artist(it) } }
        val audioAlbumsJob = async { audioArtist?.let { audioDb.albums(it.id) }.orEmpty() }
        val wikiJob = async {
            if (audioArtist?.biography != null) null else mbArtist?.wikipediaTitle?.let { WikipediaClient.summary(it) }
        }

        val fanartArtist = fanartJob.await()
        val sources = ArtistSources(mbArtist, deezerHit, audioArtist, fanartArtist, wikiJob.await())
        val merged = mergeReleases(
            ReleaseSources(
                youTubeMusic = releases,
                musicBrainz = groups,
                deezer = deezerAlbums,
                audioDb = audioAlbumsJob.await(),
                fanart = fanartArtist,
            ),
        )
        onPartial(assemble(sources, merged))
        // fork:music - a collaboration on the other artist's channel is still this artist's release.
        onStatus("Looking for collaborations")
        val assembled = assemble(sources, withCollabReleases(merged, name, artist.browseId, searchReleases))
        // fork:music - with a Last.fm key its tags lead (MusicBrainz's stay behind them), so an artist still gets a
        // place on the Constellation and in ladders when MusicBrainz is unreachable.
        withLastFmTags(assembled, name)
    }

    private suspend fun withLastFmTags(artist: LibraryArtist, name: String): LibraryArtist {
        if (!LastFmClient.isConfigured) return artist
        val tags = LastFmClient.artistTags(name)?.tags?.keys?.toList().orEmpty()
        if (tags.isEmpty()) return artist
        val merged = (tags + artist.tags).distinctBy { it.lowercase() }
            .filter { tag -> artist.genres.none { it.equals(tag, ignoreCase = true) } }
        return artist.copy(tags = merged)
    }

    /**
     * Fills label, track count, length, genres and UPC from Deezer's album pages, one call per
     * release (paced). [onProgress] gets the artist's releases again after every few, so the screen
     * fills in while this runs.
     */
    suspend fun addReleaseDetails(
        releases: List<LibraryRelease>,
        onProgress: (List<LibraryRelease>) -> Unit,
    ): List<LibraryRelease> {
        val current = releases.toMutableList()
        var sinceReport = 0
        for ((index, release) in releases.withIndex()) {
            val deezerId = release.deezerId ?: continue
            if (release.trackCount != null && release.label != null) continue
            val detail = DeezerClient.albumDetail(deezerId) ?: continue
            current[index] = release.withDeezerDetail(detail)
            if (++sinceReport >= REPORT_EVERY) {
                sinceReport = 0
                onProgress(current.toList())
            }
        }
        return current.toList()
    }

    private companion object {
        const val REPORT_EVERY = 8
    }
}
