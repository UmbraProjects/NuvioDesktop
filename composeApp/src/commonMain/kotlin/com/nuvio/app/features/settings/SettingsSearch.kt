package com.nuvio.app.features.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.updater.AppUpdaterPlatform
import com.nuvio.app.isDesktop
import com.nuvio.app.isIos
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

internal sealed class SettingsSearchTarget {
    data class Page(
        val page: SettingsPage,
        val anchor: String? = null,
        val fallbackAnchor: String? = null,
        val fallbackTitle: String? = null,
    ) : SettingsSearchTarget()
    object Downloads : SettingsSearchTarget()
    object SwitchProfile : SettingsSearchTarget()
    object CheckForUpdates : SettingsSearchTarget()
}

internal data class SettingsSearchEntry(
    val key: String,
    val title: String,
    val description: String,
    val page: String,
    val section: String,
    val category: String,
    val icon: ImageVector,
    val target: SettingsSearchTarget,
) {
    val searchableText: String = listOf(title, description, page, section, category)
        .joinToString(separator = " ")
        .lowercase()

    val contextLabel: String = listOf(page, section)
        .filter { it.isNotBlank() }
        .distinct()
        .map(::settingsTitleCase)
        .joinToString(separator = " - ")
}

@Composable
internal fun settingsSearchEntries(
    pluginsEnabled: Boolean,
    downloadsEnabled: Boolean,
    notificationsEnabled: Boolean,
    liquidGlassNativeTabBarSupported: Boolean,
    switchProfileAvailable: Boolean,
    checkForUpdatesAvailable: Boolean,
): List<SettingsSearchEntry> {
    val accountCategory = stringResource(SettingsCategory.Account.labelRes)
    val generalCategory = stringResource(SettingsCategory.General.labelRes)
    val aboutCategory = stringResource(SettingsCategory.About.labelRes)
    val advancedCategory = stringResource(SettingsCategory.Advanced.labelRes)

    val accountPage = stringResource(Res.string.compose_settings_page_account)
    val traktPage = stringResource(Res.string.compose_settings_page_trakt)
    val layoutPage = stringResource(Res.string.compose_settings_page_appearance)
    val advancedPage = stringResource(Res.string.compose_settings_page_advanced)
    val contentDiscoveryPage = stringResource(Res.string.compose_settings_page_content_discovery)
    val downloadsPage = stringResource(Res.string.compose_settings_root_downloads_title)
    val autoDownloadsPage = stringResource(Res.string.compose_settings_page_auto_downloads)
    val playbackPage = stringResource(Res.string.compose_settings_page_playback)
    val randomPlayPage = stringResource(Res.string.random_play_title)
    val streamsPage = stringResource(Res.string.compose_settings_page_streams)
    val integrationsPage = stringResource(Res.string.compose_settings_page_integrations)
    val debridPage = stringResource(Res.string.compose_settings_page_debrid)
    val notificationsPage = stringResource(Res.string.compose_settings_page_notifications)
    val supportersPage = stringResource(Res.string.compose_settings_page_supporters_contributors)
    val licensesPage = stringResource(Res.string.compose_settings_page_licenses_attributions)
    val homeLayoutPage = stringResource(Res.string.compose_settings_page_homescreen)
    val detailPage = stringResource(Res.string.compose_settings_page_meta_screen)
    val continueWatchingPage = stringResource(Res.string.compose_settings_page_continue_watching)
    val addonsPage = stringResource(Res.string.compose_settings_page_addons)
    val pluginsPage = stringResource(Res.string.compose_settings_page_plugins)
    val collectionsPage = stringResource(Res.string.collections_header)
    val tmdbPage = stringResource(Res.string.compose_settings_page_tmdb_enrichment)
    val mdbListPage = stringResource(Res.string.compose_settings_page_mdblist_ratings)
    val qualiCachePage = stringResource(Res.string.compose_settings_page_qualicache)
    val simklPage = stringResource(Res.string.compose_settings_page_simkl)

    val entries = mutableListOf<SettingsSearchEntry>()

    fun add(
        key: String,
        title: String,
        description: String = "",
        page: String = title,
        section: String = "",
        category: String = generalCategory,
        icon: ImageVector,
        target: SettingsSearchTarget,
    ) {
        entries += SettingsSearchEntry(
            key = key,
            title = title,
            description = description,
            page = page,
            section = section,
            category = category,
            icon = icon,
            target = target,
        )
    }

    fun addPage(
        page: SettingsPage,
        key: String,
        title: String,
        description: String,
        category: String = generalCategory,
        icon: ImageVector,
    ) {
        add(
            key = key,
            title = title,
            description = description,
            page = title,
            category = category,
            icon = icon,
            target = SettingsSearchTarget.Page(page),
        )
    }

    fun addRow(
        page: SettingsPage,
        key: String,
        title: String,
        description: String = "",
        pageLabel: String,
        section: String,
        category: String = generalCategory,
        icon: ImageVector,
        anchor: String? = null,
        fallbackAnchor: String? = if (section.isBlank()) {
            null
        } else {
            SettingsScrollAnchor.section(section)
        },
        fallbackTitle: String? = section.takeIf { it.isNotBlank() },
    ) {
        add(
            key = key,
            title = title,
            description = description,
            page = pageLabel,
            section = section,
            category = category,
            icon = icon,
            target = SettingsSearchTarget.Page(
                page = page,
                anchor = anchor ?: SettingsScrollAnchor.searchKey(key),
                fallbackAnchor = fallbackAnchor,
                fallbackTitle = fallbackTitle,
            ),
        )
    }

    if (switchProfileAvailable) {
        add(
            key = "switch-profile",
            title = stringResource(Res.string.compose_settings_root_switch_profile_title),
            description = stringResource(Res.string.compose_settings_root_switch_profile_description),
            page = accountPage,
            section = stringResource(Res.string.compose_settings_root_account_section),
            category = accountCategory,
            icon = Icons.Rounded.People,
            target = SettingsSearchTarget.SwitchProfile,
        )
    }
    addPage(
        page = SettingsPage.Account,
        key = "account",
        title = accountPage,
        description = stringResource(Res.string.compose_settings_root_account_description),
        category = accountCategory,
        icon = Icons.Rounded.AccountCircle,
    )
    addPage(
        page = SettingsPage.TraktAuthentication,
        key = "trakt",
        title = traktPage,
        description = stringResource(Res.string.compose_settings_root_trakt_description),
        category = generalCategory,
        icon = Icons.Rounded.Link,
    )
    addPage(
        page = SettingsPage.Appearance,
        key = "layout",
        title = layoutPage,
        description = stringResource(Res.string.compose_settings_root_appearance_description),
        icon = Icons.Rounded.Palette,
    )
    addPage(
        page = SettingsPage.Advanced,
        key = "advanced",
        title = advancedPage,
        description = stringResource(Res.string.compose_settings_root_advanced_description),
        category = advancedCategory,
        icon = Icons.Rounded.Tune,
    )
    addPage(
        page = SettingsPage.ContentDiscovery,
        key = "content-discovery",
        title = contentDiscoveryPage,
        description = stringResource(Res.string.compose_settings_root_content_discovery_description),
        icon = Icons.Rounded.Extension,
    )
    if (downloadsEnabled) {
        addPage(
            page = SettingsPage.AutoDownloads,
            key = "auto-downloads",
            title = autoDownloadsPage,
            description = stringResource(Res.string.settings_auto_downloads_description),
            icon = Icons.Rounded.CloudDownload,
        )
        add(
            key = "downloads",
            title = downloadsPage,
            description = stringResource(Res.string.compose_settings_root_downloads_description),
            category = generalCategory,
            icon = Icons.Rounded.CloudDownload,
            target = SettingsSearchTarget.Downloads,
        )
    }
    addPage(
        page = SettingsPage.Playback,
        key = "playback",
        title = playbackPage,
        description = stringResource(Res.string.settings_playback_subtitle),
        icon = Icons.Rounded.PlayArrow,
    )
    addPage(
        page = SettingsPage.RandomPlay,
        key = "random-play",
        title = randomPlayPage,
        description = stringResource(Res.string.random_play_settings_description),
        icon = Icons.Rounded.Casino,
    )
    addPage(
        page = SettingsPage.Streams,
        key = "streams",
        title = streamsPage,
        description = stringResource(Res.string.compose_settings_root_streams_description),
        icon = Icons.Rounded.Style,
    )
    addPage(
        page = SettingsPage.Integrations,
        key = "integrations",
        title = integrationsPage,
        description = stringResource(Res.string.compose_settings_root_integrations_description),
        icon = Icons.Rounded.Link,
    )
    addRow(
        page = SettingsPage.Integrations,
        key = "library-source",
        title = stringResource(Res.string.settings_library_source_title),
        description = stringResource(Res.string.settings_library_source_description),
        pageLabel = integrationsPage,
        section = stringResource(Res.string.settings_library_source_section),
        icon = Icons.Rounded.Link,
        anchor = SettingsScrollAnchor.searchKey("library-source"),
    )
    addRow(
        page = SettingsPage.Integrations,
        key = "rating-prompt",
        title = stringResource(Res.string.settings_rating_prompt_title),
        description = stringResource(Res.string.settings_rating_prompt_description),
        pageLabel = integrationsPage,
        section = stringResource(Res.string.settings_library_source_section),
        icon = Icons.Rounded.Link,
        anchor = SettingsScrollAnchor.searchKey("library-source"),
    )
    addRow(
        page = SettingsPage.Integrations,
        key = "calendar-source",
        title = stringResource(Res.string.settings_calendar_source_title),
        description = stringResource(Res.string.settings_calendar_source_description),
        pageLabel = integrationsPage,
        section = stringResource(Res.string.settings_calendar_source_section),
        icon = Icons.Rounded.Link,
        anchor = SettingsScrollAnchor.searchKey("calendar-source"),
    )
    if (isDesktop) {
        addPage(
            page = SettingsPage.KeyboardShortcuts,
            key = "keyboard-shortcuts",
            title = stringResource(Res.string.compose_settings_page_keyboard_shortcuts),
            description = stringResource(Res.string.settings_shortcuts_search_description),
            icon = Icons.Rounded.Keyboard,
        )
    }
    if (isDesktop) {
        addRow(
            page = SettingsPage.Appearance,
            key = "start-windowed",
            title = stringResource(Res.string.settings_appearance_start_windowed),
            description = stringResource(Res.string.settings_appearance_start_windowed_description),
            pageLabel = layoutPage,
            section = stringResource(Res.string.settings_appearance_section_display),
            icon = Icons.Rounded.Palette,
        )
        addRow(
            page = SettingsPage.Integrations,
            key = "discord-presence",
            title = stringResource(Res.string.settings_discord_presence),
            description = stringResource(Res.string.settings_discord_presence_search_description),
            pageLabel = integrationsPage,
            section = stringResource(Res.string.settings_integrations_section_title),
            icon = Icons.Rounded.Link,
            anchor = SettingsScrollAnchor.DiscordPresence,
        )
    }
    if (notificationsEnabled) {
        addPage(
            page = SettingsPage.Notifications,
            key = "notifications",
            title = notificationsPage,
            description = stringResource(Res.string.compose_settings_root_notifications_description),
            icon = Icons.Rounded.Notifications,
        )
    }
    addPage(
        page = SettingsPage.SupportersContributors,
        key = "supporters",
        title = supportersPage,
        description = stringResource(Res.string.about_supporters_contributors_subtitle),
        category = aboutCategory,
        icon = Icons.Rounded.Favorite,
    )
    addPage(
        page = SettingsPage.LicensesAttributions,
        key = "licenses-attributions",
        title = licensesPage,
        description = stringResource(Res.string.about_licenses_attributions_subtitle),
        category = aboutCategory,
        icon = Icons.Rounded.Info,
    )
    listOf(
        PlaybackSearchRow("nuvio-license", stringResource(Res.string.settings_licenses_attributions_nuvio_title), stringResource(Res.string.settings_licenses_attributions_nuvio_license)),
        PlaybackSearchRow("tmdb-attribution", stringResource(Res.string.settings_licenses_attributions_tmdb_title), stringResource(Res.string.settings_licenses_attributions_tmdb_body)),
        PlaybackSearchRow("trakt-attribution", stringResource(Res.string.settings_licenses_attributions_trakt_title), stringResource(Res.string.settings_licenses_attributions_trakt_body)),
        PlaybackSearchRow("premiumize-attribution", stringResource(Res.string.settings_licenses_attributions_premiumize_title), stringResource(Res.string.settings_licenses_attributions_premiumize_body)),
        PlaybackSearchRow("torbox-attribution", stringResource(Res.string.settings_licenses_attributions_torbox_title), stringResource(Res.string.settings_licenses_attributions_torbox_body)),
        PlaybackSearchRow("mdblist-attribution", stringResource(Res.string.settings_licenses_attributions_mdblist_title), stringResource(Res.string.settings_licenses_attributions_mdblist_body)),
        PlaybackSearchRow("introdb-attribution", stringResource(Res.string.settings_licenses_attributions_introdb_title), stringResource(Res.string.settings_licenses_attributions_introdb_body)),
        PlaybackSearchRow("tvdb-attribution", stringResource(Res.string.settings_licenses_attributions_tvdb_title), stringResource(Res.string.settings_licenses_attributions_tvdb_body)),
        PlaybackSearchRow("simkl-attribution", stringResource(Res.string.settings_licenses_attributions_simkl_title), stringResource(Res.string.settings_licenses_attributions_simkl_body)),
        PlaybackSearchRow("imdb-datasets", stringResource(Res.string.settings_licenses_attributions_imdb_title), stringResource(Res.string.settings_licenses_attributions_imdb_body)),
        PlaybackSearchRow(
            if (isIos) "mpvkit-license" else "exoplayer-license",
            if (isIos) {
                stringResource(Res.string.settings_licenses_attributions_mpvkit_title)
            } else {
                stringResource(Res.string.settings_licenses_attributions_exoplayer_title)
            },
            if (isIos) {
                stringResource(Res.string.settings_licenses_attributions_mpvkit_license)
            } else {
                stringResource(Res.string.settings_licenses_attributions_exoplayer_license)
            },
        ),
    ).forEach { row ->
        addRow(
            page = SettingsPage.LicensesAttributions,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = licensesPage,
            section = stringResource(Res.string.compose_settings_root_about_section),
            category = aboutCategory,
            icon = Icons.Rounded.Info,
        )
    }
    if (checkForUpdatesAvailable) {
        add(
            key = "check-updates",
            title = stringResource(Res.string.compose_settings_root_check_updates_title),
            description = stringResource(Res.string.compose_settings_root_check_updates_description),
            page = supportersPage,
            section = stringResource(Res.string.compose_settings_root_about_section),
            category = aboutCategory,
            icon = Icons.Rounded.CloudDownload,
            target = SettingsSearchTarget.CheckForUpdates,
        )
    }

    addRow(
        page = SettingsPage.Account,
        key = "account-status",
        title = stringResource(Res.string.settings_account_status),
        pageLabel = accountPage,
        section = accountPage,
        category = accountCategory,
        icon = Icons.Rounded.AccountCircle,
    )
    addRow(
        page = SettingsPage.Account,
        key = "account-sign-out",
        title = stringResource(Res.string.settings_account_sign_out),
        pageLabel = accountPage,
        section = accountPage,
        category = accountCategory,
        icon = Icons.Rounded.AccountCircle,
    )

    val synchronizationSection = stringResource(Res.string.settings_sync_section)
    addRow(
        page = SettingsPage.Account,
        key = "sync-appearance",
        title = stringResource(Res.string.settings_sync_appearance),
        description = stringResource(Res.string.settings_sync_appearance_description),
        pageLabel = accountPage,
        section = synchronizationSection,
        category = accountCategory,
        icon = Icons.Rounded.Sync,
    )
    addRow(
        page = SettingsPage.Account,
        key = "sync-home-catalogs",
        title = stringResource(Res.string.settings_sync_home_catalogs),
        description = stringResource(Res.string.settings_sync_home_catalogs_description),
        pageLabel = accountPage,
        section = synchronizationSection,
        category = accountCategory,
        icon = Icons.Rounded.Sync,
    )
    addRow(
        page = SettingsPage.Account,
        key = "sync-stream-display",
        title = stringResource(Res.string.settings_sync_stream_display),
        description = stringResource(Res.string.settings_sync_stream_display_description),
        pageLabel = accountPage,
        section = synchronizationSection,
        category = accountCategory,
        icon = Icons.Rounded.Sync,
    )
    addRow(
        page = SettingsPage.Account,
        key = "sync-debrid",
        title = stringResource(Res.string.settings_sync_debrid),
        description = stringResource(Res.string.settings_sync_debrid_description),
        pageLabel = accountPage,
        section = synchronizationSection,
        category = accountCategory,
        icon = Icons.Rounded.Sync,
    )
    addRow(
        page = SettingsPage.Account,
        key = "sync-metadata",
        title = stringResource(Res.string.settings_sync_metadata),
        description = stringResource(Res.string.settings_sync_metadata_description),
        pageLabel = accountPage,
        section = synchronizationSection,
        category = accountCategory,
        icon = Icons.Rounded.Sync,
    )
    addRow(
        page = SettingsPage.Account,
        key = "sync-content-preferences",
        title = stringResource(Res.string.settings_sync_content_preferences),
        description = stringResource(Res.string.settings_sync_content_preferences_description),
        pageLabel = accountPage,
        section = synchronizationSection,
        category = accountCategory,
        icon = Icons.Rounded.Sync,
    )
    addRow(
        page = SettingsPage.Account,
        key = "sync-trakt",
        title = stringResource(Res.string.settings_sync_trakt),
        description = stringResource(Res.string.settings_sync_trakt_description),
        pageLabel = accountPage,
        section = synchronizationSection,
        category = accountCategory,
        icon = Icons.Rounded.Sync,
    )
    addRow(
        page = SettingsPage.Account,
        key = "sync-notifications",
        title = stringResource(Res.string.settings_sync_notifications),
        description = stringResource(Res.string.settings_sync_notifications_description),
        pageLabel = accountPage,
        section = synchronizationSection,
        category = accountCategory,
        icon = Icons.Rounded.Sync,
    )

    addRow(
        page = SettingsPage.Appearance,
        key = "theme",
        title = stringResource(Res.string.settings_appearance_section_theme),
        pageLabel = layoutPage,
        section = stringResource(Res.string.settings_appearance_section_theme),
        icon = Icons.Rounded.Palette,
    )
    addRow(
        page = SettingsPage.Appearance,
        key = "amoled",
        title = stringResource(Res.string.settings_appearance_amoled_black),
        description = stringResource(Res.string.settings_appearance_amoled_description),
        pageLabel = layoutPage,
        section = stringResource(Res.string.settings_appearance_section_display),
        icon = Icons.Rounded.Palette,
    )
    if (liquidGlassNativeTabBarSupported) {
        addRow(
            page = SettingsPage.Appearance,
            key = "liquid-glass",
            title = stringResource(Res.string.settings_appearance_liquid_glass),
            description = stringResource(Res.string.settings_appearance_liquid_glass_description),
            pageLabel = layoutPage,
            section = stringResource(Res.string.settings_appearance_section_display),
            icon = Icons.Rounded.Palette,
        )
    }
    addRow(
        page = SettingsPage.Appearance,
        key = "app-language",
        title = stringResource(Res.string.settings_appearance_app_language),
        pageLabel = layoutPage,
        section = stringResource(Res.string.settings_appearance_section_display),
        icon = Icons.Rounded.Language,
    )
    if (isDesktop) {
        addRow(
            page = SettingsPage.Appearance,
            key = "desktop-navigation",
            title = stringResource(Res.string.settings_appearance_desktop_navigation),
            description = stringResource(Res.string.settings_desktop_navigation_search_description),
            pageLabel = layoutPage,
            section = stringResource(Res.string.settings_appearance_section_display),
            icon = Icons.Rounded.Palette,
        )
    }
    addRow(
        page = SettingsPage.Account,
        key = "remember-last-profile",
        title = stringResource(Res.string.settings_advanced_remember_last_profile),
        description = stringResource(Res.string.settings_advanced_remember_last_profile_description),
        pageLabel = accountPage,
        section = stringResource(Res.string.settings_advanced_section_startup),
        category = accountCategory,
        icon = Icons.Rounded.AccountCircle,
    )
    if (AppUpdaterPlatform.isSupported) {
        addRow(
            page = SettingsPage.Account,
            key = "auto-install-updates",
            title = stringResource(Res.string.settings_updates_auto_install),
            description = stringResource(Res.string.settings_updates_auto_install_description),
            pageLabel = accountPage,
            section = stringResource(Res.string.settings_updates_section),
            category = accountCategory,
            icon = Icons.Rounded.CloudDownload,
        )
    }
    addRow(
        page = SettingsPage.ContinueWatching,
        key = "clear-cw-cache",
        title = stringResource(Res.string.settings_advanced_clear_cw_cache),
        description = stringResource(Res.string.settings_advanced_clear_cw_cache_subtitle),
        pageLabel = continueWatchingPage,
        section = stringResource(Res.string.settings_advanced_section_cache),
        category = generalCategory,
        icon = Icons.Rounded.Tune,
    )
    addRow(
        page = SettingsPage.ContinueWatching,
        key = "continue-watching-withdraw-imported-history",
        title = stringResource(Res.string.settings_cw_withdraw_imported_title),
        description = stringResource(Res.string.settings_cw_withdraw_imported_subtitle),
        pageLabel = continueWatchingPage,
        section = stringResource(Res.string.settings_advanced_section_cache),
        category = generalCategory,
        icon = Icons.Rounded.Tune,
    )
    addPage(
        page = SettingsPage.ContinueWatching,
        key = "continue-watching",
        title = continueWatchingPage,
        description = stringResource(Res.string.settings_appearance_continue_watching_description),
        icon = Icons.Rounded.Style,
    )
    addPage(
        page = SettingsPage.Addons,
        key = "addons",
        title = addonsPage,
        description = stringResource(Res.string.settings_content_discovery_addons_description),
        icon = Icons.Rounded.Extension,
    )
    if (pluginsEnabled) {
        addPage(
            page = SettingsPage.Plugins,
            key = "plugins",
            title = pluginsPage,
            description = stringResource(Res.string.settings_content_discovery_plugins_description),
            icon = Icons.Rounded.Hub,
        )
    }
    addPage(
        page = SettingsPage.Homescreen,
        key = "home-layout",
        title = homeLayoutPage,
        description = stringResource(Res.string.settings_content_discovery_homescreen_description),
        icon = Icons.Rounded.Home,
    )
    addPage(
        page = SettingsPage.MetaScreen,
        key = "detail-page",
        title = detailPage,
        description = stringResource(Res.string.settings_content_discovery_meta_screen_description),
        icon = Icons.Rounded.Tune,
    )
    add(
        key = "collections",
        title = collectionsPage,
        description = stringResource(Res.string.settings_content_discovery_collections_description),
        page = contentDiscoveryPage,
        section = stringResource(Res.string.settings_content_discovery_section_home),
        category = generalCategory,
        icon = Icons.Rounded.CollectionsBookmark,
        target = SettingsSearchTarget.Page(SettingsPage.Collections),
    )

    val playbackPlayer = stringResource(Res.string.settings_playback_section_player)
    val playbackSubtitleAudio = stringResource(Res.string.settings_playback_section_subtitle_audio)
    val playbackStreamSelection = stringResource(Res.string.settings_playback_section_stream_selection)
    val playbackStreamAutoPlay = stringResource(Res.string.settings_playback_section_stream_auto_play)
    val playbackSubtitleRendering = stringResource(Res.string.settings_playback_section_subtitle_rendering)
    val playbackSkipSegments = stringResource(Res.string.settings_playback_section_skip_segments)
    val playbackNextEpisode = stringResource(Res.string.settings_playback_section_next_episode)
    addRow(
        page = SettingsPage.Streams,
        key = "stream-addon-logo",
        title = stringResource(Res.string.settings_stream_addon_logo_title),
        description = stringResource(Res.string.settings_stream_addon_logo_description),
        pageLabel = streamsPage,
        section = stringResource(Res.string.settings_stream_display_section),
        icon = Icons.Rounded.Style,
    )
    addRow(
        page = SettingsPage.Streams,
        key = "stream-size-badges",
        title = stringResource(Res.string.settings_stream_size_badges_title),
        description = stringResource(Res.string.settings_stream_size_badges_description),
        pageLabel = streamsPage,
        section = stringResource(Res.string.settings_stream_badges_section),
        icon = Icons.Rounded.Style,
    )
    addRow(
        page = SettingsPage.Streams,
        key = "stream-badge-position",
        title = stringResource(Res.string.settings_stream_badge_position_title),
        description = stringResource(Res.string.settings_stream_badge_position_description),
        pageLabel = streamsPage,
        section = stringResource(Res.string.settings_stream_badges_section),
        icon = Icons.Rounded.Style,
    )
    addRow(
        page = SettingsPage.Streams,
        key = "stream-badge-urls",
        title = stringResource(Res.string.settings_stream_badge_urls_title),
        description = stringResource(Res.string.settings_stream_badge_urls_search_description),
        pageLabel = streamsPage,
        section = stringResource(Res.string.settings_stream_badges_section),
        icon = Icons.Rounded.Style,
    )
    addPlaybackRows(
        addRow = ::addRow,
        pageLabel = playbackPage,
        section = playbackPlayer,
        icon = Icons.Rounded.PlayArrow,
        rows = listOfNotNull(
            PlaybackSearchRow(
                "loading-overlay",
                stringResource(Res.string.settings_playback_show_loading_overlay),
                stringResource(Res.string.settings_playback_show_loading_overlay_description),
            ),
            PlaybackSearchRow(
                "external-player",
                stringResource(Res.string.settings_playback_external_player),
                stringResource(Res.string.settings_playback_external_player_description_android),
            ),
            if (isIos) PlaybackSearchRow(
                "external-player-app",
                stringResource(Res.string.settings_playback_external_player_app),
            ) else null,
            if (isDesktop) PlaybackSearchRow(
                "default-speed",
                stringResource(Res.string.settings_playback_default_speed),
                anchor = SettingsScrollAnchor.DefaultSpeed,
            ) else null,
            if (isDesktop) PlaybackSearchRow(
                "mouse-move",
                stringResource(Res.string.settings_playback_mouse_move_reveals_controls),
                stringResource(Res.string.settings_playback_mouse_move_reveals_controls_description),
                anchor = SettingsScrollAnchor.MouseMove,
            ) else null,
            if (isDesktop) PlaybackSearchRow(
                "source-notch",
                stringResource(Res.string.settings_playback_source_notch),
                stringResource(Res.string.settings_playback_source_notch_description),
                anchor = SettingsScrollAnchor.SourceNotch,
            ) else null,
            if (isDesktop) PlaybackSearchRow(
                "desktop-hdr",
                stringResource(Res.string.settings_playback_desktop_hdr_mode),
                anchor = SettingsScrollAnchor.HdrMode,
            ) else null,
            if (isDesktop) PlaybackSearchRow(
                "desktop-color-profile",
                stringResource(Res.string.settings_playback_desktop_color_profile),
                anchor = SettingsScrollAnchor.ColorProfile,
            ) else null,
            if (isDesktop) PlaybackSearchRow(
                "desktop-renderer",
                stringResource(Res.string.settings_playback_desktop_renderer),
                stringResource(Res.string.settings_playback_desktop_renderer_dialog),
                anchor = SettingsScrollAnchor.DesktopRenderer,
            ) else null,
            if (isDesktop) PlaybackSearchRow(
                "desktop-buffer-preset",
                stringResource(Res.string.settings_playback_desktop_buffer_preset),
                anchor = SettingsScrollAnchor.BufferPreset,
            ) else null,
            if (isDesktop) PlaybackSearchRow(
                "desktop-anime-mode",
                stringResource(Res.string.settings_playback_desktop_anime_mode),
                anchor = SettingsScrollAnchor.AnimeEnhancements,
            ) else null,
            if (isDesktop) PlaybackSearchRow(
                "desktop-anime-auto",
                stringResource(Res.string.settings_playback_desktop_anime_auto),
                stringResource(Res.string.settings_playback_desktop_anime_auto_desc),
                anchor = SettingsScrollAnchor.AnimeAutoApply,
                fallbackAnchor = SettingsScrollAnchor.AnimeEnhancements,
                fallbackTitle = stringResource(Res.string.settings_playback_desktop_anime_mode),
            ) else null,
            if (isDesktop) PlaybackSearchRow(
                "desktop-anime-svp",
                stringResource(Res.string.settings_playback_desktop_anime_svp),
                stringResource(Res.string.settings_playback_desktop_anime_svp_desc),
                anchor = SettingsScrollAnchor.AnimeSvp,
                fallbackAnchor = SettingsScrollAnchor.AnimeEnhancements,
                fallbackTitle = stringResource(Res.string.settings_playback_desktop_anime_mode),
            ) else null,
            if (isDesktop) PlaybackSearchRow(
                "desktop-anime-svp-overlay",
                stringResource(Res.string.settings_playback_desktop_anime_svp_overlay),
                stringResource(Res.string.settings_playback_desktop_anime_svp_overlay_desc),
                anchor = SettingsScrollAnchor.AnimeSvpOverlay,
                fallbackAnchor = SettingsScrollAnchor.AnimeSvp,
                fallbackTitle = stringResource(Res.string.settings_playback_desktop_anime_svp),
            ) else null,
            if (isDesktop) PlaybackSearchRow(
                "nvidia-rtx-hdr",
                stringResource(Res.string.settings_playback_nvidia_rtx_hdr),
                anchor = SettingsScrollAnchor.RtxHdr,
            ) else null,
        ),
    )
    addPlaybackRows(
        addRow = ::addRow,
        pageLabel = playbackPage,
        section = playbackSubtitleAudio,
        icon = Icons.Rounded.PlayArrow,
        rows = listOfNotNull(
            PlaybackSearchRow("preferred-audio", stringResource(Res.string.settings_playback_preferred_audio_language)),
            PlaybackSearchRow("secondary-audio", stringResource(Res.string.settings_playback_secondary_audio_language)),
            PlaybackSearchRow("preferred-subtitles", stringResource(Res.string.settings_playback_preferred_subtitle_language)),
            PlaybackSearchRow("secondary-subtitles", stringResource(Res.string.settings_playback_secondary_subtitle_language)),
            if (isDesktop) PlaybackSearchRow(
                "dual-subtitles",
                stringResource(Res.string.settings_playback_dual_subtitles),
                stringResource(Res.string.settings_playback_dual_subtitles_description),
            ) else null,
            PlaybackSearchRow(
                "reject-subtitle-keywords",
                stringResource(Res.string.settings_playback_reject_subtitle_keywords),
                stringResource(Res.string.settings_playback_reject_subtitle_keywords_description),
            ),
            PlaybackSearchRow(
                "reject-audio-keywords",
                stringResource(Res.string.settings_playback_reject_audio_keywords),
                stringResource(Res.string.settings_playback_reject_audio_keywords_description),
            ),
        ),
    )
    addPlaybackRows(
        addRow = ::addRow,
        pageLabel = playbackPage,
        section = playbackStreamSelection,
        icon = Icons.Rounded.PlayArrow,
        rows = listOf(
            PlaybackSearchRow(
                "reuse-last-link",
                stringResource(Res.string.settings_playback_reuse_last_link),
                stringResource(Res.string.settings_playback_reuse_last_link_description),
            ),
            PlaybackSearchRow("last-link-cache", stringResource(Res.string.settings_playback_last_link_cache_duration)),
            PlaybackSearchRow(
                "pause-overlay-source",
                stringResource(Res.string.settings_playback_pause_overlay_source),
                stringResource(Res.string.settings_playback_pause_overlay_source_description),
            ),
        ),
    )
    addPlaybackRows(
        addRow = ::addRow,
        pageLabel = playbackPage,
        section = playbackStreamAutoPlay,
        icon = Icons.Rounded.PlayArrow,
        rows = buildList {
            add(PlaybackSearchRow("stream-mode", stringResource(Res.string.settings_playback_stream_selection_mode)))
            add(PlaybackSearchRow("regex-pattern", stringResource(Res.string.settings_playback_regex_pattern)))
            add(PlaybackSearchRow("stream-timeout", stringResource(Res.string.settings_playback_stream_timeout), stringResource(Res.string.settings_playback_stream_timeout_description)))
            add(PlaybackSearchRow("source-scope", stringResource(Res.string.settings_playback_source_scope)))
            add(PlaybackSearchRow("allowed-addons", stringResource(Res.string.settings_playback_allowed_addons)))
            if (pluginsEnabled) add(PlaybackSearchRow("allowed-plugins", stringResource(Res.string.settings_playback_allowed_plugins)))
        },
    )
    if (!isIos) {
        addPlaybackRows(
            addRow = ::addRow,
            pageLabel = playbackPage,
            section = playbackSubtitleRendering,
            icon = Icons.Rounded.PlayArrow,
            rows = listOf(
                PlaybackSearchRow("libass", stringResource(Res.string.settings_playback_enable_libass), stringResource(Res.string.settings_playback_enable_libass_description)),
                PlaybackSearchRow("libass-render", stringResource(Res.string.settings_playback_render_type)),
            ),
        )
    }
    addPlaybackRows(
        addRow = ::addRow,
        pageLabel = playbackPage,
        section = playbackSkipSegments,
        icon = Icons.Rounded.PlayArrow,
        rows = listOf(
            PlaybackSearchRow("skip-intro", stringResource(Res.string.settings_playback_skip_intro_outro_recap), stringResource(Res.string.settings_playback_skip_intro_outro_recap_description)),
            PlaybackSearchRow("anime-skip", stringResource(Res.string.settings_playback_anime_skip), stringResource(Res.string.settings_playback_anime_skip_description)),
            PlaybackSearchRow(
                "anime-skip-client",
                stringResource(Res.string.settings_playback_anime_skip_client_id),
                stringResource(Res.string.settings_playback_anime_skip_client_id_description),
                fallbackAnchor = SettingsScrollAnchor.searchKey("anime-skip"),
                fallbackTitle = stringResource(Res.string.settings_playback_anime_skip),
            ),
            PlaybackSearchRow("intro-submit", stringResource(Res.string.settings_playback_intro_submit_enabled), stringResource(Res.string.settings_playback_intro_submit_enabled_description)),
            PlaybackSearchRow(
                "introdb-key",
                stringResource(Res.string.settings_playback_introdb_api_key),
                stringResource(Res.string.settings_playback_introdb_api_key_description),
                fallbackAnchor = SettingsScrollAnchor.searchKey("intro-submit"),
                fallbackTitle = stringResource(Res.string.settings_playback_intro_submit_enabled),
            ),
        ),
    )
    addPlaybackRows(
        addRow = ::addRow,
        pageLabel = playbackPage,
        section = playbackNextEpisode,
        icon = Icons.Rounded.PlayArrow,
        rows = listOf(
            PlaybackSearchRow("auto-play-next", stringResource(Res.string.settings_playback_auto_play_next_episode), stringResource(Res.string.settings_playback_auto_play_next_episode_description), anchor = SettingsScrollAnchor.BingeMode),
            PlaybackSearchRow("prefer-binge", stringResource(Res.string.settings_playback_prefer_binge_group), stringResource(Res.string.settings_playback_prefer_binge_group_description)),
            PlaybackSearchRow("threshold-mode", stringResource(Res.string.settings_playback_threshold_mode)),
            PlaybackSearchRow("threshold-percent", stringResource(Res.string.settings_playback_threshold_percentage), stringResource(Res.string.settings_playback_threshold_percentage_description)),
            PlaybackSearchRow("threshold-minutes", stringResource(Res.string.settings_playback_minutes_before_end), stringResource(Res.string.settings_playback_minutes_before_end_description)),
        ),
    )

    addContinueWatchingRows(
        addRow = ::addRow,
        pageLabel = continueWatchingPage,
        section = stringResource(Res.string.settings_cw_source_section),
        icon = Icons.Rounded.Style,
        rows = listOf(
            PlaybackSearchRow(
                "source",
                stringResource(Res.string.settings_cw_source_title),
                stringResource(Res.string.settings_appearance_continue_watching_description),
                anchor = SettingsScrollAnchor.searchKey("continue-watching-source"),
            ),
            PlaybackSearchRow(
                "window",
                stringResource(Res.string.settings_cw_window_title),
                stringResource(Res.string.settings_cw_window_description),
                anchor = SettingsScrollAnchor.searchKey("continue-watching-window"),
            ),
        ),
    )
    addContinueWatchingRows(
        addRow = ::addRow,
        pageLabel = continueWatchingPage,
        section = stringResource(Res.string.settings_continue_watching_section_up_next_behavior),
        icon = Icons.Rounded.Style,
        rows = listOf(
            PlaybackSearchRow(
                "show-continue-watching",
                stringResource(Res.string.settings_continue_watching_show_title),
                stringResource(Res.string.settings_continue_watching_show_description),
            ),
            PlaybackSearchRow("episode-thumbnails", stringResource(Res.string.settings_continue_watching_use_episode_thumbnails_title), stringResource(Res.string.settings_continue_watching_use_episode_thumbnails_description)),
            PlaybackSearchRow("up-next", stringResource(Res.string.settings_continue_watching_up_next_title), stringResource(Res.string.settings_continue_watching_up_next_description)),
            PlaybackSearchRow("unaired-next-up", stringResource(Res.string.settings_continue_watching_show_unaired_next_up_title), stringResource(Res.string.settings_continue_watching_show_unaired_next_up_description)),
            PlaybackSearchRow("blur-next-up", stringResource(Res.string.settings_continue_watching_blur_next_up_title), stringResource(Res.string.settings_continue_watching_blur_next_up_description)),
        ),
    )
    addContinueWatchingRows(
        addRow = ::addRow,
        pageLabel = continueWatchingPage,
        section = stringResource(Res.string.settings_continue_watching_section_on_launch),
        icon = Icons.Rounded.Style,
        rows = listOf(
            PlaybackSearchRow("resume-prompt", stringResource(Res.string.settings_continue_watching_resume_prompt_title), stringResource(Res.string.settings_continue_watching_resume_prompt_description)),
        ),
    )

    val posterSection = stringResource(Res.string.settings_poster_card_style)
    listOf(
        PlaybackSearchRow(
            "poster-width",
            stringResource(Res.string.settings_poster_card_width),
            "Includes Compact, Dense, Standard, Balanced, Comfort, Large, and Extra Large poster sizes.",
            anchor = SettingsScrollAnchor.ExtraLargePosters,
        ),
        PlaybackSearchRow("poster-radius", stringResource(Res.string.settings_poster_card_radius)),
        PlaybackSearchRow("poster-landscape", stringResource(Res.string.settings_poster_landscape_mode)),
        PlaybackSearchRow("poster-hide-labels", stringResource(Res.string.settings_poster_hide_labels)),
    ).forEach { row ->
        addRow(
            page = SettingsPage.Appearance,
            key = "poster-${row.key}",
            title = row.title,
            description = row.description,
            pageLabel = layoutPage,
            section = posterSection,
            icon = Icons.Rounded.Tune,
            anchor = row.anchor,
        )
    }

    val homeLayoutSection = stringResource(Res.string.settings_homescreen_section_hero)
    listOf(
        PlaybackSearchRow("home-hero", stringResource(Res.string.settings_homescreen_show_hero), stringResource(Res.string.settings_homescreen_show_hero_description)),
        PlaybackSearchRow("home-hero-badge-count", "Hero badge count", "Choose how many hero badges are shown.", anchor = SettingsScrollAnchor.HeroBadgeCount),
        PlaybackSearchRow("home-hero-badge-position", "Hero badge position", "Choose where hero badges appear.", anchor = SettingsScrollAnchor.HeroBadgePosition),
        PlaybackSearchRow("home-hero-badge-size", "Hero badge size", "Scale badges for desktop or TV viewing.", anchor = SettingsScrollAnchor.HeroBadgeSize),
        PlaybackSearchRow("home-hero-badge-priority", "Hero info priority", "Choose which hero badges are preferred first.", anchor = SettingsScrollAnchor.HeroBadgePriority),
        PlaybackSearchRow("home-hero-release-status", "Only show unavailable release status", "Show release status only for cinema and production titles.", anchor = SettingsScrollAnchor.HeroReleaseStatus),
        PlaybackSearchRow("home-hide-unreleased", stringResource(Res.string.layout_hide_unreleased), stringResource(Res.string.layout_hide_unreleased_sub)),
        PlaybackSearchRow("home-hide-catalog-underline", stringResource(Res.string.settings_homescreen_hide_catalog_underline), stringResource(Res.string.settings_homescreen_hide_catalog_underline_description)),
        PlaybackSearchRow("home-display-mode", "Display Mode", "Basic, Adaptive, Adaptive Ambient, or TV Mode.", anchor = SettingsScrollAnchor.DisplayMode),
        PlaybackSearchRow("home-hero-trailer", stringResource(Res.string.settings_playback_hero_tv_trailer), stringResource(Res.string.settings_playback_hero_tv_trailer_description), anchor = SettingsScrollAnchor.AutoPlayTrailer),
        PlaybackSearchRow("home-hero-trailer-delay", stringResource(Res.string.settings_playback_hero_tv_trailer_delay), "Delay before focused hero trailers start playing.", anchor = SettingsScrollAnchor.TrailerDelay),
        PlaybackSearchRow("home-hero-trailer-sound", stringResource(Res.string.settings_playback_hero_tv_trailer_sound), stringResource(Res.string.settings_playback_hero_tv_trailer_sound_description), anchor = SettingsScrollAnchor.TrailerSound),
        PlaybackSearchRow("home-hero-trailer-search", "Trailers in Search", "Allow focused search results to play hero trailers.", anchor = SettingsScrollAnchor.TrailerSearch),
        PlaybackSearchRow("home-adaptive-hero-position", "Backdrop vertical position", "Manually tune how adaptive hero backdrops crop vertically.", anchor = SettingsScrollAnchor.AdaptiveHeroPosition),
        PlaybackSearchRow("home-adaptive-hero-height", "Hero height", "Set how much of the window the adaptive hero occupies.", anchor = SettingsScrollAnchor.AdaptiveHeroHeight),
        PlaybackSearchRow("home-catalog-row-numbers", "Number catalog rows", "Append each row's position to its name, including collections."),
        PlaybackSearchRow("home-tv-row-dots", "Row jump dots", "Click a dot beside the TV Mode row name to jump straight to that catalog."),
        PlaybackSearchRow("home-tv-row-dots-anchor", "Row jump dot position", "Put the TV Mode jump dots on the row name's line or over the backdrop."),
        PlaybackSearchRow("home-hero-sources", stringResource(Res.string.settings_homescreen_section_hero_sources)),
        PlaybackSearchRow("home-catalogs", stringResource(Res.string.settings_homescreen_section_catalogs)),
    ).forEach { row ->
        addRow(
            page = SettingsPage.Homescreen,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = homeLayoutPage,
            section = homeLayoutSection,
            icon = Icons.Rounded.Home,
            anchor = row.anchor,
        )
    }

    val detailAppearanceSection = stringResource(Res.string.settings_meta_section_appearance)
    listOf(
        PlaybackSearchRow("meta-discovery-badges", stringResource(Res.string.settings_meta_discovery_badges), stringResource(Res.string.settings_meta_discovery_badges_description)),
        PlaybackSearchRow("meta-blur-episodes", stringResource(Res.string.settings_meta_blur_unwatched_episodes), stringResource(Res.string.settings_meta_blur_unwatched_episodes_description)),
    ).forEach { row ->
        addRow(
            page = SettingsPage.MetaScreen,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = detailPage,
            section = detailAppearanceSection,
            icon = Icons.Rounded.Tune,
        )
    }

    addPage(
        page = SettingsPage.TmdbEnrichment,
        key = "tmdb",
        title = tmdbPage,
        description = stringResource(Res.string.settings_integrations_tmdb_description),
        icon = Icons.Rounded.Link,
    )
    addPage(
        page = SettingsPage.MdbListRatings,
        key = "mdblist",
        title = mdbListPage,
        description = stringResource(Res.string.settings_integrations_mdblist_description),
        icon = Icons.Rounded.Link,
    )
    addPage(
        page = SettingsPage.QualiCache,
        key = "qualicache",
        title = qualiCachePage,
        description = stringResource(Res.string.settings_integrations_qualicache_description),
        icon = Icons.Rounded.Link,
    )
    addPage(
        page = SettingsPage.Debrid,
        key = "debrid",
        title = debridPage,
        description = stringResource(Res.string.settings_integrations_debrid_description),
        icon = Icons.Rounded.CloudDownload,
    )
    val tmdbModulesSection = stringResource(Res.string.settings_tmdb_section_modules)
    listOf(
        PlaybackSearchRow("tmdb-enable", stringResource(Res.string.settings_tmdb_enable_enrichment), stringResource(Res.string.settings_tmdb_enable_enrichment_description), stringResource(Res.string.settings_tmdb_section_title)),
        PlaybackSearchRow("tmdb-api-key", stringResource(Res.string.settings_tmdb_personal_api_key), "", stringResource(Res.string.settings_tmdb_section_credentials)),
        PlaybackSearchRow("tvdb-api-key", stringResource(Res.string.settings_licenses_attributions_tvdb_title), stringResource(Res.string.settings_licenses_attributions_tvdb_body), stringResource(Res.string.settings_tmdb_section_credentials), anchor = SettingsScrollAnchor.TvdbApiKey),
        PlaybackSearchRow("tmdb-hero-images", "Hero backdrop & logo", "Choose addon artwork, TMDB artwork, or TVDB artwork for TV and anime.", "HERO BACKDROP & LOGO", anchor = SettingsScrollAnchor.TmdbHeroImages),
        PlaybackSearchRow("tmdb-language", stringResource(Res.string.settings_tmdb_preferred_language), stringResource(Res.string.settings_tmdb_preferred_language_description), stringResource(Res.string.settings_tmdb_section_localization)),
        PlaybackSearchRow("tmdb-filename-catalogs", "Resolve filenames via TMDB", "Look up catalog rows that arrive as raw release filenames (TorBox, AIOStreams library) by name and year.", "FILENAME-ONLY CATALOGS"),
        PlaybackSearchRow("tmdb-trailers", stringResource(Res.string.settings_tmdb_module_trailers), stringResource(Res.string.settings_tmdb_module_trailers_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-artwork", stringResource(Res.string.settings_tmdb_module_artwork), stringResource(Res.string.settings_tmdb_module_artwork_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-basic-info", stringResource(Res.string.settings_tmdb_module_basic_info), stringResource(Res.string.settings_tmdb_module_basic_info_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-details", stringResource(Res.string.settings_tmdb_module_details), stringResource(Res.string.settings_tmdb_module_details_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-credits", stringResource(Res.string.settings_tmdb_module_credits), stringResource(Res.string.settings_tmdb_module_credits_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-companies", stringResource(Res.string.settings_tmdb_module_production_companies), stringResource(Res.string.settings_tmdb_module_production_companies_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-networks", stringResource(Res.string.settings_tmdb_module_networks), stringResource(Res.string.settings_tmdb_module_networks_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-episodes", stringResource(Res.string.settings_tmdb_module_episodes), stringResource(Res.string.settings_tmdb_module_episodes_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-season-posters", stringResource(Res.string.settings_tmdb_module_season_posters), stringResource(Res.string.settings_tmdb_module_season_posters_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-more-like-this", stringResource(Res.string.settings_tmdb_module_more_like_this), stringResource(Res.string.settings_tmdb_module_more_like_this_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-collections", stringResource(Res.string.settings_tmdb_module_collections), stringResource(Res.string.settings_tmdb_module_collections_description), tmdbModulesSection),
    ).forEach { row ->
        addRow(
            page = SettingsPage.TmdbEnrichment,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = tmdbPage,
            section = row.sectionOverride ?: tmdbModulesSection,
            icon = Icons.Rounded.Link,
        )
    }

    listOf(
        PlaybackSearchRow("mdb-enable", stringResource(Res.string.settings_mdb_enable_ratings), stringResource(Res.string.settings_mdb_enable_ratings_description), stringResource(Res.string.settings_mdb_section_title)),
        PlaybackSearchRow("mdb-api-key", stringResource(Res.string.settings_mdb_api_key_title), stringResource(Res.string.settings_mdb_api_key_description), stringResource(Res.string.settings_mdb_section_api_key)),
        PlaybackSearchRow("mdb-imdb", stringResource(Res.string.source_imdb), "", stringResource(Res.string.settings_mdb_section_rating_providers)),
        PlaybackSearchRow("mdb-tmdb", stringResource(Res.string.source_tmdb), "", stringResource(Res.string.settings_mdb_section_rating_providers)),
        PlaybackSearchRow("mdb-tomatoes", stringResource(Res.string.source_rotten_tomatoes), "", stringResource(Res.string.settings_mdb_section_rating_providers)),
        PlaybackSearchRow("mdb-metacritic", stringResource(Res.string.source_metacritic), "", stringResource(Res.string.settings_mdb_section_rating_providers)),
        PlaybackSearchRow("mdb-trakt", stringResource(Res.string.source_trakt), "", stringResource(Res.string.settings_mdb_section_rating_providers)),
        PlaybackSearchRow("mdb-letterboxd", stringResource(Res.string.source_letterboxd), "", stringResource(Res.string.settings_mdb_section_rating_providers)),
        PlaybackSearchRow("mdb-audience", stringResource(Res.string.source_audience_score), "", stringResource(Res.string.settings_mdb_section_rating_providers)),
    ).forEach { row ->
        addRow(
            page = SettingsPage.MdbListRatings,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = mdbListPage,
            section = row.sectionOverride ?: stringResource(Res.string.settings_mdb_section_title),
            icon = Icons.Rounded.Link,
        )
    }

    listOf(
        PlaybackSearchRow("qualicache-enable", "Show quality badges", "Highlight notable release quality on the Home hero, from your QualiCache server.", "QUALICACHE"),
        PlaybackSearchRow("qualicache-url", "Server address", "Where your QualiCache instance is reachable.", "SERVER"),
        PlaybackSearchRow("qualicache-access-key", "Access key", "Only needed if you set ACCESS_KEY on the server.", "SERVER"),
        PlaybackSearchRow("qualicache-resolution", "Resolution", "The 4K disc and stream badges", "BADGES"),
        PlaybackSearchRow("qualicache-dynamic-range", "Dynamic range", "Dolby Vision, HDR", "BADGES"),
        PlaybackSearchRow("qualicache-audio", "Audio", "Dolby Atmos, DTS", "BADGES"),
    ).forEach { row ->
        addRow(
            page = SettingsPage.QualiCache,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = qualiCachePage,
            section = row.sectionOverride ?: "QUALICACHE",
            icon = Icons.Rounded.Link,
        )
    }

    listOf(
        PlaybackSearchRow("debrid-cloud-library", stringResource(Res.string.settings_debrid_cloud_library), stringResource(Res.string.settings_debrid_cloud_library_description), stringResource(Res.string.settings_debrid_section_title)),
        PlaybackSearchRow("debrid-enable", stringResource(Res.string.settings_debrid_enable), stringResource(Res.string.settings_debrid_enable_description), stringResource(Res.string.settings_debrid_section_title)),
        PlaybackSearchRow("debrid-resolve-with", stringResource(Res.string.settings_debrid_resolve_with), stringResource(Res.string.settings_debrid_resolve_with_description), stringResource(Res.string.settings_debrid_section_title)),
        PlaybackSearchRow("debrid-accounts", stringResource(Res.string.settings_debrid_section_providers), stringResource(Res.string.settings_integrations_debrid_description), stringResource(Res.string.settings_debrid_section_providers)),
        PlaybackSearchRow("debrid-prepare", stringResource(Res.string.settings_debrid_prepare_instant_playback), stringResource(Res.string.settings_debrid_prepare_instant_playback_description), stringResource(Res.string.settings_debrid_section_instant_playback)),
        PlaybackSearchRow("debrid-result-limit", stringResource(Res.string.settings_debrid_max_results), stringResource(Res.string.settings_debrid_max_results_desc), stringResource(Res.string.settings_debrid_section_result_management)),
        PlaybackSearchRow("debrid-sort", stringResource(Res.string.settings_debrid_sort_results), stringResource(Res.string.settings_debrid_sort_results_desc), stringResource(Res.string.settings_debrid_section_result_management)),
        PlaybackSearchRow("debrid-size", stringResource(Res.string.settings_debrid_size_range), stringResource(Res.string.settings_debrid_size_range_desc), stringResource(Res.string.settings_debrid_section_result_management)),
        PlaybackSearchRow("debrid-template-name", stringResource(Res.string.settings_debrid_name_template), stringResource(Res.string.settings_debrid_name_template_description), stringResource(Res.string.settings_debrid_section_formatting)),
        PlaybackSearchRow("debrid-template-description", stringResource(Res.string.settings_debrid_description_template), stringResource(Res.string.settings_debrid_description_template_description), stringResource(Res.string.settings_debrid_section_formatting)),
    ).forEach { row ->
        addRow(
            page = SettingsPage.Debrid,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = debridPage,
            section = row.sectionOverride ?: debridPage,
            icon = Icons.Rounded.CloudDownload,
        )
    }

    if (notificationsEnabled) {
        val notificationsAlerts = stringResource(Res.string.settings_notifications_section_alerts)
        addRow(
            page = SettingsPage.Notifications,
            key = "episode-release-alerts",
            title = stringResource(Res.string.settings_notifications_episode_release_alerts),
            description = stringResource(Res.string.settings_notifications_episode_release_alerts_description),
            pageLabel = notificationsPage,
            section = notificationsAlerts,
            icon = Icons.Rounded.Notifications,
        )
        addRow(
            page = SettingsPage.Notifications,
            key = "notification-test",
            title = stringResource(Res.string.settings_notifications_test_title),
            pageLabel = notificationsPage,
            section = stringResource(Res.string.settings_notifications_section_test),
            icon = Icons.Rounded.Notifications,
        )
    }

    addRow(
        page = SettingsPage.TraktAuthentication,
        key = "trakt-authentication",
        title = stringResource(Res.string.settings_trakt_authentication),
        description = stringResource(Res.string.settings_trakt_intro_description),
        pageLabel = traktPage,
        section = stringResource(Res.string.settings_trakt_authentication),
        category = generalCategory,
        icon = Icons.Rounded.Link,
    )
    listOf(
        PlaybackSearchRow("trakt-watch-progress", stringResource(Res.string.trakt_watch_progress_title), stringResource(Res.string.trakt_watch_progress_subtitle)),
        PlaybackSearchRow("trakt-comments", stringResource(Res.string.settings_trakt_comments), stringResource(Res.string.settings_trakt_comments_description)),
        PlaybackSearchRow("trakt-more-like-this-source", stringResource(Res.string.trakt_more_like_this_source_title), stringResource(Res.string.trakt_more_like_this_source_subtitle)),
    ).forEach { row ->
        addRow(
            page = SettingsPage.TraktAuthentication,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = traktPage,
            section = stringResource(Res.string.settings_trakt_features),
            category = generalCategory,
            icon = Icons.Rounded.Link,
        )
    }

    addPage(
        page = SettingsPage.SimklAuthentication,
        key = "simkl",
        title = simklPage,
        description = stringResource(Res.string.settings_simkl_description),
        category = generalCategory,
        icon = Icons.Rounded.Link,
    )
    listOf(
        PlaybackSearchRow("simkl-client-id", stringResource(Res.string.settings_simkl_client_id), stringResource(Res.string.settings_simkl_credentials_description), stringResource(Res.string.settings_simkl_section_credentials)),
        PlaybackSearchRow("simkl-connect", stringResource(Res.string.settings_simkl_connect), stringResource(Res.string.settings_simkl_description), stringResource(Res.string.settings_simkl_section_auth)),
        PlaybackSearchRow("simkl-daily-visit", stringResource(Res.string.settings_simkl_daily_visit), stringResource(Res.string.settings_simkl_daily_visit_desc), stringResource(Res.string.settings_simkl_section_daily_visit)),
    ).forEach { row ->
        addRow(
            page = SettingsPage.SimklAuthentication,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = simklPage,
            section = row.sectionOverride ?: simklPage,
            category = generalCategory,
            icon = Icons.Rounded.Link,
        )
    }

    return entries
}

private data class PlaybackSearchRow(
    val key: String,
    val title: String,
    val description: String = "",
    val sectionOverride: String? = null,
    val anchor: String? = null,
    val fallbackAnchor: String? = null,
    val fallbackTitle: String? = null,
)

private fun addPlaybackRows(
    addRow: (
        page: SettingsPage,
        key: String,
        title: String,
        description: String,
        pageLabel: String,
        section: String,
        category: String,
        icon: ImageVector,
        anchor: String?,
        fallbackAnchor: String?,
        fallbackTitle: String?,
    ) -> Unit,
    pageLabel: String,
    section: String,
    icon: ImageVector,
    rows: List<PlaybackSearchRow>,
) {
    rows.forEach { row ->
        addRow(
            SettingsPage.Playback,
            // Search anchors on PlaybackSettingsPage use the setting key itself. Prefixing only
            // the search index key made clicks request a different, nonexistent destination.
            row.key,
            row.title,
            row.description,
            pageLabel,
            section,
            "",
            icon,
            row.anchor,
            row.fallbackAnchor ?: SettingsScrollAnchor.section(section),
            row.fallbackTitle ?: section,
        )
    }
}

private fun addContinueWatchingRows(
    addRow: (
        page: SettingsPage,
        key: String,
        title: String,
        description: String,
        pageLabel: String,
        section: String,
        category: String,
        icon: ImageVector,
        anchor: String?,
        fallbackAnchor: String?,
        fallbackTitle: String?,
    ) -> Unit,
    pageLabel: String,
    section: String,
    icon: ImageVector,
    rows: List<PlaybackSearchRow>,
) {
    rows.forEach { row ->
        addRow(
            SettingsPage.ContinueWatching,
            "continue-watching-${row.key}",
            row.title,
            row.description,
            pageLabel,
            section,
            "",
            icon,
            row.anchor,
            row.fallbackAnchor ?: SettingsScrollAnchor.section(section),
            row.fallbackTitle ?: section,
        )
    }
}

internal fun LazyListScope.settingsSearchRootContent(
    query: String,
    entries: List<SettingsSearchEntry>,
    isTablet: Boolean,
    showSearchField: Boolean,
    animateSearchField: Boolean,
    onQueryChange: (String) -> Unit,
    onSearchFocusChange: (Boolean) -> Unit = {},
    onTargetClick: (SettingsSearchTarget) -> Unit,
) {
    if (showSearchField || query.isNotBlank()) {
        item(key = "settings-search-field") {
            SettingsSearchRevealItem(animate = animateSearchField && !isDesktop) {
                SettingsSearchField(
                    query = query,
                    onQueryChange = onQueryChange,
                    onFocusChange = onSearchFocusChange,
                )
            }
        }
    }

    settingsSearchResultsContent(
        query = query,
        entries = entries,
        isTablet = isTablet,
        onTargetClick = onTargetClick,
    )
}

/**
 * Renders just the search results list (no search field). Extracted from
 * [settingsSearchRootContent] so the wide desktop content column can show results on any settings
 * page — not only Root — while the search field itself lives in the top bar.
 */
internal fun LazyListScope.settingsSearchResultsContent(
    query: String,
    entries: List<SettingsSearchEntry>,
    isTablet: Boolean,
    onTargetClick: (SettingsSearchTarget) -> Unit,
) {
    if (query.isBlank()) return

    val results = settingsSearchResults(
        query = query,
        entries = entries,
    )

    item(key = "settings-search-results") {
        if (results.isEmpty()) {
            SettingsSearchEmptyState(isTablet = isTablet)
        } else {
            SettingsSection(
                title = stringResource(Res.string.settings_search_results_section),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    results.forEachIndexed { index, entry ->
                        if (index > 0) {
                            SettingsGroupDivider(isTablet = isTablet)
                        }
                        SettingsNavigationRow(
                            title = entry.title,
                            description = entry.resultDescription(),
                            icon = entry.icon,
                            isTablet = isTablet,
                            onClick = {
                                SettingsScrollAnchor.highlightTitle(entry.title)
                                onTargetClick(entry.target)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSearchRevealItem(
    animate: Boolean,
    content: @Composable () -> Unit,
) {
    if (!animate) {
        content()
        return
    }

    val visibleState = remember {
        MutableTransitionState(false).apply {
            targetState = true
        }
    }
    AnimatedVisibility(
        visibleState = visibleState,
        enter = expandVertically(
            animationSpec = tween(durationMillis = NuvioTokens.Motion.normalMillis),
            expandFrom = Alignment.Top,
        ) + fadeIn(
            animationSpec = tween(durationMillis = NuvioTokens.Motion.fastMillis),
        ) + slideInVertically(
            animationSpec = tween(durationMillis = NuvioTokens.Motion.normalMillis),
            initialOffsetY = { -it / 4 },
        ),
    ) {
        content()
    }
}

@Composable
internal fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val focused = remember { mutableStateOf(false) }
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(tokens.colors.surfaceCard, tokens.shapes.compactCard)
            .border(
                width = tokens.borders.hairline,
                color = if (focused.value) tokens.colors.borderFocus else tokens.colors.borderDefault,
                shape = tokens.shapes.compactCard,
            )
            .trackSettingsTextFocus()
            .onFocusChanged {
                focused.value = it.isFocused
                onFocusChange(it.isFocused)
            },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = tokens.colors.textPrimary),
        cursorBrush = SolidColor(tokens.colors.accent),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = tokens.colors.textMuted,
                    modifier = Modifier.size(20.dp),
                )
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isBlank()) {
                        Text(
                            text = stringResource(Res.string.settings_search_placeholder),
                            color = tokens.colors.textMuted,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
                if (query.isNotBlank()) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(Res.string.compose_search_clear),
                        tint = tokens.colors.textMuted,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { onQueryChange("") },
                    )
                }
            }
        },
    )
}

@Composable
private fun SettingsSearchEmptyState(isTablet: Boolean) {
    val tokens = MaterialTheme.nuvio
    SettingsSection(
        title = stringResource(Res.string.settings_search_results_section),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 18.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_search_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

private fun settingsSearchResults(
    query: String,
    entries: List<SettingsSearchEntry>,
): List<SettingsSearchEntry> {
    val terms = query
        .trim()
        .lowercase()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }

    if (terms.isEmpty()) return emptyList()

    return entries.filter { entry ->
        terms.all { term -> entry.searchableText.contains(term) }
    }
}

private fun SettingsSearchEntry.resultDescription(): String {
    return description.ifBlank { contextLabel }
}
