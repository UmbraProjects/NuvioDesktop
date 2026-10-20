package com.nuvio.app.core.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.SwallowedClickRescue.detectClicksSwallowedByScrolling
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)
class SwallowedClickRescueTest {

    private fun ComposeUiTest.probe(name: String, list: @Composable (onClick: (Int) -> Unit) -> Unit) {
        val clicks = mutableListOf<Int>()
        setContent {
            Box(Modifier.fillMaxSize().testTag("root").detectClicksSwallowedByScrolling()) {
                list { clicks += it }
            }
        }
        SwallowedClickRescue.takePendingReplay()
        // A plain click: the card takes it, nothing to replay.
        onNodeWithTag("root").performMouseInput { click(Offset(50f, 20f)) }
        waitForIdle()
        assertEquals(1, clicks.size, "$name: plain click")
        assertFalse(SwallowedClickRescue.takePendingReplay(), "$name: a plain click is never replayed")

        // A wheel notch, then an ordinary ~100 ms click while the list is still easing: the list
        // carries the card away under the pointer and the click is lost (the bug).
        mainClock.autoAdvance = false
        onNodeWithTag("root").performMouseInput { scroll(3f) }
        mainClock.advanceTimeBy(48)
        onNodeWithTag("root").performMouseInput { press(MouseButton.Primary) }
        mainClock.advanceTimeBy(96)
        onNodeWithTag("root").performMouseInput { release(MouseButton.Primary) }
        mainClock.advanceTimeBy(16)
        mainClock.autoAdvance = true
        waitForIdle()
        assertEquals(1, clicks.size, "$name: the click made while easing is lost without the rescue")
        assertTrue(SwallowedClickRescue.takePendingReplay(), "$name: and is marked for replay")

        // The list has stopped: the same click lands, and is not replayed on top.
        onNodeWithTag("root").performMouseInput { click(Offset(50f, 20f)) }
        waitForIdle()
        assertEquals(2, clicks.size, "$name: click after the list stopped")
        assertFalse(SwallowedClickRescue.takePendingReplay(), "$name: a click that landed is not replayed")
    }

    @Test
    fun lazyClickable() = runComposeUiTest {
        probe("lazy+clickable") { onClick ->
            LazyColumn(Modifier.fillMaxSize()) {
                items(200) { i -> Box(Modifier.fillMaxWidth().height(40.dp).clickable { onClick(i) }) }
            }
        }
    }

    @Test
    fun lazyCombined() = runComposeUiTest {
        probe("lazy+combined") { onClick ->
            LazyColumn(Modifier.fillMaxSize()) {
                items(200) { i ->
                    Box(Modifier.fillMaxWidth().height(40.dp).combinedClickable(onLongClick = {}) { onClick(i) })
                }
            }
        }
    }

    @Test
    fun columnClickable() = runComposeUiTest {
        probe("column+clickable") { onClick ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                repeat(200) { i -> Box(Modifier.fillMaxWidth().height(40.dp).clickable { onClick(i) }) }
            }
        }
    }

    @Test
    fun columnCombined() = runComposeUiTest {
        probe("column+combined") { onClick ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                repeat(200) { i ->
                    Box(Modifier.fillMaxWidth().height(40.dp).combinedClickable(onLongClick = {}) { onClick(i) })
                }
            }
        }
    }
}
