package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConstellationLadderLinkTest {
    private fun artist(name: String, foundVia: String? = null, id: String = "lib-$name") =
        LibraryArtist(id = id, name = name, genres = listOf("shoegaze"), foundVia = foundVia)

    // region found-via labels

    @Test
    fun bothLadderLabelsCountAsFoundThroughALadder() {
        assertTrue(foundViaLadder("ladder: Into shoegaze"))
        assertTrue(foundViaLadder("ladder: Out from Sting"))
        assertTrue(foundViaLadder("Where next from Sting")) // saved from the artist page before it used the shared label
        assertTrue(foundViaLadder(foundViaLabel("Where next from Sting")))
        assertFalse(foundViaLadder(null))
        assertFalse(foundViaLadder("added by hand"))
    }

    @Test
    fun theStoredPrefixIsDroppedForDisplay() {
        assertEquals("Into shoegaze", foundViaText("ladder: Into shoegaze"))
        assertEquals("Where next from Sting", foundViaText("Where next from Sting"))
        assertEquals("Where next from Sting", foundViaText(foundViaLabel("Where next from Sting")))
        assertNull(foundViaText(null))
        assertNull(foundViaText("ladder: "))
    }

    @Test
    fun onlyOutFromAndWhereNextLabelsNameASourceArtist() {
        assertEquals("Sting", foundViaSource("ladder: Out from Sting"))
        assertEquals("Sting", foundViaSource("ladder: Where next from Sting"))
        assertEquals("Sting", foundViaSource("Where next from Sting"))
        assertNull(foundViaSource("ladder: Into shoegaze"))
        assertNull(foundViaSource("ladder: Out from "))
        assertNull(foundViaSource(null))
    }

    // endregion

    // region ring and line

    @Test
    fun ringsGoToLadderFoundStarsOnly() {
        val all = listOf(artist("Sting"), artist("Slowdive", "ladder: Into shoegaze"), artist("Ride", "added by hand"))
        assertEquals(setOf("lib-Slowdive"), ladderFoundIds(all))
    }

    @Test
    fun aLineRunsToTheSourceArtistWhenItIsInTheLibrary() {
        val all = listOf(artist("Sting"), artist("Branford", "ladder: Out from sting"), artist("Slowdive", "ladder: Into shoegaze"))
        assertEquals(listOf(FoundViaLink("lib-Branford", "lib-Sting")), foundViaLinks(all))
    }

    @Test
    fun noLineWhenTheSourceIsNotSavedOrIsTheArtistItself() {
        val all = listOf(
            artist("Branford", "ladder: Out from Sting"), // Sting is not in the Library
            artist("Echo", "ladder: Out from Echo"), // never linked to itself
            artist("Wrapped", "Where next from Some Record"), // a record title, not an artist
        )
        assertEquals(emptyList(), foundViaLinks(all))
    }

    // endregion

    // region star menu and cluster ladder

    @Test
    fun theMenuLeavesOutLadderIntoAGroupThatIsNotAGenre() {
        assertEquals(
            listOf(StarAction.Play, StarAction.PlayCluster, StarAction.LadderFrom, StarAction.ClimbInto, StarAction.LadderIntoCluster, StarAction.OpenRoom),
            starActions("shoegaze"),
        )
        // A group named after a mood can still be played, but not laddered into.
        assertEquals(
            listOf(StarAction.Play, StarAction.PlayCluster, StarAction.LadderFrom, StarAction.ClimbInto, StarAction.OpenRoom),
            starActions(""),
        )
    }

    @Test
    fun aStarWithNoGroupHasNoPlayClusterEither() {
        assertEquals(listOf(StarAction.Play, StarAction.LadderFrom, StarAction.ClimbInto, StarAction.OpenRoom), starActions(null))
    }

    @Test
    fun actionsOpenTheRightLadder() {
        assertEquals(LadderRequest.Artist("Slowdive", LadderMode.OutFrom), starActionRequest(StarAction.LadderFrom, "Slowdive", "shoegaze"))
        assertEquals(LadderRequest.Artist("Slowdive", LadderMode.Into), starActionRequest(StarAction.ClimbInto, "Slowdive", "shoegaze"))
        assertEquals(LadderRequest.Genre("shoegaze"), starActionRequest(StarAction.LadderIntoCluster, "Slowdive", "shoegaze"))
        assertNull(starActionRequest(StarAction.LadderIntoCluster, "Slowdive", null))
        assertNull(starActionRequest(StarAction.OpenRoom, "Slowdive", "shoegaze"))
        assertNull(starActionRequest(StarAction.Play, "Slowdive", "shoegaze"))
        assertNull(starActionRequest(StarAction.PlayCluster, "Slowdive", "shoegaze"))
    }

    @Test
    fun labelsNameTheArtistAndTheGenre() {
        assertEquals("Climb into Slowdive", starActionLabel(StarAction.ClimbInto, "Slowdive", "shoegaze"))
        assertEquals("Ladder into shoegaze", starActionLabel(StarAction.LadderIntoCluster, "Slowdive", "shoegaze"))
        assertEquals("Ladder from here", starActionLabel(StarAction.LadderFrom, "Slowdive", null))
        assertEquals("Open artist room", starActionLabel(StarAction.OpenRoom, "Slowdive", null))
        assertEquals("Play", starActionLabel(StarAction.Play, "Slowdive", "shoegaze"))
        assertEquals("Play all shoegaze", starActionLabel(StarAction.PlayCluster, "Slowdive", "shoegaze"))
    }

    @Test
    fun aClusterLaddersIntoItsGenreByItsLabel() {
        assertEquals(LadderRequest.Genre("dream pop"), clusterLadderRequest("dream pop"))
        assertTrue(canLadderInto("shoegaze"))
        assertFalse(canLadderInto(null))
        assertFalse(canLadderInto(""))
    }

    // endregion
}
