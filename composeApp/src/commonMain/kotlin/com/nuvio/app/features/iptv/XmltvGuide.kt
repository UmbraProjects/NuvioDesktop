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
        val id = epgIdCandidates(channel.epgId).firstOrNull { it in programmes }
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

        /**
         * The guide ids a channel's `tvg-id` may appear under, lower case, best first: as given,
         * then without an iptv-org feed suffix (`BBCOne.uk@SD` → `bbcone.uk`). iptv-org playlists
         * name the feed; the guides built from the same database (iptv-epg.org and others) do not.
         */
        fun epgIdCandidates(epgId: String?): List<String> {
            val id = epgId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return emptyList()
            val base = id.substringBefore('@')
            return if ('@' in id && base.isNotEmpty()) listOf(id, base) else listOf(id)
        }

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
            val wantedIds = channels.flatMapTo(HashSet()) { epgIdCandidates(it.epgId) }
            // Only channels with no programmes by id need a name match; that is settled at the end.
            val channelNames = channels.mapTo(HashSet()) { normalizeName(it.name) }.apply { remove("") }
            val names = HashMap<String, String>()
            val nameIds = HashSet<String>()
            val programmes = HashMap<String, ArrayList<IptvEpgEntry>>()
            val titles = HashMap<String, String>() // titles repeat a lot: one String per title
            var kind: String? = null // of the programme being read, from its categories
            // fork:live-tv-stage - its details, kept only near now so a 16,000-channel guide stays small in memory.
            val detailFrom = fetchedAt - 12 * 3600_000L
            val detailTo = fetchedAt + 18 * 3600_000L
            val texts = HashMap<String, String>() // descriptions repeat (loops, repeats): one String per text
            var desc: String? = null
            var episode: String? = null
            var image: String? = null
            var year: Int? = null
            var flags = 0

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
                    kind = null
                    desc = null
                    episode = null
                    image = null
                    year = null
                    flags = 0
                    end > fromMs && start < toMs && ch.lowercase().let { it in wantedIds || it in nameIds }
                },
                onProgramme = { ch, start, end, title ->
                    val clean = IptvGuideText.clean(title)
                    val near = start in detailFrom..detailTo
                    if (clean.isNew) flags = flags or IptvGuideText.FLAG_NEW
                    if (clean.isLive) flags = flags or IptvGuideText.FLAG_LIVE
                    programmes.getOrPut(ch.lowercase()) { ArrayList() } += IptvEpgEntry(
                        title = titles.getOrPut(clean.name) { clean.name },
                        startMs = start,
                        endMs = end,
                        kind = kind,
                        desc = if (near) desc?.let { d -> texts.getOrPut(d) { d } } else null,
                        episode = episode ?: clean.episode,
                        image = if (near) image else null,
                        year = year,
                        flags = flags,
                    )
                    kind = null
                },
                onCategory = { category -> if (kind == null) kind = IptvProgrammeKind.of(category) },
                onDetail = { name, attrs, text ->
                    when (name) {
                        "desc" -> if (desc == null && text != null) desc = IptvGuideText.shortDesc(text)
                        "episode-num" -> if (text != null) IptvGuideText.episode(attrs["system"], text)?.let { if (episode == null || attrs["system"] == "xmltv_ns") episode = it }
                        "date" -> if (text != null) year = IptvGuideText.year(text)
                        "icon" -> attrs["src"]?.trim()?.takeIf { it.startsWith("http") }?.let { image = it }
                        "new", "premiere" -> flags = flags or IptvGuideText.FLAG_NEW
                        "live" -> flags = flags or IptvGuideText.FLAG_LIVE
                    }
                },
            )
            parse(parser)
            val cleaned = programmes.mapValues { (_, list) -> list.withoutOverlaps() }
            return XmltvGuide(url, fetchedAt, cleaned, names.filterValues { it in programmes })
        }
    }
}
