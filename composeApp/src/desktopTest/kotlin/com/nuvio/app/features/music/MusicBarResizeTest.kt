package com.nuvio.app.features.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The real bar, dragged by its real grips: the width must follow the pointer. */
@OptIn(ExperimentalTestApi::class)
class MusicBarResizeTest {
    @AfterTest
    fun reset() {
        MusicPlayback.barPlacement.value = MusicPlayerPlacement.Default
    }

    private fun bar(state: MusicPlayerState) = @androidx.compose.runtime.Composable {
        val player = MusicPlayer(CoroutineScope(Dispatchers.Unconfined), resolver = { track, _, _ -> MusicResolution.Failed(track, "test") })
        Box(Modifier.size(1000.dp, 700.dp)) {
            MusicMiniPlayerBar(
                state = state,
                visible = true,
                player = player,
                initiallyShown = true,
                areaHeight = 700.dp,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    @Test
    fun draggingTheRightGripInwardNarrowsTheBar() = runComposeUiTest {
        setContent { bar(MusicPlayerState(durationMs = 200_000L, positionMs = 18_000L))() }
        waitForIdle()
        val before = onNodeWithTag("musicResizeGrip-Right").fetchSemanticsNode().boundsInRoot.left
        onNodeWithTag("musicResizeGrip-Right").performMouseInput {
            moveTo(center)
            press()
            moveBy(androidx.compose.ui.geometry.Offset(-40f, 0f))
            moveBy(androidx.compose.ui.geometry.Offset(-160f, 0f))
            release()
        }
        waitForIdle()
        val width = MusicPlayback.barPlacement.value.width
        assertNotNull(width, "the right grip should set a width")
        assertTrue(width < (1000.dp - 1000.dp * 0.05f * 2), "the bar should be narrower than its usual width, was $width")
        val after = onNodeWithTag("musicResizeGrip-Right").fetchSemanticsNode().boundsInRoot.left
        assertTrue(after < before - 100f, "the right edge should be drawn further left: before=$before after=$after")
    }

    @Test
    fun controlAPlainDragHandlerSeesTheTestDrag() = runComposeUiTest {
        var moved = 0f
        setContent {
            Box(
                Modifier.size(200.dp).testTag("plain").pointerInput(Unit) {
                    detectDragGestures { change, drag -> change.consume(); moved += drag.x }
                },
            )
        }
        waitForIdle()
        onNodeWithTag("plain").performMouseInput {
            moveTo(center)
            press()
            moveBy(androidx.compose.ui.geometry.Offset(-40f, 0f))
            moveBy(androidx.compose.ui.geometry.Offset(-160f, 0f))
            release()
        }
        waitForIdle()
        assertTrue(moved < 0f, "a bare drag handler should see the drag, moved=$moved")
    }

    @Test
    fun theGripAloneResizes() = runComposeUiTest {
        setContent {
            Box(Modifier.size(600.dp, 120.dp)) {
                MusicResizeGrip(
                    placement = MusicPlayback.barPlacement,
                    currentWidth = 500.dp,
                    minWidth = 100.dp,
                    maxWidth = 560.dp,
                    centred = true,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
        waitForIdle()
        onNodeWithTag("musicResizeGrip-Right").performMouseInput {
            moveTo(center)
            press()
            moveBy(androidx.compose.ui.geometry.Offset(-40f, 0f))
            moveBy(androidx.compose.ui.geometry.Offset(-160f, 0f))
            release()
        }
        waitForIdle()
        assertNotNull(MusicPlayback.barPlacement.value.width, "the grip on its own should set a width")
    }

    @Test
    fun draggingTheLeftGripInwardNarrowsTheBar() = runComposeUiTest {
        setContent { bar(MusicPlayerState(durationMs = 200_000L, positionMs = 18_000L))() }
        waitForIdle()
        onNodeWithTag("musicResizeGrip-Left").performMouseInput {
            moveTo(center)
            press()
            moveBy(androidx.compose.ui.geometry.Offset(40f, 0f))
            moveBy(androidx.compose.ui.geometry.Offset(160f, 0f))
            release()
        }
        waitForIdle()
        assertNotNull(MusicPlayback.barPlacement.value.width, "the left grip should set a width")
    }
}
