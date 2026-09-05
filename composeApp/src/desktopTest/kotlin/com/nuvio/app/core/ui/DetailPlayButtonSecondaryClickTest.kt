package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import com.nuvio.app.features.details.components.DetailActionButtons
import kotlin.test.Test
import kotlin.test.assertEquals

/** The real details Play button, not a stand-in: it nests its clicks inside Surface + AnimatedContent. */
@OptIn(ExperimentalTestApi::class)
class DetailPlayButtonSecondaryClickTest {

    @Test
    fun `right clicking the details play button runs the alternate action`() = runComposeUiTest {
        var primary = 0
        var alternate = 0
        setContent {
            Box(modifier = Modifier.testTag("row")) {
                DetailActionButtons(
                    playLabel = "Resume",
                    isTablet = true,
                    onPlayClick = { primary++ },
                    onPlayLongClick = { alternate++ },
                )
            }
        }

        onNodeWithTag("row").performMouseInput { rightClick() }
        waitForIdle()

        assertEquals(1, alternate, "right-click should run the alternate play action")
        assertEquals(0, primary, "right-click must not also resume")
    }

    @Test
    fun `left clicking the details play button resumes`() = runComposeUiTest {
        var primary = 0
        var alternate = 0
        setContent {
            Box(modifier = Modifier.testTag("row")) {
                DetailActionButtons(
                    playLabel = "Resume",
                    isTablet = true,
                    onPlayClick = { primary++ },
                    onPlayLongClick = { alternate++ },
                )
            }
        }

        onNodeWithTag("row").performMouseInput { click() }
        waitForIdle()

        assertEquals(1, primary, "left-click should resume")
        assertEquals(0, alternate, "left-click must not run the alternate action")
    }
}
