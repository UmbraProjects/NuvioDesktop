package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.MusicArtistRef
import com.nuvio.app.features.music.MusicHistoryItem
import com.nuvio.app.features.music.MusicTrack
import com.nuvio.app.features.music.parseHistory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MusicPlayLogTest {
    private fun play(at: Long, album: String?, source: String? = album, kind: String? = "Album", title: String = "t$at") =
        PlayEntry(atMs = at, videoId = "v$at", title = title, albumBrowseId = album, albumTitle = album, sourceId = source, sourceTitle = source, sourceKind = kind)

    @Test
    fun lastPlayedAndCountsAreByAlbum() {
        val log = listOf(play(30, "MPREb_a"), play(20, "MPREb_b"), play(10, "MPREb_a"))
        assertEquals(mapOf("MPREb_a" to 30L, "MPREb_b" to 20L), lastPlayedByAlbum(log))
        assertEquals(mapOf("MPREb_a" to 2, "MPREb_b" to 1), playsByAlbum(log))
    }

    @Test
    fun recentPlaysListEachAlbumOrQueueOnceNewestFirst() {
        val log = listOf(
            play(50, "MPREb_x", source = "list:RDTMAK5uy_discover", kind = "Radio"),
            play(40, "MPREb_a"),
            play(30, "MPREb_y", source = "list:RDTMAK5uy_discover", kind = "Radio"),
            play(20, "MPREb_a"),
            play(10, null, source = "radio:abcdefghijk", kind = "Radio"),
            play(5, null, source = "home:remixes", kind = "Mix"),
        )
        val recent = recentPlays(log)
        assertEquals(listOf("queue:list:RDTMAK5uy_discover", "album:MPREb_a", "queue:radio:abcdefghijk"), recent.map { it.key })
        val mix = recent.first() as RecentPlay.Queue
        assertEquals("RDTMAK5uy_discover", mix.playlistId)
        assertTrue((recent[2] as RecentPlay.Queue).playlistId == null, "a song radio has no list id")
    }

    private fun albumRun(album: String?): String =
        if (album == null) "" else "{\"text\":\"Album\",\"navigationEndpoint\":{\"browseEndpoint\":{\"browseId\":\"" + album + "\"}}}"

    private fun row(id: String, title: String, album: String? = null) =
        """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"$id"},"flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$title"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Prince","navigationEndpoint":{"browseEndpoint":{"browseId":"UCprince"}}}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[${albumRun(album)}]}}}
        ]}}"""

    private fun shelf(title: String, vararg rows: String) =
        """{"musicShelfRenderer":{"title":{"runs":[{"text":"$title"}]},"contents":[${rows.joinToString(",")}]}}"""

    @Test
    fun historyPageReadsEachHeadingsSongs() {
        val body = """{"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
            ${shelf("Today", row("aaaaaaaaaaa", "Kiss", "MPREb_pr"), row("bbbbbbbbbbb", "1999"))},
            ${shelf("Yesterday", row("ccccccccccc", "Purple Rain", "MPREb_pr"))}
        ]}}}}]}}}"""
        val page = parseHistory(body)!!
        assertEquals(listOf("Today" to "Kiss", "Today" to "1999", "Yesterday" to "Purple Rain"), page.items.map { it.section to it.track.title })
        assertEquals("MPREb_pr", page.items.first().track.albumBrowseId)
        assertEquals(null, parseHistory("""{"contents":{}}"""))
    }

    private fun item(section: String, id: String) =
        MusicHistoryItem(section, MusicTrack(id, "t$id", listOf(MusicArtistRef("Prince")), 0, 1, true, albumBrowseId = "MPREb_pr"))

    @Test
    fun historyIsDatedByHeadingKeepsItsOrderAndImportsOnce() {
        val day = 24L * 60 * 60 * 1000
        val now = 100 * day
        val items = listOf(item("Today", "a"), item("Today", "b"), item("Yesterday", "c"))
        val first = historyEntries(items, now, emptyList())
        assertEquals(listOf("a", "b", "c"), first.map { it.videoId })
        assertEquals(now, first[0].atMs)
        assertTrue(first[0].atMs > first[1].atMs && first[1].atMs > first[2].atMs)
        assertEquals(now - day, first[2].atMs)
        assertTrue(historyEntries(items, now, first).isEmpty())
        // The same song a week earlier is another play.
        assertEquals(1, historyEntries(listOf(item("This week", "a")), now, first.filter { it.videoId == "a" }.map { it.copy(atMs = now - 8 * day) }).size)
    }
}
