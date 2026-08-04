package com.nuvio.app.features.settings

import com.nuvio.app.core.build.AppFeaturePolicy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.ViewColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.labelRes
import com.nuvio.app.core.ui.LocalNuvioBottomNavigationOverlayPadding
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.isLiquidGlassNativeTabBarSupported
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.platformExitApp
import com.nuvio.app.core.ui.platformOpenLogsDirectory
import com.nuvio.app.core.ui.secondaryClick
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.details.MetaScreenSettingsUiState
import com.nuvio.app.core.ui.PosterCardStyleRepository
import com.nuvio.app.core.ui.PosterCardStyleUiState
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.debrid.DebridSettings
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.discord.DiscordPresenceSettings
import com.nuvio.app.features.discord.DiscordPresenceSettingsRepository
import com.nuvio.app.features.home.HeroBadgePlacement
import com.nuvio.app.features.home.HomeCatalogSettingsItem
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeCatalogSettingsUiState
import com.nuvio.app.features.locallibrary.LocalLibraryRepository
import com.nuvio.app.features.librarypvr.libraryDownloadsSection
import com.nuvio.app.features.mdblist.MdbListSettings
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.qualicache.QualiCacheSettings
import com.nuvio.app.features.qualicache.QualiCacheSettingsRepository
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsRepository
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsUiState
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.profiles.ActiveProfileMiniAvatar
import com.nuvio.app.features.profiles.AvatarCatalogItem
import com.nuvio.app.features.profiles.AvatarRepository
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.simkl.SimklAuthRepository
import com.nuvio.app.features.simkl.SimklAuthUiState
import com.nuvio.app.features.simkl.SimklConnectionMode
import com.nuvio.app.features.simkl.SimklSettingsRepository
import com.nuvio.app.features.simkl.SimklSettingsUiState
import com.nuvio.app.features.yamtrack.YamtrackSettings
import com.nuvio.app.features.yamtrack.YamtrackSettingsRepository
import com.nuvio.app.features.trakt.TraktAuthUiState
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktConnectionMode
import com.nuvio.app.features.trakt.TraktCommentsSettings
import com.nuvio.app.features.trakt.TraktSettingsRepository
import com.nuvio.app.features.trakt.TraktSettingsUiState
import com.nuvio.app.features.tmdb.TmdbSettings
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesUiState
import com.nuvio.app.isDesktop
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.*
import nuvio.composeapp.generated.resources.collections_header
import nuvio.composeapp.generated.resources.compose_about_open_logs_folder
import nuvio.composeapp.generated.resources.compose_nav_home
import nuvio.composeapp.generated.resources.compose_nav_library
import nuvio.composeapp.generated.resources.compose_nav_search
import nuvio.composeapp.generated.resources.compose_settings_page_account
import nuvio.composeapp.generated.resources.compose_settings_page_addons
import nuvio.composeapp.generated.resources.compose_settings_page_advanced
import nuvio.composeapp.generated.resources.compose_settings_page_appearance
import nuvio.composeapp.generated.resources.compose_settings_page_continue_watching
import nuvio.composeapp.generated.resources.compose_settings_page_debrid
import nuvio.composeapp.generated.resources.compose_settings_page_homescreen
import nuvio.composeapp.generated.resources.compose_settings_page_integrations
import nuvio.composeapp.generated.resources.compose_settings_page_keyboard_shortcuts
import nuvio.composeapp.generated.resources.compose_settings_page_licenses_attributions
import nuvio.composeapp.generated.resources.compose_settings_page_mdblist_ratings
import nuvio.composeapp.generated.resources.compose_settings_page_meta_screen
import nuvio.composeapp.generated.resources.compose_settings_page_notifications
import nuvio.composeapp.generated.resources.compose_settings_page_playback
import nuvio.composeapp.generated.resources.compose_settings_page_plugins
import nuvio.composeapp.generated.resources.compose_settings_page_poster_customization
import nuvio.composeapp.generated.resources.compose_settings_page_root
import nuvio.composeapp.generated.resources.compose_settings_page_streams
import nuvio.composeapp.generated.resources.compose_settings_page_local_library
import nuvio.composeapp.generated.resources.compose_settings_page_auto_downloads
import nuvio.composeapp.generated.resources.compose_settings_page_simkl
import nuvio.composeapp.generated.resources.compose_settings_page_tmdb_enrichment
import nuvio.composeapp.generated.resources.compose_settings_page_trakt
import nuvio.composeapp.generated.resources.sidebar_library
import nuvio.composeapp.generated.resources.sidebar_search
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private val SettingsSearchRevealThreshold = 28.dp
private const val SettingsSearchRevealAnimationMillis = 240L
private const val SettingsSearchRevealHapticDelayMillis = 90L
// Placeholder GitHub target for the sidebar footer link — no dedicated Nuvio HTPC repo exists yet.
private const val NuvioHtpcRepoUrl = "https://github.com/UmbraProjects/NuvioDesktop"

private val DesktopSettingsSidebarWidth = 244.dp
// Widened 20% from 775.dp: the denser pages (stream scoring's label + stepper rows in particular)
// were running out of horizontal room for their captions.
private val DesktopSettingsMainColumnWidth = 930.dp
private val DesktopSettingsContextPanelWidth = 300.dp

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    rootActionRequests: Flow<Unit> = emptyFlow(),
    requestedPageName: String? = null,
    onRequestedPageConsumed: () -> Unit = {},
    rootActionsEnabled: Boolean = true,
    onSwitchProfile: (() -> Unit)? = null,
    onHomescreenClick: () -> Unit = {},
    onMetaScreenClick: () -> Unit = {},
    onContinueWatchingClick: () -> Unit = {},
    onAddonsClick: () -> Unit = {},
    onPluginsClick: () -> Unit = {},
    onDownloadsClick: () -> Unit = {},
    onAccountClick: () -> Unit = {},
    onSupportersContributorsClick: () -> Unit = {},
    onLicensesAttributionsClick: () -> Unit = {},
    onCheckForUpdatesClick: (() -> Unit)? = null,
    onShowLatestChangelogClick: (() -> Unit)? = null,
    onOpenCollectionEditor: (String?) -> Unit = {},
    onNavigateToHome: (() -> Unit)? = null,
    onNavigateToSearch: (() -> Unit)? = null,
    onNavigateToLibrary: (() -> Unit)? = null,
) {
    val homeKeyFocusRequester = remember { FocusRequester() }
    var settingsSearchHasFocus by remember { mutableStateOf(false) }
    // Any editable field on the page (e.g. a Local Library catalog name) suppresses the shortcut so
    // typing "H" doesn't jump to Home. The search bar keeps its own flag for the same reason.
    val textInputActive by SettingsTextInputTracker.active.collectAsStateWithLifecycle()
    // H returns to the home tab. onKeyEvent (bubble phase) so settings text fields,
    // which consume their own keystrokes, are never disrupted.
    val homeKeyModifier = if (isDesktop && onNavigateToHome != null) {
        Modifier
            .focusRequester(homeKeyFocusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.H && !settingsSearchHasFocus && !textInputActive) {
                    onNavigateToHome(); true
                } else {
                    false
                }
            }
    } else {
        Modifier
    }
    LaunchedEffect(Unit) {
        if (isDesktop && onNavigateToHome != null) runCatching { homeKeyFocusRequester.requestFocus() }
    }

    BoxWithConstraints(
        modifier = modifier.then(homeKeyModifier).fillMaxSize(),
    ) {
        val playerSettingsUiState by remember {
            PlayerSettingsRepository.ensureLoaded()
            PlayerSettingsRepository.uiState
        }.collectAsStateWithLifecycle()

        val selectedTheme by remember {
            ThemeSettingsRepository.ensureLoaded()
            ThemeSettingsRepository.selectedTheme
        }.collectAsStateWithLifecycle()
        val customTheme by remember { ThemeSettingsRepository.customTheme }.collectAsStateWithLifecycle()
        val amoledEnabled by remember { ThemeSettingsRepository.amoledEnabled }.collectAsStateWithLifecycle()
        val liquidGlassNativeTabBarEnabled by remember {
            ThemeSettingsRepository.liquidGlassNativeTabBarEnabled
        }.collectAsStateWithLifecycle()
        val desktopColumnGuidesVisible by remember {
            ThemeSettingsRepository.desktopColumnGuidesVisible
        }.collectAsStateWithLifecycle()
        val desktopNavigationLayout by remember {
            ThemeSettingsRepository.desktopNavigationLayout
        }.collectAsStateWithLifecycle()
        val desktopAppUiScalePercent by remember {
            ThemeSettingsRepository.desktopAppUiScalePercent
        }.collectAsStateWithLifecycle()
        val desktopAppUiScaleAppliesToDetails by remember {
            ThemeSettingsRepository.desktopAppUiScaleAppliesToDetails
        }.collectAsStateWithLifecycle()
        val liquidGlassNativeTabBarSupported = remember { isLiquidGlassNativeTabBarSupported() }
        val selectedAppLanguage by remember { ThemeSettingsRepository.selectedAppLanguage }.collectAsStateWithLifecycle()
        val tmdbSettings by remember {
            TmdbSettingsRepository.ensureLoaded()
            TmdbSettingsRepository.uiState
        }.collectAsStateWithLifecycle()
        val mdbListSettings by remember {
            MdbListSettingsRepository.ensureLoaded()
            MdbListSettingsRepository.uiState
        }.collectAsStateWithLifecycle()
        val qualiCacheSettings by remember {
            QualiCacheSettingsRepository.ensureLoaded()
            QualiCacheSettingsRepository.uiState
        }.collectAsStateWithLifecycle()
        val debridSettings by remember {
            DebridSettingsRepository.ensureLoaded()
            DebridSettingsRepository.uiState
        }.collectAsStateWithLifecycle()
        val discordPresenceSettings by remember {
            DiscordPresenceSettingsRepository.ensureLoaded()
            DiscordPresenceSettingsRepository.uiState
        }.collectAsStateWithLifecycle()
        val traktAuthUiState by remember {
            TraktAuthRepository.ensureLoaded()
            TraktAuthRepository.uiState
        }.collectAsStateWithLifecycle()
        val simklAuthUiState by remember {
            SimklAuthRepository.ensureLoaded()
            SimklAuthRepository.uiState
        }.collectAsStateWithLifecycle()
        val simklSettingsUiState by remember {
            SimklSettingsRepository.ensureLoaded()
            SimklSettingsRepository.uiState
        }.collectAsStateWithLifecycle()
        val yamtrackSettingsUiState by remember {
            YamtrackSettingsRepository.ensureLoaded()
            YamtrackSettingsRepository.uiState
        }.collectAsStateWithLifecycle()
        val traktCommentsEnabled by remember {
            TraktCommentsSettings.ensureLoaded()
            TraktCommentsSettings.enabled
        }.collectAsStateWithLifecycle()
        val traktSettingsUiState by remember {
            TraktSettingsRepository.ensureLoaded()
            TraktSettingsRepository.uiState
        }.collectAsStateWithLifecycle()
        val addonsUiState by remember {
            AddonRepository.initialize()
            AddonRepository.uiState
        }.collectAsStateWithLifecycle()
        val homescreenCatalogRefreshKey = remember(addonsUiState.addons) {
            val enabledAddons = addonsUiState.addons.enabledAddons()
            val allManifestsSettled = enabledAddons.isNotEmpty() &&
                enabledAddons.none { it.isRefreshing }
            if (!allManifestsSettled) return@remember emptyList<String>()
            enabledAddons.mapNotNull { addon ->
                val manifest = addon.manifest ?: return@mapNotNull null
                buildString {
                    append(manifest.transportUrl)
                    append(':')
                    append(manifest.catalogs.joinToString(separator = ",") { catalog ->
                        "${catalog.type}:${catalog.id}:${catalog.extra.count { it.isRequired }}"
                    })
                }
            }
        }
        val homescreenSettingsUiState by remember {
            HomeCatalogSettingsRepository.snapshot()
            HomeCatalogSettingsRepository.uiState
        }.collectAsStateWithLifecycle()
        val collections by CollectionRepository.collections.collectAsStateWithLifecycle()
        val metaScreenSettingsUiState by remember {
            MetaScreenSettingsRepository.ensureLoaded()
            MetaScreenSettingsRepository.uiState
        }.collectAsStateWithLifecycle()
        val continueWatchingPreferencesUiState by remember {
            ContinueWatchingPreferencesRepository.ensureLoaded()
            ContinueWatchingPreferencesRepository.uiState
        }.collectAsStateWithLifecycle()
        val posterCardStyleUiState by remember {
            PosterCardStyleRepository.ensureLoaded()
            PosterCardStyleRepository.uiState
        }.collectAsStateWithLifecycle()
        val episodeReleaseNotificationsUiState by remember {
            EpisodeReleaseNotificationsRepository.ensureLoaded()
            EpisodeReleaseNotificationsRepository.uiState
        }.collectAsStateWithLifecycle()
        val profileSettingsState by remember {
            ProfileRepository.state
        }.collectAsStateWithLifecycle()
        val profileAvatars by AvatarRepository.avatars.collectAsStateWithLifecycle()

        LaunchedEffect(homescreenCatalogRefreshKey) {
            if (homescreenCatalogRefreshKey.isEmpty()) return@LaunchedEffect
            HomeCatalogSettingsRepository.syncCatalogs(addonsUiState.addons.enabledAddons())
        }

        LaunchedEffect(Unit) {
            CollectionRepository.initialize()
        }

        LaunchedEffect(collections) {
            HomeCatalogSettingsRepository.syncCollections(collections)
        }

        var currentPage by rememberSaveable { mutableStateOf(SettingsPage.Addons.name) }
        val scrollToTopRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
        val page = remember(currentPage) { SettingsPage.valueOf(currentPage) }
        val previousPage = page.desktopBackPage()
        val pendingSettingsAnchor by SettingsScrollAnchor.requested.collectAsStateWithLifecycle()
        val pendingTitleHighlight by SettingsScrollAnchor.titleHighlight.collectAsStateWithLifecycle()

        // A result whose row is absent on this platform must not remain armed indefinitely and
        // unexpectedly scroll some later page. Mounted destination rows normally consume it on
        // their first frame; this only expires unresolved requests.
        LaunchedEffect(pendingSettingsAnchor) {
            val request = pendingSettingsAnchor ?: return@LaunchedEffect
            delay(1_500L)
            SettingsScrollAnchor.expire(request.sequence)
        }
        LaunchedEffect(pendingTitleHighlight) {
            val highlight = pendingTitleHighlight ?: return@LaunchedEffect
            delay(SettingsScrollAnchorHighlightMillis)
            SettingsScrollAnchor.expireTitleHighlight(highlight.sequence)
        }

        LaunchedEffect(page) {
            // Leaving a page drops any text-input shortcut lock, so a field left focused (e.g. a
            // catalog name box) can't keep navigation shortcuts suppressed on the next page.
            SettingsTextInputTracker.reset()
            if (!page.isEnabledByFeaturePolicy()) {
                currentPage = SettingsPage.Addons.name
            }
        }

        LaunchedEffect(rootActionRequests, rootActionsEnabled, page) {
            rootActionRequests.collect {
                if (!rootActionsEnabled) return@collect
                val pageToOpen = page.desktopBackPage()
                if (pageToOpen != null) {
                    currentPage = pageToOpen.name
                } else {
                    scrollToTopRequests.tryEmit(Unit)
                }
            }
        }

        LaunchedEffect(requestedPageName, rootActionsEnabled) {
            val targetPage = requestedPageName
                ?.let { runCatching { SettingsPage.valueOf(it) }.getOrNull() }
                ?: return@LaunchedEffect
            if (!rootActionsEnabled) return@LaunchedEffect
            if (targetPage.isEnabledByFeaturePolicy()) {
                currentPage = targetPage.name
            }
            onRequestedPageConsumed()
        }

        PlatformBackHandler(
            enabled = rootActionsEnabled && previousPage != null,
            onBack = {
                val dest = SettingsScrollAnchor.consumeBackTo() ?: previousPage
                dest?.let { currentPage = it.name }
            },
        )

        if (maxWidth >= 768.dp) {
            TabletSettingsScreen(
                page = page,
                scrollToTopRequests = scrollToTopRequests,
                onPageChange = { currentPage = it.name },
                showContextPanel = maxWidth >= 1180.dp,
                showLoadingOverlay = playerSettingsUiState.showLoadingOverlay,
                defaultPlaybackSpeed = playerSettingsUiState.defaultPlaybackSpeed,
                preferredAudioLanguage = playerSettingsUiState.preferredAudioLanguage,
                secondaryPreferredAudioLanguage = playerSettingsUiState.secondaryPreferredAudioLanguage,
                preferredSubtitleLanguage = playerSettingsUiState.preferredSubtitleLanguage,
                secondaryPreferredSubtitleLanguage = playerSettingsUiState.secondaryPreferredSubtitleLanguage,
                streamReuseLastLinkEnabled = playerSettingsUiState.streamReuseLastLinkEnabled,
                streamReuseLastLinkCacheHours = playerSettingsUiState.streamReuseLastLinkCacheHours,
                decoderPriority = playerSettingsUiState.decoderPriority,
                mapDV7ToHevc = playerSettingsUiState.mapDV7ToHevc,
                tunnelingEnabled = playerSettingsUiState.tunnelingEnabled,
                useLibass = playerSettingsUiState.useLibass,
                libassRenderType = playerSettingsUiState.libassRenderType,
                rememberLastProfileEnabled = profileSettingsState.rememberLastProfileEnabled,
                selectedTheme = selectedTheme,
                onThemeSelected = ThemeSettingsRepository::setTheme,
                customTheme = customTheme,
                amoledEnabled = amoledEnabled,
                onAmoledToggle = ThemeSettingsRepository::setAmoled,
                liquidGlassNativeTabBarSupported = liquidGlassNativeTabBarSupported,
                liquidGlassNativeTabBarEnabled = liquidGlassNativeTabBarEnabled,
                onLiquidGlassNativeTabBarToggle = ThemeSettingsRepository::setLiquidGlassNativeTabBar,
                desktopNavigationLayout = desktopNavigationLayout,
                onDesktopNavigationLayoutSelected = ThemeSettingsRepository::setDesktopNavigationLayout,
                desktopAppUiScalePercent = desktopAppUiScalePercent,
                onDesktopAppUiScalePercentChange = ThemeSettingsRepository::setDesktopAppUiScalePercent,
                desktopAppUiScaleAppliesToDetails = desktopAppUiScaleAppliesToDetails,
                onDesktopAppUiScaleAppliesToDetailsChange = ThemeSettingsRepository::setDesktopAppUiScaleAppliesToDetails,
                desktopColumnGuidesVisible = desktopColumnGuidesVisible,
                onDesktopColumnGuidesVisibleChange = ThemeSettingsRepository::setDesktopColumnGuidesVisible,
                selectedAppLanguage = selectedAppLanguage,
                onAppLanguageSelected = ThemeSettingsRepository::setAppLanguage,
                episodeReleaseNotificationsUiState = episodeReleaseNotificationsUiState,
                tmdbSettings = tmdbSettings,
                mdbListSettings = mdbListSettings,
                qualiCacheSettings = qualiCacheSettings,
                debridSettings = debridSettings,
                discordPresenceSettings = discordPresenceSettings,
                traktAuthUiState = traktAuthUiState,
                traktCommentsEnabled = traktCommentsEnabled,
                traktSettingsUiState = traktSettingsUiState,
                simklAuthUiState = simklAuthUiState,
                simklSettingsUiState = simklSettingsUiState,
                yamtrackSettingsUiState = yamtrackSettingsUiState,
                homescreenHeroEnabled = homescreenSettingsUiState.heroEnabled,
                homescreenHeroInfoLines = homescreenSettingsUiState.heroInfoLines,
                homescreenHeroInfoPriority = homescreenSettingsUiState.heroInfoPriority,
                homescreenHeroBadgePlacement = homescreenSettingsUiState.heroBadgePlacement,
                homescreenHeroBadgeScale = homescreenSettingsUiState.heroBadgeScale,
                homescreenHeroReleaseStatusUnavailableOnly = homescreenSettingsUiState.heroReleaseStatusUnavailableOnly,
                homescreenHideUnreleasedContent = homescreenSettingsUiState.hideUnreleasedContent,
                homescreenHideCatalogUnderline = homescreenSettingsUiState.hideCatalogUnderline,
                homescreenAdaptiveHeroEnabled = homescreenSettingsUiState.adaptiveHeroEnabled,
                homescreenAdaptiveHeroVerticalBias = homescreenSettingsUiState.adaptiveHeroVerticalBias,
                homescreenHeroAmbientBackgroundEnabled = homescreenSettingsUiState.heroAmbientBackgroundEnabled,
                homescreenTvModeEnabled = homescreenSettingsUiState.tvModeEnabled,
                homescreenItems = homescreenSettingsUiState.items,
                randomPlaySettingsUiState = homescreenSettingsUiState,
                metaScreenSettingsUiState = metaScreenSettingsUiState,
                continueWatchingPreferencesUiState = continueWatchingPreferencesUiState,
                posterCardStyleUiState = posterCardStyleUiState,
                profileAvatars = profileAvatars,
                onSwitchProfile = onSwitchProfile,
                onDownloadsClick = onDownloadsClick,
                onSupportersContributorsClick = onSupportersContributorsClick,
                onLicensesAttributionsClick = onLicensesAttributionsClick,
                onCheckForUpdatesClick = onCheckForUpdatesClick,
                onShowLatestChangelogClick = onShowLatestChangelogClick,
                onOpenCollectionEditor = onOpenCollectionEditor,
                onNavigateToHome = onNavigateToHome,
                onNavigateToSearch = onNavigateToSearch,
                onNavigateToLibrary = onNavigateToLibrary,
                onSettingsSearchFocusChange = { settingsSearchHasFocus = it },
            )
        } else {
            MobileSettingsScreen(
                page = page,
                scrollToTopRequests = scrollToTopRequests,
                onPageChange = { currentPage = it.name },
                showLoadingOverlay = playerSettingsUiState.showLoadingOverlay,
                defaultPlaybackSpeed = playerSettingsUiState.defaultPlaybackSpeed,
                preferredAudioLanguage = playerSettingsUiState.preferredAudioLanguage,
                secondaryPreferredAudioLanguage = playerSettingsUiState.secondaryPreferredAudioLanguage,
                preferredSubtitleLanguage = playerSettingsUiState.preferredSubtitleLanguage,
                secondaryPreferredSubtitleLanguage = playerSettingsUiState.secondaryPreferredSubtitleLanguage,
                streamReuseLastLinkEnabled = playerSettingsUiState.streamReuseLastLinkEnabled,
                streamReuseLastLinkCacheHours = playerSettingsUiState.streamReuseLastLinkCacheHours,
                decoderPriority = playerSettingsUiState.decoderPriority,
                mapDV7ToHevc = playerSettingsUiState.mapDV7ToHevc,
                tunnelingEnabled = playerSettingsUiState.tunnelingEnabled,
                useLibass = playerSettingsUiState.useLibass,
                libassRenderType = playerSettingsUiState.libassRenderType,
                rememberLastProfileEnabled = profileSettingsState.rememberLastProfileEnabled,
                selectedTheme = selectedTheme,
                onThemeSelected = ThemeSettingsRepository::setTheme,
                customTheme = customTheme,
                amoledEnabled = amoledEnabled,
                onAmoledToggle = ThemeSettingsRepository::setAmoled,
                liquidGlassNativeTabBarSupported = liquidGlassNativeTabBarSupported,
                liquidGlassNativeTabBarEnabled = liquidGlassNativeTabBarEnabled,
                onLiquidGlassNativeTabBarToggle = ThemeSettingsRepository::setLiquidGlassNativeTabBar,
                desktopNavigationLayout = desktopNavigationLayout,
                onDesktopNavigationLayoutSelected = ThemeSettingsRepository::setDesktopNavigationLayout,
                desktopAppUiScalePercent = desktopAppUiScalePercent,
                onDesktopAppUiScalePercentChange = ThemeSettingsRepository::setDesktopAppUiScalePercent,
                desktopAppUiScaleAppliesToDetails = desktopAppUiScaleAppliesToDetails,
                onDesktopAppUiScaleAppliesToDetailsChange = ThemeSettingsRepository::setDesktopAppUiScaleAppliesToDetails,
                selectedAppLanguage = selectedAppLanguage,
                onAppLanguageSelected = ThemeSettingsRepository::setAppLanguage,
                episodeReleaseNotificationsUiState = episodeReleaseNotificationsUiState,
                tmdbSettings = tmdbSettings,
                mdbListSettings = mdbListSettings,
                qualiCacheSettings = qualiCacheSettings,
                debridSettings = debridSettings,
                discordPresenceSettings = discordPresenceSettings,
                traktAuthUiState = traktAuthUiState,
                traktCommentsEnabled = traktCommentsEnabled,
                traktSettingsUiState = traktSettingsUiState,
                simklAuthUiState = simklAuthUiState,
                simklSettingsUiState = simklSettingsUiState,
                yamtrackSettingsUiState = yamtrackSettingsUiState,
                homescreenHeroEnabled = homescreenSettingsUiState.heroEnabled,
                homescreenHeroInfoLines = homescreenSettingsUiState.heroInfoLines,
                homescreenHeroInfoPriority = homescreenSettingsUiState.heroInfoPriority,
                homescreenHeroBadgePlacement = homescreenSettingsUiState.heroBadgePlacement,
                homescreenHeroBadgeScale = homescreenSettingsUiState.heroBadgeScale,
                homescreenHeroReleaseStatusUnavailableOnly = homescreenSettingsUiState.heroReleaseStatusUnavailableOnly,
                homescreenHideUnreleasedContent = homescreenSettingsUiState.hideUnreleasedContent,
                homescreenHideCatalogUnderline = homescreenSettingsUiState.hideCatalogUnderline,
                homescreenAdaptiveHeroEnabled = homescreenSettingsUiState.adaptiveHeroEnabled,
                homescreenAdaptiveHeroVerticalBias = homescreenSettingsUiState.adaptiveHeroVerticalBias,
                homescreenHeroAmbientBackgroundEnabled = homescreenSettingsUiState.heroAmbientBackgroundEnabled,
                homescreenTvModeEnabled = homescreenSettingsUiState.tvModeEnabled,
                homescreenItems = homescreenSettingsUiState.items,
                randomPlaySettingsUiState = homescreenSettingsUiState,
                metaScreenSettingsUiState = metaScreenSettingsUiState,
                continueWatchingPreferencesUiState = continueWatchingPreferencesUiState,
                posterCardStyleUiState = posterCardStyleUiState,
                onSwitchProfile = onSwitchProfile,
                onHomescreenClick = onHomescreenClick,
                onMetaScreenClick = onMetaScreenClick,
                onContinueWatchingClick = onContinueWatchingClick,
                onAddonsClick = onAddonsClick,
                onPluginsClick = onPluginsClick,
                onDownloadsClick = onDownloadsClick,
                onAccountClick = onAccountClick,
                onSupportersContributorsClick = onSupportersContributorsClick,
                onLicensesAttributionsClick = onLicensesAttributionsClick,
                onCheckForUpdatesClick = onCheckForUpdatesClick,
                onShowLatestChangelogClick = onShowLatestChangelogClick,
                onOpenCollectionEditor = onOpenCollectionEditor,
                onSettingsSearchFocusChange = { settingsSearchHasFocus = it },
            )
        }
    }
}

@Composable
private fun MobileSettingsScreen(
    page: SettingsPage,
    scrollToTopRequests: Flow<Unit>,
    onPageChange: (SettingsPage) -> Unit,
    showLoadingOverlay: Boolean,
    defaultPlaybackSpeed: Float,
    preferredAudioLanguage: String,
    secondaryPreferredAudioLanguage: String?,
    preferredSubtitleLanguage: String,
    secondaryPreferredSubtitleLanguage: String?,
    streamReuseLastLinkEnabled: Boolean,
    streamReuseLastLinkCacheHours: Int,
    decoderPriority: Int,
    mapDV7ToHevc: Boolean,
    tunnelingEnabled: Boolean,
    useLibass: Boolean,
    libassRenderType: String,
    rememberLastProfileEnabled: Boolean,
    selectedTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit,
    customTheme: CustomThemeSettings,
    amoledEnabled: Boolean,
    onAmoledToggle: (Boolean) -> Unit,
    liquidGlassNativeTabBarSupported: Boolean,
    liquidGlassNativeTabBarEnabled: Boolean,
    onLiquidGlassNativeTabBarToggle: (Boolean) -> Unit,
    desktopNavigationLayout: DesktopNavigationLayout,
    onDesktopNavigationLayoutSelected: (DesktopNavigationLayout) -> Unit,
    desktopAppUiScalePercent: Int,
    onDesktopAppUiScalePercentChange: (Int) -> Unit,
    desktopAppUiScaleAppliesToDetails: Boolean,
    onDesktopAppUiScaleAppliesToDetailsChange: (Boolean) -> Unit,
    selectedAppLanguage: AppLanguage,
    onAppLanguageSelected: (AppLanguage) -> Unit,
    episodeReleaseNotificationsUiState: EpisodeReleaseNotificationsUiState,
    tmdbSettings: TmdbSettings,
    mdbListSettings: MdbListSettings,
    qualiCacheSettings: QualiCacheSettings,
    debridSettings: DebridSettings,
    discordPresenceSettings: DiscordPresenceSettings,
    traktAuthUiState: TraktAuthUiState,
    traktCommentsEnabled: Boolean,
    traktSettingsUiState: TraktSettingsUiState,
    simklAuthUiState: SimklAuthUiState,
    simklSettingsUiState: SimklSettingsUiState,
    yamtrackSettingsUiState: YamtrackSettings,
    homescreenHeroEnabled: Boolean,
    homescreenHeroInfoLines: Int,
    homescreenHeroInfoPriority: String,
    homescreenHeroBadgePlacement: HeroBadgePlacement,
    homescreenHeroBadgeScale: Float,
    homescreenHeroReleaseStatusUnavailableOnly: Boolean,
    homescreenHideUnreleasedContent: Boolean,
    homescreenHideCatalogUnderline: Boolean,
    homescreenAdaptiveHeroEnabled: Boolean,
    homescreenAdaptiveHeroVerticalBias: Float,
    homescreenHeroAmbientBackgroundEnabled: Boolean,
    homescreenTvModeEnabled: Boolean,
    homescreenItems: List<HomeCatalogSettingsItem>,
    randomPlaySettingsUiState: HomeCatalogSettingsUiState,
    metaScreenSettingsUiState: MetaScreenSettingsUiState,
    continueWatchingPreferencesUiState: ContinueWatchingPreferencesUiState,
    posterCardStyleUiState: PosterCardStyleUiState,
    onSwitchProfile: (() -> Unit)? = null,
    onHomescreenClick: () -> Unit = {},
    onMetaScreenClick: () -> Unit = {},
    onContinueWatchingClick: () -> Unit = {},
    onAddonsClick: () -> Unit = {},
    onPluginsClick: () -> Unit = {},
    onDownloadsClick: () -> Unit = {},
    onAccountClick: () -> Unit = {},
    onSupportersContributorsClick: () -> Unit = {},
    onLicensesAttributionsClick: () -> Unit = {},
    onCheckForUpdatesClick: (() -> Unit)? = null,
    onShowLatestChangelogClick: (() -> Unit)? = null,
    onOpenCollectionEditor: (String?) -> Unit = {},
    onNavigateToHome: (() -> Unit)? = null,
    onNavigateToSearch: (() -> Unit)? = null,
    onNavigateToLibrary: (() -> Unit)? = null,
    onSettingsSearchFocusChange: (Boolean) -> Unit = {},
) {
    val saveableStateHolder = rememberSaveableStateHolder()
    // Search belongs to the settings screen, not to an individual destination. Keeping this
    // outside the per-page SaveableStateProvider makes the top-bar search one global index.
    var settingsSearchQuery by rememberSaveable { mutableStateOf("") }
    saveableStateHolder.SaveableStateProvider(page.name) {
        val localLibraryUiState by LocalLibraryRepository.uiState.collectAsStateWithLifecycle()
        val localLibraryTitlesState = rememberLocalLibraryTitlesState()
        var rootSearchVisible by rememberSaveable { mutableStateOf(isDesktop) }
        var rootSearchRevealAnimating by rememberSaveable { mutableStateOf(false) }
        val listState = rememberLazyListState()
        val hapticFeedback = LocalHapticFeedback.current
        val hapticScope = rememberCoroutineScope()
        val rootSearchRevealConnection = rememberSettingsRootSearchRevealConnection(
            page = page,
            listState = listState,
            query = settingsSearchQuery,
            searchVisible = rootSearchVisible,
        ) {
            rootSearchVisible = true
            rootSearchRevealAnimating = true
            hapticScope.launch {
                delay(SettingsSearchRevealHapticDelayMillis)
                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
        val searchEntries = settingsSearchEntries(
            pluginsEnabled = AppFeaturePolicy.pluginsEnabled,
            downloadsEnabled = AppFeaturePolicy.downloadsEnabled,
            notificationsEnabled = AppFeaturePolicy.notificationsEnabled,
            liquidGlassNativeTabBarSupported = liquidGlassNativeTabBarSupported,
            switchProfileAvailable = onSwitchProfile != null,
            checkForUpdatesAvailable = onCheckForUpdatesClick != null,
        )

        fun openSearchTarget(target: SettingsSearchTarget) {
            // Remove the results layer before mounting the destination. Otherwise it can keep
            // covering the page whose row is waiting to consume the requested scroll anchor.
            settingsSearchQuery = ""
            when (target) {
                is SettingsSearchTarget.Page -> {
                    target.anchor?.let { anchor ->
                        SettingsScrollAnchor.request(
                            anchor = anchor,
                            fallbackAnchor = target.fallbackAnchor,
                            fallbackTitle = target.fallbackTitle,
                        )
                    }
                    when (target.page) {
                        SettingsPage.Account -> onAccountClick()
                        SettingsPage.SupportersContributors -> onSupportersContributorsClick()
                        SettingsPage.LicensesAttributions -> onLicensesAttributionsClick()
                        SettingsPage.ContinueWatching -> onContinueWatchingClick()
                        SettingsPage.Addons -> onAddonsClick()
                        SettingsPage.Plugins -> {
                            if (AppFeaturePolicy.pluginsEnabled) {
                                onPluginsClick()
                            }
                        }
                        SettingsPage.Homescreen -> onHomescreenClick()
                        SettingsPage.MetaScreen -> onMetaScreenClick()
                        else -> onPageChange(target.page)
                    }
                }
                SettingsSearchTarget.Downloads -> {
                    if (AppFeaturePolicy.downloadsEnabled) {
                        onDownloadsClick()
                    }
                }
                SettingsSearchTarget.SwitchProfile -> onSwitchProfile?.invoke()
                SettingsSearchTarget.CheckForUpdates -> onCheckForUpdatesClick?.invoke()
            }
        }

        LaunchedEffect(rootSearchRevealAnimating) {
            if (rootSearchRevealAnimating) {
                delay(SettingsSearchRevealAnimationMillis)
                rootSearchRevealAnimating = false
            }
        }

        LaunchedEffect(scrollToTopRequests) {
            scrollToTopRequests.collect {
                listState.animateScrollToItem(0)
            }
        }

        NuvioScreen(
            modifier = Modifier.nestedScroll(rootSearchRevealConnection),
            listState = listState,
        ) {
            stickyHeader {
                val previousPage = page.desktopBackPage()
                NuvioScreenHeader(
                    title = stringResource(page.titleRes),
                    onBack = previousPage?.let { default ->
                        {
                            val dest = SettingsScrollAnchor.consumeBackTo() ?: default
                            onPageChange(dest)
                        }
                    },
                )
            }

            when (page) {
                SettingsPage.Root -> {
                    settingsSearchRootContent(
                        query = settingsSearchQuery,
                        entries = searchEntries,
                        isTablet = false,
                        showSearchField = rootSearchVisible,
                        animateSearchField = rootSearchRevealAnimating,
                        onQueryChange = { settingsSearchQuery = it },
                        onSearchFocusChange = onSettingsSearchFocusChange,
                        onTargetClick = { openSearchTarget(it) },
                    )
                    if (settingsSearchQuery.isBlank()) {
                        settingsRootContent(
                            isTablet = false,
                            onPlaybackClick = { onPageChange(SettingsPage.Playback) },
                            onRandomPlayClick = { onPageChange(SettingsPage.RandomPlay) },
                            onStreamsClick = { onPageChange(SettingsPage.Streams) },
                            onLocalLibraryClick = { onPageChange(SettingsPage.LocalLibrary) },
                            onAutoDownloadsClick = { onPageChange(SettingsPage.AutoDownloads) },
                            onAppearanceClick = { onPageChange(SettingsPage.Appearance) },
                            onAdvancedClick = { onPageChange(SettingsPage.Advanced) },
                            onNotificationsClick = { onPageChange(SettingsPage.Notifications) },
                            onContinueWatchingClick = { onPageChange(SettingsPage.ContinueWatching) },
                            onAddonsClick = { onPageChange(SettingsPage.Addons) },
                            onPluginsClick = { onPageChange(SettingsPage.Plugins) },
                            onHomescreenClick = { onPageChange(SettingsPage.Homescreen) },
                            onMetaScreenClick = { onPageChange(SettingsPage.MetaScreen) },
                            onCollectionsClick = { onPageChange(SettingsPage.Collections) },
                            onIntegrationsClick = { onPageChange(SettingsPage.Integrations) },
                            onTraktClick = { onPageChange(SettingsPage.TraktAuthentication) },
                            onSimklClick = { onPageChange(SettingsPage.SimklAuthentication) },
                            onYamtrackClick = { onPageChange(SettingsPage.YamtrackAuthentication) },
                            onSupportersContributorsClick = onSupportersContributorsClick,
                            onLicensesAttributionsClick = onLicensesAttributionsClick,
                            onCheckForUpdatesClick = onCheckForUpdatesClick,
                            onDownloadsClick = onDownloadsClick,
                            onAccountClick = onAccountClick,
                            onSwitchProfileClick = onSwitchProfile,
                            showDownloadsEntry = AppFeaturePolicy.downloadsEnabled,
                            showNotificationsEntry = AppFeaturePolicy.notificationsEnabled,
                            showPluginsEntry = AppFeaturePolicy.pluginsEnabled,
                        )
                    }
                }
                SettingsPage.Account -> accountSettingsContent(
                    isTablet = false,
                    rememberLastProfileEnabled = rememberLastProfileEnabled,
                )
                SettingsPage.SupportersContributors -> supportersContributorsContent(
                    isTablet = false,
                )
                SettingsPage.LicensesAttributions -> licensesAttributionsContent(
                    isTablet = false,
                )
                SettingsPage.Playback -> playbackSettingsContent(
                    isTablet = false,
                    showLoadingOverlay = showLoadingOverlay,
                    defaultPlaybackSpeed = defaultPlaybackSpeed,
                    preferredAudioLanguage = preferredAudioLanguage,
                    secondaryPreferredAudioLanguage = secondaryPreferredAudioLanguage,
                    preferredSubtitleLanguage = preferredSubtitleLanguage,
                    secondaryPreferredSubtitleLanguage = secondaryPreferredSubtitleLanguage,
                    streamReuseLastLinkEnabled = streamReuseLastLinkEnabled,
                    streamReuseLastLinkCacheHours = streamReuseLastLinkCacheHours,
                    decoderPriority = decoderPriority,
                    mapDV7ToHevc = mapDV7ToHevc,
                    tunnelingEnabled = tunnelingEnabled,
                    useLibass = useLibass,
                    libassRenderType = libassRenderType,
                )
                SettingsPage.RandomPlay -> randomPlaySettingsContent(
                    isTablet = false,
                    settings = randomPlaySettingsUiState,
                )
                SettingsPage.Streams -> streamsSettingsContent(
                    isTablet = false,
                    onOpenStreamScoring = { onPageChange(SettingsPage.StreamScoring) },
                )
                SettingsPage.LocalLibrary -> localLibraryContent(
                    isTablet = false,
                    state = localLibraryUiState,
                    titlesState = localLibraryTitlesState,
                )
                SettingsPage.AutoDownloads -> libraryDownloadsSection(
                    isTablet = false,
                    onDownloadsClick = onDownloadsClick,
                )
                SettingsPage.StreamScoring -> streamScoringSection(isTablet = false)
                SettingsPage.KeyboardShortcuts -> keyboardShortcutsContent(
                    isTablet = false,
                )
                SettingsPage.Appearance -> appearanceSettingsContent(
                    isTablet = false,
                    selectedTheme = selectedTheme,
                    onThemeSelected = onThemeSelected,
                    customTheme = customTheme,
                    amoledEnabled = amoledEnabled,
                    onAmoledToggle = onAmoledToggle,
                    liquidGlassNativeTabBarSupported = liquidGlassNativeTabBarSupported,
                    liquidGlassNativeTabBarEnabled = liquidGlassNativeTabBarEnabled,
                    onLiquidGlassNativeTabBarToggle = onLiquidGlassNativeTabBarToggle,
                    desktopNavigationLayout = desktopNavigationLayout,
                    onDesktopNavigationLayoutSelected = onDesktopNavigationLayoutSelected,
                    desktopAppUiScalePercent = desktopAppUiScalePercent,
                    onDesktopAppUiScalePercentChange = onDesktopAppUiScalePercentChange,
                    desktopAppUiScaleAppliesToDetails = desktopAppUiScaleAppliesToDetails,
                    onDesktopAppUiScaleAppliesToDetailsChange = onDesktopAppUiScaleAppliesToDetailsChange,
                    selectedAppLanguage = selectedAppLanguage,
                    onAppLanguageSelected = onAppLanguageSelected,
                    posterCardStyleUiState = posterCardStyleUiState,
                )
                SettingsPage.Advanced -> advancedSettingsContent(
                    isTablet = false,
                    rememberLastProfileEnabled = rememberLastProfileEnabled,
                )
                SettingsPage.Notifications -> if (AppFeaturePolicy.notificationsEnabled) {
                    notificationsSettingsContent(
                        isTablet = false,
                        uiState = episodeReleaseNotificationsUiState,
                    )
                }
                SettingsPage.ContinueWatching -> continueWatchingSettingsContent(
                    isTablet = false,
                    isVisible = continueWatchingPreferencesUiState.isVisible,
                    style = continueWatchingPreferencesUiState.style,
                    upNextFromFurthestEpisode = continueWatchingPreferencesUiState.upNextFromFurthestEpisode,
                    useEpisodeThumbnails = continueWatchingPreferencesUiState.useEpisodeThumbnails,
                    showUnairedNextUp = continueWatchingPreferencesUiState.showUnairedNextUp,
                    blurNextUp = continueWatchingPreferencesUiState.blurNextUp,
                    showResumePromptOnLaunch = continueWatchingPreferencesUiState.showResumePromptOnLaunch,
                    sortMode = continueWatchingPreferencesUiState.sortMode,
                )
                SettingsPage.PosterCustomization -> posterCustomizationSettingsContent(
                    isTablet = false,
                    uiState = posterCardStyleUiState,
                )
                SettingsPage.ContentDiscovery -> contentDiscoveryContent(
                    isTablet = false,
                    showPluginsEntry = AppFeaturePolicy.pluginsEnabled,
                    showDownloadsEntry = AppFeaturePolicy.downloadsEnabled,
                    onAddonsClick = onAddonsClick,
                    onPluginsClick = onPluginsClick,
                    onHomescreenClick = onHomescreenClick,
                    onMetaScreenClick = onMetaScreenClick,
                    onCollectionsClick = { onPageChange(SettingsPage.Collections) },
                    onDownloadsClick = onDownloadsClick,
                )
                SettingsPage.Collections -> collectionsSettingsContent(
                    isTablet = false,
                    onNavigateToEditor = onOpenCollectionEditor,
                )
                SettingsPage.Addons -> addonsSettingsContent()
                SettingsPage.Plugins -> if (AppFeaturePolicy.pluginsEnabled) pluginsSettingsContent() else addonsSettingsContent()
                SettingsPage.Homescreen -> homescreenSettingsContent(
                    isTablet = false,
                    heroEnabled = homescreenHeroEnabled,
                    heroInfoLines = homescreenHeroInfoLines,
                    heroInfoPriority = homescreenHeroInfoPriority,
                    heroBadgePlacement = homescreenHeroBadgePlacement,
                    heroBadgeScale = homescreenHeroBadgeScale,
                    heroReleaseStatusUnavailableOnly = homescreenHeroReleaseStatusUnavailableOnly,
                    hideUnreleasedContent = homescreenHideUnreleasedContent,
                    hideCatalogUnderline = homescreenHideCatalogUnderline,
                    adaptiveHeroEnabled = homescreenAdaptiveHeroEnabled,
                    adaptiveHeroVerticalBias = homescreenAdaptiveHeroVerticalBias,
                    heroAmbientBackgroundEnabled = homescreenHeroAmbientBackgroundEnabled,
                    tvModeEnabled = homescreenTvModeEnabled,
                    items = homescreenItems,
                )
                SettingsPage.MetaScreen -> metaScreenSettingsContent(
                    isTablet = false,
                    uiState = metaScreenSettingsUiState,
                )
                SettingsPage.Integrations -> integrationsContent(
                    isTablet = false,
                    discordPresenceSettings = discordPresenceSettings,
                    onDiscordPresenceModeChange = DiscordPresenceSettingsRepository::setMode,
                    onTmdbClick = { onPageChange(SettingsPage.TmdbEnrichment) },
                    onMdbListClick = { onPageChange(SettingsPage.MdbListRatings) },
                    onQualiCacheClick = { onPageChange(SettingsPage.QualiCache) },
                    onDebridClick = { onPageChange(SettingsPage.Debrid) },
                    onTraktClick = { onPageChange(SettingsPage.TraktAuthentication) },
                    onSimklClick = { onPageChange(SettingsPage.SimklAuthentication) },
                    onYamtrackClick = { onPageChange(SettingsPage.YamtrackAuthentication) },
                )
                SettingsPage.TmdbEnrichment -> tmdbSettingsContent(
                    isTablet = false,
                    settings = tmdbSettings,
                )
                SettingsPage.MdbListRatings -> mdbListSettingsContent(
                    isTablet = false,
                    settings = mdbListSettings,
                )
                SettingsPage.QualiCache -> qualiCacheSettingsContent(
                    isTablet = false,
                    settings = qualiCacheSettings,
                )
                SettingsPage.Debrid -> debridSettingsContent(
                    isTablet = false,
                    settings = debridSettings,
                )
                SettingsPage.TraktAuthentication -> traktSettingsContent(
                    isTablet = false,
                    uiState = traktAuthUiState,
                    settingsUiState = traktSettingsUiState,
                    commentsEnabled = traktCommentsEnabled,
                    onCommentsEnabledChange = TraktCommentsSettings::setEnabled,
                )
                SettingsPage.SimklAuthentication -> simklSettingsContent(isTablet = false, uiState = simklAuthUiState, settingsUiState = simklSettingsUiState)
                SettingsPage.YamtrackAuthentication -> yamtrackSettingsContent(isTablet = false, settings = yamtrackSettingsUiState)
            }
        }
    }
}

private fun SettingsPage.isEnabledByFeaturePolicy(): Boolean =
    when (this) {
        SettingsPage.Notifications -> AppFeaturePolicy.notificationsEnabled
        SettingsPage.Plugins -> AppFeaturePolicy.pluginsEnabled
        SettingsPage.AutoDownloads -> AppFeaturePolicy.downloadsEnabled
        else -> true
    }

@Composable
private fun rememberSettingsRootSearchRevealConnection(
    page: SettingsPage,
    listState: LazyListState,
    query: String,
    searchVisible: Boolean,
    onReveal: () -> Unit,
): NestedScrollConnection {
    val revealThresholdPx = with(LocalDensity.current) { SettingsSearchRevealThreshold.toPx() }
    val currentOnReveal by rememberUpdatedState(onReveal)
    var pullDistancePx by remember(page) { mutableStateOf(0f) }
    var revealTriggered by remember(page) { mutableStateOf(false) }

    return remember(page, listState, query, searchVisible, revealThresholdPx) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val isRootAtTop = page == SettingsPage.Root &&
                    listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
                val canRevealSearch = isRootAtTop && !searchVisible && !revealTriggered && query.isBlank()

                if (canRevealSearch && available.y > 0f) {
                    pullDistancePx += available.y
                    if (pullDistancePx >= revealThresholdPx) {
                        pullDistancePx = 0f
                        revealTriggered = true
                        currentOnReveal()
                    }
                } else if (!isRootAtTop || available.y < 0f) {
                    pullDistancePx = 0f
                }

                return Offset.Zero
            }
        }
    }
}

@Composable
private fun TabletSettingsScreen(
    page: SettingsPage,
    scrollToTopRequests: Flow<Unit>,
    onPageChange: (SettingsPage) -> Unit,
    showContextPanel: Boolean,
    showLoadingOverlay: Boolean,
    defaultPlaybackSpeed: Float,
    preferredAudioLanguage: String,
    secondaryPreferredAudioLanguage: String?,
    preferredSubtitleLanguage: String,
    secondaryPreferredSubtitleLanguage: String?,
    streamReuseLastLinkEnabled: Boolean,
    streamReuseLastLinkCacheHours: Int,
    decoderPriority: Int,
    mapDV7ToHevc: Boolean,
    tunnelingEnabled: Boolean,
    useLibass: Boolean,
    libassRenderType: String,
    rememberLastProfileEnabled: Boolean,
    selectedTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit,
    customTheme: CustomThemeSettings,
    amoledEnabled: Boolean,
    onAmoledToggle: (Boolean) -> Unit,
    liquidGlassNativeTabBarSupported: Boolean,
    liquidGlassNativeTabBarEnabled: Boolean,
    onLiquidGlassNativeTabBarToggle: (Boolean) -> Unit,
    desktopNavigationLayout: DesktopNavigationLayout,
    onDesktopNavigationLayoutSelected: (DesktopNavigationLayout) -> Unit,
    desktopAppUiScalePercent: Int,
    onDesktopAppUiScalePercentChange: (Int) -> Unit,
    desktopAppUiScaleAppliesToDetails: Boolean,
    onDesktopAppUiScaleAppliesToDetailsChange: (Boolean) -> Unit,
    desktopColumnGuidesVisible: Boolean,
    onDesktopColumnGuidesVisibleChange: (Boolean) -> Unit,
    selectedAppLanguage: AppLanguage,
    onAppLanguageSelected: (AppLanguage) -> Unit,
    episodeReleaseNotificationsUiState: EpisodeReleaseNotificationsUiState,
    tmdbSettings: TmdbSettings,
    mdbListSettings: MdbListSettings,
    qualiCacheSettings: QualiCacheSettings,
    debridSettings: DebridSettings,
    discordPresenceSettings: DiscordPresenceSettings,
    traktAuthUiState: TraktAuthUiState,
    traktCommentsEnabled: Boolean,
    traktSettingsUiState: TraktSettingsUiState,
    simklAuthUiState: SimklAuthUiState,
    simklSettingsUiState: SimklSettingsUiState,
    yamtrackSettingsUiState: YamtrackSettings,
    homescreenHeroEnabled: Boolean,
    homescreenHeroInfoLines: Int,
    homescreenHeroInfoPriority: String,
    homescreenHeroBadgePlacement: HeroBadgePlacement,
    homescreenHeroBadgeScale: Float,
    homescreenHeroReleaseStatusUnavailableOnly: Boolean,
    homescreenHideUnreleasedContent: Boolean,
    homescreenHideCatalogUnderline: Boolean,
    homescreenAdaptiveHeroEnabled: Boolean,
    homescreenAdaptiveHeroVerticalBias: Float,
    homescreenHeroAmbientBackgroundEnabled: Boolean,
    homescreenTvModeEnabled: Boolean,
    homescreenItems: List<HomeCatalogSettingsItem>,
    randomPlaySettingsUiState: HomeCatalogSettingsUiState,
    metaScreenSettingsUiState: MetaScreenSettingsUiState,
    continueWatchingPreferencesUiState: ContinueWatchingPreferencesUiState,
    posterCardStyleUiState: PosterCardStyleUiState,
    profileAvatars: List<AvatarCatalogItem>,
    onSwitchProfile: (() -> Unit)? = null,
    onDownloadsClick: () -> Unit = {},
    onSupportersContributorsClick: () -> Unit = {},
    onLicensesAttributionsClick: () -> Unit = {},
    onCheckForUpdatesClick: (() -> Unit)? = null,
    onShowLatestChangelogClick: (() -> Unit)? = null,
    onOpenCollectionEditor: (String?) -> Unit = {},
    onNavigateToHome: (() -> Unit)? = null,
    onNavigateToSearch: (() -> Unit)? = null,
    onNavigateToLibrary: (() -> Unit)? = null,
    onSettingsSearchFocusChange: (Boolean) -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topOffset = max(statusBarPadding + 18.dp, 36.dp)

    fun openInlinePage(page: SettingsPage) {
        onPageChange(page)
    }

    val saveableStateHolder = rememberSaveableStateHolder()
    val activeSidebarPage = remember(page) { page.desktopSidebarPage() }
    val profileState by remember { ProfileRepository.state }.collectAsStateWithLifecycle()
    // A single desktop top bar must also have a single query when the selected settings page
    // changes. Per-page query state caused result clicks to reopen stale result lists.
    var settingsSearchQuery by rememberSaveable { mutableStateOf("") }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.colors.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        val contextPanelWidth = if (showContextPanel) DesktopSettingsContextPanelWidth else 0.dp
        val desiredShellWidth = DesktopSettingsSidebarWidth + DesktopSettingsMainColumnWidth + contextPanelWidth
        val shellWidth = if (maxWidth < desiredShellWidth) maxWidth else desiredShellWidth
        val contentWidth = shellWidth - DesktopSettingsSidebarWidth

        saveableStateHolder.SaveableStateProvider(page.name) {
            val localLibraryUiState by LocalLibraryRepository.uiState.collectAsStateWithLifecycle()
            val localLibraryTitlesState = rememberLocalLibraryTitlesState()
            var rootSearchVisible by rememberSaveable { mutableStateOf(false) }
            var rootSearchRevealAnimating by rememberSaveable { mutableStateOf(false) }
            val hapticFeedback = LocalHapticFeedback.current
            val hapticScope = rememberCoroutineScope()
            val searchEntries = settingsSearchEntries(
                pluginsEnabled = AppFeaturePolicy.pluginsEnabled,
                downloadsEnabled = AppFeaturePolicy.downloadsEnabled,
                notificationsEnabled = AppFeaturePolicy.notificationsEnabled,
                liquidGlassNativeTabBarSupported = liquidGlassNativeTabBarSupported,
                switchProfileAvailable = onSwitchProfile != null,
                checkForUpdatesAvailable = onCheckForUpdatesClick != null,
            )

            fun openSearchTarget(target: SettingsSearchTarget) {
                settingsSearchQuery = ""
                when (target) {
                    is SettingsSearchTarget.Page -> {
                        if (target.page.isEnabledByFeaturePolicy()) {
                            target.anchor?.let { anchor ->
                                SettingsScrollAnchor.request(
                                    anchor = anchor,
                                    fallbackAnchor = target.fallbackAnchor,
                                    fallbackTitle = target.fallbackTitle,
                                )
                            }
                            openInlinePage(target.page)
                        }
                    }
                    SettingsSearchTarget.Downloads -> {
                        if (AppFeaturePolicy.downloadsEnabled) {
                            onDownloadsClick()
                        }
                    }
                    SettingsSearchTarget.SwitchProfile -> onSwitchProfile?.invoke()
                    SettingsSearchTarget.CheckForUpdates -> onCheckForUpdatesClick?.invoke()
                }
            }

            val listState = rememberLazyListState()
            val bottomOverlayPadding = LocalNuvioBottomNavigationOverlayPadding.current
            val rootSearchRevealConnection = rememberSettingsRootSearchRevealConnection(
                page = page,
                listState = listState,
                query = settingsSearchQuery,
                searchVisible = rootSearchVisible,
            ) {
                rootSearchVisible = true
                rootSearchRevealAnimating = true
                hapticScope.launch {
                    delay(SettingsSearchRevealAnimationMillis)
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
            LaunchedEffect(rootSearchRevealAnimating) {
                if (rootSearchRevealAnimating) {
                    delay(SettingsSearchRevealAnimationMillis)
                    rootSearchRevealAnimating = false
                }
            }
            LaunchedEffect(scrollToTopRequests) {
                scrollToTopRequests.collect {
                    listState.animateScrollToItem(0)
                }
            }

        val columnGuideModifier = if (desktopColumnGuidesVisible) {
            Modifier.drawBehind {
                    val strokeWidth = tokens.borders.hairline.toPx()
                    val color = tokens.colors.accent.copy(alpha = 0.34f)
                    drawLine(
                        color = color,
                        start = Offset(strokeWidth / 2f, 0f),
                        end = Offset(strokeWidth / 2f, size.height),
                        strokeWidth = strokeWidth,
                    )
                    drawLine(
                        color = color,
                        start = Offset(size.width - strokeWidth / 2f, 0f),
                        end = Offset(size.width - strokeWidth / 2f, size.height),
                        strokeWidth = strokeWidth,
                    )
                }
        } else {
            Modifier
        }

        Column(
            modifier = Modifier
                .width(shellWidth)
                .fillMaxHeight()
                .then(columnGuideModifier),
        ) {
            DesktopSettingsTopBar(
                query = settingsSearchQuery,
                activeProfile = profileState.activeProfile,
                profileAvatars = profileAvatars,
                onQueryChange = { settingsSearchQuery = it },
                onSearchFocusChange = onSettingsSearchFocusChange,
                onProfileClick = onSwitchProfile,
                onShowLatestChangelogClick = onShowLatestChangelogClick ?: onCheckForUpdatesClick,
                columnGuidesVisible = desktopColumnGuidesVisible,
                onColumnGuidesVisibleChange = onDesktopColumnGuidesVisibleChange,
                onQuitClick = { platformExitApp() },
                onNavigateToHome = onNavigateToHome,
                onNavigateToSearch = onNavigateToSearch,
                onNavigateToLibrary = onNavigateToLibrary,
                contextPanelWidth = contextPanelWidth,
            )
            if (desktopColumnGuidesVisible) {
                HorizontalDivider(color = tokens.colors.accent.copy(alpha = 0.34f))
            }
            Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .width(DesktopSettingsSidebarWidth)
                    .fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 22.dp, vertical = 24.dp),
                ) {
                    LaunchedEffect(Unit) { SettingsCategoryOrderRepository.ensureLoaded() }
                    val categoryOrder by SettingsCategoryOrderRepository.order.collectAsStateWithLifecycle()
                    val sidebarItems = desktopSettingsSidebarItems()
                    val orderedSidebarItems = remember(sidebarItems, categoryOrder) {
                        orderDesktopSettingsSidebarItems(sidebarItems, categoryOrder)
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        DesktopPanelSection(title = stringResource(Res.string.settings_desktop_categories)) {
                            DesktopSettingsSidebarList(
                                items = orderedSidebarItems,
                                activeSidebarPage = activeSidebarPage,
                                onPageChange = ::openInlinePage,
                            )
                        }
                    }
                    DesktopSettingsSidebarFooter()
                }
                if (desktopColumnGuidesVisible) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(tokens.borders.hairline)
                            .fillMaxHeight()
                            .background(tokens.colors.accent.copy(alpha = 0.34f)),
                    )
                }
            }

            BoxWithConstraints(modifier = Modifier.width(contentWidth).fillMaxHeight()) {
                    val contextPanelWidth = if (showContextPanel) DesktopSettingsContextPanelWidth else 0.dp
                    val remainingForMain = maxWidth - contextPanelWidth
                    val mainColumnWidth = if (remainingForMain < DesktopSettingsMainColumnWidth) {
                        remainingForMain
                    } else {
                        DesktopSettingsMainColumnWidth
                    }
                    Row(modifier = Modifier.fillMaxSize()) {
                        // Expose the current page to every SettingsSection heading so it can build a
                        // stable favorite/scroll-anchor id and be pinned via right-click.
                        CompositionLocalProvider(LocalSettingsPage provides page) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .width(mainColumnWidth)
                                .fillMaxHeight()
                                .nestedScroll(rootSearchRevealConnection),
                            contentPadding = PaddingValues(
                                start = 32.dp,
                                top = 22.dp,
                                end = 32.dp,
                                bottom = 40.dp + bottomOverlayPadding,
                            ),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                val previousPage = page.desktopBackPage()
                if (previousPage != null) {
                    item {
                        TabletPageHeader(
                            title = stringResource(page.titleRes),
                            showBack = true,
                            onBack = {
                                val dest = SettingsScrollAnchor.consumeBackTo() ?: previousPage
                                dest.let(onPageChange)
                            },
                        )
                    }
                }
                if (settingsSearchQuery.isNotBlank()) {
                    // The top-bar search field is shown on every page in the wide layout, so a
                    // non-blank query must render results here regardless of which page is open —
                    // otherwise typing on a non-Root page (e.g. Addons) draws nothing.
                    settingsSearchResultsContent(
                        query = settingsSearchQuery,
                        entries = searchEntries,
                        isTablet = true,
                        onTargetClick = { openSearchTarget(it) },
                    )
                } else when (page) {
                    SettingsPage.Root -> {
                        settingsSearchRootContent(
                            query = settingsSearchQuery,
                            entries = searchEntries,
                            isTablet = true,
                            showSearchField = rootSearchVisible,
                            animateSearchField = rootSearchRevealAnimating,
                            onQueryChange = { settingsSearchQuery = it },
                            onSearchFocusChange = onSettingsSearchFocusChange,
                            onTargetClick = { openSearchTarget(it) },
                        )
                        if (settingsSearchQuery.isBlank()) {
                            settingsRootContent(
                                isTablet = true,
                                onPlaybackClick = { openInlinePage(SettingsPage.Playback) },
                                onRandomPlayClick = { openInlinePage(SettingsPage.RandomPlay) },
                                onStreamsClick = { openInlinePage(SettingsPage.Streams) },
                                onLocalLibraryClick = { openInlinePage(SettingsPage.LocalLibrary) },
                                onAutoDownloadsClick = { openInlinePage(SettingsPage.AutoDownloads) },
                                onAppearanceClick = { openInlinePage(SettingsPage.Appearance) },
                                onAdvancedClick = { openInlinePage(SettingsPage.Advanced) },
                                onNotificationsClick = { openInlinePage(SettingsPage.Notifications) },
                                onContinueWatchingClick = { openInlinePage(SettingsPage.ContinueWatching) },
                                onAddonsClick = { openInlinePage(SettingsPage.Addons) },
                                onPluginsClick = { openInlinePage(SettingsPage.Plugins) },
                                onHomescreenClick = { openInlinePage(SettingsPage.Homescreen) },
                                onMetaScreenClick = { openInlinePage(SettingsPage.MetaScreen) },
                                onCollectionsClick = { openInlinePage(SettingsPage.Collections) },
                                onIntegrationsClick = { openInlinePage(SettingsPage.Integrations) },
                                onTraktClick = { openInlinePage(SettingsPage.TraktAuthentication) },
                                onSimklClick = { openInlinePage(SettingsPage.SimklAuthentication) },
                                onYamtrackClick = { openInlinePage(SettingsPage.YamtrackAuthentication) },
                                onSupportersContributorsClick = { openInlinePage(SettingsPage.SupportersContributors) },
                                onLicensesAttributionsClick = { openInlinePage(SettingsPage.LicensesAttributions) },
                                onCheckForUpdatesClick = onCheckForUpdatesClick,
                                onDownloadsClick = onDownloadsClick,
                                onAccountClick = { openInlinePage(SettingsPage.Account) },
                                onSwitchProfileClick = onSwitchProfile,
                                showDownloadsEntry = AppFeaturePolicy.downloadsEnabled,
                                showNotificationsEntry = AppFeaturePolicy.notificationsEnabled,
                                showPluginsEntry = AppFeaturePolicy.pluginsEnabled,
                                showAccountSection = false,
                                showGeneralSection = true,
                                showAboutSection = false,
                                showAdvancedSection = false,
                            )
                        }
                    }
                    SettingsPage.Account -> accountSettingsContent(
                        isTablet = true,
                        rememberLastProfileEnabled = rememberLastProfileEnabled,
                    )
                    SettingsPage.SupportersContributors -> supportersContributorsContent(
                        isTablet = true,
                    )
                    SettingsPage.LicensesAttributions -> licensesAttributionsContent(
                        isTablet = true,
                    )
                    SettingsPage.Playback -> playbackSettingsContent(
                        isTablet = true,
                        showLoadingOverlay = showLoadingOverlay,
                        defaultPlaybackSpeed = defaultPlaybackSpeed,
                        preferredAudioLanguage = preferredAudioLanguage,
                        secondaryPreferredAudioLanguage = secondaryPreferredAudioLanguage,
                        preferredSubtitleLanguage = preferredSubtitleLanguage,
                        secondaryPreferredSubtitleLanguage = secondaryPreferredSubtitleLanguage,
                        streamReuseLastLinkEnabled = streamReuseLastLinkEnabled,
                        streamReuseLastLinkCacheHours = streamReuseLastLinkCacheHours,
                        decoderPriority = decoderPriority,
                        mapDV7ToHevc = mapDV7ToHevc,
                        tunnelingEnabled = tunnelingEnabled,
                        useLibass = useLibass,
                        libassRenderType = libassRenderType,
                    )
                    SettingsPage.RandomPlay -> randomPlaySettingsContent(
                        isTablet = true,
                        settings = randomPlaySettingsUiState,
                    )
                    SettingsPage.Streams -> streamsSettingsContent(
                        isTablet = true,
                        onOpenStreamScoring = { openInlinePage(SettingsPage.StreamScoring) },
                    )
                    SettingsPage.LocalLibrary -> localLibraryContent(
                        isTablet = true,
                        state = localLibraryUiState,
                        titlesState = localLibraryTitlesState,
                    )
                    SettingsPage.AutoDownloads -> libraryDownloadsSection(
                        isTablet = true,
                        onDownloadsClick = onDownloadsClick,
                    )
                SettingsPage.StreamScoring -> streamScoringSection(isTablet = true)
                    SettingsPage.KeyboardShortcuts -> keyboardShortcutsContent(
                        isTablet = true,
                    )
                    SettingsPage.Appearance -> appearanceSettingsContent(
                        isTablet = true,
                        selectedTheme = selectedTheme,
                        onThemeSelected = onThemeSelected,
                        customTheme = customTheme,
                        amoledEnabled = amoledEnabled,
                        onAmoledToggle = onAmoledToggle,
                        liquidGlassNativeTabBarSupported = liquidGlassNativeTabBarSupported,
                        liquidGlassNativeTabBarEnabled = liquidGlassNativeTabBarEnabled,
                        onLiquidGlassNativeTabBarToggle = onLiquidGlassNativeTabBarToggle,
                        desktopNavigationLayout = desktopNavigationLayout,
                        onDesktopNavigationLayoutSelected = onDesktopNavigationLayoutSelected,
                        desktopAppUiScalePercent = desktopAppUiScalePercent,
                        onDesktopAppUiScalePercentChange = onDesktopAppUiScalePercentChange,
                        desktopAppUiScaleAppliesToDetails = desktopAppUiScaleAppliesToDetails,
                        onDesktopAppUiScaleAppliesToDetailsChange = onDesktopAppUiScaleAppliesToDetailsChange,
                        selectedAppLanguage = selectedAppLanguage,
                        onAppLanguageSelected = onAppLanguageSelected,
                        posterCardStyleUiState = posterCardStyleUiState,
                    )
                    SettingsPage.Advanced -> advancedSettingsContent(
                        isTablet = true,
                        rememberLastProfileEnabled = rememberLastProfileEnabled,
                    )
                    SettingsPage.Notifications -> if (AppFeaturePolicy.notificationsEnabled) {
                        notificationsSettingsContent(
                            isTablet = true,
                            uiState = episodeReleaseNotificationsUiState,
                        )
                    }
                    SettingsPage.ContinueWatching -> continueWatchingSettingsContent(
                        isTablet = true,
                        isVisible = continueWatchingPreferencesUiState.isVisible,
                        style = continueWatchingPreferencesUiState.style,
                        upNextFromFurthestEpisode = continueWatchingPreferencesUiState.upNextFromFurthestEpisode,
                        useEpisodeThumbnails = continueWatchingPreferencesUiState.useEpisodeThumbnails,
                        showUnairedNextUp = continueWatchingPreferencesUiState.showUnairedNextUp,
                        blurNextUp = continueWatchingPreferencesUiState.blurNextUp,
                        showResumePromptOnLaunch = continueWatchingPreferencesUiState.showResumePromptOnLaunch,
                        sortMode = continueWatchingPreferencesUiState.sortMode,
                    )
                    SettingsPage.PosterCustomization -> posterCustomizationSettingsContent(
                        isTablet = true,
                        uiState = posterCardStyleUiState,
                    )
                    SettingsPage.ContentDiscovery -> contentDiscoveryContent(
                        isTablet = true,
                        showPluginsEntry = AppFeaturePolicy.pluginsEnabled,
                        showDownloadsEntry = AppFeaturePolicy.downloadsEnabled,
                        onAddonsClick = { openInlinePage(SettingsPage.Addons) },
                        onPluginsClick = { openInlinePage(SettingsPage.Plugins) },
                        onHomescreenClick = { openInlinePage(SettingsPage.Homescreen) },
                        onMetaScreenClick = { openInlinePage(SettingsPage.MetaScreen) },
                        onCollectionsClick = { openInlinePage(SettingsPage.Collections) },
                        onDownloadsClick = onDownloadsClick,
                    )
                    SettingsPage.Collections -> collectionsSettingsContent(
                        isTablet = true,
                        onNavigateToEditor = onOpenCollectionEditor,
                    )
                    SettingsPage.Addons -> addonsSettingsContent()
                    SettingsPage.Plugins -> if (AppFeaturePolicy.pluginsEnabled) pluginsSettingsContent() else addonsSettingsContent()
                    SettingsPage.Homescreen -> homescreenSettingsContent(
                        isTablet = true,
                        heroEnabled = homescreenHeroEnabled,
                        heroInfoLines = homescreenHeroInfoLines,
                        heroInfoPriority = homescreenHeroInfoPriority,
                        heroBadgePlacement = homescreenHeroBadgePlacement,
                    heroBadgeScale = homescreenHeroBadgeScale,
                        heroReleaseStatusUnavailableOnly = homescreenHeroReleaseStatusUnavailableOnly,
                        hideUnreleasedContent = homescreenHideUnreleasedContent,
                        hideCatalogUnderline = homescreenHideCatalogUnderline,
                        adaptiveHeroEnabled = homescreenAdaptiveHeroEnabled,
                        adaptiveHeroVerticalBias = homescreenAdaptiveHeroVerticalBias,
                        heroAmbientBackgroundEnabled = homescreenHeroAmbientBackgroundEnabled,
                        tvModeEnabled = homescreenTvModeEnabled,
                        items = homescreenItems,
                    )
                    SettingsPage.MetaScreen -> metaScreenSettingsContent(
                        isTablet = true,
                        uiState = metaScreenSettingsUiState,
                    )
                    SettingsPage.Integrations -> integrationsContent(
                        isTablet = true,
                        discordPresenceSettings = discordPresenceSettings,
                        onDiscordPresenceModeChange = DiscordPresenceSettingsRepository::setMode,
                        onTmdbClick = { onPageChange(SettingsPage.TmdbEnrichment) },
                        onMdbListClick = { onPageChange(SettingsPage.MdbListRatings) },
                    onQualiCacheClick = { onPageChange(SettingsPage.QualiCache) },
                        onDebridClick = { onPageChange(SettingsPage.Debrid) },
                        onTraktClick = { onPageChange(SettingsPage.TraktAuthentication) },
                        onSimklClick = { onPageChange(SettingsPage.SimklAuthentication) },
                        onYamtrackClick = { onPageChange(SettingsPage.YamtrackAuthentication) },
                    )
                    SettingsPage.TmdbEnrichment -> tmdbSettingsContent(
                        isTablet = true,
                        settings = tmdbSettings,
                    )
                    SettingsPage.MdbListRatings -> mdbListSettingsContent(
                        isTablet = true,
                        settings = mdbListSettings,
                    )
                    SettingsPage.QualiCache -> qualiCacheSettingsContent(
                        isTablet = true,
                        settings = qualiCacheSettings,
                    )
                    SettingsPage.Debrid -> debridSettingsContent(
                        isTablet = true,
                        settings = debridSettings,
                    )
                    SettingsPage.TraktAuthentication -> traktSettingsContent(
                        isTablet = true,
                        uiState = traktAuthUiState,
                        settingsUiState = traktSettingsUiState,
                        commentsEnabled = traktCommentsEnabled,
                        onCommentsEnabledChange = TraktCommentsSettings::setEnabled,
                    )
                    SettingsPage.SimklAuthentication -> simklSettingsContent(isTablet = true, uiState = simklAuthUiState, settingsUiState = simklSettingsUiState)
                    SettingsPage.YamtrackAuthentication -> yamtrackSettingsContent(isTablet = true, settings = yamtrackSettingsUiState)
                }
                    }
                    }
                    if (showContextPanel) {
                        DesktopSettingsContextPanel(
                            page = page,
                            selectedTheme = selectedTheme,
                            amoledEnabled = amoledEnabled,
                            liquidGlassNativeTabBarEnabled = liquidGlassNativeTabBarEnabled,
                            discordPresenceSettings = discordPresenceSettings,
                            tmdbSettings = tmdbSettings,
                            mdbListSettings = mdbListSettings,
                            debridSettings = debridSettings,
                            traktAuthUiState = traktAuthUiState,
                            simklAuthUiState = simklAuthUiState,
                            showLoadingOverlay = showLoadingOverlay,
                            defaultPlaybackSpeed = defaultPlaybackSpeed,
                            onPageChange = ::openInlinePage,
                            onCheckForUpdatesClick = onCheckForUpdatesClick,
                            columnGuidesVisible = desktopColumnGuidesVisible,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
    }
}
}

private data class DesktopSettingsSidebarItem(
    val label: String,
    val icon: ImageVector,
    val page: SettingsPage,
)

@Composable
private fun desktopSettingsSidebarItems(): List<DesktopSettingsSidebarItem> = listOf(
    DesktopSettingsSidebarItem(
        label = stringResource(Res.string.compose_settings_page_addons),
        icon = Icons.Rounded.AutoAwesome,
        page = SettingsPage.Addons,
    ),
    DesktopSettingsSidebarItem(
        label = stringResource(Res.string.collections_header),
        icon = Icons.Rounded.CollectionsBookmark,
        page = SettingsPage.Collections,
    ),
    DesktopSettingsSidebarItem(
        label = "Cont Watching",
        icon = Icons.Rounded.CollectionsBookmark,
        page = SettingsPage.ContinueWatching,
    ),
    DesktopSettingsSidebarItem(
        label = stringResource(Res.string.compose_settings_page_meta_screen),
        icon = Icons.Rounded.Tune,
        page = SettingsPage.MetaScreen,
    ),
    DesktopSettingsSidebarItem(
        label = stringResource(Res.string.compose_settings_page_homescreen),
        icon = Icons.Rounded.Settings,
        page = SettingsPage.Homescreen,
    ),
    DesktopSettingsSidebarItem(
        label = stringResource(Res.string.compose_settings_page_integrations),
        icon = Icons.Rounded.Link,
        page = SettingsPage.Integrations,
    ),
    DesktopSettingsSidebarItem(
        label = stringResource(Res.string.compose_settings_page_account),
        icon = Icons.Rounded.AccountCircle,
        page = SettingsPage.Account,
    ),
    DesktopSettingsSidebarItem(
        label = stringResource(Res.string.compose_settings_page_appearance),
        icon = Icons.Rounded.Palette,
        page = SettingsPage.Appearance,
    ),
    DesktopSettingsSidebarItem(
        label = stringResource(Res.string.compose_settings_page_playback),
        icon = Icons.Rounded.PlayArrow,
        page = SettingsPage.Playback,
    ),
    DesktopSettingsSidebarItem(
        label = stringResource(Res.string.random_play_title),
        icon = Icons.Rounded.Casino,
        page = SettingsPage.RandomPlay,
    ),
    DesktopSettingsSidebarItem(
        label = stringResource(Res.string.compose_settings_page_plugins),
        icon = Icons.Rounded.Settings,
        page = SettingsPage.Plugins,
    ),
    DesktopSettingsSidebarItem(
        label = stringResource(Res.string.compose_settings_page_streams),
        icon = Icons.Rounded.Tune,
        page = SettingsPage.Streams,
    ),
    DesktopSettingsSidebarItem(
        label = stringResource(Res.string.compose_settings_page_local_library),
        icon = Icons.Rounded.VideoLibrary,
        page = SettingsPage.LocalLibrary,
    ),
    DesktopSettingsSidebarItem(
        label = stringResource(Res.string.compose_settings_page_auto_downloads),
        icon = Icons.Rounded.CloudDownload,
        page = SettingsPage.AutoDownloads,
    ),
    DesktopSettingsSidebarItem(
        label = stringResource(Res.string.compose_settings_page_keyboard_shortcuts),
        icon = Icons.Rounded.Keyboard,
        page = SettingsPage.KeyboardShortcuts,
    ),
    DesktopSettingsSidebarItem(
        label = stringResource(Res.string.compose_settings_page_licenses_attributions),
        icon = Icons.Rounded.Info,
        page = SettingsPage.LicensesAttributions,
    ),
).filter { it.page.isEnabledByFeaturePolicy() }

private fun SettingsPage.desktopSidebarPage(): SettingsPage = when (this) {
    SettingsPage.Addons -> SettingsPage.Addons
    SettingsPage.Collections -> SettingsPage.Collections
    SettingsPage.ContinueWatching -> SettingsPage.ContinueWatching
    SettingsPage.MetaScreen -> SettingsPage.MetaScreen
    SettingsPage.Playback -> SettingsPage.Playback
    SettingsPage.RandomPlay -> SettingsPage.RandomPlay
    SettingsPage.Appearance,
    SettingsPage.PosterCustomization -> SettingsPage.Appearance
    SettingsPage.Homescreen -> SettingsPage.Homescreen
    SettingsPage.Plugins -> SettingsPage.Plugins
    SettingsPage.Streams -> SettingsPage.Streams
    SettingsPage.LocalLibrary -> SettingsPage.LocalLibrary
    SettingsPage.AutoDownloads -> SettingsPage.AutoDownloads
    SettingsPage.StreamScoring -> SettingsPage.Streams
    SettingsPage.KeyboardShortcuts -> SettingsPage.KeyboardShortcuts
    SettingsPage.LicensesAttributions -> SettingsPage.LicensesAttributions
    SettingsPage.Account -> SettingsPage.Account
    SettingsPage.TraktAuthentication,
    SettingsPage.SimklAuthentication -> SettingsPage.Integrations
    SettingsPage.YamtrackAuthentication -> SettingsPage.Integrations
    SettingsPage.Integrations,
    SettingsPage.TmdbEnrichment,
    SettingsPage.MdbListRatings,
    SettingsPage.QualiCache,
    SettingsPage.Debrid -> SettingsPage.Integrations
    SettingsPage.Notifications -> SettingsPage.Notifications
    SettingsPage.Advanced -> SettingsPage.Advanced
    else -> SettingsPage.Root
}

private fun SettingsPage.desktopBackPage(): SettingsPage? = when (this) {
    SettingsPage.PosterCustomization -> SettingsPage.Appearance
    SettingsPage.StreamScoring -> SettingsPage.Streams
    SettingsPage.TmdbEnrichment,
    SettingsPage.MdbListRatings,
    SettingsPage.QualiCache,
    SettingsPage.Debrid,
    SettingsPage.TraktAuthentication,
    SettingsPage.SimklAuthentication,
    SettingsPage.YamtrackAuthentication -> SettingsPage.Integrations
    else -> null
}

private fun orderDesktopSettingsSidebarItems(
    items: List<DesktopSettingsSidebarItem>,
    categoryOrder: List<String>,
): List<DesktopSettingsSidebarItem> {
    if (categoryOrder.isEmpty()) return items
    val byPage = items.associateBy { it.page.name }
    val ordered = categoryOrder.mapNotNull { byPage[it] }
    return ordered + items.filterNot { item -> item.page.name in categoryOrder }
}

@Composable
private fun DesktopSettingsSidebarList(
    items: List<DesktopSettingsSidebarItem>,
    activeSidebarPage: SettingsPage,
    onPageChange: (SettingsPage) -> Unit,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(listState) { from, to ->
        SettingsCategoryOrderRepository.moveByIndex(
            fromIndex = from.index,
            toIndex = to.index,
            visiblePages = items.map { it.page.name },
        )
        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(items, key = { _, item -> item.page.name }) { _, item ->
            ReorderableItem(reorderableLazyListState, key = item.page.name) {
                DesktopSettingsSidebarRow(
                    label = item.label,
                    icon = item.icon,
                    selected = item.page == activeSidebarPage,
                    modifier = with(this@ReorderableItem) {
                        // The whole row is both clickable (select category) and the drag handle, so
                        // draggableHandle's immediate slop-based drag turned a click with the
                        // slightest movement into a reorder. Require a deliberate long-press before a
                        // drag begins so a normal click just selects the category.
                        Modifier.longPressDraggableHandle(
                            onDragStarted = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                        )
                    },
                    onClick = { onPageChange(item.page) },
                )
            }
        }
    }
}

@Composable
private fun DesktopSettingsSidebarRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val contentColor = if (selected) tokens.colors.accent else tokens.colors.textMuted
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DesktopSettingsProfileRow(
    activeProfile: NuvioProfile?,
    profileAvatars: List<AvatarCatalogItem>,
    onClick: (() -> Unit)?,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ActiveProfileMiniAvatar(
            profile = activeProfile,
            avatars = profileAvatars,
            selected = false,
            size = 42,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = activeProfile?.name?.takeIf { it.isNotBlank() } ?: "Profile",
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "HTPC",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DesktopSettingsTopBar(
    query: String,
    activeProfile: NuvioProfile?,
    profileAvatars: List<AvatarCatalogItem>,
    onQueryChange: (String) -> Unit,
    onSearchFocusChange: (Boolean) -> Unit,
    onProfileClick: (() -> Unit)?,
    onShowLatestChangelogClick: (() -> Unit)?,
    columnGuidesVisible: Boolean,
    onColumnGuidesVisibleChange: (Boolean) -> Unit,
    onQuitClick: () -> Unit,
    onNavigateToHome: (() -> Unit)?,
    onNavigateToSearch: (() -> Unit)?,
    onNavigateToLibrary: (() -> Unit)?,
    contextPanelWidth: Dp,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(
                modifier = Modifier
                    .clickable(enabled = onProfileClick != null) { onProfileClick?.invoke() }
                    .padding(2.dp),
            ) {
                ActiveProfileMiniAvatar(
                    profile = activeProfile,
                    avatars = profileAvatars,
                    selected = false,
                    size = 34,
                )
            }
            IconButton(
                onClick = { onShowLatestChangelogClick?.invoke() },
                enabled = onShowLatestChangelogClick != null,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Notifications,
                    contentDescription = null,
                    tint = if (onShowLatestChangelogClick != null) {
                        tokens.colors.accent
                    } else {
                        tokens.colors.textMuted.copy(alpha = 0.42f)
                    },
                )
            }
            IconButton(
                onClick = { onColumnGuidesVisibleChange(!columnGuidesVisible) },
            ) {
                Icon(
                    imageVector = Icons.Rounded.ViewColumn,
                    contentDescription = stringResource(Res.string.settings_desktop_toggle_column_guides),
                    tint = if (columnGuidesVisible) {
                        tokens.colors.accent
                    } else {
                        tokens.colors.textMuted
                    },
                )
            }
            IconButton(onClick = onQuitClick) {
                Icon(
                    imageVector = Icons.Rounded.PowerSettingsNew,
                    contentDescription = null,
                    tint = tokens.colors.accent,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            DesktopSettingsRootNavigation(
                onNavigateToHome = onNavigateToHome,
                onNavigateToSearch = onNavigateToSearch,
                onNavigateToLibrary = onNavigateToLibrary,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (contextPanelWidth == 0.dp) {
                SettingsSearchField(
                    query = query,
                    onQueryChange = onQueryChange,
                    onFocusChange = onSearchFocusChange,
                    modifier = Modifier
                        .padding(end = 18.dp)
                        .height(44.dp)
                        .widthIn(min = 240.dp, max = 320.dp),
                )
            }
        }
        if (contextPanelWidth > 0.dp) {
            Box(
                modifier = Modifier
                    .width(contextPanelWidth)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                SettingsSearchField(
                    query = query,
                    onQueryChange = onQueryChange,
                    onFocusChange = onSearchFocusChange,
                    modifier = Modifier
                        .padding(horizontal = 18.dp)
                        .height(44.dp)
                        .widthIn(min = 240.dp, max = 268.dp),
                )
            }
        }
    }
}

@Composable
private fun DesktopSettingsRootNavigation(
    onNavigateToHome: (() -> Unit)?,
    onNavigateToSearch: (() -> Unit)?,
    onNavigateToLibrary: (() -> Unit)?,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        IconButton(
            onClick = { onNavigateToHome?.invoke() },
            enabled = onNavigateToHome != null,
        ) {
            Icon(
                imageVector = Icons.Filled.Home,
                contentDescription = stringResource(Res.string.compose_nav_home),
                modifier = Modifier.size(22.dp),
                tint = if (onNavigateToHome != null) {
                    tokens.colors.accent
                } else {
                    tokens.colors.textMuted.copy(alpha = 0.42f)
                },
            )
        }
        IconButton(
            onClick = { onNavigateToSearch?.invoke() },
            enabled = onNavigateToSearch != null,
        ) {
            Icon(
                painter = painterResource(Res.drawable.sidebar_search),
                contentDescription = stringResource(Res.string.compose_nav_search),
                modifier = Modifier.size(22.dp),
                tint = if (onNavigateToSearch != null) {
                    tokens.colors.accent
                } else {
                    tokens.colors.textMuted.copy(alpha = 0.42f)
                },
            )
        }
        IconButton(
            onClick = { onNavigateToLibrary?.invoke() },
            enabled = onNavigateToLibrary != null,
        ) {
            Icon(
                painter = painterResource(Res.drawable.sidebar_library),
                contentDescription = stringResource(Res.string.compose_nav_library),
                modifier = Modifier.size(22.dp),
                tint = if (onNavigateToLibrary != null) {
                    tokens.colors.accent
                } else {
                    tokens.colors.textMuted.copy(alpha = 0.42f)
                },
            )
        }
    }
}

@Composable
private fun DesktopSettingsContextPanel(
    page: SettingsPage,
    selectedTheme: AppTheme,
    amoledEnabled: Boolean,
    liquidGlassNativeTabBarEnabled: Boolean,
    discordPresenceSettings: DiscordPresenceSettings,
    tmdbSettings: TmdbSettings,
    mdbListSettings: MdbListSettings,
    debridSettings: DebridSettings,
    traktAuthUiState: TraktAuthUiState,
    simklAuthUiState: SimklAuthUiState,
    showLoadingOverlay: Boolean,
    defaultPlaybackSpeed: Float,
    onPageChange: (SettingsPage) -> Unit,
    onCheckForUpdatesClick: (() -> Unit)?,
    columnGuidesVisible: Boolean,
) {
    val tokens = MaterialTheme.nuvio
    val playerSettings by PlayerSettingsRepository.uiState.collectAsStateWithLifecycle()
    Surface(
        modifier = Modifier
            .width(DesktopSettingsContextPanelWidth)
            .fillMaxHeight(),
        color = tokens.colors.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (columnGuidesVisible) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(tokens.borders.hairline)
                        .fillMaxHeight()
                        .background(tokens.colors.accent.copy(alpha = 0.34f)),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(tokens.borders.hairline)
                        .fillMaxHeight()
                        .background(tokens.colors.accent.copy(alpha = 0.34f)),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 22.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                LaunchedEffect(Unit) { SettingsFavoritesRepository.ensureLoaded() }
                val favorites by SettingsFavoritesRepository.favorites.collectAsStateWithLifecycle()
                DesktopPanelSection(title = stringResource(Res.string.settings_desktop_favorites)) {
                    if (favorites.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.settings_desktop_favorites_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.colors.textMuted,
                        )
                    } else {
                        DesktopFavoritesList(
                            favorites = favorites,
                            onPageChange = onPageChange,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopFavoritesList(
    favorites: List<SettingsFavorite>,
    onPageChange: (SettingsPage) -> Unit,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(listState) { from, to ->
        SettingsFavoritesRepository.moveByIndex(from.index, to.index)
        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp),
        state = listState,
    ) {
        itemsIndexed(favorites, key = { _, favorite -> favorite.anchor }) { _, favorite ->
            ReorderableItem(reorderableLazyListState, key = favorite.anchor) {
                val target = runCatching { SettingsPage.valueOf(favorite.page) }.getOrNull()
                val subtitle = target?.let { stringResource(it.titleRes) }.orEmpty()
                DesktopActionRow(
                    icon = Icons.Rounded.Star,
                    title = favorite.title,
                    subtitle = subtitle,
                    modifier = with(this@ReorderableItem) {
                        Modifier.draggableHandle(
                            onDragStarted = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                        )
                    },
                    onSecondaryClick = {
                        SettingsFavoritesRepository.remove(favorite.anchor)
                    },
                ) {
                    if (target != null) {
                        onPageChange(target)
                        SettingsScrollAnchor.request(favorite.anchor)
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopPanelSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.nuvio.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            content()
        }
    }
}

@Composable
private fun DesktopSettingsSidebarFooter() {
    val tokens = MaterialTheme.nuvio
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier.padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Muted rather than accent-coloured: this is a quiet footnote, and a bright accent (red,
        // cyan, gold) made it the loudest thing in the sidebar. The underline still marks it as a
        // link.
        Text(
            text = stringResource(Res.string.settings_desktop_project_name),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
            fontWeight = FontWeight.Medium,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable {
                runCatching { uriHandler.openUri(NuvioHtpcRepoUrl) }
            },
        )
        Text(
            text = stringResource(
                Res.string.settings_desktop_build,
                AppVersionConfig.DESKTOP_VERSION_NAME,
                AppVersionConfig.DESKTOP_VERSION_CODE,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
        Text(
            text = stringResource(Res.string.compose_about_open_logs_folder),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
            fontWeight = FontWeight.Medium,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable { platformOpenLogsDirectory() },
        )
    }
}

@Composable
private fun DesktopInfoRow(
    icon: ImageVector,
    label: String,
    value: String,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tokens.colors.textMuted,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DesktopActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onSecondaryClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = modifier
            .fillMaxWidth()
            .secondaryClick(onSecondaryClick)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tokens.colors.textMuted,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailingContent?.invoke()
    }
}

private fun enabledLabel(enabled: Boolean): String = if (enabled) "Enabled" else "Disabled"

private fun connectedLabel(connected: Boolean): String = if (connected) "Connected" else "Not connected"
