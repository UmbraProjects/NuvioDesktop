package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.details.LightweightMetaDiskCache
import com.nuvio.app.features.home.HomeCatalogDiskCache
import com.nuvio.app.isDesktop
import com.nuvio.app.core.ui.ImageCacheSettings
import com.nuvio.app.core.ui.ImageCacheSettingsRepository
import com.nuvio.app.core.ui.MemoryUse
import com.nuvio.app.features.cloud.formatCloudLibraryBytes
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentCache
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_advanced_clear_cw_cache
import nuvio.composeapp.generated.resources.settings_advanced_clear_cw_cache_done
import nuvio.composeapp.generated.resources.settings_advanced_clear_cw_cache_subtitle
import nuvio.composeapp.generated.resources.settings_advanced_home_catalog_cache
import nuvio.composeapp.generated.resources.settings_advanced_home_catalog_cache_description
import nuvio.composeapp.generated.resources.settings_advanced_metadata_cache
import nuvio.composeapp.generated.resources.settings_advanced_memory_use
import nuvio.composeapp.generated.resources.settings_advanced_memory_use_balanced
import nuvio.composeapp.generated.resources.settings_advanced_memory_use_description
import nuvio.composeapp.generated.resources.settings_advanced_memory_use_generous
import nuvio.composeapp.generated.resources.settings_advanced_memory_use_lean
import nuvio.composeapp.generated.resources.settings_advanced_metadata_cache_days
import nuvio.composeapp.generated.resources.settings_advanced_metadata_cache_description
import nuvio.composeapp.generated.resources.settings_advanced_metadata_cache_hours
import nuvio.composeapp.generated.resources.settings_advanced_metadata_cache_off
import nuvio.composeapp.generated.resources.settings_advanced_metadata_cache_one_day
import nuvio.composeapp.generated.resources.settings_advanced_remember_last_profile
import nuvio.composeapp.generated.resources.settings_advanced_remember_last_profile_description
import nuvio.composeapp.generated.resources.settings_advanced_section_cache
import nuvio.composeapp.generated.resources.settings_advanced_performance_logging
import nuvio.composeapp.generated.resources.settings_advanced_performance_logging_description
import nuvio.composeapp.generated.resources.settings_advanced_section_diagnostics
import nuvio.composeapp.generated.resources.settings_advanced_section_startup
import nuvio.composeapp.generated.resources.settings_advanced_image_cache_size
import nuvio.composeapp.generated.resources.settings_advanced_image_cache_size_description
import nuvio.composeapp.generated.resources.settings_advanced_image_cache_size_description_unknown
import nuvio.composeapp.generated.resources.settings_advanced_image_prewarm
import nuvio.composeapp.generated.resources.settings_advanced_image_prewarm_description
import nuvio.composeapp.generated.resources.settings_advanced_image_prewarm_running
import nuvio.composeapp.generated.resources.settings_advanced_image_prewarm_backdrops_framed
import nuvio.composeapp.generated.resources.settings_advanced_image_prewarm_backdrops_framed_running
import nuvio.composeapp.generated.resources.settings_advanced_image_prewarm_done
import nuvio.composeapp.generated.resources.settings_advanced_image_prewarm_ready
import nuvio.composeapp.generated.resources.settings_advanced_image_prewarm_ready_running
import nuvio.composeapp.generated.resources.settings_advanced_image_prewarm_posters
import nuvio.composeapp.generated.resources.settings_advanced_image_prewarm_posters_description
import nuvio.composeapp.generated.resources.settings_advanced_image_prewarm_logos
import nuvio.composeapp.generated.resources.settings_advanced_image_prewarm_logos_description
import nuvio.composeapp.generated.resources.settings_advanced_image_prewarm_backdrops
import nuvio.composeapp.generated.resources.settings_advanced_image_prewarm_backdrops_description
import nuvio.composeapp.generated.resources.settings_advanced_image_prewarm_collection_contents
import nuvio.composeapp.generated.resources.settings_advanced_image_prewarm_collection_contents_description
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.advancedSettingsContent(
    isTablet: Boolean,
    rememberLastProfileEnabled: Boolean,
) {
    // Desktop-only: the frame-budget telemetry it drives exists only there.
    if (isDesktop) {
        item {
            // Read here rather than plumbed through both call sites: this is the only row that
            // needs it, and the page is already a LazyListScope whose items are composable.
            val playerSettings by PlayerSettingsRepository.uiState.collectAsStateWithLifecycle()
            SettingsSection(
                title = stringResource(Res.string.settings_advanced_section_diagnostics),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_advanced_performance_logging),
                        description = stringResource(
                            Res.string.settings_advanced_performance_logging_description,
                        ),
                        checked = playerSettings.desktopPerformanceLoggingEnabled,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(
                            SettingsScrollAnchor.searchKey("performance-logging"),
                        ),
                        onCheckedChange = PlayerSettingsRepository::setDesktopPerformanceLogging,
                    )
                }
            }
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_advanced_section_startup),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_advanced_remember_last_profile),
                    description = stringResource(Res.string.settings_advanced_remember_last_profile_description),
                    checked = rememberLastProfileEnabled,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("remember-last-profile")),
                    onCheckedChange = ProfileRepository::setRememberLastProfileEnabled,
                )
            }
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_advanced_section_cache),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                if (isDesktop) {
                    ImageCacheRows(isTablet)
                    SettingsGroupDivider(isTablet = isTablet)
                }
                val scope = rememberCoroutineScope()
                var cleared by rememberSaveable { mutableStateOf(false) }
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_advanced_clear_cw_cache),
                    description = if (cleared) {
                        stringResource(Res.string.settings_advanced_clear_cw_cache_done)
                    } else {
                        stringResource(Res.string.settings_advanced_clear_cw_cache_subtitle)
                    },
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("clear-cw-cache")),
                    onClick = {
                        if (!cleared) {
                            ContinueWatchingEnrichmentCache.clearAll()
                            cleared = true
                            scope.launch {
                                WatchProgressRepository.forceSnapshotRefreshFromServer(
                                    ProfileRepository.activeProfileId,
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * Desktop-only: the artwork disk cache and its prewarm live in the desktop image pipeline. The
 * size is read when the cache opens, so a change says it applies after restart rather than
 * pretending to take effect.
 */
@Composable
private fun ImageCacheRows(isTablet: Boolean) {
    val settings by ImageCacheSettingsRepository.settings.collectAsStateWithLifecycle()
    val prewarmStatus by ImageCacheSettingsRepository.prewarmStatus.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { ImageCacheSettingsRepository.ensureLoaded() }
    var usedBytes by remember { mutableStateOf(ImageCacheSettingsRepository.usedBytes()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            usedBytes = ImageCacheSettingsRepository.usedBytes()
        }
    }

    SettingsDropdownChoiceRow(
        title = stringResource(Res.string.settings_advanced_image_cache_size),
        description = usedBytes?.let {
            stringResource(Res.string.settings_advanced_image_cache_size_description, formatCloudLibraryBytes(it))
        } ?: stringResource(Res.string.settings_advanced_image_cache_size_description_unknown),
        options = ImageCacheSettings.DiskCacheGbChoices.map { SettingsChoiceOption(it, "$it GB") },
        selectedValue = settings.diskCacheGb,
        isTablet = isTablet,
        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("image-cache-size")),
        onSelected = ImageCacheSettingsRepository::setDiskCacheGb,
    )

    SettingsGroupDivider(isTablet = isTablet)
    val metadataCacheScope = rememberCoroutineScope()
    SettingsDropdownChoiceRow(
        title = stringResource(Res.string.settings_advanced_metadata_cache),
        description = stringResource(Res.string.settings_advanced_metadata_cache_description),
        options = ImageCacheSettings.MetadataCacheHoursChoices.map { hours ->
            SettingsChoiceOption(hours, metadataCacheHoursLabel(hours))
        },
        selectedValue = settings.metadataCacheHours,
        isTablet = isTablet,
        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("metadata-cache")),
        onSelected = { hours ->
            ImageCacheSettingsRepository.setMetadataCacheHours(hours)
            // Off means nothing outlives the session, so what is already on disk goes too.
            if (hours == 0) metadataCacheScope.launch { LightweightMetaDiskCache.clear() }
        },
    )

    SettingsGroupDivider(isTablet = isTablet)
    SettingsDropdownChoiceRow(
        title = stringResource(Res.string.settings_advanced_memory_use),
        description = stringResource(Res.string.settings_advanced_memory_use_description),
        options = MemoryUse.entries.map { level ->
            SettingsChoiceOption(
                level,
                stringResource(
                    when (level) {
                        MemoryUse.Lean -> Res.string.settings_advanced_memory_use_lean
                        MemoryUse.Balanced -> Res.string.settings_advanced_memory_use_balanced
                        MemoryUse.Generous -> Res.string.settings_advanced_memory_use_generous
                    },
                ),
            )
        },
        selectedValue = settings.memoryUse,
        isTablet = isTablet,
        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("memory-use")),
        onSelected = ImageCacheSettingsRepository::setMemoryUse,
    )

    SettingsGroupDivider(isTablet = isTablet)
    SettingsDropdownChoiceRow(
        title = stringResource(Res.string.settings_advanced_home_catalog_cache),
        description = stringResource(Res.string.settings_advanced_home_catalog_cache_description),
        options = ImageCacheSettings.HomeCatalogCacheHoursChoices.map { hours ->
            SettingsChoiceOption(hours, metadataCacheHoursLabel(hours))
        },
        selectedValue = settings.homeCatalogCacheHours,
        isTablet = isTablet,
        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("home-catalog-cache")),
        onSelected = { hours ->
            ImageCacheSettingsRepository.setHomeCatalogCacheHours(hours)
            if (hours == 0) metadataCacheScope.launch { HomeCatalogDiskCache.clear() }
        },
    )

    val prewarmDescription = buildString {
        append(stringResource(Res.string.settings_advanced_image_prewarm_description))
        val status = prewarmStatus
        if (settings.prewarmEnabled && status.total > 0) {
            append(' ')
            append(
                if (status.running) {
                    stringResource(
                        Res.string.settings_advanced_image_prewarm_running,
                        status.checked,
                        status.total,
                    )
                } else {
                    stringResource(
                        Res.string.settings_advanced_image_prewarm_done,
                        status.downloaded,
                        status.alreadyCached,
                        status.failed,
                    )
                },
            )
        }
    }
    SettingsGroupDivider(isTablet = isTablet)
    SettingsSwitchRow(
        title = stringResource(Res.string.settings_advanced_image_prewarm),
        description = prewarmDescription,
        checked = settings.prewarmEnabled,
        isTablet = isTablet,
        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("image-prewarm")),
        onCheckedChange = ImageCacheSettingsRepository::setPrewarmEnabled,
    )
    if (settings.prewarmEnabled) {
        SettingsGroupDivider(isTablet = isTablet)
        SettingsSwitchRow(
            title = stringResource(Res.string.settings_advanced_image_prewarm_posters),
            description = prewarmKindDescription(
                stringResource(Res.string.settings_advanced_image_prewarm_posters_description),
                if (settings.prewarmPosters && prewarmStatus.total > 0) {
                    prewarmReadyText(prewarmStatus.postersReady, prewarmStatus.running)
                } else {
                    null
                },
            ),
            checked = settings.prewarmPosters,
            isTablet = isTablet,
            modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("image-prewarm-posters")),
            onCheckedChange = ImageCacheSettingsRepository::setPrewarmPosters,
        )
        SettingsGroupDivider(isTablet = isTablet)
        SettingsSwitchRow(
            title = stringResource(Res.string.settings_advanced_image_prewarm_logos),
            description = prewarmKindDescription(
                stringResource(Res.string.settings_advanced_image_prewarm_logos_description),
                if (settings.prewarmLogos && prewarmStatus.total > 0) {
                    prewarmReadyText(prewarmStatus.logosReady, prewarmStatus.running)
                } else {
                    null
                },
            ),
            checked = settings.prewarmLogos,
            isTablet = isTablet,
            modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("image-prewarm-logos")),
            onCheckedChange = ImageCacheSettingsRepository::setPrewarmLogos,
        )
        SettingsGroupDivider(isTablet = isTablet)
        SettingsSwitchRow(
            title = stringResource(Res.string.settings_advanced_image_prewarm_backdrops),
            description = prewarmKindDescription(
                stringResource(Res.string.settings_advanced_image_prewarm_backdrops_description),
                when {
                    !settings.prewarmBackdrops || prewarmStatus.total == 0 -> null
                    prewarmStatus.framingActive -> stringResource(
                        if (prewarmStatus.running) {
                            Res.string.settings_advanced_image_prewarm_backdrops_framed_running
                        } else {
                            Res.string.settings_advanced_image_prewarm_backdrops_framed
                        },
                        prewarmStatus.backdropsReady,
                        prewarmStatus.backdropsFramed,
                    )
                    else -> prewarmReadyText(prewarmStatus.backdropsReady, prewarmStatus.running)
                },
            ),
            checked = settings.prewarmBackdrops,
            isTablet = isTablet,
            modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("image-prewarm-backdrops")),
            onCheckedChange = ImageCacheSettingsRepository::setPrewarmBackdrops,
        )
        SettingsGroupDivider(isTablet = isTablet)
        SettingsSwitchRow(
            title = stringResource(Res.string.settings_advanced_image_prewarm_collection_contents),
            description = stringResource(Res.string.settings_advanced_image_prewarm_collection_contents_description),
            checked = settings.prewarmCollectionContents,
            isTablet = isTablet,
            modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("image-prewarm-collection-contents")),
            onCheckedChange = ImageCacheSettingsRepository::setPrewarmCollectionContents,
        )
    }
}

/**
 * How many of a kind are on disk for what Home and the Library show now. While a pass runs it is
 * the count reached so far, so the number visibly climbs on a first pass.
 */
@Composable
private fun prewarmReadyText(count: Int, running: Boolean): String = stringResource(
    if (running) Res.string.settings_advanced_image_prewarm_ready_running else Res.string.settings_advanced_image_prewarm_ready,
    count,
)

private fun prewarmKindDescription(description: String, status: String?): String =
    if (status == null) description else "$description $status"

@Composable
private fun metadataCacheHoursLabel(hours: Int): String = when {
    hours <= 0 -> stringResource(Res.string.settings_advanced_metadata_cache_off)
    hours == 24 -> stringResource(Res.string.settings_advanced_metadata_cache_one_day)
    hours % 24 == 0 -> stringResource(Res.string.settings_advanced_metadata_cache_days, hours / 24)
    else -> stringResource(Res.string.settings_advanced_metadata_cache_hours, hours)
}

