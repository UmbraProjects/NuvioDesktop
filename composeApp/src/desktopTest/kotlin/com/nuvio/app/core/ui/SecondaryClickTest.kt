package com.nuvio.app.core.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The details Play button stacks `combinedClickable` with [secondaryClick] so a right-click reaches
 * the alternate action. Whether the primary click ALSO fires on a secondary press is a property of
 * Compose's own button filtering, not of our code, so it is pinned here rather than assumed.
 */
@OptIn(ExperimentalTestApi::class)
class SecondaryClickTest {

    private class Clicks {
        var primary = 0
        var long = 0
        var secondary = 0
    }

    @Test
    fun `right click runs the secondary action and not the primary click`() = runComposeUiTest {
        val clicks = Clicks()
        setContent {
            Box(
                modifier = Modifier
                    .testTag("target")
                    .size(100.dp)
                    .combinedClickable(
                        onClick = { clicks.primary++ },
                        onLongClick = { clicks.long++ },
                    )
                    .secondaryClick { clicks.secondary++ },
            )
        }

        onNodeWithTag("target").performMouseInput { rightClick() }
        waitForIdle()

        assertEquals(1, clicks.secondary, "secondary action should run on right-click")
        assertEquals(0, clicks.primary, "right-click must not also trigger the primary click")
    }

    @Test
    fun `left click still runs the primary click`() = runComposeUiTest {
        val clicks = Clicks()
        setContent {
            Box(
                modifier = Modifier
                    .testTag("target")
                    .size(100.dp)
                    .combinedClickable(
                        onClick = { clicks.primary++ },
                        onLongClick = { clicks.long++ },
                    )
                    .secondaryClick { clicks.secondary++ },
            )
        }

        onNodeWithTag("target").performMouseInput { click() }
        waitForIdle()

        assertEquals(1, clicks.primary, "left-click should run the primary click")
        assertEquals(0, clicks.secondary, "left-click must not run the secondary action")
    }
}
