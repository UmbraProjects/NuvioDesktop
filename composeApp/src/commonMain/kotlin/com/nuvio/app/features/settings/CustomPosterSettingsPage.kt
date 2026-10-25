package com.nuvio.app.features.settings

import com.nuvio.app.core.ui.ditheredBackground
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.nuvio.app.core.ui.NuvioModalDialog
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.settings_poster_service_placeholders_title
import nuvio.composeapp.generated.resources.settings_poster_service_placeholders_row_description
import nuvio.composeapp.generated.resources.settings_poster_service_placeholders_subtitle
import nuvio.composeapp.generated.resources.settings_poster_service_ph_group_ids
import nuvio.composeapp.generated.resources.settings_poster_service_ph_group_values
import nuvio.composeapp.generated.resources.settings_poster_service_ph_group_modifiers
import nuvio.composeapp.generated.resources.settings_poster_service_ph_id
import nuvio.composeapp.generated.resources.settings_poster_service_ph_type
import nuvio.composeapp.generated.resources.settings_poster_service_ph_shape
import nuvio.composeapp.generated.resources.settings_poster_service_ph_tmdb_key
import nuvio.composeapp.generated.resources.settings_poster_service_ph_mdblist_key
import nuvio.composeapp.generated.resources.settings_poster_service_ph_optional
import nuvio.composeapp.generated.resources.settings_poster_service_ph_alternatives
import nuvio.composeapp.generated.resources.settings_poster_service_ph_note_blank
import nuvio.composeapp.generated.resources.settings_poster_service_ph_note_local
import nuvio.composeapp.generated.resources.action_close
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.posterservice.CustomPosterContinueWatchingMode
import com.nuvio.app.features.posterservice.CustomPosterScreen
import com.nuvio.app.features.posterservice.CustomPosterSettings
import com.nuvio.app.features.posterservice.CustomPosterSettingsRepository
import com.nuvio.app.features.posterservice.CustomPosterShape
import com.nuvio.app.features.posterservice.CustomPosterTemplateProbe
import com.nuvio.app.features.posterservice.probeCustomPosterTemplate
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_poster_service_description
import nuvio.composeapp.generated.resources.settings_poster_service_landscape_template
import nuvio.composeapp.generated.resources.settings_poster_service_landscape_template_description
import nuvio.composeapp.generated.resources.settings_poster_service_landscape_override_note
import nuvio.composeapp.generated.resources.settings_poster_service_missing_template
import nuvio.composeapp.generated.resources.settings_poster_service_poster_template
import nuvio.composeapp.generated.resources.settings_poster_service_poster_template_description
import nuvio.composeapp.generated.resources.settings_poster_service_section
import nuvio.composeapp.generated.resources.settings_poster_service_screens_section
import nuvio.composeapp.generated.resources.settings_poster_service_cw_mode_off
import nuvio.composeapp.generated.resources.settings_poster_service_cw_mode_base_art
import nuvio.composeapp.generated.resources.settings_poster_service_cw_mode_all
import nuvio.composeapp.generated.resources.settings_poster_service_screen_home
import nuvio.composeapp.generated.resources.settings_poster_service_screen_home_description
import nuvio.composeapp.generated.resources.settings_poster_service_screen_continue_watching
import nuvio.composeapp.generated.resources.settings_poster_service_screen_continue_watching_description
import nuvio.composeapp.generated.resources.settings_poster_service_screen_collections
import nuvio.composeapp.generated.resources.settings_poster_service_screen_collections_description
import nuvio.composeapp.generated.resources.settings_poster_service_screen_library
import nuvio.composeapp.generated.resources.settings_poster_service_screen_library_description
import nuvio.composeapp.generated.resources.settings_poster_service_screen_search
import nuvio.composeapp.generated.resources.settings_poster_service_screen_search_description
import nuvio.composeapp.generated.resources.settings_poster_service_screen_details
import nuvio.composeapp.generated.resources.settings_poster_service_screen_details_description
import nuvio.composeapp.generated.resources.settings_poster_service_screen_discover
import nuvio.composeapp.generated.resources.settings_poster_service_screen_discover_description
import nuvio.composeapp.generated.resources.settings_poster_service_template_label
import nuvio.composeapp.generated.resources.settings_poster_service_templates_section
import nuvio.composeapp.generated.resources.settings_poster_service_test_description
import nuvio.composeapp.generated.resources.settings_poster_service_test_landscape_title
import nuvio.composeapp.generated.resources.settings_poster_service_test_ok
import nuvio.composeapp.generated.resources.settings_poster_service_test_poster_title
import nuvio.composeapp.generated.resources.settings_poster_service_test_running
import nuvio.composeapp.generated.resources.settings_poster_service_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.customPosterSettingsContent(
    isTablet: Boolean,
    settings: CustomPosterSettings,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_poster_service_section),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("poster-service-enable")),
            ) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_poster_service_title),
                    description = stringResource(Res.string.settings_poster_service_description),
                    checked = settings.enabled,
                    enabled = settings.hasAnyTemplate,
                    isTablet = isTablet,
                    onCheckedChange = CustomPosterSettingsRepository::setEnabled,
                )
                if (!settings.hasAnyTemplate) {
                    SettingsGroupDivider(isTablet = isTablet)
                    CustomPosterInfoRow(
                        isTablet = isTablet,
                        text = stringResource(Res.string.settings_poster_service_missing_template),
                    )
                }
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_poster_service_templates_section),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsSearchAnchors(
                    "poster-service-poster-template",
                    "poster-service-landscape-template",
                    "poster-service-test",
                ),
            ) {
                // The placeholder reference used to sit here as one dense paragraph; it reads far
                // better laid out as a table in its own window.
                var placeholdersOpen by remember { mutableStateOf(false) }
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_poster_service_placeholders_title),
                    description = stringResource(Res.string.settings_poster_service_placeholders_row_description),
                    isTablet = isTablet,
                    onClick = { placeholdersOpen = true },
                )
                if (placeholdersOpen) {
                    CustomPosterPlaceholdersDialog(onDismiss = { placeholdersOpen = false })
                }
                SettingsGroupDivider(isTablet = isTablet)
                CustomPosterTemplateRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_poster_service_poster_template),
                    description = stringResource(Res.string.settings_poster_service_poster_template_description),
                    value = settings.posterUrlTemplate,
                    onTemplateCommitted = CustomPosterSettingsRepository::setPosterUrlTemplate,
                )
                if (settings.hasPosterTemplate) {
                    SettingsGroupDivider(isTablet = isTablet)
                    CustomPosterTemplateTestRow(
                        isTablet = isTablet,
                        title = stringResource(Res.string.settings_poster_service_test_poster_title),
                        settings = settings,
                        shape = CustomPosterShape.Portrait,
                    )
                }
                // Tests whichever URL landscape cards will actually use: the separate one when set,
                // otherwise the main URL with {shape}=landscape.
                if (settings.templateFor(CustomPosterShape.Landscape).isNotBlank()) {
                    SettingsGroupDivider(isTablet = isTablet)
                    CustomPosterTemplateTestRow(
                        isTablet = isTablet,
                        title = stringResource(Res.string.settings_poster_service_test_landscape_title),
                        settings = settings,
                        shape = CustomPosterShape.Landscape,
                    )
                }
                // Demoted to last: a URL naming {shape} already covers landscape, so this only
                // matters for services that keep landscape art elsewhere.
                SettingsGroupDivider(isTablet = isTablet)
                CustomPosterTemplateRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_poster_service_landscape_template),
                    description = stringResource(Res.string.settings_poster_service_landscape_template_description),
                    value = settings.landscapeUrlTemplate,
                    onTemplateCommitted = CustomPosterSettingsRepository::setLandscapeUrlTemplate,
                )
                if (settings.hasLandscapeTemplate && settings.posterTemplateServesLandscape) {
                    CustomPosterInfoRow(
                        isTablet = isTablet,
                        text = stringResource(Res.string.settings_poster_service_landscape_override_note),
                    )
                }
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_poster_service_screens_section),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("poster-service-screens")),
            ) {
                CustomPosterScreen.entries.forEachIndexed { index, screen ->
                    if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                    if (screen == CustomPosterScreen.ContinueWatching) {
                        ContinueWatchingModeRow(isTablet = isTablet, settings = settings)
                        return@forEachIndexed
                    }
                    SettingsSwitchRow(
                        title = stringResource(screen.titleRes()),
                        description = stringResource(screen.descriptionRes()),
                        checked = screen in settings.enabledScreens,
                        enabled = settings.enabled,
                        isTablet = isTablet,
                        onCheckedChange = { checked -> CustomPosterSettingsRepository.setScreenEnabled(screen, checked) },
                    )
                }
            }
        }
    }
}

/** Continue Watching is Off / Base art / All rather than a switch — see [CustomPosterContinueWatchingMode]. */
@Composable
private fun ContinueWatchingModeRow(isTablet: Boolean, settings: CustomPosterSettings) {
    val labels = mapOf(
        CustomPosterContinueWatchingMode.Off to stringResource(Res.string.settings_poster_service_cw_mode_off),
        CustomPosterContinueWatchingMode.BaseArt to stringResource(Res.string.settings_poster_service_cw_mode_base_art),
        CustomPosterContinueWatchingMode.All to stringResource(Res.string.settings_poster_service_cw_mode_all),
    )
    SettingsChoiceRow(
        title = stringResource(Res.string.settings_poster_service_screen_continue_watching),
        description = stringResource(Res.string.settings_poster_service_screen_continue_watching_description),
        options = CustomPosterContinueWatchingMode.entries.map { mode -> SettingsChoiceOption(mode, labels.getValue(mode)) },
        selectedValue = settings.continueWatchingMode,
        enabled = settings.enabled,
        isTablet = isTablet,
        onSelected = CustomPosterSettingsRepository::setContinueWatchingMode,
    )
}

private fun CustomPosterScreen.titleRes(): StringResource = when (this) {
    CustomPosterScreen.Home -> Res.string.settings_poster_service_screen_home
    CustomPosterScreen.ContinueWatching -> Res.string.settings_poster_service_screen_continue_watching
    CustomPosterScreen.Collections -> Res.string.settings_poster_service_screen_collections
    CustomPosterScreen.Library -> Res.string.settings_poster_service_screen_library
    CustomPosterScreen.Search -> Res.string.settings_poster_service_screen_search
    CustomPosterScreen.Details -> Res.string.settings_poster_service_screen_details
    CustomPosterScreen.Discover -> Res.string.settings_poster_service_screen_discover
}

private fun CustomPosterScreen.descriptionRes(): StringResource = when (this) {
    CustomPosterScreen.Home -> Res.string.settings_poster_service_screen_home_description
    CustomPosterScreen.ContinueWatching -> Res.string.settings_poster_service_screen_continue_watching_description
    CustomPosterScreen.Collections -> Res.string.settings_poster_service_screen_collections_description
    CustomPosterScreen.Library -> Res.string.settings_poster_service_screen_library_description
    CustomPosterScreen.Search -> Res.string.settings_poster_service_screen_search_description
    CustomPosterScreen.Details -> Res.string.settings_poster_service_screen_details_description
    CustomPosterScreen.Discover -> Res.string.settings_poster_service_screen_discover_description
}

@Composable
private fun CustomPosterTemplateRow(
    isTablet: Boolean,
    title: String,
    description: String,
    value: String,
    onTemplateCommitted: (String) -> Unit,
) {
    SettingsTextInputRow(
        title = title,
        description = description,
        value = value,
        placeholder = stringResource(Res.string.settings_poster_service_template_label),
        singleLine = false,
        minLines = 2,
        maxLines = 6,
        keyboardType = KeyboardType.Uri,
        summarizeAsConfigured = true,
        isTablet = isTablet,
        onSave = onTemplateCommitted,
    )
}

/**
 * One-shot check of a template against the service, with its answer shown verbatim.
 *
 * A poster service that rejects the request is invisible from the library: the card quietly falls
 * back to the plain poster, so a broken template and a service with no art for a title look exactly
 * the same. The service's own error text names the problem (a missing `tmdb_key=` on a self-hosted
 * instance, a bad host, an expired key) far faster than reading the library ever could.
 */
@Composable
private fun CustomPosterTemplateTestRow(
    isTablet: Boolean,
    title: String,
    settings: CustomPosterSettings,
    shape: CustomPosterShape,
) {
    val scope = rememberCoroutineScope()
    val runningText = stringResource(Res.string.settings_poster_service_test_running)
    val okText = stringResource(Res.string.settings_poster_service_test_ok)
    var isRunning by remember { mutableStateOf(false) }
    var result by remember(settings.templateFor(shape)) { mutableStateOf<String?>(null) }

    SettingsNavigationRow(
        title = title,
        description = stringResource(Res.string.settings_poster_service_test_description),
        enabled = !isRunning,
        isTablet = isTablet,
        onClick = {
            if (isRunning) return@SettingsNavigationRow
            isRunning = true
            result = null
            scope.launch {
                result = when (val probe = probeCustomPosterTemplate(settings, shape)) {
                    is CustomPosterTemplateProbe.Ok -> okText
                    is CustomPosterTemplateProbe.Failed -> probe.detail
                }
                isRunning = false
            }
        },
    )
    val message = if (isRunning) runningText else result
    if (message != null) {
        CustomPosterInfoRow(isTablet = isTablet, text = message)
    }
}

@Composable
private fun CustomPosterInfoRow(
    isTablet: Boolean,
    text: String,
) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 14.dp else 12.dp

    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One placeholder and what it stands for, a row of [CustomPosterPlaceholdersDialog]. */
private class TemplatePlaceholder(val token: String, val meaning: StringResource? = null)

@Composable
private fun CustomPosterPlaceholdersDialog(onDismiss: () -> Unit) {
    val ids = listOf(
        TemplatePlaceholder("{id}", Res.string.settings_poster_service_ph_id),
        TemplatePlaceholder("{id_type}"),
        TemplatePlaceholder("{typed_id}"),
        TemplatePlaceholder("{imdb_id}"),
        TemplatePlaceholder("{tmdb_id}"),
        TemplatePlaceholder("{tvdb_id}"),
        TemplatePlaceholder("{kitsu_id}"),
        TemplatePlaceholder("{anilist_id}"),
        TemplatePlaceholder("{mal_id}"),
        TemplatePlaceholder("{anidb_id}"),
    )
    val values = listOf(
        TemplatePlaceholder("{type}", Res.string.settings_poster_service_ph_type),
        TemplatePlaceholder("{shape}", Res.string.settings_poster_service_ph_shape),
        TemplatePlaceholder("{tmdb_key}", Res.string.settings_poster_service_ph_tmdb_key),
        TemplatePlaceholder("{mdblist_key}", Res.string.settings_poster_service_ph_mdblist_key),
    )
    val modifiers = listOf(
        TemplatePlaceholder("{imdb_id?}", Res.string.settings_poster_service_ph_optional),
        TemplatePlaceholder("{imdb_id|tmdb_id}", Res.string.settings_poster_service_ph_alternatives),
    )
    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_poster_service_placeholders_title),
        subtitle = stringResource(Res.string.settings_poster_service_placeholders_subtitle),
        actions = {
            NuvioPrimaryButton(
                text = stringResource(Res.string.action_close),
                onClick = onDismiss,
            )
        },
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            PlaceholderGroup(stringResource(Res.string.settings_poster_service_ph_group_ids), ids)
            PlaceholderGroup(stringResource(Res.string.settings_poster_service_ph_group_values), values)
            PlaceholderGroup(stringResource(Res.string.settings_poster_service_ph_group_modifiers), modifiers)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PlaceholderNote(stringResource(Res.string.settings_poster_service_ph_note_blank))
                PlaceholderNote(stringResource(Res.string.settings_poster_service_ph_note_local))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceholderGroup(title: String, items: List<TemplatePlaceholder>) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = tokens.colors.textMuted,
            fontWeight = FontWeight.SemiBold,
        )
        // Placeholders with no explanation of their own wrap as a cloud of chips; the ones that
        // need a word get a row each.
        val (plain, explained) = items.partition { it.meaning == null }
        if (plain.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                plain.forEach { PlaceholderChip(it.token) }
            }
        }
        explained.forEach { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(modifier = Modifier.width(PlaceholderTokenColumn)) { PlaceholderChip(item.token) }
                Text(
                    text = stringResource(item.meaning!!),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private val PlaceholderTokenColumn = 170.dp

@Composable
private fun PlaceholderChip(token: String) {
    val tokens = MaterialTheme.nuvio
    Text(
        text = token,
        modifier = Modifier
            .ditheredBackground(SettingsSectionGlassFill, SettingsSectionGlassShape)
            .border(1.dp, SettingsSectionGlassEdge, SettingsSectionGlassShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        color = tokens.colors.textPrimary,
        maxLines = 1,
    )
}

@Composable
private fun PlaceholderNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.nuvio.colors.textMuted,
    )
}
