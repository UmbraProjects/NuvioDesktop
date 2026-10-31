package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The artist page's handling of releases YouTube Music lacks but the music addon has (keys come from AddonReleases). */
class ArtistAddonTest {
    private fun release(
        key: String,
        title: String,
        kind: ReleaseKind = ReleaseKind.Album,
        year: String? = "2024",
        yt: String? = null,
        tracks: Int? = null,
    ) = LibraryRelease(key = key, title = title, kind = kind, year = year, ytBrowseId = yt, trackCount = tracks)

    private val onYouTube = release("yt", "On YouTube", yt = "MPREb_1")
    private val onAddonOnly = release("gap", "Not On YouTube")
    private val nowhere = release("none", "Nowhere", year = "2023")
    private val all = listOf(onYouTube, onAddonOnly, nowhere)

    private fun keysShown(d: ArtistDiscography) = d.eras.flatMap { it.full + it.singles }.map { it.primary.key }.toSet()

    private fun groups() = buildDiscography(all, playableOnly = false, filter = ReleaseFilter.All).eras.flatMap { it.full }

    @Test
    fun aReleaseIsPlayableWithTheAddonOnlyWhenItsKeyIsKnown() {
        val gap = groups().single { it.primary.key == "gap" }
        assertFalse(gap.isPlayableWith(emptySet()))
        assertFalse(gap.isPlayableWith(setOf("other")))
        assertTrue(gap.isPlayableWith(setOf("gap")))
        assertTrue(gap.onAddon(setOf("gap")))
    }

    @Test
    fun aCardOnYouTubeMusicIsNeverMarkedAsOnTheAddon() {
        val yt = groups().single { it.primary.key == "yt" }
        assertTrue(yt.isPlayableWith(emptySet()))
        assertFalse(yt.onAddon(setOf("yt")))
    }

    @Test
    fun playableOnlyKeepsAddonReleasesAndCountsThem() {
        val d = buildDiscography(all, playableOnly = true, filter = ReleaseFilter.All, addonKeys = setOf("gap"))
        assertEquals(setOf("yt", "gap"), keysShown(d))
        assertEquals(2, d.playableCount)
        assertEquals(3, d.totalCount)
    }

    @Test
    fun withoutAddonKeysPlayableOnlyIsAsItWas() {
        val d = buildDiscography(all, playableOnly = true, filter = ReleaseFilter.All)
        assertEquals(setOf("yt"), keysShown(d))
        assertEquals(1, d.playableCount)
    }

    @Test
    fun allShowsEveryReleaseWhateverTheAddonHas() {
        assertEquals(setOf("yt", "gap", "none"), keysShown(buildDiscography(all, playableOnly = false, filter = ReleaseFilter.All, addonKeys = setOf("gap"))))
    }

    @Test
    fun aFoldedCardIsOnTheAddonWhenAnyOfItsVersionsIs() {
        // Two listings of one record, neither on YouTube Music. The card shows the fuller one, but the addon check may have found the other.
        val card = buildDiscography(
            listOf(release("a", "Same Album", tracks = 7), release("b", "Same Album", tracks = 11)),
            playableOnly = false, filter = ReleaseFilter.All,
        ).eras.single().full.single()
        assertEquals("b", card.primary.key)
        assertTrue(card.onAddon(setOf("a")))
        assertFalse(card.onAddon(setOf("c")))
    }

    @Test
    fun theTimelineKeepsAddonReleasesUnderPlayableOnly() {
        assertEquals(setOf("yt", "gap"), timelineEntries(groups(), playableOnly = true, addonKeys = setOf("gap")).map { it.primary.key }.toSet())
        assertEquals(setOf("yt"), timelineEntries(groups(), playableOnly = true).map { it.primary.key }.toSet())
        assertEquals(3, timelineEntries(groups(), playableOnly = false, addonKeys = setOf("gap")).size)
    }

    @Test
    fun asksOnlyAboutAlbumsAndEPsYouTubeMusicLacksNewestFirst() {
        val releases = listOf(
            release("old", "Old Album", year = "2001"),
            release("new", "New Album", year = "2024"),
            release("ep", "An EP", kind = ReleaseKind.EP, year = "2010"),
            release("single", "A Single", kind = ReleaseKind.Single, year = "2024"),
            release("live", "Live Thing", kind = ReleaseKind.Live, year = "2015"),
            release("demo", "Rough Demo", year = "2000"),
            release("yt", "On YouTube", year = "2020", yt = "MPREb_1"),
        )
        assertEquals(listOf("new", "ep", "old"), releasesToAskAddon(releases).map { it.key })
    }

    @Test
    fun asksOncePerFoldedCardAboutItsPrimaryRelease() {
        val asked = releasesToAskAddon(listOf(release("a", "Same Album", tracks = 7), release("b", "Same Album", tracks = 11)))
        assertEquals(listOf("b"), asked.map { it.key })
    }

    @Test
    fun doesNotAskAboutACardThatHasAYouTubeVersion() {
        val asked = releasesToAskAddon(
            listOf(release("a", "Same Album", tracks = 7), release("b", "Same Album", yt = "MPREb_9", tracks = 11)),
        )
        assertTrue(asked.isEmpty())
    }
}
