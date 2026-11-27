package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.NuvioModalDialog
import com.nuvio.app.core.ui.NuvioNumberStepper
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.usenet.BuiltInUsenet
import com.nuvio.app.features.usenet.DEFAULT_API_PATH
import com.nuvio.app.features.usenet.IndexerProblem
import com.nuvio.app.features.usenet.NntpProviderTester
import com.nuvio.app.features.usenet.NntpProviderTuner
import com.nuvio.app.features.usenet.ProviderTestResult
import com.nuvio.app.features.usenet.TuneFailure
import com.nuvio.app.features.usenet.TuneOutcome
import com.nuvio.app.features.usenet.TuneProgress
import com.nuvio.app.features.usenet.TuneReading
import com.nuvio.app.features.usenet.UsenetConfiguration
import com.nuvio.app.features.usenet.UsenetEngine
import com.nuvio.app.features.usenet.isValidUserAgent
import com.nuvio.app.features.usenet.UsenetIndexer
import com.nuvio.app.features.usenet.UsenetProvider
import com.nuvio.app.features.usenet.UsenetSettingsRepository
import com.nuvio.app.features.usenet.UsenetSort
import com.nuvio.app.features.usenet.UsenetSearchMode
import com.nuvio.app.features.usenet.UsenetSourceConfiguration
import com.nuvio.app.features.usenet.UsenetStats
import com.nuvio.app.features.usenet.UsenetInfoFormat
import com.nuvio.app.features.usenet.USENET_PRIORITIES
import com.nuvio.app.features.usenet.replaceOrAdd
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/*
 * Settings > Usenet: built-in Usenet (NuvioTV PR 4679), laid out as the app's other settings pages.
 * Providers and indexers are edited in the app's glass dialogs; the TV's phone set-up page, ranking
 * presets and diagnostics are not ported. Usenet info ([UsenetInfoDialog]) is Nuvio's own.
 */

private sealed interface UsenetEditor {
    data class Provider(val provider: UsenetProvider, val isNew: Boolean) : UsenetEditor
    data class TuneProvider(val provider: UsenetProvider) : UsenetEditor
    data class Indexer(val indexer: UsenetIndexer, val isNew: Boolean) : UsenetEditor
}

internal fun LazyListScope.usenetSettingsContent(isTablet: Boolean) {
    item(key = "usenet-settings") {
        val state by remember {
            UsenetSettingsRepository.ensureLoaded()
            UsenetSettingsRepository.uiState
        }.collectAsStateWithLifecycle()
        val indexerRevision by BuiltInUsenet.client.statusChanges.collectAsStateWithLifecycle()
        var editor by remember { mutableStateOf<UsenetEditor?>(null) }
        var infoOpen by remember { mutableStateOf(false) }
        val stats by UsenetStats.book.collectAsStateWithLifecycle()
        val sources = state.sources
        val engine = state.engine
        val invalidAgent = stringResource(Res.string.usenet_user_agent_invalid)

        fun save(transform: (UsenetSourceConfiguration) -> UsenetSourceConfiguration) {
            runCatching { UsenetSettingsRepository.updateSources(transform) }
                .onFailure { NuvioToastController.show(it.message ?: "Invalid setting") }
        }

        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.nuvio.spacing.listGap)) {
            val engineMissing = !UsenetEngine.isInstalled
            SettingsSection(
                title = stringResource(Res.string.usenet_section_search),
                isTablet = isTablet,
                subtitle = when {
                    engineMissing -> stringResource(Res.string.usenet_engine_missing)
                    !sources.configured -> stringResource(Res.string.usenet_not_ready)
                    else -> null
                },
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsSwitchRow(
                        title = stringResource(Res.string.usenet_enabled),
                        description = stringResource(Res.string.usenet_enabled_description),
                        checked = sources.enabled,
                        isTablet = isTablet,
                        onCheckedChange = { on -> save { it.copy(enabled = on, firstUseAccepted = it.firstUseAccepted || on) } },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsChoiceRow(
                        title = stringResource(Res.string.usenet_user_agent),
                        description = stringResource(Res.string.usenet_user_agent_description),
                        options = listOf(
                            SettingsChoiceOption(UsenetConfiguration.AGENT_GENERIC, stringResource(Res.string.usenet_user_agent_generic)),
                            SettingsChoiceOption(UsenetConfiguration.AGENT_NUVIO, "Nuvio"),
                            SettingsChoiceOption(UsenetConfiguration.AGENT_CUSTOM, stringResource(Res.string.usenet_user_agent_custom)),
                        ),
                        selectedValue = engine.userAgentMode,
                        isTablet = isTablet,
                        onSelected = { mode -> UsenetSettingsRepository.updateEngine { it.copy(userAgentMode = mode) } },
                    )
                    if (engine.userAgentMode == UsenetConfiguration.AGENT_CUSTOM) {
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsTextInputRow(
                            title = stringResource(Res.string.usenet_user_agent_custom_title),
                            description = stringResource(Res.string.usenet_user_agent_custom_description),
                            value = engine.customUserAgent,
                            placeholder = "Example/1.0",
                            isTablet = isTablet,
                            onSave = { value ->
                                if (isValidUserAgent(value)) {
                                    UsenetSettingsRepository.updateEngine { it.copy(customUserAgent = value) }
                                } else {
                                    NuvioToastController.show(invalidAgent)
                                }
                            },
                        )
                    }
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.usenet_info_title),
                        description = stringResource(Res.string.usenet_info_description),
                        isTablet = isTablet,
                        onClick = { infoOpen = true },
                    )
                }
            }

            SettingsSection(
                title = stringResource(Res.string.usenet_section_providers),
                isTablet = isTablet,
                subtitle = stringResource(Res.string.usenet_providers_description),
            ) {
                SettingsGroup(isTablet = isTablet) {
                    sources.providers.forEach { provider ->
                        SettingsNavigationRow(
                            title = provider.name,
                            description = listOfNotNull(
                                "${provider.host}:${provider.port}",
                                stringResource(Res.string.usenet_connections_count, provider.connections),
                                stringResource(Res.string.usenet_priority_value, provider.priority),
                                stringResource(Res.string.usenet_disabled).takeUnless { provider.enabled },
                                stats.providers[provider.id]?.missingPercent?.let {
                                    stringResource(Res.string.usenet_info_missing_row, UsenetInfoFormat.percent(it))
                                },
                            ).joinToString(" · "),
                            isTablet = isTablet,
                            onClick = { editor = UsenetEditor.Provider(provider, isNew = false) },
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                    }
                    SettingsNavigationRow(
                        title = stringResource(Res.string.usenet_add_provider),
                        description = "",
                        isTablet = isTablet,
                        onClick = { editor = UsenetEditor.Provider(UsenetProvider(), isNew = true) },
                    )
                }
            }

            SettingsSection(
                title = stringResource(Res.string.usenet_section_indexers),
                isTablet = isTablet,
                subtitle = stringResource(Res.string.usenet_indexers_description),
            ) {
                SettingsGroup(isTablet = isTablet) {
                    sources.indexers.forEach { indexer ->
                        val status = remember(indexer, indexerRevision) { BuiltInUsenet.client.status(indexer) }
                        val problem = when {
                            status.pausedUntil > System.currentTimeMillis() -> stringResource(Res.string.usenet_indexer_paused)
                            status.problem == IndexerProblem.AUTH -> stringResource(Res.string.usenet_indexer_auth)
                            status.problem == IndexerProblem.UNREACHABLE -> stringResource(Res.string.usenet_indexer_unreachable)
                            status.problem == IndexerProblem.RESPONSE -> stringResource(Res.string.usenet_indexer_response)
                            else -> null
                        }
                        SettingsNavigationRow(
                            title = indexer.name,
                            description = listOfNotNull(
                                stringResource(Res.string.usenet_priority_value, indexer.priority),
                                indexer.fetchResultsLimit?.let { limit ->
                                    stringResource(
                                        Res.string.usenet_indexer_fetch_limit_row,
                                        if (limit == 0) stringResource(Res.string.usenet_unlimited) else limit.toString(),
                                    )
                                },
                                status.apiHitsLeft?.let { stringResource(Res.string.usenet_hits_left, it) },
                                stringResource(Res.string.usenet_disabled).takeUnless { indexer.enabled },
                                stats.indexers[indexer.id]?.averageRequestMs?.let {
                                    stringResource(Res.string.usenet_info_avg_row, UsenetInfoFormat.duration(it))
                                },
                                problem,
                            ).joinToString(" · "),
                            isTablet = isTablet,
                            onClick = { editor = UsenetEditor.Indexer(indexer, isNew = false) },
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                    }
                    SettingsNavigationRow(
                        title = stringResource(Res.string.usenet_add_indexer),
                        description = "",
                        isTablet = isTablet,
                        onClick = { editor = UsenetEditor.Indexer(UsenetIndexer(apiPath = DEFAULT_API_PATH), isNew = true) },
                    )
                }
            }

            SettingsSection(title = stringResource(Res.string.usenet_section_results), isTablet = isTablet) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsChoiceRow(
                        title = stringResource(Res.string.usenet_sort),
                        description = null,
                        options = listOf(
                            SettingsChoiceOption(UsenetSort.QUALITY, stringResource(Res.string.usenet_sort_quality)),
                            SettingsChoiceOption(UsenetSort.LARGEST, stringResource(Res.string.usenet_sort_largest)),
                            SettingsChoiceOption(UsenetSort.SMALLEST, stringResource(Res.string.usenet_sort_smallest)),
                            SettingsChoiceOption(UsenetSort.NEWEST, stringResource(Res.string.usenet_sort_newest)),
                            SettingsChoiceOption(UsenetSort.INDEXER, stringResource(Res.string.usenet_sort_indexer)),
                        ),
                        selectedValue = sources.sort,
                        isTablet = isTablet,
                        onSelected = { sort -> save { it.copy(sort = sort) } },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsChoiceRow(
                        title = stringResource(Res.string.usenet_min_resolution),
                        description = null,
                        options = listOf(0, 720, 1080, 2160).map { value ->
                            SettingsChoiceOption(value, if (value == 0) stringResource(Res.string.usenet_any) else if (value == 2160) "4K" else "${value}p")
                        },
                        selectedValue = sources.minResolution,
                        isTablet = isTablet,
                        onSelected = { value -> save { it.copy(minResolution = value) } },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsChoiceRow(
                        title = stringResource(Res.string.usenet_max_results),
                        description = null,
                        options = listOf(20, 50, 100, UsenetSourceConfiguration.UNLIMITED_RESULTS).map { value ->
                            SettingsChoiceOption(value, if (value == UsenetSourceConfiguration.UNLIMITED_RESULTS) stringResource(Res.string.usenet_unlimited) else value.toString())
                        },
                        selectedValue = sources.maxResults.takeIf { it in listOf(20, 50, 100, 0) } ?: 50,
                        isTablet = isTablet,
                        onSelected = { value -> save { it.copy(maxResults = value) } },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.usenet_exclude_low_quality),
                        checked = sources.excludeLowQuality,
                        isTablet = isTablet,
                        onCheckedChange = { on -> save { it.copy(excludeLowQuality = on) } },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.usenet_hide_passworded),
                        description = stringResource(Res.string.usenet_hide_passworded_description),
                        checked = sources.hidePassworded,
                        isTablet = isTablet,
                        onCheckedChange = { on -> save { it.copy(hidePassworded = on) } },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.usenet_exclude_full_disc),
                        checked = sources.excludeFullDisc,
                        isTablet = isTablet,
                        onCheckedChange = { on -> save { it.copy(excludeFullDisc = on) } },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsChoiceRow(
                        title = stringResource(Res.string.usenet_compressed_releases),
                        description = stringResource(Res.string.usenet_compressed_releases_description),
                        options = UsenetConfiguration.COMPRESSED_CHOICES.map { value ->
                            SettingsChoiceOption(
                                value,
                                when (value) {
                                    UsenetConfiguration.COMPRESSED_PLAY_ALL -> stringResource(Res.string.usenet_compressed_play_all)
                                    UsenetConfiguration.COMPRESSED_SKIP_ALL -> stringResource(Res.string.usenet_compressed_skip_all)
                                    else -> stringResource(Res.string.usenet_compressed_skip_over, "$value GB")
                                },
                            )
                        },
                        selectedValue = engine.compressedSkipGb,
                        isTablet = isTablet,
                        onSelected = { value -> UsenetSettingsRepository.updateEngine { it.copy(compressedSkipGb = value) } },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.usenet_hide_duplicates),
                        description = stringResource(Res.string.usenet_hide_duplicates_description),
                        checked = sources.hideDuplicates,
                        isTablet = isTablet,
                        onCheckedChange = { on -> save { it.copy(hideDuplicates = on) } },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.usenet_indexer_fallback),
                        description = stringResource(Res.string.usenet_indexer_fallback_description),
                        checked = sources.indexerFallback,
                        isTablet = isTablet,
                        onCheckedChange = { on -> save { it.copy(indexerFallback = on) } },
                    )
                    if (sources.indexerFallback) {
                        SettingsGroupDivider(isTablet = isTablet)
                        UsenetFallbackScoreRow(
                            title = stringResource(Res.string.usenet_fallback_min_score_movie),
                            value = sources.fallbackMinScoreMovie,
                            isTablet = isTablet,
                            onValueChange = { v -> save { it.copy(fallbackMinScoreMovie = v) } },
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        UsenetFallbackScoreRow(
                            title = stringResource(Res.string.usenet_fallback_min_score_tv),
                            value = sources.fallbackMinScoreTv,
                            isTablet = isTablet,
                            onValueChange = { v -> save { it.copy(fallbackMinScoreTv = v) } },
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        UsenetFallbackScoreRow(
                            title = stringResource(Res.string.usenet_fallback_min_score_anime),
                            value = sources.fallbackMinScoreAnime,
                            isTablet = isTablet,
                            onValueChange = { v -> save { it.copy(fallbackMinScoreAnime = v) } },
                        )
                    }
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.usenet_show_release_names),
                        checked = sources.showReleaseNames,
                        isTablet = isTablet,
                        onCheckedChange = { on -> save { it.copy(showReleaseNames = on) } },
                    )
                }
            }

            SettingsSection(title = stringResource(Res.string.usenet_section_engine), isTablet = isTablet) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsChoiceRow(
                        title = stringResource(Res.string.usenet_engine_profile),
                        description = null,
                        options = listOf(
                            SettingsChoiceOption("low-memory", stringResource(Res.string.usenet_profile_low)),
                            SettingsChoiceOption("balanced", stringResource(Res.string.usenet_profile_balanced)),
                            SettingsChoiceOption("throughput", stringResource(Res.string.usenet_profile_throughput)),
                        ),
                        selectedValue = engine.profile,
                        isTablet = isTablet,
                        onSelected = { value -> UsenetSettingsRepository.updateEngine { it.copy(profile = value) } },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.usenet_cache_nzb),
                        description = stringResource(Res.string.usenet_cache_nzb_description),
                        checked = engine.cacheNzb,
                        isTablet = isTablet,
                        onCheckedChange = { on -> UsenetSettingsRepository.updateEngine { it.copy(cacheNzb = on) } },
                    )
                }
            }
        }

        if (infoOpen) UsenetInfoDialog(sources = sources, onDismiss = { infoOpen = false })

        when (val open = editor) {
            is UsenetEditor.Provider -> ProviderDialog(
                initial = open.provider,
                isNew = open.isNew,
                allowPrivateNetwork = engine.allowPrivateNetwork,
                onTune = if (open.isNew) null else { { editor = UsenetEditor.TuneProvider(open.provider) } },
                onSave = { provider ->
                    save { it.copy(providers = it.providers.replaceOrAdd(provider) { p -> p.id }) }
                    editor = null
                },
                onDelete = { id ->
                    save { it.copy(providers = it.providers.filterNot { p -> p.id == id }) }
                    editor = null
                },
                onDismiss = { editor = null },
            )
            is UsenetEditor.TuneProvider -> ProviderTuneDialog(
                provider = sources.providers.firstOrNull { it.id == open.provider.id } ?: open.provider,
                allowPrivateNetwork = engine.allowPrivateNetwork,
                pipeline = engine.pipelineDepth,
                onApply = { tuned ->
                    save { it.copy(providers = it.providers.replaceOrAdd(tuned) { p -> p.id }) }
                    editor = null
                },
                onDismiss = { editor = null },
            )
            is UsenetEditor.Indexer -> IndexerDialog(
                initial = open.indexer,
                isNew = open.isNew,
                onSave = { indexer ->
                    save { it.copy(indexers = it.indexers.replaceOrAdd(indexer) { i -> i.id }) }
                    editor = null
                },
                onDelete = { id ->
                    save { it.copy(indexers = it.indexers.filterNot { i -> i.id == id }) }
                    editor = null
                },
                onDismiss = { editor = null },
            )
            null -> Unit
        }
    }
}

@Composable
private fun UsenetField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    example: String? = null,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    // A visible label above the field: the placeholder alone vanishes once the field has a value.
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.nuvio.colors.textMuted,
        )
        NuvioTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = example,
            secret = secret,
            keyboardType = keyboardType,
        )
    }
}

/** The editor's body scrolls; the test result stays pinned under it, above the buttons. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.UsenetDialogBody(
    result: Pair<String, Boolean>?,
    fields: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = fields,
    )
    TestLine(result?.first, result?.second == true)
}

@Composable
private fun TestLine(text: String?, ok: Boolean) {
    text ?: return
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (ok) MaterialTheme.nuvio.colors.accent else MaterialTheme.nuvio.colors.danger,
    )
}

@Composable
private fun ProviderDialog(
    initial: UsenetProvider,
    isNew: Boolean,
    allowPrivateNetwork: Boolean,
    onTune: (() -> Unit)?,
    onSave: (UsenetProvider) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.name) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var tls by remember { mutableStateOf(initial.tls) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }
    var connections by remember { mutableStateOf(initial.connections.toString()) }
    var priority by remember { mutableStateOf(initial.priority) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val messages = providerMessages()

    fun draft() = initial.copy(
        name = name.trim(), host = host.trim(), port = port.trim().toIntOrNull() ?: 0, tls = tls,
        username = username.trim(), password = password, connections = connections.trim().toIntOrNull() ?: 0,
        priority = priority, enabled = enabled,
    )

    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.usenet_dialog_provider),
        actions = {
            if (!isNew) {
                DialogButton(text = stringResource(Res.string.usenet_delete), onClick = { onDelete(initial.id) })
            }
            if (onTune != null) {
                DialogButton(
                    text = stringResource(Res.string.usenet_provider_tune),
                    enabled = !testing,
                    onClick = onTune,
                )
            }
            DialogButton(
                text = stringResource(if (testing) Res.string.usenet_testing else Res.string.usenet_test),
                loading = testing,
                enabled = !testing,
                onClick = {
                    val provider = draft()
                    val invalid = runCatching { provider.validate() }.exceptionOrNull()
                    if (invalid != null) {
                        result = (invalid.message ?: "Invalid provider") to false
                        return@DialogButton
                    }
                    testing = true
                    scope.launch {
                        val outcome = try {
                            NntpProviderTester().test(provider, allowPrivateNetwork)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            ProviderTestResult.UNREACHABLE
                        }
                        result = messages(outcome, provider.username.isBlank()) to (outcome == ProviderTestResult.SUCCESS)
                        testing = false
                    }
                },
            )
            DialogButton(text = stringResource(Res.string.usenet_cancel), onClick = onDismiss)
            DialogButton(
                text = stringResource(Res.string.usenet_save),
                style = DialogButtonStyle.Primary,
                onClick = {
                    val provider = draft()
                    runCatching { provider.validate() }
                        .onSuccess { onSave(provider) }
                        .onFailure { result = (it.message ?: "Invalid provider") to false }
                },
            )
        },
    ) {
        UsenetDialogBody(result) {
            UsenetField(name, { name = it }, stringResource(Res.string.usenet_field_name), example = "Easynews")
            UsenetField(host, { host = it }, stringResource(Res.string.usenet_field_host), example = "news.example.com", keyboardType = KeyboardType.Uri)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) { UsenetField(port, { port = it.filter(Char::isDigit) }, stringResource(Res.string.usenet_field_port), example = if (tls) "563" else "119", keyboardType = KeyboardType.Number) }
                Column(Modifier.weight(1f)) { UsenetField(connections, { connections = it.filter(Char::isDigit) }, stringResource(Res.string.usenet_field_connections), example = "20", keyboardType = KeyboardType.Number) }
            }
            UsenetField(username, { username = it }, stringResource(Res.string.usenet_field_username))
            UsenetField(password, { password = it }, stringResource(Res.string.usenet_field_password), secret = true)
            // isTablet = true: the desktop rows give the control a fixed width; the phone layout
            // lets it take the whole row and squeezes the title into one letter per line.
            SettingsSwitchRow(title = stringResource(Res.string.usenet_field_tls), checked = tls, isTablet = true, onCheckedChange = { tls = it })
            SettingsSwitchRow(title = stringResource(Res.string.usenet_field_enabled), checked = enabled, isTablet = true, onCheckedChange = { enabled = it })
            SettingsChoiceRow(
                title = stringResource(Res.string.usenet_field_priority),
                description = null,
                options = USENET_PRIORITIES.map { SettingsChoiceOption(it, it.toString()) },
                selectedValue = priority,
                isTablet = true,
                onSelected = { priority = it },
            )
        }
    }
}

@Composable
private fun IndexerDialog(
    initial: UsenetIndexer,
    isNew: Boolean,
    onSave: (UsenetIndexer) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.name) }
    var apiUrl by remember { mutableStateOf(initial.apiUrl) }
    var apiPath by remember { mutableStateOf(initial.apiPath) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var priority by remember { mutableStateOf(initial.priority) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var searchMode by remember { mutableStateOf(initial.searchMode) }
    var fetchResultsLimit by remember { mutableStateOf(initial.fetchResultsLimit) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val okText = stringResource(Res.string.usenet_test_ok)
    val authText = stringResource(Res.string.usenet_indexer_auth)
    val failText = stringResource(Res.string.usenet_indexer_unreachable)

    fun draft() = initial.copy(
        name = name.trim(), apiUrl = apiUrl.trim(), apiPath = apiPath.trim(), apiKey = apiKey.trim(),
        priority = priority, enabled = enabled, searchMode = searchMode, fetchResultsLimit = fetchResultsLimit,
    )

    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.usenet_dialog_indexer),
        actions = {
            if (!isNew) {
                DialogButton(text = stringResource(Res.string.usenet_delete), onClick = { onDelete(initial.id) })
            }
            DialogButton(
                text = stringResource(if (testing) Res.string.usenet_testing else Res.string.usenet_test),
                loading = testing,
                enabled = !testing,
                onClick = {
                    val indexer = draft()
                    val invalid = runCatching { indexer.validate() }.exceptionOrNull()
                    if (invalid != null) {
                        result = (invalid.message ?: "Invalid indexer") to false
                        return@DialogButton
                    }
                    testing = true
                    scope.launch {
                        result = try {
                            BuiltInUsenet.client.verify(indexer)
                            okText to true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: com.nuvio.app.features.usenet.NewznabAuthException) {
                            authText to false
                        } catch (_: Exception) {
                            failText to false
                        }
                        testing = false
                    }
                },
            )
            DialogButton(text = stringResource(Res.string.usenet_cancel), onClick = onDismiss)
            DialogButton(
                text = stringResource(Res.string.usenet_save),
                style = DialogButtonStyle.Primary,
                onClick = {
                    val indexer = draft()
                    runCatching { indexer.validate() }
                        .onSuccess { onSave(indexer) }
                        .onFailure { result = (it.message ?: "Invalid indexer") to false }
                },
            )
        },
    ) {
        UsenetDialogBody(result) {
            UsenetField(name, { name = it }, stringResource(Res.string.usenet_field_name), example = "NZBgeek")
            UsenetField(apiUrl, { apiUrl = it }, stringResource(Res.string.usenet_field_api_url), example = "https://api.indexer.example", keyboardType = KeyboardType.Uri)
            UsenetField(apiPath, { apiPath = it }, stringResource(Res.string.usenet_field_api_path), example = DEFAULT_API_PATH)
            UsenetField(apiKey, { apiKey = it }, stringResource(Res.string.usenet_field_api_key), secret = true)
            SettingsChoiceRow(
                title = stringResource(Res.string.usenet_search_mode),
                description = stringResource(Res.string.usenet_search_mode_description),
                options = listOf(
                    SettingsChoiceOption(UsenetSearchMode.AUTO, stringResource(Res.string.usenet_search_mode_auto)),
                    SettingsChoiceOption(UsenetSearchMode.ID, stringResource(Res.string.usenet_search_mode_id)),
                    SettingsChoiceOption(UsenetSearchMode.TITLE, stringResource(Res.string.usenet_search_mode_title)),
                    SettingsChoiceOption(UsenetSearchMode.BOTH, stringResource(Res.string.usenet_search_mode_both)),
                ),
                selectedValue = searchMode,
                isTablet = true,
                onSelected = { searchMode = it },
            )
            SettingsChoiceRow(
                title = stringResource(Res.string.usenet_indexer_fetch_limit),
                description = stringResource(Res.string.usenet_indexer_fetch_limit_description),
                options = listOf(
                    SettingsChoiceOption<Int?>(null, stringResource(Res.string.usenet_indexer_fetch_limit_default)),
                ) + UsenetSourceConfiguration.INDEXER_RESULT_CHOICES.map { value ->
                    SettingsChoiceOption<Int?>(value, value.toString())
                } + listOf(
                    SettingsChoiceOption<Int?>(0, stringResource(Res.string.usenet_unlimited)),
                ),
                selectedValue = fetchResultsLimit,
                isTablet = true,
                onSelected = { fetchResultsLimit = it },
            )
            SettingsSwitchRow(title = stringResource(Res.string.usenet_field_enabled), checked = enabled, isTablet = true, onCheckedChange = { enabled = it })
            SettingsChoiceRow(
                title = stringResource(Res.string.usenet_field_priority),
                description = null,
                options = USENET_PRIORITIES.map { SettingsChoiceOption(it, it.toString()) },
                selectedValue = priority,
                isTablet = true,
                onSelected = { priority = it },
            )
        }
    }
}

@Composable
private fun UsenetFallbackScoreRow(
    title: String,
    value: Int,
    isTablet: Boolean,
    onValueChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = if (isTablet) DesktopRowVerticalPadding else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (isTablet) SettingsRowTextGap else 12.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textPrimary,
        )
        NuvioNumberStepper(
            value = value.toDouble(),
            step = 20.0,
            min = 0.0,
            max = 1000.0,
            modifier = if (isTablet) Modifier.width(DesktopControlWidth) else Modifier,
            fillWidth = isTablet,
            onChange = { onValueChange(it.toInt()) },
            format = { it.toInt().toString() },
        )
    }
}

@Composable
private fun ProviderTuneDialog(
    provider: UsenetProvider,
    allowPrivateNetwork: Boolean,
    pipeline: Int,
    onApply: (UsenetProvider) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val tuner = remember { NntpProviderTuner() }
    val messages = providerMessages()
    var running by remember { mutableStateOf(false) }
    var stopped by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<TuneProgress?>(null) }
    var outcome by remember { mutableStateOf<TuneOutcome?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose { job?.cancel() }
    }

    fun start() {
        job?.cancel()
        running = true
        stopped = false
        progress = null
        outcome = null
        job = scope.launch {
            try {
                val result = tuner.tune(provider, allowPrivateNetwork, pipeline) { update ->
                    progress = update
                }
                outcome = result
            } catch (_: CancellationException) {
                stopped = true
            } finally {
                running = false
            }
        }
    }

    fun stop() {
        stopped = true
        job?.cancel()
    }

    val success = outcome as? TuneOutcome.Success
    val hasChanges = success != null && (
        success.result.port != provider.port ||
            success.result.tls != provider.tls ||
            success.result.connections != provider.connections
        )
    val notStarted = !running && !stopped && outcome == null
    val readings = success?.result?.readings ?: progress?.readings.orEmpty()

    NuvioModalDialog(
        onDismissRequest = { if (running) stop() else onDismiss() },
        title = stringResource(Res.string.usenet_provider_tune_title, provider.name.ifBlank { provider.host }),
        actions = {
            if (running) {
                DialogButton(
                    text = stringResource(Res.string.usenet_provider_tune_stop),
                    style = DialogButtonStyle.Primary,
                    onClick = ::stop,
                )
            } else if (hasChanges) {
                val tuned = success.result
                DialogButton(text = stringResource(Res.string.usenet_cancel), onClick = onDismiss)
                DialogButton(
                    text = stringResource(Res.string.usenet_provider_tune_apply),
                    style = DialogButtonStyle.Primary,
                    onClick = {
                        onApply(
                            provider.copy(
                                port = tuned.port,
                                tls = tuned.tls,
                                connections = tuned.connections,
                            ),
                        )
                    },
                )
            } else if (outcome != null || stopped) {
                DialogButton(text = stringResource(Res.string.usenet_provider_tune_again), onClick = ::start)
                DialogButton(
                    text = stringResource(Res.string.usenet_provider_tune_close),
                    style = DialogButtonStyle.Primary,
                    onClick = onDismiss,
                )
            } else {
                DialogButton(text = stringResource(Res.string.usenet_cancel), onClick = onDismiss)
                DialogButton(
                    text = stringResource(Res.string.usenet_provider_tune_start),
                    style = DialogButtonStyle.Primary,
                    onClick = ::start,
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (notStarted) {
                Text(
                    text = stringResource(Res.string.usenet_provider_tune_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.nuvio.colors.textSecondary,
                )
            }
            if (running) {
                Text(
                    text = tuneProgressText(progress),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.nuvio.colors.textPrimary,
                )
            }
            if (success != null) {
                val result = success.result
                Text(
                    text = if (result.limit != null) {
                        stringResource(Res.string.usenet_provider_tune_limit, result.limit)
                    } else {
                        stringResource(Res.string.usenet_provider_tune_no_limit, result.tried)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.nuvio.colors.textPrimary,
                )
                if (result.limit != null && provider.connections > result.limit) {
                    Text(
                        text = stringResource(Res.string.usenet_provider_tune_over_limit, provider.connections),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.nuvio.colors.textPrimary,
                    )
                }
                val fastestMbps = result.readings
                    .filter { it.port == result.port && it.tls == result.tls && it.connections == result.fastestConnections }
                    .map { it.mbps }
                    .takeIf { it.isNotEmpty() }
                    ?.average()
                    ?: result.mbps
                Text(
                    text = stringResource(Res.string.usenet_provider_tune_fastest, result.fastestConnections, formatTuneMbps(fastestMbps)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.nuvio.colors.textPrimary,
                )
                Text(
                    text = if (hasChanges) {
                        stringResource(
                            Res.string.usenet_provider_tune_recommended,
                            result.port,
                            result.connections,
                            provider.port,
                            provider.connections,
                        )
                    } else {
                        stringResource(Res.string.usenet_provider_tune_unchanged)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.nuvio.colors.textPrimary,
                )
                Text(
                    text = stringResource(Res.string.usenet_provider_tune_data, UsenetInfoFormat.bytes(result.downloadedBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.nuvio.colors.textSecondary,
                )
            }
            val failed = outcome as? TuneOutcome.Failed
            if (failed != null) {
                val errorText = when (failed.reason) {
                    TuneFailure.CONNECT -> messages(failed.connect ?: ProviderTestResult.UNREACHABLE, provider.username.isBlank())
                    TuneFailure.TEST_DATA -> stringResource(Res.string.usenet_provider_tune_test_data)
                    TuneFailure.MISSING_ARTICLES -> stringResource(Res.string.usenet_provider_tune_missing)
                    TuneFailure.TOO_SLOW -> stringResource(Res.string.usenet_provider_tune_too_slow)
                }
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.nuvio.colors.danger,
                )
            }
            if (stopped && !running && outcome == null) {
                Text(
                    text = stringResource(Res.string.usenet_provider_tune_stopped),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.nuvio.colors.textSecondary,
                )
            }
            readings.forEach { reading ->
                Text(
                    text = tuneReadingText(reading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.nuvio.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun tuneProgressText(progress: TuneProgress?): String = when (progress?.phase) {
    null -> stringResource(Res.string.usenet_provider_tune_preparing)
    TuneProgress.Phase.LIMIT -> stringResource(Res.string.usenet_provider_tune_limit_running, progress.connections)
    TuneProgress.Phase.PORT -> stringResource(Res.string.usenet_provider_tune_port_running, progress.port, progress.connections)
    TuneProgress.Phase.CONNECTIONS -> stringResource(Res.string.usenet_provider_tune_connections_running, progress.connections)
}

@Composable
private fun tuneReadingText(reading: TuneReading): String =
    stringResource(Res.string.usenet_provider_tune_reading, reading.port, reading.connections, formatTuneMbps(reading.mbps))

private fun formatTuneMbps(value: Double): String = String.format(Locale.getDefault(), "%.0f", value)

@Composable
private fun providerMessages(): (ProviderTestResult, Boolean) -> String {
    val ok = stringResource(Res.string.usenet_test_ok)
    val unreachable = stringResource(Res.string.usenet_provider_unreachable)
    val tls = stringResource(Res.string.usenet_provider_tls)
    val auth = stringResource(Res.string.usenet_provider_auth)
    val anonymous = stringResource(Res.string.usenet_provider_needs_login)
    val refused = stringResource(Res.string.usenet_provider_refused)
    val private = stringResource(Res.string.usenet_provider_private)
    return { result, isAnonymous ->
        when (result) {
            ProviderTestResult.SUCCESS -> ok
            ProviderTestResult.UNREACHABLE -> unreachable
            ProviderTestResult.TLS -> tls
            ProviderTestResult.AUTH -> if (isAnonymous) anonymous else auth
            ProviderTestResult.REFUSED -> refused
            ProviderTestResult.PRIVATE_NETWORK -> private
        }
    }
}

