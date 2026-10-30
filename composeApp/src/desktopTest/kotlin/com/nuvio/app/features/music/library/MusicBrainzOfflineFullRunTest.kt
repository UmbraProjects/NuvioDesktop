package com.nuvio.app.features.music.library

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The whole offline copy, end to end: download (~3 GB), check, build, open, look up. Runs only when
 * `build/mb-full.flag` exists, into the test data folder; it takes 15-30 minutes.
 */
class MusicBrainzOfflineFullRunTest {
    @Test
    fun downloadsAndBuildsTheLatestDump() {
        if (!Files.exists(Paths.get("build/mb-full.flag"))) return
        val copy = DesktopMusicBrainzOffline
        val started = System.currentTimeMillis()
        copy.download()
        var last = ""
        var peakHeap = 0L
        while (true) {
            val state = copy.state.value
            val rt = Runtime.getRuntime()
            peakHeap = maxOf(peakHeap, rt.totalMemory() - rt.freeMemory())
            val line = when (state) {
                is MusicBrainzOfflineState.Downloading -> "downloading ${state.doneBytes / 100_000_000}/${state.totalBytes / 100_000_000}"
                is MusicBrainzOfflineState.Building -> "building ${(state.fraction * 100).toInt()}%"
                else -> state.toString()
            }
            if (line != last) {
                println("MB full [${(System.currentTimeMillis() - started) / 1000}s, heap ${peakHeap / 1_000_000} MB]: $line")
                last = line
            }
            if (state is MusicBrainzOfflineState.Ready && copy.index != null || state is MusicBrainzOfflineState.Failed) break
            Thread.sleep(2_000)
        }
        val ready = assertIs<MusicBrainzOfflineState.Ready>(copy.state.value)
        println("MB full: ready $ready in ${(System.currentTimeMillis() - started) / 1000}s, peak heap ${peakHeap / 1_000_000} MB")
        runBlocking {
            val hits = MusicBrainzClient.searchArtist("Against The Current")
            val atc = assertNotNull(pickMbArtist(hits, "Against The Current"))
            val artist = assertNotNull(MusicBrainzClient.artist(atc.id))
            val groups = MusicBrainzClient.releaseGroups(atc.id)
            println("MB full: ${artist.name} ${artist.country} ${artist.beginYear} genres=${artist.genres} links=${artist.links.size} groups=${groups.size}")
            assertTrue(groups.size > 10)
            val tagged = assertNotNull(MusicBrainzClient.searchByTag("shoegaze"))
            println("MB full: shoegaze -> ${tagged.size}, first ${tagged.take(5).map { it.name }}")
        }
    }
}
