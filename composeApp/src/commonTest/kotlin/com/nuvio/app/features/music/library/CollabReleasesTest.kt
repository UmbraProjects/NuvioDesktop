package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.MusicAlbumSummary
import com.nuvio.app.features.music.MusicArtistRef
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Hits are the album searches YouTube Music gave for Against The Current's collaborations (2026-10-29). */
class CollabReleasesTest {
    private val atcId = "UC3u6Cup1AEtYoSu8lv9iMSQ"

    private fun hit(id: String, title: String, vararg artists: Pair<String, String?>) =
        MusicAlbumSummary(id, title, artists.map { (name, channel) -> MusicArtistRef(name, channel) }, "2024", "Single", null)

    private val caughtIt = listOf(
        hit("MPREb_voila", "Caught It (with Chrissy Costanza & Against The Current)", "VOILÀ" to "UCtfzWsTkHQ07L0XZGtEbbAQ"),
        hit("MPREb_other", "Catch My Breath", "Alex Goot & ATC" to "UCFn-B19siHrgQ8_dsJKhyzA"),
    )

    @Test
    fun aCollaborationNamingTheArtistInItsTitleIsTaken() {
        assertEquals("MPREb_voila", pickCollabRelease("Caught It", "Against The Current", atcId, caughtIt, emptySet())?.browseId)
    }

    @Test
    fun anArtistCreditedOnlyByChannelIsTakenToo() {
        // Credited as "ATC", which is not the name, but the channel is the artist's own.
        val hits = listOf(hit("MPREb_x", "Every Collab, Ever", "Alex Goot" to "UCFn", "ATC" to atcId))
        assertEquals("MPREb_x", pickCollabRelease("Every Collab, Ever", "Against The Current", atcId, hits, emptySet())?.browseId)
    }

    @Test
    fun aSameNamedReleaseBySomeoneElseIsNot() {
        val hits = listOf(hit("MPREb_y", "Romantic Disaster", "The Midnight Computers" to "UC7S"))
        assertNull(pickCollabRelease("Romantic Disaster", "Against The Current", atcId, hits, emptySet()))
    }

    @Test
    fun anAlbumAlreadyOnThePageIsNotHandedOutAgain() {
        assertNull(pickCollabRelease("Caught It", "Against The Current", atcId, caughtIt, setOf("MPREb_voila")))
    }

    @Test
    fun unplayableReleasesGetTheirCollaborationLinked() = runBlocking {
        val releases = listOf(
            LibraryRelease(key = "g1", title = "Caught It", kind = ReleaseKind.Single, year = "2024"),
            LibraryRelease(key = "g2", title = "Wildfire", kind = ReleaseKind.Single, year = "2022", ytBrowseId = "MPREb_w"),
        )
        val searched = ArrayList<String>()
        val out = withCollabReleases(releases, "Against The Current", atcId) { query -> searched += query; caughtIt }
        assertEquals(listOf("Caught It Against The Current"), searched) // only the unplayable one is looked for
        assertEquals("MPREb_voila", out.first { it.key == "g1" }.ytBrowseId)
        assertEquals("MPREb_w", out.first { it.key == "g2" }.ytBrowseId)
    }
}
