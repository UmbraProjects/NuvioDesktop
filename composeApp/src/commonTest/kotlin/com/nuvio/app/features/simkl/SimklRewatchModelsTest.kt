package com.nuvio.app.features.simkl

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimklRewatchModelsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    private val ids = SimklScrobbleRepository.SimklIds(simkl = 17465, imdb = "tt0944947")

    @Test
    fun `first write declares explicit rewatch intent without a guessed session id`() {
        val body = json.encodeToString(
            buildRewatchRequest(
                kind = SimklRewatchKind.SHOW,
                media = RewatchHistoryMedia(
                    title = "Game of Thrones",
                    ids = ids,
                    isRewatch = true,
                    rewatchStatus = "active",
                ),
            ),
        )

        assertTrue("\"is_rewatch\":true" in body)
        assertTrue("\"rewatch_status\":\"active\"" in body)
        assertFalse("\"rewatch_id\"" in body)
    }

    @Test
    fun `subsequent episode write pins the returned rewatch id`() {
        val body = json.encodeToString(
            buildRewatchRequest(
                kind = SimklRewatchKind.SHOW,
                media = RewatchHistoryMedia(
                    ids = ids,
                    isRewatch = true,
                    rewatchId = 7482,
                    rewatchStatus = "active",
                    seasons = listOf(
                        RewatchHistorySeason(1, listOf(RewatchHistoryEpisode(4))),
                    ),
                ),
            ),
        )

        assertTrue("\"rewatch_id\":7482" in body)
        assertTrue("\"number\":4" in body)
    }

    @Test
    fun `history response extracts the durable session id`() {
        val response = json.decodeFromString<RewatchMutationResponse>(
            """{"added":{"shows":1,"statuses":[{"response":{"rewatch_id":7482,"rewatch_status":"active"}}]}}""",
        )

        assertEquals(7482, response.rewatchMutation()?.rewatchId)
        assertEquals("active", response.rewatchMutation()?.rewatchStatus)
    }

    @Test
    fun `rewatch rows are sidecars and never become canonical watched items`() {
        val canonical = SimklAllItemsEntry(
            lastWatchedAt = "2026-05-15T00:13:18Z",
            show = SimklShowMedia("Game of Thrones", ids = SimklMediaIds(imdb = "tt0944947", simkl = 17465)),
            seasons = listOf(SimklWatchedSeason(1, listOf(SimklWatchedEpisode(1, "2026-05-15T00:13:18Z")))),
        )
        val rewatch = canonical.copy(
            isRewatch = true,
            rewatchId = 7482,
            rewatchStatus = "active",
            watchedEpisodesCount = 1,
        )
        val payload = SimklAllItemsResponse(shows = listOf(canonical, rewatch))

        assertEquals(1, payload.toWatchedItems().size)
        val sessions = payload.toRewatchSessions()
        assertEquals(1, sessions.size)
        assertEquals(7482, sessions.single().rewatchId)
        assertEquals(setOf("1:1"), sessions.single().watchedEpisodeKeys)
    }

    @Test
    fun `rewatch delta updates changed sessions without discarding the baseline`() {
        val first = SimklRewatchSession(
            rewatchId = 10,
            kind = SimklRewatchKind.SHOW,
            contentId = "tt-first",
            title = "First",
            ids = SimklScrobbleRepository.SimklIds(simkl = 100),
            status = "active",
            watchedEpisodesCount = 1,
        )
        val second = SimklRewatchSession(
            rewatchId = 20,
            kind = SimklRewatchKind.SHOW,
            contentId = "tt-second",
            title = "Second",
            ids = SimklScrobbleRepository.SimklIds(simkl = 200),
            status = "active",
        )

        val merged = mutableListOf(first, second).mergeRewatchDelta(
            listOf(first.copy(status = "completed", watchedEpisodesCount = 10)),
        )

        assertEquals(2, merged.size)
        assertEquals("completed", merged.single { it.rewatchId == 10 }.status)
        assertEquals("active", merged.single { it.rewatchId == 20 }.status)
    }

    @Test
    fun `activities exposes the exact top-level delta watermark`() {
        val activities = json.decodeFromString<SimklActivities>(
            """{"all":"2026-08-12T09:10:11Z","settings":{"all":"2026-08-11T01:02:03Z"}}""",
        )

        assertEquals("2026-08-12T09:10:11Z", activities.all)
    }
}
