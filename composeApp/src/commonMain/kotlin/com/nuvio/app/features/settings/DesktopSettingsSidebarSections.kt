package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.home.HomeCatalogSettingsItem
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.updater.AppUpdaterPlatform
import com.nuvio.app.isDesktop
import com.nuvio.app.isWindows
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * One entry of the sub-navigation the desktop sidebar unfolds under the selected category: a
 * section heading on that category's page (scrolled to through [SettingsScrollAnchor.section]) or
 * a sub-page reached from it (TMDB under Integrations), which has no [anchor].
 */
internal data class DesktopSettingsSidebarSubItem(
    val label: String,
    val page: SettingsPage,
    val anchor: String? = null,
)

/**
 * The sub-navigation for one sidebar category, in page order.
 *
 * A static table rather than a runtime registry: most pages put one section per lazy item, so the
 * headings below the fold are never composed and could not report themselves. Each title has to be
 * the exact string the page hands to [SettingsSection] - the anchor id is derived from it. A section
 * that a page shows conditionally and this table cannot predict (a custom-theme block, hero sources)
 * is listed anyway; a request nothing consumes expires quietly.
 *
 * Only ever resolve the strings for the category that is open - every `stringResource` is a jar read
 * on desktop, and the whole table is ~90 of them.
 */
@Composable
internal fun desktopSettingsSidebarSubItems(
    sidebarPage: SettingsPage,
    homescreenItems: List<HomeCatalogSettingsItem>,
): List<DesktopSettingsSidebarSubItem> {
    @Composable
    fun section(page: SettingsPage, titleRes: StringResource): DesktopSettingsSidebarSubItem =
        sectionTitled(page, stringResource(titleRes))

    @Composable
    fun subPage(page: SettingsPage): DesktopSettingsSidebarSubItem =
        DesktopSettingsSidebarSubItem(label = stringResource(page.titleRes), page = page)

    return when (sidebarPage) {
        SettingsPage.Playback -> listOfNotNull(
            if (isWindows) section(SettingsPage.Playback, Res.string.settings_playback_nvidia_rtx_video_section) else null,
            section(SettingsPage.Playback, Res.string.settings_playback_section_player),
            if (isDesktop) section(SettingsPage.Playback, Res.string.settings_playback_section_anime) else null,
            if (isDesktop) section(SettingsPage.Playback, Res.string.settings_playback_section_advanced) else null,
            section(SettingsPage.Playback, Res.string.settings_playback_section_subtitle_audio),
            section(SettingsPage.Playback, Res.string.settings_playback_section_subtitle_rendering),
            if (P2pSettingsRepository.isVisible) section(SettingsPage.Playback, Res.string.settings_p2p_title) else null,
            section(SettingsPage.Playback, Res.string.settings_playback_section_stream_selection),
            section(SettingsPage.Playback, Res.string.settings_playback_section_stream_auto_play),
            section(SettingsPage.Playback, Res.string.settings_playback_section_skip_segments),
            section(SettingsPage.Playback, Res.string.settings_playback_section_next_episode),
        )
        SettingsPage.Homescreen -> {
            val catalogCount = homescreenItems.count { !it.isCollection }
            val collectionCount = homescreenItems.count { it.isCollection }
            // Mirrors the title HomescreenSettingsPage picks for its catalog list.
            val catalogsTitle = when {
                collectionCount > 0 && catalogCount > 0 -> Res.string.settings_homescreen_section_catalogs_collections
                collectionCount > 0 -> Res.string.settings_homescreen_section_collections
                else -> Res.string.settings_homescreen_section_catalogs
            }
            listOf(
                section(SettingsPage.Homescreen, Res.string.settings_home_display_mode),
                section(SettingsPage.Homescreen, Res.string.settings_homescreen_section_hero),
                section(SettingsPage.Homescreen, Res.string.settings_homescreen_section_search),
                section(SettingsPage.Homescreen, Res.string.settings_homescreen_section_hero_sources),
                section(SettingsPage.Homescreen, catalogsTitle),
            )
        }
        SettingsPage.Appearance -> listOf(
            section(SettingsPage.Appearance, Res.string.settings_appearance_section_theme),
            section(SettingsPage.Appearance, Res.string.settings_appearance_custom_theme),
            section(SettingsPage.Appearance, Res.string.settings_appearance_section_display),
            section(SettingsPage.Appearance, Res.string.settings_poster_card_style),
            section(SettingsPage.Appearance, Res.string.settings_appearance_card_depth),
        )
        SettingsPage.ContinueWatching -> listOf(
            section(SettingsPage.ContinueWatching, Res.string.settings_continue_watching_section_card_style),
            section(SettingsPage.ContinueWatching, Res.string.settings_continue_watching_section_default_action),
            section(SettingsPage.ContinueWatching, Res.string.settings_cw_source_section),
            section(SettingsPage.ContinueWatching, Res.string.settings_continue_watching_section_up_next_behavior),
            section(SettingsPage.ContinueWatching, Res.string.settings_continue_watching_section_on_launch),
            section(SettingsPage.ContinueWatching, Res.string.settings_advanced_section_cache),
            section(SettingsPage.ContinueWatching, Res.string.settings_continue_watching_section_sort_order),
        )
        SettingsPage.Account -> listOfNotNull(
            section(SettingsPage.Account, Res.string.compose_settings_page_account),
            section(SettingsPage.Account, Res.string.settings_sync_section),
            if (isDesktop) section(SettingsPage.Account, Res.string.settings_backup_section) else null,
            section(SettingsPage.Account, Res.string.settings_advanced_section_startup),
            if (AppUpdaterPlatform.isSupported) section(SettingsPage.Account, Res.string.settings_updates_section) else null,
        )
        SettingsPage.Advanced -> listOf(
            section(SettingsPage.Advanced, Res.string.settings_advanced_section_diagnostics),
            section(SettingsPage.Advanced, Res.string.settings_advanced_section_startup),
            section(SettingsPage.Advanced, Res.string.settings_advanced_section_cache),
        )
        SettingsPage.Integrations -> listOfNotNull(
            section(SettingsPage.Integrations, Res.string.settings_library_source_section),
            section(SettingsPage.Integrations, Res.string.settings_calendar_source_section),
            section(SettingsPage.Integrations, Res.string.settings_integrations_section_title),
            subPage(SettingsPage.TmdbEnrichment),
            subPage(SettingsPage.MdbListRatings),
            subPage(SettingsPage.QualiCache),
            subPage(SettingsPage.PosterService),
            subPage(SettingsPage.Debrid),
            subPage(SettingsPage.TraktAuthentication),
            subPage(SettingsPage.SimklAuthentication),
            subPage(SettingsPage.YamtrackAuthentication),
            if (isDesktop) subPage(SettingsPage.Seekr) else null,
            if (isDesktop) subPage(SettingsPage.Lights) else null,
            if (isDesktop) subPage(SettingsPage.DiscordPresence) else null,
        )
        SettingsPage.Streams -> listOf(
            // The page's own "Stream scoring" section holds only the row that opens the sub-page,
            // so the sub-page is the one entry - listing both put the same label twice.
            section(SettingsPage.Streams, Res.string.settings_stream_badges_section),
            section(SettingsPage.Streams, Res.string.settings_stream_display_section),
            subPage(SettingsPage.StreamScoring),
        )
        SettingsPage.Discover -> listOf(
            section(SettingsPage.Discover, Res.string.settings_discover_section_row_order),
            section(SettingsPage.Discover, Res.string.settings_discover_section_rows),
            section(SettingsPage.Discover, Res.string.settings_discover_section_excluded_genres),
            section(SettingsPage.Discover, Res.string.settings_discover_ai_section),
        )
        SettingsPage.RandomPlay -> listOf(
            section(SettingsPage.RandomPlay, Res.string.random_play_section_catalog),
            section(SettingsPage.RandomPlay, Res.string.random_play_section_types),
            section(SettingsPage.RandomPlay, Res.string.random_play_section_filters),
            section(SettingsPage.RandomPlay, Res.string.random_play_section_genres),
        )
        SettingsPage.Screensaver -> listOf(
            section(SettingsPage.Screensaver, Res.string.settings_screensaver_section_title),
            section(SettingsPage.Screensaver, Res.string.settings_screensaver_section_shutdown),
        )
        SettingsPage.Games -> listOf(
            section(SettingsPage.Games, Res.string.settings_games_title),
            section(SettingsPage.Games, Res.string.settings_games_section_metadata),
            section(SettingsPage.Games, Res.string.settings_games_section_presentation),
            section(SettingsPage.Games, Res.string.settings_games_section_rows),
            section(SettingsPage.Games, Res.string.settings_games_section_artwork),
        )
        SettingsPage.Iptv -> listOf(
            section(SettingsPage.Iptv, Res.string.iptv_settings_section_playlists),
            section(SettingsPage.Iptv, Res.string.iptv_settings_section_updates),
            section(SettingsPage.Iptv, Res.string.iptv_settings_section_playback),
            section(SettingsPage.Iptv, Res.string.iptv_settings_section_connection),
        )
        SettingsPage.LocalLibrary -> listOf(
            section(SettingsPage.LocalLibrary, Res.string.settings_local_library_browse_section),
            section(SettingsPage.LocalLibrary, Res.string.settings_local_library_playback_title),
            section(SettingsPage.LocalLibrary, Res.string.settings_local_library_folders_title),
            section(SettingsPage.LocalLibrary, Res.string.settings_local_library_catalogs_title),
        )
        SettingsPage.AutoDownloads -> listOf(
            section(SettingsPage.AutoDownloads, Res.string.compose_settings_root_downloads_title),
            section(SettingsPage.AutoDownloads, Res.string.library_downloads_section_settings),
            section(SettingsPage.AutoDownloads, Res.string.library_downloads_section_monitored),
            section(SettingsPage.AutoDownloads, Res.string.library_downloads_section_activity),
        )
        SettingsPage.Notifications -> if (AppFeaturePolicy.notificationsEnabled) {
            listOf(
                section(SettingsPage.Notifications, Res.string.settings_notifications_section_alerts),
                section(SettingsPage.Notifications, Res.string.settings_notifications_section_test),
            )
        } else {
            emptyList()
        }
        SettingsPage.KeyboardShortcuts -> listOf(
            section(SettingsPage.KeyboardShortcuts, Res.string.settings_shortcuts_navigation),
            // Literal titles, matching KeyboardShortcutsPage's own.
            sectionTitled(SettingsPage.KeyboardShortcuts, "Player · Playback"),
            sectionTitled(SettingsPage.KeyboardShortcuts, "Player · Tracks & Panels"),
            sectionTitled(SettingsPage.KeyboardShortcuts, "Player · Video Enhancement"),
            sectionTitled(SettingsPage.KeyboardShortcuts, "Controller"),
            sectionTitled(SettingsPage.KeyboardShortcuts, "Controller · Buttons"),
        )
        else -> emptyList()
    }
}

private fun sectionTitled(page: SettingsPage, title: String): DesktopSettingsSidebarSubItem =
    DesktopSettingsSidebarSubItem(
        label = settingsTitleCase(title),
        page = page,
        anchor = SettingsScrollAnchor.section(title),
    )
