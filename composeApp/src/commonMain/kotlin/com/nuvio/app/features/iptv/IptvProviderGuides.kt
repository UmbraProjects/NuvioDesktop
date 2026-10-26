package com.nuvio.app.features.iptv

/**
 * Free guides for channels of the free streaming services Matt Huisman's i.mjh.nz republishes,
 * used for channels the playlist's own guide does not cover.
 *
 * iptv-org's playlist carries over a thousand Pluto TV channels and no guide. Their stream URLs go
 * through i.mjh.nz's redirector (`https://jmp2.uk/plu-<Pluto channel id>.m3u8`), and the same site
 * publishes each service's XMLTV keyed by exactly that id: 1,130 of the playlist's 1,158 Pluto
 * channels and all 10 of its Samsung TV Plus ones matched when this was written. The id comes from
 * the stream URL, not `tvg-id`, which iptv-org names its own way.
 *
 * Each guide is tried from i.mjh.nz (which redirects to GitHub) and then from the jsDelivr mirror
 * of the same repository, for networks that block GitHub.
 */
internal object IptvProviderGuides {
    class Source(
        /** Short name, in cache file names and logs. */
        val key: String,
        private val path: String,
        private val streamUrl: Regex,
    ) {
        val urls: List<String> = listOf(
            "https://i.mjh.nz/$path",
            "https://cdn.jsdelivr.net/gh/matthuisman/i.mjh.nz@master/$path",
        )

        /** The service's channel id in [url], the key its guide uses; null for any other stream. */
        fun channelId(url: String?): String? = url?.trim()?.let { streamUrl.find(it)?.groupValues?.get(1) }
    }

    val sources = listOf(
        Source("pluto", "PlutoTV/all.xml.gz", Regex("""^https?://(?:jmp2\.uk|i\.mjh\.nz)/plu-([0-9a-fA-F]{24})\b""")),
        Source("samsung", "SamsungTVPlus/all.xml.gz", Regex("""^https?://(?:jmp2\.uk|i\.mjh\.nz)/stvp-([A-Za-z0-9]+)\b""")),
    )

    /** [channels] of [source], each with the service's id as its guide id; empty when there are none. */
    fun channelsFor(source: Source, channels: List<IptvChannel>): List<IptvChannel> =
        channels.mapNotNull { channel -> source.channelId(channel.url)?.let { channel.copy(epgId = it) } }

    /** [channel] as [source]'s guide knows it, or null when it is not one of that service's. */
    fun asGuideChannel(source: Source, channel: IptvChannel): IptvChannel? =
        source.channelId(channel.url)?.let { channel.copy(epgId = it) }
}
