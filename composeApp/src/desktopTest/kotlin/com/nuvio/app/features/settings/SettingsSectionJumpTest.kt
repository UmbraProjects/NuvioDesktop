package com.nuvio.app.features.settings

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyListState
import kotlin.test.Test
import kotlin.test.assertEquals
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesUiState

/**
 * The sidebar's section links: jumping to the last section of a page and then back to the first
 * must land on the first. Built like the desktop settings list - a lazy column of tall sections,
 * each heading anchored with alignToTop, the viewport's own scroll, and the seek that walks the
 * list to a heading that is not composed.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsSectionJumpTest {

    private val titles = (1..7).map { "Section $it" }

    @Test
    fun `jumping to the last section and then the first lands on the first`() = runComposeUiTest {
        var state: LazyListState? = null
        setContent {
            val listState = rememberLazyListState()
            state = listState
            val viewportSize = remember { mutableStateOf(IntSize.Zero) }
            val scope = rememberCoroutineScope()
            val viewport = remember(listState) {
                SettingsAnchorViewport(viewportSize, 22.dp, scrollBy = { delta, instant -> if (instant) listState.scrollBy(delta) else listState.animateScrollBy(delta) }, scope = scope, scrollPosition = { listState.scrollPositionKey() })
            }
            SeekSettingsAnchorIntoView(listState)
            CompositionLocalProvider(LocalSettingsAnchorViewport provides viewport) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .size(width = 400.dp, height = 500.dp)
                        .onSizeChanged { viewportSize.value = it }
                        .onPlaced { viewport.coordinates = it },
                ) {
                    titles.forEach { title ->
                        item {
                            Column {
                                val highlight = rememberSettingsAnchorHighlight(
                                    SettingsScrollAnchor.section(title),
                                    alignToTop = true,
                                )
                                Box(modifier = highlight.modifier.fillMaxWidth().height(40.dp))
                                Box(modifier = Modifier.fillMaxWidth().height(360.dp))
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()

        SettingsScrollAnchor.request(SettingsScrollAnchor.section(titles.last()))
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        val afterLast = state!!.firstVisibleItemIndex
        assert(afterLast >= titles.lastIndex - 1) { "last jump stopped at item $afterLast" }

        SettingsScrollAnchor.request(SettingsScrollAnchor.section(titles.first()))
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        assertEquals(0, state!!.firstVisibleItemIndex, "jump back to the first section did not land")
    }

    /** The reported case: Continue Watching's Sort Order (last), then Poster Card Style (first). */
    @Test
    fun `continue watching sort order then poster card style`() {
        for (height in listOf(420, 700, 1000, 1400)) jumpOnContinueWatching(height)
    }

    private fun jumpOnContinueWatching(viewportHeight: Int) = runComposeUiTest {
        var state: LazyListState? = null
        val defaults = ContinueWatchingPreferencesUiState()
        setContent {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                val listState = rememberLazyListState()
                state = listState
                val viewportSize = remember { mutableStateOf(IntSize.Zero) }
                val scope = rememberCoroutineScope()
                val viewport = remember(listState) {
                    SettingsAnchorViewport(viewportSize, 22.dp, scrollBy = { delta, instant -> if (instant) listState.scrollBy(delta) else listState.animateScrollBy(delta) }, scope = scope, scrollPosition = { listState.scrollPositionKey() })
                }
                SeekSettingsAnchorIntoView(listState)
                CompositionLocalProvider(
                    LocalSettingsAnchorViewport provides viewport,
                    LocalSettingsPage provides SettingsPage.ContinueWatching,
                    LocalSettingsSectionCards provides true,
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .size(width = 900.dp, height = viewportHeight.dp)
                            .onSizeChanged { viewportSize.value = it }
                            .onPlaced { viewport.coordinates = it },
                    ) {
                        continueWatchingSettingsContent(
                            isTablet = true,
                            isVisible = defaults.isVisible,
                            style = defaults.style,
                            clickAction = defaults.clickAction,
                            upNextFromFurthestEpisode = defaults.upNextFromFurthestEpisode,
                            useEpisodeThumbnails = defaults.useEpisodeThumbnails,
                            showUnairedNextUp = defaults.showUnairedNextUp,
                            separateNextUpRow = defaults.separateNextUpRow,
                            separateUpcomingRow = defaults.separateUpcomingRow,
                            blurNextUp = defaults.blurNextUp,
                            showResumePromptOnLaunch = defaults.showResumePromptOnLaunch,
                            sortMode = defaults.sortMode,
                            relativeAirCountdown = defaults.relativeAirCountdown,
                        )
                    }
                }
            }
        }
        waitForIdle()
        SettingsScrollAnchor.request(SettingsScrollAnchor.section("SORT ORDER"))
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        SettingsScrollAnchor.request(SettingsScrollAnchor.section("Poster Card Style"))
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        assertEquals(0, state!!.firstVisibleItemIndex, "[$viewportHeight] jump to Poster Card Style did not land")
    }
}
