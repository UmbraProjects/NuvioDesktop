package com.nuvio.app.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class KeptAliveLayerTest {
    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun `covering keeps the content composed and its state, and uncovering shows it again`() = runComposeUiTest {
        var visible by mutableStateOf(true)
        var compositions = 0
        var covered: Boolean? = null
        val owner = TestOwner()
        setContent {
            KeptAliveLayer(visible = visible, lifecycleOwner = owner, modifier = Modifier.fillMaxSize()) {
                val counter = remember { mutableIntStateOf(0) }
                LaunchedEffect(Unit) { compositions++ }
                covered = LocalScreenCovered.current
                Box(Modifier.fillMaxSize().testTag("kept").clickable { counter.intValue++ })
            }
        }
        waitForIdle()
        assertEquals(1, compositions)
        assertEquals(false, covered)

        visible = false
        waitForIdle()
        assertEquals(true, covered)
        onNodeWithTag("kept").assertDoesNotExistOrIsNotPlaced()

        visible = true
        waitForIdle()
        assertEquals(1, compositions, "the content must not be rebuilt on uncover")
        assertEquals(false, covered)
        onNodeWithTag("kept").assertExists()
    }

    @Test
    fun `a covered layer takes no clicks, the screen beneath it does`() = runComposeUiTest {
        var visible by mutableStateOf(true)
        var keptClicks = 0
        var beneathClicks = 0
        val owner = TestOwner()
        setContent {
            Box(Modifier.fillMaxSize().testTag("root")) {
                Box(Modifier.fillMaxSize().clickable { beneathClicks++ })
                KeptAliveLayer(visible = visible, lifecycleOwner = owner, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().clickable { keptClicks++ })
                }
            }
        }
        onNodeWithTag("root").performMouseInput { click() }
        waitForIdle()
        assertEquals(1, keptClicks)
        assertEquals(0, beneathClicks)

        visible = false
        waitForIdle()
        onNodeWithTag("root").performMouseInput { click() }
        waitForIdle()
        assertEquals(1, keptClicks, "covered content must not take the click")
        assertEquals(1, beneathClicks)
    }

    @Test
    fun `a tab hidden behind another pauses its lifecycle, and nested layers inherit covered`() = runComposeUiTest {
        var outerVisible by mutableStateOf(true)
        var innerVisible by mutableStateOf(true)
        var innerCovered: Boolean? = null
        var child: LifecycleOwner? = null
        val childState: () -> Lifecycle.State? = { child?.lifecycle?.currentState }
        val owner = TestOwner()
        setContent {
            KeptAliveLayer(visible = outerVisible, lifecycleOwner = owner, modifier = Modifier.fillMaxSize()) {
                val childOwner = rememberChildLifecycleOwner(active = innerVisible)
                child = childOwner
                KeptAliveLayer(visible = innerVisible, lifecycleOwner = childOwner, modifier = Modifier.fillMaxSize()) {
                    innerCovered = LocalScreenCovered.current
                }
            }
        }
        waitForIdle()
        assertEquals(false, innerCovered)
        assertEquals(Lifecycle.State.RESUMED, childState())

        innerVisible = false
        waitForIdle()
        assertEquals(true, innerCovered)
        assertEquals(Lifecycle.State.CREATED, childState(), "a hidden tab stops collecting")

        innerVisible = true
        outerVisible = false
        waitForIdle()
        assertEquals(true, innerCovered, "covered because the outer layer is")
        assertEquals(Lifecycle.State.RESUMED, childState())
    }

    @Test
    fun `hover ends while covered, so a hover preview cannot come back over the card on return`() = runComposeUiTest {
        var visible by mutableStateOf(true)
        val hover = MutableInteractionSource()
        var hovered = false
        val owner = TestOwner()
        setContent {
            Box(Modifier.fillMaxSize().testTag("root")) {
                KeptAliveLayer(visible = visible, lifecycleOwner = owner, modifier = Modifier.fillMaxSize()) {
                    val isHovered by hover.collectIsHoveredAsState()
                    hovered = isHovered
                    Box(Modifier.size(100.dp).hoverable(hover))
                }
            }
        }
        onNodeWithTag("root").performMouseInput { moveTo(Offset(50f, 50f)) }
        waitForIdle()
        assertTrue(hovered, "precondition: the card is hovered")

        visible = false
        waitForIdle()
        // The pointer moves on (to where the next card would be) while the covering screen is up.
        onNodeWithTag("root").performMouseInput { moveTo(Offset(220f, 60f)) }
        waitForIdle()
        assertFalse(hovered, "hover ends as soon as the pointer moves while covered")
        visible = true
        waitForIdle()
        assertFalse(hovered, "the card the pointer left while covered must not still be hovered")
    }

    @Test
    fun `focus held inside is released when covered`() = runComposeUiTest {
        var visible by mutableStateOf(true)
        var keptFocused = false
        val requester = FocusRequester()
        val owner = TestOwner()
        setContent {
            KeptAliveLayer(visible = visible, lifecycleOwner = owner, modifier = Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxSize()
                        .focusRequester(requester)
                        .onFocusChanged { keptFocused = it.isFocused }
                        .focusable(),
                )
            }
        }
        runOnIdle { requester.requestFocus() }
        waitForIdle()
        assertTrue(keptFocused)

        visible = false
        waitForIdle()
        assertFalse(keptFocused, "keys must not reach a screen nobody can see")
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistOrIsNotPlaced() {
    val nodes = fetchSemanticsNodes()
    assertTrue(
        nodes.isEmpty() || nodes.none { it.layoutInfo.isPlaced && it.positionInRoot.x >= 0f },
        "covered content must be off-screen",
    )
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.fetchSemanticsNodes() =
    runCatching { listOf(fetchSemanticsNode()) }.getOrDefault(emptyList())
