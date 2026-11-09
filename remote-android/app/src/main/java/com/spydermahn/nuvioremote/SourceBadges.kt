package com.spydermahn.nuvioremote

/** One chip on a source card ("4K", "Dolby Vision", "HEVC"). [key] is what the filter row matches on. */
data class SourceBadge(val label: String, val key: String)

/** What a source's name and release text say about it, read once so the card can show badges and the filter row can match. */
data class SourceFacts(
    val resolution: SourceBadge?,
    val hdr: SourceBadge?,
    val codec: SourceBadge?,
    val extras: List<SourceBadge>,
) {
    /** The badges in the order the card draws them. */
    val all: List<SourceBadge> get() = listOfNotNull(resolution, hdr, codec) + extras
}

object SourceBadges {
    const val FOUR_K = "4k"
    const val HDR = "hdr"
    const val DOLBY_VISION = "dv"

    private val fourK = Regex("""\b(2160p|4K|UHD)\b""", RegexOption.IGNORE_CASE)
    private val dolbyVision = Regex("""\b(DV|DoVi|Dolby[ .]?Vision)\b""", RegexOption.IGNORE_CASE)
    private val hdrPlus = Regex("""\bHDR10\+""", RegexOption.IGNORE_CASE)
    private val hdr10 = Regex("""\bHDR10\b""", RegexOption.IGNORE_CASE)
    private val hdrAny = Regex("""\bHDR\b""", RegexOption.IGNORE_CASE)
    private val hevc = Regex("""\b(HEVC|x265|H[ .]?265)\b""", RegexOption.IGNORE_CASE)
    private val avc = Regex("""\b(x264|H[ .]?264|AVC)\b""", RegexOption.IGNORE_CASE)
    private val av1 = Regex("""\bAV1\b""", RegexOption.IGNORE_CASE)
    private val atmos = Regex("""\bAtmos\b""", RegexOption.IGNORE_CASE)
    private val remux = Regex("""\bREMUX\b""", RegexOption.IGNORE_CASE)

    /** Reads [name] and [detail] (the add-on's lines, release name included) for resolution, HDR and codec. Unknown parts are simply missing. */
    fun read(name: String, detail: String?): SourceFacts {
        val text = (name + "\n" + (detail ?: "")).replace('.', ' ').replace('_', ' ')
        val resolution = when {
            fourK.containsMatchIn(text) -> SourceBadge("4K", FOUR_K)
            Regex("""\b1080p\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> SourceBadge("1080p", "1080p")
            Regex("""\b720p\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> SourceBadge("720p", "720p")
            Regex("""\b480p\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> SourceBadge("480p", "480p")
            else -> null
        }
        val hdr = when {
            dolbyVision.containsMatchIn(text) -> SourceBadge("Dolby Vision", DOLBY_VISION)
            hdrPlus.containsMatchIn(text) -> SourceBadge("HDR10+", HDR)
            hdr10.containsMatchIn(text) -> SourceBadge("HDR10", HDR)
            hdrAny.containsMatchIn(text) -> SourceBadge("HDR", HDR)
            else -> null
        }
        val codec = when {
            av1.containsMatchIn(text) -> SourceBadge("AV1", "av1")
            hevc.containsMatchIn(text) -> SourceBadge("HEVC", "hevc")
            avc.containsMatchIn(text) -> SourceBadge("H.264", "h264")
            else -> null
        }
        val extras = buildList {
            if (remux.containsMatchIn(text)) add(SourceBadge("Remux", "remux"))
            if (atmos.containsMatchIn(text)) add(SourceBadge("Atmos", "atmos"))
        }
        return SourceFacts(resolution, hdr, codec, extras)
    }

    /** The release name for the collapsed line: the add-on's detail without its emoji lines (size, seeders), joined on one line. */
    fun releaseName(detail: String?): String? {
        val lines = (detail ?: return null).split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.none { c -> Character.getType(c) == Character.OTHER_SYMBOL.toInt() || c.isSurrogate() } }
        return lines.joinToString(" ").takeIf { it.isNotBlank() }
    }

    /** True when the filter [key] ("4k", "hdr", "dv") matches; HDR matches Dolby Vision too, since DV is an HDR format. */
    fun matches(facts: SourceFacts, key: String): Boolean = when (key) {
        FOUR_K -> facts.resolution?.key == FOUR_K
        HDR -> facts.hdr != null
        DOLBY_VISION -> facts.hdr?.key == DOLBY_VISION
        else -> true
    }
}
