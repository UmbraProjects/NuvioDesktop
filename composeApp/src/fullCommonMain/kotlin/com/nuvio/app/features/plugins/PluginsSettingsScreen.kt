package com.nuvio.app.features.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import com.nuvio.app.core.ui.NuvioIconActionButton
import com.nuvio.app.core.ui.NuvioInfoBadge
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioPanelBackdrop
import com.nuvio.app.isDesktop
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.*
import nuvio.composeapp.generated.resources.plugins_badge_disabled
import nuvio.composeapp.generated.resources.plugins_badge_enabled
import nuvio.composeapp.generated.resources.plugins_badge_providers
import nuvio.composeapp.generated.resources.plugins_badge_refreshing
import nuvio.composeapp.generated.resources.plugins_badge_repos
import nuvio.composeapp.generated.resources.plugins_badge_tmdb_key_missing
import nuvio.composeapp.generated.resources.plugins_badge_tmdb_key_set
import nuvio.composeapp.generated.resources.plugins_button_install_repo
import nuvio.composeapp.generated.resources.plugins_button_installing
import nuvio.composeapp.generated.resources.plugins_button_test_provider
import nuvio.composeapp.generated.resources.plugins_button_testing
import nuvio.composeapp.generated.resources.plugins_cd_delete_repo
import nuvio.composeapp.generated.resources.plugins_cd_refresh_repo
import nuvio.composeapp.generated.resources.plugins_empty_providers
import nuvio.composeapp.generated.resources.plugins_empty_repos_subtitle
import nuvio.composeapp.generated.resources.plugins_empty_repos_title
import nuvio.composeapp.generated.resources.plugins_enable_globally_desc
import nuvio.composeapp.generated.resources.plugins_enable_globally_title
import nuvio.composeapp.generated.resources.plugins_error_enter_repo_url
import nuvio.composeapp.generated.resources.plugins_group_by_repo_desc
import nuvio.composeapp.generated.resources.plugins_group_by_repo_title
import nuvio.composeapp.generated.resources.plugins_input_manifest_placeholder
import nuvio.composeapp.generated.resources.plugins_message_installed
import nuvio.composeapp.generated.resources.plugins_provider_disabled_by_repo
import nuvio.composeapp.generated.resources.plugins_provider_no_description
import nuvio.composeapp.generated.resources.plugins_provider_version
import nuvio.composeapp.generated.resources.plugins_repo_fallback_label
import nuvio.composeapp.generated.resources.plugins_repo_version
import nuvio.composeapp.generated.resources.plugins_section_add_repo
import nuvio.composeapp.generated.resources.plugins_section_installed_repos
import nuvio.composeapp.generated.resources.plugins_section_overview
import nuvio.composeapp.generated.resources.plugins_section_providers
import nuvio.composeapp.generated.resources.plugins_test_error_title
import nuvio.composeapp.generated.resources.plugins_test_failed
import nuvio.composeapp.generated.resources.plugins_test_results_count
import nuvio.composeapp.generated.resources.plugins_tmdb_required_message
import org.jetbrains.compose.resources.stringResource
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import com.nuvio.app.core.ui.trackTextInputFocus
import com.nuvio.app.core.ui.accentFill
import com.nuvio.app.core.ui.accentBrush

@Composable
fun PluginsSettingsPageContent(
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        PluginRepository.initialize()
    }

    val uiState by PluginRepository.uiState.collectAsStateWithLifecycle()
    val tmdbSettings by remember {
        TmdbSettingsRepository.ensureLoaded()
        TmdbSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    var repositoryUrl by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var isAdding by remember { mutableStateOf(false) }

    var testingScraperId by remember { mutableStateOf<String?>(null) }
    val testResults = remember { mutableStateMapOf<String, List<PluginRuntimeResult>>() }
    var configuringScraper by remember { mutableStateOf<PluginScraper?>(null) }
    var configuringLayout by remember { mutableStateOf<String?>(null) }

    val sortedRepos = remember(uiState.repositories) {
        uiState.repositories.sortedBy { it.name.lowercase() }
    }
    val hasTmdbApiKey = tmdbSettings.hasApiKey
    val repositoryNameByUrl = remember(sortedRepos) {
        sortedRepos.associate { it.manifestUrl to it.name }
    }
    val sortedScrapers = remember(uiState.scrapers, repositoryNameByUrl) {
        uiState.scrapers.sortedWith(
            compareBy<PluginScraper>(
                { repositoryNameByUrl[it.repositoryUrl]?.lowercase() ?: it.repositoryUrl.lowercase() },
                { it.name.lowercase() },
            ),
        )
    }

    val repoFallbackLabel = stringResource(Res.string.plugins_repo_fallback_label)
    val testFailedDefault = stringResource(Res.string.plugins_test_failed)
    val testErrorTitle = stringResource(Res.string.plugins_test_error_title)
    val installedTemplate = stringResource(Res.string.plugins_message_installed)
    val enterRepoUrlError = stringResource(Res.string.plugins_error_enter_repo_url)
    val installRepository: () -> Unit = {
        val requested = repositoryUrl.trim()
        if (requested.isBlank()) {
            message = enterRepoUrlError
        } else {
            isAdding = true
            message = null
            coroutineScope.launch {
                when (val result = PluginRepository.addRepository(requested)) {
                    is AddPluginRepositoryResult.Success -> {
                        repositoryUrl = ""
                        message = installedTemplate.replace("%1\$s", result.repository.name)
                    }
                    is AddPluginRepositoryResult.Error -> {
                        message = result.message
                    }
                }
                isAdding = false
            }
        }
    }
    val openScraperSettings: (PluginScraper) -> Unit = { scraper ->
        coroutineScope.launch {
            PluginRuntime.getPluginSettingsLayout(scraper.code, scraper.id)?.let { layout ->
                configuringScraper = scraper
                configuringLayout = layout
            }
        }
    }

    if (isDesktop) {
        DesktopPluginsManager(
            uiState = uiState,
            repositories = sortedRepos,
            scrapers = sortedScrapers,
            repositoryNameByUrl = repositoryNameByUrl,
            repositoryUrl = repositoryUrl,
            message = message,
            isAdding = isAdding,
            hasTmdbApiKey = hasTmdbApiKey,
            repoFallbackLabel = repoFallbackLabel,
            onRepositoryUrlChange = {
                repositoryUrl = it
                message = null
            },
            onInstallRepository = installRepository,
            onConfigureScraper = openScraperSettings,
            modifier = modifier,
        )
        if (configuringScraper != null && configuringLayout != null) {
            PluginSettingsDialog(
                scraperId = configuringScraper!!.id,
                scraperName = configuringScraper!!.name,
                layoutJson = configuringLayout!!,
                onDismiss = {
                    configuringScraper = null
                    configuringLayout = null
                },
            )
        }
        return
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NuvioSectionLabel(stringResource(Res.string.plugins_section_overview))
        NuvioSurfaceCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NuvioInfoBadge(text = stringResource(Res.string.plugins_badge_repos, sortedRepos.size))
                NuvioInfoBadge(text = stringResource(Res.string.plugins_badge_providers, sortedScrapers.size))
                NuvioInfoBadge(
                    text = if (uiState.pluginsEnabled) {
                        stringResource(Res.string.plugins_badge_enabled)
                    } else {
                        stringResource(Res.string.plugins_badge_disabled)
                    },
                )
                NuvioInfoBadge(
                    text = if (hasTmdbApiKey) {
                        stringResource(Res.string.plugins_badge_tmdb_key_set)
                    } else {
                        stringResource(Res.string.plugins_badge_tmdb_key_missing)
                    },
                )
            }
            if (!hasTmdbApiKey) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(Res.string.plugins_tmdb_required_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.plugins_enable_globally_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(Res.string.plugins_enable_globally_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = uiState.pluginsEnabled,
                    onCheckedChange = { PluginRepository.setPluginsEnabled(it) },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.plugins_group_by_repo_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(Res.string.plugins_group_by_repo_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = uiState.groupStreamsByRepository,
                    onCheckedChange = { PluginRepository.setGroupStreamsByRepository(it) },
                )
            }
        }

        NuvioSectionLabel(stringResource(Res.string.plugins_section_add_repo))
        NuvioSurfaceCard {
            NuvioInputField(
                value = repositoryUrl,
                onValueChange = {
                    repositoryUrl = it
                    message = null
                },
                placeholder = stringResource(Res.string.plugins_input_manifest_placeholder),
            )
            Spacer(modifier = Modifier.height(16.dp))
            NuvioPrimaryButton(
                text = if (isAdding) {
                    stringResource(Res.string.plugins_button_installing)
                } else {
                    stringResource(Res.string.plugins_button_install_repo)
                },
                enabled = repositoryUrl.isNotBlank() && !isAdding,
                onClick = installRepository,
            )
            message?.let { text ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        NuvioSectionLabel(stringResource(Res.string.plugins_section_installed_repos))
        if (sortedRepos.isEmpty()) {
            NuvioSurfaceCard {
                Text(
                    text = stringResource(Res.string.plugins_empty_repos_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.plugins_empty_repos_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            sortedRepos.forEach { repo ->
                NuvioSurfaceCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = repo.name,
                                style = MaterialTheme.typography.headlineLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            repo.version?.let { version ->
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = stringResource(Res.string.plugins_repo_version, version),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = repo.manifestUrl,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NuvioIconActionButton(
                                icon = Icons.Rounded.Refresh,
                                contentDescription = stringResource(Res.string.plugins_cd_refresh_repo),
                                tint = MaterialTheme.colorScheme.primary,
                                onClick = { PluginRepository.refreshRepository(repo.manifestUrl, pushAfterRefresh = true) },
                            )
                            NuvioIconActionButton(
                                icon = Icons.Rounded.Delete,
                                contentDescription = stringResource(Res.string.plugins_cd_delete_repo),
                                tint = MaterialTheme.colorScheme.error,
                                onClick = { PluginRepository.removeRepository(repo.manifestUrl) },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        NuvioInfoBadge(text = stringResource(Res.string.plugins_badge_providers, repo.scraperCount))
                        if (repo.isRefreshing) {
                            NuvioInfoBadge(text = stringResource(Res.string.plugins_badge_refreshing))
                        }
                    }
                    repo.errorMessage?.let { errorMessage ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        NuvioSectionLabel(stringResource(Res.string.plugins_section_providers))
        if (sortedScrapers.isEmpty()) {
            NuvioSurfaceCard {
                Text(
                    text = stringResource(Res.string.plugins_empty_providers),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            sortedScrapers.forEach { scraper ->
                val scraperResults = testResults[scraper.id]
                val isTestingThisScraper = testingScraperId == scraper.id
                val repositoryName = repositoryNameByUrl[scraper.repositoryUrl]
                    ?: scraper.repositoryUrl.fallbackRepositoryLabel(repoFallbackLabel)

                NuvioSurfaceCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Extension,
                                contentDescription = null,
                                tint = if (scraper.enabled) Color(0xFF68B76A) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = repositoryName,
                                    style = MaterialTheme.typography.labelMedium.accentBrush(),
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = scraper.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = scraper.description.ifBlank {
                                        stringResource(Res.string.plugins_provider_no_description)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (scraper.hasSettings) {
                                IconButton(onClick = { openScraperSettings(scraper) }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Settings,
                                        contentDescription = stringResource(Res.string.plugins_provider_settings),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Switch(
                                checked = scraper.enabled,
                                onCheckedChange = { PluginRepository.toggleScraper(scraper.id, it) },
                                enabled = scraper.manifestEnabled,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        NuvioInfoBadge(text = scraper.supportedTypes.joinToString(" | "))
                        NuvioInfoBadge(text = stringResource(Res.string.plugins_provider_version, scraper.version))
                        if (!scraper.manifestEnabled) {
                            NuvioInfoBadge(text = stringResource(Res.string.plugins_provider_disabled_by_repo))
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    NuvioPrimaryButton(
                        text = if (isTestingThisScraper) {
                            stringResource(Res.string.plugins_button_testing)
                        } else {
                            stringResource(Res.string.plugins_button_test_provider)
                        },
                        enabled = hasTmdbApiKey && !isTestingThisScraper,
                        onClick = {
                            testingScraperId = scraper.id
                            coroutineScope.launch {
                                PluginRepository.testScraper(scraper.id)
                                    .onSuccess { results ->
                                        testResults[scraper.id] = results
                                    }
                                    .onFailure { error ->
                                        testResults[scraper.id] = listOf(
                                            PluginRuntimeResult(
                                                title = testErrorTitle,
                                                name = error.message ?: testFailedDefault,
                                                url = "about:error",
                                            ),
                                        )
                                    }
                                testingScraperId = null
                            }
                        },
                    )

                    if (!scraperResults.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(Res.string.plugins_test_results_count, scraperResults.size),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        scraperResults.take(8).forEach { result ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Bolt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = result.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = result.url,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
    }

    if (configuringScraper != null && configuringLayout != null) {
        PluginSettingsDialog(
            scraperId = configuringScraper!!.id,
            scraperName = configuringScraper!!.name,
            layoutJson = configuringLayout!!,
            onDismiss = {
                configuringScraper = null
                configuringLayout = null
            },
        )
    }
}

@Composable
private fun DesktopPluginsManager(
    uiState: PluginsUiState,
    repositories: List<PluginRepositoryItem>,
    scrapers: List<PluginScraper>,
    repositoryNameByUrl: Map<String, String>,
    repositoryUrl: String,
    message: String?,
    isAdding: Boolean,
    hasTmdbApiKey: Boolean,
    repoFallbackLabel: String,
    onRepositoryUrlChange: (String) -> Unit,
    onInstallRepository: () -> Unit,
    onConfigureScraper: (PluginScraper) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var enabledOnly by rememberSaveable { mutableStateOf(false) }
    var reposOnly by rememberSaveable { mutableStateOf(false) }
    var providersOnly by rememberSaveable { mutableStateOf(false) }
    var showAddRepository by rememberSaveable { mutableStateOf(false) }
    val trimmedQuery = query.trim()
    val visibleRepositories = repositories.filter { repo ->
        val matchesQuery = trimmedQuery.isBlank() ||
            repo.name.contains(trimmedQuery, ignoreCase = true) ||
            repo.manifestUrl.contains(trimmedQuery, ignoreCase = true) ||
            repo.description?.contains(trimmedQuery, ignoreCase = true) == true
        !providersOnly && matchesQuery
    }
    val visibleScrapers = scrapers.filter { scraper ->
        val repositoryName = repositoryNameByUrl[scraper.repositoryUrl]
            ?: scraper.repositoryUrl.fallbackRepositoryLabel(repoFallbackLabel)
        val matchesQuery = trimmedQuery.isBlank() ||
            scraper.name.contains(trimmedQuery, ignoreCase = true) ||
            scraper.description.contains(trimmedQuery, ignoreCase = true) ||
            repositoryName.contains(trimmedQuery, ignoreCase = true) ||
            scraper.supportedTypes.any { it.contains(trimmedQuery, ignoreCase = true) }
        !reposOnly && (!enabledOnly || scraper.enabled) && matchesQuery
    }
    val tokens = MaterialTheme.nuvio

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(Res.string.plugins_section_providers),
                    style = MaterialTheme.typography.headlineSmall,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(
                        Res.string.plugins_repository_provider_count,
                        repositories.size,
                        scrapers.size,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                    modifier = Modifier.padding(bottom = 2.dp),
                    maxLines = 1,
                )
            }
            DesktopPluginAddButton(
                selected = showAddRepository,
                onClick = { showAddRepository = !showAddRepository },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DesktopPluginSearchField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
            )
            DesktopPluginSegmentedFilters(
                enabledOnly = enabledOnly,
                onEnabledOnlyChange = { enabledOnly = it },
                reposOnly = reposOnly,
                onReposOnlyChange = {
                    reposOnly = it
                    if (it) providersOnly = false
                },
                providersOnly = providersOnly,
                onProvidersOnlyChange = {
                    providersOnly = it
                    if (it) reposOnly = false
                },
            )
        }

        if (showAddRepository) {
            DesktopPluginAddRepositoryPopover(
                repositoryUrl = repositoryUrl,
                message = message,
                isAdding = isAdding,
                onRepositoryUrlChange = onRepositoryUrlChange,
                onInstallRepository = onInstallRepository,
            )
        }

        if (!providersOnly) {
            DesktopPluginRepositoriesTable(
                repositories = visibleRepositories,
                totalRepositoryCount = repositories.size,
            )
        }
        if (!reposOnly) {
            DesktopPluginProvidersTable(
                uiState = uiState,
                scrapers = visibleScrapers,
                repositoryNameByUrl = repositoryNameByUrl,
                repoFallbackLabel = repoFallbackLabel,
                hasTmdbApiKey = hasTmdbApiKey,
                onConfigureScraper = onConfigureScraper,
            )
        }
    }
}

@Composable
private fun DesktopPluginSegmentedFilters(
    enabledOnly: Boolean,
    onEnabledOnlyChange: (Boolean) -> Unit,
    reposOnly: Boolean,
    onReposOnlyChange: (Boolean) -> Unit,
    providersOnly: Boolean,
    onProvidersOnlyChange: (Boolean) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier.height(40.dp),
        shape = RoundedCornerShape(10.dp),
        color = tokens.colors.surfaceCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, tokens.colors.borderDefault),
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DesktopPluginFilterSegment(stringResource(Res.string.plugins_badge_enabled), enabledOnly) { onEnabledOnlyChange(!enabledOnly) }
            DesktopPluginFilterSegment(stringResource(Res.string.plugins_filter_repos), reposOnly) { onReposOnlyChange(!reposOnly) }
            DesktopPluginFilterSegment(stringResource(Res.string.plugins_filter_providers), providersOnly) { onProvidersOnlyChange(!providersOnly) }
        }
    }
}

@Composable
private fun DesktopPluginFilterSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(
                if (selected) tokens.colors.accentFill(0.16f) else SolidColor(Color.Transparent),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) tokens.colors.accent else tokens.colors.textMuted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun DesktopPluginSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .height(40.dp)
            .background(tokens.colors.surfaceCard, RoundedCornerShape(10.dp))
            .border(
                width = 1.dp,
                color = if (focused) tokens.colors.borderFocus else tokens.colors.borderDefault,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { focusRequester.requestFocus() }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = tokens.colors.textMuted,
                modifier = Modifier.size(20.dp),
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused }
                    .trackTextInputFocus(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = tokens.colors.textPrimary),
                cursorBrush = SolidColor(tokens.colors.accent),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isBlank()) {
                            Text(
                                text = stringResource(Res.string.plugins_filter_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = tokens.colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
    }
}

@Composable
private fun DesktopPluginAddButton(
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(12.dp)
    Surface(
        modifier = Modifier
            .height(44.dp)
            // Painted as a brush rather than through Surface's colour so a gradient accent reaches
            // this pill too; accentFill is a SolidColor on the flat themes and renders identically.
            // The open state keeps its flat accentStrong, which is what distinguishes it.
            .background(
                brush = if (selected) SolidColor(tokens.colors.accentStrong) else tokens.colors.accentFill,
                shape = shape,
            )
            .clickable(onClick = onClick),
        shape = shape,
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                tint = tokens.colors.background,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(Res.string.plugins_button_install_repo),
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.background,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DesktopPluginAddRepositoryPopover(
    repositoryUrl: String,
    message: String?,
    isAdding: Boolean,
    onRepositoryUrlChange: (String) -> Unit,
    onInstallRepository: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(10.dp)
    Surface(
        modifier = Modifier.fillMaxWidth().nuvioPanelBackdrop(tokens.colors.surface, shape),
        shape = shape,
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, tokens.colors.borderDefault),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NuvioInputField(
                    value = repositoryUrl,
                    onValueChange = onRepositoryUrlChange,
                    placeholder = stringResource(Res.string.plugins_input_manifest_placeholder),
                    modifier = Modifier.weight(1f),
                )
                NuvioPrimaryButton(
                    text = if (isAdding) {
                        stringResource(Res.string.plugins_button_installing)
                    } else {
                        stringResource(Res.string.plugins_button_install_repo)
                    },
                    enabled = repositoryUrl.isNotBlank() && !isAdding,
                    onClick = onInstallRepository,
                    modifier = Modifier.width(148.dp),
                )
            }
            message?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun DesktopPluginRepositoriesTable(
    repositories: List<PluginRepositoryItem>,
    totalRepositoryCount: Int,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(10.dp)
    Surface(
        modifier = Modifier.fillMaxWidth().nuvioPanelBackdrop(tokens.colors.surface, shape),
        shape = shape,
        color = Color.Transparent,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp)) {
            DesktopPluginTableTitle(stringResource(Res.string.plugins_section_installed_repos))
            HorizontalDivider(color = tokens.colors.borderDefault)
            if (totalRepositoryCount == 0) {
                DesktopPluginEmptyTable(
                    title = stringResource(Res.string.plugins_empty_repos_title),
                    subtitle = stringResource(Res.string.plugins_empty_repos_subtitle),
                )
            } else if (repositories.isEmpty()) {
                DesktopPluginEmptyTable(
                    title = stringResource(Res.string.plugins_filtered_repositories_empty_title),
                    subtitle = stringResource(Res.string.plugins_filtered_repositories_empty_subtitle),
                )
            } else {
                repositories.forEachIndexed { index, repo ->
                    DesktopPluginRepositoryRow(repo)
                    if (index != repositories.lastIndex) {
                        HorizontalDivider(color = tokens.colors.borderDefault.copy(alpha = 0.72f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopPluginProvidersTable(
    uiState: PluginsUiState,
    scrapers: List<PluginScraper>,
    repositoryNameByUrl: Map<String, String>,
    repoFallbackLabel: String,
    hasTmdbApiKey: Boolean,
    onConfigureScraper: (PluginScraper) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(10.dp)
    Surface(
        modifier = Modifier.fillMaxWidth().nuvioPanelBackdrop(tokens.colors.surface, shape),
        shape = shape,
        color = Color.Transparent,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp)) {
            DesktopPluginProvidersHeader(
                uiState = uiState,
                hasTmdbApiKey = hasTmdbApiKey,
            )
            HorizontalDivider(color = tokens.colors.borderDefault)
            if (scrapers.isEmpty()) {
                DesktopPluginEmptyTable(
                    title = stringResource(Res.string.plugins_empty_providers),
                    subtitle = stringResource(Res.string.plugins_filtered_providers_empty_subtitle),
                )
            } else {
                scrapers.forEachIndexed { index, scraper ->
                    val repositoryName = repositoryNameByUrl[scraper.repositoryUrl]
                        ?: scraper.repositoryUrl.fallbackRepositoryLabel(repoFallbackLabel)
                    DesktopPluginProviderRow(
                        scraper = scraper,
                        repositoryName = repositoryName,
                        onConfigure = onConfigureScraper,
                    )
                    if (index != scrapers.lastIndex) {
                        HorizontalDivider(color = tokens.colors.borderDefault.copy(alpha = 0.72f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopPluginProvidersHeader(
    uiState: PluginsUiState,
    hasTmdbApiKey: Boolean,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = stringResource(Res.string.plugins_section_providers),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textMuted,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        DesktopPluginDotSetting(
            label = if (uiState.pluginsEnabled) {
                stringResource(Res.string.plugins_badge_enabled)
            } else {
                stringResource(Res.string.plugins_badge_disabled)
            },
            active = uiState.pluginsEnabled,
            onClick = { PluginRepository.setPluginsEnabled(!uiState.pluginsEnabled) },
        )
        DesktopPluginDotSetting(
            label = stringResource(Res.string.plugins_group_by_repo),
            active = uiState.groupStreamsByRepository,
            onClick = { PluginRepository.setGroupStreamsByRepository(!uiState.groupStreamsByRepository) },
        )
        DesktopPluginStatusIndicator(
            label = if (hasTmdbApiKey) {
                stringResource(Res.string.plugins_badge_tmdb_key_set)
            } else {
                stringResource(Res.string.plugins_badge_tmdb_key_missing)
            },
            active = hasTmdbApiKey,
            modifier = Modifier.width(150.dp),
        )
    }
}

@Composable
private fun DesktopPluginDotSetting(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (active) tokens.colors.accent else tokens.colors.textMuted),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (active) tokens.colors.textPrimary else tokens.colors.textMuted,
            maxLines = 1,
        )
    }
}

@Composable
private fun DesktopPluginTableTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.nuvio.colors.textMuted,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
    )
}

@Composable
private fun DesktopPluginRepositoryRow(repo: PluginRepositoryItem) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PluginMonogramBadge(imageUrl = null, title = repo.name, available = repo.errorMessage == null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = repo.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = repo.version?.let { stringResource(Res.string.plugins_repo_version, it) } ?: repo.manifestUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                repo.errorMessage?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        DesktopPluginStatusIndicator(
            label = if (repo.isRefreshing) {
                stringResource(Res.string.plugins_badge_refreshing)
            } else {
                stringResource(Res.string.plugins_badge_providers, repo.scraperCount)
            },
            active = repo.errorMessage == null,
            modifier = Modifier.width(150.dp),
        )
        DesktopPluginActionCluster(modifier = Modifier.width(86.dp)) {
            DesktopPluginActionButton(
                icon = Icons.Rounded.Refresh,
                contentDescription = stringResource(Res.string.plugins_cd_refresh_repo),
                tint = tokens.colors.accent,
                onClick = { PluginRepository.refreshRepository(repo.manifestUrl, pushAfterRefresh = true) },
            )
            DesktopPluginActionButton(
                icon = Icons.Rounded.Delete,
                contentDescription = stringResource(Res.string.plugins_cd_delete_repo),
                tint = MaterialTheme.colorScheme.error,
                onClick = { PluginRepository.removeRepository(repo.manifestUrl) },
            )
        }
    }
}

@Composable
private fun DesktopPluginProviderRow(
    scraper: PluginScraper,
    repositoryName: String,
    onConfigure: (PluginScraper) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PluginMonogramBadge(
                    imageUrl = scraper.logo,
                    title = scraper.name,
                    available = scraper.manifestEnabled,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = repositoryName,
                        style = MaterialTheme.typography.bodySmall.accentBrush(),
                        color = tokens.colors.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = scraper.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = tokens.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = scraper.description.ifBlank {
                            stringResource(Res.string.plugins_provider_no_description)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = scraper.supportedTypes.joinToString(" | "),
                modifier = Modifier.width(120.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            DesktopPluginStatusIndicator(
                label = if (!scraper.manifestEnabled) {
                    stringResource(Res.string.plugins_provider_disabled_by_repo)
                } else if (scraper.enabled) {
                    stringResource(Res.string.plugins_badge_enabled)
                } else {
                    stringResource(Res.string.plugins_badge_disabled)
                },
                active = scraper.enabled && scraper.manifestEnabled,
                modifier = Modifier.width(120.dp),
                onClick = if (scraper.manifestEnabled) {
                    { PluginRepository.toggleScraper(scraper.id, !scraper.enabled) }
                } else {
                    null
                },
            )
            if (scraper.hasSettings) {
                DesktopPluginActionCluster(modifier = Modifier.width(44.dp)) {
                    DesktopPluginActionButton(
                        icon = Icons.Rounded.Settings,
                        contentDescription = stringResource(Res.string.plugins_provider_settings),
                        tint = tokens.colors.accent,
                        onClick = { onConfigure(scraper) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopPluginStatusIndicator(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = onClick != null) { onClick?.invoke() }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (active) tokens.colors.accent else tokens.colors.textMuted),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (active) tokens.colors.textPrimary else tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DesktopPluginActionCluster(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = tokens.colors.surfaceCard.copy(alpha = 0.5f),
            border = androidx.compose.foundation.BorderStroke(1.dp, tokens.colors.borderDefault),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                content()
            }
        }
    }
}

@Composable
private fun DesktopPluginActionButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.nuvio.colors.textMuted,
    onClick: () -> Unit,
) {
    IconButton(
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.size(32.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.28f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun PluginMonogramBadge(
    imageUrl: String?,
    title: String,
    available: Boolean,
) {
    val tokens = MaterialTheme.nuvio
    val hasLogo = !imageUrl.isNullOrBlank()
    val badgeColor = when {
        hasLogo -> tokens.colors.surfaceCard
        available -> pluginBadgeColor(title)
        else -> tokens.colors.surfaceCard
    }
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(badgeColor),
        contentAlignment = Alignment.Center,
    ) {
        if (hasLogo) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(5.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                text = pluginMonogram(title),
                style = MaterialTheme.typography.titleMedium,
                color = if (available) Color.White else tokens.colors.textMuted,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private val PluginBadgePalette = listOf(
    Color(0xFF1F9E8E),
    Color(0xFF3A4654),
    Color(0xFF2E5C86),
    Color(0xFF3E3E6B),
    Color(0xFF2F6B4F),
    Color(0xFF7A6320),
    Color(0xFF6B2F55),
    Color(0xFF2F5F6B),
)

private fun pluginBadgeColor(key: String): Color {
    val index = (key.hashCode() and 0x7FFFFFFF) % PluginBadgePalette.size
    return PluginBadgePalette[index]
}

private fun pluginMonogram(title: String): String {
    val cleaned = title.trim()
    if (cleaned.isEmpty()) return "?"
    val words = cleaned.split(' ', '-', '_', '.', '/').filter { it.isNotBlank() }
    val letters = if (words.size >= 2) {
        "${words[0].first()}${words[1].first()}"
    } else {
        cleaned.filter { it.isLetterOrDigit() }.take(2)
    }
    return letters.uppercase().ifBlank { "?" }
}

@Composable
private fun DesktopPluginEmptyTable(
    title: String,
    subtitle: String,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
        )
    }
}

private fun String.fallbackRepositoryLabel(fallback: String): String {
    val withoutQuery = substringBefore("?")
    val withoutManifest = withoutQuery.removeSuffix("/manifest.json")
    val host = withoutManifest.substringAfter("://", withoutManifest).substringBefore('/')
    return host.ifBlank {
        withoutManifest.substringAfterLast('/').ifBlank { fallback }
    }
}
