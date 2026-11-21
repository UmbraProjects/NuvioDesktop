package com.nuvio.app.features.usenet

import com.nuvio.app.features.debrid.DebridStreamAudioTag
import com.nuvio.app.features.debrid.DebridStreamQuality
import com.nuvio.app.features.debrid.DebridStreamResolution
import com.nuvio.app.features.debrid.DebridStreamVisualTag
import com.nuvio.app.features.streams.ReleaseGroupName
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamReleaseGroups
import com.nuvio.app.features.streams.StreamTraitDetector
import com.nuvio.app.features.streams.StreamTraits
import com.nuvio.app.features.streams.isScorableStream
import java.util.Locale

/**
 * How built-in Usenet rows read in the source picker, laid out like a popular AIOStreams format
 * so addon rows and Usenet rows read the same way:
 *
 *     📀 2160p
 *     ⚡Midsommar | 2019        (📖 instead of ⚡ when the NZB is already kept: Cache NZB files)
 *     ☀️ DV · HDR | 9 months | AltHub
 *     🥇 FraMeSToR 🎧 DTS-HD MA • 5.1
 *     (release name, when "Show release names" is on)
 *
 * The size is left out: the card prints its own size chip.
 */
internal object UsenetStreamFormat {
    private const val TITLE_LIMIT = 25

    /** What the row is a release of; the title and year come from the metadata, not the release name. */
    data class Subject(val title: String? = null, val year: Int? = null, val series: Boolean = false)

    /** Fills [stream]'s name and description; [stream] must already carry the filename and size. */
    fun format(
        stream: StreamItem,
        subject: Subject,
        indexerName: String?,
        publishedAt: Long,
        showReleaseName: Boolean,
        now: Long = System.currentTimeMillis(),
        kept: Boolean = false,
    ): StreamItem {
        val traits = StreamTraitDetector.detect(stream)
        val releaseName = stream.behaviorHints.filename.orEmpty()
        val lines = listOf(
            titleLine(subject, releaseName, kept),
            pictureLine(traits, ageLabel(publishedAt, now), indexerName),
            groupAudioLine(traits, releaseName),
            releaseName.takeIf { showReleaseName && it.isNotBlank() },
        ).filterNot { it.isNullOrBlank() }
        return stream.copy(
            name = header(traits),
            description = lines.joinToString("\n").ifBlank { releaseName },
        )
    }

    /**
     * Applies the built-in Usenet layout to any stream row (addon, debrid, P2P, media server) for
     * display. Built-in Usenet rows are returned unchanged so their indexer age and kept-NZB badge
     * stay intact, and non-release notice rows (e.g. addon diagnostics) are left alone.
     */
    fun formatForAllResults(
        stream: StreamItem,
        subject: Subject,
        showReleaseName: Boolean,
    ): StreamItem {
        if (stream.addonId == BuiltInUsenet.GROUP_ID || stream.needsUsenetPreparation) return stream
        if (!stream.isScorableStream) return stream
        val traits = StreamTraitDetector.detect(stream)
        val rawFilename = stream.behaviorHints.filename?.takeIf { it.isNotBlank() }
        val releaseName = rawFilename
            ?: stream.description?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
            ?: stream.name?.trim().orEmpty()
        val sourceLabel = stream.addonName.takeIf { it.isNotBlank() }
        val lines = listOf(
            titleLine(subject, releaseName, kept = false),
            pictureLine(traits, age = null, indexer = sourceLabel),
            groupAudioLine(traits, releaseName),
            rawFilename.takeIf { showReleaseName },
        ).filterNot { it.isNullOrBlank() }
        return stream.copy(
            name = header(traits, fallbackLabel = sourceLabel ?: "Stream"),
            description = lines.joinToString("\n").ifBlank { stream.description },
        )
    }

    /** The source's emoji and the resolution: "📀 2160p". */
    fun header(traits: StreamTraits, fallbackLabel: String = "Usenet"): String {
        val emoji = when (traits.quality) {
            DebridStreamQuality.BLURAY_REMUX -> "📀"
            DebridStreamQuality.BLURAY -> "💿"
            DebridStreamQuality.WEB_DL -> "🌐"
            DebridStreamQuality.WEBRIP -> "🕸️"
            DebridStreamQuality.UNKNOWN -> "❔"
            else -> "📼"
        }
        val resolution = traits.resolution.takeUnless { it == DebridStreamResolution.UNKNOWN }?.label
        return listOfNotNull(emoji, resolution ?: fallbackLabel).joinToString(" ")
    }

    /**
     * "⚡Midsommar | 2019", or "⚡The Bear | S02 E05" (a pack: "S02"). A release whose NZB the engine
     * already keeps ([kept]) starts with a book instead: it is in your library and opens at once.
     */
    fun titleLine(subject: Subject, releaseName: String, kept: Boolean = false): String? {
        val title = subject.title?.trim()?.takeIf { it.isNotEmpty() }?.let(::truncate)
        val detail = if (subject.series) seasonEpisode(releaseName) else subject.year?.toString()
        if (title == null && detail == null) return null
        return (if (kept) KEPT else FRESH) + listOfNotNull(title, detail).joinToString(" | ")
    }

    const val FRESH = "⚡"
    const val KEPT = "📖"

    /** The picture's emoji and kind, then the age and the indexer: "🌞 DV · HDR10+ | 9 months | AltHub". */
    fun pictureLine(traits: StreamTraits, age: String?, indexer: String?): String {
        val tags = traits.visualTags
        val dv = tags.any { it == DebridStreamVisualTag.DV || it == DebridStreamVisualTag.HDR_DV || it == DebridStreamVisualTag.DV_ONLY }
        val hdr10Plus = DebridStreamVisualTag.HDR10_PLUS in tags
        val hdr = hdr10Plus || tags.any {
            it == DebridStreamVisualTag.HDR || it == DebridStreamVisualTag.HDR10 ||
                it == DebridStreamVisualTag.HDR_DV || it == DebridStreamVisualTag.HDR_ONLY || it == DebridStreamVisualTag.HLG
        }
        val picture = when {
            dv && hdr -> "${if (hdr10Plus) "🌞" else "☀️"} DV · HDR"
            dv -> "☀️ DV"
            hdr -> "${if (hdr10Plus) "🌞" else "☀️"} HDR"
            else -> "🌑 SDR"
        }
        return listOfNotNull(picture, age, indexer?.takeIf { it.isNotBlank() }).joinToString(" | ")
    }

    /** The group's tier medal (💩 a known bad group, 🥷 unknown) and name, then the audio. */
    fun groupAudioLine(traits: StreamTraits, releaseName: String): String {
        val group = releaseGroup(releaseName)
        val tier = group?.let(StreamReleaseGroups::tierOf) ?: traits.groupTier
        val medal = when {
            tier == 1 -> "🥇"
            tier == 2 -> "🥈"
            tier == 3 -> "🥉"
            tier != null -> "🏅"
            traits.isLowQualityGroup || (group != null && StreamReleaseGroups.isLowQuality(group)) -> "💩"
            else -> "🥷"
        }
        val audio = (bestAudio(traits.audioTags) + traits.audioChannels.map { it.label })
            .filterNot { it.equals("Unknown", ignoreCase = true) }
        return buildString {
            append(medal)
            group?.let { append(' ').append(it) }
            if (audio.isNotEmpty()) append(" 🎧 ").append(audio.joinToString(" • "))
        }
    }

    /** "DTS-HD MA" alone, not "DTS-HD MA • DTS-HD • DTS": a tag that is the start of another is dropped. */
    fun bestAudio(tags: List<DebridStreamAudioTag>): List<String> {
        val labels = tags.filterNot { it == DebridStreamAudioTag.UNKNOWN }.map { it.label }.distinct()
        return labels.filterNot { label -> labels.any { other -> other != label && other.startsWith(label) } }
    }

    /** The uploader's tag, as written; null when the name gives none. See [ReleaseGroupName]. */
    fun releaseGroup(releaseName: String): String? = ReleaseGroupName.of(releaseName)

    /** "292 days", "9 months", "3 years"; null when the indexer gave no date. */
    fun ageLabel(publishedAt: Long, now: Long): String? {
        if (publishedAt <= 0) return null
        val days = ((now - publishedAt) / 86_400_000L).coerceAtLeast(0)
        return when {
            days < 1 -> "today"
            days == 1L -> "1 day"
            days < 60 -> "$days days"
            days < 730 -> "${days / 30} months"
            else -> "${days / 365} years"
        }
    }

    private fun seasonEpisode(releaseName: String): String? {
        SEASON_EPISODE.find(releaseName)?.let { match ->
            val season = match.groupValues[1].toInt()
            val episode = match.groupValues[2].toInt()
            return "S${two(season)} E${two(episode)}"
        }
        val seasons = NewznabProtocol.packSeasons(releaseName.lowercase())
        return seasons.takeIf { it.isNotEmpty() }?.joinToString(" ") { "S${two(it)}" }
    }

    private fun truncate(text: String): String =
        if (text.length <= TITLE_LIMIT) text else text.take(TITLE_LIMIT - 1).trimEnd() + "…"

    private fun two(value: Int) = value.toString().padStart(2, '0')

    private val SEASON_EPISODE = Regex("(?i)\\bS(\\d{1,2})[ ._-]?E(\\d{1,3})\\b")
}
