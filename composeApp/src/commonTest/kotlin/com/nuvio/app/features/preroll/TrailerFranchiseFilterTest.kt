package com.nuvio.app.features.preroll

import com.nuvio.app.features.home.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrailerFranchiseFilterTest {
    @Test
    fun readsTheDroppedNumbersAsZeroBasedIndexes() {
        assertEquals(setOf(0, 2), parseFranchiseDrops("""{"drop": [1, 3]}""", 6))
    }

    @Test
    fun toleratesFencesProseAndNumbersAsText() {
        val reply = "Here you go:\n```json\n{\"drop\": [\"2\", 4]}\n```"
        assertEquals(setOf(1, 3), parseFranchiseDrops(reply, 6))
    }

    @Test
    fun anEmptyListIsAValidAnswer() {
        assertEquals(emptySet(), parseFranchiseDrops("""{"drop": []}""", 6))
    }

    @Test
    fun numbersOutsideTheListAreIgnored() {
        assertEquals(setOf(0), parseFranchiseDrops("""{"drop": [0, 1, 9, -2]}""", 5))
    }

    @Test
    fun unreadableRepliesGiveNull() {
        assertNull(parseFranchiseDrops("I cannot help with that", 6))
        assertNull(parseFranchiseDrops("""{"other": [1]}""", 6))
        assertNull(parseFranchiseDrops("""{"drop": "all"}""", 6))
    }

    @Test
    fun aReplyThatDropsMostOfTheListIsNotTrusted() {
        assertNull(parseFranchiseDrops("""{"drop": [1,2,3,4,5,6,7,8]}""", 10))
        assertEquals(setOf(0, 1, 2), parseFranchiseDrops("""{"drop": [1,2,3]}""", 10))
    }

    @Test
    fun onlyTitlesMarkedTheSameFranchiseAreRemoved() {
        val list = listOf(
            MetaPreview(id = "1", type = "movie", name = "Batman"),
            MetaPreview(id = "2", type = "movie", name = "Heat"),
            MetaPreview(id = "3", type = "movie", name = "Unjudged"),
        )
        val decisions = mapOf(trailerHistoryKey("movie", "1") to true, trailerHistoryKey("movie", "2") to false)
        assertEquals(listOf("2", "3"), applyFranchiseDecisions(list, decisions).map { it.id })
    }
}
