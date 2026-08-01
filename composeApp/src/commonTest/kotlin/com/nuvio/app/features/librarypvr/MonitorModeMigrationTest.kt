package com.nuvio.app.features.librarypvr

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The picker renamed two modes. Existing profiles on disk still carry the old wire values, so these
 * lock in that a pre-upgrade monitored title keeps behaving exactly as it did.
 */
class MonitorModeMigrationTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val legacyFutureEpisodes = """
        {"id":"m1","contentId":"tt0108778","contentType":"series","title":"Friends",
         "targetFolderId":"f1","mode":"FUTURE_EPISODES","monitoredSeasons":[],
         "addedAtEpochMs":1700000000000}
    """.trimIndent()

    private val legacySelectedSeasons = """
        {"id":"m2","contentId":"tt0108778","contentType":"series","title":"Friends",
         "targetFolderId":"f1","mode":"SELECTED_SEASONS","monitoredSeasons":[3,4],
         "addedAtEpochMs":1700000000000}
    """.trimIndent()

    @Test
    fun legacyFutureEpisodesDecodesToSelectedPlusFuture() {
        val item = json.decodeFromString(MonitoredItem.serializer(), legacyFutureEpisodes)
        assertEquals(MonitorMode.SELECTED_PLUS_FUTURE, item.mode)
        // Empty selection means it still only picks up newly aired episodes, as before.
        assertFalse(item.wantsEpisode(1, 1, item.addedAtEpochMs - 1_000L))
        assertTrue(item.wantsEpisode(1, 1, item.addedAtEpochMs + 1_000L))
    }

    @Test
    fun legacySelectedSeasonsDecodesToSelectedOnlyWithItsSeasons() {
        val item = json.decodeFromString(MonitoredItem.serializer(), legacySelectedSeasons)
        assertEquals(MonitorMode.SELECTED_ONLY, item.mode)
        assertEquals(setOf(3, 4), item.monitoredSeasons)
        assertTrue(item.selectsEpisode(3, 1))
        assertFalse(item.selectsEpisode(5, 1))
    }

    @Test
    fun itemsWithoutEpisodeOverridesStillDecode() {
        val item = json.decodeFromString(MonitoredItem.serializer(), legacySelectedSeasons)
        assertEquals(emptyMap(), item.episodeOverrides)
    }

    @Test
    fun modesRoundTripOnTheLegacyWireValues() {
        val encoded = json.encodeToString(
            MonitoredItem.serializer(),
            json.decodeFromString(MonitoredItem.serializer(), legacyFutureEpisodes),
        )
        assertTrue(encoded.contains("\"FUTURE_EPISODES\""), "expected legacy wire value, got: $encoded")
    }
}
