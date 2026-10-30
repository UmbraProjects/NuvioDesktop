package com.nuvio.app.features.music.library

import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lines shaped like the 2026-10-28 JSON dumps (trimmed), including the recording relations the build must skip. */
class MusicBrainzOfflineIndexTest {
    private val atc = "d93e8b87-0a6a-4a0b-8b0f-8e3d9e1e8f01"
    private val otherAtc = "0b1c3c2a-7a8e-4d9f-9d55-3d1f7a2f9e02"
    private val chvrches = "6a65d878-fcd0-42cf-aff9-ca1d636a8bcc"

    private val artists = listOf(
        """{"id":"$atc","name":"Against The Current","sort-name":"Against The Current","type":"Group","country":"US","disambiguation":"","area":{"name":"United States","id":"x"},"life-span":{"begin":"2011","end":null,"ended":false},"aliases":[{"name":"ATC","sort-name":"ATC","primary":null}],"genres":[{"name":"pop rock","count":3,"id":"g"}],"tags":[{"name":"pop rock","count":3},{"name":"alternative rock","count":5},{"name":"cute","count":0}],"rating":{"value":4.5,"votes-count":4},"relations":[{"target-type":"recording","type":"performer","recording":{"title":"Gravity","id":"r","nested":{"deep":[1,2,{"x":"}"}]}}},{"target-type":"url","type":"youtube","url":{"resource":"https://www.youtube.com/channel/UC3u6Cup1AEtYoSu8lv9iMSQ","id":"u"}},{"target-type":"url","type":"wikipedia","url":{"resource":"https://en.wikipedia.org/wiki/Against_the_Current_(band)"}},{"target-type":"url","type":"wikipedia","url":{"resource":"https://de.wikipedia.org/wiki/Against_the_Current"}},{"target-type":"url","type":"lyrics","url":{"resource":"https://genius.com/x"}}]}""",
        // A namesake nobody has tagged or linked.
        """{"id":"$otherAtc","name":"Against the Current","sort-name":"Against the Current","type":"Person","disambiguation":"Swedish jazz","aliases":[],"genres":[],"tags":[],"rating":{"value":null,"votes-count":0},"relations":[]}""",
        """{"id":"$chvrches","name":"CHVRCHES","sort-name":"CHVRCHES","type":"Group","country":"GB","aliases":[{"name":"Churches"}],"genres":[{"name":"synth-pop","count":9}],"tags":[{"name":"synth-pop","count":9},{"name":"electronic","count":2}],"rating":{"value":4,"votes-count":2},"relations":[{"target-type":"url","type":"official homepage","url":{"resource":"https://chvrch.es/"}}],"annotation":"Escaped \"quote\" and é"}""",
    )

    private val groups = listOf(
        """{"id":"11111111-1111-1111-1111-111111111111","title":"In Our Bones","primary-type":"Album","secondary-types":[],"first-release-date":"2016-05-20","artist-credit":[{"artist":{"id":"$atc","name":"Against The Current","aliases":[{"name":"ATC"}]},"name":"Against The Current","joinphrase":""}],"relations":[{"target-type":"url","type":"discogs"}]}""",
        """{"id":"22222222-2222-2222-2222-222222222222","title":"Caught It","primary-type":"Single","secondary-types":["Remix"],"first-release-date":"2024","artist-credit":[{"artist":{"id":"$atc"},"joinphrase":" & "},{"artist":{"id":"$chvrches"}}]}""",
    )

    private fun build(): MusicBrainzIndexReader {
        val dir = Files.createTempDirectory("mb-index")
        MusicBrainzIndexBuilder(dir).use { b ->
            forEachJsonLine(ByteArrayInputStream(artists.joinToString("\n").toByteArray())) { line, n -> b.addArtistLine(line, n) }
            forEachJsonLine(ByteArrayInputStream(groups.joinToString("\n").toByteArray())) { line, n -> b.addGroupLine(line, n) }
            b.finish("20261028-001001")
        }
        return MusicBrainzIndexReader(dir)
    }

    @Test
    fun anArtistComesBackInTheWebServicesShape() {
        val index = build()
        val artist = assertNotNull(parseMbArtist(assertNotNull(index.artist(atc))))
        assertEquals("Against The Current", artist.name)
        assertEquals("United States", artist.area)
        assertEquals("2011", artist.beginYear)
        assertEquals(listOf("pop rock"), artist.genres)
        assertEquals(listOf("alternative rock", "pop rock"), artist.tags)
        assertEquals(4.5, artist.rating)
        assertEquals("Against_the_Current_(band)", artist.wikipediaTitle)
        // The English Wikipedia and YouTube links are kept; the German article and lyrics are not.
        assertEquals(listOf("https://www.youtube.com/channel/UC3u6Cup1AEtYoSu8lv9iMSQ", "https://en.wikipedia.org/wiki/Against_the_Current_(band)"), artist.links.map { it.url })
        assertEquals(3, index.artistCount)
        assertNull(index.artist("99999999-9999-9999-9999-999999999999"))
    }

    @Test
    fun releaseGroupsAreFoundUnderEveryCreditedArtist() {
        val index = build()
        val (atcGroups, total) = parseMbReleaseGroups(assertNotNull(index.releaseGroups(atc)))
        assertEquals(setOf("In Our Bones", "Caught It"), atcGroups.map { it.title }.toSet())
        assertEquals(2, total)
        assertEquals(listOf("Remix"), atcGroups.first { it.title == "Caught It" }.secondaryTypes)
        assertEquals(listOf("Caught It"), parseMbReleaseGroups(assertNotNull(index.releaseGroups(chvrches))).first.map { it.title })
        // Known artist without groups: an empty answer, not "unknown".
        assertEquals(0, parseMbReleaseGroups(assertNotNull(index.releaseGroups(otherAtc))).first.size)
        assertNull(index.releaseGroups("99999999-9999-9999-9999-999999999999"))
    }

    @Test
    fun aNameSearchPutsTheBetterKnownNamesakeFirst() {
        val index = build()
        val hits = parseMbArtistSearch(assertNotNull(index.searchArtist("against the current", 6)))
        assertEquals(listOf(atc, otherAtc), hits.map { it.id })
        assertEquals(atc, pickMbArtist(hits, "Against The Current")?.id)
        // Accents and punctuation don't matter, and an alias finds the artist too (scored lower).
        assertEquals(chvrches, parseMbArtistSearch(assertNotNull(index.searchArtist("Chvrchés!", 6))).single().id)
        assertTrue(parseMbArtistSearch(assertNotNull(index.searchArtist("Churches", 6))).single().score < 100)
        assertNull(index.searchArtist("Nobody At All", 6))
    }

    @Test
    fun anAliasHitNamesTheAliasSoTheArtistCanBePickedByIt() {
        val index = build()
        val byAlias = parseMbArtistSearch(assertNotNull(index.searchArtist("Churches", 6)))
        assertEquals(listOf("Churches"), byAlias.single().aliases)
        assertEquals(chvrches, pickMbArtist(byAlias, "Churches")?.id)
        assertEquals(atc, pickMbArtist(parseMbArtistSearch(assertNotNull(index.searchArtist("ATC", 6))), "ATC")?.id)
        // A hit found by the artist's own name has no alias to report; its name is what the picker uses.
        val byName = parseMbArtistSearch(assertNotNull(index.searchArtist("CHVRCHES", 6)))
        assertEquals(emptyList<String>(), byName.single().aliases)
        assertEquals(chvrches, pickMbArtist(byName, "CHVRCHES")?.id)
    }

    @Test
    fun aTagSearchListsTheMostVotedFirstWithTheirVotes() {
        val index = build()
        val rock = parseMbTagSearch(index.searchByTag("Alternative Rock", 100))
        assertEquals(listOf(atc), rock.map { it.id })
        assertEquals(5, rock.single().votes["alternative rock"])
        assertEquals(2011, rock.single().beginYear)
        assertEquals(listOf(chvrches), parseMbTagSearch(index.searchByTag("synth-pop", 100)).map { it.id })
        assertTrue(parseMbTagSearch(index.searchByTag("cute", 100)).isEmpty()) // no votes, not indexed
    }

    /** Runs on the real dump's first megabytes when they are in `build/mb-sample/` (see the offline-copy notes). */
    @Test
    fun theRealDumpsHeadBuilds() {
        val sample = Paths.get("build/mb-sample")
        val artistXz = sample.resolve("artist-head.xz")
        val groupXz = sample.resolve("rg-head.xz")
        if (!Files.exists(artistXz) || !Files.exists(groupXz)) return
        val dir = Files.createTempDirectory("mb-real")
        val started = System.nanoTime()
        var artistLines = 0
        var groupLines = 0
        var rawBytes = 0L
        MusicBrainzIndexBuilder(dir).use { b ->
            readTruncated(artistXz, "mbdump/artist") { line, n -> rawBytes += n; artistLines++; b.addArtistLine(line, n) }
            readTruncated(groupXz, "mbdump/release-group") { line, n -> rawBytes += n; groupLines++; b.addGroupLine(line, n) }
            b.finish("sample")
            println("MB sample: $artistLines artist lines -> ${b.artists}, $groupLines group lines -> ${b.groups}")
        }
        val seconds = (System.nanoTime() - started) / 1e9
        val stored = Files.list(dir).use { s -> s.mapToLong { Files.size(it) }.sum() }
        println("MB sample: ${rawBytes / 1_000_000} MB of JSON in %.1f s (%.0f MB/s), stored %d KB".format(seconds, rawBytes / 1e6 / seconds, stored / 1000))
        val reader = MusicBrainzIndexReader(dir)
        assertTrue(reader.artistCount > 0 && reader.groupCount > 0)
    }

    /** The sample files are cut mid-stream, so the xz decoder fails at their end; every whole line before that counts. */
    private fun readTruncated(file: Path, entry: String, onLine: (ByteArray, Int) -> Unit) {
        val pending = ArrayList<Pair<ByteArray, Int>>()
        runCatching {
            val tar = XZInputStream(BufferedInputStream(Files.newInputStream(file), 1 shl 20))
            val stream = assertNotNull(openTarEntry(tar, entry))
            forEachJsonLine(stream) { line, n ->
                pending.forEach { (l, len) -> onLine(l, len) }
                pending.clear()
                pending += line.copyOf(n) to n // held back one line: the last one before the cut is partial
            }
        }
    }
}
