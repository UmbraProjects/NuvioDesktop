package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nuvio.app.features.discover.DiscoverAiSettingsRepository
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.tonight.TonightDiscoverSource
import com.nuvio.app.features.tonight.TonightPickFromTopRange
import com.nuvio.app.features.tonight.TonightSettingsRepository
import com.nuvio.app.features.tonight.clockLabel
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** fork:tonight - the Tonight picker's switch and bedtime, at the top of the Random Play page. */
internal fun LazyListScope.tonightSettingsContent(isTablet: Boolean) {
    item {
        SettingsSection(
            title = stringResource(Res.string.tonight_section),
            isTablet = isTablet,
        ) {
            remember { TonightSettingsRepository.ensureLoaded() }
            val settings by TonightSettingsRepository.state.collectAsStateWithLifecycle()
            // 20:00 to 02:00, in minutes past the evening's midnight (so 01:00 is 1500).
            val bedValue = settings.bedMinuteOfDay.let { if (it < 12 * 60) it + 24 * 60 else it }
            var consentPromptFor by remember { mutableStateOf<AiConsentTarget?>(null) }
            val ai = remember(settings.mapAiEnabled) { DiscoverAiSettingsRepository.snapshot() }
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.tonight_enable),
                    description = stringResource(Res.string.tonight_enable_description),
                    checked = settings.enabled,
                    isTablet = isTablet,
                    onCheckedChange = TonightSettingsRepository::setEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSliderRow(
                    title = stringResource(Res.string.tonight_bedtime),
                    description = stringResource(Res.string.tonight_bedtime_description),
                    value = bedValue.coerceIn(20 * 60, 26 * 60),
                    valueText = clockLabel(bedValue),
                    valueTextForValue = ::clockLabel,
                    valueRange = (20 * 60)..(26 * 60),
                    step = 15,
                    isTablet = isTablet,
                    onValueChange = TonightSettingsRepository::setBedtime,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSliderRow(
                    title = stringResource(Res.string.tonight_pick_from_top),
                    description = stringResource(Res.string.tonight_pick_from_top_description),
                    value = settings.pickFromTop,
                    valueText = settings.pickFromTop.toString(),
                    valueRange = TonightPickFromTopRange,
                    step = 1,
                    isTablet = isTablet,
                    onValueChange = TonightSettingsRepository::setPickFromTop,
                )
                for (source in TonightDiscoverSource.entries) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(
                            when (source) {
                                TonightDiscoverSource.TopPicks -> Res.string.tonight_source_top_picks
                                TonightDiscoverSource.BecauseYouWatched -> Res.string.tonight_source_because
                                TonightDiscoverSource.Favourites -> Res.string.tonight_source_favourites
                            },
                        ),
                        description = stringResource(Res.string.tonight_source_description),
                        checked = source in settings.discoverSources,
                        isTablet = isTablet,
                        onCheckedChange = { on -> TonightSettingsRepository.setDiscoverSource(source, on) },
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.tonight_map_ai),
                    description = stringResource(Res.string.tonight_map_ai_description),
                    checked = settings.mapAiEnabled,
                    isTablet = isTablet,
                    onCheckedChange = { on ->
                        when {
                            !on -> TonightSettingsRepository.setMapAiEnabled(false)
                            ai.apiKey.isNotBlank() && !ai.consentGiven -> consentPromptFor = AiConsentTarget.TonightMap
                            else -> TonightSettingsRepository.setMapAiEnabled(true)
                        }
                    },
                )
            }
            AiConsentDialog(target = consentPromptFor, onDismiss = { consentPromptFor = null })
        }
    }
}
