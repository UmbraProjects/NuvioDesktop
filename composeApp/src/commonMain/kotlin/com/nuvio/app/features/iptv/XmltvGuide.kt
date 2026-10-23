package com.nuvio.app.features.iptv

import java.text.Normalizer
import kotlinx.serialization.Serializable

/**
 * A playlist's XMLTV guide, already cut down to that playlist's channels and a window around now.
 * [programmes]: XMLTV channel id (lower case) → programmes in start order.
 * [names]: normalised display name → id, for channels with no `tvg-id` (or one the guide lacks).
 *
 * Ported from GoatTV's `XmltvGuide`.
 */
@Serializable
class XmltvGuide(
    val url: String,
    val fetchedAt: Long,
    val programmes: Map<String, List<IptvEpgEntry>>,
    val names: Map<String, String>,
) {
    fun entriesFor(channel: IptvChannel): List<IptvEpgEntry> {
        val id = channel.epgId?.lowercase()?.takeIf { it in programmes }
            ?: names[normalizeName(channel.name)]
            ?: return emptyList()
        // Also cleans guides cached before build() did (see withoutOverlaps); cheap per channel.
        return programmes[id].orEmpty().withoutOverlaps()
    }

    companion object {
        /** Past programmes kept, for the guide grid (and catch-up later). */
        const val WINDOW_BEFORE_MS = 24 * 3600_000L
        const val WINDOW_AFTER_MS = 36 * 3600_000L

        private val PREFIX = Regex("""^[a-z]{2,3}\s*[:|]\s*""")
        private val QUALITY = Regex("""\b(uhd|fhd|hd|sd|4k|hevc|h265|1080p?|720p?)\b""")
        private val NON_ALNUM = Regex("""[^a-z0-9]""")
        private val ACCENTS = Regex("""\p{Mn}+""")

        /** "PT: RTP 1 HD" → "rtp1": the same channel spelt differently in the playlist and the guide. */
        fun normalizeName(name: String): String {
            val plain = ACCENTS.replace(Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD), "")
            return NON_ALNUM.replace(QUALITY.replace(PREFIX.replace(plain.trim(), ""), ""), "")
        }

        /**
         * Reads the XMLTV keeping only the programmes of [channels] between [fromMs] and [toMs].
         * Ids compare lower-cased (playlists don't always keep the guide's case).
         */
        fun build(
            url: String,
            channels: List<IptvChannel>,
            fromMs: Long,
            toMs: Long,
            fetchedAt: Long,
            parse: (XmltvParser) -> Unit,
        ): XmltvGuide {
            val wantedIds = channels.mapNotNullTo(HashSet()) { it.epgId?.lowercase() }
            // Only channels with no programmes by id need a name match; that is settled at the end.
            val channelNames = channels.mapTo(HashSet()) { normalizeName(it.name) }.apply { remove("") }
            val names = HashMap<String, String>()
            val nameIds = HashSet<String>()
            val programmes = HashMap<String, ArrayList<IptvEpgEntry>>()
            val titles = HashMap<String, String>() // titles repeat a lot: one String per title

            val parser = XmltvParser(
                onChannel = { id, displayNames ->
                    val lower = id.lowercase()
                    for (n in displayNames) {
                        val key = normalizeName(n)
                        if (key in channelNames && key !in names) {
                            names[key] = lower
                            nameIds += lower
                        }
                    }
                },
                wantsProgramme = { ch, start, end ->
                    end > fromMs && start < toMs && ch.lowercase().let { it in wantedIds || it in nameIds }
                },
                onProgramme = { ch, start, end, title ->
                    programmes.getOrPut(ch.lowercase()) { ArrayList() } += IptvEpgEntry(titles.getOrPut(title) { title }, start, end)
                },
            )
            parse(parser)
            val cleaned = programmes.mapValues { (_, list) -> list.withoutOverlaps() }
            return XmltvGuide(url, fetchedAt, cleaned, names.filterValues { it in programmes })
        }
    }
}
