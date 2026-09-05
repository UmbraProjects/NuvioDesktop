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
import androidx.compose.material.icons.rounded.Explore
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
import androidx.compose.material.icons.rounded.SportsEsports
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
import com.nuvio.app.features.home.RandomPlayGenres
import com.nuvio.app.features.updater.AppUpdaterPlatform
import com.nuvio.app.isDesktop
import com.nuvio.app.isIos
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.core.ui.trackTextInputFocus

internal sealed class SettingsSearchTarget {
    data class Page(
        val page: SettingsPage,
        val anchor: String? = null,
        val fallbackAnchor: String? = null,
        val fallbackTitle: String? = null,
    ) : SettingsSearchTarget()
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

/**
 * Builds the settings search index.
 *
 * Suspending rather than `@Composable` on purpose: this resolves ~550 string resources, and on
 * desktop `stringResource` reads each one with a separate `runBlocking` byte-range read out of the
 * packaged jar (~250ms of blocking I/O in total). Composing it eagerly stalled the first frame of
 * the settings screen. Callers go through [SettingsSearchIndex], which builds this once off the UI
 * thread and caches it.
 *
 * The resolving happens in two passes rather than inline, because a single suspend function with
 * ~550 `getString` calls in its body compiles to a coroutine state machine large enough to run the
 * Kotlin JVM backend out of memory. The first pass collects which resources the index needs, the
 * loop between them is the only suspension point, and the second pass builds the real entries.
 */
internal suspend fun buildSettingsSearchEntries(
    pluginsEnabled: Boolean,
    downloadsEnabled: Boolean,
    notificationsEnabled: Boolean,
    liquidGlassNativeTabBarSupported: Boolean,
    switchProfileAvailable: Boolean,
    checkForUpdatesAvailable: Boolean,
): List<SettingsSearchEntry> {
    val required = LinkedHashSet<StringResource>()
    settingsSearchEntries(
        pluginsEnabled = pluginsEnabled,
        downloadsEnabled = downloadsEnabled,
        notificationsEnabled = notificationsEnabled,
        liquidGlassNativeTabBarSupported = liquidGlassNativeTabBarSupported,
        switchProfileAvailable = switchProfileAvailable,
        checkForUpdatesAvailable = checkForUpdatesAvailable,
        resolve = { resource -> required += resource; "" },
    )
    val resolved = HashMap<StringResource, String>(required.size)
    for (resource in required) {
        resolved[resource] = getString(resource)
    }
    return settingsSearchEntries(
        pluginsEnabled = pluginsEnabled,
        downloadsEnabled = downloadsEnabled,
        notificationsEnabled = notificationsEnabled,
        liquidGlassNativeTabBarSupported = liquidGlassNativeTabBarSupported,
        switchProfileAvailable = switchProfileAvailable,
        checkForUpdatesAvailable = checkForUpdatesAvailable,
        resolve = { resource -> resolved.getValue(resource) },
    )
}

private fun settingsSearchEntries(
    pluginsEnabled: Boolean,
    downloadsEnabled: Boolean,
    notificationsEnabled: Boolean,
    liquidGlassNativeTabBarSupported: Boolean,
    switchProfileAvailable: Boolean,
    checkForUpdatesAvailable: Boolean,
    resolve: (StringResource) -> String,
): List<SettingsSearchEntry> {
    val accountCategory = resolve(SettingsCategory.Account.labelRes)
    val generalCategory = resolve(SettingsCategory.General.labelRes)
    val aboutCategory = resolve(SettingsCategory.About.labelRes)
    val advancedCategory = resolve(SettingsCategory.Advanced.labelRes)

    val accountPage = resolve(Res.string.compose_settings_page_account)
    val traktPage = resolve(Res.string.compose_settings_page_trakt)
    val layoutPage = resolve(Res.string.compose_settings_page_appearance)
    val advancedPage = resolve(Res.string.compose_settings_page_advanced)
    val contentDiscoveryPage = resolve(Res.string.compose_settings_page_content_discovery)
    val downloadsPage = resolve(Res.string.compose_settings_root_downloads_title)
    val autoDownloadsPage = resolve(Res.string.compose_settings_page_auto_downloads)
    val playbackPage = resolve(Res.string.compose_settings_page_playback)
    val randomPlayPage = resolve(Res.string.random_play_title)
    val discoverPage = resolve(Res.string.compose_settings_page_discover)
    val streamsPage = resolve(Res.string.compose_settings_page_streams)
    val streamScoringPage = resolve(Res.string.compose_settings_page_stream_scoring)
    val localLibraryPage = resolve(Res.string.compose_settings_page_local_library)
    val gamesPage = resolve(Res.string.compose_settings_page_games)
    val posterCustomizationPage = resolve(Res.string.compose_settings_page_poster_customization)
    val integrationsPage = resolve(Res.string.compose_settings_page_integrations)
    val debridPage = resolve(Res.string.compose_settings_page_debrid)
    val notificationsPage = resolve(Res.string.compose_settings_page_notifications)
    val supportersPage = resolve(Res.string.compose_settings_page_supporters_contributors)
    val licensesPage = resolve(Res.string.compose_settings_page_licenses_attributions)
    val homeLayoutPage = resolve(Res.string.compose_settings_page_homescreen)
    val detailPage = resolve(Res.string.compose_settings_page_meta_screen)
    val continueWatchingPage = resolve(Res.string.compose_settings_page_continue_watching)
    val addonsPage = resolve(Res.string.compose_settings_page_addons)
    val pluginsPage = resolve(Res.string.compose_settings_page_plugins)
    val collectionsPage = resolve(Res.string.collections_header)
    val tmdbPage = resolve(Res.string.compose_settings_page_tmdb_enrichment)
    val mdbListPage = resolve(Res.string.compose_settings_page_mdblist_ratings)
    val qualiCachePage = resolve(Res.string.compose_settings_page_qualicache)
    val simklPage = resolve(Res.string.compose_settings_page_simkl)
    val yamtrackPage = resolve(Res.string.compose_settings_page_yamtrack)

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
            title = resolve(Res.string.compose_settings_root_switch_profile_title),
            description = resolve(Res.string.compose_settings_root_switch_profile_description),
            page = accountPage,
            section = resolve(Res.string.compose_settings_root_account_section),
            category = accountCategory,
            icon = Icons.Rounded.People,
            target = SettingsSearchTarget.SwitchProfile,
        )
    }
    addPage(
        page = SettingsPage.Account,
        key = "account",
        title = accountPage,
        description = resolve(Res.string.compose_settings_root_account_description),
        category = accountCategory,
        icon = Icons.Rounded.AccountCircle,
    )
    addPage(
        page = SettingsPage.TraktAuthentication,
        key = "trakt",
        title = traktPage,
        description = resolve(Res.string.compose_settings_root_trakt_description),
        category = generalCategory,
        icon = Icons.Rounded.Link,
    )
    addPage(
        page = SettingsPage.Appearance,
        key = "layout",
        title = layoutPage,
        description = resolve(Res.string.compose_settings_root_appearance_description),
        icon = Icons.Rounded.Palette,
    )
    addPage(
        page = SettingsPage.Advanced,
        key = "advanced",
        title = advancedPage,
        description = resolve(Res.string.compose_settings_root_advanced_description),
        category = advancedCategory,
        icon = Icons.Rounded.Tune,
    )
    addPage(
        page = SettingsPage.ContentDiscovery,
        key = "content-discovery",
        title = contentDiscoveryPage,
        description = resolve(Res.string.compose_settings_root_content_discovery_description),
        icon = Icons.Rounded.Extension,
    )
    if (downloadsEnabled) {
        addPage(
            page = SettingsPage.AutoDownloads,
            key = "auto-downloads",
            title = autoDownloadsPage,
            description = resolve(Res.string.settings_auto_downloads_description),
            icon = Icons.Rounded.CloudDownload,
        )
        addPage(
            page = SettingsPage.Downloads,
            key = "downloads",
            title = downloadsPage,
            description = resolve(Res.string.compose_settings_root_downloads_description),
            icon = Icons.Rounded.CloudDownload,
        )
    }
    addPage(
        page = SettingsPage.Playback,
        key = "playback",
        title = playbackPage,
        description = resolve(Res.string.settings_playback_subtitle),
        icon = Icons.Rounded.PlayArrow,
    )
    addPage(
        page = SettingsPage.RandomPlay,
        key = "random-play",
        title = randomPlayPage,
        description = resolve(Res.string.random_play_settings_description),
        icon = Icons.Rounded.Casino,
    )
    listOf(
        PlaybackSearchRow("random-play-enable", resolve(Res.string.random_play_enable), resolve(Res.string.random_play_enable_description), resolve(Res.string.random_play_section_catalog)),
        PlaybackSearchRow("random-play-include-collections", resolve(Res.string.random_play_include_collections), resolve(Res.string.random_play_include_collections_description), resolve(Res.string.random_play_section_catalog)),
        PlaybackSearchRow("random-play-click-action", resolve(Res.string.random_play_click_action), resolve(Res.string.random_play_click_action_description), resolve(Res.string.random_play_section_catalog)),
        PlaybackSearchRow("random-play-movie", resolve(Res.string.random_play_movie), sectionOverride = resolve(Res.string.random_play_section_types)),
        PlaybackSearchRow("random-play-series", resolve(Res.string.random_play_series), sectionOverride = resolve(Res.string.random_play_section_types)),
        PlaybackSearchRow("random-play-anime-movie", resolve(Res.string.random_play_anime_movie), sectionOverride = resolve(Res.string.random_play_section_types)),
        PlaybackSearchRow("random-play-anime-series", resolve(Res.string.random_play_anime_series), sectionOverride = resolve(Res.string.random_play_section_types)),
        PlaybackSearchRow("random-play-minimum-imdb", resolve(Res.string.random_play_minimum_imdb), resolve(Res.string.random_play_minimum_imdb_description), resolve(Res.string.random_play_section_filters)),
    ).forEach { row ->
        addRow(
            page = SettingsPage.RandomPlay,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = randomPlayPage,
            section = row.sectionOverride ?: randomPlayPage,
            icon = Icons.Rounded.Casino,
        )
    }
    RandomPlayGenres.forEach { genre ->
        addRow(
            page = SettingsPage.RandomPlay,
            key = "random-play-genre-${genre.lowercase().replace(' ', '-')}",
            title = genre,
            pageLabel = randomPlayPage,
            section = resolve(Res.string.random_play_section_genres),
            icon = Icons.Rounded.Casino,
        )
    }
    addPage(
        page = SettingsPage.Discover,
        key = "discover",
        title = discoverPage,
        description = resolve(Res.string.settings_discover_page_description),
        icon = Icons.Rounded.Explore,
    )
    listOf(
        PlaybackSearchRow(
            "discover-row-order",
            resolve(Res.string.settings_discover_section_row_order),
            resolve(Res.string.settings_discover_row_order_description),
            resolve(Res.string.settings_discover_section_row_order),
        ),
        PlaybackSearchRow(
            "discover-custom-rows",
            resolve(Res.string.settings_discover_add_custom_row),
            resolve(Res.string.settings_discover_add_custom_row_description),
            resolve(Res.string.settings_discover_section_row_order),
        ),
        PlaybackSearchRow(
            "discover-import-row",
            resolve(Res.string.settings_discover_import_row),
            resolve(Res.string.settings_discover_export_dialog_subtitle),
            resolve(Res.string.settings_discover_section_row_order),
        ),
        PlaybackSearchRow(
            "discover-export-row",
            resolve(Res.string.settings_discover_export_row),
            resolve(Res.string.settings_discover_export_dialog_subtitle),
            resolve(Res.string.settings_discover_section_row_order),
        ),
        PlaybackSearchRow(
            "discover-ai-enabled",
            resolve(Res.string.settings_discover_ai_enabled),
            resolve(Res.string.settings_discover_ai_enabled_description),
            resolve(Res.string.settings_discover_ai_section),
        ),
        PlaybackSearchRow(
            "discover-ai-rows",
            resolve(Res.string.settings_discover_ai_add_row),
            resolve(Res.string.settings_discover_ai_add_row_description),
            resolve(Res.string.settings_discover_section_row_order),
        ),
        PlaybackSearchRow(
            "discover-ai-suggested",
            resolve(Res.string.settings_discover_ai_suggested),
            resolve(Res.string.settings_discover_ai_suggested_description),
            resolve(Res.string.settings_discover_section_row_order),
        ),
        PlaybackSearchRow(
            "discover-finish-started",
            resolve(Res.string.settings_discover_finish_started),
            resolve(Res.string.settings_discover_finish_started_description),
            resolve(Res.string.settings_discover_section_row_order),
        ),
        PlaybackSearchRow(
            "discover-finish-idle-days",
            resolve(Res.string.settings_discover_finish_idle_days),
            resolve(Res.string.settings_discover_finish_idle_days_description),
            resolve(Res.string.settings_discover_section_rows),
        ),
        PlaybackSearchRow(
            "discover-because-rows",
            resolve(Res.string.settings_discover_because_rows),
            resolve(Res.string.settings_discover_because_rows_description),
            resolve(Res.string.settings_discover_section_rows),
        ),
        PlaybackSearchRow(
            "discover-more-like-favourites",
            resolve(Res.string.settings_discover_more_like_favourites),
            resolve(Res.string.settings_discover_more_like_favourites_description),
            resolve(Res.string.settings_discover_section_rows),
        ),
        PlaybackSearchRow(
            "discover-hidden-gems",
            resolve(Res.string.settings_discover_hidden_gems),
            resolve(Res.string.settings_discover_hidden_gems_description),
            resolve(Res.string.settings_discover_section_rows),
        ),
        PlaybackSearchRow(
            "discover-trending-rows",
            resolve(Res.string.settings_discover_trending_rows),
            resolve(Res.string.settings_discover_trending_rows_description),
            resolve(Res.string.settings_discover_section_rows),
        ),
        PlaybackSearchRow(
            "discover-hide-watched",
            resolve(Res.string.settings_discover_hide_watched),
            resolve(Res.string.settings_discover_hide_watched_description),
            resolve(Res.string.settings_discover_section_rows),
        ),
        PlaybackSearchRow(
            "discover-excluded-genres",
            resolve(Res.string.settings_discover_section_excluded_genres),
            resolve(Res.string.settings_discover_excluded_genres_description),
            resolve(Res.string.settings_discover_section_excluded_genres),
        ),
    ).forEach { row ->
        addRow(
            page = SettingsPage.Discover,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = discoverPage,
            section = row.sectionOverride ?: discoverPage,
            icon = Icons.Rounded.Explore,
        )
    }
    addPage(
        page = SettingsPage.Streams,
        key = "streams",
        title = streamsPage,
        description = resolve(Res.string.compose_settings_root_streams_description),
        icon = Icons.Rounded.Style,
    )
    addPage(
        page = SettingsPage.StreamScoring,
        key = "stream-scoring",
        title = streamScoringPage,
        description = resolve(Res.string.settings_stream_scoring_enabled_desc),
        icon = Icons.Rounded.Tune,
    )
    addPage(
        page = SettingsPage.LocalLibrary,
        key = "local-library",
        title = localLibraryPage,
        description = resolve(Res.string.settings_local_library_description),
        icon = Icons.Rounded.CollectionsBookmark,
    )
    addPage(
        page = SettingsPage.Games,
        key = "games",
        title = gamesPage,
        description = resolve(Res.string.compose_settings_root_games_description),
        icon = Icons.Rounded.SportsEsports,
    )
    addPage(
        page = SettingsPage.Integrations,
        key = "integrations",
        title = integrationsPage,
        description = resolve(Res.string.compose_settings_root_integrations_description),
        icon = Icons.Rounded.Link,
    )
    addRow(
        page = SettingsPage.Integrations,
        key = "library-source",
        title = resolve(Res.string.settings_library_source_title),
        description = resolve(Res.string.settings_library_source_description),
        pageLabel = integrationsPage,
        section = resolve(Res.string.settings_library_source_section),
        icon = Icons.Rounded.Link,
        anchor = SettingsScrollAnchor.searchKey("library-source"),
    )
    addRow(
        page = SettingsPage.Integrations,
        key = "rating-prompt",
        title = resolve(Res.string.settings_rating_prompt_title),
        description = resolve(Res.string.settings_rating_prompt_description),
        pageLabel = integrationsPage,
        section = resolve(Res.string.settings_library_source_section),
        icon = Icons.Rounded.Link,
        anchor = SettingsScrollAnchor.searchKey("library-source"),
    )
    addRow(
        page = SettingsPage.Integrations,
        key = "calendar-source",
        title = resolve(Res.string.settings_calendar_source_title),
        description = resolve(Res.string.settings_calendar_source_description),
        pageLabel = integrationsPage,
        section = resolve(Res.string.settings_calendar_source_section),
        icon = Icons.Rounded.Link,
        anchor = SettingsScrollAnchor.searchKey("calendar-source"),
    )
    if (isDesktop) {
        addPage(
            page = SettingsPage.KeyboardShortcuts,
            key = "keyboard-shortcuts",
            title = resolve(Res.string.compose_settings_page_keyboard_shortcuts),
            description = resolve(Res.string.settings_shortcuts_search_description),
            icon = Icons.Rounded.Keyboard,
        )
    }
    if (isDesktop) {
        addRow(
            page = SettingsPage.Appearance,
            key = "start-windowed",
            title = resolve(Res.string.settings_appearance_start_windowed),
            description = resolve(Res.string.settings_appearance_start_windowed_description),
            pageLabel = layoutPage,
            section = resolve(Res.string.settings_appearance_section_display),
            icon = Icons.Rounded.Palette,
        )
        addRow(
            page = SettingsPage.Integrations,
            key = "discord-presence",
            title = resolve(Res.string.settings_discord_presence),
            description = resolve(Res.string.settings_discord_presence_search_description),
            pageLabel = integrationsPage,
            section = resolve(Res.string.settings_integrations_section_title),
            icon = Icons.Rounded.Link,
            anchor = SettingsScrollAnchor.DiscordPresence,
        )
        addRow(
            page = SettingsPage.Integrations,
            key = "discord-episode-artwork",
            title = resolve(Res.string.settings_discord_episode_artwork),
            description = resolve(Res.string.settings_discord_episode_artwork_search_description),
            pageLabel = integrationsPage,
            section = resolve(Res.string.settings_integrations_section_title),
            icon = Icons.Rounded.Link,
            anchor = SettingsScrollAnchor.DiscordEpisodeArtwork,
        )
    }
    // The four export destinations lost their own rows when they moved inside the export dialog
    // (see DiscoverExportButton). They keep their search entries — someone looking for "BingeCat"
    // should still find it — but every one of them anchors to the single entry that opens them,
    // because that is the only thing left on the page to scroll to.
    listOf(
        Res.string.settings_discover_mdblist_publish to Res.string.settings_discover_mdblist_publish_description,
        Res.string.settings_discover_bingecat_copy to Res.string.settings_discover_bingecat_copy_description,
        Res.string.settings_discover_collection_save to Res.string.settings_discover_collection_save_description,
        Res.string.settings_discover_aiometadata_export to Res.string.settings_discover_aiometadata_export_description,
    ).forEachIndexed { index, (title, description) ->
        addRow(
            page = SettingsPage.Discover,
            key = "discover-export-destination-$index",
            title = resolve(title),
            description = resolve(description),
            pageLabel = discoverPage,
            section = resolve(Res.string.settings_discover_section_row_order),
            icon = Icons.Rounded.Link,
            anchor = SettingsScrollAnchor.searchKey("discover-export-row"),
        )
    }

    if (notificationsEnabled) {
        addPage(
            page = SettingsPage.Notifications,
            key = "notifications",
            title = notificationsPage,
            description = resolve(Res.string.compose_settings_root_notifications_description),
            icon = Icons.Rounded.Notifications,
        )
    }
    addPage(
        page = SettingsPage.SupportersContributors,
        key = "supporters",
        title = supportersPage,
        description = resolve(Res.string.about_supporters_contributors_subtitle),
        category = aboutCategory,
        icon = Icons.Rounded.Favorite,
    )
    addPage(
        page = SettingsPage.LicensesAttributions,
        key = "licenses-attributions",
        title = licensesPage,
        description = resolve(Res.string.about_licenses_attributions_subtitle),
        category = aboutCategory,
        icon = Icons.Rounded.Info,
    )
    listOf(
        PlaybackSearchRow("nuvio-license", resolve(Res.string.settings_licenses_attributions_nuvio_title), resolve(Res.string.settings_licenses_attributions_nuvio_license)),
        PlaybackSearchRow("tmdb-attribution", resolve(Res.string.settings_licenses_attributions_tmdb_title), resolve(Res.string.settings_licenses_attributions_tmdb_body)),
        PlaybackSearchRow("trakt-attribution", resolve(Res.string.settings_licenses_attributions_trakt_title), resolve(Res.string.settings_licenses_attributions_trakt_body)),
        PlaybackSearchRow("premiumize-attribution", resolve(Res.string.settings_licenses_attributions_premiumize_title), resolve(Res.string.settings_licenses_attributions_premiumize_body)),
        PlaybackSearchRow("torbox-attribution", resolve(Res.string.settings_licenses_attributions_torbox_title), resolve(Res.string.settings_licenses_attributions_torbox_body)),
        PlaybackSearchRow("mdblist-attribution", resolve(Res.string.settings_licenses_attributions_mdblist_title), resolve(Res.string.settings_licenses_attributions_mdblist_body)),
        PlaybackSearchRow("introdb-attribution", resolve(Res.string.settings_licenses_attributions_introdb_title), resolve(Res.string.settings_licenses_attributions_introdb_body)),
        PlaybackSearchRow("tvdb-attribution", resolve(Res.string.settings_licenses_attributions_tvdb_title), resolve(Res.string.settings_licenses_attributions_tvdb_body)),
        PlaybackSearchRow("simkl-attribution", resolve(Res.string.settings_licenses_attributions_simkl_title), resolve(Res.string.settings_licenses_attributions_simkl_body)),
        PlaybackSearchRow("imdb-datasets", resolve(Res.string.settings_licenses_attributions_imdb_title), resolve(Res.string.settings_licenses_attributions_imdb_body)),
        PlaybackSearchRow(
            if (isIos) "mpvkit-license" else "exoplayer-license",
            if (isIos) {
                resolve(Res.string.settings_licenses_attributions_mpvkit_title)
            } else {
                resolve(Res.string.settings_licenses_attributions_exoplayer_title)
            },
            if (isIos) {
                resolve(Res.string.settings_licenses_attributions_mpvkit_license)
            } else {
                resolve(Res.string.settings_licenses_attributions_exoplayer_license)
            },
        ),
    ).forEach { row ->
        addRow(
            page = SettingsPage.LicensesAttributions,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = licensesPage,
            section = resolve(Res.string.compose_settings_root_about_section),
            category = aboutCategory,
            icon = Icons.Rounded.Info,
        )
    }
    if (checkForUpdatesAvailable) {
        add(
            key = "check-updates",
            title = resolve(Res.string.compose_settings_root_check_updates_title),
            description = resolve(Res.string.compose_settings_root_check_updates_description),
            page = supportersPage,
            section = resolve(Res.string.compose_settings_root_about_section),
            category = aboutCategory,
            icon = Icons.Rounded.CloudDownload,
            target = SettingsSearchTarget.CheckForUpdates,
        )
    }

    if (isDesktop) {
        addRow(
            page = SettingsPage.Account,
            key = "setup-wizard",
            title = resolve(Res.string.settings_account_run_wizard),
            description = resolve(Res.string.settings_account_run_wizard_description),
            pageLabel = accountPage,
            section = resolve(Res.string.settings_account_setup),
            category = accountCategory,
            icon = Icons.Rounded.AccountCircle,
        )
    }
    addRow(
        page = SettingsPage.Account,
        key = "account-status",
        title = resolve(Res.string.settings_account_status),
        pageLabel = accountPage,
        section = accountPage,
        category = accountCategory,
        icon = Icons.Rounded.AccountCircle,
    )
    addRow(
        page = SettingsPage.Account,
        key = "account-sign-out",
        title = resolve(Res.string.settings_account_sign_out),
        pageLabel = accountPage,
        section = accountPage,
        category = accountCategory,
        icon = Icons.Rounded.AccountCircle,
    )

    val synchronizationSection = resolve(Res.string.settings_sync_section)
    addRow(
        page = SettingsPage.Account,
        key = "sync-appearance",
        title = resolve(Res.string.settings_sync_appearance),
        description = resolve(Res.string.settings_sync_appearance_description),
        pageLabel = accountPage,
        section = synchronizationSection,
        category = accountCategory,
        icon = Icons.Rounded.Sync,
    )
    addRow(
        page = SettingsPage.Account,
        key = "sync-home-catalogs",
        title = resolve(Res.string.settings_sync_home_catalogs),
        description = resolve(Res.string.settings_sync_home_catalogs_description),
        pageLabel = accountPage,
        section = synchronizationSection,
        category = accountCategory,
        icon = Icons.Rounded.Sync,
    )
    addRow(
        page = SettingsPage.Account,
        key = "sync-stream-display",
        title = resolve(Res.string.settings_sync_stream_display),
        description = resolve(Res.string.settings_sync_stream_display_description),
        pageLabel = accountPage,
        section = synchronizationSection,
        category = accountCategory,
        icon = Icons.Rounded.Sync,
    )
    addRow(
        page = SettingsPage.Account,
        key = "sync-debrid",
        title = resolve(Res.string.settings_sync_debrid),
        description = resolve(Res.string.settings_sync_debrid_description),
        pageLabel = accountPage,
        section = synchronizationSection,
        category = accountCategory,
        icon = Icons.Rounded.Sync,
    )
    addRow(
        page = SettingsPage.Account,
        key = "sync-metadata",
        title = resolve(Res.string.settings_sync_metadata),
        description = resolve(Res.string.settings_sync_metadata_description),
        pageLabel = accountPage,
        section = synchronizationSection,
        category = accountCategory,
        icon = Icons.Rounded.Sync,
    )
    addRow(
        page = SettingsPage.Account,
        key = "sync-content-preferences",
        title = resolve(Res.string.settings_sync_content_preferences),
        description = resolve(Res.string.settings_sync_content_preferences_description),
        pageLabel = accountPage,
        section = synchronizationSection,
        category = accountCategory,
        icon = Icons.Rounded.Sync,
    )
    addRow(
        page = SettingsPage.Account,
        key = "sync-trakt",
        title = resolve(Res.string.settings_sync_trakt),
        description = resolve(Res.string.settings_sync_trakt_description),
        pageLabel = accountPage,
        section = synchronizationSection,
        category = accountCategory,
        icon = Icons.Rounded.Sync,
    )
    addRow(
        page = SettingsPage.Account,
        key = "sync-notifications",
        title = resolve(Res.string.settings_sync_notifications),
        description = resolve(Res.string.settings_sync_notifications_description),
        pageLabel = accountPage,
        section = synchronizationSection,
        category = accountCategory,
        icon = Icons.Rounded.Sync,
    )
    if (isDesktop) {
        val backupSection = resolve(Res.string.settings_backup_section)
        addRow(
            page = SettingsPage.Account,
            key = "backup-settings",
            title = resolve(Res.string.settings_backup_without_credentials),
            description = resolve(Res.string.settings_backup_without_credentials_description),
            pageLabel = accountPage,
            section = backupSection,
            category = accountCategory,
            icon = Icons.Rounded.CloudDownload,
        )
        addRow(
            page = SettingsPage.Account,
            key = "backup-settings-credentials",
            title = resolve(Res.string.settings_backup_with_credentials),
            description = resolve(Res.string.settings_backup_with_credentials_description),
            pageLabel = accountPage,
            section = backupSection,
            category = accountCategory,
            icon = Icons.Rounded.CloudDownload,
        )
    }

    addRow(
        page = SettingsPage.Appearance,
        key = "theme",
        title = resolve(Res.string.settings_appearance_section_theme),
        pageLabel = layoutPage,
        section = resolve(Res.string.settings_appearance_section_theme),
        icon = Icons.Rounded.Palette,
    )
    addRow(
        page = SettingsPage.Appearance,
        key = "accent-gradient-direction",
        title = resolve(Res.string.settings_appearance_accent_gradient_direction),
        pageLabel = layoutPage,
        section = resolve(Res.string.settings_appearance_section_theme),
        icon = Icons.Rounded.Palette,
    )
    addRow(
        page = SettingsPage.Appearance,
        key = "amoled",
        title = resolve(Res.string.settings_appearance_amoled_black),
        description = resolve(Res.string.settings_appearance_amoled_description),
        pageLabel = layoutPage,
        section = resolve(Res.string.settings_appearance_section_display),
        icon = Icons.Rounded.Palette,
    )
    if (liquidGlassNativeTabBarSupported) {
        addRow(
            page = SettingsPage.Appearance,
            key = "liquid-glass",
            title = resolve(Res.string.settings_appearance_liquid_glass),
            description = resolve(Res.string.settings_appearance_liquid_glass_description),
            pageLabel = layoutPage,
            section = resolve(Res.string.settings_appearance_section_display),
            icon = Icons.Rounded.Palette,
        )
        addRow(
            page = SettingsPage.Appearance,
            key = "app-ui-scale",
            title = resolve(Res.string.settings_appearance_app_ui_scale),
            pageLabel = layoutPage,
            section = resolve(Res.string.settings_appearance_section_display),
            icon = Icons.Rounded.Palette,
        )
        addRow(
            page = SettingsPage.Appearance,
            key = "app-ui-scale-details",
            title = resolve(Res.string.settings_appearance_app_ui_scale_details),
            description = resolve(Res.string.settings_appearance_app_ui_scale_details_description),
            pageLabel = layoutPage,
            section = resolve(Res.string.settings_appearance_section_display),
            icon = Icons.Rounded.Palette,
        )
    }
    if (isDesktop) {
        addRow(
            page = SettingsPage.Appearance,
            key = "app-font",
            title = resolve(Res.string.settings_appearance_app_font),
            description = resolve(Res.string.settings_appearance_app_font_player_note),
            pageLabel = layoutPage,
            section = resolve(Res.string.settings_appearance_section_display),
            icon = Icons.Rounded.Palette,
        )
    }
    addRow(
        page = SettingsPage.Appearance,
        key = "app-language",
        title = resolve(Res.string.settings_appearance_app_language),
        pageLabel = layoutPage,
        section = resolve(Res.string.settings_appearance_section_display),
        icon = Icons.Rounded.Language,
    )
    if (isDesktop) {
        addRow(
            page = SettingsPage.Appearance,
            key = "desktop-navigation",
            title = resolve(Res.string.settings_appearance_desktop_navigation),
            description = resolve(Res.string.settings_desktop_navigation_search_description),
            pageLabel = layoutPage,
            section = resolve(Res.string.settings_appearance_section_display),
            icon = Icons.Rounded.Palette,
        )
        addRow(
            page = SettingsPage.Appearance,
            key = "desktop-discover-tab",
            title = resolve(Res.string.settings_appearance_desktop_discover_tab),
            description = resolve(Res.string.settings_appearance_desktop_discover_tab_description),
            pageLabel = layoutPage,
            section = resolve(Res.string.settings_appearance_section_display),
            icon = Icons.Rounded.Palette,
        )
        addRow(
            page = SettingsPage.Appearance,
            key = "desktop-top-bar-always-visible",
            title = resolve(Res.string.settings_appearance_desktop_top_bar_always_visible),
            description = resolve(Res.string.settings_appearance_desktop_top_bar_always_visible_description),
            pageLabel = layoutPage,
            section = resolve(Res.string.settings_appearance_section_display),
            icon = Icons.Rounded.Palette,
        )
    }
    addRow(
        page = SettingsPage.Account,
        key = "remember-last-profile",
        title = resolve(Res.string.settings_advanced_remember_last_profile),
        description = resolve(Res.string.settings_advanced_remember_last_profile_description),
        pageLabel = accountPage,
        section = resolve(Res.string.settings_advanced_section_startup),
        category = accountCategory,
        icon = Icons.Rounded.AccountCircle,
    )
    if (AppUpdaterPlatform.isSupported) {
        addRow(
            page = SettingsPage.Account,
            key = "update-channel",
            title = resolve(Res.string.settings_updates_channel),
            description = resolve(Res.string.settings_updates_channel_description),
            pageLabel = accountPage,
            section = resolve(Res.string.settings_updates_section),
            category = accountCategory,
            icon = Icons.Rounded.CloudDownload,
        )
        addRow(
            page = SettingsPage.Account,
            key = "auto-install-updates",
            title = resolve(Res.string.settings_updates_auto_install),
            description = resolve(Res.string.settings_updates_auto_install_description),
            pageLabel = accountPage,
            section = resolve(Res.string.settings_updates_section),
            category = accountCategory,
            icon = Icons.Rounded.CloudDownload,
        )
    }
    addRow(
        page = SettingsPage.ContinueWatching,
        key = "clear-cw-cache",
        title = resolve(Res.string.settings_advanced_clear_cw_cache),
        description = resolve(Res.string.settings_advanced_clear_cw_cache_subtitle),
        pageLabel = continueWatchingPage,
        section = resolve(Res.string.settings_advanced_section_cache),
        category = generalCategory,
        icon = Icons.Rounded.Tune,
    )
    addRow(
        page = SettingsPage.ContinueWatching,
        key = "continue-watching-withdraw-imported-history",
        title = resolve(Res.string.settings_cw_withdraw_imported_title),
        description = resolve(Res.string.settings_cw_withdraw_imported_subtitle),
        pageLabel = continueWatchingPage,
        section = resolve(Res.string.settings_advanced_section_cache),
        category = generalCategory,
        icon = Icons.Rounded.Tune,
    )
    addPage(
        page = SettingsPage.ContinueWatching,
        key = "continue-watching",
        title = continueWatchingPage,
        description = resolve(Res.string.settings_appearance_continue_watching_description),
        icon = Icons.Rounded.Style,
    )
    addPage(
        page = SettingsPage.Addons,
        key = "addons",
        title = addonsPage,
        description = resolve(Res.string.settings_content_discovery_addons_description),
        icon = Icons.Rounded.Extension,
    )
    if (pluginsEnabled) {
        addPage(
            page = SettingsPage.Plugins,
            key = "plugins",
            title = pluginsPage,
            description = resolve(Res.string.settings_content_discovery_plugins_description),
            icon = Icons.Rounded.Hub,
        )
    }
    addPage(
        page = SettingsPage.Homescreen,
        key = "home-layout",
        title = homeLayoutPage,
        description = resolve(Res.string.settings_content_discovery_homescreen_description),
        icon = Icons.Rounded.Home,
    )
    addPage(
        page = SettingsPage.PosterCustomization,
        key = "poster-customization",
        title = posterCustomizationPage,
        description = "Configure poster width, corners, landscape titles, and depth effects.",
        icon = Icons.Rounded.Palette,
    )
    addPage(
        page = SettingsPage.MetaScreen,
        key = "detail-page",
        title = detailPage,
        description = resolve(Res.string.settings_content_discovery_meta_screen_description),
        icon = Icons.Rounded.Tune,
    )
    add(
        key = "collections",
        title = collectionsPage,
        description = resolve(Res.string.settings_content_discovery_collections_description),
        page = contentDiscoveryPage,
        section = resolve(Res.string.settings_content_discovery_section_home),
        category = generalCategory,
        icon = Icons.Rounded.CollectionsBookmark,
        target = SettingsSearchTarget.Page(SettingsPage.Collections),
    )

    val playbackPlayer = resolve(Res.string.settings_playback_section_player)
    val playbackSubtitleAudio = resolve(Res.string.settings_playback_section_subtitle_audio)
    val playbackStreamSelection = resolve(Res.string.settings_playback_section_stream_selection)
    val playbackStreamAutoPlay = resolve(Res.string.settings_playback_section_stream_auto_play)
    val playbackSubtitleRendering = resolve(Res.string.settings_playback_section_subtitle_rendering)
    val playbackSkipSegments = resolve(Res.string.settings_playback_section_skip_segments)
    val playbackNextEpisode = resolve(Res.string.settings_playback_section_next_episode)
    addRow(
        page = SettingsPage.Streams,
        key = "stream-addon-logo",
        title = resolve(Res.string.settings_stream_addon_logo_title),
        description = resolve(Res.string.settings_stream_addon_logo_description),
        pageLabel = streamsPage,
        section = resolve(Res.string.settings_stream_display_section),
        icon = Icons.Rounded.Style,
    )
    addRow(
        page = SettingsPage.Streams,
        key = "stream-size-badges",
        title = resolve(Res.string.settings_stream_size_badges_title),
        description = resolve(Res.string.settings_stream_size_badges_description),
        pageLabel = streamsPage,
        section = resolve(Res.string.settings_stream_badges_section),
        icon = Icons.Rounded.Style,
    )
    addRow(
        page = SettingsPage.Streams,
        key = "stream-badge-position",
        title = resolve(Res.string.settings_stream_badge_position_title),
        description = resolve(Res.string.settings_stream_badge_position_description),
        pageLabel = streamsPage,
        section = resolve(Res.string.settings_stream_badges_section),
        icon = Icons.Rounded.Style,
    )
    addRow(
        page = SettingsPage.Streams,
        key = "stream-badge-urls",
        title = resolve(Res.string.settings_stream_badge_urls_title),
        description = resolve(Res.string.settings_stream_badge_urls_search_description),
        pageLabel = streamsPage,
        section = resolve(Res.string.settings_stream_badges_section),
        icon = Icons.Rounded.Style,
    )

    listOf(
        PlaybackSearchRow("stream-scoring-enabled", resolve(Res.string.settings_stream_scoring_enabled_title), resolve(Res.string.settings_stream_scoring_enabled_desc)),
        PlaybackSearchRow("stream-scoring-first-stream", resolve(Res.string.settings_stream_scoring_apply_first_stream), sectionOverride = resolve(Res.string.settings_stream_scoring_section_apply)),
        PlaybackSearchRow("stream-scoring-binge", resolve(Res.string.settings_stream_scoring_override_binge_group_title), resolve(Res.string.settings_stream_scoring_override_binge_group_desc), resolve(Res.string.settings_stream_scoring_section_apply)),
        PlaybackSearchRow("stream-scoring-auto-download", resolve(Res.string.settings_stream_scoring_apply_auto_download), sectionOverride = resolve(Res.string.settings_stream_scoring_section_apply)),
        PlaybackSearchRow("stream-scoring-failover", resolve(Res.string.settings_stream_scoring_apply_failover), sectionOverride = resolve(Res.string.settings_stream_scoring_section_apply)),
        PlaybackSearchRow("stream-scoring-sort", resolve(Res.string.settings_stream_scoring_sort_list_title), resolve(Res.string.settings_stream_scoring_sort_list_desc), resolve(Res.string.settings_stream_scoring_section_apply)),
        PlaybackSearchRow("stream-scoring-merge", resolve(Res.string.settings_stream_scoring_merge_sources_title), resolve(Res.string.settings_stream_scoring_merge_sources_desc), resolve(Res.string.settings_stream_scoring_section_apply)),
        PlaybackSearchRow("stream-scoring-htpc-tab", resolve(Res.string.settings_stream_scoring_htpc_tab_title), resolve(Res.string.settings_stream_scoring_htpc_tab_desc), resolve(Res.string.settings_stream_scoring_section_apply)),
        PlaybackSearchRow("stream-scoring-show-scores", resolve(Res.string.settings_stream_scoring_show_on_streams_title), resolve(Res.string.settings_stream_scoring_show_on_streams_desc), resolve(Res.string.settings_stream_scoring_section_apply)),
        PlaybackSearchRow("stream-scoring-audio", resolve(Res.string.settings_stream_scoring_question_audio_title), resolve(Res.string.settings_stream_scoring_question_audio_caption), resolve(Res.string.settings_stream_scoring_section_setup)),
        PlaybackSearchRow("stream-scoring-hdr", resolve(Res.string.settings_stream_scoring_question_hdr_title), resolve(Res.string.settings_stream_scoring_question_hdr_caption), resolve(Res.string.settings_stream_scoring_section_setup)),
        PlaybackSearchRow("stream-scoring-3d", resolve(Res.string.settings_stream_scoring_question_three_d_title), resolve(Res.string.settings_stream_scoring_question_three_d_caption), resolve(Res.string.settings_stream_scoring_section_setup)),
        PlaybackSearchRow("stream-scoring-size-quality", resolve(Res.string.settings_stream_scoring_question_size_quality_title), resolve(Res.string.settings_stream_scoring_question_size_quality_caption), resolve(Res.string.settings_stream_scoring_section_setup)),
        PlaybackSearchRow("stream-scoring-language", resolve(Res.string.settings_stream_scoring_question_language_title), resolve(Res.string.settings_stream_scoring_question_language_caption), resolve(Res.string.settings_stream_scoring_section_setup)),
        PlaybackSearchRow("stream-scoring-cached", resolve(Res.string.settings_stream_scoring_question_cached_title), resolve(Res.string.settings_stream_scoring_question_cached_caption), resolve(Res.string.settings_stream_scoring_section_setup)),
        PlaybackSearchRow("stream-scoring-unknown-group", resolve(Res.string.settings_stream_scoring_question_unknown_group_title), resolve(Res.string.settings_stream_scoring_question_unknown_group_caption), resolve(Res.string.settings_stream_scoring_section_setup)),
        PlaybackSearchRow("stream-scoring-low-quality-group", resolve(Res.string.settings_stream_scoring_question_low_quality_group_title), resolve(Res.string.settings_stream_scoring_question_low_quality_group_caption), resolve(Res.string.settings_stream_scoring_section_setup)),
        PlaybackSearchRow("stream-scoring-size-band", resolve(Res.string.settings_stream_scoring_size_band_title), resolve(Res.string.settings_stream_scoring_size_band_desc)),
        PlaybackSearchRow("stream-scoring-preview", resolve(Res.string.settings_stream_scoring_preview_title)),
    ).forEach { row ->
        addRow(
            page = SettingsPage.StreamScoring,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = streamScoringPage,
            section = row.sectionOverride ?: streamScoringPage,
            icon = Icons.Rounded.Tune,
        )
    }

    listOf(
        PlaybackSearchRow("local-library-browse", resolve(Res.string.settings_local_library_browse_open), sectionOverride = resolve(Res.string.settings_local_library_browse_section)),
        PlaybackSearchRow("local-library-playback", resolve(Res.string.settings_local_library_preferred_play_action), resolve(Res.string.settings_local_library_preferred_play_action_description), resolve(Res.string.settings_local_library_playback_title)),
        PlaybackSearchRow("local-library-anime-id", resolve(Res.string.settings_anime_id_preference), resolve(Res.string.settings_anime_id_preference_description), resolve(Res.string.settings_local_library_playback_title)),
        PlaybackSearchRow("local-library-folders", resolve(Res.string.settings_local_library_folders_title)),
        PlaybackSearchRow("local-library-layout", resolve(Res.string.settings_local_library_mode_title), resolve(Res.string.settings_local_library_mode_description), resolve(Res.string.settings_local_library_catalogs_title)),
        PlaybackSearchRow("local-library-catalogs", resolve(Res.string.settings_local_library_catalogs_title)),
    ).forEach { row ->
        addRow(
            page = SettingsPage.LocalLibrary,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = localLibraryPage,
            section = row.sectionOverride ?: localLibraryPage,
            icon = Icons.Rounded.CollectionsBookmark,
        )
    }

    listOf(
        PlaybackSearchRow("games-shortcut", gamesPage, resolve(Res.string.settings_games_shortcut_hint), resolve(Res.string.settings_games_title)),
        PlaybackSearchRow("games-igdb-client-id", resolve(Res.string.settings_games_igdb_client_id), resolve(Res.string.settings_games_igdb_client_id_description), resolve(Res.string.settings_games_section_igdb)),
        PlaybackSearchRow("games-igdb-client-secret", resolve(Res.string.settings_games_igdb_client_secret), resolve(Res.string.settings_games_igdb_client_secret_description), resolve(Res.string.settings_games_section_igdb)),
        PlaybackSearchRow("games-backdrop-style", resolve(Res.string.settings_games_backdrop_style), resolve(Res.string.settings_games_backdrop_style_description), resolve(Res.string.settings_games_section_presentation)),
        PlaybackSearchRow("games-steamgriddb-key", resolve(Res.string.settings_games_steamgriddb_key), resolve(Res.string.settings_games_steamgriddb_key_description), resolve(Res.string.settings_games_section_artwork)),
    ).forEach { row ->
        addRow(
            page = SettingsPage.Games,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = gamesPage,
            section = row.sectionOverride ?: gamesPage,
            icon = Icons.Rounded.SportsEsports,
        )
    }
    addPlaybackRows(
        addRow = ::addRow,
        pageLabel = playbackPage,
        section = playbackPlayer,
        icon = Icons.Rounded.PlayArrow,
        rows = listOfNotNull(
            PlaybackSearchRow(
                "loading-overlay",
                resolve(Res.string.settings_playback_show_loading_overlay),
                resolve(Res.string.settings_playback_show_loading_overlay_description),
            ),
            PlaybackSearchRow(
                "external-player",
                resolve(Res.string.settings_playback_external_player),
                resolve(Res.string.settings_playback_external_player_description_android),
            ),
            if (isIos) PlaybackSearchRow(
                "external-player-app",
                resolve(Res.string.settings_playback_external_player_app),
            ) else null,
            if (isDesktop) PlaybackSearchRow(
                "default-speed",
                resolve(Res.string.settings_playback_default_speed),
                anchor = SettingsScrollAnchor.DefaultSpeed,
            ) else null,
            PlaybackSearchRow(
                "seek-step",
                resolve(Res.string.settings_playback_seek_step),
                resolve(Res.string.settings_playback_seek_step_description),
            ),
            if (isDesktop) PlaybackSearchRow(
                "speed-toggle",
                resolve(Res.string.settings_playback_speed_toggle),
                resolve(Res.string.settings_playback_speed_toggle_description),
                anchor = SettingsScrollAnchor.SpeedToggle,
            ) else null,
            if (isDesktop) PlaybackSearchRow(
                "mouse-move",
                resolve(Res.string.settings_playback_mouse_move_reveals_controls),
                resolve(Res.string.settings_playback_mouse_move_reveals_controls_description),
                anchor = SettingsScrollAnchor.MouseMove,
            ) else null,
            if (isDesktop) PlaybackSearchRow(
                "source-notch",
                resolve(Res.string.settings_playback_source_notch),
                resolve(Res.string.settings_playback_source_notch_description),
                anchor = SettingsScrollAnchor.SourceNotch,
            ) else null,
            if (isDesktop) PlaybackSearchRow(
                "notification-position",
                resolve(Res.string.settings_playback_notification_position),
                resolve(Res.string.settings_playback_notification_position_description),
                anchor = SettingsScrollAnchor.NotificationPosition,
            ) else null,
            if (isDesktop) PlaybackSearchRow(
                "desktop-hdr",
                resolve(Res.string.settings_playback_desktop_hdr_mode),
                anchor = SettingsScrollAnchor.HdrMode,
            ) else null,
            if (isDesktop) PlaybackSearchRow(
                "desktop-color-profile",
                resolve(Res.string.settings_playback_desktop_color_profile),
                anchor = SettingsScrollAnchor.ColorProfile,
            ) else null,
            if (isDesktop) PlaybackSearchRow(
                "desktop-renderer",
                resolve(Res.string.settings_playback_desktop_renderer),
                resolve(Res.string.settings_playback_desktop_renderer_dialog),
                anchor = SettingsScrollAnchor.DesktopRenderer,
            ) else null,
            if (isDesktop) PlaybackSearchRow(
                "desktop-buffer-preset",
                resolve(Res.string.settings_playback_desktop_buffer_preset),
                anchor = SettingsScrollAnchor.BufferPreset,
            ) else null,
            if (isDesktop) PlaybackSearchRow(
                "desktop-anime-mode",
                resolve(Res.string.settings_playback_desktop_anime_mode),
                anchor = SettingsScrollAnchor.AnimeEnhancements,
            ) else null,
            if (isDesktop) PlaybackSearchRow(
                "desktop-anime-auto",
                resolve(Res.string.settings_playback_desktop_anime_auto),
                resolve(Res.string.settings_playback_desktop_anime_auto_desc),
                anchor = SettingsScrollAnchor.AnimeAutoApply,
                fallbackAnchor = SettingsScrollAnchor.AnimeEnhancements,
                fallbackTitle = resolve(Res.string.settings_playback_desktop_anime_mode),
            ) else null,
            if (isDesktop) PlaybackSearchRow(
                "desktop-anime-include-western",
                resolve(Res.string.settings_playback_desktop_anime_include_western),
                resolve(Res.string.settings_playback_desktop_anime_include_western_desc),
                anchor = SettingsScrollAnchor.AnimeIncludeWesternAnimation,
                fallbackAnchor = SettingsScrollAnchor.AnimeAutoApply,
                fallbackTitle = resolve(Res.string.settings_playback_desktop_anime_auto),
            ) else null,
            if (isDesktop) PlaybackSearchRow(
                "desktop-anime-skip-uhd",
                resolve(Res.string.settings_playback_desktop_anime_skip_uhd),
                resolve(Res.string.settings_playback_desktop_anime_skip_uhd_desc),
                anchor = SettingsScrollAnchor.AnimeSkipUltraHd,
                fallbackAnchor = SettingsScrollAnchor.AnimeEnhancements,
                fallbackTitle = resolve(Res.string.settings_playback_desktop_anime_mode),
            ) else null,
            if (isDesktop) PlaybackSearchRow(
                "desktop-anime-svp",
                resolve(Res.string.settings_playback_desktop_anime_svp),
                resolve(Res.string.settings_playback_desktop_anime_svp_desc),
                anchor = SettingsScrollAnchor.AnimeSvp,
                fallbackAnchor = SettingsScrollAnchor.AnimeEnhancements,
                fallbackTitle = resolve(Res.string.settings_playback_desktop_anime_mode),
            ) else null,
            if (isDesktop) PlaybackSearchRow(
                "desktop-anime-svp-overlay",
                resolve(Res.string.settings_playback_desktop_anime_svp_overlay),
                resolve(Res.string.settings_playback_desktop_anime_svp_overlay_desc),
                anchor = SettingsScrollAnchor.AnimeSvpOverlay,
                fallbackAnchor = SettingsScrollAnchor.AnimeSvp,
                fallbackTitle = resolve(Res.string.settings_playback_desktop_anime_svp),
            ) else null,
            if (isDesktop) PlaybackSearchRow(
                "nvidia-rtx-hdr",
                resolve(Res.string.settings_playback_nvidia_rtx_hdr),
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
            PlaybackSearchRow("preferred-audio", resolve(Res.string.settings_playback_preferred_audio_language)),
            PlaybackSearchRow("secondary-audio", resolve(Res.string.settings_playback_secondary_audio_language)),
            PlaybackSearchRow("preferred-subtitles", resolve(Res.string.settings_playback_preferred_subtitle_language)),
            PlaybackSearchRow("secondary-subtitles", resolve(Res.string.settings_playback_secondary_subtitle_language)),
            if (isDesktop) PlaybackSearchRow(
                "dual-subtitles",
                resolve(Res.string.settings_playback_dual_subtitles),
                resolve(Res.string.settings_playback_dual_subtitles_description),
            ) else null,
            PlaybackSearchRow(
                "addon-subtitle-startup",
                resolve(Res.string.settings_playback_addon_subtitle_startup_mode),
                resolve(Res.string.settings_playback_addon_subtitle_startup_fast_description),
            ),
            PlaybackSearchRow(
                "reject-subtitle-keywords",
                resolve(Res.string.settings_playback_reject_subtitle_keywords),
                resolve(Res.string.settings_playback_reject_subtitle_keywords_description),
            ),
            PlaybackSearchRow(
                "reject-audio-keywords",
                resolve(Res.string.settings_playback_reject_audio_keywords),
                resolve(Res.string.settings_playback_reject_audio_keywords_description),
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
                "prefetch-streams",
                resolve(Res.string.settings_playback_prefetch_streams),
                resolve(Res.string.settings_playback_prefetch_streams_description),
            ),
            PlaybackSearchRow(
                "prefetch-cache-duration",
                resolve(Res.string.settings_playback_prefetch_cache_duration),
            ),
            PlaybackSearchRow(
                "prefetch-resolve-links",
                resolve(Res.string.settings_playback_prefetch_resolve_links),
                resolve(Res.string.settings_playback_prefetch_resolve_links_description),
            ),
            PlaybackSearchRow(
                "reuse-last-link",
                resolve(Res.string.settings_playback_reuse_last_link),
                resolve(Res.string.settings_playback_reuse_last_link_description),
            ),
            PlaybackSearchRow("last-link-cache", resolve(Res.string.settings_playback_last_link_cache_duration)),
            PlaybackSearchRow(
                "pause-overlay-source",
                resolve(Res.string.settings_playback_pause_overlay_source),
                resolve(Res.string.settings_playback_pause_overlay_source_description),
            ),
        ),
    )
    addPlaybackRows(
        addRow = ::addRow,
        pageLabel = playbackPage,
        section = playbackStreamAutoPlay,
        icon = Icons.Rounded.PlayArrow,
        rows = buildList {
            add(PlaybackSearchRow("stream-mode", resolve(Res.string.settings_playback_stream_selection_mode)))
            add(PlaybackSearchRow("regex-pattern", resolve(Res.string.settings_playback_regex_pattern)))
            add(PlaybackSearchRow("stream-timeout", resolve(Res.string.settings_playback_stream_timeout), resolve(Res.string.settings_playback_stream_timeout_description)))
            add(PlaybackSearchRow("source-scope", resolve(Res.string.settings_playback_source_scope)))
            add(PlaybackSearchRow("allowed-addons", resolve(Res.string.settings_playback_allowed_addons)))
            if (pluginsEnabled) add(PlaybackSearchRow("allowed-plugins", resolve(Res.string.settings_playback_allowed_plugins)))
        },
    )
    if (!isIos) {
        addPlaybackRows(
            addRow = ::addRow,
            pageLabel = playbackPage,
            section = playbackSubtitleRendering,
            icon = Icons.Rounded.PlayArrow,
            rows = listOf(
                PlaybackSearchRow("libass", resolve(Res.string.settings_playback_enable_libass), resolve(Res.string.settings_playback_enable_libass_description)),
                PlaybackSearchRow("libass-render", resolve(Res.string.settings_playback_render_type)),
                PlaybackSearchRow(
                    "ass-style-mode",
                    resolve(Res.string.settings_subtitle_ass_mode_title),
                    resolve(Res.string.settings_subtitle_ass_mode_subtitle),
                ),
                PlaybackSearchRow(
                    "ass-scale",
                    resolve(Res.string.settings_subtitle_ass_scale_title),
                    resolve(Res.string.settings_subtitle_ass_scale_subtitle),
                ),
            ),
        )
    }
    addPlaybackRows(
        addRow = ::addRow,
        pageLabel = playbackPage,
        section = playbackSkipSegments,
        icon = Icons.Rounded.PlayArrow,
        rows = listOf(
            PlaybackSearchRow("skip-intro", resolve(Res.string.settings_playback_skip_intro_outro_recap), resolve(Res.string.settings_playback_skip_intro_outro_recap_description)),
            PlaybackSearchRow(
                "skip-auto-accept",
                resolve(Res.string.settings_playback_skip_auto_accept),
                resolve(Res.string.settings_playback_skip_auto_accept_chapters_description),
            ),
            PlaybackSearchRow("anime-skip", resolve(Res.string.settings_playback_anime_skip), resolve(Res.string.settings_playback_anime_skip_description)),
            PlaybackSearchRow(
                "anime-skip-client",
                resolve(Res.string.settings_playback_anime_skip_client_id),
                resolve(Res.string.settings_playback_anime_skip_client_id_description),
                fallbackAnchor = SettingsScrollAnchor.searchKey("anime-skip"),
                fallbackTitle = resolve(Res.string.settings_playback_anime_skip),
            ),
            PlaybackSearchRow("intro-submit", resolve(Res.string.settings_playback_intro_submit_enabled), resolve(Res.string.settings_playback_intro_submit_enabled_description)),
            PlaybackSearchRow(
                "introdb-key",
                resolve(Res.string.settings_playback_introdb_api_key),
                resolve(Res.string.settings_playback_introdb_api_key_description),
                fallbackAnchor = SettingsScrollAnchor.searchKey("intro-submit"),
                fallbackTitle = resolve(Res.string.settings_playback_intro_submit_enabled),
            ),
        ),
    )
    addPlaybackRows(
        addRow = ::addRow,
        pageLabel = playbackPage,
        section = playbackNextEpisode,
        icon = Icons.Rounded.PlayArrow,
        rows = listOf(
            PlaybackSearchRow("auto-play-next", resolve(Res.string.settings_playback_auto_play_next_episode), resolve(Res.string.settings_playback_auto_play_next_episode_description), anchor = SettingsScrollAnchor.BingeMode),
            PlaybackSearchRow("prefer-binge", resolve(Res.string.settings_playback_prefer_binge_group), resolve(Res.string.settings_playback_prefer_binge_group_description)),
            PlaybackSearchRow("threshold-mode", resolve(Res.string.settings_playback_threshold_mode)),
            PlaybackSearchRow("threshold-percent", resolve(Res.string.settings_playback_threshold_percentage), resolve(Res.string.settings_playback_threshold_percentage_description)),
            PlaybackSearchRow("threshold-minutes", resolve(Res.string.settings_playback_minutes_before_end), resolve(Res.string.settings_playback_minutes_before_end_description)),
        ),
    )

    addContinueWatchingRows(
        addRow = ::addRow,
        pageLabel = continueWatchingPage,
        section = resolve(Res.string.settings_continue_watching_section_default_action),
        icon = Icons.Rounded.PlayArrow,
        rows = listOf(
            PlaybackSearchRow(
                "click-action",
                resolve(Res.string.settings_continue_watching_click_action_title),
                resolve(Res.string.settings_continue_watching_click_action_description),
            ),
        ),
    )
    addContinueWatchingRows(
        addRow = ::addRow,
        pageLabel = continueWatchingPage,
        section = resolve(Res.string.settings_cw_source_section),
        icon = Icons.Rounded.Style,
        rows = listOf(
            PlaybackSearchRow(
                "source",
                resolve(Res.string.settings_cw_source_title),
                resolve(Res.string.settings_appearance_continue_watching_description),
                anchor = SettingsScrollAnchor.searchKey("continue-watching-source"),
            ),
            PlaybackSearchRow(
                "window",
                resolve(Res.string.settings_cw_window_title),
                resolve(Res.string.settings_cw_window_description),
                anchor = SettingsScrollAnchor.searchKey("continue-watching-window"),
            ),
            PlaybackSearchRow(
                "anime-id-preference",
                resolve(Res.string.settings_anime_id_preference),
                resolve(Res.string.settings_anime_id_preference_description),
                anchor = SettingsScrollAnchor.searchKey("anime-id-preference"),
            ),
        ),
    )
    addContinueWatchingRows(
        addRow = ::addRow,
        pageLabel = continueWatchingPage,
        section = resolve(Res.string.settings_continue_watching_section_up_next_behavior),
        icon = Icons.Rounded.Style,
        rows = listOf(
            PlaybackSearchRow(
                "show-continue-watching",
                resolve(Res.string.settings_continue_watching_show_title),
                resolve(Res.string.settings_continue_watching_show_description),
            ),
            PlaybackSearchRow("episode-thumbnails", resolve(Res.string.settings_continue_watching_use_episode_thumbnails_title), resolve(Res.string.settings_continue_watching_use_episode_thumbnails_description)),
            PlaybackSearchRow("up-next", resolve(Res.string.settings_continue_watching_up_next_title), resolve(Res.string.settings_continue_watching_up_next_description)),
            PlaybackSearchRow("separate-next-up", resolve(Res.string.settings_continue_watching_separate_next_up_title), resolve(Res.string.settings_continue_watching_separate_next_up_description)),
            PlaybackSearchRow("unaired-next-up", resolve(Res.string.settings_continue_watching_show_unaired_next_up_title), resolve(Res.string.settings_continue_watching_show_unaired_next_up_description)),
            PlaybackSearchRow("blur-next-up", resolve(Res.string.settings_continue_watching_blur_next_up_title), resolve(Res.string.settings_continue_watching_blur_next_up_description)),
        ),
    )
    addContinueWatchingRows(
        addRow = ::addRow,
        pageLabel = continueWatchingPage,
        section = resolve(Res.string.settings_continue_watching_section_on_launch),
        icon = Icons.Rounded.Style,
        rows = listOf(
            PlaybackSearchRow("resume-prompt", resolve(Res.string.settings_continue_watching_resume_prompt_title), resolve(Res.string.settings_continue_watching_resume_prompt_description)),
        ),
    )

    val posterSection = resolve(Res.string.settings_poster_card_style)
    listOf(
        PlaybackSearchRow(
            "poster-width",
            resolve(Res.string.settings_poster_card_width),
            "Includes Compact, Dense, Standard, Balanced, Comfort, Large, and Extra Large poster sizes.",
            anchor = SettingsScrollAnchor.ExtraLargePosters,
        ),
        PlaybackSearchRow("poster-radius", resolve(Res.string.settings_poster_card_radius)),
        PlaybackSearchRow(
            "poster-highlight",
            resolve(Res.string.settings_poster_highlight),
            resolve(Res.string.settings_poster_highlight_description),
        ),
        PlaybackSearchRow("poster-landscape", resolve(Res.string.settings_poster_landscape_mode)),
        PlaybackSearchRow(
            "collections-portrait",
            resolve(Res.string.settings_poster_collections_portrait),
            resolve(Res.string.settings_poster_collections_portrait_description),
        ),
        PlaybackSearchRow(
            "landscape-text-titles",
            resolve(Res.string.settings_poster_landscape_text_titles),
            resolve(Res.string.settings_poster_landscape_text_titles_description),
        ),
        PlaybackSearchRow(
            "landscape-rating-badge",
            resolve(Res.string.settings_poster_landscape_rating_badge),
            resolve(Res.string.settings_poster_landscape_rating_badge_description),
        ),
        PlaybackSearchRow("poster-hide-labels", resolve(Res.string.settings_poster_hide_labels)),
        PlaybackSearchRow("action-preview", resolve(Res.string.settings_poster_action_preview), resolve(Res.string.settings_poster_action_preview_description)),
        PlaybackSearchRow("card-depth", resolve(Res.string.settings_poster_card_depth), resolve(Res.string.settings_poster_card_depth_description)),
        PlaybackSearchRow("card-depth-edge", resolve(Res.string.settings_poster_card_depth_edge)),
        PlaybackSearchRow("card-depth-sheen", resolve(Res.string.settings_poster_card_depth_sheen)),
        PlaybackSearchRow("card-depth-edge-coverage", resolve(Res.string.settings_poster_card_depth_edge_coverage)),
    ).forEach { row ->
        addRow(
            page = SettingsPage.PosterCustomization,
            key = "poster-${row.key}",
            title = row.title,
            description = row.description,
            pageLabel = posterCustomizationPage,
            section = posterSection,
            icon = Icons.Rounded.Tune,
            anchor = row.anchor,
        )
    }

    val homeLayoutSection = resolve(Res.string.settings_homescreen_section_hero)
    listOf(
        PlaybackSearchRow("home-hero", resolve(Res.string.settings_homescreen_show_hero), resolve(Res.string.settings_homescreen_show_hero_description)),
        PlaybackSearchRow("home-hero-badge-count", "Hero badge count", "Choose how many hero badges are shown.", anchor = SettingsScrollAnchor.HeroBadgeCount),
        PlaybackSearchRow("home-hero-badge-position", "Hero badge position", "Choose where hero badges appear.", anchor = SettingsScrollAnchor.HeroBadgePosition),
        PlaybackSearchRow("home-hero-badge-size", "Hero badge size", "Scale badges for desktop or TV viewing.", anchor = SettingsScrollAnchor.HeroBadgeSize),
        PlaybackSearchRow("home-hero-badge-priority", "Hero info priority", "Choose which hero badges are preferred first.", anchor = SettingsScrollAnchor.HeroBadgePriority),
        PlaybackSearchRow("home-hero-release-status", "Only show unavailable release status", "Show release status only for cinema and production titles.", anchor = SettingsScrollAnchor.HeroReleaseStatus),
        PlaybackSearchRow("home-hide-unreleased", resolve(Res.string.layout_hide_unreleased), resolve(Res.string.layout_hide_unreleased_sub)),
        PlaybackSearchRow("home-hide-catalog-underline", resolve(Res.string.settings_homescreen_hide_catalog_underline), resolve(Res.string.settings_homescreen_hide_catalog_underline_description)),
        PlaybackSearchRow("home-row-shuffle", resolve(Res.string.settings_homescreen_row_shuffle), resolve(Res.string.settings_homescreen_row_shuffle_description)),
        PlaybackSearchRow("home-display-mode", "Display Mode", "Basic, Adaptive, Adaptive Ambient, or TV Mode.", anchor = SettingsScrollAnchor.DisplayMode),
        PlaybackSearchRow("home-hero-trailer", resolve(Res.string.settings_playback_hero_tv_trailer), resolve(Res.string.settings_playback_hero_tv_trailer_description), anchor = SettingsScrollAnchor.AutoPlayTrailer),
        PlaybackSearchRow("home-hero-trailer-delay", resolve(Res.string.settings_playback_hero_tv_trailer_delay), "Delay before focused hero trailers start playing.", anchor = SettingsScrollAnchor.TrailerDelay),
        PlaybackSearchRow("home-hero-trailer-sound", resolve(Res.string.settings_playback_hero_tv_trailer_sound), resolve(Res.string.settings_playback_hero_tv_trailer_sound_description), anchor = SettingsScrollAnchor.TrailerSound),
        PlaybackSearchRow("home-hero-trailer-search", "Trailers in Search", "Allow focused search results to play hero trailers.", anchor = SettingsScrollAnchor.TrailerSearch),
        PlaybackSearchRow("home-adaptive-hero-position", "Backdrop vertical position", "Manually tune how adaptive hero backdrops crop vertically.", anchor = SettingsScrollAnchor.AdaptiveHeroPosition),
        PlaybackSearchRow("home-adaptive-hero-height", "Hero height", "Set how much of the window the adaptive hero occupies.", anchor = SettingsScrollAnchor.AdaptiveHeroHeight),
        PlaybackSearchRow("home-smooth-scrolling", resolve(Res.string.settings_home_smooth_scrolling), resolve(Res.string.settings_home_smooth_scrolling_description)),
        PlaybackSearchRow("home-hover-preview", resolve(Res.string.settings_home_hover_preview), resolve(Res.string.settings_home_hover_preview_description)),
        PlaybackSearchRow("home-catalog-see-more", resolve(Res.string.settings_home_see_more_arrows), resolve(Res.string.settings_home_see_more_arrows_description)),
        PlaybackSearchRow("home-catalog-row-numbers", "Number catalog rows", "Append each row's position to its name, including collections."),
        PlaybackSearchRow("home-tv-full-backdrop", "Full backdrop", "Extend the TV Mode backdrop to the bottom of the screen and let the rows float over it."),
        PlaybackSearchRow("home-tv-row-dots", "Row jump dots", "Click a dot beside the TV Mode row name to jump straight to that catalog."),
        PlaybackSearchRow("home-tv-row-dots-anchor", "Row jump dot position", "Put the TV Mode jump dots on the row name's line or over the backdrop."),
        PlaybackSearchRow("home-hero-sources", resolve(Res.string.settings_homescreen_section_hero_sources)),
        PlaybackSearchRow("home-catalogs", resolve(Res.string.settings_homescreen_section_catalogs)),
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

    val detailAppearanceSection = resolve(Res.string.settings_meta_section_appearance)
    listOf(
        PlaybackSearchRow("meta-dominant-background", resolve(Res.string.settings_meta_background_title), resolve(Res.string.settings_meta_background_description)),
        PlaybackSearchRow("meta-hero-trailer-playback", resolve(Res.string.settings_meta_hero_trailer_playback), resolve(Res.string.settings_meta_hero_trailer_playback_description)),
        PlaybackSearchRow("meta-hero-trailer-delay", resolve(Res.string.settings_playback_hero_tv_trailer_delay)),
        PlaybackSearchRow("meta-hero-trailer-sound", resolve(Res.string.settings_meta_hero_trailer_sound), resolve(Res.string.settings_meta_hero_trailer_sound_description)),
        PlaybackSearchRow("meta-hero-trailer-background", resolve(Res.string.settings_meta_hero_trailer_background), resolve(Res.string.settings_meta_hero_trailer_background_description)),
        PlaybackSearchRow("meta-discovery-badges", resolve(Res.string.settings_meta_discovery_badges), resolve(Res.string.settings_meta_discovery_badges_description)),
        PlaybackSearchRow("meta-blur-episodes", resolve(Res.string.settings_meta_blur_unwatched_episodes), resolve(Res.string.settings_meta_blur_unwatched_episodes_description)),
        PlaybackSearchRow("meta-episode-ratings", resolve(Res.string.settings_meta_episode_ratings), resolve(Res.string.settings_meta_episode_ratings_description)),
        PlaybackSearchRow("meta-actions", resolve(Res.string.settings_meta_actions), resolve(Res.string.settings_meta_actions_description)),
        PlaybackSearchRow("meta-overview", resolve(Res.string.settings_meta_overview), resolve(Res.string.settings_meta_overview_description)),
        PlaybackSearchRow("meta-production", resolve(Res.string.settings_meta_production), resolve(Res.string.settings_meta_production_description)),
        PlaybackSearchRow("meta-cast", resolve(Res.string.settings_meta_cast), resolve(Res.string.settings_meta_cast_description)),
        PlaybackSearchRow("meta-comments", resolve(Res.string.settings_meta_comments), resolve(Res.string.settings_meta_comments_description)),
        PlaybackSearchRow("meta-trailers", resolve(Res.string.settings_meta_trailers), resolve(Res.string.settings_meta_trailers_description)),
        PlaybackSearchRow("meta-episodes", resolve(Res.string.settings_meta_episodes), resolve(Res.string.settings_meta_episodes_description)),
        PlaybackSearchRow("meta-details", resolve(Res.string.settings_meta_details), resolve(Res.string.settings_meta_details_description)),
        PlaybackSearchRow("meta-collection", resolve(Res.string.settings_meta_collection), resolve(Res.string.settings_meta_collection_description)),
        PlaybackSearchRow("meta-more-like-this", resolve(Res.string.settings_meta_more_like_this), resolve(Res.string.settings_meta_more_like_this_description)),
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
        description = resolve(Res.string.settings_integrations_tmdb_description),
        icon = Icons.Rounded.Link,
    )
    addPage(
        page = SettingsPage.MdbListRatings,
        key = "mdblist",
        title = mdbListPage,
        description = resolve(Res.string.settings_integrations_mdblist_description),
        icon = Icons.Rounded.Link,
    )
    addPage(
        page = SettingsPage.QualiCache,
        key = "qualicache",
        title = qualiCachePage,
        description = resolve(Res.string.settings_integrations_qualicache_description),
        icon = Icons.Rounded.Link,
    )
    addPage(
        page = SettingsPage.Debrid,
        key = "debrid",
        title = debridPage,
        description = resolve(Res.string.settings_integrations_debrid_description),
        icon = Icons.Rounded.CloudDownload,
    )
    val tmdbModulesSection = resolve(Res.string.settings_tmdb_section_modules)
    listOf(
        PlaybackSearchRow("tmdb-enable", resolve(Res.string.settings_tmdb_enable_enrichment), resolve(Res.string.settings_tmdb_enable_enrichment_description), resolve(Res.string.settings_tmdb_section_title)),
        PlaybackSearchRow("tmdb-api-key", resolve(Res.string.settings_tmdb_personal_api_key), "", resolve(Res.string.settings_tmdb_section_credentials)),
        PlaybackSearchRow("tvdb-api-key", resolve(Res.string.settings_licenses_attributions_tvdb_title), resolve(Res.string.settings_licenses_attributions_tvdb_body), resolve(Res.string.settings_tmdb_section_credentials), anchor = SettingsScrollAnchor.TvdbApiKey),
        PlaybackSearchRow("tmdb-hero-images", "Hero backdrop & logo", "Choose addon artwork, TMDB artwork, or TVDB artwork for TV and anime.", "HERO BACKDROP & LOGO", anchor = SettingsScrollAnchor.TmdbHeroImages),
        PlaybackSearchRow("tmdb-language", resolve(Res.string.settings_tmdb_preferred_language), resolve(Res.string.settings_tmdb_preferred_language_description), resolve(Res.string.settings_tmdb_section_localization)),
        PlaybackSearchRow("tmdb-filename-catalogs", "Resolve filenames via TMDB", "Look up catalog rows that arrive as raw release filenames (TorBox, AIOStreams library) by name and year.", "FILENAME-ONLY CATALOGS"),
        PlaybackSearchRow("tmdb-library-posters", resolve(Res.string.settings_tmdb_library_posters_title), resolve(Res.string.settings_tmdb_library_posters_description), resolve(Res.string.settings_tmdb_library_posters_section)),
        PlaybackSearchRow("tmdb-poster-template", resolve(Res.string.settings_tmdb_poster_template), resolve(Res.string.settings_tmdb_poster_template_description), resolve(Res.string.settings_tmdb_library_posters_section)),
        PlaybackSearchRow("tmdb-library-posters-test", resolve(Res.string.settings_tmdb_library_posters_test_title), resolve(Res.string.settings_tmdb_library_posters_test_description), resolve(Res.string.settings_tmdb_library_posters_section)),
        PlaybackSearchRow("tmdb-trailers", resolve(Res.string.settings_tmdb_module_trailers), resolve(Res.string.settings_tmdb_module_trailers_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-artwork", resolve(Res.string.settings_tmdb_module_artwork), resolve(Res.string.settings_tmdb_module_artwork_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-basic-info", resolve(Res.string.settings_tmdb_module_basic_info), resolve(Res.string.settings_tmdb_module_basic_info_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-details", resolve(Res.string.settings_tmdb_module_details), resolve(Res.string.settings_tmdb_module_details_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-credits", resolve(Res.string.settings_tmdb_module_credits), resolve(Res.string.settings_tmdb_module_credits_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-companies", resolve(Res.string.settings_tmdb_module_production_companies), resolve(Res.string.settings_tmdb_module_production_companies_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-networks", resolve(Res.string.settings_tmdb_module_networks), resolve(Res.string.settings_tmdb_module_networks_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-episodes", resolve(Res.string.settings_tmdb_module_episodes), resolve(Res.string.settings_tmdb_module_episodes_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-season-posters", resolve(Res.string.settings_tmdb_module_season_posters), resolve(Res.string.settings_tmdb_module_season_posters_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-more-like-this", resolve(Res.string.settings_tmdb_module_more_like_this), resolve(Res.string.settings_tmdb_module_more_like_this_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-collections", resolve(Res.string.settings_tmdb_module_collections), resolve(Res.string.settings_tmdb_module_collections_description), tmdbModulesSection),
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
        PlaybackSearchRow("mdb-enable", resolve(Res.string.settings_mdb_enable_ratings), resolve(Res.string.settings_mdb_enable_ratings_description), resolve(Res.string.settings_mdb_section_title)),
        PlaybackSearchRow("mdb-api-key", resolve(Res.string.settings_mdb_api_key_title), resolve(Res.string.settings_mdb_api_key_description), resolve(Res.string.settings_mdb_section_api_key)),
        PlaybackSearchRow("mdb-imdb", resolve(Res.string.source_imdb), "", resolve(Res.string.settings_mdb_section_rating_providers)),
        PlaybackSearchRow("mdb-tmdb", resolve(Res.string.source_tmdb), "", resolve(Res.string.settings_mdb_section_rating_providers)),
        PlaybackSearchRow("mdb-tomatoes", resolve(Res.string.source_rotten_tomatoes), "", resolve(Res.string.settings_mdb_section_rating_providers)),
        PlaybackSearchRow("mdb-metacritic", resolve(Res.string.source_metacritic), "", resolve(Res.string.settings_mdb_section_rating_providers)),
        PlaybackSearchRow("mdb-trakt", resolve(Res.string.source_trakt), "", resolve(Res.string.settings_mdb_section_rating_providers)),
        PlaybackSearchRow("mdb-letterboxd", resolve(Res.string.source_letterboxd), "", resolve(Res.string.settings_mdb_section_rating_providers)),
        PlaybackSearchRow("mdb-audience", resolve(Res.string.source_audience_score), "", resolve(Res.string.settings_mdb_section_rating_providers)),
    ).forEach { row ->
        addRow(
            page = SettingsPage.MdbListRatings,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = mdbListPage,
            section = row.sectionOverride ?: resolve(Res.string.settings_mdb_section_title),
            icon = Icons.Rounded.Link,
        )
    }

    listOf(
        PlaybackSearchRow("qualicache-enable", "Show quality badges", "Highlight notable release quality on the Home hero, from your QualiCache server.", "QUALICACHE"),
        PlaybackSearchRow("qualicache-url", "Server address", "Where your QualiCache instance is reachable.", "SERVER"),
        PlaybackSearchRow("qualicache-access-key", "Access key", "Only needed if you set ACCESS_KEY on the server.", "SERVER"),
        PlaybackSearchRow("qualicache-minimum-trust", "Minimum release trust", "Choose which QualiCache release-group tiers may be used.", "SERVER"),
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
        PlaybackSearchRow("debrid-cloud-library", resolve(Res.string.settings_debrid_cloud_library), resolve(Res.string.settings_debrid_cloud_library_description), resolve(Res.string.settings_debrid_section_title)),
        PlaybackSearchRow("debrid-cloud-library-window", resolve(Res.string.settings_debrid_cloud_library_window), resolve(Res.string.settings_debrid_cloud_library_window_description), resolve(Res.string.settings_debrid_section_title)),
        PlaybackSearchRow("debrid-enable", resolve(Res.string.settings_debrid_enable), resolve(Res.string.settings_debrid_enable_description), resolve(Res.string.settings_debrid_section_title)),
        PlaybackSearchRow("debrid-resolve-with", resolve(Res.string.settings_debrid_resolve_with), resolve(Res.string.settings_debrid_resolve_with_description), resolve(Res.string.settings_debrid_section_title)),
        PlaybackSearchRow("debrid-accounts", resolve(Res.string.settings_debrid_section_providers), resolve(Res.string.settings_integrations_debrid_description), resolve(Res.string.settings_debrid_section_providers)),
        PlaybackSearchRow("debrid-prepare", resolve(Res.string.settings_debrid_prepare_instant_playback), resolve(Res.string.settings_debrid_prepare_instant_playback_description), resolve(Res.string.settings_debrid_section_instant_playback)),
        PlaybackSearchRow("debrid-result-limit", resolve(Res.string.settings_debrid_max_results), resolve(Res.string.settings_debrid_max_results_desc), resolve(Res.string.settings_debrid_section_result_management)),
        PlaybackSearchRow("debrid-sort", resolve(Res.string.settings_debrid_sort_results), resolve(Res.string.settings_debrid_sort_results_desc), resolve(Res.string.settings_debrid_section_result_management)),
        PlaybackSearchRow("debrid-size", resolve(Res.string.settings_debrid_size_range), resolve(Res.string.settings_debrid_size_range_desc), resolve(Res.string.settings_debrid_section_result_management)),
        PlaybackSearchRow("debrid-template-name", resolve(Res.string.settings_debrid_name_template), resolve(Res.string.settings_debrid_name_template_description), resolve(Res.string.settings_debrid_section_formatting)),
        PlaybackSearchRow("debrid-template-description", resolve(Res.string.settings_debrid_description_template), resolve(Res.string.settings_debrid_description_template_description), resolve(Res.string.settings_debrid_section_formatting)),
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
        val notificationsAlerts = resolve(Res.string.settings_notifications_section_alerts)
        addRow(
            page = SettingsPage.Notifications,
            key = "episode-release-alerts",
            title = resolve(Res.string.settings_notifications_episode_release_alerts),
            description = resolve(Res.string.settings_notifications_episode_release_alerts_description),
            pageLabel = notificationsPage,
            section = notificationsAlerts,
            icon = Icons.Rounded.Notifications,
        )
        addRow(
            page = SettingsPage.Notifications,
            key = "notification-test",
            title = resolve(Res.string.settings_notifications_test_title),
            pageLabel = notificationsPage,
            section = resolve(Res.string.settings_notifications_section_test),
            icon = Icons.Rounded.Notifications,
        )
    }

    addRow(
        page = SettingsPage.TraktAuthentication,
        key = "trakt-authentication",
        title = resolve(Res.string.settings_trakt_authentication),
        description = resolve(Res.string.settings_trakt_intro_description),
        pageLabel = traktPage,
        section = resolve(Res.string.settings_trakt_authentication),
        category = generalCategory,
        icon = Icons.Rounded.Link,
    )
    listOf(
        PlaybackSearchRow("trakt-watch-progress", resolve(Res.string.trakt_watch_progress_title), resolve(Res.string.trakt_watch_progress_subtitle)),
        PlaybackSearchRow("trakt-comments", resolve(Res.string.settings_trakt_comments), resolve(Res.string.settings_trakt_comments_description)),
        PlaybackSearchRow("trakt-more-like-this-source", resolve(Res.string.trakt_more_like_this_source_title), resolve(Res.string.trakt_more_like_this_source_subtitle)),
    ).forEach { row ->
        addRow(
            page = SettingsPage.TraktAuthentication,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = traktPage,
            section = resolve(Res.string.settings_trakt_features),
            category = generalCategory,
            icon = Icons.Rounded.Link,
        )
    }

    addPage(
        page = SettingsPage.SimklAuthentication,
        key = "simkl",
        title = simklPage,
        description = resolve(Res.string.settings_simkl_description),
        category = generalCategory,
        icon = Icons.Rounded.Link,
    )
    listOf(
        PlaybackSearchRow("simkl-client-id", resolve(Res.string.settings_simkl_client_id), resolve(Res.string.settings_simkl_credentials_description), resolve(Res.string.settings_simkl_section_credentials)),
        PlaybackSearchRow("simkl-connect", resolve(Res.string.settings_simkl_connect), resolve(Res.string.settings_simkl_description), resolve(Res.string.settings_simkl_section_auth)),
        PlaybackSearchRow("simkl-daily-visit", resolve(Res.string.settings_simkl_daily_visit), resolve(Res.string.settings_simkl_daily_visit_desc), resolve(Res.string.settings_simkl_section_daily_visit)),
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

    addPage(
        page = SettingsPage.YamtrackAuthentication,
        key = "yamtrack",
        title = yamtrackPage,
        description = resolve(Res.string.settings_yamtrack_enable_description),
        category = generalCategory,
        icon = Icons.Rounded.Link,
    )
    listOf(
        PlaybackSearchRow("yamtrack-enable", resolve(Res.string.settings_yamtrack_enable), resolve(Res.string.settings_yamtrack_enable_description), yamtrackPage),
        PlaybackSearchRow("yamtrack-connection", resolve(Res.string.settings_yamtrack_section_connection), resolve(Res.string.settings_yamtrack_url_description), resolve(Res.string.settings_yamtrack_section_connection)),
        PlaybackSearchRow("yamtrack-url", resolve(Res.string.settings_yamtrack_url_title), resolve(Res.string.settings_yamtrack_url_description), resolve(Res.string.settings_yamtrack_section_connection), anchor = SettingsScrollAnchor.searchKey("yamtrack-connection")),
        PlaybackSearchRow("yamtrack-token", resolve(Res.string.settings_yamtrack_token_title), resolve(Res.string.settings_yamtrack_token_description), resolve(Res.string.settings_yamtrack_section_connection), anchor = SettingsScrollAnchor.searchKey("yamtrack-connection")),
        PlaybackSearchRow("yamtrack-test", resolve(Res.string.settings_yamtrack_test_connection), sectionOverride = resolve(Res.string.settings_yamtrack_section_connection), anchor = SettingsScrollAnchor.searchKey("yamtrack-connection")),
    ).forEach { row ->
        addRow(
            page = SettingsPage.YamtrackAuthentication,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = yamtrackPage,
            section = row.sectionOverride ?: yamtrackPage,
            category = generalCategory,
            icon = Icons.Rounded.Link,
            anchor = row.anchor,
        )
    }

    // Every page exposed by Settings must remain discoverable. Keep the assertion beside the
    // registry so adding a new SettingsPage without a search entry fails immediately in debug/test
    // builds instead of silently producing another unreachable option.
    val unavailablePages = buildSet {
        add(SettingsPage.Root)
        if (!pluginsEnabled) add(SettingsPage.Plugins)
        if (!downloadsEnabled) {
            add(SettingsPage.AutoDownloads)
            add(SettingsPage.Downloads)
        }
        if (!notificationsEnabled) add(SettingsPage.Notifications)
        if (!isDesktop) add(SettingsPage.KeyboardShortcuts)
    }
    val indexedPages = entries.mapNotNull { (it.target as? SettingsSearchTarget.Page)?.page }.toSet()
    check(SettingsPage.entries.none { it !in unavailablePages && it !in indexedPages }) {
        "Settings search is missing page registrations: " +
            SettingsPage.entries.filter { it !in unavailablePages && it !in indexedPages }
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
            .trackTextInputFocus()
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
