package com.nuvio.app.features.iptv

import java.text.Normalizer

/*
 * fork:live-tv-on-now - one channel, not five. Providers list the same channel several times ("BBC One FHD",
 * "BBC One HD", "BBC One SD", "UK: BBC 1 (backup)"). With Settings > Live TV > On Now on, those become one row
 * whose other copies are tried by themselves when the one playing will not open or keeps dropping.
 * Pure Kotlin, tested by IptvChannelMergeTest.
 */

/** One channel as the viewer thinks of it: its copies in the playlist, the best one first. */
data class IptvMergedChannel(val sources: List<IptvChannel>) {
    /** The copy that plays first and that the row shows (name, number, logo). */
    val primary: IptvChannel get() = sources.first()

    /** The copies tried after [primary], in order. */
    val alternates: List<IptvChannel> get() = sources.drop(1)

    /** "FHD · 4 sources", "HD", "3 sources", or null when there is nothing to say. */
    fun sourcesLabel(): String? {
        val quality = IptvChannelMerge.qualityLabel(primary.name)
        val count = if (sources.size > 1) "${sources.size} sources" else null
        return listOfNotNull(quality, count).joinToString(" · ").takeIf { it.isNotEmpty() }
    }
}

object IptvChannelMerge {
    private val ACCENTS = Regex("""\p{Mn}+""")

    // A country code set off from the name: "UK: BBC One", "UK | BBC One", "[UK] BBC One", "|UK| BBC One", "UK - BBC One".
    private val PREFIX = Regex("""^\s*(?:\[\s*([a-z]{2,3})\s*]|\(\s*([a-z]{2,3})\s*\)|\|\s*([a-z]{2,3})\s*\||([a-z]{2,3})\s*[:|]|([a-z]{2,3})\s+[-–]\s)\s*""")
    private val CODEC_DOTS = Regex("""h\.(26[45])""")
    private val SPLIT = Regex("""[^a-z0-9+]+""")

    private val FHD = setOf("fhd", "1080", "1080p", "1080i")
    private val HD = setOf("hd", "720", "720p")
    private val SD = setOf("sd", "576", "576p", "480", "480p")
    private val HEVC = setOf("hevc", "h265")

    /** Marks of a spare copy: tried last, and no part of the channel's name. */
    private val SPARE = setOf("backup", "bkup", "alt", "raw", "low", "multi")

    /** Words that say how a copy is delivered, not which channel it is. */
    private val NOISE = FHD + HD + SD + HEVC + SPARE + setOf("h264", "50fps", "60fps")

    /** Ultra HD copies stay their own row: providers often run them only for events, with other programmes. */
    private val ULTRA = setOf("uhd", "4k", "8k", "2160", "2160p")

    private fun plain(name: String): String {
        // NFKD also turns the small raised letters providers like ("ᴴᴰ", "ᶠᴴᴰ") into ordinary ones.
        val folded = Normalizer.normalize(name, Normalizer.Form.NFKD).lowercase()
        return CODEC_DOTS.replace(ACCENTS.replace(folded, ""), "h$1").trim()
    }

    private fun tokens(plainName: String): List<String> = plainName.split(SPLIT).filter { it.isNotEmpty() }

    /** "FHD", "HD", "SD", "4K", or null when the name does not say. */
    fun qualityLabel(name: String): String? {
        val words = tokens(plain(name))
        return when {
            words.any { it in ULTRA } -> "4K"
            words.any { it in FHD } -> "FHD"
            words.any { it in HD } -> "HD"
            words.any { it in SD } -> "SD"
            else -> null
        }
    }

    /** Higher plays first: Full HD, HD, unmarked, SD; a spare or HEVC copy after its plain twin. */
    fun rank(name: String): Int {
        val words = tokens(plain(name))
        var rank = when {
            words.any { it in FHD } -> 30
            words.any { it in HD } -> 20
            words.any { it in SD } -> 10
            else -> 15
        }
        if (words.any { it in HEVC }) rank -= 1
        if (words.any { it in SPARE }) rank -= 5
        return rank
    }

    /**
     * What two copies of one channel share: the region and the name without quality marks. [categoryRegion] is the
     * region of the channel's category (IptvRegions), which wins over a prefix in the name; "+1" stays part of the
     * name, so a timeshifted channel is never merged with the original. Null for a channel with no usable name.
     */
    fun mergeKey(name: String, categoryRegion: String?): String? {
        var text = plain(name)
        var prefixRegion: String? = null
        PREFIX.find(text)?.let { match ->
            val code = match.groupValues.drop(1).first { it.isNotEmpty() }
            val region = IptvRegions.regionOf("${code.uppercase()} | x")
            if (region != null) {
                prefixRegion = region
                text = text.substring(match.range.last + 1)
            }
        }
        val words = tokens(text).filter { it !in NOISE }.map { if (it in ULTRA) "4k" else it }
        if (words.none { word -> word.any { it.isLetterOrDigit() } }) return null
        return (categoryRegion ?: prefixRegion).orEmpty() + "|" + words.joinToString("")
    }

    /**
     * [channels] with the copies of one channel folded together, in the order each channel first appears.
     * [regionOf]: the region of a channel's category, if it has one.
     */
    fun merge(channels: List<IptvChannel>, regionOf: (IptvChannel) -> String? = { null }): List<IptvMergedChannel> {
        val groups = LinkedHashMap<String, MutableList<IptvChannel>>()
        for (channel in channels) {
            val key = mergeKey(channel.name, regionOf(channel)) ?: "#${channel.uid}"
            groups.getOrPut(key) { ArrayList(1) } += channel
        }
        return groups.values.map { copies ->
            // sortedByDescending is stable: equal copies keep the playlist's order.
            IptvMergedChannel(if (copies.size == 1) copies else copies.sortedByDescending { rank(it.name) })
        }
    }
}
