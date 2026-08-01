package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Season-pack detection, driven through [StreamTraitDetector] with the field shapes real addons
 * actually produce — a multi-line title, a separately resolved file name, a description that labels
 * the row. The distinction these lock down is *scoping*: what the backing torrent holds is read from
 * the source's own name, never from the name of one file resolved out of it.
 */
class StreamSeasonPackDetectionTest {

    private fun stream(
        name: String,
        title: String? = null,
        description: String? = null,
        filename: String? = null,
    ) = StreamItem(
        name = name,
        title = title,
        description = description,
        addonName = "addon",
        addonId = "addon",
        url = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
        behaviorHints = StreamBehaviorHints(filename = filename),
    )

    private fun traits(stream: StreamItem) = StreamTraitDetector.detect(stream)

    // --- Resolved file names must not veto the pack ---

    @Test
    fun `a pack stays a pack once the addon resolves one episode out of it`() {
        // The shape that used to break: torrent name on line 1, the resolved file on line 2. The
        // single-episode marker in the file name vetoed the whole detection, so the same torrent
        // was a pack before a file was resolved and not a pack afterwards.
        val resolved = traits(
            stream(
                name = "Torrentio\n4k",
                title = "Show.S01.2160p.WEB-DL.DDP5.1.HEVC-NTb\n" +
                    "Show.S01E03.2160p.WEB-DL.DDP5.1.HEVC-NTb.mkv\n" +
                    "👤 42 💾 58.4 GB",
                filename = "Show.S01E03.2160p.WEB-DL.DDP5.1.HEVC-NTb.mkv",
            ),
        )
        val unresolved = traits(
            stream(
                name = "Torrentio",
                title = "Show.S01.2160p.WEB-DL.DDP5.1.HEVC-NTb\n👤 42 💾 58.4 GB",
            ),
        )

        assertTrue(resolved.isSeasonPack)
        assertTrue(unresolved.isSeasonPack)
        assertEquals(setOf(1), resolved.packSeasons)
    }

    @Test
    fun `an explicit pack label still wins over a resolved episode filename`() {
        val item = stream(
            name = "Station.Eleven.S01E01.2160p.STAN.WEB-DL.DDP5.1.HDR.HEVC-DB.mkv",
            description = "Q3 | Season Pack | Stan | 4.99 GB",
            filename = "Station.Eleven.S01E01.2160p.STAN.WEB-DL.DDP5.1.HDR.HEVC-DB.mkv",
        )

        assertTrue(traits(item).isSeasonPack)
    }

    @Test
    fun `a complete series is a pack and names no season`() {
        val item = stream(
            name = "Show.Complete.Series.1080p.BluRay.x264-GROUP",
            filename = "Show.S02E07.1080p.BluRay.x264-GROUP.mkv",
        )

        val detected = traits(item)
        assertTrue(detected.isSeasonPack)
        assertTrue(detected.packSeasons.isEmpty())
    }

    // --- Single episodes stay single episodes ---

    @Test
    fun `a plain single episode is not a pack`() {
        assertFalse(traits(stream("Show.S01E05.1080p.WEB-DL.x264-NTb")).isSeasonPack)
        assertFalse(traits(stream("Show.S01.E05.1080p.WEB-DL.x264-NTb")).isSeasonPack)
    }

    @Test
    fun `separator-only episode numbering is not a pack`() {
        // "S01 - 05" and "S01.05" carry no "E", so they used to read as a bare season token.
        assertFalse(traits(stream("Show.S01 - 05.720p.WEB-DL.x264")).isSeasonPack)
        assertFalse(traits(stream("Show.S01.05.720p.WEB-DL.x264")).isSeasonPack)
        assertFalse(traits(stream("Show S01 05 1080p WEB-DL x264")).isSeasonPack)
    }

    @Test
    fun `a resolution or year after the season token is not an episode number`() {
        assertTrue(traits(stream("Show.S01.1080p.WEB-DL.x264-GROUP")).isSeasonPack)
        assertTrue(traits(stream("Show.S01.2160p.WEB-DL.x265-GROUP")).isSeasonPack)
        assertTrue(traits(stream("Show.S02.2024.1080p.WEB-DL-GROUP")).isSeasonPack)
    }

    // --- Which seasons a pack covers ---

    @Test
    fun `season ranges expand to every season they cover`() {
        assertEquals(setOf(1, 2, 3), traits(stream("Show.S01-S03.1080p.BluRay-GROUP")).packSeasons)
        // Tight hyphen with no spaces is the range form; the spaced form is an episode.
        assertEquals(setOf(1, 2, 3), traits(stream("Show.S01-03.1080p.BluRay-GROUP")).packSeasons)
    }

    @Test
    fun `a worded season and an episode range both name their season`() {
        assertEquals(setOf(2), traits(stream("Show.Season.2.1080p.WEB-DL-GROUP")).packSeasons)
        assertEquals(setOf(1), traits(stream("Show.S01E01-E12.1080p.WEB-DL-GROUP")).packSeasons)
    }

    @Test
    fun `a bare season pack label names no season`() {
        val item = stream(name = "Show 1080p WEB-DL", description = "Season Pack | 24.1 GB")

        val detected = traits(item)
        assertTrue(detected.isSeasonPack)
        assertTrue(detected.packSeasons.isEmpty())
    }

    // --- Structural evidence from the addon's own fields ---

    /** An AIOStreams-shaped row: the chosen file, plus the folder that contains it. */
    private fun resolvedStream(
        filename: String,
        fileSize: Long? = null,
        folderSize: Long? = null,
        seasons: List<Int> = emptyList(),
        episodes: List<Int> = emptyList(),
        torrentName: String? = null,
        seasonPack: Boolean? = null,
        folderSeasons: List<Int> = emptyList(),
        folderEpisodes: List<Int> = emptyList(),
    ) = stream(name = "Torz\n720p", filename = filename).copy(
        clientResolve = StreamClientResolve(
            type = "debrid",
            service = "torbox",
            isCached = true,
            stream = StreamClientResolveStream(
                raw = StreamClientResolveRaw(
                    torrentName = torrentName,
                    filename = filename,
                    size = fileSize,
                    folderSize = folderSize,
                    parsed = StreamClientResolveParsed(
                        seasons = seasons,
                        episodes = episodes,
                        seasonPack = seasonPack,
                        folderSeasons = folderSeasons,
                        folderEpisodes = folderEpisodes,
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `a folder much larger than the chosen file is a pack`() {
        // The reported case: nothing in any name says "pack" — the file is honestly S01E01 — but the
        // addon reports a 207 MB file inside a 2.0 GB folder, so the rest of the season is in there.
        val item = resolvedStream(
            filename = "The.Girl.in.the.Mirror.S01E01.SPANISH.720p.NF.WEBRip.x264-GalaxyTV.mkv",
            fileSize = 207L * 1024 * 1024,
            folderSize = 2L * 1024 * 1024 * 1024,
            seasons = listOf(1),
            episodes = listOf(1),
        )

        val detected = traits(item)
        assertTrue(detected.isSeasonPack)
        assertEquals(setOf(1), detected.packSeasons)
    }

    @Test
    fun `a folder barely larger than the file is not a pack`() {
        // Subtitles, artwork and an nfo alongside a single episode — not another episode.
        val item = resolvedStream(
            filename = "Show.S01E01.1080p.WEB-DL.x264-GROUP.mkv",
            fileSize = 2_000L * 1024 * 1024,
            folderSize = 2_040L * 1024 * 1024,
            seasons = listOf(1),
            episodes = listOf(1),
        )

        assertFalse(traits(item).isSeasonPack)
    }

    @Test
    fun `a single-file torrent is not a pack`() {
        val item = resolvedStream(
            filename = "Show.S01E01.1080p.WEB-DL.x264-GROUP.mkv",
            fileSize = 2_000L * 1024 * 1024,
            folderSize = 2_000L * 1024 * 1024,
            seasons = listOf(1),
            episodes = listOf(1),
        )

        assertFalse(traits(item).isSeasonPack)
    }

    @Test
    fun `a counted file listing settles it either way`() {
        // From Torbox checkcached?list_files=true — a fact about the torrent, not a reading of a
        // name, so it outranks a filename that says single episode.
        val base = stream(
            name = "Comet 2160p",
            filename = "Show.S01E01.2160p.WEB-DL.x265-GROUP.mkv",
        )

        assertTrue(traits(base.copy(sourceVideoFileCount = 10)).isSeasonPack)
        assertFalse(traits(base.copy(sourceVideoFileCount = 1)).isSeasonPack)
        // Null is "nobody looked", not "one file" — the other signals still get their say.
        assertFalse(traits(base.copy(sourceVideoFileCount = null)).isSeasonPack)
    }

    @Test
    fun `the producer's own season-pack verdict is taken as given`() {
        // AIOStreams computes seasonPack from the folder AND the file and OR-s the two, so it stays
        // true for a pack whose selected file is a single episode. Trust it over our own reading.
        val item = resolvedStream(
            filename = "Show.S01E01.1080p.WEB-DL.x264-GROUP.mkv",
            fileSize = 2_000L * 1024 * 1024,
            folderSize = 2_000L * 1024 * 1024,
            seasons = listOf(1),
            episodes = listOf(1),
            seasonPack = true,
        )

        val detected = traits(item)
        assertTrue(detected.isSeasonPack)
        assertEquals(setOf(1), detected.packSeasons)
    }

    @Test
    fun `a folder naming a season with no episode is a pack`() {
        val item = resolvedStream(
            filename = "Show.S03E04.1080p.WEB-DL.x264-GROUP.mkv",
            fileSize = 2_000L * 1024 * 1024,
            folderSize = 2_000L * 1024 * 1024,
            seasons = listOf(3),
            episodes = listOf(4),
            folderSeasons = listOf(3),
            folderEpisodes = emptyList(),
        )

        val detected = traits(item)
        assertTrue(detected.isSeasonPack)
        assertEquals(setOf(3), detected.packSeasons)
    }

    @Test
    fun `a folder naming one episode is not a pack`() {
        val item = resolvedStream(
            filename = "Show.S03E04.1080p.WEB-DL.x264-GROUP.mkv",
            fileSize = 2_000L * 1024 * 1024,
            folderSize = 2_000L * 1024 * 1024,
            seasons = listOf(3),
            episodes = listOf(4),
            folderSeasons = listOf(3),
            folderEpisodes = listOf(4),
            seasonPack = false,
        )

        assertFalse(traits(item).isSeasonPack)
    }

    @Test
    fun `a parsed season with no parsed episode is a pack`() {
        val item = resolvedStream(
            filename = "Show.S02.1080p.WEB-DL.x264-GROUP",
            seasons = listOf(2),
            episodes = emptyList(),
        )

        val detected = traits(item)
        assertTrue(detected.isSeasonPack)
        assertEquals(setOf(2), detected.packSeasons)
    }

    @Test
    fun `a file spanning several parsed episodes is a pack`() {
        val item = resolvedStream(
            filename = "Show.S01E01E02.1080p.WEB-DL.x264-GROUP.mkv",
            seasons = listOf(1),
            episodes = listOf(1, 2),
        )

        assertTrue(traits(item).isSeasonPack)
    }

    @Test
    fun `structural evidence needs both sizes to fire`() {
        // A missing folderSize must not be read as a tiny folder.
        val item = resolvedStream(
            filename = "Show.S01E01.1080p.WEB-DL.x264-GROUP.mkv",
            fileSize = 2_000L * 1024 * 1024,
            folderSize = null,
            seasons = listOf(1),
            episodes = listOf(1),
        )

        assertFalse(traits(item).isSeasonPack)
    }

    // --- "Complete" and "season" in other languages ---

    @Test
    fun `a complete season is detected in other languages`() {
        // Romance languages put the adjective after the noun, German and Dutch before it.
        assertTrue(traits(stream("La.Casa.de.Papel.Temporada.1.Completa.1080p.WEB-DL")).isSeasonPack)
        assertTrue(traits(stream("Show.Serie.Completa.1080p.BluRay.x264")).isSeasonPack)
        assertTrue(traits(stream("Show.Stagione.Completa.1080p.WEB-DL")).isSeasonPack)
        assertTrue(traits(stream("Show.Saison.Complete.1080p.WEB-DL")).isSeasonPack)
        assertTrue(traits(stream("Show.Komplette.Staffel.1080p.WEB-DL")).isSeasonPack)
        assertTrue(traits(stream("Show.Volledige.Serie.1080p.WEB-DL")).isSeasonPack)
        assertTrue(traits(stream("Show.Integrale.Serie.1080p.WEB-DL")).isSeasonPack)
    }

    @Test
    fun `a season named in another language names its number`() {
        assertEquals(setOf(1), traits(stream("La.Casa.de.Papel.Temporada.1.1080p.WEB-DL")).packSeasons)
        assertEquals(setOf(3), traits(stream("Show.Staffel.3.1080p.WEB-DL-GROUP")).packSeasons)
        assertEquals(setOf(2), traits(stream("Show.Saison.2.1080p.WEB-DL-GROUP")).packSeasons)
        assertEquals(setOf(4), traits(stream("Show.Stagione.4.1080p.WEB-DL-GROUP")).packSeasons)
        assertEquals(setOf(2), traits(stream("Show.Sezonul.2.1080p.WEB-DL-GROUP")).packSeasons)
    }

    @Test
    fun `a foreign complete word alone does not declare a pack`() {
        // "Completa" needs a noun beside it — a title that merely contains the word is not a pack.
        assertFalse(traits(stream("La.Obra.Completa.2019.1080p.BluRay.x264-GROUP")).isSeasonPack)
        assertFalse(traits(stream("Integral.2020.1080p.WEB-DL.x264-GROUP")).isSeasonPack)
    }

    // --- Sizes the addon printed as text ---

    @Test
    fun `two printed sizes an order of magnitude apart are a pack`() {
        // A StremThru/Torz row: no clientResolve at all, so nothing structured to read. The file
        // size and the folder holding it exist only in the addon's own description text.
        val item = stream(
            name = "Torz\n4k",
            description = "BluRay REMUX HEVC\nDV HDR10 DTS Lossless | 5.1, stereo\n" +
                "💾 12 GB 📦 158 GB ⛰ 5.0 MB/s 👤 18\n" +
                "CoSMiC TorrentGalaxyClone\n" +
                "Station.Eleven.S01E01.2160p.UHD.Blu-ray.Remux.DV.HEVC.DTS-HD.MA.5.1-CoSMiC.mkv",
            filename = "Station.Eleven.S01E01.2160p.UHD.Blu-ray.Remux.DV.HEVC.DTS-HD.MA.5.1-CoSMiC.mkv",
        )

        assertTrue(traits(item).isSeasonPack)
    }

    @Test
    fun `the smaller printed pair is also a pack`() {
        val item = stream(
            name = "Torz\n4k",
            description = "BluRay HEVC\nDV HDR10 5.1\n💾 6.1 GB 📦 67 GB ⛰ 2.4 MB/s\nMiMiC\n" +
                "station.eleven.s01e01.2160p.uhd.bluray.x265-mimic.mkv",
            filename = "station.eleven.s01e01.2160p.uhd.bluray.x265-mimic.mkv",
        )

        assertTrue(traits(item).isSeasonPack)
    }

    @Test
    fun `mixed size units are compared correctly`() {
        val item = stream(
            name = "Torz\n720p",
            description = "💾 207 MB 📦 2.0 GB ⛰ 82 KB/s 👤 5\n" +
                "The.Girl.in.the.Mirror.S01E01.SPANISH.720p.NF.WEBRip.x264-GalaxyTV.mkv",
            filename = "The.Girl.in.the.Mirror.S01E01.SPANISH.720p.NF.WEBRip.x264-GalaxyTV.mkv",
        )

        assertTrue(traits(item).isSeasonPack)
    }

    @Test
    fun `one printed size repeated is not a pack`() {
        // A single-episode row echoes the same size; there is no second, larger one.
        val item = stream(
            name = "Torz\n1080p",
            description = "WEB-DL H264\n💾 3.2 GB ⛰ 1.1 MB/s 👤 41\nSIZE 3.2 GB\n" +
                "Show.S01E05.1080p.WEB-DL.x264-NTb.mkv",
            filename = "Show.S01E05.1080p.WEB-DL.x264-NTb.mkv",
        )

        assertFalse(traits(item).isSeasonPack)
    }

    @Test
    fun `a transfer rate is not read as a size`() {
        // 8 GB file, and a rate that would otherwise look like a huge second size.
        val item = stream(
            name = "Torz\n1080p",
            description = "💾 8 GB ⛰ 950 MB/s 👤 12\nShow.S01E05.1080p.WEB-DL.x264-NTb.mkv",
            filename = "Show.S01E05.1080p.WEB-DL.x264-NTb.mkv",
        )

        assertFalse(traits(item).isSeasonPack)
    }

    @Test
    fun `a folder only slightly larger than the file is not a pack`() {
        val item = stream(
            name = "Torz\n1080p",
            description = "💾 3.2 GB 📦 3.3 GB ⛰ 1.1 MB/s\nShow.S01E05.1080p.WEB-DL.x264-NTb.mkv",
            filename = "Show.S01E05.1080p.WEB-DL.x264-NTb.mkv",
        )

        assertFalse(traits(item).isSeasonPack)
    }

    @Test
    fun `structured sizes win over printed ones when both exist`() {
        // A structured pair that says "single file" must not be overridden by stray text sizes.
        val item = resolvedStream(
            filename = "Show.S01E05.1080p.WEB-DL.x264-GROUP.mkv",
            fileSize = 3_200L * 1024 * 1024,
            folderSize = 3_300L * 1024 * 1024,
            seasons = listOf(1),
            episodes = listOf(1),
        ).copy(description = "💾 3.2 GB 📦 158 GB")

        assertFalse(traits(item).isSeasonPack)
    }

    // --- The torrent name looked up from the debrid provider ---

    @Test
    fun `a looked-up torrent name marks a pack the filename hides`() {
        // A resolved Comet/StremThru row: every visible field names the one file the addon picked,
        // and only the provider's torrent name says S01.
        val item = stream(
            name = "Comet 2160p",
            description = "Station.Eleven.S01E01.2160p.UHD.Blu-ray.Remux.DV.HEVC.DTS-HD.MA.5.1-CoSMiC.mkv\n" +
                "hevc • DV | DTS Lossless • 5.1\nBluRay REMUX | CoSMiC\n💾 12.4 GB StremThru",
            filename = "Station.Eleven.S01E01.2160p.UHD.Blu-ray.Remux.DV.HEVC.DTS-HD.MA.5.1-CoSMiC.mkv",
        )

        assertFalse(traits(item).isSeasonPack)

        val named = item.copy(
            resolvedTorrentName = "Station Eleven S01 2160p UHD Blu ray Remux DV HEVC DTS HD MA 5 1",
        )
        val detected = traits(named)
        assertTrue(detected.isSeasonPack)
        assertEquals(setOf(1), detected.packSeasons)
    }

    @Test
    fun `the provider's torrent size is the pack size on already-resolved rows`() {
        // The AIOUmbra shape: every field describes the one file the addon picked, and the single
        // size it prints is that file's, so nothing but the provider's own measurement knows how
        // big the torrent is. Without it the pack icon shows with no number under it.
        val item = stream(
            name = "AIOUmbra 2160p",
            description = "Friends | S02 E06\nQ1 | Season Pack | 7.4 GB\n" +
                "Friends.S02E06.The.One.with.the.Baby.on.the.Bus.UHD.BluRay.2160p.mkv",
            filename = "Friends.S02E06.The.One.with.the.Baby.on.the.Bus.UHD.BluRay.2160p.mkv",
        )

        // A lone printed size is the file's, so it is not read as the folder's.
        assertNull(traits(item).packSizeBytes)

        val annotated = item.copy(sourceTotalSizeBytes = 158L * 1024 * 1024 * 1024)
        assertEquals(158L * 1024 * 1024 * 1024, traits(annotated).packSizeBytes)
    }

    @Test
    fun `the provider's torrent size outranks the addon's folder size`() {
        val item = resolvedStream(
            filename = "Show.S01E01.1080p.WEB-DL.x264-GROUP.mkv",
            fileSize = 2L * 1024 * 1024 * 1024,
            folderSize = 20L * 1024 * 1024 * 1024,
            seasons = listOf(1),
            episodes = listOf(1),
        ).copy(sourceTotalSizeBytes = 24L * 1024 * 1024 * 1024)

        assertEquals(24L * 1024 * 1024 * 1024, traits(item).packSizeBytes)
    }

    @Test
    fun `a provider size matching the file is not a pack`() {
        // Single-file torrent: the container measures the same as the file, so the size signal must
        // not turn every resolved row into a pack now that it has a number to compare.
        val item = stream(
            name = "AIOUmbra 1080p",
            filename = "Show.S01E05.1080p.WEB-DL.x264-GROUP.mkv",
        ).copy(
            behaviorHints = StreamBehaviorHints(
                filename = "Show.S01E05.1080p.WEB-DL.x264-GROUP.mkv",
                videoSize = 3L * 1024 * 1024 * 1024,
            ),
            sourceTotalSizeBytes = 3L * 1024 * 1024 * 1024,
        )

        assertFalse(traits(item).isSeasonPack)
    }

    @Test
    fun `a looked-up torrent name for a single episode stays a single episode`() {
        val item = stream(
            name = "Comet 2160p",
            filename = "Show.S01E05.2160p.WEB-DL.x265-GROUP.mkv",
        ).copy(resolvedTorrentName = "Show S01E05 2160p WEB DL x265 GROUP")

        assertFalse(traits(item).isSeasonPack)
    }

    // --- The magnet itself ---

    private fun magnetStream(
        magnet: String,
        name: String = "Torrentio\n1080p",
        filename: String? = null,
    ) = StreamItem(
        name = name,
        addonName = "addon",
        addonId = "addon",
        url = magnet,
        behaviorHints = StreamBehaviorHints(filename = filename),
    )

    @Test
    fun `the magnet display name names the torrent when no other field does`() {
        // The row labels itself with the file it selected; only `dn` says what the torrent is.
        val item = magnetStream(
            magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567" +
                "&dn=Show.S01.1080p.WEB-DL.DDP5.1.H.264-NTb&tr=udp%3A%2F%2Ftracker.example%3A80",
            name = "Torrentio\n1080p",
            filename = "Show.S01E04.1080p.WEB-DL.DDP5.1.H.264-NTb.mkv",
        )

        val detected = traits(item)
        assertTrue(detected.isSeasonPack)
        assertEquals(setOf(1), detected.packSeasons)
    }

    @Test
    fun `a magnet display name for a single episode is not a pack`() {
        val item = magnetStream(
            magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567" +
                "&dn=Show.S01E04.1080p.WEB-DL.DDP5.1.H.264-NTb",
        )

        assertFalse(traits(item).isSeasonPack)
    }

    @Test
    fun `a percent-encoded magnet display name is decoded before matching`() {
        // "Show S01 1080p …" written with the +/%20 encodings trackers actually emit.
        val item = magnetStream(
            magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567" +
                "&dn=Show+S01+Complete+Season+%5B1080p%5D",
        )

        assertTrue(traits(item).isSeasonPack)
    }

    @Test
    fun `a multi-byte magnet display name survives decoding`() {
        // %C3%A9 is a single character; decoding each escape alone would corrupt it.
        val item = magnetStream(
            magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567" +
                "&dn=Les%20Mis%C3%A9rables.S02.1080p.WEB-DL-GROUP",
        )

        val detected = traits(item)
        assertTrue(detected.isSeasonPack)
        assertEquals(setOf(2), detected.packSeasons)
    }

    @Test
    fun `a magnet exact length much larger than the file is a pack`() {
        val item = magnetStream(
            magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567" +
                "&dn=Some.Ambiguously.Named.Release&xl=21474836480",
            filename = "episode.mkv",
        ).copy(behaviorHints = StreamBehaviorHints(filename = "episode.mkv", videoSize = 2L * 1024 * 1024 * 1024))

        assertTrue(traits(item).isSeasonPack)
    }

    @Test
    fun `a magnet without a display name is handled`() {
        val item = magnetStream(magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567")

        assertFalse(traits(item).isSeasonPack)
    }

    @Test
    fun `an implausible season range is not expanded`() {
        // No show has 99 seasons, so the span is dropped rather than enumerated; the leading token
        // still names season 1, which is the only part of the string that means anything.
        assertEquals(setOf(1), traits(stream("Show.S01-99.1080p-GROUP")).packSeasons)
    }
}
