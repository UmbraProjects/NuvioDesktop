package com.nuvio.app.features.preroll

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.features.trailer.TrailerPlaybackResolver
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import com.nuvio.app.features.trailer.sourceOrNull
import kotlinx.coroutines.withTimeoutOrNull

internal expect object PreshowLocalFiles {
    fun defaultFolder(): String

    /** Media files in [folder]/[subfolder], creating the folder layout on first use. */
    fun list(folder: String, subfolder: String): List<String>
}

private val log = Logger.withTag("PreshowAssets")

private const val AVID_PAGE_TTL_MS = 6L * 60L * 60L * 1000L
private const val AVID_FETCH_TIMEOUT_MS = 12_000L
private const val YOUTUBE_RESOLVE_TIMEOUT_MS = 10_000L
private const val AVID_CANDIDATES_TRIED = 3

/** An embedded YouTube video on an AVID page, with the text just before it (heading, caption). */
internal data class AvidClip(val videoId: String, val context: String)

private val YOUTUBE_ID = Regex(
    "(?:youtube(?:-nocookie)?\\.com/(?:embed/|watch\\?v=)|youtu\\.be/)([A-Za-z0-9_-]{11})",
)
private val HTML_TAG = Regex("<[^>]*>")

internal fun parseAvidClips(html: String): List<AvidClip> =
    YOUTUBE_ID.findAll(html)
        .map { match ->
            val before = html.substring((match.range.first - 900).coerceAtLeast(0), match.range.first)
            val text = HTML_TAG.replace(before, " ").replace(Regex("\\s+"), " ").takeLast(300)
            AvidClip(match.groupValues[1], text)
        }
        .distinctBy { it.videoId }
        .toList()

/** The links in a slot's AVID setting: one or several, separated by spaces, commas, semicolons or new lines. */
internal fun splitAvidLinks(raw: String): List<String> =
    raw.split(Regex("[\\s,;]+")).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

internal fun selectRatingClip(clips: List<AvidClip>, certificate: String): AvidClip? =
    clips.firstOrNull { mentionsCertificate(it.context, certificate) }

private object AvidClips {
    private val lock = Any()
    private val cache = mutableMapOf<String, Pair<Long, List<AvidClip>>>()

    suspend fun forPage(url: String): List<AvidClip> {
        // A bare YouTube link is accepted in place of a wiki page.
        YOUTUBE_ID.find(url)?.let { return listOf(AvidClip(it.groupValues[1], "")) }
        val now = LibraryClock.nowEpochMs()
        synchronized(lock) { cache[url] }?.let { (at, clips) -> if (now - at < AVID_PAGE_TTL_MS) return clips }
        val html = withTimeoutOrNull(AVID_FETCH_TIMEOUT_MS) {
            runCatching {
                httpGetTextWithHeaders(
                    url,
                    mapOf("Accept" to "text/html,application/xhtml+xml", "User-Agent" to "Mozilla/5.0 NuvioDesktop"),
                )
            }.onFailure { log.w { "AVID fetch failed for $url: ${it.message}" } }.getOrNull()
        } ?: return emptyList()
        val clips = parseAvidClips(html)
        log.i { "AVID $url: ${clips.size} embedded videos" }
        if (clips.isNotEmpty()) synchronized(lock) { cache[url] = now to clips }
        return clips
    }
}

/** Finds something playable for a clip slot: a local file first, then an AVID page. */
object PreshowAssets {
    suspend fun clipFor(slot: PreshowSlot, certification: String?): TrailerPlaybackSource? {
        if (!slot.isClipSlot) return null
        val region = PreshowSettings.ratingRegion.value
        val certificate = if (slot == PreshowSlot.RatingCard) {
            normalizeCertification(certification, region) ?: return null
        } else {
            null
        }
        return runCatching {
            localClip(slot, region, certificate) ?: avidClip(slot, region, certificate)
        }.onFailure { log.w { "no clip for ${slot.key}: ${it.message}" } }.getOrNull()
    }

    private fun localClip(slot: PreshowSlot, region: RatingRegion, certificate: String?): TrailerPlaybackSource? {
        val subfolder = slot.localSubfolder(region) ?: return null
        val folder = PreshowSettings.localFolder.value.ifBlank { PreshowLocalFiles.defaultFolder() }
        val files = PreshowLocalFiles.list(folder, subfolder)
        val path = if (certificate != null) {
            files.firstOrNull { mentionsCertificate(it.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.'), certificate) }
        } else {
            files.randomOrNull()
        }
        return path?.also { log.i { "${slot.key}: local file $it" } }?.let { TrailerPlaybackSource(videoUrl = it) }
    }

    private suspend fun avidClip(slot: PreshowSlot, region: RatingRegion, certificate: String?): TrailerPlaybackSource? {
        val urls = splitAvidLinks(PreshowSettings.avidUrl(slot, region)).ifEmpty { return null }
        // Several pages pool their clips, so a slot can draw from more than one source.
        val clips = urls.flatMap { AvidClips.forPage(it) }.distinctBy { it.videoId }
        val candidates = if (certificate != null) {
            listOfNotNull(selectRatingClip(clips, certificate))
        } else {
            clips.shuffled().take(AVID_CANDIDATES_TRIED)
        }
        for (clip in candidates) {
            val source = withTimeoutOrNull(YOUTUBE_RESOLVE_TIMEOUT_MS) {
                TrailerPlaybackResolver.resolveFromYouTubeUrl("https://www.youtube.com/watch?v=${clip.videoId}").sourceOrNull()
            }
            if (source != null) {
                log.i { "${slot.key}: AVID clip ${clip.videoId}" }
                return source
            }
        }
        return null
    }
}
