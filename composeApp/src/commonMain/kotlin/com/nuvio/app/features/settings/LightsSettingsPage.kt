package com.nuvio.app.features.settings

import nuvio.composeapp.generated.resources.settings_action_run
import nuvio.composeapp.generated.resources.settings_action_load
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.lights.GoveeDeviceLoadState
import com.nuvio.app.features.lights.LightsController
import com.nuvio.app.features.lights.LightsEndBehavior
import com.nuvio.app.features.lights.LightsPauseBehavior
import com.nuvio.app.features.lights.LightsSettings
import com.nuvio.app.features.lights.LightsSettingsRepository
import com.nuvio.app.features.lights.LightsStartAction
import com.nuvio.app.features.lights.LightsTestState
import com.nuvio.app.features.lights.LightsWebhookMethod
import com.nuvio.app.features.lights.MINUTES_PER_DAY
import com.nuvio.app.features.lights.normalizeWebhookUrl
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_lights_dim_level
import nuvio.composeapp.generated.resources.settings_lights_dim_level_description
import nuvio.composeapp.generated.resources.settings_lights_enable
import nuvio.composeapp.generated.resources.settings_lights_enable_description
import nuvio.composeapp.generated.resources.settings_lights_end_behavior
import nuvio.composeapp.generated.resources.settings_lights_end_behavior_description
import nuvio.composeapp.generated.resources.settings_lights_end_restore
import nuvio.composeapp.generated.resources.settings_lights_end_turn_on
import nuvio.composeapp.generated.resources.settings_lights_external_players
import nuvio.composeapp.generated.resources.settings_lights_external_players_description
import nuvio.composeapp.generated.resources.settings_lights_govee_api_key
import nuvio.composeapp.generated.resources.settings_lights_govee_api_key_description
import nuvio.composeapp.generated.resources.settings_lights_govee_api_key_placeholder
import nuvio.composeapp.generated.resources.settings_lights_govee_devices
import nuvio.composeapp.generated.resources.settings_lights_govee_devices_count
import nuvio.composeapp.generated.resources.settings_lights_govee_devices_description
import nuvio.composeapp.generated.resources.settings_lights_govee_devices_empty
import nuvio.composeapp.generated.resources.settings_lights_govee_devices_failed
import nuvio.composeapp.generated.resources.settings_lights_govee_devices_idle
import nuvio.composeapp.generated.resources.settings_lights_govee_devices_loaded
import nuvio.composeapp.generated.resources.settings_lights_govee_devices_loading
import nuvio.composeapp.generated.resources.settings_lights_govee_devices_none
import nuvio.composeapp.generated.resources.settings_lights_govee_load_devices
import nuvio.composeapp.generated.resources.settings_lights_missing_provider
import nuvio.composeapp.generated.resources.settings_lights_pause_behavior
import nuvio.composeapp.generated.resources.settings_lights_pause_behavior_description
import nuvio.composeapp.generated.resources.settings_lights_pause_bring_up
import nuvio.composeapp.generated.resources.settings_lights_pause_stay
import nuvio.composeapp.generated.resources.settings_lights_section_govee
import nuvio.composeapp.generated.resources.settings_lights_section_title
import nuvio.composeapp.generated.resources.settings_lights_section_webhooks
import nuvio.composeapp.generated.resources.settings_lights_schedule
import nuvio.composeapp.generated.resources.settings_lights_schedule_description
import nuvio.composeapp.generated.resources.settings_lights_schedule_from
import nuvio.composeapp.generated.resources.settings_lights_schedule_from_description
import nuvio.composeapp.generated.resources.settings_lights_schedule_until
import nuvio.composeapp.generated.resources.settings_lights_schedule_until_description
import nuvio.composeapp.generated.resources.settings_lights_start_action
import nuvio.composeapp.generated.resources.settings_lights_start_action_description
import nuvio.composeapp.generated.resources.settings_lights_start_dim
import nuvio.composeapp.generated.resources.settings_lights_start_off
import nuvio.composeapp.generated.resources.settings_lights_test
import nuvio.composeapp.generated.resources.settings_lights_test_done
import nuvio.composeapp.generated.resources.settings_lights_test_failed
import nuvio.composeapp.generated.resources.settings_lights_test_idle
import nuvio.composeapp.generated.resources.settings_lights_test_running
import nuvio.composeapp.generated.resources.settings_lights_webhook_end
import nuvio.composeapp.generated.resources.settings_lights_webhook_method
import nuvio.composeapp.generated.resources.settings_lights_webhook_method_description
import nuvio.composeapp.generated.resources.settings_lights_webhook_pause
import nuvio.composeapp.generated.resources.settings_lights_webhook_resume
import nuvio.composeapp.generated.resources.settings_lights_webhook_start
import nuvio.composeapp.generated.resources.settings_lights_webhook_url_description
import nuvio.composeapp.generated.resources.settings_lights_webhook_url_placeholder
import nuvio.composeapp.generated.resources.settings_lights_webhooks_description
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.lightsSettingsContent(
    isTablet: Boolean,
    settings: LightsSettings,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_lights_section_title),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsSearchAnchors(
                    "lights-enable",
                    "lights-start-action",
                    "lights-pause",
                    "lights-end",
                    "lights-external",
                    "lights-schedule",
                    "lights-test",
                ),
            ) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_lights_enable),
                    description = stringResource(Res.string.settings_lights_enable_description),
                    checked = settings.enabled && settings.hasAnyProvider,
                    enabled = settings.hasAnyProvider,
                    isTablet = isTablet,
                    onCheckedChange = LightsSettingsRepository::setEnabled,
                )
                if (!settings.hasAnyProvider) {
                    SettingsGroupDivider(isTablet = isTablet)
                    LightsInfoRow(
                        isTablet = isTablet,
                        text = stringResource(Res.string.settings_lights_missing_provider),
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsChoiceRow(
                    title = stringResource(Res.string.settings_lights_start_action),
                    description = stringResource(Res.string.settings_lights_start_action_description),
                    options = listOf(
                        SettingsChoiceOption(LightsStartAction.TurnOff, stringResource(Res.string.settings_lights_start_off)),
                        SettingsChoiceOption(LightsStartAction.Dim, stringResource(Res.string.settings_lights_start_dim)),
                    ),
                    selectedValue = settings.startAction,
                    isTablet = isTablet,
                    onSelected = LightsSettingsRepository::setStartAction,
                )
                if (settings.startAction == LightsStartAction.Dim) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSliderRow(
                        title = stringResource(Res.string.settings_lights_dim_level),
                        description = stringResource(Res.string.settings_lights_dim_level_description),
                        value = settings.dimPercent,
                        valueText = "${settings.dimPercent}%",
                        valueTextForValue = { "$it%" },
                        valueRange = 1..100,
                        step = 1,
                        isTablet = isTablet,
                        onValueChange = LightsSettingsRepository::setDimPercent,
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsChoiceRow(
                    title = stringResource(Res.string.settings_lights_pause_behavior),
                    description = stringResource(Res.string.settings_lights_pause_behavior_description),
                    options = listOf(
                        SettingsChoiceOption(LightsPauseBehavior.StayDark, stringResource(Res.string.settings_lights_pause_stay)),
                        SettingsChoiceOption(LightsPauseBehavior.BringUp, stringResource(Res.string.settings_lights_pause_bring_up)),
                    ),
                    selectedValue = settings.pauseBehavior,
                    isTablet = isTablet,
                    onSelected = LightsSettingsRepository::setPauseBehavior,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsChoiceRow(
                    title = stringResource(Res.string.settings_lights_end_behavior),
                    description = stringResource(Res.string.settings_lights_end_behavior_description),
                    options = listOf(
                        SettingsChoiceOption(LightsEndBehavior.Restore, stringResource(Res.string.settings_lights_end_restore)),
                        SettingsChoiceOption(LightsEndBehavior.TurnOn, stringResource(Res.string.settings_lights_end_turn_on)),
                    ),
                    selectedValue = settings.endBehavior,
                    isTablet = isTablet,
                    onSelected = LightsSettingsRepository::setEndBehavior,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_lights_external_players),
                    description = stringResource(Res.string.settings_lights_external_players_description),
                    checked = settings.externalPlayers,
                    isTablet = isTablet,
                    onCheckedChange = LightsSettingsRepository::setExternalPlayers,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_lights_schedule),
                    description = stringResource(Res.string.settings_lights_schedule_description),
                    checked = settings.scheduleEnabled,
                    isTablet = isTablet,
                    onCheckedChange = LightsSettingsRepository::setScheduleEnabled,
                )
                if (settings.scheduleEnabled) {
                    val timeOptions = scheduleTimeOptions()
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsChoiceRow(
                        title = stringResource(Res.string.settings_lights_schedule_from),
                        description = stringResource(Res.string.settings_lights_schedule_from_description),
                        options = timeOptions,
                        selectedValue = settings.scheduleFromMinutes.roundedToHalfHour(),
                        isTablet = isTablet,
                        onSelected = LightsSettingsRepository::setScheduleFromMinutes,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsChoiceRow(
                        title = stringResource(Res.string.settings_lights_schedule_until),
                        description = stringResource(Res.string.settings_lights_schedule_until_description),
                        options = timeOptions,
                        selectedValue = settings.scheduleUntilMinutes.roundedToHalfHour(),
                        isTablet = isTablet,
                        onSelected = LightsSettingsRepository::setScheduleUntilMinutes,
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                LightsTestRow(isTablet = isTablet, settings = settings)
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_lights_section_govee),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsSearchAnchors("lights-govee-key", "lights-govee-devices"),
            ) {
                SettingsTextInputRow(
                    title = stringResource(Res.string.settings_lights_govee_api_key),
                    description = stringResource(Res.string.settings_lights_govee_api_key_description),
                    value = settings.goveeApiKey,
                    placeholder = stringResource(Res.string.settings_lights_govee_api_key_placeholder),
                    secret = true,
                    isTablet = isTablet,
                    onSave = LightsSettingsRepository::setGoveeApiKey,
                )
                SettingsGroupDivider(isTablet = isTablet)
                GoveeDevicesRows(isTablet = isTablet, settings = settings)
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_lights_section_webhooks),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsSearchAnchors("lights-webhook-method", "lights-webhook-urls"),
            ) {
                LightsInfoRow(
                    isTablet = isTablet,
                    text = stringResource(Res.string.settings_lights_webhooks_description),
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsChoiceRow(
                    title = stringResource(Res.string.settings_lights_webhook_method),
                    description = stringResource(Res.string.settings_lights_webhook_method_description),
                    options = listOf(
                        SettingsChoiceOption(LightsWebhookMethod.Post, "POST"),
                        SettingsChoiceOption(LightsWebhookMethod.Get, "GET"),
                    ),
                    selectedValue = settings.webhookMethod,
                    isTablet = isTablet,
                    onSelected = LightsSettingsRepository::setWebhookMethod,
                )
                SettingsGroupDivider(isTablet = isTablet)
                WebhookUrlRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_lights_webhook_start),
                    value = settings.webhookStartUrl,
                    onSave = LightsSettingsRepository::setWebhookStartUrl,
                )
                SettingsGroupDivider(isTablet = isTablet)
                WebhookUrlRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_lights_webhook_pause),
                    value = settings.webhookPauseUrl,
                    onSave = LightsSettingsRepository::setWebhookPauseUrl,
                )
                SettingsGroupDivider(isTablet = isTablet)
                WebhookUrlRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_lights_webhook_resume),
                    value = settings.webhookResumeUrl,
                    onSave = LightsSettingsRepository::setWebhookResumeUrl,
                )
                SettingsGroupDivider(isTablet = isTablet)
                WebhookUrlRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_lights_webhook_end),
                    value = settings.webhookEndUrl,
                    onSave = LightsSettingsRepository::setWebhookEndUrl,
                )
            }
        }
    }
}

/** Every half hour of the day, labelled the way a clock face reads: 12:00 AM … 11:30 PM. */
private fun scheduleTimeOptions(): List<SettingsChoiceOption<Int>> =
    (0 until MINUTES_PER_DAY step 30).map { minutes ->
        SettingsChoiceOption(minutes, formatClockTime(minutes))
    }

private fun formatClockTime(minutesOfDay: Int): String {
    val hour24 = minutesOfDay / 60
    val minute = minutesOfDay % 60
    val hour12 = if (hour24 % 12 == 0) 12 else hour24 % 12
    val suffix = if (hour24 < 12) "AM" else "PM"
    return "$hour12:${minute.toString().padStart(2, '0')} $suffix"
}

/** A stored value that is not on the half-hour grid (older builds, hand-edited) still selects a row. */
private fun Int.roundedToHalfHour(): Int = ((this + 15) / 30 * 30).mod(MINUTES_PER_DAY)

@Composable
private fun LightsTestRow(isTablet: Boolean, settings: LightsSettings) {
    val state = settings.testState
    // A row with its action in the control column: the last result under the title.
    SettingsActionRow(
        title = stringResource(Res.string.settings_lights_test),
        description = when (state) {
            LightsTestState.Idle -> stringResource(Res.string.settings_lights_test_idle)
            LightsTestState.Running -> stringResource(Res.string.settings_lights_test_running)
            LightsTestState.Done -> stringResource(Res.string.settings_lights_test_done)
            is LightsTestState.Failed -> stringResource(Res.string.settings_lights_test_failed, state.detail)
        },
        descriptionColor = if (state is LightsTestState.Failed) MaterialTheme.colorScheme.error else null,
        isTablet = isTablet,
    ) {
        SettingsGlassButton(
            text = stringResource(Res.string.settings_action_run),
            onClick = { LightsController.runTest() },
            enabled = settings.hasAnyProvider,
            accent = true,
            loading = state is LightsTestState.Running,
        )
    }
}

@Composable
private fun GoveeDevicesRows(isTablet: Boolean, settings: LightsSettings) {
    val loadState = settings.goveeDeviceLoadState
    SettingsActionRow(
        title = stringResource(Res.string.settings_lights_govee_load_devices),
        description = when (loadState) {
            GoveeDeviceLoadState.Idle -> stringResource(Res.string.settings_lights_govee_devices_idle)
            GoveeDeviceLoadState.Loading -> stringResource(Res.string.settings_lights_govee_devices_loading)
            is GoveeDeviceLoadState.Loaded -> if (loadState.count == 0) {
                stringResource(Res.string.settings_lights_govee_devices_none)
            } else {
                stringResource(Res.string.settings_lights_govee_devices_loaded, loadState.count)
            }
            is GoveeDeviceLoadState.Failed ->
                stringResource(Res.string.settings_lights_govee_devices_failed, loadState.detail)
        },
        descriptionColor = if (loadState is GoveeDeviceLoadState.Failed) MaterialTheme.colorScheme.error else null,
        isTablet = isTablet,
    ) {
        SettingsGlassButton(
            text = stringResource(Res.string.settings_action_load),
            onClick = { LightsController.loadGoveeDevices() },
            enabled = settings.hasGoveeKey,
            accent = true,
            loading = loadState is GoveeDeviceLoadState.Loading,
        )
    }
    if (settings.goveeDevices.isNotEmpty()) {
        SettingsGroupDivider(isTablet = isTablet)
        SettingsMultiSelectRow(
            title = stringResource(Res.string.settings_lights_govee_devices),
            description = stringResource(Res.string.settings_lights_govee_devices_description),
            options = settings.goveeDevices.map { device ->
                SettingsChoiceOption(device.deviceId, "${device.name} (${device.sku})")
            },
            selectedValues = settings.goveeSelectedDeviceIds,
            emptyLabel = stringResource(Res.string.settings_lights_govee_devices_empty),
            isTablet = isTablet,
            summaryForCount = { stringResource(Res.string.settings_lights_govee_devices_count, it) },
            onToggle = LightsSettingsRepository::toggleGoveeDevice,
        )
    }
}

@Composable
private fun WebhookUrlRow(
    isTablet: Boolean,
    title: String,
    value: String,
    onSave: (String) -> Unit,
) {
    SettingsTextInputRow(
        title = title,
        description = stringResource(Res.string.settings_lights_webhook_url_description),
        value = value,
        placeholder = stringResource(Res.string.settings_lights_webhook_url_placeholder),
        keyboardType = KeyboardType.Uri,
        normalize = ::normalizeWebhookUrl,
        isTablet = isTablet,
        onSave = onSave,
    )
}

@Composable
private fun LightsInfoRow(isTablet: Boolean, text: String) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 16.dp else 14.dp
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
    )
}
