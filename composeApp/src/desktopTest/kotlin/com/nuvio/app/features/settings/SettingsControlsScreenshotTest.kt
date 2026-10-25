package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.nuvio
import androidx.compose.material3.MaterialTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Renders one settings section with every control type so their sizes can be compared by eye:
 * a switch row (On/Off), an A/B choice, a dropdown and a slider should all span one column.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsControlsScreenshotTest {
    @Test
    fun `controls share one column`() = runComposeUiTest {
        setContent {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                val tokens = MaterialTheme.nuvio
                CompositionLocalProvider(LocalSettingsSectionCards provides true) {
                    Box(
                        Modifier
                            .size(900.dp, 720.dp)
                            .settingsSkyBackground(tokens.colors.background, tokens.colors.accent)
                            .padding(24.dp),
                    ) {
                        SettingsSection(title = "Example", isTablet = true, subtitle = "51 of 51 catalogs visible • 2 hero sources selected") {
                            SettingsGroup(isTablet = true) {
                                SettingsSwitchRow(
                                    title = "A switch row",
                                    description = "Turns something on or off.",
                                    checked = true,
                                    isTablet = true,
                                    onCheckedChange = {},
                                )
                                SettingsGroupDivider(isTablet = true)
                                SettingsChoiceRow(
                                    title = "An A/B choice",
                                    description = null,
                                    options = listOf(SettingsChoiceOption(1, "Internal"), SettingsChoiceOption(2, "External")),
                                    selectedValue = 1,
                                    isTablet = true,
                                    onSelected = {},
                                )
                                SettingsGroupDivider(isTablet = true)
                                SettingsChoiceRow(
                                    title = "A dropdown",
                                    description = "Three or more options.",
                                    options = listOf(
                                        SettingsChoiceOption(1, "Standard"),
                                        SettingsChoiceOption(2, "Faster"),
                                        SettingsChoiceOption(3, "Fastest"),
                                    ),
                                    selectedValue = 1,
                                    isTablet = true,
                                    onSelected = {},
                                )
                                SettingsGroupDivider(isTablet = true)
                                SettingsNavigationRow(
                                    title = "Personal API key",
                                    description = "Configured",
                                    isTablet = true,
                                    trailingContent = { androidx.compose.material3.Text("Accepted") },
                                    onClick = {},
                                )
                                SettingsGroupDivider(isTablet = true)
                                SettingsActionRow(
                                    title = "Connected as someone",
                                    description = "Your Save actions can now target Trakt lists.",
                                    isTablet = true,
                                ) {
                                    SettingsGlassButton(text = "Disconnect", onClick = {})
                                }
                                SettingsGroupDivider(isTablet = true)
                                SettingsActionRow(
                                    title = "Test lights",
                                    description = "Applies the scene for a few seconds.",
                                    isTablet = true,
                                ) {
                                    SettingsGlassButton(text = "Run", onClick = {}, accent = true)
                                }
                                SettingsGroupDivider(isTablet = true)
                                SettingsSliderRow(
                                    title = "A slider",
                                    value = -20,
                                    valueText = "-20%",
                                    valueRange = -50..50,
                                    step = 5,
                                    isTablet = true,
                                    onValueChange = {},
                                )
                                SettingsGroupDivider(isTablet = true)
                                SettingsRangeSliderRow(
                                    title = "A range",
                                    lowValue = 1,
                                    highValue = 2,
                                    valueTextForRange = { low, high -> "${low}x ⇄ ${high}x" },
                                    valueRange = 1..4,
                                    step = 1,
                                    isTablet = true,
                                    onValueChange = { _, _ -> },
                                )
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()
        val dir = File("build/test-screenshots").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "settings-controls.png"))
    }
}
