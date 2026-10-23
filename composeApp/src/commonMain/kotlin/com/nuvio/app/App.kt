package com.nuvio.app

import androidx.compose.runtime.key
import com.nuvio.app.core.perf.PerfTelemetry
import com.nuvio.app.features.settings.perfSettingsPage
import com.nuvio.app.features.streams.StreamsPresentation
import com.nuvio.app.features.streams.StreamPickerPlacement
import com.nuvio.app.features.streams.StreamPickerFrame
import com.nuvio.app.features.streams.StreamPickerRequest
import com.nuvio.app.features.streams.StreamPickerController
import com.nuvio.app.features.streams.StreamPickerDock
import co.touchlab.kermit.Logger
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material3.IconButton
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.max
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.milliseconds
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.ReauthenticationTrigger
import com.nuvio.app.features.party.matches
import com.nuvio.app.core.deeplink.AppDeepLink
import com.nuvio.app.core.deeplink.AppDeepLinkRepository
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.sync.AppForegroundMonitor
import com.nuvio.app.core.sync.ProfileSettingsSync
import com.nuvio.app.core.sync.SyncManager
import com.nuvio.app.core.ui.ImageCacheSettingsRepository
import com.nuvio.app.core.ui.KeptAliveLayer
import com.nuvio.app.core.ui.rememberChildLifecycleOwner
import com.nuvio.app.core.ui.nuvioLaunchBackdrop
import com.nuvio.app.core.ui.rememberNuvioLaunchDrift
import com.nuvio.app.core.ui.NuvioNavigationBar
import com.nuvio.app.core.ui.NuvioContinueWatchingActionSheet
import com.nuvio.app.core.ui.NuvioPosterActionSheet
import com.nuvio.app.core.ui.PosterZoomAnchor
import com.nuvio.app.core.ui.PosterZoomAnchorHolder
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.DesktopNavigationGestureBridge
import com.nuvio.app.core.ui.DesktopBackRequestSource
import androidx.compose.runtime.rememberUpdatedState
import com.nuvio.app.core.ui.PosterZoomOverlayCoordinator
import com.nuvio.app.core.ui.ContextMenuInvocation
import com.nuvio.app.core.ui.unclaimedSecondaryClick
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.platformExitApp
import com.nuvio.app.core.ui.configurePlatformImageLoader
import com.nuvio.app.core.ui.TransientImageErrorRetryInterceptor
import com.nuvio.app.core.ui.NuvioToastHost
import com.nuvio.app.core.ui.StartupOverlayCoordinator
import com.nuvio.app.core.ui.LocalOpenMetaDetails
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioFloatingPrompt
import com.nuvio.app.core.ui.NuvioResumePromptDialog
import com.nuvio.app.core.ui.NuvioDesktopViewportDensityScaler
import com.nuvio.app.core.ui.LocalNuvioViewportDensity
import com.nuvio.app.core.ui.TraktListPickerDialog
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.LocalNuvioBottomNavigationOverlayPadding
import com.nuvio.app.core.ui.NativeNavigationTab
import com.nuvio.app.core.ui.NativeTabBridge
import com.nuvio.app.core.ui.isLiquidGlassNativeTabBarSupported
import com.nuvio.app.core.ui.localizedContinueWatchingSubtitle
import com.nuvio.app.core.ui.nuvio
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import com.nuvio.app.features.discover.DiscoverRecommendationsRepository
import com.nuvio.app.features.auth.AuthScreen
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.calendar.CalendarPrewarm
import com.nuvio.app.features.calendar.CalendarScreen
import com.nuvio.app.features.catalog.CatalogRepository
import com.nuvio.app.features.catalog.CatalogScreen
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.catalog.CatalogTargetKind
import com.nuvio.app.features.cloud.CloudLibraryContentType
import com.nuvio.app.features.cloud.CloudLibraryFileChooserDialog
import com.nuvio.app.features.cloud.CloudLibraryFile
import com.nuvio.app.features.cloud.CloudLibraryItem
import com.nuvio.app.features.cloud.CloudLibraryPlaybackResult
import com.nuvio.app.features.cloud.CloudLibraryPlaybackTargetLookupResult
import com.nuvio.app.features.cloud.CloudLibraryRepository
import com.nuvio.app.features.cloud.playbackVideoId
import com.nuvio.app.features.cloud.providerPosterUrl
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.toastMessage
import com.nuvio.app.features.discord.DiscordPresenceSettingsRepository
import com.nuvio.app.features.discord.DiscordRichPresenceActivity
import com.nuvio.app.features.discord.DiscordRichPresenceActivityType
import com.nuvio.app.features.discord.DiscordRichPresenceImageFit
import com.nuvio.app.features.discord.DiscordRichPresenceController
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.playbackLabels
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaDetailsScreen
import com.nuvio.app.features.details.MetaPerson
import com.nuvio.app.features.details.PersonDetailScreen
import com.nuvio.app.features.details.resolveSeriesEpisodePosition
import com.nuvio.app.features.details.HeroBadgeBrowseScreen
import com.nuvio.app.features.details.TmdbEntityBrowseScreen
import com.nuvio.app.features.metadata.MediaIdResolver
import com.nuvio.app.features.tmdb.TmdbEntityKind
import com.nuvio.app.features.home.HeroCastMember
import com.nuvio.app.features.home.HeroDiscoveryBadgeTarget
import com.nuvio.app.features.home.HeroDiscoveryFact
import com.nuvio.app.features.home.browseTarget
import com.nuvio.app.features.home.browseTitle
import com.nuvio.app.features.home.decodeHeroDiscoveryBadgeTarget
import com.nuvio.app.features.home.encodeForRoute
import com.nuvio.app.features.home.components.heroDiscoveryAwardLabel
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.HomeScreen
import com.nuvio.app.features.games.GameModeController
import com.nuvio.app.features.games.GameModeScreen
import com.nuvio.app.features.home.components.HomeHeroTrailerGate
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.home.RandomPlayAction
import com.nuvio.app.features.home.RandomPlayCandidatePool
import com.nuvio.app.features.home.RandomPlayCollectionPool
import com.nuvio.app.features.home.pickRandomPlayItem
import com.nuvio.app.features.home.randomPlaySourceSections
import com.nuvio.app.features.home.withRandomPlayPool
import com.nuvio.app.features.home.randomPlayCategoryOrNull
import com.nuvio.app.features.home.randomPlayPickTrace
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryNavigationContextMenu
import com.nuvio.app.features.library.LibraryQuickAccess
import com.nuvio.app.features.library.LibraryQuickAccessMenuPanel
import com.nuvio.app.core.ui.secondaryClick
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.librarypvr.ReleaseYearResolver
import com.nuvio.app.features.library.LibrarySection
import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.library.LibraryScreen
import com.nuvio.app.features.library.toLibraryItem
import com.nuvio.app.features.library.toMetaPreview
import com.nuvio.app.features.locallibrary.FilenameParser
import com.nuvio.app.features.locallibrary.LocalLibraryPlaybackPreference
import com.nuvio.app.features.locallibrary.LocalLibraryRepository
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsRepository
import com.nuvio.app.features.p2p.P2pConsentDialog
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.player.PlaybackStartTrace
import com.nuvio.app.features.player.PlayerAutoPlayMode
import com.nuvio.app.features.player.PlayerHandoff
import com.nuvio.app.features.playlist.PlaylistAddDialogHost
import com.nuvio.app.features.playlist.PlaylistAddController
import com.nuvio.app.features.playlist.PlaylistAddTarget
import com.nuvio.app.features.playlist.PlaylistEntries
import com.nuvio.app.features.playlist.PlaylistPlaybackSession
import com.nuvio.app.features.playlist.PlaylistRepository
import com.nuvio.app.features.playlist.PlaylistEntry
import com.nuvio.app.features.playlist.PlaylistRandomEpisodes
import com.nuvio.app.features.playlist.PlaylistDialog
import com.nuvio.app.features.playlist.PlaylistHandoff
import com.nuvio.app.features.playlist.isPlaylistPreview
import com.nuvio.app.features.playlist.PlaylistContinueWatchingSnapshot
import com.nuvio.app.features.playlist.displayTitle
import com.nuvio.app.features.playlist.playlistAddTargetForContinueWatching
import com.nuvio.app.features.playlist.toPlaylistEntryOrNull
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.LocalFileDrop
import com.nuvio.app.features.player.AppShortcutAction
import com.nuvio.app.features.player.AppShortcutBridge
import com.nuvio.app.features.player.PlayerLaunchStore
import com.nuvio.app.features.player.PlayerRoute
import com.nuvio.app.features.player.PlayerScreen
import com.nuvio.app.features.player.PlayerSourceAffinity
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.ExternalPlayerIntentResult
import com.nuvio.app.features.player.ExternalPlayerPlatform
import com.nuvio.app.features.player.ExternalPlayerPlaybackRequest
import com.nuvio.app.features.player.rememberExternalPlayerLauncher
import com.nuvio.app.features.player.prepareExternalPlayerLaunch
import com.nuvio.app.features.player.playerSourceIdentityKey
import com.nuvio.app.features.player.externalPlayerSubtitleTargets
import com.nuvio.app.features.player.externalPlaybackTracking
import com.nuvio.app.features.cast.CastController
import com.nuvio.app.features.cast.CastDialogHost
import com.nuvio.app.features.cast.toCastRequest
import com.nuvio.app.features.player.OriginalLanguageCache
import com.nuvio.app.features.player.sanitizePlaybackHeaders
import com.nuvio.app.features.player.sanitizePlaybackResponseHeaders
import com.nuvio.app.features.player.skip.SkipDbDumpRepository
import com.nuvio.app.features.profiles.ActiveProfileMiniAvatar
import com.nuvio.app.features.profiles.AvatarCatalogItem
import com.nuvio.app.features.profiles.AvatarRepository
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileEditScreen
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.ProfileSelectionScreen
import com.nuvio.app.features.profiles.ProfileSwitcherTab
import com.nuvio.app.features.profiles.SidebarProfileSwitcherStack
import com.nuvio.app.features.profiles.profileAvatarImageUrl
import com.nuvio.app.features.search.SearchScreen
import com.nuvio.app.features.search.SearchRepository
import com.nuvio.app.features.search.discoverCatalogDisplayLabels
import com.nuvio.app.features.settings.ApiKeysOnboardingHost
import com.nuvio.app.features.setup.FirstRunWizardController
import com.nuvio.app.features.setup.FirstRunWizardHost
import com.nuvio.app.features.settings.SettingsScreen
import com.nuvio.app.features.settings.HomescreenSettingsScreen
import com.nuvio.app.features.settings.MetaScreenSettingsScreen
import com.nuvio.app.features.settings.ContinueWatchingSettingsScreen
import com.nuvio.app.features.settings.AddonsSettingsScreen
import com.nuvio.app.features.settings.PluginsSettingsScreen
import com.nuvio.app.features.settings.AccountSettingsScreen
import com.nuvio.app.features.settings.SupportersContributorsSettingsScreen
import com.nuvio.app.features.settings.LicensesAttributionsSettingsScreen
import com.nuvio.app.features.settings.DesktopNavigationLayout
import com.nuvio.app.features.settings.SettingsSearchIndex
import com.nuvio.app.features.settings.ThemeSettingsRepository
import com.nuvio.app.features.collection.CollectionEditorScreen
import com.nuvio.app.features.collection.CollectionEditorRepository
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.collection.CollectionSyncService
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeCatalogSettingsSyncService
import com.nuvio.app.features.collection.FolderDetailScreen
import com.nuvio.app.features.collection.FolderDetailRepository
import com.nuvio.app.features.collection.clearFolderScrollSession
import com.nuvio.app.features.streams.mediaFilename
import com.nuvio.app.features.streams.BingeGroupCacheRepository
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamLaunch
import com.nuvio.app.features.streams.StreamLinkCacheRepository
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.streams.StreamsScreen
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.simkl.SimklAuthRepository
import com.nuvio.app.features.simkl.SimklConnectionMode
import com.nuvio.app.features.simkl.SimklDailyVisit
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.simkl.SimklSettingsRepository
import com.nuvio.app.features.simkl.SimklRewatchRepository
import com.nuvio.app.features.simkl.SimklRewatchStartResult
import com.nuvio.app.features.simkl.canUseRewatches
import com.nuvio.app.features.mdblist.MdbListScrobbleAdapter
import com.nuvio.app.features.mdblist.MdbListLibraryAdapter
import com.nuvio.app.features.mdblist.MdbListTrackingAuthProvider
import com.nuvio.app.features.mdblist.MdbListWatchedAdapter
import com.nuvio.app.features.mdblist.MdbListHistoryWriter
import com.nuvio.app.features.mdblist.MdbListRatingWriter
import com.nuvio.app.features.simkl.SimklScrobbleAdapter
import com.nuvio.app.features.simkl.SimklLibraryAdapter
import com.nuvio.app.features.simkl.SimklTrackingAuthProvider
import com.nuvio.app.features.simkl.SimklWatchedAdapter
import com.nuvio.app.features.simkl.SimklHistoryWriter
import com.nuvio.app.features.simkl.SimklRatingWriter
import com.nuvio.app.features.tracking.CalendarSourceRepository
import com.nuvio.app.features.tracking.ContinueWatchingSource
import com.nuvio.app.features.tracking.ContinueWatchingSourceRepository
import com.nuvio.app.features.tracking.RatingPromptHost
import com.nuvio.app.features.tracking.RatingPromptRepository
import com.nuvio.app.features.tracking.LibrarySourceRepository
import com.nuvio.app.features.tracking.migratedCalendarSource
import com.nuvio.app.features.tracking.migratedContinueWatchingSource
import com.nuvio.app.features.tracking.migratedLibrarySource
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleCoordinator
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.buildTrackingMediaReference
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktListTab
import com.nuvio.app.features.trakt.TraktScrobbleAdapter
import com.nuvio.app.features.trakt.TraktLibraryAdapter
import com.nuvio.app.features.trakt.TraktSettingsRepository
import com.nuvio.app.features.trakt.WatchProgressSource
import com.nuvio.app.features.trakt.TraktTrackingAuthProvider
import com.nuvio.app.features.trakt.TraktHistoryWriter
import com.nuvio.app.features.yamtrack.YamtrackScrobbleAdapter
import com.nuvio.app.features.yamtrack.YamtrackHistoryWriter
import com.nuvio.app.features.yamtrack.YamtrackLibraryAdapter
import com.nuvio.app.features.yamtrack.YamtrackWatchedAdapter
import com.nuvio.app.features.yamtrack.YamtrackRatingWriter
import com.nuvio.app.features.yamtrack.YamtrackTrackingAuthProvider
import kotlin.concurrent.Volatile
import com.nuvio.app.features.updater.AppUpdaterHost
import com.nuvio.app.features.updater.rememberAppUpdaterController
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.ResumePromptRepository
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.nextUpDismissKey
import com.nuvio.app.features.watchprogress.opensDetails
import com.nuvio.app.features.watchprogress.toContinueWatchingItem
import com.nuvio.app.features.watching.application.WatchingActions
import com.nuvio.app.features.watching.application.WatchingState
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import nuvio.composeapp.generated.resources.*
import nuvio.composeapp.generated.resources.app_wordmark_text
import nuvio.composeapp.generated.resources.compose_catalog_subtitle_library
import nuvio.composeapp.generated.resources.compose_catalog_subtitle_trakt_library
import nuvio.composeapp.generated.resources.compose_nav_home
import nuvio.composeapp.generated.resources.compose_nav_library
import nuvio.composeapp.generated.resources.compose_nav_profile
import nuvio.composeapp.generated.resources.compose_nav_search
import nuvio.composeapp.generated.resources.sidebar_library
import nuvio.composeapp.generated.resources.sidebar_search
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.core.ui.trackTextInputFocus

@Serializable
object TabsRoute

@Serializable
data class DetailRoute(
    val type: String,
    val id: String,
    val autoPlay: Boolean = false,
)

@Serializable
data class PersonDetailRoute(
    val personId: Int,
    val personName: String,
    val personPhoto: String? = null,
    val castAvatarTransitionKey: String? = null,
    val preferCrew: Boolean = false,
)

@Serializable
data class EntityBrowseRoute(
    val entityKind: String,
    val entityId: Int,
    val entityName: String,
    val sourceType: String = "tv",
)

/**
 * A hero discovery badge's browse screen. [targetKind]/[targetValue] are the encoded
 * [HeroDiscoveryBadgeTarget]; [title] is the badge's display name, shown in the rail titles.
 */
@Serializable
data class BadgeBrowseRoute(
    val targetKind: String,
    val targetValue: String,
    val title: String,
    val sourceType: String = "movie",
)

private data class PendingP2pStreamOpen(
    val stream: StreamItem,
    val resumePositionMs: Long?,
    val resumeProgressFraction: Float?,
    val forceExternal: Boolean,
    val forceInternal: Boolean,
    val isAutoPlay: Boolean,
)

@Serializable
object HomescreenSettingsRoute

@Serializable
object MetaScreenSettingsRoute

@Serializable
object ContinueWatchingSettingsRoute

@Serializable
object AddonsSettingsRoute

@Serializable
object PluginsSettingsRoute

@Serializable
object AccountSettingsRoute

@Serializable
object SupportersContributorsSettingsRoute

@Serializable
object LicensesAttributionsSettingsRoute

@Serializable
object CalendarRoute

@Serializable
data class CollectionEditorRoute(val collectionId: String? = null)

@Serializable
data class FolderDetailRoute(val collectionId: String, val folderId: String)

@Serializable
data class CatalogRoute(
    val title: String,
    val subtitle: String,
    val targetKind: String,
    val contentType: String,
    val supportsPagination: Boolean = false,
    val manifestUrl: String? = null,
    val addonCatalogId: String? = null,
    val genre: String? = null,
    val searchQuery: String? = null,
    val librarySectionType: String? = null,
    val collectionId: String? = null,
    val folderId: String? = null,
    val sourceKey: String? = null,
    val discoverRowKey: String? = null,
) {
    constructor(
        title: String,
        subtitle: String,
        target: CatalogTarget,
    ) : this(
        title = title,
        subtitle = subtitle,
        targetKind = when (target) {
            is CatalogTarget.Addon -> CatalogTargetKind.ADDON
            is CatalogTarget.Library -> CatalogTargetKind.LIBRARY
            is CatalogTarget.CollectionSource -> CatalogTargetKind.COLLECTION_SOURCE
            is CatalogTarget.DiscoverRow -> CatalogTargetKind.DISCOVER_ROW
        }.name,
        contentType = target.contentType,
        supportsPagination = target.supportsPagination,
        manifestUrl = (target as? CatalogTarget.Addon)?.manifestUrl,
        addonCatalogId = (target as? CatalogTarget.Addon)?.catalogId,
        genre = (target as? CatalogTarget.Addon)?.genre,
        searchQuery = (target as? CatalogTarget.Addon)?.searchQuery,
        librarySectionType = (target as? CatalogTarget.Library)?.sectionType,
        collectionId = (target as? CatalogTarget.CollectionSource)?.collectionId,
        folderId = (target as? CatalogTarget.CollectionSource)?.folderId,
        sourceKey = (target as? CatalogTarget.CollectionSource)?.sourceKey,
        discoverRowKey = (target as? CatalogTarget.DiscoverRow)?.rowKey,
    )

    fun toCatalogTarget(): CatalogTarget =
        when (CatalogTargetKind.valueOf(targetKind)) {
            CatalogTargetKind.ADDON -> CatalogTarget.Addon(
                manifestUrl = requireNotNull(manifestUrl),
                contentType = contentType,
                catalogId = requireNotNull(addonCatalogId),
                genre = genre,
                searchQuery = searchQuery,
                supportsPagination = supportsPagination,
            )

            CatalogTargetKind.LIBRARY -> CatalogTarget.Library(
                contentType = contentType,
                sectionType = requireNotNull(librarySectionType),
            )

            CatalogTargetKind.COLLECTION_SOURCE -> CatalogTarget.CollectionSource(
                collectionId = requireNotNull(collectionId),
                folderId = requireNotNull(folderId),
                sourceKey = requireNotNull(sourceKey),
                contentType = contentType,
                supportsPagination = supportsPagination,
            )

            CatalogTargetKind.DISCOVER_ROW -> CatalogTarget.DiscoverRow(
                rowKey = requireNotNull(discoverRowKey),
                contentType = contentType,
            )
        }
}

private data class PosterActionTarget(
    val preview: MetaPreview,
    val libraryItem: LibraryItem? = null,
    val libraryListKey: String? = null,
)

/** The screen name `(PerfScreen)` attributes a telemetry window to. */
private fun perfScreenName(entry: NavBackStackEntry, tab: AppScreenTab, searchOverlayActive: Boolean): String {
    val destination = entry.destination
    return when {
        destination.hasRoute<TabsRoute>() -> when {
            searchOverlayActive -> "Search"
            tab == AppScreenTab.Settings -> "Settings/$perfSettingsPage"
            else -> tab.name
        }
        destination.hasRoute<DetailRoute>() -> entry.toRoute<DetailRoute>().let { "Details ${it.type} ${it.id}" }
        else -> destination.route.orEmpty()
            .substringBefore('/')
            .substringBefore('?')
            .substringAfterLast('.')
            .removeSuffix("Route")
            .ifEmpty { "?" }
    }
}

enum class AppScreenTab {
    Home,
    Search,
    Discover,
    Library,
    /** IPTV channels; only in the navigation while a playlist exists (see rememberLiveTvTabVisible). */
    LiveTv,
    Settings,
}

internal fun rootTabHistoryAfterNavigation(
    history: List<AppScreenTab>,
    current: AppScreenTab,
    target: AppScreenTab,
): List<AppScreenTab> {
    if (current == target || history.lastOrNull() == current) return history
    return (history + current).takeLast(16)
}

// Width of the floating top bar pill *and* the picker panel that hangs off its bottom edge — the
// two must stay identical or the panel's rounded top corners stop lining up with the bar. Sized so
// the five nav quadrants keep the 80.dp rhythm they had when there were four. Quadrants divide the
// bar by weight, so hiding Discover subtracts one quadrant instead of stretching the other four.
private val FloatingTopBarQuadrantWidth = 80.dp
private val FloatingTopBarWidth = 400.dp

// The sidebar's nav glyphs sit alone in a wide rail rather than in a dense pill, so they carry
// 15% more than the standard 20.dp icon step to stop them reading as undersized.
private val DesktopSidebarIconSize = 23.dp
private val DesktopSidebarCollapsedWidth = 76.dp
private val DesktopSidebarExpandedContentWidth = 144.dp
private val DesktopSidebarIconSlotSize = 36.dp

private fun AppScreenTab.toNativeNavigationTab(): NativeNavigationTab = when (this) {
    AppScreenTab.Home -> NativeNavigationTab.Home
    AppScreenTab.Search -> NativeNavigationTab.Search
    // The native tab bar is a fixed four-tab surface with no Discover slot, so Discover reports as
    // Home rather than inventing a tab the native bar cannot render or select back.
    AppScreenTab.Discover -> NativeNavigationTab.Home
    AppScreenTab.LiveTv -> NativeNavigationTab.Home
    AppScreenTab.Library -> NativeNavigationTab.Library
    AppScreenTab.Settings -> NativeNavigationTab.Settings
}

private fun NativeNavigationTab.toAppScreenTab(): AppScreenTab = when (this) {
    NativeNavigationTab.Home -> AppScreenTab.Home
    NativeNavigationTab.Search -> AppScreenTab.Search
    NativeNavigationTab.Library -> AppScreenTab.Library
    NativeNavigationTab.Settings -> AppScreenTab.Settings
}

private fun PlayerLaunch.toExternalPlayerPlaybackRequest(): ExternalPlayerPlaybackRequest =
    ExternalPlayerPlaybackRequest(
        sourceUrl = sourceUrl,
        title = title,
        streamTitle = streamTitle,
        sourceHeaders = sourceHeaders,
        resumePositionMs = initialPositionMs,
        season = seasonNumber,
        episode = episodeNumber,
        episodeTitle = episodeTitle,
        tracking = externalPlaybackTracking(),
    )

/**
 * Recovers the display file name for a directly opened media URI (dropped local file or a pasted
 * HTTP(S) stream). Cloud/debrid links routinely use an opaque object id as the final path segment
 * and carry the real release name in a `filename=` query parameter, so that parameter is preferred
 * for web streams before falling back to the last path segment. Returns null when nothing usable
 * can be recovered. The extension is left intact for [FilenameParser] to strip.
 */
private fun directPlayMediaName(mediaUri: String, isWebStream: Boolean): String? {
    if (isWebStream) {
        runCatching { Url(mediaUri) }.getOrNull()
            ?.parameters?.get("filename")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
    }
    return mediaUri
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('\\')
        .substringAfterLast('/')
        .trim()
        .takeIf { it.isNotBlank() }
}

private enum class AppGateScreen {
    Loading,
    Auth,
    ProfileSelection,
    ProfileSwitching,
    ProfileEdit,
    Main,
}

private data class PendingProfileSwitch(
    val profile: NuvioProfile,
    val syncOnEnter: Boolean,
)

private val appStartupLog = Logger.withTag("AppStartup")

/** Why a Random Play click landed on the title it did; see [randomPlayPickTrace]. */
private val randomPlayLog = Logger.withTag("RandomPlay")

/** Startup work that waits for Home (see StartupReadySignal); IO because it is mostly probing. */
private val deferredStartupScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

private const val EXTERNAL_PLAYER_DETECTION_DELAY_MS = 5_000L
private const val SETTINGS_SEARCH_WARM_DELAY_MS = 8_000L
private const val HEAVY_STARTUP_WARM_DELAY_MS = 3_000L

private inline fun startupWarmStep(
    name: String,
    rethrow: Boolean = true,
    block: () -> Unit,
) {
    val startedAt = System.currentTimeMillis()
    val result = runCatching(block)
    val elapsedMs = System.currentTimeMillis() - startedAt
    result.onSuccess {
        appStartupLog.i { "$name completed in ${elapsedMs}ms" }
    }.onFailure { error ->
        appStartupLog.e(error) { "$name failed after ${elapsedMs}ms" }
        if (rethrow) throw error
    }
}

private val trackingRegistrationLock = Any()

@Volatile
private var trackingProvidersRegistered = false

/**
 * Publishes the tracking ports once per process.
 *
 * Registration is provider identity, not profile state, so it must not be repeated on a profile
 * switch — and it has to happen before the first scrobble regardless of whether a given provider's
 * repositories are warmed in the critical or the deferred pass.
 */
private fun registerTrackingProviders() {
    synchronized(trackingRegistrationLock) {
        if (trackingProvidersRegistered) return
        trackingProvidersRegistered = true
    }
    // Read once, only if no single selection has been stored yet, so an upgrading user keeps
    // whatever they were actually seeing before the three per-provider flags were collapsed.
    LibrarySourceRepository.legacyMigrationProbe = {
        migratedLibrarySource(
            simklWasLibrarySource = SimklSettingsRepository.isSimklLibrarySource(),
            storedMode = TraktSettingsRepository.uiState.value.librarySourceMode
                .takeIf { TraktAuthRepository.isAuthenticated.value }
                ?: LibrarySourceMode.LOCAL,
        )
    }
    ContinueWatchingSourceRepository.legacyMigrationProbe = {
        migratedContinueWatchingSource(
            mdbListWasCwSource = MdbListSettingsRepository.isMdbListCwSource(),
            simklWasCwSource = SimklSettingsRepository.isSimklCwSource(),
            traktWasCwSource = TraktAuthRepository.isAuthenticated.value &&
                TraktSettingsRepository.uiState.value.watchProgressSource == WatchProgressSource.TRAKT,
        )
    }
    CalendarSourceRepository.legacyMigrationProbe = {
        migratedCalendarSource(
            mdbListWasCalendarSource = MdbListSettingsRepository.isMdbListCalendarSource(),
            simklWasCalendarSource = SimklSettingsRepository.isSimklCalendarSource(),
        )
    }
    TrackingProviderRegistry.register(TraktTrackingAuthProvider)
    TrackingProviderRegistry.registerLibraryProvider(TraktLibraryAdapter)
    TrackingProviderRegistry.registerHistoryWriter(TraktHistoryWriter)
    TrackingProviderRegistry.registerScrobbler(TraktScrobbleAdapter)
    TrackingProviderRegistry.register(SimklTrackingAuthProvider)
    TrackingProviderRegistry.registerLibraryProvider(SimklLibraryAdapter)
    TrackingProviderRegistry.registerHistoryWriter(SimklHistoryWriter)
    TrackingProviderRegistry.registerRatingWriter(SimklRatingWriter)
    TrackingProviderRegistry.registerScrobbler(SimklScrobbleAdapter)
    TrackingProviderRegistry.registerWatchedProvider(SimklWatchedAdapter)
    TrackingProviderRegistry.register(MdbListTrackingAuthProvider)
    TrackingProviderRegistry.registerLibraryProvider(MdbListLibraryAdapter)
    TrackingProviderRegistry.registerHistoryWriter(MdbListHistoryWriter)
    TrackingProviderRegistry.registerRatingWriter(MdbListRatingWriter)
    TrackingProviderRegistry.registerScrobbler(MdbListScrobbleAdapter)
    TrackingProviderRegistry.registerWatchedProvider(MdbListWatchedAdapter)
    TrackingProviderRegistry.register(YamtrackTrackingAuthProvider)
    TrackingProviderRegistry.registerScrobbler(YamtrackScrobbleAdapter)
    TrackingProviderRegistry.registerHistoryWriter(YamtrackHistoryWriter)
    TrackingProviderRegistry.registerRatingWriter(YamtrackRatingWriter)
    TrackingProviderRegistry.registerLibraryProvider(YamtrackLibraryAdapter)
    TrackingProviderRegistry.registerWatchedProvider(YamtrackWatchedAdapter)
}

private suspend fun warmProfileStartupRepositories() {
    registerTrackingProviders()
    withContext(Dispatchers.Default) {
        val startedAt = System.currentTimeMillis()
        appStartupLog.i { "critical profile warm started" }
        startupWarmStep("addons local load") { AddonRepository.initialize() }
        startupWarmStep("collections local load") { CollectionRepository.initialize() }
        startupWarmStep("continue watching preferences load") { ContinueWatchingPreferencesRepository.ensureLoaded() }
        // Home reads it in its first composition; cold, that was a file load and JSON decode on
        // the UI thread.
        startupWarmStep("continue watching enrichment cache load") {
            com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentCache.warm()
        }
        startupWarmStep("home catalog settings load") { HomeCatalogSettingsRepository.snapshot() }
        // Background: parses last launch's rows while the rest of the warm-up and the gate run, so
        // Home opens with them in place. Needs the catalog settings above to know which rows show.
        startupWarmStep("home rows disk restore start") { HomeRepository.restoreFromDiskInBackground() }
        startupWarmStep("player settings load") { PlayerSettingsRepository.ensureLoaded() }
        startupWarmStep("p2p settings load") { P2pSettingsRepository.ensureLoaded() }
        startupWarmStep("trakt settings load") { TraktSettingsRepository.ensureLoaded() }
        startupWarmStep("trakt auth load") { TraktAuthRepository.ensureLoaded() }
        startupWarmStep("watch progress load") { WatchProgressRepository.ensureLoaded() }
        startupWarmStep("watched state load") { WatchedRepository.ensureLoaded() }
        appStartupLog.i { "critical profile warm completed in ${System.currentTimeMillis() - startedAt}ms" }
    }
}

private suspend fun warmProfileDeferredRepositories() {
    withContext(Dispatchers.Default) {
        val startedAt = System.currentTimeMillis()
        appStartupLog.i { "deferred profile warm started" }
        // The two loads that touch the library drive (downloads stat their files; the local
        // library reads its store and kicks off the folder scan) run on IO so a sleeping HDD
        // parks an IO thread rather than a Default worker the rest of the warm needs. Neither is
        // called from composition any more, so this is where they first load.
        startupWarmStep("downloads load", rethrow = false) {
            withContext(Dispatchers.IO) { DownloadsRepository.ensureLoaded() }
        }
        startupWarmStep("local library load", rethrow = false) {
            withContext(Dispatchers.IO) { LocalLibraryRepository.ensureLoaded() }
        }
        startupWarmStep("library pvr load", rethrow = false) { com.nuvio.app.features.librarypvr.LibraryPvrRepository.ensureLoaded() }
        startupWarmStep("episode notifications load", rethrow = false) { EpisodeReleaseNotificationsRepository.ensureLoaded() }
        startupWarmStep("library load", rethrow = false) { LibraryRepository.ensureLoaded() }
        startupWarmStep("discord presence settings load", rethrow = false) { DiscordPresenceSettingsRepository.ensureLoaded() }
        startupWarmStep("lights settings load", rethrow = false) { com.nuvio.app.features.lights.LightsController.ensureLoaded() }
        startupWarmStep("simkl settings load", rethrow = false) { SimklSettingsRepository.ensureLoaded() }
        startupWarmStep("simkl auth load", rethrow = false) { SimklAuthRepository.ensureLoaded() }
        startupWarmStep("simkl rewatches load", rethrow = false) {
            SimklRewatchRepository.ensureLoaded()
            if (
                SimklSettingsRepository.isRewatchTrackingEnabled() &&
                SimklAuthRepository.uiState.value.canUseRewatches
            ) {
                // Gated on activities: a delta when something moved, a full read only when
                // `removed_from_list` moved or the last full read is a day old. See refreshNow.
                SimklRewatchRepository.refreshAsync()
            }
        }
        startupWarmStep("tvdb settings load", rethrow = false) { com.nuvio.app.features.tvdb.TvdbSettingsRepository.ensureLoaded() }
        // The Playback settings page lists external players while composing. The first lookup
        // spawns reg.exe for the default media association and probes known install paths and
        // PATH for each player — on the UI thread, as a stall on opening that page. Cached after.
        // Warmed, but not now: it is 16 reg.exe launches, and here they landed in the middle of
        // Home's first composition (a startup profile caught them there). A few seconds after Home
        // is up is still long before anyone reaches the Playback page.
        startupWarmStep("external player detection scheduled", rethrow = false) {
            com.nuvio.app.core.build.StartupReadySignal.onHomeReady {
                deferredStartupScope.launch {
                    delay(EXTERNAL_PLAYER_DETECTION_DELAY_MS)
                    startupWarmStep("external player detection", rethrow = false) {
                        com.nuvio.app.features.player.ExternalPlayerPlatform.availablePlayers()
                    }
                }
            }
        }
        // MDBList ratings that have come due are refreshed in bulk once Home is up, not one title
        // at a time as the hero and details pages reach them.
        startupWarmStep("mdblist stale sweep scheduled", rethrow = false) {
            com.nuvio.app.core.build.StartupReadySignal.onHomeReady {
                com.nuvio.app.features.mdblist.MdbListMetadataService.startStaleSweep()
            }
        }
        // The first initialize() reads every scraper's source out of nuvio_plugin_code (MBs of
        // JS; the open alone measured ~2.9 s, likely AV scanning it). Left to first use, that
        // was the stream screen, in series with the source list — or the UI thread, from settings.
        // A few seconds after Home is up rather than now (2026-10-20): reading every scraper's source
        // and the anime-list parse below both built long-lived data during Home's first frames, where
        // each young collection copied it again ((YoungGc): ~1 s of pauses in the first 2.5 s). Nothing
        // on Home needs either: plugins serve the stream list, and an early anime-id lookup simply
        // parses the list on its own thread, as it would without the warm.
        if (AppFeaturePolicy.pluginsEnabled) {
            startupWarmStep("plugins load scheduled", rethrow = false) {
                com.nuvio.app.core.build.StartupReadySignal.onHomeReady {
                    deferredStartupScope.launch {
                        delay(HEAVY_STARTUP_WARM_DELAY_MS)
                        startupWarmStep("plugins load", rethrow = false) {
                            withContext(Dispatchers.IO) { com.nuvio.app.features.plugins.PluginRepository.initialize() }
                        }
                    }
                }
            }
        }
        startupWarmStep("collection sync observer start", rethrow = false) { CollectionSyncService.startObserving() }
        if (AppFeaturePolicy.downloadsEnabled) {
            startupWarmStep("library auto-download scheduler start", rethrow = false) {
                com.nuvio.app.features.librarypvr.LibraryPvrScheduler.start()
            }
        }
        // Resolves the ~550 string resources behind settings search. Each one is a separate
        // blocking read out of the packaged jar, so doing it here rather than during the settings
        // screen's first composition is what keeps opening settings instant. A few seconds after
        // Home is up, not now: run here it took ~180 ms in the middle of the young-GC burst at the
        // first Home frame (2026-10-19), and nobody reaches settings search that soon.
        startupWarmStep("settings search index warm scheduled", rethrow = false) {
            com.nuvio.app.core.build.StartupReadySignal.onHomeReady {
                deferredStartupScope.launch {
                    delay(SETTINGS_SEARCH_WARM_DELAY_MS)
                    startupWarmStep("settings search index warm", rethrow = false) {
                        ThemeSettingsRepository.ensureLoaded()
                        SettingsSearchIndex.warm(ThemeSettingsRepository.selectedAppLanguage.value.code)
                    }
                }
            }
        }
        startupWarmStep("home catalog sync observer start", rethrow = false) { HomeCatalogSettingsSyncService.startObserving() }
        startupWarmStep("profile settings sync observer start", rethrow = false) { ProfileSettingsSync.startObserving() }
        startupWarmStep("anime id mapping warm scheduled", rethrow = false) {
            com.nuvio.app.core.build.StartupReadySignal.onHomeReady {
                deferredStartupScope.launch {
                    delay(HEAVY_STARTUP_WARM_DELAY_MS)
                    com.nuvio.app.features.metadata.AnimeIdMappingRepository.warmAsync()
                }
            }
        }
        // Connected tracking services are the user's own accounts, independent of whether they
        // signed into Nuvio — and `SyncManager.pullAllForProfile` runs neither for a signed-out
        // user nor on a warm start, so this is the only place their watched history is imported.
        startupWarmStep("tracking provider watched history import", rethrow = false) {
            WatchedRepository.pullConnectedProviderHistory(ProfileRepository.activeProfileId)
        }
        val continueWatchingSyncStartedAt = System.currentTimeMillis()
        runCatching {
            WatchProgressRepository.forceContinueWatchingSync(
                profileId = ProfileRepository.activeProfileId,
                // The step above already imported it; a second forced pull here would only spend
                // another request against the provider's daily budget.
                reimportProviderHistory = false,
            )
        }.onSuccess {
            appStartupLog.i {
                "continue watching startup sync completed in " +
                    "${System.currentTimeMillis() - continueWatchingSyncStartedAt}ms"
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            appStartupLog.e(error) {
                "continue watching startup sync failed after " +
                    "${System.currentTimeMillis() - continueWatchingSyncStartedAt}ms"
            }
        }
        appStartupLog.i { "deferred profile warm completed in ${System.currentTimeMillis() - startedAt}ms" }
        // Not a warm step: it waits for the app to settle and then fetches the whole calendar,
        // so it runs on its own scope rather than inside this timed sequence. Sits here rather
        // than at the launch site so a profile switch (which resets the calendar repositories)
        // prewarms the new profile's calendar too.
        CalendarPrewarm.scheduleAsync()
    }
}

/**
 * Optional SIMKL reminder: opens simkl.com in the default browser on the first app start of each
 * UTC day so the user keeps the visit streak that unlocks free SIMKL Pro. Off by default, and only
 * while SIMKL is actually connected. The day stamp is written after the browser hand-off succeeds,
 * so a failed launch retries on the next start instead of burning the day.
 */
private suspend fun openSimklDailyVisitIfDue(openUri: (String) -> Unit) {
    val due = withContext(Dispatchers.Default) {
        runCatching {
            SimklSettingsRepository.ensureLoaded()
            SimklAuthRepository.ensureLoaded()
            SimklAuthRepository.uiState.value.mode == SimklConnectionMode.CONNECTED &&
                SimklSettingsRepository.isDailyVisitDue(System.currentTimeMillis())
        }.getOrDefault(false)
    }
    if (!due) return
    runCatching { openUri(SimklDailyVisit.URL) }
        .onSuccess {
            appStartupLog.i { "simkl daily visit opened" }
            SimklSettingsRepository.markDailyVisitOpened(System.currentTimeMillis())
        }
        .onFailure { error -> appStartupLog.e(error) { "simkl daily visit open failed" } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Preview
fun App() {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .crossfade(true)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .components {
                add(SvgDecoder.Factory())
                add(TransientImageErrorRetryInterceptor())
            }
            .configurePlatformImageLoader()
            .build()
    }
    val selectedTheme by remember {
        ThemeSettingsRepository.ensureLoaded()
        ThemeSettingsRepository.selectedTheme
    }.collectAsStateWithLifecycle()
    val customTheme by remember { ThemeSettingsRepository.customTheme }.collectAsStateWithLifecycle()
    val amoledEnabled by remember { ThemeSettingsRepository.amoledEnabled }.collectAsStateWithLifecycle()
    val desktopAppUiScalePercent by remember {
        ThemeSettingsRepository.desktopAppUiScalePercent
    }.collectAsStateWithLifecycle()
    val accentGradientDirection by remember {
        ThemeSettingsRepository.accentGradientDirection
    }.collectAsStateWithLifecycle()
    val appFontFamily by remember { ThemeSettingsRepository.appFontFamily }.collectAsStateWithLifecycle()
    NuvioTheme(
        appTheme = selectedTheme,
        customThemePalette = customTheme.palette,
        amoled = amoledEnabled,
        accentGradientDirection = accentGradientDirection,
        appFontFamilyName = appFontFamily,
    ) {
        LaunchedEffect(Unit) {
            AuthRepository.initialize()
        }

        LaunchedEffect(Unit) {
            NetworkStatusRepository.ensureStarted()
            ProfileRepository.loadCachedProfiles()
            AvatarRepository.fetchAvatars()
        }

        // SkipDB publishes its whole database as one file rebuilt daily, so it is kept locally and
        // refreshed at most once a day rather than queried per episode. Deliberately off the
        // startup path and not awaited: playback answers from the previous copy meanwhile, and a
        // user who has turned skip detection off should not be downloading it at all.
        LaunchedEffect(Unit) {
            PlayerSettingsRepository.ensureLoaded()
            if (PlayerSettingsRepository.uiState.value.skipIntroEnabled) {
                SkipDbDumpRepository.syncInBackground()
            }
        }

        val authState by AuthRepository.state.collectAsStateWithLifecycle()
        val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
        val profileAvatars by AvatarRepository.avatars.collectAsStateWithLifecycle()
        val networkStatusUiState by remember {
            NetworkStatusRepository.uiState
        }.collectAsStateWithLifecycle()

        LaunchedEffect(
            profileState.activeProfile?.profileIndex,
            profileState.activeProfile?.name,
            profileState.activeProfile?.avatarColorHex,
            profileState.activeProfile?.avatarId,
            profileState.activeProfile?.avatarUrl,
            profileAvatars,
        ) {
            val activeProfile = profileState.activeProfile
            val avatarItem = activeProfile?.avatarId?.let { avatarId ->
                profileAvatars.find { it.id == avatarId }
            }
            NativeTabBridge.publishProfileTabIcon(
                name = activeProfile?.name,
                avatarColorHex = activeProfile?.avatarColorHex,
                avatarImageUrl = activeProfile?.let { profileAvatarImageUrl(it, avatarItem) },
                avatarBackgroundColorHex = avatarItem?.bgColor,
            )
        }

        var gateScreen by rememberSaveable { mutableStateOf(AppGateScreen.Loading.name) }
        var editingProfile by remember { mutableStateOf<NuvioProfile?>(null) }
        var isNewProfile by remember { mutableStateOf(false) }
        var autoSkipProfileSelection by rememberSaveable { mutableStateOf(false) }
        var pendingProfileSwitch by remember { mutableStateOf<PendingProfileSwitch?>(null) }

        fun rememberedStartupProfile(profiles: List<NuvioProfile>): NuvioProfile? {
            val currentProfileState = ProfileRepository.state.value
            if (
                !currentProfileState.rememberLastProfileEnabled ||
                !currentProfileState.hasEverSelectedProfile
            ) {
                return null
            }

            return profiles
                .find { it.profileIndex == ProfileRepository.activeProfileId }
                ?.takeUnless { it.pinEnabled }
        }

        fun requestProfileSwitch(profile: NuvioProfile, syncOnEnter: Boolean) {
            autoSkipProfileSelection = false
            pendingProfileSwitch = PendingProfileSwitch(profile, syncOnEnter)
            gateScreen = AppGateScreen.ProfileSwitching.name
        }

        fun enterProfileGate(profiles: List<NuvioProfile>, syncOnEnter: Boolean) {
            if (profiles.isEmpty()) {
                autoSkipProfileSelection = true
                gateScreen = AppGateScreen.ProfileSelection.name
                return
            }

            rememberedStartupProfile(profiles)?.let { profile ->
                requestProfileSwitch(profile, syncOnEnter)
                return
            }

            autoSkipProfileSelection = true
            if (profiles.size == 1) {
                val onlyProfile = profiles.first()
                if (onlyProfile.pinEnabled) {
                    gateScreen = AppGateScreen.ProfileSelection.name
                    return
                }
                requestProfileSwitch(onlyProfile, syncOnEnter)
            } else {
                gateScreen = AppGateScreen.ProfileSelection.name
            }
        }

        LaunchedEffect(gateScreen, pendingProfileSwitch) {
            if (gateScreen == AppGateScreen.ProfileSwitching.name && pendingProfileSwitch == null) {
                gateScreen = AppGateScreen.Loading.name
            }
        }

        LaunchedEffect(pendingProfileSwitch) {
            val request = pendingProfileSwitch ?: return@LaunchedEffect
            runCatching {
                ProfileRepository.switchToProfile(request.profile.profileIndex)
                warmProfileStartupRepositories()
                if (request.syncOnEnter) {
                    SyncManager.pullAllForProfile(request.profile.profileIndex)
                }
            }.onSuccess {
                pendingProfileSwitch = null
                autoSkipProfileSelection = false
                gateScreen = AppGateScreen.Main.name
            }.onFailure {
                pendingProfileSwitch = null
                autoSkipProfileSelection = false
                gateScreen = AppGateScreen.ProfileSelection.name
            }
        }

        LaunchedEffect(authState, networkStatusUiState.condition, profileState.profiles) {
            if (gateScreen == AppGateScreen.ProfileSwitching.name) return@LaunchedEffect

            val cachedProfiles = profileState.profiles
            val hasCachedProfileAccess =
                cachedProfiles.isNotEmpty() &&
                    authState !is AuthState.Authenticated
            // Signed-out users with cached profiles may only bypass the sign-in gate when the
            // app is provably offline (signing in is impossible anyway) or when they are
            // already past the gate (a session dying mid-use must not yank the UI away).
            // Anything else — including startup while the network probe is still Unknown —
            // waits for auth to resolve and lands on the sign-in screen when there is no
            // session. Previously the cached fast path won that race, so an expired/lost
            // session silently entered the app and the user never learned they were signed
            // out. "Continue without account" on the sign-in screen persists a local
            // anonymous account, which permanently opts out of this prompt.
            val pastGate = gateScreen == AppGateScreen.Main.name ||
                gateScreen == AppGateScreen.ProfileSelection.name ||
                gateScreen == AppGateScreen.ProfileEdit.name
            val allowCachedProfileAccess =
                hasCachedProfileAccess &&
                    (networkStatusUiState.isOfflineLike || pastGate)

            when (authState) {
                is AuthState.Loading -> {
                    if (allowCachedProfileAccess) {
                        enterProfileGate(cachedProfiles, syncOnEnter = false)
                    } else if (!pastGate) {
                        gateScreen = AppGateScreen.Loading.name
                    }
                }
                is AuthState.Unauthenticated -> {
                    if (allowCachedProfileAccess) {
                        enterProfileGate(cachedProfiles, syncOnEnter = false)
                    } else {
                        // Also covers signing out mid-session (profiles wiped, so no cached
                        // access): the user must land back on the auth gate, not stay inside.
                        ProfileRepository.clearInMemory()
                        gateScreen = AppGateScreen.Auth.name
                    }
                }
                is AuthState.Authenticated -> {
                    val authenticatedState = authState as AuthState.Authenticated
                    ProfileRepository.ensureLoaded(authenticatedState.userId)
                    if (gateScreen == AppGateScreen.Loading.name || gateScreen == AppGateScreen.Auth.name) {
                        // Warm startups now pass through here (they used to enter via the
                        // cached fast path with syncOnEnter=false); only a fresh sign-in from
                        // the auth gate should trigger the blocking full sync pull.
                        enterProfileGate(
                            ProfileRepository.state.value.profiles,
                            syncOnEnter = gateScreen == AppGateScreen.Auth.name,
                        )
                    }
                }
            }
        }

        LaunchedEffect((authState as? AuthState.Authenticated)?.userId) {
            val authenticatedState = authState as? AuthState.Authenticated ?: return@LaunchedEffect
            ProfileRepository.ensureLoaded(authenticatedState.userId)
            ProfileRepository.pullProfiles()
        }

        // Explicit "Sign In" request from deep inside Settings (see ReauthenticationTrigger):
        // drop cached-profile access and send the user to the auth gate instead of waiting for
        // authState to naturally flip, since a signed-out-but-cached user is otherwise kept on
        // the main app indefinitely.
        LaunchedEffect(Unit) {
            ReauthenticationTrigger.events.collect {
                ProfileRepository.clearInMemory()
                gateScreen = AppGateScreen.Auth.name
            }
        }

        LaunchedEffect(
            gateScreen,
            autoSkipProfileSelection,
            profileState.profiles,
            profileState.hasEverSelectedProfile,
            profileState.rememberLastProfileEnabled,
            profileState.activeProfile?.profileIndex,
            profileState.activeProfile?.pinEnabled,
        ) {
            if (
                autoSkipProfileSelection &&
                gateScreen == AppGateScreen.ProfileSelection.name
            ) {
                rememberedStartupProfile(profileState.profiles)?.let { profile ->
                    requestProfileSwitch(
                        profile = profile,
                        syncOnEnter = authState is AuthState.Authenticated,
                    )
                    return@LaunchedEffect
                }

                if (profileState.profiles.size != 1) return@LaunchedEffect

                val onlyProfile = profileState.profiles.first()
                if (onlyProfile.pinEnabled) return@LaunchedEffect

                requestProfileSwitch(
                    profile = onlyProfile,
                    syncOnEnter = authState is AuthState.Authenticated,
                )
            }
        }

        NuvioDesktopViewportDensityScaler(
            modifier = Modifier.fillMaxSize(),
            uiScalePercent = desktopAppUiScalePercent,
        ) {
            AnimatedContent(
                targetState = gateScreen,
                label = "app_gate",
                transitionSpec = {
                    (fadeIn(tween(400)) + scaleIn(tween(400), initialScale = 0.94f))
                        .togetherWith(fadeOut(tween(250)))
                },
            ) { currentGate ->
                when (currentGate) {
                    AppGateScreen.Loading.name,
                    AppGateScreen.ProfileSwitching.name -> {
                        AppLaunchOverlay(modifier = Modifier.fillMaxSize())
                    }
                    AppGateScreen.Auth.name -> {
                        AuthScreen(modifier = Modifier.fillMaxSize())
                    }
                    AppGateScreen.ProfileSelection.name -> {
                        PlatformBackHandler(enabled = gateScreen == AppGateScreen.ProfileSelection.name) {
                            if (!autoSkipProfileSelection) {
                                gateScreen = AppGateScreen.Main.name
                            }
                        }
                        ProfileSelectionScreen(
                            onProfileSelected = { profile ->
                                requestProfileSwitch(
                                    profile = profile,
                                    syncOnEnter = authState is AuthState.Authenticated,
                                )
                            },
                            onEditProfile = { profile ->
                                editingProfile = profile
                                isNewProfile = false
                                gateScreen = AppGateScreen.ProfileEdit.name
                            },
                            onAddProfile = {
                                editingProfile = null
                                isNewProfile = true
                                gateScreen = AppGateScreen.ProfileEdit.name
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    AppGateScreen.ProfileEdit.name -> {
                        PlatformBackHandler(enabled = gateScreen == AppGateScreen.ProfileEdit.name) {
                            gateScreen = AppGateScreen.ProfileSelection.name
                        }
                        ProfileEditScreen(
                            profile = editingProfile,
                            onBack = { gateScreen = AppGateScreen.ProfileSelection.name },
                            onSaved = { gateScreen = AppGateScreen.ProfileSelection.name },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    AppGateScreen.Main.name -> {
                        MainAppContent(
                            onSwitchProfile = {
                                autoSkipProfileSelection = false
                                gateScreen = AppGateScreen.ProfileSelection.name
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Navigates only if the current back stack entry is RESUMED, guarding against the
 * well-known double-navigation issue where a click registered just before/while a
 * pop transition is animating can fire navigate() twice (or navigate while the
 * destination is mid-disposal), leaving the AnimatedContent transition in an
 * inconsistent state and the window stuck rendering a black frame.
 */
private fun NavController.navigateIfResumed(route: Any) {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
        // launchSingleTop avoids stacking a second instance of the same destination
        // (e.g. the same details page) on top of itself, which would register
        // duplicate shared-element transition keys and freeze the renderer.
        navigate(route) { launchSingleTop = true }
    }
}

/**
 * Opens the player unless the player is already the current screen. Everything that starts playback
 * goes through here: a held Enter (or a controller's A, which repeats) fires a source pick many
 * times inside one transition, and each one used to push its own player — so Exit popped one copy
 * and revealed the next, reloading the same file every time.
 */
private fun NavController.navigateToPlayer(
    launch: PlayerLaunch,
    builder: NavOptionsBuilder.() -> Unit = {},
): Boolean {
    if (currentDestination?.hasRoute<PlayerRoute>() == true) return false
    navigate(PlayerRoute(launchId = PlayerLaunchStore.put(launch)), builder)
    return true
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun MainAppContent(
    onSwitchProfile: () -> Unit = {},
) {
        val navController = rememberNavController()
        val appUpdaterController = rememberAppUpdaterController()
        val hapticFeedback = LocalHapticFeedback.current
        val coroutineScope = rememberCoroutineScope()
        var selectedTab by rememberSaveable { mutableStateOf(AppScreenTab.Home) }
        var rootTabBackStack by remember { mutableStateOf<List<AppScreenTab>>(emptyList()) }
        var searchFocusRequestCount by remember { mutableStateOf(0) }
        var navigateToContentCount by remember { mutableStateOf(0) }
        var searchQuery by rememberSaveable { mutableStateOf("") }
        var submittedSearchQuery by rememberSaveable { mutableStateOf("") }
        var searchOverlayActive by rememberSaveable { mutableStateOf(false) }
        // The confident-match lookup for the latest submit; a newer submit replaces it.
        val confidentSearchJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
        val searchSubmitRequests = remember { MutableSharedFlow<String>(extraBufferCapacity = 1) }
        val homeScrollToTopRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
        val searchScrollToTopRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
        val libraryScrollToTopRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
        val discoverScrollToTopRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
        val settingsRootActionRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }

        LaunchedEffect(Unit) {
            warmProfileDeferredRepositories()
        }
        // Live TV plays in the built-in player only: the proxy relay and live reconnects are mpv's.
        LaunchedEffect(navController) {
            com.nuvio.app.features.iptv.IptvPlaybackRequests.requests.collect { launch ->
                navController.navigateToPlayer(launch)
            }
        }
        val uriHandler = LocalUriHandler.current
        LaunchedEffect(Unit) {
            openSimklDailyVisitIfDue(uriHandler::openUri)
        }
        val currentBackStackEntry by navController.currentBackStackEntryAsState()
        val streamPickerRequest by StreamPickerController.request.collectAsStateWithLifecycle()
        // The picker belongs to the screen that opened it; going anywhere else closes it. The
        // player is exempt only because playback start closes it itself, without clearing the
        // stream list the player's sources panel still reads.
        LaunchedEffect(currentBackStackEntry?.id, streamPickerRequest?.launchId) {
            val request = streamPickerRequest ?: return@LaunchedEffect
            val entry = currentBackStackEntry ?: return@LaunchedEffect
            if (entry.id == request.ownerEntryId) return@LaunchedEffect
            if (entry.destination.hasRoute<PlayerRoute>()) return@LaunchedEffect
            StreamPickerController.close()
        }
        // Closing a modal picker over the tabs leaves no focused node; hand focus back to the tab
        // content the way a returning route does. The details page reclaims its own.
        val streamPickerClosedCount by StreamPickerController.closedCount.collectAsStateWithLifecycle()
        LaunchedEffect(streamPickerClosedCount) {
            if (streamPickerClosedCount == 0) return@LaunchedEffect
            if (navController.currentDestination?.hasRoute<TabsRoute>() == true) {
                navigateToContentCount++
            }
        }
        var previousDestinationWasTabs by remember { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(currentBackStackEntry) {
            val destinationIsTabs = currentBackStackEntry?.destination?.hasRoute<TabsRoute>() == true
            if (
                destinationIsTabs &&
                previousDestinationWasTabs == false &&
                (
                    selectedTab == AppScreenTab.Home ||
                        selectedTab == AppScreenTab.Library ||
                        selectedTab == AppScreenTab.Discover
                    )
            ) {
                // Pushed routes (notably Calendar) can retain focus during their exit transition.
                // Tell the tab content to reclaim it once the Tabs destination becomes active.
                navigateToContentCount++
            }
            previousDestinationWasTabs = destinationIsTabs
        }
        var lastPlayerExitBackMark by remember { mutableStateOf<TimeMark?>(null) }
        BindDiscordBrowsingPresence(
            currentBackStackEntry = currentBackStackEntry,
            selectedTab = selectedTab,
            searchOverlayActive = searchOverlayActive,
            searchQuery = searchQuery,
            submittedSearchQuery = submittedSearchQuery,
        )
        LaunchedEffect(currentBackStackEntry?.id, selectedTab, searchOverlayActive) {
            if (!PerfTelemetry.enabled) return@LaunchedEffect
            val entry = currentBackStackEntry ?: return@LaunchedEffect
            PerfTelemetry.markScreen(perfScreenName(entry, selectedTab, searchOverlayActive))
        }
        LaunchedEffect(navController, selectedTab, rootTabBackStack, searchOverlayActive) {
            DesktopNavigationGestureBridge.backRequests.collect { source ->
                // Back leaves game mode rather than navigating the app hidden behind it. Read
                // straight off the flow: this collector is not re-keyed on the toggle.
                if (GameModeController.active.value) {
                    GameModeController.setActive(false)
                    return@collect
                }
                if (
                    StreamPickerController.isOpen &&
                    navController.currentDestination?.hasRoute<PlayerRoute>() != true
                ) {
                    StreamPickerController.close()
                    return@collect
                }
                // The native player WebView handles Mouse Back itself. On some Windows setups the
                // same physical click also reaches AWT; accepting both requests pops the player and
                // then the screen behind it. Ignore the duplicate while the player owns the input,
                // plus the short tail after its asynchronous native callback closes the route.
                if (
                    source == DesktopBackRequestSource.Mouse &&
                    navController.currentDestination?.hasRoute<PlayerRoute>() == true
                ) {
                    return@collect
                }
                if (
                    source == DesktopBackRequestSource.Mouse &&
                    lastPlayerExitBackMark?.elapsedNow()?.let {
                        it < PLAYER_BACK_DUPLICATE_WINDOW_MS.milliseconds
                    } == true
                ) {
                    return@collect
                }
                if (navController.currentDestination?.hasRoute<TabsRoute>() == true) {
                    when {
                        searchOverlayActive -> {
                            searchOverlayActive = false
                            navigateToContentCount++
                        }
                        rootTabBackStack.isNotEmpty() -> {
                            val target = rootTabBackStack.last()
                            rootTabBackStack = rootTabBackStack.dropLast(1)
                            selectedTab = target
                        }
                        selectedTab != AppScreenTab.Home -> {
                            selectedTab = AppScreenTab.Home
                        }
                    }
                } else if (navController.previousBackStackEntry != null) {
                    navController.popBackStack()
                }
            }
        }
        val liquidGlassNativeTabBarEnabled by remember {
            ThemeSettingsRepository.liquidGlassNativeTabBarEnabled
        }.collectAsStateWithLifecycle()
        val desktopNavigationLayout by remember {
            ThemeSettingsRepository.desktopNavigationLayout
        }.collectAsStateWithLifecycle()
        val desktopTopBarAlwaysVisible by remember {
            ThemeSettingsRepository.desktopTopBarAlwaysVisible
        }.collectAsStateWithLifecycle()
        val discoverTabVisible by remember {
            ThemeSettingsRepository.desktopDiscoverTabVisible
        }.collectAsStateWithLifecycle()
        val liveTvTabVisible = com.nuvio.app.features.iptv.rememberLiveTvTabVisible()
        val desktopAppUiScaleAppliesToDetails by remember {
            ThemeSettingsRepository.desktopAppUiScaleAppliesToDetails
        }.collectAsStateWithLifecycle()
        val liquidGlassNativeTabBarSupported = remember { isLiquidGlassNativeTabBarSupported() }
        var showExitConfirmation by rememberSaveable { mutableStateOf(false) }
        var selectedPosterActionTarget by remember { mutableStateOf<PosterActionTarget?>(null) }
        // The playlist popup open over the current screen, if any.
        var openPlaylistId by remember { mutableStateOf<String?>(null) }
        var selectedPosterAnchor by remember { mutableStateOf<PosterZoomAnchor?>(null) }
        val posterOverlayHazeState = rememberHazeState()
        var selectedContinueWatchingForActions by remember { mutableStateOf<ContinueWatchingItem?>(null) }
        var selectedContinueWatchingAnchor by remember { mutableStateOf<PosterZoomAnchor?>(null) }
        // Set when a right-click opened the menu: it then appears as a context menu at the cursor.
        var selectedPosterMenuPosition by remember { mutableStateOf<IntOffset?>(null) }
        var selectedContinueWatchingMenuPosition by remember { mutableStateOf<IntOffset?>(null) }
        var requestedSettingsPageName by rememberSaveable { mutableStateOf<String?>(null) }
        // The Library nav item's right-click shortcuts (any nav chrome) land on the local library
        // settings page; the browser variant is opened there by SettingsScreen.
        LaunchedEffect(Unit) {
            LibraryQuickAccess.requests.collect {
                requestedSettingsPageName = "LocalLibrary"
                selectedTab = AppScreenTab.Settings
            }
        }
        val gameModeActive by GameModeController.active.collectAsStateWithLifecycle()
        var showLibraryListPicker by remember { mutableStateOf(false) }
        var pickerItem by remember { mutableStateOf<LibraryItem?>(null) }
        var pickerTitle by remember { mutableStateOf("") }
        var pickerTabs by remember { mutableStateOf<List<TraktListTab>>(emptyList()) }
        var pickerMembership by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
        var pickerPending by remember { mutableStateOf(false) }
        var pickerError by remember { mutableStateOf<String?>(null) }
        val addonsUiState by AddonRepository.uiState.collectAsStateWithLifecycle()
    val libraryUiState by LibraryRepository.uiState.collectAsStateWithLifecycle()
    // Loaded by warmProfileDeferredRepositories on a background thread, not here: both loads open
    // a store file, and the downloads load used to stat every completed file on the library
    // drive — inside composition that put a sleeping HDD's spin-up on the UI thread at launch.
    // The flows are safe to collect before either repository has loaded (empty until then).
    val localLibraryUiState by LocalLibraryRepository.uiState.collectAsStateWithLifecycle()
    val downloadsUiState by DownloadsRepository.uiState.collectAsStateWithLifecycle()
        val authState by AuthRepository.state.collectAsStateWithLifecycle()
        val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val playerSettingsUiState by PlayerSettingsRepository.uiState.collectAsStateWithLifecycle()
    val homeCatalogSettingsUiState by HomeCatalogSettingsRepository.uiState.collectAsStateWithLifecycle()
    val p2pSettingsUiState by P2pSettingsRepository.uiState.collectAsStateWithLifecycle()
        val watchedUiState by WatchedRepository.uiState.collectAsStateWithLifecycle()
        val simklAuthUiState by SimklAuthRepository.uiState.collectAsStateWithLifecycle()
        val simklSettingsUiState by SimklSettingsRepository.uiState.collectAsStateWithLifecycle()
        val simklRewatchActionLabel = stringResource(Res.string.simkl_start_rewatch)
        val simklRewatchStartedLabel = stringResource(Res.string.simkl_rewatch_started)
        val simklRewatchFailedLabel = stringResource(Res.string.simkl_rewatch_failed)
    val networkStatusUiState by remember {
        NetworkStatusRepository.uiState
    }.collectAsStateWithLifecycle()
    val downloadedProviderLabel = stringResource(Res.string.provider_downloaded)
    val externalPlayerNotConfiguredText = stringResource(Res.string.external_player_not_configured)
    val externalPlayerUnavailableText = stringResource(Res.string.external_player_unavailable)
    val externalPlayerFailedText = stringResource(Res.string.external_player_failed)
    val cloudLibraryPlayFailedText = stringResource(Res.string.cloud_library_play_failed)
    val cloudLibraryPlayDisabledText = stringResource(Res.string.cloud_library_play_disabled)
    val cloudLibraryPlayNotConnectedText = stringResource(Res.string.cloud_library_play_not_connected)
    val isRemoteLibrarySource = libraryUiState.sourceMode != LibrarySourceMode.LOCAL
    var initialHomeReady by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialHomeReady) {
        if (initialHomeReady) com.nuvio.app.core.build.StartupReadySignal.notifyHomeReady()
    }
    var networkToastBaselineReady by rememberSaveable { mutableStateOf(false) }
    var lastNetworkToastCondition by rememberSaveable { mutableStateOf(NetworkCondition.Unknown.name) }

    fun handleRootTabClick(tab: AppScreenTab) {
        // Discover is hidden from the navigation: nothing may select it, or the bar would show no
        // active tab and leave no way back.
        if (tab == AppScreenTab.Discover && !discoverTabVisible) return
        if (tab == AppScreenTab.LiveTv && !liveTvTabVisible) return
        if (tab != AppScreenTab.Search) {
            searchOverlayActive = false
        }
        if (selectedTab != tab) {
            rootTabBackStack = rootTabHistoryAfterNavigation(
                history = rootTabBackStack,
                current = selectedTab,
                target = tab,
            )
            selectedTab = tab
            if (tab == AppScreenTab.Search) searchFocusRequestCount++
            return
        }

        when (tab) {
            AppScreenTab.Home -> homeScrollToTopRequests.tryEmit(Unit)
            AppScreenTab.Search -> {
                searchFocusRequestCount++
                searchScrollToTopRequests.tryEmit(Unit)
            }
            AppScreenTab.Library -> libraryScrollToTopRequests.tryEmit(Unit)
            AppScreenTab.Discover -> discoverScrollToTopRequests.tryEmit(Unit)
            AppScreenTab.LiveTv -> Unit
            AppScreenTab.Settings -> settingsRootActionRequests.tryEmit(Unit)
        }
    }

    // The last playlist removed while the tab is open: same as hiding Discover.
    LaunchedEffect(liveTvTabVisible) {
        if (liveTvTabVisible) return@LaunchedEffect
        rootTabBackStack = rootTabBackStack.filterNot { it == AppScreenTab.LiveTv }
        if (selectedTab == AppScreenTab.LiveTv) selectedTab = AppScreenTab.Home
    }

    // Hiding the tab while it is open would leave an active tab with no entry in the bar, and its
    // history entry would let Back walk right back into it.
    LaunchedEffect(discoverTabVisible) {
        if (discoverTabVisible) return@LaunchedEffect
        rootTabBackStack = rootTabBackStack.filterNot { it == AppScreenTab.Discover }
        if (selectedTab == AppScreenTab.Discover) selectedTab = AppScreenTab.Home
    }

    fun openSearchOverlay() {
        searchOverlayActive = true
        searchFocusRequestCount++
    }

    fun dismissSearchOverlay() {
        searchOverlayActive = false
        if (selectedTab == AppScreenTab.Search) {
            selectedTab = AppScreenTab.Home
        }
        navigateToContentCount++
    }

    // The collector below outlives compositions; the click handler it calls must be the current
    // one, or a tab shown since launch (Discover, Live TV) would still look hidden to it.
    val latestRootTabClick = rememberUpdatedState<(AppScreenTab) -> Unit>(::handleRootTabClick)
    LaunchedEffect(navController) {
        AppShortcutBridge.events.collect { action ->
            if (navController.currentDestination?.hasRoute<PlayerRoute>() == true) return@collect
            when (action) {
                AppShortcutAction.GoHome,
                AppShortcutAction.OpenSearch,
                AppShortcutAction.OpenDiscover,
                AppShortcutAction.OpenLiveTv,
                AppShortcutAction.OpenLibrary -> {
                    // Live TV only exists once a playlist does; the key then does nothing.
                    if (action == AppShortcutAction.OpenLiveTv &&
                        com.nuvio.app.features.iptv.IptvRepository.settings.value.playlists.isEmpty()
                    ) {
                        return@collect
                    }
                    navController.navigate(TabsRoute) {
                        popUpTo<TabsRoute> { inclusive = false }
                        launchSingleTop = true
                    }
                    val target = when (action) {
                        AppShortcutAction.GoHome -> AppScreenTab.Home
                        AppShortcutAction.OpenSearch -> AppScreenTab.Search
                        AppShortcutAction.OpenLibrary -> if (selectedTab == AppScreenTab.Library) {
                            AppScreenTab.Home
                        } else {
                            AppScreenTab.Library
                        }
                        AppShortcutAction.OpenDiscover -> if (selectedTab == AppScreenTab.Discover) {
                            AppScreenTab.Home
                        } else {
                            AppScreenTab.Discover
                        }
                        AppShortcutAction.OpenLiveTv -> if (selectedTab == AppScreenTab.LiveTv) {
                            AppScreenTab.Home
                        } else {
                            AppScreenTab.LiveTv
                        }
                        else -> AppScreenTab.Home
                    }
                    latestRootTabClick.value(target)
                }
                AppShortcutAction.OpenCalendar -> navController.navigateIfResumed(CalendarRoute)
                AppShortcutAction.ToggleGameMode -> GameModeController.toggle()
                else -> Unit
            }
        }
    }

    LaunchedEffect(liquidGlassNativeTabBarSupported, liquidGlassNativeTabBarEnabled) {
        NativeTabBridge.requestedTabs.collectLatest { requestedTab ->
            if (liquidGlassNativeTabBarSupported && liquidGlassNativeTabBarEnabled) {
                handleRootTabClick(requestedTab.toAppScreenTab())
            }
        }
    }

    LaunchedEffect(selectedTab) {
        NativeTabBridge.publishSelectedTab(selectedTab.toNativeNavigationTab())
        if (selectedTab != AppScreenTab.Search) {
            searchFocusRequestCount = 0
        }
    }

    // Pause/reset the TV hero-trailer dwell timer whenever no home-style screen is the
    // foreground screen (Settings tab, or a details/player route pushed over the tabs).
    // Search, Library, and a collection folder all render the same TV Mode / Adaptive Hero
    // hero as Home, so they count as "home" here too — otherwise their hero trailers could
    // never play.
    // The startup overlays (setup wizard, API-key prompt, updater) are the same kind of cover: a
    // centred sheet the native trailer canvas would paint straight through, while the user is
    // still reading it. Closing one restarts the dwell timer from zero.
    val startupOverlayVisible by StartupOverlayCoordinator.visibleOverlay
        .collectAsStateWithLifecycle()
    // The source picker's modal is the same again whenever it opens over a home-style tab.
    val streamPickerOpen by remember {
        StreamPickerController.request.map { it != null }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = StreamPickerController.isOpen)
    LaunchedEffect(selectedTab, currentBackStackEntry, gameModeActive, startupOverlayVisible, streamPickerOpen) {
        val onTabsWithHomeStyleTab = (
            selectedTab == AppScreenTab.Home ||
                selectedTab == AppScreenTab.Search ||
                selectedTab == AppScreenTab.Discover ||
                selectedTab == AppScreenTab.Library
            ) &&
            navController.currentDestination?.hasRoute<TabsRoute>() == true
        val onFolderDetail = navController.currentDestination?.hasRoute<FolderDetailRoute>() == true
        // Game mode covers the home screen, and the desktop trailer surface is a native canvas
        // that would paint straight over it — so the hero must count as "not home" while it is up.
        HomeHeroTrailerGate.setHomeActive(
            (onTabsWithHomeStyleTab || onFolderDetail) && !gameModeActive && startupOverlayVisible == null &&
                !streamPickerOpen,
        )
    }

    // Game mode's library takes keyboard focus while it is up, and Compose drops focus entirely
    // when that focusable is removed on exit: with no focused node, key events never reach the
    // window-level shortcut handler, so G (and every other global shortcut) is dead until
    // something focusable is clicked. Hand focus back to the tab content the way a returning
    // route does.
    var gameModeWasActive by remember { mutableStateOf(gameModeActive) }
    LaunchedEffect(gameModeActive) {
        if (gameModeWasActive && !gameModeActive) {
            navigateToContentCount++
        }
        gameModeWasActive = gameModeActive
    }

    DisposableEffect(
        navController,
        liquidGlassNativeTabBarSupported,
        liquidGlassNativeTabBarEnabled,
        initialHomeReady,
    ) {
        fun publishNativeTabVisibilityForCurrentRoute() {
            val visible = liquidGlassNativeTabBarSupported &&
                liquidGlassNativeTabBarEnabled &&
                initialHomeReady &&
                navController.currentDestination?.hasRoute<TabsRoute>() == true
            NativeTabBridge.publishTabBarVisible(visible)
        }

        val destinationChangedListener = NavController.OnDestinationChangedListener { _, _, _ ->
            publishNativeTabVisibilityForCurrentRoute()
        }

        publishNativeTabVisibilityForCurrentRoute()
        navController.addOnDestinationChangedListener(destinationChangedListener)
        onDispose {
            navController.removeOnDestinationChangedListener(destinationChangedListener)
            NativeTabBridge.publishTabBarVisible(false)
        }
    }

    LaunchedEffect(Unit) {
        NetworkStatusRepository.ensureStarted()
        EpisodeReleaseNotificationsRepository.refreshAsync()
        kotlinx.coroutines.delay(5_000)
        initialHomeReady = true
    }

    LaunchedEffect(Unit) {
        AppForegroundMonitor.events().collect {
            NetworkStatusRepository.requestForegroundRefresh()
        }
    }

    LaunchedEffect(networkStatusUiState.condition) {
        val condition = networkStatusUiState.condition
        if (!networkToastBaselineReady) {
            networkToastBaselineReady = true
            lastNetworkToastCondition = condition.name
            return@LaunchedEffect
        }

        val previousConditionName = lastNetworkToastCondition
        if (previousConditionName == condition.name) return@LaunchedEffect

        when (condition) {
            NetworkCondition.NoInternet -> {
                NuvioToastController.show(getString(Res.string.network_no_internet_connection))
            }

            NetworkCondition.ServersUnreachable -> {
                NuvioToastController.show(getString(Res.string.network_cannot_reach_servers))
            }

            NetworkCondition.Online -> {
                if (
                    previousConditionName == NetworkCondition.NoInternet.name ||
                    previousConditionName == NetworkCondition.ServersUnreachable.name
                ) {
                    NuvioToastController.show(getString(Res.string.network_back_online))
                }
            }

            NetworkCondition.Unknown,
            NetworkCondition.Checking,
            -> Unit
        }

        lastNetworkToastCondition = condition.name
    }

    LaunchedEffect(authState, profileState.activeProfile?.profileIndex) {
        val authenticatedState = authState as? AuthState.Authenticated ?: return@LaunchedEffect
        if (authenticatedState.isAnonymous) return@LaunchedEffect

        val activeProfileId = profileState.activeProfile?.profileIndex ?: return@LaunchedEffect
        AppForegroundMonitor.events().collect {
            SyncManager.requestForegroundPull(activeProfileId, force = true)
        }
    }

    // Separate from the pull above, and deliberately not auth-gated: a connected tracking service
    // is the user's own account, unrelated to whether they signed into Nuvio. `requestForegroundPull`
    // returns early for anonymous and signed-out users and only ever reads Nuvio-account state, so
    // for anyone running SIMKL, Trakt or MDBList as their Continue Watching source it does nothing
    // at all.
    LaunchedEffect(Unit) {
        AppForegroundMonitor.events().collect {
            runCatching { WatchProgressRepository.refreshContinueWatchingOnForeground() }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    appStartupLog.w(error) { "Continue Watching foreground refresh failed" }
                }
        }
    }
    var profileSwitchLoading by remember { mutableStateOf(false) }
    var resumePromptItem by remember { mutableStateOf<ContinueWatchingItem?>(null) }
    // Session-scoped (survives tab switches, unlike Home-local state): the TV-Mode dismissal of the
    // continue-watching resume hero. Reset only when playback starts or the session ends.
    var continueWatchingHeroDismissed by remember { mutableStateOf(false) }
    var lastExternalPlayerLaunch by remember { mutableStateOf<PlayerLaunch?>(null) }
    val launchExternalPlayer = rememberExternalPlayerLauncher { result ->
        if (result != null && result.positionMs > 0L) {
            coroutineScope.launch {
                val durationMs = result.durationMs
                val progressPercent = if (durationMs != null && durationMs > 0L) {
                    (result.positionMs.toFloat() / durationMs.toFloat() * 100f).coerceIn(0f, 100f)
                } else {
                    null
                }
                if (progressPercent != null) {
                    // Previously Trakt-only, so an external-player watch never reached SIMKL. Going
                    // through the coordinator fans it out to every connected provider, which is the
                    // behaviour the in-app player already had.
                    val media = buildTrackingMediaReference(
                        contentType = lastExternalPlayerLaunch?.parentMetaType ?: "",
                        parentMetaId = lastExternalPlayerLaunch?.parentMetaId ?: "",
                        videoId = lastExternalPlayerLaunch?.videoId,
                        title = lastExternalPlayerLaunch?.title,
                        seasonNumber = lastExternalPlayerLaunch?.seasonNumber,
                        episodeNumber = lastExternalPlayerLaunch?.episodeNumber,
                        episodeTitle = lastExternalPlayerLaunch?.episodeTitle,
                    )
                    runCatching {
                        TrackingScrobbleCoordinator.scrobble(
                            profileId = ProfileRepository.activeProfileId,
                            action = TrackingScrobbleAction.STOP,
                            event = TrackingScrobbleEvent(
                                media = media,
                                progressPercent = progressPercent.toDouble(),
                            ),
                        )
                    }
                }
                lastExternalPlayerLaunch?.let { playerLaunch ->
                    val session = WatchProgressPlaybackSession(
                        contentType = playerLaunch.contentType ?: playerLaunch.parentMetaType,
                        parentMetaId = playerLaunch.parentMetaId,
                        parentMetaType = playerLaunch.parentMetaType,
                        videoId = playerLaunch.videoId ?: playerLaunch.parentMetaId,
                        title = playerLaunch.title,
                        logo = playerLaunch.logo,
                        poster = playerLaunch.poster,
                        background = playerLaunch.background,
                        seasonNumber = playerLaunch.seasonNumber,
                        episodeNumber = playerLaunch.episodeNumber,
                        episodeTitle = playerLaunch.episodeTitle,
                        episodeThumbnail = playerLaunch.episodeThumbnail,
                        providerName = playerLaunch.providerName,
                        providerAddonId = playerLaunch.providerAddonId,
                        lastStreamTitle = playerLaunch.streamTitle,
                        lastSourceUrl = playerLaunch.sourceUrl,
                    )
                    val snapshot = PlayerPlaybackSnapshot(
                        isLoading = false,
                        isPlaying = false,
                        isEnded = !result.endedByUser,
                        durationMs = durationMs ?: 0L,
                        positionMs = result.positionMs,
                    )
                    WatchProgressRepository.upsertPlaybackProgress(
                        session = session,
                        snapshot = snapshot,
                    )
                }
            }
        }
    }
    val continueWatchingPreferencesUiState by ContinueWatchingPreferencesRepository.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(
        initialHomeReady,
        profileSwitchLoading,
        profileState.activeProfile?.profileIndex,
        continueWatchingPreferencesUiState.showResumePromptOnLaunch,
    ) {
        if (!initialHomeReady || profileSwitchLoading) return@LaunchedEffect
        // Before the prompt consumes the unclean-exit flags: re-send the scrobble stop a force-close
        // swallowed, so a remote Continue Watching source keeps the item.
        if (ResumePromptRepository.recoverUncleanPlayerExit()) {
            launch {
                runCatching {
                    WatchProgressRepository.forceContinueWatchingSync(
                        ProfileRepository.activeProfileId,
                        rereadHistoryInFull = false,
                    )
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    appStartupLog.e(error) { "Continue Watching resync after unclean exit failed" }
                }
            }
        }
        if (resumePromptItem != null) return@LaunchedEffect
        if (continueWatchingPreferencesUiState.showResumePromptOnLaunch) {
            if (com.nuvio.app.features.party.PartyInvites.latest.value == null) {
                resumePromptItem = ResumePromptRepository.consumeResumePrompt()
            }
        }
    }

    LaunchedEffect(currentBackStackEntry?.destination) {
        val inPlaybackFlow = StreamPickerController.isOpen ||
            currentBackStackEntry?.destination?.hasRoute<PlayerRoute>() == true
        if (inPlaybackFlow) {
            resumePromptItem = null
        }
        // Entering the player counts as "playback" — clear any TV-Mode dismissal so the
        // continue-watching resume hero returns for the next home visit.
        if (currentBackStackEntry?.destination?.hasRoute<PlayerRoute>() == true) {
            continueWatchingHeroDismissed = false
        }
    }

        LaunchedEffect(navController) {
            AppDeepLinkRepository.pendingDeepLink.collectLatest { deepLink ->
                when (deepLink) {
                    is AppDeepLink.Meta -> {
                        selectedTab = AppScreenTab.Home
                        navController.navigate(DetailRoute(type = deepLink.type, id = deepLink.id)) {
                            launchSingleTop = true
                        }
                        AppDeepLinkRepository.markConsumed(deepLink)
                    }

                    AppDeepLink.Downloads -> {
                        if (AppFeaturePolicy.downloadsEnabled) {
                            requestedSettingsPageName = "Downloads"
                            selectedTab = AppScreenTab.Settings
                        }
                        AppDeepLinkRepository.markConsumed(deepLink)
                    }

                    is AppDeepLink.Party -> {
                        // Upstream has a party route; here the page lives in Settings.
                        com.nuvio.app.features.party.PartyInvites.open(deepLink.code)
                        requestedSettingsPageName = "WatchParty"
                        selectedTab = AppScreenTab.Settings
                        AppDeepLinkRepository.markConsumed(deepLink)
                    }

                    null -> Unit
                }
            }
        }

        // Direct media open: local file drag/drop or an HTTP(S) stream pasted with Ctrl+V.
        if (isDesktop) {
            LaunchedEffect(navController) {
                LocalFileDrop.events.collect { mediaUri ->
                    // Web stream paste is deliberately unavailable during playback.
                    if (navController.currentDestination?.hasRoute<PlayerRoute>() == true) {
                        return@collect
                    }
                    val isWebStream = mediaUri.startsWith("https://", ignoreCase = true) ||
                        mediaUri.startsWith("http://", ignoreCase = true)
                    val provider = if (isWebStream) "Web Stream" else "Local File"
                    val fallbackTitle = if (isWebStream) "Web Stream" else "Local File"

                    // Cloud/debrid links commonly use an opaque object id as the path segment and
                    // carry the real release name in a `filename=` query param, so recover the name
                    // and run it through the local-library filename parser for a human title (+ the
                    // season/episode coordinates that give the player HUD and Discord presence a
                    // proper episode line) instead of surfacing the raw id.
                    val rawName = directPlayMediaName(mediaUri, isWebStream)
                    val parsedEpisode = rawName?.let { FilenameParser.parseEpisode(it) }
                    val hasEpisode = parsedEpisode?.episode != null
                    val parsedTitle = rawName?.let { FilenameParser.parseTitle(it) }
                    val movieTitle = parsedTitle?.title?.takeIf { it.isNotBlank() }
                    val displayTitle = when {
                        hasEpisode -> parsedEpisode?.showTitle?.takeIf { it.isNotBlank() }
                            ?: movieTitle
                        else -> movieTitle
                    } ?: fallbackTitle
                    val streamLabel = rawName
                        ?.let { FilenameParser.cleanTitle(it) }
                        ?.takeIf { it.isNotBlank() }
                        ?: displayTitle

                    val playerLaunch = PlayerLaunch(
                        title = displayTitle,
                        sourceUrl = mediaUri,
                        streamTitle = streamLabel,
                        providerName = provider,
                        parentMetaId = "",
                        parentMetaType = if (hasEpisode) "series" else "movie",
                        seasonNumber = parsedEpisode?.season,
                        episodeNumber = parsedEpisode?.episode,
                        episodeTitle = parsedEpisode?.episodeTitle?.takeIf { it.isNotBlank() },
                        // Movie year disambiguates the artwork lookup (remakes); series filenames
                        // rarely carry a reliable year, so only thread it for the movie path.
                        releaseYear = if (hasEpisode) null else parsedTitle?.year,
                        disableProgressTracking = true,
                    )
                    val launchId = PlayerLaunchStore.put(playerLaunch)
                    navController.navigate(PlayerRoute(launchId = launchId)) {
                        launchSingleTop = true
                    }
                }
            }
        }

        suspend fun openExternalPlayback(launch: PlayerLaunch): Boolean {
            lastExternalPlayerLaunch = launch

            // Persist binge group for subsequent episode plays (same as internal player)
            val bingeGroup = launch.bingeGroup
            if (bingeGroup != null && launch.parentMetaId.isNotBlank()) {
                BingeGroupCacheRepository.save(launch.parentMetaId, bingeGroup)
            }

            val baseRequest = launch.toExternalPlayerPlaybackRequest()
            // The same question the launch itself asks, so the overlay is shown exactly when there
            // is a fetch to wait for — a secondary language alone is reason enough, and neither
            // "Device language" nor "Original" is a language until it has been resolved.
            val externalSubtitleLanguages = externalPlayerSubtitleTargets(
                settings = playerSettingsUiState,
                originalLanguage = OriginalLanguageCache.languageFor(launch.parentMetaId),
            )
            // The forwarding switch is only shown in External mode. Reaching here in Internal mode
            // means a one-off "Open in external player" from a stream's menu, where the hidden
            // (default-off) switch would otherwise silently drop every subtitle.
            val forwardSubtitles = !playerSettingsUiState.externalPlayerEnabled ||
                playerSettingsUiState.externalPlayerForwardSubtitles
            val shouldForwardSubtitles = forwardSubtitles && externalSubtitleLanguages.isNotEmpty()
            if (shouldForwardSubtitles) {
                StreamsRepository.setOverlayVisible(true, getString(Res.string.streams_loading_subtitles))
            }
            val enrichedRequest = prepareExternalPlayerLaunch(
                request = baseRequest,
                type = launch.contentType ?: launch.parentMetaType,
                videoId = launch.videoId ?: launch.parentMetaId,
                forwardSubtitles = forwardSubtitles,
                settings = playerSettingsUiState,
                originalLanguage = OriginalLanguageCache.languageFor(launch.parentMetaId),
                onOverlayMessage = { _ -> },
            )
            StreamsRepository.setOverlayVisible(false)
            return when (
                val intentResult = ExternalPlayerPlatform.buildIntent(
                    request = enrichedRequest,
                    playerId = playerSettingsUiState.externalPlayerId,
                )
            ) {
                is ExternalPlayerIntentResult.Success -> {
                    val launched = launchExternalPlayer(intentResult)
                    if (!launched) {
                        NuvioToastController.show(externalPlayerFailedText)
                    }
                    launched
                }
                ExternalPlayerIntentResult.NotConfigured -> {
                    NuvioToastController.show(externalPlayerNotConfiguredText)
                    false
                }
                ExternalPlayerIntentResult.Failed -> {
                    NuvioToastController.show(externalPlayerFailedText)
                    false
                }
            }
        }

        suspend fun launchCloudLibraryFile(
            item: CloudLibraryItem,
            file: CloudLibraryFile,
            resumePositionMs: Long? = null,
            resumeProgressFraction: Float? = null,
            startFromBeginning: Boolean = false,
        ): Boolean {
            return when (
                val resolved = CloudLibraryRepository.resolvePlayback(
                    item = item,
                    file = file,
                )
            ) {
                is CloudLibraryPlaybackResult.Success -> {
                    val playbackTitle = resolved.filename
                        ?.takeIf { it.isNotBlank() }
                        ?: file.name.ifBlank { item.name }
                    val playerLaunch = PlayerLaunch(
                        title = playbackTitle,
                        sourceUrl = resolved.url,
                        streamTitle = playbackTitle,
                        streamSubtitle = item.name.takeIf { it != playbackTitle },
                        providerName = item.providerName,
                        providerAddonId = "cloud:${item.providerId}",
                        poster = item.providerPosterUrl(),
                        contentType = CloudLibraryContentType,
                        videoId = item.playbackVideoId(file),
                        parentMetaId = item.stableKey,
                        parentMetaType = CloudLibraryContentType,
                        initialPositionMs = if (startFromBeginning) 0L else (resumePositionMs ?: 0L),
                        initialProgressFraction = if (startFromBeginning) null else resumeProgressFraction,
                    )
                    if (playerSettingsUiState.externalPlayerEnabled) {
                        openExternalPlayback(playerLaunch)
                        true
                    } else {
                        navController.navigateToPlayer(playerLaunch)
                        true
                    }
                }

                else -> false
            }
        }

        fun launchPlaybackWithDownloadPreference(
            type: String,
            videoId: String,
            parentMetaId: String,
            parentMetaType: String,
            title: String,
            logo: String?,
            poster: String?,
            background: String?,
            seasonNumber: Int?,
            episodeNumber: Int?,
            episodeTitle: String?,
            episodeThumbnail: String?,
            pauseDescription: String?,
            resumePositionMs: Long?,
            resumeProgressFraction: Float?,
            useAlternateBehavior: Boolean,
            startFromBeginning: Boolean,
            watchProgressSource: String? = null,
            streamVideoId: String? = null,
            disableProgressTracking: Boolean = false,
            autoPlayMode: PlayerAutoPlayMode = PlayerAutoPlayMode.NextEpisode,
            // A playlist's own "Prefer local files": true plays a local copy when there is one,
            // false ignores local copies and searches streams. Null follows the app-wide setting.
            preferLocalOverride: Boolean? = null,
            // Watch party: always open the source picker (which marks the party's source) rather
            // than auto-playing a stream or a downloaded copy that may be a different cut.
            forceManualSelection: Boolean = false,
            // Continue Watching's source shortcut: replay the cached last link even with Reuse
            // Last Link off.
            forceReuseLastLink: Boolean = false,
        ) {
            val targetResumePositionMs = if (startFromBeginning) 0L else (resumePositionMs ?: 0L)
            val targetResumeProgressFraction = if (startFromBeginning) null else resumeProgressFraction
            // Free, synchronous, and now reads every in-memory store that knows a year rather than
            // only the details-screen cache — see ReleaseYearResolver. Still best-effort here: a
            // genuinely cold title resolves to null and the stream screen fills it in behind
            // playback, before the download menu can be opened.
            val releaseYear = ReleaseYearResolver.peek(parentMetaType, parentMetaId)
                ?: ReleaseYearResolver.parseReleaseYear(
                    MetaDetailsRepository.uiState.value.meta
                        ?.takeIf { it.id == parentMetaId }
                        ?.releaseInfo,
                )

            PlaybackStartTrace.beginPending("playClicked type=$type id=$videoId")

            // Read the repository at the moment of the click. A details destination can outlive the
            // composition that created its callbacks; using its captured UI snapshot meant changing
            // this setting to Source picker could still leave that destination opening local files.
            val configuredBehavior = if (preferLocalOverride == true) {
                LocalLibraryPlaybackPreference.LOCAL_LIBRARY
            } else {
                LocalLibraryRepository.currentPlaybackPreference()
            }
            // The download lookup stats the file, and downloads live on the library drive: on a
            // spun-down HDD that first stat waits out the spin-up, which froze the UI on the click.
            // Resolve it on IO; the rest of the launch continues back on the UI thread.
            coroutineScope.launch {
                val (downloadedItem, localSourceUrl) = withContext(Dispatchers.IO) {
                    val item = if (AppFeaturePolicy.downloadsEnabled && preferLocalOverride != false) {
                        DownloadsRepository.findPlayableDownload(
                            parentMetaId = parentMetaId,
                            seasonNumber = seasonNumber,
                            episodeNumber = episodeNumber,
                            videoId = videoId,
                        )
                    } else {
                        null
                    }
                    item to item?.let(DownloadsRepository::playableLocalFileUri)
                }
                val hasLocalLibraryStream = AppFeaturePolicy.downloadsEnabled && preferLocalOverride != false &&
                    LocalLibraryRepository.localStreamsFor(parentMetaId, videoId).isNotEmpty()
                val localRouting = configuredBehavior.resolvePlaybackRouting(
                    useAlternate = useAlternateBehavior,
                    hasDownloadedFile = !localSourceUrl.isNullOrBlank(),
                    hasLocalLibraryStream = hasLocalLibraryStream,
                )
                val prefersLocalStreams = localRouting.preferLocalStreams
                val manualSelection = localRouting.manualSelection || forceManualSelection
                PlaybackStartTrace.markPending(
                    "localRoute configured=$configuredBehavior requested=${localRouting.requestedBehavior} " +
                        "downloaded=${!localSourceUrl.isNullOrBlank()} scanned=$hasLocalLibraryStream " +
                        "manual=$manualSelection",
                )

                if (localRouting.playDownloadedFileDirectly && downloadedItem != null && !forceManualSelection) {
                    if (!localSourceUrl.isNullOrBlank()) {
                        val downloadedLabels = downloadedItem.playbackLabels(
                            localPath = localSourceUrl,
                            fallbackTitle = title,
                            downloadedLabel = downloadedProviderLabel,
                        )
                        val playerLaunch = PlayerLaunch(
                                title = title,
                                sourceUrl = localSourceUrl,
                                sourceHeaders = emptyMap(),
                                sourceResponseHeaders = emptyMap(),
                                logo = logo,
                                poster = poster,
                                background = background,
                                seasonNumber = seasonNumber,
                                episodeNumber = episodeNumber,
                                episodeTitle = episodeTitle,
                                episodeThumbnail = episodeThumbnail,
                                releaseYear = releaseYear,
                                streamTitle = downloadedLabels.streamTitle,
                                streamSubtitle = downloadedLabels.streamSubtitle,
                                pauseDescription = pauseDescription,
                                providerName = downloadedLabels.providerName,
                                providerAddonId = downloadedItem.providerAddonId,
                                contentType = type,
                                videoId = videoId,
                                parentMetaId = parentMetaId,
                                parentMetaType = parentMetaType,
                                watchProgressSource = watchProgressSource,
                                initialPositionMs = targetResumePositionMs,
                                initialProgressFraction = targetResumeProgressFraction,
                                disableProgressTracking = disableProgressTracking,
                                autoPlayMode = autoPlayMode,
                            )
                        if (playerSettingsUiState.externalPlayerEnabled) {
                            coroutineScope.launch { openExternalPlayback(playerLaunch) }
                            return@launch
                        }
                        navController.navigateToPlayer(playerLaunch)
                        return@launch
                    }
                }

                StreamPickerController.open(
                    launch = StreamLaunch(
                        type = type,
                        videoId = videoId,
                        streamVideoId = streamVideoId,
                        parentMetaId = parentMetaId,
                        parentMetaType = parentMetaType,
                        watchProgressSource = watchProgressSource,
                        title = title,
                        logo = logo,
                        poster = poster,
                        background = background,
                        releaseYear = releaseYear,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        episodeTitle = episodeTitle,
                        episodeThumbnail = episodeThumbnail,
                        pauseDescription = pauseDescription,
                        resumePositionMs = if (startFromBeginning) 0L else resumePositionMs,
                        resumeProgressFraction = targetResumeProgressFraction,
                        manualSelection = manualSelection,
                        reuseLastLink = forceReuseLastLink,
                        preferLocalStreams = prefersLocalStreams,
                        startFromBeginning = startFromBeginning,
                        disableProgressTracking = disableProgressTracking,
                        autoPlayMode = autoPlayMode,
                        sourceAffinity = if (prefersLocalStreams) {
                            PlayerSourceAffinity.Local
                        } else {
                            PlayerSourceAffinity.Stream
                        },
                    ),
                    ownerEntryId = navController.currentBackStackEntry?.id,
                )
                PlaybackStartTrace.markPending("open:streamPicker")
            }
        }

        /**
         * Watch party: open what the party is watching, starting where the party is now. Called
         * from the Watch party page and from the player when the party moves to another title.
         */
        fun openPartyMedia(media: com.nuvio.app.features.party.PartyMedia) {
            if (navController.currentBackStackEntry?.destination?.hasRoute<PlayerRoute>() == true) {
                navController.popBackStack()
            }
            val resumePositionMs = com.nuvio.app.features.party.PartyRepository.playback.value
                ?.takeIf { it.mediaKey == null || it.mediaKey == media.key }
                ?.let {
                    com.nuvio.app.features.party.PartySync.expectedPositionMs(
                        it,
                        com.nuvio.app.features.party.PartyRepository.nowMs(),
                    )
                }
            launchPlaybackWithDownloadPreference(
                type = media.type,
                videoId = media.videoId ?: media.metaId,
                parentMetaId = media.metaId,
                parentMetaType = media.metaType,
                title = media.title,
                logo = media.logo,
                poster = media.poster,
                background = media.background,
                seasonNumber = media.season,
                episodeNumber = media.episode,
                episodeTitle = media.episodeTitle,
                episodeThumbnail = null,
                pauseDescription = media.description,
                resumePositionMs = resumePositionMs,
                resumeProgressFraction = null,
                useAlternateBehavior = false,
                startFromBeginning = resumePositionMs == null,
                forceManualSelection = true,
            )
        }

        // Party notices for whoever is not in the player (which shows its own in the HUD).
        LaunchedEffect(Unit) {
            com.nuvio.app.features.party.PartyRepository.events.collect { event ->
                if (navController.currentBackStackEntry?.destination?.hasRoute<PlayerRoute>() == true) {
                    return@collect
                }
                val message = when (event) {
                    is com.nuvio.app.features.party.PartyEvent.MemberJoined ->
                        getString(Res.string.party_member_joined, event.name)
                    is com.nuvio.app.features.party.PartyEvent.MemberLeft ->
                        getString(Res.string.party_member_left, event.name)
                    com.nuvio.app.features.party.PartyEvent.Removed -> getString(Res.string.party_removed)
                    com.nuvio.app.features.party.PartyEvent.Ended -> getString(Res.string.party_ended)
                    is com.nuvio.app.features.party.PartyEvent.MediaChanged ->
                        getString(Res.string.party_media_changed, event.media.title)
                    is com.nuvio.app.features.party.PartyEvent.ControlChanged -> getString(
                        if (event.hostOnly) Res.string.party_control_host_only else Res.string.party_control_everyone,
                    )
                }
                NuvioToastController.show(message)
            }
        }

        val onPlay: (String, String, String, String, String, String?, String?, String?, Int?, Int?, String?, String?, String?, Long?) -> Unit =
            { type, videoId, parentMetaId, parentMetaType, title, logo, poster, background, seasonNumber, episodeNumber, episodeTitle, episodeThumbnail, pauseDescription, resumePositionMs ->
                launchPlaybackWithDownloadPreference(
                    type = type,
                    videoId = videoId,
                    parentMetaId = parentMetaId,
                    parentMetaType = parentMetaType,
                    title = title,
                    logo = logo,
                    poster = poster,
                    background = background,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    episodeTitle = episodeTitle,
                    episodeThumbnail = episodeThumbnail,
                    pauseDescription = pauseDescription,
                    resumePositionMs = resumePositionMs,
                    resumeProgressFraction = null,
                    useAlternateBehavior = false,
                    startFromBeginning = false,
                )
            }

        val onPlayAlternate: (String, String, String, String, String, String?, String?, String?, Int?, Int?, String?, String?, String?, Long?) -> Unit =
            { type, videoId, parentMetaId, parentMetaType, title, logo, poster, background, seasonNumber, episodeNumber, episodeTitle, episodeThumbnail, pauseDescription, resumePositionMs ->
                launchPlaybackWithDownloadPreference(
                    type = type,
                    videoId = videoId,
                    parentMetaId = parentMetaId,
                    parentMetaType = parentMetaType,
                    title = title,
                    logo = logo,
                    poster = poster,
                    background = background,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    episodeTitle = episodeTitle,
                    episodeThumbnail = episodeThumbnail,
                    pauseDescription = pauseDescription,
                    resumePositionMs = resumePositionMs,
                    resumeProgressFraction = null,
                    useAlternateBehavior = true,
                    startFromBeginning = false,
                )
            }

        val onPlayRandomEpisode: (String, String, String, String, String, String?, String?, String?, Int?, Int?, String?, String?, String?) -> Unit =
            { type, videoId, parentMetaId, parentMetaType, title, logo, poster, background, seasonNumber, episodeNumber, episodeTitle, episodeThumbnail, pauseDescription ->
                launchPlaybackWithDownloadPreference(
                    type = type,
                    videoId = videoId,
                    parentMetaId = parentMetaId,
                    parentMetaType = parentMetaType,
                    title = title,
                    logo = logo,
                    poster = poster,
                    background = background,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    episodeTitle = episodeTitle,
                    episodeThumbnail = episodeThumbnail,
                    pauseDescription = pauseDescription,
                    resumePositionMs = null,
                    resumeProgressFraction = null,
                    useAlternateBehavior = false,
                    startFromBeginning = true,
                    disableProgressTracking = true,
                    autoPlayMode = PlayerAutoPlayMode.RandomEpisode,
                )
            }

        // Plays one playlist entry through the same path as pressing Play on its details page, in
        // playlist mode: the player then hands the end of the file back to onPlaybackCompleted,
        // which moves on to the next entry. Resume position is the entry's own watch progress; an
        // entry already watched through starts over rather than opening on its credits.
        //
        // A random-episode slot is first turned into a concrete episode ([resolved] when the player
        // already picked one for its up-next card), then plays like the details page's Random
        // episode: from the start, untracked and unscrobbled.
        fun launchPlaylistEntry(playlistId: String, entryId: String, resolved: PlaylistEntry? = null) {
            val stored = PlaylistRepository.get(playlistId)
                ?.entries
                ?.firstOrNull { it.entryId == entryId }
                ?: return
            val entry = resolved?.takeUnless { it.isUnresolvedRandom } ?: stored
            if (entry.isUnresolvedRandom) {
                coroutineScope.launch {
                    val picked = PlaylistRandomEpisodes.resolve(entry)
                    if (picked == null) {
                        PlaylistPlaybackSession.clear()
                        NuvioToastController.show("Couldn't load episodes for ${entry.title}")
                    } else {
                        launchPlaylistEntry(playlistId, entryId, picked)
                    }
                }
                return
            }
            PlaylistPlaybackSession.start(playlistId, entryId)
            val progress = WatchProgressRepository.progressForVideo(entry.videoId)
                ?.takeUnless { entry.randomEpisode }
            val resumePositionMs = progress
                ?.takeUnless { it.isCompleted }
                ?.lastPositionMs
                ?.takeIf { it > 0L }
            launchPlaybackWithDownloadPreference(
                type = entry.type,
                videoId = entry.videoId,
                parentMetaId = entry.parentMetaId,
                parentMetaType = entry.parentMetaType,
                title = entry.title,
                logo = entry.logo,
                poster = entry.poster,
                background = entry.background,
                seasonNumber = entry.seasonNumber,
                episodeNumber = entry.episodeNumber,
                episodeTitle = entry.episodeTitle,
                episodeThumbnail = entry.episodeThumbnail,
                pauseDescription = entry.description,
                resumePositionMs = resumePositionMs,
                resumeProgressFraction = null,
                useAlternateBehavior = false,
                startFromBeginning = entry.randomEpisode || progress?.isCompleted == true,
                disableProgressTracking = entry.randomEpisode,
                autoPlayMode = PlayerAutoPlayMode.Playlist,
                preferLocalOverride = PlaylistRepository.get(playlistId)?.preferLocalLibrary ?: true,
            )
        }

        // The viewer picked another entry from the player's playlist peek. The entry being left is
        // judged finished or not as on exit; then the player closes fully (two players must never
        // coexist — see PlayerHandoff) and the picked entry starts like pressing it in the playlist.
        fun jumpPlaylistEntry(fromLaunchId: Long, entryId: String) {
            val active = PlaylistPlaybackSession.current() ?: return
            lastPlayerExitBackMark = TimeSource.Monotonic.markNow()
            ResumePromptRepository.markPlayerExitedNormally()
            PlayerLaunchStore.remove(fromLaunchId)
            PlaylistPlaybackSession.leaveCurrent()
            navController.popBackStack()
            coroutineScope.launch {
                withTimeoutOrNull(PLAYLIST_HANDOFF_DISPOSE_TIMEOUT_MS) {
                    PlayerHandoff.activeRoutes.first { it == 0 }
                }
                launchPlaylistEntry(active.playlistId, entryId)
            }
        }

        // The player found (in the background, binge-style) the source for the playlist's next
        // entry: swap the player route for one playing it, with no streams screen in between.
        // Only an entry nothing could be auto-selected for goes through the normal source list.
        fun handOffPlaylistEntry(fromLaunchId: Long, handoff: PlaylistHandoff) {
            val active = PlaylistPlaybackSession.current() ?: return
            val entry = handoff.entry
            lastPlayerExitBackMark = TimeSource.Monotonic.markNow()
            ResumePromptRepository.markPlayerExitedNormally()
            PlayerLaunchStore.remove(fromLaunchId)
            PlaylistPlaybackSession.finishCurrent()
            if (handoff is PlaylistHandoff.SourceList) {
                navController.popBackStack()
                launchPlaylistEntry(active.playlistId, entry.entryId, entry)
                return
            }
            PlaylistPlaybackSession.start(active.playlistId, entry.entryId)
            val progress = WatchProgressRepository.progressForVideo(entry.videoId)?.takeUnless { entry.randomEpisode }
            val resumePositionMs = progress?.takeUnless { it.isCompleted }?.lastPositionMs?.takeIf { it > 0L } ?: 0L
            val releaseYear = ReleaseYearResolver.peek(entry.parentMetaType, entry.parentMetaId)
            val playerLaunch = when (handoff) {
                is PlaylistHandoff.Stream -> {
                    val stream = handoff.stream
                    PlayerLaunch(
                        title = entry.title,
                        sourceUrl = stream.playableDirectUrl ?: return,
                        sourceHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request),
                        sourceResponseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
                        streamType = stream.streamType,
                        sourceAffinity = PlayerSourceAffinity.fromInitialStreamType(stream.streamType),
                        logo = entry.logo,
                        poster = entry.poster,
                        background = entry.background,
                        seasonNumber = entry.seasonNumber,
                        episodeNumber = entry.episodeNumber,
                        episodeTitle = entry.episodeTitle,
                        episodeThumbnail = entry.episodeThumbnail,
                        releaseYear = releaseYear,
                        streamTitle = stream.streamLabel,
                        streamFilename = stream.mediaFilename,
                        streamSubtitle = stream.streamSubtitle,
                        sourceIdentityKey = stream.playerSourceIdentityKey(),
                        bingeGroup = stream.behaviorHints.bingeGroup,
                        pauseDescription = entry.description,
                        providerName = stream.addonName,
                        providerAddonId = stream.addonId,
                        contentType = entry.type,
                        videoId = entry.videoId,
                        parentMetaId = entry.parentMetaId,
                        parentMetaType = entry.parentMetaType,
                        initialPositionMs = resumePositionMs,
                        disableProgressTracking = entry.randomEpisode,
                        autoPlayMode = PlayerAutoPlayMode.Playlist,
                    )
                }
                is PlaylistHandoff.Downloaded -> {
                    val localUri = DownloadsRepository.playableLocalFileUri(handoff.item) ?: return
                    val labels = handoff.item.playbackLabels(
                        localPath = localUri,
                        fallbackTitle = entry.title,
                        downloadedLabel = downloadedProviderLabel,
                    )
                    PlayerLaunch(
                        title = entry.title,
                        sourceUrl = localUri,
                        logo = entry.logo,
                        poster = entry.poster,
                        background = entry.background,
                        seasonNumber = entry.seasonNumber,
                        episodeNumber = entry.episodeNumber,
                        episodeTitle = entry.episodeTitle,
                        episodeThumbnail = entry.episodeThumbnail,
                        releaseYear = releaseYear,
                        streamTitle = labels.streamTitle,
                        streamSubtitle = labels.streamSubtitle,
                        pauseDescription = entry.description,
                        providerName = labels.providerName,
                        providerAddonId = handoff.item.providerAddonId,
                        contentType = entry.type,
                        videoId = entry.videoId,
                        parentMetaId = entry.parentMetaId,
                        parentMetaType = entry.parentMetaType,
                        initialPositionMs = resumePositionMs,
                        disableProgressTracking = entry.randomEpisode,
                        autoPlayMode = PlayerAutoPlayMode.Playlist,
                    )
                }
                is PlaylistHandoff.SourceList -> return
            }
            // Close the outgoing player completely before opening the next — see PlayerHandoff.
            PlayerHandoff.holdShield()
            navController.popBackStack()
            coroutineScope.launch {
                withTimeoutOrNull(PLAYLIST_HANDOFF_DISPOSE_TIMEOUT_MS) {
                    PlayerHandoff.activeRoutes.first { it == 0 }
                }
                navController.navigateToPlayer(playerLaunch)
            }
        }

        val onCatalogClick: (HomeCatalogSection) -> Unit = onCatalogClick@ { section ->
            val target = section.target ?: return@onCatalogClick
            navController.navigate(
                CatalogRoute(
                    title = section.title,
                    subtitle = section.subtitle,
                    target = target,
                ),
            )
        }
        val onHeroCastClick: (HeroCastMember) -> Unit = { person ->
            val tmdbId = person.tmdbId
            if (tmdbId != null && tmdbId > 0) {
                navController.navigateIfResumed(
                    PersonDetailRoute(
                        personId = tmdbId,
                        personName = person.name,
                        personPhoto = person.photo,
                    ),
                )
            }
        }
        // A discovery badge click. Studio and director badges reuse the screens the details page
        // already has for companies and people; everything else opens the badge browse.
        val onHeroBadgeClick: (HeroDiscoveryFact, String) -> Unit = { fact, sourceType ->
            when (val target = fact.browseTarget()) {
                null -> Unit
                is HeroDiscoveryBadgeTarget.Company -> navController.navigateIfResumed(
                    EntityBrowseRoute(
                        entityKind = TmdbEntityKind.COMPANY.routeValue,
                        entityId = target.tmdbId,
                        entityName = target.name,
                        sourceType = sourceType,
                    ),
                )
                is HeroDiscoveryBadgeTarget.Director -> coroutineScope.launch {
                    // The badge only carries the name hero_discovery.json matched on; TMDB's
                    // person search resolves it, and an exact name match keeps "Ridley Scott"
                    // from opening the first Scott it finds.
                    val person = TmdbService.searchPeople(target.name)
                        .firstOrNull { it.name.equals(target.name, ignoreCase = true) }
                    if (person != null && person.id > 0) {
                        navController.navigateIfResumed(
                            PersonDetailRoute(
                                personId = person.id,
                                personName = person.name.ifBlank { target.name },
                                preferCrew = true,
                            ),
                        )
                    }
                }
                else -> target.encodeForRoute()?.let { (kind, value) ->
                    navController.navigateIfResumed(
                        BadgeBrowseRoute(
                            targetKind = kind,
                            targetValue = value,
                            title = target.browseTitle(fact.heroDiscoveryAwardLabel()),
                            sourceType = sourceType,
                        ),
                    )
                }
            }
        }

        val librarySectionSubtitle = if (libraryUiState.sourceMode == LibrarySourceMode.TRAKT) {
            stringResource(Res.string.compose_catalog_subtitle_trakt_library)
        } else {
            stringResource(Res.string.compose_catalog_subtitle_library)
        }

        val onLibrarySectionViewAllClick: (LibrarySection) -> Unit = { section ->
            navController.navigate(
                CatalogRoute(
                    title = section.displayTitle,
                    subtitle = librarySectionSubtitle,
                    target = CatalogTarget.Library(
                        contentType = section.items.firstOrNull()?.type ?: "movie",
                        sectionType = section.type,
                    ),
                ),
            )
        }

        fun openContinueWatching(
            item: ContinueWatchingItem,
            useAlternateBehavior: Boolean,
            startFromBeginning: Boolean,
            forceManualSelection: Boolean = false,
            forceReuseLastLink: Boolean = false,
        ) {
            resumePromptItem = null
            if (item.isCloudLibraryContinueWatchingItem()) {
                coroutineScope.launch {
                    when (
                        val lookup = CloudLibraryRepository.findPlaybackTargetForProgressResult(
                            contentId = item.parentMetaId,
                            videoId = item.videoId,
                        )
                    ) {
                        is CloudLibraryPlaybackTargetLookupResult.Found -> {
                            val launched = launchCloudLibraryFile(
                                item = lookup.target.item,
                                file = lookup.target.file,
                                resumePositionMs = item.resumePositionMs,
                                resumeProgressFraction = item.resumeProgressFraction,
                                startFromBeginning = startFromBeginning,
                            )
                            if (!launched) {
                                NuvioToastController.show(cloudLibraryPlayFailedText)
                            }
                        }

                        CloudLibraryPlaybackTargetLookupResult.Disabled -> {
                            NuvioToastController.show(cloudLibraryPlayDisabledText)
                        }

                        is CloudLibraryPlaybackTargetLookupResult.NotConnected -> {
                            val providerName = lookup.providerName?.takeIf { it.isNotBlank() }
                            NuvioToastController.show(
                                providerName?.let { name ->
                                    getString(Res.string.cloud_library_play_provider_not_connected, name)
                                }
                                    ?: cloudLibraryPlayNotConnectedText,
                            )
                        }

                        CloudLibraryPlaybackTargetLookupResult.NotFound -> {
                            NuvioToastController.show(cloudLibraryPlayFailedText)
                        }
                    }
                }
            } else {
                launchPlaybackWithDownloadPreference(
                    type = item.parentMetaType,
                    videoId = item.videoId,
                    parentMetaId = item.parentMetaId,
                    parentMetaType = item.parentMetaType,
                    title = item.title,
                    logo = item.logo,
                    poster = item.poster,
                    background = item.background,
                    seasonNumber = item.seasonNumber,
                    episodeNumber = item.episodeNumber,
                    episodeTitle = item.episodeTitle,
                    episodeThumbnail = item.episodeThumbnail,
                    pauseDescription = item.pauseDescription,
                    resumePositionMs = item.resumePositionMs,
                    resumeProgressFraction = item.resumeProgressFraction,
                    useAlternateBehavior = useAlternateBehavior,
                    startFromBeginning = startFromBeginning,
                    watchProgressSource = item.source,
                    forceManualSelection = forceManualSelection,
                    forceReuseLastLink = forceReuseLastLink,
                )
            }
        }

        val onContinueWatchingClick: (ContinueWatchingItem) -> Unit = { item ->
            if (
                continueWatchingPreferencesUiState.clickAction.opensDetails(
                    canOpenDetails = !item.isCloudLibraryContinueWatchingItem(),
                )
            ) {
                resumePromptItem = null
                navController.navigateIfResumed(
                    DetailRoute(type = item.parentMetaType, id = item.parentMetaId),
                )
            } else {
                openContinueWatching(item, false, false)
            }
        }

        // Explicit Resume/Play affordances never inherit the card's default click action. They
        // continue into the existing local-file/source-picker/autoplay decision tree.
        val onContinueWatchingPlay: (ContinueWatchingItem) -> Unit = { item ->
            openContinueWatching(item, false, false)
        }

        // The card's source shortcut does the opposite of a plain click: with Reuse Last Link on it
        // opens the source picker, otherwise it replays the last link.
        val onContinueWatchingSourceShortcut: (ContinueWatchingItem) -> Unit = { item ->
            if (PlayerSettingsRepository.uiState.value.streamReuseLastLinkEnabled) {
                openContinueWatching(item, false, false, forceManualSelection = true)
            } else {
                openContinueWatching(item, false, false, forceReuseLastLink = true)
            }
        }

        val onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit = { item ->
            openContinueWatching(item, false, true)
        }

        val onContinueWatchingLongPress: (ContinueWatchingItem) -> Unit = { item ->
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            selectedContinueWatchingAnchor = PosterZoomAnchorHolder.consume()
            selectedContinueWatchingMenuPosition = ContextMenuInvocation.consume()
            selectedContinueWatchingForActions = item
        }

        val onOpenDownloadedItem: (DownloadItem) -> Unit = openDownload@ { item ->
            val sourceUrl = DownloadsRepository.playableLocalFileUri(item) ?: return@openDownload
            val labels = item.playbackLabels(
                localPath = sourceUrl,
                fallbackTitle = item.title,
                downloadedLabel = downloadedProviderLabel,
            )
            val resumeEntry = item.videoId
                .takeIf { it.isNotBlank() }
                ?.let(WatchProgressRepository::progressForVideo)
                ?.takeIf { it.isResumable }

            val playerLaunch = PlayerLaunch(
                title = item.title,
                sourceUrl = sourceUrl,
                sourceHeaders = emptyMap(),
                sourceResponseHeaders = emptyMap(),
                logo = item.logo,
                poster = item.poster,
                background = item.background,
                seasonNumber = item.seasonNumber,
                episodeNumber = item.episodeNumber,
                episodeTitle = item.episodeTitle,
                episodeThumbnail = item.episodeThumbnail,
                streamTitle = labels.streamTitle,
                streamSubtitle = labels.streamSubtitle,
                providerName = labels.providerName,
                providerAddonId = item.providerAddonId,
                contentType = item.contentType,
                videoId = item.videoId,
                parentMetaId = item.parentMetaId,
                parentMetaType = item.parentMetaType,
                initialPositionMs = resumeEntry?.lastPositionMs?.takeIf { it > 0L } ?: 0L,
                initialProgressFraction = resumeEntry?.progressFraction?.takeIf { it > 0f },
            )
            if (playerSettingsUiState.externalPlayerEnabled) {
                coroutineScope.launch { openExternalPlayback(playerLaunch) }
                return@openDownload
            }
            navController.navigateToPlayer(playerLaunch)
        }

        // In adaptive-hero / TV mode the resume prompt is owned by the home hero. When the user
        // navigates off the hero (e.g. into a collection) the prompt must simply wait for their
        // return, NOT fall back to the basic resume dialog — that fallback was nagging them.
        val resumePromptHeroModeActive = isDesktop &&
            (homeCatalogSettingsUiState.adaptiveHeroEnabled || homeCatalogSettingsUiState.tvModeEnabled)
        val resumePromptUsesHero = resumePromptHeroModeActive &&
            selectedTab == AppScreenTab.Home &&
            navController.currentDestination?.hasRoute<TabsRoute>() == true

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.nuvio.colors.background)
                .onPreviewKeyEvent { event ->
                    val canOpenDiscovery =
                        discoverTabVisible &&
                            currentBackStackEntry?.destination?.hasRoute<TabsRoute>() == true &&
                            selectedTab != AppScreenTab.Settings
                    if (event.key != Key.Tab || !canOpenDiscovery) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown) {
                        // Tab used to open the search overlay parked on its catalog picker. Discover
                        // owns catalog browsing now, so it goes straight there.
                        handleRootTabClick(AppScreenTab.Discover)
                    }
                    true
                },
        ) {
            // Read from the right-click-back handler at click time, which a plain capture would pin
            // to the value from whichever composition installed it.
            val latestPosterOverlayOpen = rememberUpdatedState(
                selectedPosterActionTarget != null || selectedContinueWatchingForActions != null,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (selectedPosterActionTarget != null || selectedContinueWatchingForActions != null) {
                            Modifier.hazeSource(posterOverlayHazeState)
                        } else {
                            Modifier
                        },
                    )
                    .background(MaterialTheme.nuvio.colors.background),
            ) {
            SharedTransitionLayout {
                // Settings-hosted screens (local library, monitored titles) need to reach details
                // but are composed nowhere near the routes that can navigate there.
                CompositionLocalProvider(
                    LocalOpenMetaDetails provides { type, id ->
                        navController.navigateIfResumed(DetailRoute(type = type, id = id))
                    },
                ) {
                Box(modifier = Modifier.fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = TabsRoute,
                    // Instant on desktop. Routes that pass `null` fall through to these, and the
                    // library default is a 700 ms crossfade: every screen change faded (the "ghost"
                    // of the details page on Back), and the entry underneath only reached RESUMED
                    // when the fade ended, so navigateIfResumed dropped every click in that window -
                    // with Home kept composed and shown at once on Back, the first click on the next
                    // title did nothing (2026-10-19 08:43: clicks 0.5-0.7 s after Back logged, no
                    // navigation). iOS keeps the default.
                    enterTransition = { if (isIos) fadeIn(animationSpec = tween(700)) else EnterTransition.None },
                    exitTransition = { if (isIos) fadeOut(animationSpec = tween(700)) else ExitTransition.None },
                    popEnterTransition = { if (isIos) fadeIn(animationSpec = tween(700)) else EnterTransition.None },
                    popExitTransition = { if (isIos) fadeOut(animationSpec = tween(700)) else ExitTransition.None },
                    modifier = Modifier
                        .fillMaxSize()
                        // Right-click on empty space in a pushed screen (anything with a back arrow)
                        // acts as Back — the same request the mouse Back button sends. Posters and
                        // every other right-click action consume the press first, so they win.
                        .unclaimedSecondaryClick(
                            enabled = {
                                val destination = navController.currentDestination
                                destination != null &&
                                    !destination.hasRoute<TabsRoute>() &&
                                    !destination.hasRoute<PlayerRoute>() &&
                                    navController.previousBackStackEntry != null &&
                                    latestPosterOverlayOpen.value.not() &&
                                    !PosterZoomOverlayCoordinator.isVisible
                            },
                            onClick = {
                                DesktopNavigationGestureBridge.requestBack(DesktopBackRequestSource.Mouse)
                            },
                        ),
                ) {
                composable<TabsRoute> {
                    PlatformBackHandler(
                        enabled = true,
                        onBack = {
                            when {
                                searchOverlayActive -> {
                                    searchOverlayActive = false
                                    navigateToContentCount++
                                }
                                rootTabBackStack.isNotEmpty() -> {
                                    val target = rootTabBackStack.last()
                                    rootTabBackStack = rootTabBackStack.dropLast(1)
                                    selectedTab = target
                                }
                                selectedTab != AppScreenTab.Home -> {
                                    selectedTab = AppScreenTab.Home
                                }
                                else -> {
                                    showExitConfirmation = !showExitConfirmation
                                }
                            }
                        },
                    )
                }
                composable<DetailRoute> { backStackEntry ->
                    val route = backStackEntry.toRoute<DetailRoute>()
                    val randomPlayAutoPlayConsumed =
                        backStackEntry.savedStateHandle.get<Boolean>(DETAIL_AUTO_PLAY_CONSUMED_KEY) == true
                    val directorRole = stringResource(Res.string.person_role_director)
                    val writerRole = stringResource(Res.string.person_role_writer)
                    val creatorRole = stringResource(Res.string.person_role_creator)
                    val onBackFromDetail = rememberGuardedPopBackStack(
                        navController = navController,
                        backStackEntry = backStackEntry,
                    )
                    val detailsDensity = if (desktopAppUiScaleAppliesToDetails) {
                        LocalDensity.current
                    } else {
                        LocalNuvioViewportDensity.current
                    }
                    CompositionLocalProvider(LocalDensity provides detailsDensity) {
                        MetaDetailsScreen(
                        type = route.type,
                        id = route.id,
                        autoPlayOnLoad = route.autoPlay && !randomPlayAutoPlayConsumed,
                        onAutoPlayOnLoadConsumed = {
                            // The typed route remains autoPlay=true for the lifetime of this entry.
                            // Persist consumption on the entry before navigating so returning from
                            // streams/player cannot re-arm the one-shot request.
                            backStackEntry.savedStateHandle[DETAIL_AUTO_PLAY_CONSUMED_KEY] = true
                        },
                        onBack = onBackFromDetail,
                        onPlay = onPlay,
                        onPlayAlternate = onPlayAlternate,
                        onPlayRandomEpisode = onPlayRandomEpisode,
                        onPlayTrailer = { trailerLaunch ->
                            if (playerSettingsUiState.externalPlayerEnabled) {
                                coroutineScope.launch { openExternalPlayback(trailerLaunch) }
                            } else {
                                navController.navigateToPlayer(trailerLaunch)
                            }
                        },
                        onOpenMeta = { preview ->
                            coroutineScope.launch {
                                val resolvedId = detailRouteIdFor(preview)
                                navController.navigateIfResumed(
                                    DetailRoute(
                                        type = preview.type,
                                        id = resolvedId,
                                    ),
                                )
                            }
                        },
                        onCastClick = { person, avatarTransitionKey ->
                            val tmdbId = person.tmdbId
                            if (tmdbId != null && tmdbId > 0) {
                                navController.navigateIfResumed(
                                    PersonDetailRoute(
                                        personId = tmdbId,
                                        personName = person.name,
                                        personPhoto = person.photo,
                                        castAvatarTransitionKey = avatarTransitionKey,
                                        preferCrew = person.role?.let {
                                            it.equals("Director", ignoreCase = true) ||
                                                it.equals(directorRole, ignoreCase = true) ||
                                                it.equals("Writer", ignoreCase = true) ||
                                                it.equals(writerRole, ignoreCase = true) ||
                                                it.equals("Creator", ignoreCase = true)
                                                || it.equals(creatorRole, ignoreCase = true)
                                        } ?: false,
                                    ),
                                )
                            }
                        },
                        onCompanyClick = { company, entityKind ->
                            val tmdbId = company.tmdbId
                            if (tmdbId != null && tmdbId > 0) {
                                navController.navigateIfResumed(
                                    EntityBrowseRoute(
                                        entityKind = entityKind,
                                        entityId = tmdbId,
                                        entityName = company.name,
                                        sourceType = route.type,
                                    ),
                                )
                            }
                        },
                        onBadgeClick = { fact -> onHeroBadgeClick(fact, route.type) },
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                composable<PersonDetailRoute> { backStackEntry ->
                    val route = backStackEntry.toRoute<PersonDetailRoute>()
                    PersonDetailScreen(
                        personId = route.personId,
                        personName = route.personName,
                        initialProfilePhoto = route.personPhoto,
                        avatarTransitionKey = route.castAvatarTransitionKey,
                        preferCrew = route.preferCrew,
                        onBack = { navController.popBackStack() },
                        onOpenMeta = { preview ->
                            coroutineScope.launch {
                                val resolvedId = detailRouteIdFor(preview)
                                navController.navigateIfResumed(
                                    DetailRoute(
                                        type = preview.type,
                                        id = resolvedId,
                                    ),
                                )
                            }
                        },
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                composable<EntityBrowseRoute> { backStackEntry ->
                    val route = backStackEntry.toRoute<EntityBrowseRoute>()
                    TmdbEntityBrowseScreen(
                        entityKind = TmdbEntityKind.fromRouteValue(route.entityKind),
                        entityId = route.entityId,
                        entityName = route.entityName,
                        sourceType = route.sourceType,
                        onBack = { navController.popBackStack() },
                        onOpenMeta = { preview ->
                            coroutineScope.launch {
                                val resolvedId = detailRouteIdFor(preview)
                                navController.navigateIfResumed(
                                    DetailRoute(
                                        type = preview.type,
                                        id = resolvedId,
                                    ),
                                )
                            }
                        },
                        onPosterLongClick = { meta ->
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            selectedPosterAnchor = PosterZoomAnchorHolder.consume()
                            selectedPosterMenuPosition = ContextMenuInvocation.consume()
                            selectedPosterActionTarget = PosterActionTarget(preview = meta)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                composable<BadgeBrowseRoute> { backStackEntry ->
                    val route = backStackEntry.toRoute<BadgeBrowseRoute>()
                    val target = remember(route) { decodeHeroDiscoveryBadgeTarget(route.targetKind, route.targetValue) }
                    if (target == null) {
                        // Only reachable from a stale back stack after the encoding changes.
                        LaunchedEffect(route) { navController.popBackStack() }
                        return@composable
                    }
                    HeroBadgeBrowseScreen(
                        target = target,
                        title = route.title,
                        sourceType = route.sourceType,
                        onBack = { navController.popBackStack() },
                        onOpenMeta = { preview ->
                            coroutineScope.launch {
                                val resolvedId = detailRouteIdFor(preview)
                                navController.navigateIfResumed(
                                    DetailRoute(
                                        type = preview.type,
                                        id = resolvedId,
                                    ),
                                )
                            }
                        },
                        onPosterLongClick = { meta ->
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            selectedPosterAnchor = PosterZoomAnchorHolder.consume()
                            selectedPosterMenuPosition = ContextMenuInvocation.consume()
                            selectedPosterActionTarget = PosterActionTarget(preview = meta)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                composable<PlayerRoute>(
                    enterTransition = {
                        if (isIos) fadeIn(animationSpec = tween(220)) else null
                    },
                    exitTransition = {
                        if (isIos) fadeOut(animationSpec = tween(220)) else null
                    },
                    popEnterTransition = {
                        if (isIos) fadeIn(animationSpec = tween(220)) else null
                    },
                    popExitTransition = {
                        if (isIos) fadeOut(animationSpec = tween(220)) else null
                    },
                ) { backStackEntry ->
                    val route = backStackEntry.toRoute<PlayerRoute>()
                    val launch = remember(route.launchId) { PlayerLaunchStore.get(route.launchId) }
                    if (launch == null) {
                        LaunchedEffect(route.launchId) {
                            navController.popBackStack()
                        }
                        Box(modifier = Modifier.fillMaxSize())
                        return@composable
                    }
                    DisposableEffect(route.launchId) {
                        PlayerHandoff.routeEntered()
                        com.nuvio.app.features.librarypvr.LibraryPvrScheduler.setPlaybackActive(true)
                        onDispose {
                            com.nuvio.app.features.librarypvr.LibraryPvrScheduler.setPlaybackActive(false)
                            PlayerHandoff.routeLeft()
                        }
                    }
                    LaunchedEffect(launch.videoId) {
                        launch.videoId?.let { ResumePromptRepository.markPlayerEntered(it) }
                    }
                    val onBackFromPlayer = rememberGuardedPopBackStack(
                        navController = navController,
                        backStackEntry = backStackEntry,
                        beforePop = {
                            lastPlayerExitBackMark = TimeSource.Monotonic.markNow()
                            ResumePromptRepository.markPlayerExitedNormally()
                            PlayerLaunchStore.remove(route.launchId)
                            if (launch.autoPlayMode == PlayerAutoPlayMode.Playlist) {
                                PlaylistPlaybackSession.onPlayerExited()
                            }
                        },
                    )
                    PlayerScreen(
                        title = launch.title,
                        sourceUrl = launch.sourceUrl,
                        sourceAudioUrl = launch.sourceAudioUrl,
                        sourceHeaders = launch.sourceHeaders,
                        sourceResponseHeaders = launch.sourceResponseHeaders,
                        streamType = launch.streamType,
                        sourceAffinity = launch.sourceAffinity,
                        logo = launch.logo,
                        poster = launch.poster,
                        background = launch.background,
                        seasonNumber = launch.seasonNumber,
                        episodeNumber = launch.episodeNumber,
                        episodeTitle = launch.episodeTitle,
                        episodeThumbnail = launch.episodeThumbnail,
                        releaseYear = launch.releaseYear,
                        streamTitle = launch.streamTitle,
                        streamFilename = launch.streamFilename,
                        streamSubtitle = launch.streamSubtitle,
                        sourceIdentityKey = launch.sourceIdentityKey,
                        initialBingeGroup = launch.bingeGroup,
                        pauseDescription = launch.pauseDescription,
                        providerName = launch.providerName,
                        providerAddonId = launch.providerAddonId,
                        contentType = launch.contentType,
                        videoId = launch.videoId,
                        parentMetaId = launch.parentMetaId,
                        parentMetaType = launch.parentMetaType,
                        watchProgressSource = launch.watchProgressSource,
                        torrentInfoHash = launch.torrentInfoHash,
                        torrentFileIdx = launch.torrentFileIdx,
                        torrentFilename = launch.torrentFilename,
                        torrentTrackers = launch.torrentTrackers,
                        initialPositionMs = launch.initialPositionMs,
                        initialProgressFraction = launch.initialProgressFraction,
                        disableProgressTracking = launch.disableProgressTracking,
                        autoPlayMode = launch.autoPlayMode,
                        onPlaylistHandoff = if (launch.autoPlayMode == PlayerAutoPlayMode.Playlist) {
                            { handoff -> handOffPlaylistEntry(route.launchId, handoff) }
                        } else {
                            null
                        },
                        onPlaylistJump = if (launch.autoPlayMode == PlayerAutoPlayMode.Playlist) {
                            { entryId -> jumpPlaylistEntry(route.launchId, entryId) }
                        } else {
                            null
                        },
                        onOpenPartyMedia = ::openPartyMedia,
                        stream = launch.stream,
                        onBack = onBackFromPlayer,
                        onPlaybackCompleted = completed@{
                            lastPlayerExitBackMark = TimeSource.Monotonic.markNow()
                            ResumePromptRepository.markPlayerExitedNormally()
                            PlayerLaunchStore.remove(route.launchId)
                            val playlistPlayback = PlaylistPlaybackSession.current()
                                ?.takeIf { launch.autoPlayMode == PlayerAutoPlayMode.Playlist }
                            if (playlistPlayback != null) {
                                // Back to wherever the playlist was started from (normally its
                                // screen), then on to the next entry's source search from there,
                                // so Back from the next player lands in the same place.
                                val next = PlaylistPlaybackSession.finishCurrent()
                                navController.popBackStack()
                                if (next != null) {
                                    launchPlaylistEntry(playlistPlayback.playlistId, next.entryId)
                                } else {
                                    PlaylistPlaybackSession.clear()
                                    NuvioToastController.show("Playlist finished")
                                }
                                return@completed
                            }
                            val returnedToDetails = navController.popBackStack<DetailRoute>(inclusive = false)
                            if (!returnedToDetails) navController.popBackStack()
                        },
                        onOpenInExternalPlayer = { request ->
                            val playerLaunch = PlayerLaunch(
                                title = launch.title,
                                sourceUrl = request.sourceUrl,
                                sourceHeaders = request.sourceHeaders,
                                sourceAffinity = launch.sourceAffinity,
                                logo = launch.logo,
                                poster = launch.poster,
                                background = launch.background,
                                seasonNumber = launch.seasonNumber,
                                episodeNumber = launch.episodeNumber,
                                episodeTitle = launch.episodeTitle,
                                episodeThumbnail = launch.episodeThumbnail,
                                streamTitle = request.streamTitle ?: launch.streamTitle,
                                streamSubtitle = launch.streamSubtitle,
                                bingeGroup = launch.bingeGroup,
                                pauseDescription = launch.pauseDescription,
                                providerName = launch.providerName,
                                providerAddonId = launch.providerAddonId,
                                contentType = launch.contentType,
                                videoId = launch.videoId,
                                parentMetaId = launch.parentMetaId,
                                parentMetaType = launch.parentMetaType,
                                watchProgressSource = launch.watchProgressSource,
                                initialPositionMs = request.resumePositionMs,
                                disableProgressTracking = launch.disableProgressTracking,
                            )
                            lastExternalPlayerLaunch = playerLaunch
                            // After an in-player episode advance the launch still names the first
                            // episode; tracking it would credit the wrong one, so skip it then.
                            val sameEpisode = request.season == launch.seasonNumber &&
                                request.episode == launch.episodeNumber
                            val intentResult = ExternalPlayerPlatform.buildIntent(
                                request = request.copy(
                                    tracking = playerLaunch.externalPlaybackTracking().takeIf { sameEpisode },
                                ),
                                playerId = playerSettingsUiState.externalPlayerId,
                            )
                            when (intentResult) {
                                is ExternalPlayerIntentResult.Success -> {
                                    val launched = launchExternalPlayer(intentResult)
                                    if (!launched) {
                                        NuvioToastController.show(externalPlayerFailedText)
                                    }
                                }
                                ExternalPlayerIntentResult.NotConfigured -> {
                                    NuvioToastController.show(externalPlayerNotConfiguredText)
                                }
                                ExternalPlayerIntentResult.Failed -> {
                                    NuvioToastController.show(externalPlayerFailedText)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                composable<CatalogRoute> { backStackEntry ->
                    val route = backStackEntry.toRoute<CatalogRoute>()
                    val target = route.toCatalogTarget()
                    CatalogScreen(
                        entryKey = backStackEntry.id,
                        title = route.title,
                        subtitle = route.subtitle,
                        target = target,
                        onBack = {
                            CatalogRepository.clear()
                            navController.popBackStack()
                        },
                        onPosterClick = { meta ->
                            navController.navigateIfResumed(DetailRoute(type = meta.type, id = meta.id))
                        },
                        onPosterLongClick = { meta ->
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            selectedPosterAnchor = PosterZoomAnchorHolder.consume()
                            selectedPosterMenuPosition = ContextMenuInvocation.consume()
                            selectedPosterActionTarget = if (target is CatalogTarget.Library) {
                                PosterActionTarget(
                                    preview = meta,
                                    libraryItem = meta.toLibraryItem(savedAtEpochMs = 0L),
                                    libraryListKey = target.sectionType,
                                )
                            } else {
                                PosterActionTarget(preview = meta)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                composable<CalendarRoute> { backStackEntry ->
                    val onBack = rememberGuardedPopBackStack(
                        navController = navController,
                        backStackEntry = backStackEntry,
                    )
                    CalendarScreen(
                        modifier = Modifier.fillMaxSize(),
                        onBack = onBack,
                        onNavigateHome = {
                            handleRootTabClick(AppScreenTab.Home)
                            onBack()
                        },
                        onItemClick = { entry ->
                            navController.navigateIfResumed(
                                DetailRoute(type = entry.type, id = entry.contentId),
                            )
                        },
                    )
                }
                composable<HomescreenSettingsRoute> {
                    val onBack = rememberGuardedPopBackStack(
                        navController = navController,
                        backStackEntry = it,
                    )
                    HomescreenSettingsScreen(
                        onBack = onBack,
                    )
                }
                composable<MetaScreenSettingsRoute> { backStackEntry ->
                    val onBack = rememberGuardedPopBackStack(
                        navController = navController,
                        backStackEntry = backStackEntry,
                    )
                    MetaScreenSettingsScreen(
                        onBack = onBack,
                    )
                }
                composable<ContinueWatchingSettingsRoute> { backStackEntry ->
                    val onBack = rememberGuardedPopBackStack(
                        navController = navController,
                        backStackEntry = backStackEntry,
                    )
                    ContinueWatchingSettingsScreen(
                        onBack = onBack,
                    )
                }
                composable<AddonsSettingsRoute> { backStackEntry ->
                    val onBack = rememberGuardedPopBackStack(
                        navController = navController,
                        backStackEntry = backStackEntry,
                    )
                    AddonsSettingsScreen(
                        onBack = onBack,
                    )
                }
                if (AppFeaturePolicy.pluginsEnabled) {
                    composable<PluginsSettingsRoute> { backStackEntry ->
                        val onBack = rememberGuardedPopBackStack(
                            navController = navController,
                            backStackEntry = backStackEntry,
                        )
                        PluginsSettingsScreen(
                            onBack = onBack,
                        )
                    }
                }
                composable<AccountSettingsRoute> { backStackEntry ->
                    val onBack = rememberGuardedPopBackStack(
                        navController = navController,
                        backStackEntry = backStackEntry,
                    )
                    AccountSettingsScreen(
                        onBack = onBack,
                    )
                }
                composable<SupportersContributorsSettingsRoute> { backStackEntry ->
                    val onBack = rememberGuardedPopBackStack(
                        navController = navController,
                        backStackEntry = backStackEntry,
                    )
                    SupportersContributorsSettingsScreen(
                        onBack = onBack,
                    )
                }
                composable<LicensesAttributionsSettingsRoute> { backStackEntry ->
                    val onBack = rememberGuardedPopBackStack(
                        navController = navController,
                        backStackEntry = backStackEntry,
                    )
                    LicensesAttributionsSettingsScreen(
                        onBack = onBack,
                    )
                }
                composable<CollectionEditorRoute> { backStackEntry ->
                    val route = backStackEntry.toRoute<CollectionEditorRoute>()
                    CollectionEditorScreen(
                        collectionId = route.collectionId,
                        onBack = {
                            CollectionEditorRepository.clear()
                            navController.popBackStack()
                        },
                    )
                }
                composable<FolderDetailRoute> { backStackEntry ->
                    val route = backStackEntry.toRoute<FolderDetailRoute>()
                    LaunchedEffect(route.collectionId, route.folderId) {
                        FolderDetailRepository.initialize(route.collectionId, route.folderId)
                    }
                    FolderDetailScreen(
                        entryKey = backStackEntry.id,
                        onBack = {
                            FolderDetailRepository.clear()
                            clearFolderScrollSession(backStackEntry.id)
                            navController.popBackStack()
                        },
                        onCatalogClick = onCatalogClick,
                        onCastClick = onHeroCastClick,
                        onBadgeClick = { fact, item -> onHeroBadgeClick(fact, item.type) },
                        onPosterClick = { meta ->
                            navController.navigateIfResumed(DetailRoute(type = meta.type, id = meta.id))
                        },
                        onPosterLongClick = { meta ->
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            selectedPosterAnchor = PosterZoomAnchorHolder.consume()
                            selectedPosterMenuPosition = ContextMenuInvocation.consume()
                            selectedPosterActionTarget = PosterActionTarget(preview = meta)
                        },
                    )
                }
                }
                // Home and the other tabs stay composed while a pushed screen covers them, so Back shows a
                // frame that already exists instead of rebuilding Home (2026-10-19: every Details -> Home
                // return hitched 80-150 ms, with the details page lingering until Home's first frame). Not
                // during playback: playback has its own memory trims, which a composed Home's artwork would
                // sit out, and rebuilding Home on exit costs nothing measurable now (2026-10-19, four exits:
                // no stalls with Home kept or rebuilt), so the setting that kept it was removed.
                // Placed above the NavHost so it takes input while visible; covered, it is not placed at all.
                val tabsEntry = remember(currentBackStackEntry) {
                    runCatching { navController.getBackStackEntry<TabsRoute>() }.getOrNull()
                }
                val playerOnTop = currentBackStackEntry?.destination?.hasRoute<PlayerRoute>() == true
                if (tabsEntry != null && !playerOnTop) {
                    KeptAliveLayer(
                        visible = currentBackStackEntry?.destination?.hasRoute<TabsRoute>() == true,
                        lifecycleOwner = tabsEntry,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val isTabletLayout = maxWidth >= 768.dp
                            val useNativeBottomTabs =
                                liquidGlassNativeTabBarSupported && liquidGlassNativeTabBarEnabled && initialHomeReady
                            val useDesktopSidebar =
                                isTabletLayout &&
                                    !useNativeBottomTabs &&
                                    desktopNavigationLayout == DesktopNavigationLayout.Sidebar
                            val showDesktopSidebar = useDesktopSidebar && selectedTab != AppScreenTab.Settings
                            val useFloatingTopBar = isTabletLayout && !useNativeBottomTabs && !useDesktopSidebar
                            val topChromePadding = if (useFloatingTopBar || selectedTab == AppScreenTab.Search) {
                                val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                                max(statusBarPadding + 24.dp, 48.dp) + 64.dp
                            } else {
                                null
                            }
                            val tabsRouteActive = currentBackStackEntry?.destination?.hasRoute<TabsRoute>() == true
                            val onProfileSelected: (NuvioProfile) -> Unit = { profile ->
                                profileSwitchLoading = true
                                selectedTab = AppScreenTab.Home
                                coroutineScope.launch {
                                    try {
                                        ProfileRepository.switchToProfile(profile.profileIndex)
                                        warmProfileStartupRepositories()
                                        SyncManager.pullAllForProfile(profile.profileIndex)
                                        launch {
                                            warmProfileDeferredRepositories()
                                        }
                                        delay(300)
                                    } finally {
                                        profileSwitchLoading = false
                                    }
                                }
                            }

                            Scaffold(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .alpha(if (initialHomeReady) 1f else 0f),
                                containerColor = Color.Transparent,
                                contentWindowInsets = WindowInsets(0),
                                bottomBar = {
                                    if (!isTabletLayout && !useNativeBottomTabs && selectedTab != AppScreenTab.Settings) {
                                        NuvioNavigationBar {
                                            NavItem(
                                                selected = selectedTab == AppScreenTab.Home,
                                                onClick = { handleRootTabClick(AppScreenTab.Home) },
                                                icon = Icons.Filled.Home,
                                                contentDescription = stringResource(Res.string.compose_nav_home),
                                            )
                                            NavItem(
                                                selected = selectedTab == AppScreenTab.Search,
                                                onClick = { handleRootTabClick(AppScreenTab.Search) },
                                                icon = Res.drawable.sidebar_search,
                                                contentDescription = stringResource(Res.string.compose_nav_search),
                                            )
                                            LibraryNavigationContextMenu(modifier = Modifier.width(64.dp)) { contextModifier ->
                                                NavItem(
                                                    selected = selectedTab == AppScreenTab.Library,
                                                    onClick = { handleRootTabClick(AppScreenTab.Library) },
                                                    icon = Res.drawable.sidebar_library,
                                                    contentDescription = stringResource(Res.string.compose_nav_library),
                                                    modifier = contextModifier,
                                                )
                                            }
                                            NavItem(
                                                selected = selectedTab == AppScreenTab.Settings,
                                                onClick = { handleRootTabClick(AppScreenTab.Settings) },
                                            ) {
                                                ProfileSwitcherTab(
                                                    selected = selectedTab == AppScreenTab.Settings,
                                                    onClick = { handleRootTabClick(AppScreenTab.Settings) },
                                                    onProfileSelected = onProfileSelected,
                                                    onAddProfileRequested = onSwitchProfile,
                                                )
                                            }
                                        }
                                    }
                                },
                            ) { innerPadding ->
                                Box(modifier = Modifier.fillMaxSize()) {
                                    CompositionLocalProvider(
                                        LocalNuvioBottomNavigationOverlayPadding provides if (useNativeBottomTabs) 49.dp else 0.dp,
                                    ) {
                                        AppTabHost(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(innerPadding)
                                                .padding(start = if (showDesktopSidebar) DesktopSidebarCollapsedWidth else 0.dp),
                                            selectedTab = selectedTab,
                                            topChromePadding = topChromePadding,
                                            searchFocusRequestCount = searchFocusRequestCount,
                                            navigateToContentCount = navigateToContentCount,
                                            searchQuery = searchQuery,
                                            submittedSearchQuery = submittedSearchQuery,
                                            onSearchQueryChange = { searchQuery = it },
                                            searchSubmitRequests = searchSubmitRequests,
                                            rootActionsEnabled = tabsRouteActive,
                                            homeScrollToTopRequests = homeScrollToTopRequests,
                                            searchScrollToTopRequests = searchScrollToTopRequests,
                                            libraryScrollToTopRequests = libraryScrollToTopRequests,
                                            discoverScrollToTopRequests = discoverScrollToTopRequests,
                                            settingsRootActionRequests = settingsRootActionRequests,
                                            animateHomeCollectionGifs = tabsRouteActive,
                                            resumePromptItem = resumePromptItem.takeIf { resumePromptUsesHero },
                                            resumePromptLabel = stringResource(Res.string.resume_prompt_question),
                                            onResumePromptAction = {
                                                val item = resumePromptItem
                                                if (item != null) {
                                                    resumePromptItem = null
                                                    openContinueWatching(item, false, false)
                                                }
                                            },
                                            onResumePromptDismiss = { resumePromptItem = null },
                                            continueWatchingHeroDismissed = continueWatchingHeroDismissed,
                                            onContinueWatchingHeroDismiss = { continueWatchingHeroDismissed = true },
                                            onCatalogClick = onCatalogClick,
                                            onCastClick = onHeroCastClick,
                                            onBadgeClick = { fact, item -> onHeroBadgeClick(fact, item.type) },
                                            onPosterClick = posterClick@{ meta ->
                                                if (meta.isPlaylistPreview()) {
                                                    openPlaylistId = meta.id
                                                    return@posterClick
                                                }
                                                val randomCategory = meta.randomPlayCategoryOrNull()
                                                if (randomCategory != null) {
                                                    val randomSettings = HomeCatalogSettingsRepository.uiState.value
                                                    val randomSourceSections = randomPlaySourceSections(
                                                        homeSections = HomeRepository.uiState.value.sections,
                                                        collectionSections = RandomPlayCollectionPool.sections.value,
                                                        settings = randomSettings,
                                                    )
                                                    val selected = pickRandomPlayItem(
                                                        category = randomCategory,
                                                        sourceSections = randomSourceSections
                                                            .withRandomPlayPool(RandomPlayCandidatePool.state.value),
                                                        settings = randomSettings,
                                                        watchedKeys = WatchedRepository.uiState.value.watchedKeys,
                                                    )
                                                    // Warm the next window of collection sources for
                                                    // the pick after this one. No-op once every source
                                                    // is covered, or when collections are not included.
                                                    RandomPlayCollectionPool.ensureLoaded()
                                                    // Likewise top the candidate pool back up: this
                                                    // pick may have been the one that took a card
                                                    // under its target.
                                                    RandomPlayCandidatePool.ensureFilled(
                                                        sourceSections = randomSourceSections,
                                                        settings = randomSettings,
                                                        watchedKeys = WatchedRepository.uiState.value.watchedKeys,
                                                    )
                                                    randomPlayLog.i {
                                                        val pooled = randomSourceSections
                                                            .withRandomPlayPool(RandomPlayCandidatePool.state.value)
                                                        "Pick for $randomCategory: " + (
                                                            selected?.let { randomPlayPickTrace(pooled, it) }
                                                                ?: "no candidates"
                                                            )
                                                    }
                                                    if (selected == null) {
                                                        coroutineScope.launch {
                                                            NuvioToastController.show(
                                                                getString(Res.string.random_play_no_matches),
                                                            )
                                                        }
                                                    } else {
                                                        navController.navigateIfResumed(
                                                            DetailRoute(
                                                                type = selected.type,
                                                                id = selected.id,
                                                                autoPlay = randomSettings.randomPlayAction == RandomPlayAction.Play,
                                                            ),
                                                        )
                                                    }
                                                    return@posterClick
                                                }
                                                navController.navigateIfResumed(DetailRoute(type = meta.type, id = meta.id))
                                            },
                                            onPosterLongClick = posterLongClick@{ meta ->
                                                if (meta.randomPlayCategoryOrNull() != null) return@posterLongClick
                                                // Library, tracker and watched actions mean nothing
                                                // for a playlist card; its screen holds its actions.
                                                if (meta.isPlaylistPreview()) {
                                                    openPlaylistId = meta.id
                                                    return@posterLongClick
                                                }
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                                selectedPosterAnchor = PosterZoomAnchorHolder.consume()
                                                selectedPosterMenuPosition = ContextMenuInvocation.consume()
                                                selectedPosterActionTarget = PosterActionTarget(preview = meta)
                                            },
                                            onLibraryPosterClick = { item ->
                                                navController.navigateIfResumed(
                                                    DetailRoute(type = item.type, id = item.id),
                                                )
                                            },
                                            onLocalLibraryPosterClick = { meta ->
                                                navController.navigateIfResumed(
                                                    DetailRoute(type = meta.type, id = meta.id),
                                                )
                                            },
                                            onLibraryPosterLongClick = { item, section ->
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                                selectedPosterAnchor = PosterZoomAnchorHolder.consume()
                                                selectedPosterMenuPosition = ContextMenuInvocation.consume()
                                                selectedPosterActionTarget = PosterActionTarget(
                                                    preview = item.toMetaPreview(),
                                                    libraryItem = item,
                                                    libraryListKey = section.type,
                                                )
                                            },
                                            onLibrarySectionViewAllClick = onLibrarySectionViewAllClick,
                                            onCloudFilePlay = { item, file ->
                                                coroutineScope.launch {
                                                    val resumeItem = WatchProgressRepository
                                                        .progressForVideo(item.playbackVideoId(file))
                                                        ?.takeIf { it.isResumable }
                                                        ?.toContinueWatchingItem()
                                                    if (
                                                        !launchCloudLibraryFile(
                                                            item = item,
                                                            file = file,
                                                            resumePositionMs = resumeItem?.resumePositionMs,
                                                            resumeProgressFraction = resumeItem?.resumeProgressFraction,
                                                        )
                                                    ) {
                                                        NuvioToastController.show(cloudLibraryPlayFailedText)
                                                    }
                                                }
                                            },
                                            onConnectCloudClick = {
                                                requestedSettingsPageName = "Debrid"
                                                selectedTab = AppScreenTab.Settings
                                            },
                                            onContinueWatchingClick = onContinueWatchingClick,
                                            onContinueWatchingPlay = onContinueWatchingPlay,
                                            onContinueWatchingSourceShortcut = onContinueWatchingSourceShortcut,
                                            onContinueWatchingLongPress = onContinueWatchingLongPress,
                                            onSwitchProfile = onSwitchProfile,
                                            onHomescreenSettingsClick = { navController.navigate(HomescreenSettingsRoute) },
                                            onMetaScreenSettingsClick = { navController.navigate(MetaScreenSettingsRoute) },
                                            onContinueWatchingSettingsClick = { navController.navigate(ContinueWatchingSettingsRoute) },
                                            onOpenDownload = onOpenDownloadedItem,
                                            onOpenPartyMedia = ::openPartyMedia,
                                            onAddonsSettingsClick = { navController.navigate(AddonsSettingsRoute) },
                                            onPluginsSettingsClick = {
                                                if (AppFeaturePolicy.pluginsEnabled) {
                                                    navController.navigate(PluginsSettingsRoute)
                                                }
                                            },
                                            onAccountSettingsClick = { navController.navigate(AccountSettingsRoute) },
                                            onSupportersContributorsSettingsClick = {
                                                navController.navigate(SupportersContributorsSettingsRoute)
                                            },
                                            onLicensesAttributionsSettingsClick = {
                                                navController.navigate(LicensesAttributionsSettingsRoute)
                                            },
                                            onCheckForUpdatesClick = if (AppFeaturePolicy.inAppUpdaterEnabled) {
                                                {
                                                    appUpdaterController.checkForUpdates(
                                                        force = true,
                                                        showNoUpdateFeedback = true,
                                                    )
                                                }
                                            } else {
                                                null
                                            },
                                            onShowLatestChangelogClick = if (AppFeaturePolicy.inAppUpdaterEnabled) {
                                                appUpdaterController::showLatestChangelog
                                            } else {
                                                null
                                            },
                                            onOpenCollectionEditor = { collectionId ->
                                                navController.navigate(CollectionEditorRoute(collectionId = collectionId))
                                            },
                                            onFolderClick = { collectionId, folderId ->
                                                navController.navigate(FolderDetailRoute(collectionId = collectionId, folderId = folderId))
                                            },
                                            requestedSettingsPageName = requestedSettingsPageName,
                                            onRequestedSettingsPageConsumed = {
                                                requestedSettingsPageName = null
                                            },
                                            onInitialHomeContentRendered = { initialHomeReady = true },
                                            onNavigateToSearch = { openSearchOverlay() },
                                            onNavigateToSearchTab = { handleRootTabClick(AppScreenTab.Search) },
                                            onNavigateToLibrary = { handleRootTabClick(AppScreenTab.Library) },
                                            onNavigateToDiscover = { handleRootTabClick(AppScreenTab.Discover) },
                                            onNavigateToHome = { handleRootTabClick(AppScreenTab.Home) },
                                            onNavigateToCalendar = { navController.navigateIfResumed(CalendarRoute) },
                                            onNavigateToLiveTv = { handleRootTabClick(AppScreenTab.LiveTv) },
                                            onLiveTvSettingsClick = {
                                                requestedSettingsPageName = "Iptv"
                                                handleRootTabClick(AppScreenTab.Settings)
                                            },
                                        )
                                    }

                                    if (showDesktopSidebar) {
                                        DesktopHoverSidebar(
                                            selectedTab = selectedTab,
                                            onTabSelected = ::handleRootTabClick,
                                            onProfileSelected = onProfileSelected,
                                            onAddProfileRequested = onSwitchProfile,
                                        )
                                    }
                                    val homeHeroPickerDocked by remember {
                                        combine(
                                            StreamPickerController.request,
                                            StreamPickerController.dock,
                                        ) { request, dock ->
                                            request != null && dock?.ownerKey == StreamPickerDock.HOME
                                        }.distinctUntilChanged()
                                    }.collectAsStateWithLifecycle(initialValue = false)
                                    if (
                                        (useFloatingTopBar && selectedTab != AppScreenTab.Settings) ||
                                        selectedTab == AppScreenTab.Search ||
                                        searchOverlayActive
                                    ) {
                                        TabletFloatingTopBar(
                                            selectedTab = selectedTab,
                                            onTabSelected = ::handleRootTabClick,
                                            onProfileSelected = onProfileSelected,
                                            onAddProfileRequested = onSwitchProfile,
                                            // TV mode docks the source picker into the home hero, right where
                                            // the bar sits; the bar has nothing to offer there, pinned or not.
                                            hidden = homeHeroPickerDocked && !searchOverlayActive,
                                            // Pinning the bar is a content-screen preference: Settings
                                            // draws its own chrome, so it keeps the fade regardless.
                                            dimUntilHovered = (
                                                !desktopTopBarAlwaysVisible ||
                                                    selectedTab == AppScreenTab.Settings
                                                ) && (
                                                selectedTab == AppScreenTab.Home ||
                                                    selectedTab == AppScreenTab.Library ||
                                                    selectedTab == AppScreenTab.Discover ||
                                                    selectedTab == AppScreenTab.Search ||
                                                    selectedTab == AppScreenTab.LiveTv ||
                                                    selectedTab == AppScreenTab.Settings
                                                ),
                                            searchOverlayActive = searchOverlayActive,
                                            onSearchOverlayOpen = { openSearchOverlay() },
                                            onSearchOverlayDismiss = { dismissSearchOverlay() },
                                            searchQuery = searchQuery,
                                            onSearchQueryChange = { searchQuery = it },
                                            searchFocusRequestCount = searchFocusRequestCount,
                                            onSearchSubmit = { q ->
                                                val trimmed = q.trim()
                                                submittedSearchQuery = trimmed
                                                searchOverlayActive = false
                                                handleRootTabClick(AppScreenTab.Search)
                                                navigateToContentCount++
                                                // Results load as usual; if the query can only mean one
                                                // title, its details open on top once that's known, so
                                                // Back lands on the full results.
                                                confidentSearchJob[0]?.cancel()
                                                confidentSearchJob[0] = coroutineScope.launch {
                                                    val match = com.nuvio.app.features.search.ConfidentSearch.resolve(trimmed)
                                                        ?: return@launch
                                                    // Not if the user has already opened something or moved on.
                                                    val onResults = navController.currentBackStackEntry
                                                        ?.destination?.hasRoute<TabsRoute>() == true
                                                    if (onResults && selectedTab == AppScreenTab.Search && submittedSearchQuery == trimmed) {
                                                        navController.navigateIfResumed(DetailRoute(type = match.type, id = match.id))
                                                    }
                                                }
                                            },
                                            onNavigateToContent = { navigateToContentCount++ },
                                        )
                                    }
                                    if (isDesktop) {
                                        DesktopFullscreenHoverButton(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(top = 12.dp, end = 12.dp)
                                                .zIndex(NuvioTokens.Z.navigation + 1f),
                                        )
                                    }

                                }
                            }
                        }
                    }
                }
                }
                }
            }

            }

            PlaylistAddDialogHost()
            CastDialogHost()

            openPlaylistId?.let { playlistId ->
                PlaylistDialog(
                    playlistId = playlistId,
                    onDismiss = { openPlaylistId = null },
                    onPlayEntry = { entryId ->
                        openPlaylistId = null
                        launchPlaylistEntry(playlistId, entryId)
                    },
                    onOpenDetails = { entry ->
                        openPlaylistId = null
                        navController.navigateIfResumed(
                            DetailRoute(type = entry.parentMetaType, id = entry.parentMetaId),
                        )
                    },
                )
            }

            NuvioPosterActionSheet(
                item = selectedPosterActionTarget?.preview,
                isSaved = selectedPosterActionTarget?.preview?.let { preview ->
                    LibraryRepository.isSaved(preview.id, preview.type)
                } == true,
                isWatched = selectedPosterActionTarget?.preview?.let { preview ->
                    WatchingState.isPosterWatched(
                        watchedKeys = watchedUiState.watchedKeys,
                        item = preview,
                    )
                } == true,
                onDismiss = {
                    selectedPosterActionTarget = null
                    selectedPosterAnchor = null
                    selectedPosterMenuPosition = null
                },
                // Only offered for titles that actually have a local copy — the page it opens is
                // the local library list, which would be meaningless for a streaming-only title.
                // Addon content only: cloud and synthetic rows have no episodes or streams to queue.
                onAddToPlaylist = selectedPosterActionTarget?.preview?.let { preview ->
                    val target = when (preview.type.lowercase()) {
                        "movie" -> PlaylistAddTarget.Entries(
                            label = preview.name,
                            entries = listOf(PlaylistEntries.movie(preview)),
                        )
                        "series", "tv", "anime" ->
                            PlaylistAddTarget.Series(type = preview.type, id = preview.id, name = preview.name)
                        else -> null
                    }
                    target?.let { { PlaylistAddController.request(it) } }
                },
                onNotInterested = selectedPosterActionTarget?.preview
                    ?.takeIf { preview -> DiscoverRecommendationsRepository.canMarkNotInterested(preview) }
                    ?.let { preview -> { DiscoverRecommendationsRepository.markNotInterested(preview) } },
                onOpenInLocalLibrary = selectedPosterActionTarget?.preview
                    ?.let { preview -> LocalLibraryRepository.itemForContentId(preview.id) }
                    ?.let {
                        {
                            requestedSettingsPageName = "LocalLibrary"
                            selectedTab = AppScreenTab.Settings
                        }
                    },
                onToggleLibrary = {
                    selectedPosterActionTarget?.let { target ->
                        val preview = target.preview
                        val libraryItem = target.libraryItem ?: preview.toLibraryItem(savedAtEpochMs = 0L)
                        if (target.libraryItem != null) {
                            if (isRemoteLibrarySource) {
                                coroutineScope.launch {
                                    runCatching {
                                        val listKey = target.libraryListKey
                                        if (listKey.isNullOrBlank()) {
                                            val currentMembership = LibraryRepository.getMembershipSnapshot(libraryItem)
                                            LibraryRepository.applyMembershipChanges(
                                                item = libraryItem,
                                                desiredMembership = currentMembership.mapValues { false },
                                            )
                                        } else {
                                            LibraryRepository.removeFromList(libraryItem, listKey)
                                        }
                                    }.onFailure { error ->
                                        NuvioToastController.show(
                                            error.message ?: getString(Res.string.trakt_lists_update_failed),
                                        )
                                    }
                                }
                            } else {
                                LibraryRepository.remove(libraryItem.id)
                            }
                        } else {
                            // Same as the details page save button: a plain click adds the title
                            // to the active provider's default list; picking a specific list is
                            // the right-click path below.
                            LibraryRepository.toggleSaved(libraryItem)
                        }
                    }
                },
                onOpenLibraryPicker = selectedPosterActionTarget?.let { target ->
                    {
                        val preview = target.preview
                        val libraryItem = target.libraryItem ?: preview.toLibraryItem(savedAtEpochMs = 0L)
                        pickerItem = libraryItem
                        pickerTitle = preview.name
                        pickerTabs = LibraryRepository.libraryListTabs()
                        pickerMembership = pickerTabs.associate { it.key to false }
                        pickerPending = true
                        pickerError = null
                        showLibraryListPicker = true
                        coroutineScope.launch {
                            runCatching {
                                val snapshot = LibraryRepository.getMembershipSnapshot(libraryItem)
                                val tabs = LibraryRepository.libraryListTabs()
                                pickerTabs = tabs
                                pickerMembership = tabs.associate { tab ->
                                    tab.key to (snapshot[tab.key] == true)
                                }
                            }.onFailure { error ->
                                pickerError = error.message ?: getString(Res.string.trakt_lists_load_failed)
                            }
                            pickerPending = false
                        }
                        Unit
                    }
                },
                onStartRewatch = selectedPosterActionTarget?.preview
                    ?.takeIf {
                        simklAuthUiState.canUseRewatches &&
                            simklSettingsUiState.simklTrackRewatches &&
                            WatchingState.isPosterWatched(watchedUiState.watchedKeys, it)
                    }
                    ?.let { preview ->
                        {
                            coroutineScope.launch {
                                when (val result = SimklRewatchRepository.startRewatch(preview)) {
                                    is SimklRewatchStartResult.Started -> NuvioToastController.show(
                                        message = result.session.title,
                                        title = simklRewatchStartedLabel,
                                    )
                                    is SimklRewatchStartResult.Failed -> NuvioToastController.show(
                                        message = result.reason,
                                        title = simklRewatchFailedLabel,
                                        durationMillis = 4_000L,
                                    )
                                }
                            }
                        }
                    },
                rewatchLabel = simklRewatchActionLabel,
                onToggleWatched = {
                    selectedPosterActionTarget?.preview?.let { preview ->
                        coroutineScope.launch {
                            WatchingActions.togglePosterWatched(preview, origin = "poster menu")
                        }
                    }
                },
                zoomAnchor = selectedPosterAnchor,
                zoomHazeState = posterOverlayHazeState,
                contextMenuPosition = selectedPosterMenuPosition,
            )

            val selectedContinueWatching = selectedContinueWatchingForActions
            val selectedContinueWatchingHasLocalPlayback = remember(
                selectedContinueWatching,
                localLibraryUiState.items,
                downloadsUiState.items,
            ) {
                selectedContinueWatching?.let { item ->
                    val playableDownload = DownloadsRepository.findPlayableDownload(
                        parentMetaId = item.parentMetaId,
                        seasonNumber = item.seasonNumber,
                        episodeNumber = item.episodeNumber,
                        videoId = item.videoId,
                    )?.let(DownloadsRepository::playableLocalFileUri)
                    AppFeaturePolicy.downloadsEnabled &&
                        (
                            !playableDownload.isNullOrBlank() ||
                                LocalLibraryRepository.localStreamsFor(
                                    item.parentMetaId,
                                    item.videoId,
                                ).isNotEmpty()
                            )
                } == true
            }
            // Which of the two `useAlternate` values reaches the source picker depends on the
            // configured preference, so it is resolved here rather than hardcoded per entry.
            val continueWatchingAlternateOpensPicker = localLibraryUiState.playbackPreference
                .alternateOpensSourcePicker(selectedContinueWatchingHasLocalPlayback)
            val continueWatchingIsPlayable = selectedContinueWatching
                ?.isCloudLibraryContinueWatchingItem() == false
            // Only offered when there is a local file AND the default click is not already playing
            // it — with no file, or under the Local library preference, this entry would say
            // nothing the sheet does not already do.
            val continueWatchingAlternatePlayLabel = stringResource(Res.string.play_local_file)
                .takeIf {
                    continueWatchingIsPlayable &&
                        selectedContinueWatchingHasLocalPlayback &&
                        !continueWatchingAlternateOpensPicker
                }

            val continueWatchingUsesDetailsByDefault = selectedContinueWatching?.let { item ->
                continueWatchingPreferencesUiState.clickAction.opensDetails(
                    canOpenDetails = !item.isCloudLibraryContinueWatchingItem(),
                )
            } == true

            val continueWatchingPlaylistRow = selectedContinueWatchingForActions
                ?.let(PlaylistContinueWatchingSnapshot::rowOf)
                .orEmpty()
            val continueWatchingRowIsNextUp = continueWatchingPlaylistRow.isNotEmpty() &&
                continueWatchingPlaylistRow.all { it.isNextUp }
            NuvioContinueWatchingActionSheet(
                item = selectedContinueWatchingForActions,
                onAddToPlaylist = selectedContinueWatchingForActions
                    ?.toPlaylistEntryOrNull()
                    ?.let { entry ->
                        {
                            PlaylistAddController.request(
                                PlaylistAddTarget.Entries(label = entry.displayTitle(), entries = listOf(entry)),
                            )
                        }
                    },
                // "Bung all my new episodes into a playlist and press play": the whole row the card
                // is in, as drawn, in its order.
                addRowToPlaylistLabel = if (continueWatchingRowIsNextUp) {
                    "Add all of Up Next to playlist"
                } else {
                    "Add all of Continue Watching to playlist"
                },
                onAddRowToPlaylist = playlistAddTargetForContinueWatching(
                    items = continueWatchingPlaylistRow,
                    label = if (continueWatchingRowIsNextUp) "Up Next" else "Continue Watching",
                )
                    ?.takeIf { (it as PlaylistAddTarget.Entries).entries.size > 1 }
                    ?.let { target -> { PlaylistAddController.request(target) } },
                primaryPlayLabel = stringResource(Res.string.cw_action_play)
                    .takeIf { continueWatchingUsesDetailsByDefault },
                alternatePlayLabel = continueWatchingAlternatePlayLabel,
                showDetailsOption = selectedContinueWatchingForActions?.isCloudLibraryContinueWatchingItem() != true,
                onDismiss = {
                    selectedContinueWatchingForActions = null
                    selectedContinueWatchingAnchor = null
                    selectedContinueWatchingMenuPosition = null
                },
                onOpenDetails = {
                    selectedContinueWatchingForActions?.let { item ->
                        navController.navigateIfResumed(
                            DetailRoute(
                                type = item.parentMetaType,
                                id = item.parentMetaId,
                            ),
                        )
                    }
                },
                onPrimaryPlay = selectedContinueWatchingForActions
                    ?.takeIf { continueWatchingUsesDetailsByDefault }
                    ?.let { item -> { onContinueWatchingPlay(item) } },
                onStartFromBeginning = selectedContinueWatchingForActions
                    ?.takeIf { !it.isNextUp }
                    ?.let { item -> { onContinueWatchingStartFromBeginning(item) } },
                onAlternatePlay = selectedContinueWatchingForActions
                    ?.takeIf { continueWatchingAlternatePlayLabel != null }
                    ?.let { item -> { openContinueWatching(item, true, false) } },
                // Always offered, whatever the local-library preference is: this entry is what
                // overrides stream auto-play, which is not a local-library concern.
                onChooseSource = selectedContinueWatchingForActions
                    ?.takeIf { continueWatchingIsPlayable }
                    ?.let { item ->
                        { openContinueWatching(item, continueWatchingAlternateOpensPicker, false) }
                    },
                onResync = {
                    coroutineScope.launch {
                        runCatching {
                            WatchProgressRepository.forceContinueWatchingSync(ProfileRepository.activeProfileId)
                        }.onFailure { error ->
                            if (error is CancellationException) throw error
                            appStartupLog.e(error) { "Manual Continue Watching resync failed" }
                        }
                    }
                },
                onRemove = {
                    selectedContinueWatchingForActions?.let(WatchProgressRepository::dismissContinueWatchingCard)
                },
                zoomAnchor = selectedContinueWatchingAnchor,
                zoomHazeState = posterOverlayHazeState,
                contextMenuPosition = selectedContinueWatchingMenuPosition,
            )

            TraktListPickerDialog(
                visible = showLibraryListPicker,
                title = pickerTitle,
                tabs = pickerTabs,
                membership = pickerMembership,
                isPending = pickerPending,
                errorMessage = pickerError,
                onToggle = { listKey ->
                    pickerMembership = pickerMembership.toMutableMap().apply {
                        this[listKey] = !(this[listKey] == true)
                    }
                },
                onDismiss = {
                    if (!pickerPending) {
                        showLibraryListPicker = false
                        pickerItem = null
                        pickerError = null
                    }
                },
                onSave = {
                    val item = pickerItem ?: return@TraktListPickerDialog
                    coroutineScope.launch {
                        pickerPending = true
                        pickerError = null
                        runCatching {
                            LibraryRepository.applyMembershipChanges(
                                item = item,
                                desiredMembership = pickerMembership,
                            )
                        }.onSuccess {
                            showLibraryListPicker = false
                            pickerItem = null
                            pickerError = null
                        }.onFailure { error ->
                            pickerError = error.message ?: getString(Res.string.trakt_lists_update_failed)
                        }
                        pickerPending = false
                    }
                },
            )

            NuvioStatusModal(
                title = stringResource(Res.string.app_exit_title),
                message = stringResource(Res.string.app_exit_message),
                isVisible = showExitConfirmation,
                confirmText = stringResource(Res.string.action_yes),
                dismissText = stringResource(Res.string.action_no),
                onConfirm = {
                    showExitConfirmation = false
                    platformExitApp()
                },
                onDismiss = {
                    showExitConfirmation = false
                },
            )

            androidx.compose.animation.AnimatedVisibility(
                visible = !initialHomeReady || profileSwitchLoading,
                enter = fadeIn(),
                exit = fadeOut(androidx.compose.animation.core.tween(400)),
            ) {
                AppLaunchOverlay(modifier = Modifier.fillMaxSize())
            }

            NuvioFloatingPrompt(
                visible = resumePromptItem != null && !isDesktop,
                imageUrl = resumePromptItem?.poster ?: resumePromptItem?.imageUrl,
                title = resumePromptItem?.title.orEmpty(),
                subtitle = resumePromptItem?.let { localizedContinueWatchingSubtitle(it) }.orEmpty(),
                progressFraction = resumePromptItem?.progressFraction ?: 0f,
                actionLabel = stringResource(Res.string.resume_prompt_action),
                onAction = {
                    val item = resumePromptItem ?: return@NuvioFloatingPrompt
                    resumePromptItem = null
                    openContinueWatching(item, false, false)
                },
                onDismiss = { resumePromptItem = null },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(15f),
            )

            NuvioResumePromptDialog(
                visible = resumePromptItem != null && isDesktop && !resumePromptUsesHero &&
                    !resumePromptHeroModeActive,
                imageUrl = resumePromptItem?.poster ?: resumePromptItem?.imageUrl,
                title = resumePromptItem?.title.orEmpty(),
                subtitle = resumePromptItem?.let { localizedContinueWatchingSubtitle(it) }.orEmpty(),
                progressFraction = resumePromptItem?.progressFraction ?: 0f,
                prompt = stringResource(Res.string.resume_prompt_question),
                actionLabel = stringResource(Res.string.resume_prompt_action),
                dismissLabel = stringResource(Res.string.action_close),
                onAction = {
                    val item = resumePromptItem
                    if (item != null) {
                        resumePromptItem = null
                        openContinueWatching(item, false, false)
                    }
                },
                onDismiss = { resumePromptItem = null },
            )

            // The source picker. It used to be a route of its own; now it is drawn over whatever
            // opened it (see StreamPickerController), above the NavHost and below the toasts.
            val streamPickerDock by StreamPickerController.dock.collectAsStateWithLifecycle()
            val streamPickerSession: @Composable (StreamPickerRequest) -> Unit = session@{ request ->
                val launch = request.launch
                val pauseDescription = launch.pauseDescription
                val streamRouteScope = rememberCoroutineScope()
                var resolvingDebridStream by rememberSaveable(request.launchId) { mutableStateOf(false) }
                var pendingP2pStreamOpen by remember { mutableStateOf<PendingP2pStreamOpen?>(null) }
                val resolvedLaunchEpisode = remember(
                    launch.type,
                    launch.parentMetaId,
                    launch.videoId,
                    launch.streamVideoId,
                    launch.title,
                    launch.seasonNumber,
                    launch.episodeNumber,
                ) {
                    MediaIdResolver.resolveLocalEpisodeIdentity(
                        contentType = launch.type,
                        parentMetaId = launch.parentMetaId ?: launch.videoId,
                        videoId = launch.streamVideoId?.takeIf { it.isNotBlank() } ?: launch.videoId,
                        title = launch.title,
                        season = launch.seasonNumber,
                        episode = launch.episodeNumber,
                        isAnimeHint = launch.type.equals("anime", ignoreCase = true),
                    )
                }
                val canonicalSeasonNumber = resolvedLaunchEpisode.season ?: launch.seasonNumber
                val canonicalEpisodeNumber = resolvedLaunchEpisode.episode ?: launch.episodeNumber
                val streamLookupSeasonNumber = resolvedLaunchEpisode.streamSeason ?: launch.seasonNumber
                val streamLookupEpisodeNumber = resolvedLaunchEpisode.streamEpisode ?: launch.episodeNumber
                val streamLookupVideoId = resolvedLaunchEpisode.videoId
                val canonicalLaunchVideoId = resolvedLaunchEpisode.canonicalVideoId
                val shouldResolveEpisodeVideoId =
                    launch.parentMetaId != null &&
                        canonicalSeasonNumber != null &&
                        canonicalEpisodeNumber != null
                var effectiveVideoId by rememberSaveable(
                    launch.videoId,
                    launch.parentMetaId,
                    canonicalSeasonNumber,
                    canonicalEpisodeNumber,
                ) {
                    mutableStateOf(launch.videoId)
                }
                var hasResolvedVideoId by rememberSaveable(
                    launch.videoId,
                    launch.parentMetaId,
                    canonicalSeasonNumber,
                    canonicalEpisodeNumber,
                ) {
                    mutableStateOf(!shouldResolveEpisodeVideoId)
                }

                LaunchedEffect(
                    launch.videoId,
                    launch.parentMetaId,
                    launch.parentMetaType,
                    launch.type,
                    canonicalSeasonNumber,
                    canonicalEpisodeNumber,
                ) {
                    effectiveVideoId = launch.videoId
                    if (!shouldResolveEpisodeVideoId) {
                        hasResolvedVideoId = true
                        return@LaunchedEffect
                    }

                    hasResolvedVideoId = false
                    val metaType = launch.parentMetaType ?: launch.type
                    val metaId = launch.parentMetaId
                    // Resolve against the *canonical* (franchise) coordinates, never the
                    // entry-local stream ones. Native anime ids carry no season, so
                    // streamLookupSeasonNumber falls back to the franchise season and would
                    // pair it with a cour-local episode: playing Dr. STONE S04E25
                    // (kitsu:50187:1) matched the S04E01 row, and everything keyed off the
                    // active video — the next/previous episode cards, autoplay, the sources
                    // panel — then walked the franchise from there. Share the player's own
                    // resolver so the two can't disagree: it matches the metadata row id
                    // first (the strongest evidence, and what the details list handed us),
                    // then the canonical coordinates.
                    //
                    // Only the addon's video rows matter here, and TMDB enrichment never changes
                    // their ids or coordinates — so take any record already held, else the
                    // un-enriched fetch. The enriched one gates this screen on TMDB: a long
                    // series asks for a trailer list per season, those queue behind Home's own
                    // TMDB traffic, and South Park sat out the full 5 s enrich timeout before
                    // the source list could even compose.
                    val resolvedVideoId = runCatching {
                        MetaDetailsRepository.peekAny(metaType, metaId)
                            ?.takeIf { it.videos.isNotEmpty() }
                            ?: MetaDetailsRepository.fetch(metaType, metaId, enrichTmdb = false)
                    }.getOrNull()
                        ?.videos
                        ?.resolveSeriesEpisodePosition(
                            parentMetaId = metaId,
                            videoId = launch.videoId,
                            seasonNumber = canonicalSeasonNumber,
                            episodeNumber = canonicalEpisodeNumber,
                        )
                        ?.video
                        ?.id
                        ?.takeIf { it.isNotBlank() }

                    effectiveVideoId = resolvedVideoId ?: canonicalLaunchVideoId
                    hasResolvedVideoId = true
                }

                val playerSettings by PlayerSettingsRepository.uiState.collectAsStateWithLifecycle()
                val effectiveStreamVideoId = streamLookupVideoId.takeIf { it.isNotBlank() } ?: effectiveVideoId

                fun p2pSentinelUrl(infoHash: String, fileIdx: Int?): String =
                    "torrent://$infoHash${fileIdx?.let { "?index=$it" }.orEmpty()}"

                fun openP2pStream(
                    stream: StreamItem,
                    resolvedResumePositionMs: Long?,
                    resolvedResumeProgressFraction: Float?,
                ) {
                    val infoHash = stream.p2pInfoHash ?: return
                    val sentinelUrl = p2pSentinelUrl(infoHash, stream.p2pFileIdx)
                    if (StreamLinkCacheRepository.shouldSaveLinks(playerSettings.streamReuseLastLinkEnabled)) {
                        val cacheKey = StreamLinkCacheRepository.contentKey(
                            type = launch.type,
                            videoId = effectiveStreamVideoId,
                            parentMetaId = launch.parentMetaId,
                            season = streamLookupSeasonNumber,
                            episode = streamLookupEpisodeNumber,
                        )
                        StreamLinkCacheRepository.save(
                            contentKey = cacheKey,
                            url = "",
                            streamName = stream.streamLabel,
                            addonName = stream.addonName,
                            addonId = stream.addonId,
                            requestHeaders = emptyMap(),
                            responseHeaders = emptyMap(),
                            filename = stream.behaviorHints.filename,
                            videoSize = stream.behaviorHints.videoSize,
                            infoHash = infoHash,
                            fileIdx = stream.p2pFileIdx,
                            sources = stream.sources,
                            bingeGroup = stream.behaviorHints.bingeGroup,
                        )
                    }
                    val playerLaunch = PlayerLaunch(
                        title = launch.title,
                        sourceUrl = sentinelUrl,
                        sourceHeaders = emptyMap(),
                        sourceResponseHeaders = emptyMap(),
                        streamType = stream.streamType,
                        sourceAffinity = if (stream.streamType.equals("local", ignoreCase = true)) {
                            PlayerSourceAffinity.Local
                        } else {
                            launch.sourceAffinity
                        },
                        logo = launch.logo,
                        poster = launch.poster,
                        background = launch.background,
                        releaseYear = launch.releaseYear,
                        seasonNumber = canonicalSeasonNumber,
                        episodeNumber = canonicalEpisodeNumber,
                        episodeTitle = launch.episodeTitle,
                        episodeThumbnail = launch.episodeThumbnail,
                        streamTitle = stream.streamLabel,
                        streamFilename = stream.mediaFilename,
                        streamSubtitle = stream.streamSubtitle,
                        sourceIdentityKey = stream.playerSourceIdentityKey(),
                        bingeGroup = stream.behaviorHints.bingeGroup,
                        pauseDescription = pauseDescription,
                        providerName = stream.addonName,
                        providerAddonId = stream.addonId,
                        contentType = launch.type,
                        videoId = effectiveVideoId,
                        parentMetaId = launch.parentMetaId ?: effectiveVideoId,
                        parentMetaType = launch.parentMetaType ?: launch.type,
                        watchProgressSource = launch.watchProgressSource,
                        torrentInfoHash = infoHash,
                        torrentFileIdx = stream.p2pFileIdx,
                        torrentFilename = stream.behaviorHints.filename,
                        torrentTrackers = stream.p2pTrackers,
                        initialPositionMs = resolvedResumePositionMs ?: 0L,
                        initialProgressFraction = resolvedResumeProgressFraction,
                        disableProgressTracking = launch.disableProgressTracking,
                        autoPlayMode = launch.autoPlayMode,
                        stream = stream,
                    )

                    StreamsRepository.cancelLoading()
                    // Playback starting is the picker's job done, manual pick or not: leaving the
                    // player returns to the page as it was, and sources switch in the player.
                    StreamPickerController.close(clearStreams = false)
                    navController.navigateToPlayer(playerLaunch)
                }

                fun requestOrOpenP2pStream(
                    stream: StreamItem,
                    resolvedResumePositionMs: Long?,
                    resolvedResumeProgressFraction: Float?,
                    forceExternal: Boolean,
                    forceInternal: Boolean,
                    isAutoPlay: Boolean,
                ) {
                    if (stream.p2pInfoHash == null) {
                        if (isAutoPlay) StreamsRepository.skipAutoPlayStream(stream)
                        return
                    }
                    if (!P2pSettingsRepository.isVisible) {
                        if (isAutoPlay) StreamsRepository.skipAutoPlayStream(stream)
                        return
                    }
                    if (!p2pSettingsUiState.p2pEnabled) {
                        pendingP2pStreamOpen = PendingP2pStreamOpen(
                            stream = stream,
                            resumePositionMs = resolvedResumePositionMs,
                            resumeProgressFraction = resolvedResumeProgressFraction,
                            forceExternal = forceExternal,
                            forceInternal = forceInternal,
                            isAutoPlay = isAutoPlay,
                        )
                        return
                    }
                    openP2pStream(
                        stream = stream,
                        resolvedResumePositionMs = resolvedResumePositionMs,
                        resolvedResumeProgressFraction = resolvedResumeProgressFraction,
                    )
                }

                // Reuse Last Link: auto-play from cache if enabled (only on first entry)
                var reuseHandled by rememberSaveable(launch.videoId, effectiveVideoId) { mutableStateOf(false) }
                var reuseNavigated by remember { mutableStateOf(false) }
                LaunchedEffect(effectiveVideoId, hasResolvedVideoId, playerSettings.streamReuseLastLinkEnabled, launch.manualSelection) {
                    if (!hasResolvedVideoId) return@LaunchedEffect
                    if (reuseHandled) return@LaunchedEffect
                    reuseHandled = true
                    // Continue Watching's source shortcut asks for the last link explicitly, whatever
                    // the setting and the local-library routing say.
                    if (!launch.reuseLastLink) {
                        if (launch.manualSelection) return@LaunchedEffect
                        if (!playerSettings.streamReuseLastLinkEnabled) return@LaunchedEffect
                    }
                    val cacheKey = StreamLinkCacheRepository.contentKey(
                        type = launch.type,
                        videoId = effectiveStreamVideoId,
                        parentMetaId = launch.parentMetaId,
                        season = streamLookupSeasonNumber,
                        episode = streamLookupEpisodeNumber,
                    )
                    val maxAgeMs = playerSettings.streamReuseLastLinkCacheHours * 60L * 60L * 1000L
                    val cached = StreamLinkCacheRepository.getValid(cacheKey, maxAgeMs)
                    if (cached != null) {
                        if (cached.url.isBlank() && !cached.infoHash.isNullOrBlank()) {
                            val cachedStream = StreamItem(
                                name = cached.streamName,
                                url = null,
                                infoHash = cached.infoHash,
                                fileIdx = cached.fileIdx,
                                sources = cached.sources,
                                addonName = cached.addonName,
                                addonId = cached.addonId,
                                behaviorHints = StreamBehaviorHints(
                                    filename = cached.filename,
                                    videoSize = cached.videoSize,
                                    bingeGroup = cached.bingeGroup,
                                ),
                            )
                            requestOrOpenP2pStream(
                                stream = cachedStream,
                                resolvedResumePositionMs = launch.resumePositionMs,
                                resolvedResumeProgressFraction = launch.resumeProgressFraction,
                                forceExternal = false,
                                forceInternal = true,
                                isAutoPlay = true,
                            )
                            reuseNavigated = true
                            return@LaunchedEffect
                        }
                        val cachedDirectStream = StreamItem(
                            name = cached.streamName,
                            url = cached.url,
                            addonName = cached.addonName,
                            addonId = cached.addonId,
                            streamType = cached.streamType,
                            behaviorHints = StreamBehaviorHints(
                                filename = cached.filename,
                                videoSize = cached.videoSize,
                                bingeGroup = cached.bingeGroup,
                            ),
                        )
                        val playerLaunch = PlayerLaunch(
                                title = launch.title,
                                sourceUrl = cached.url,
                                sourceHeaders = sanitizePlaybackHeaders(cached.requestHeaders),
                                sourceResponseHeaders = sanitizePlaybackResponseHeaders(cached.responseHeaders),
                                streamType = cached.streamType,
                                sourceAffinity = if (cached.streamType.equals("local", ignoreCase = true)) {
                                    PlayerSourceAffinity.Local
                                } else {
                                    launch.sourceAffinity
                                },
                                logo = launch.logo,
                                poster = launch.poster,
                                background = launch.background,
                                seasonNumber = canonicalSeasonNumber,
                                episodeNumber = canonicalEpisodeNumber,
                                episodeTitle = launch.episodeTitle,
                                episodeThumbnail = launch.episodeThumbnail,
                                streamTitle = cached.streamName,
                                streamSubtitle = null,
                                // Entries cached before the key was stored fall back to the
                                // filename + size identity, which a fresh listing also produces.
                                sourceIdentityKey = cached.sourceIdentityKey
                                    ?: cachedDirectStream.playerSourceIdentityKey(),
                                bingeGroup = cached.bingeGroup,
                                pauseDescription = pauseDescription,
                                providerName = cached.addonName,
                                providerAddonId = cached.addonId,
                                contentType = launch.type,
                                videoId = effectiveVideoId,
                                parentMetaId = launch.parentMetaId ?: effectiveVideoId,
                                parentMetaType = launch.parentMetaType ?: launch.type,
                                watchProgressSource = launch.watchProgressSource,
                                initialPositionMs = launch.resumePositionMs ?: 0L,
                                initialProgressFraction = launch.resumeProgressFraction,
                                disableProgressTracking = launch.disableProgressTracking,
                                autoPlayMode = launch.autoPlayMode,
                                stream = cachedDirectStream,
                            )
                        if (playerSettings.externalPlayerEnabled) {
                            openExternalPlayback(playerLaunch)
                            StreamsRepository.setOverlayVisible(false)
                            reuseNavigated = true
                            return@LaunchedEffect
                        }
                        StreamsRepository.clear()
                        reuseNavigated = true
                        StreamPickerController.close(clearStreams = false)
                        navController.navigateToPlayer(playerLaunch)
                    }
                }

                val streamsUiState by StreamsRepository.uiState.collectAsStateWithLifecycle()
                val expectedStreamsRequestToken = StreamsRepository.requestToken(
                    type = launch.type,
                    videoId = effectiveStreamVideoId,
                    parentMetaId = launch.parentMetaId,
                    title = launch.title,
                    season = streamLookupSeasonNumber,
                    episode = streamLookupEpisodeNumber,
                    manualSelection = launch.manualSelection,
                )
                var autoPlayHandled by rememberSaveable(launch.videoId, effectiveVideoId) { mutableStateOf(false) }
                LaunchedEffect(
                    streamsUiState.autoPlayStream,
                    streamsUiState.requestToken,
                    expectedStreamsRequestToken,
                    reuseHandled,
                    launch.manualSelection,
                ) {
                    if (!reuseHandled) return@LaunchedEffect
                    if (launch.manualSelection) return@LaunchedEffect
                    if (reuseNavigated) return@LaunchedEffect
                    if (autoPlayHandled) return@LaunchedEffect
                    if (streamsUiState.requestToken != expectedStreamsRequestToken) return@LaunchedEffect
                    val selectedStream = streamsUiState.autoPlayStream ?: return@LaunchedEffect
                    val selectedSourceIdentityKey = selectedStream.playerSourceIdentityKey()
                    val stream = if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(selectedStream)) {
                        when (
                            val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(
                                stream = selectedStream,
                                season = streamLookupSeasonNumber,
                                episode = streamLookupEpisodeNumber,
                            )
                        ) {
                            is DirectDebridPlayableResult.Success -> resolved.stream
                            else -> {
                                val hasNextCandidate = StreamsRepository.skipAutoPlayStream(selectedStream)
                                if (!hasNextCandidate) {
                                    resolved.toastMessage()?.let { NuvioToastController.show(it) }
                                }
                                if (!hasNextCandidate && resolved == DirectDebridPlayableResult.Stale) {
                                    StreamsRepository.reload(
                                        type = launch.type,
                                        videoId = effectiveStreamVideoId,
                                        parentMetaId = launch.parentMetaId,
                                        title = launch.title,
                                        season = streamLookupSeasonNumber,
                                        episode = streamLookupEpisodeNumber,
                                        manualSelection = launch.manualSelection,
                                        preferLocalStreams = launch.preferLocalStreams,
                                    )
                                }
                                return@LaunchedEffect
                            }
                        }
                    } else {
                        selectedStream
                    }
                    val sourceUrl = stream.playableDirectUrl
                    if (sourceUrl == null && stream.needsLocalDebridResolve && stream.p2pInfoHash != null) {
                        autoPlayHandled = true
                        requestOrOpenP2pStream(
                            stream = stream,
                            resolvedResumePositionMs = launch.resumePositionMs,
                            resolvedResumeProgressFraction = launch.resumeProgressFraction,
                            forceExternal = false,
                            forceInternal = true,
                            isAutoPlay = true,
                        )
                        StreamsRepository.consumeAutoPlay()
                        return@LaunchedEffect
                    }
                    if (sourceUrl == null) {
                        StreamsRepository.skipAutoPlayStream(selectedStream)
                        return@LaunchedEffect
                    }
                    autoPlayHandled = true
                    if (StreamLinkCacheRepository.shouldSaveLinks(playerSettings.streamReuseLastLinkEnabled)) {
                        val cacheKey = StreamLinkCacheRepository.contentKey(
                            type = launch.type,
                            videoId = effectiveStreamVideoId,
                            parentMetaId = launch.parentMetaId,
                            season = streamLookupSeasonNumber,
                            episode = streamLookupEpisodeNumber,
                        )
                        StreamLinkCacheRepository.save(
                            contentKey = cacheKey,
                            url = sourceUrl,
                            streamName = stream.streamLabel,
                            addonName = stream.addonName,
                            addonId = stream.addonId,
                            requestHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request),
                            responseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
                            filename = stream.behaviorHints.filename,
                            videoSize = stream.behaviorHints.videoSize,
                            bingeGroup = stream.behaviorHints.bingeGroup,
                            streamType = stream.streamType,
                            sourceIdentityKey = selectedSourceIdentityKey,
                        )
                    }
                    val playerLaunch = PlayerLaunch(
                            title = launch.title,
                            sourceUrl = sourceUrl,
                            sourceHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request),
                            sourceResponseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
                            streamType = stream.streamType,
                            sourceAffinity = if (stream.streamType.equals("local", ignoreCase = true)) {
                                PlayerSourceAffinity.Local
                            } else {
                                launch.sourceAffinity
                            },
                            logo = launch.logo,
                            poster = launch.poster,
                            background = launch.background,
                            seasonNumber = canonicalSeasonNumber,
                            episodeNumber = canonicalEpisodeNumber,
                            episodeTitle = launch.episodeTitle,
                            episodeThumbnail = launch.episodeThumbnail,
                            streamTitle = stream.streamLabel,
                            streamFilename = stream.mediaFilename,
                            streamSubtitle = stream.streamSubtitle,
                            sourceIdentityKey = selectedSourceIdentityKey,
                            bingeGroup = stream.behaviorHints.bingeGroup,
                            pauseDescription = pauseDescription,
                            providerName = stream.addonName,
                            providerAddonId = stream.addonId,
                            contentType = launch.type,
                            videoId = effectiveVideoId,
                            parentMetaId = launch.parentMetaId ?: effectiveVideoId,
                            parentMetaType = launch.parentMetaType ?: launch.type,
                            watchProgressSource = launch.watchProgressSource,
                            initialPositionMs = launch.resumePositionMs ?: 0L,
                            initialProgressFraction = launch.resumeProgressFraction,
                            disableProgressTracking = launch.disableProgressTracking,
                            autoPlayMode = launch.autoPlayMode,
                            stream = stream,
                        )
                    if (playerSettings.externalPlayerEnabled) {
                        openExternalPlayback(playerLaunch)
                        StreamsRepository.consumeAutoPlay()
                        StreamsRepository.cancelLoading()
                        return@LaunchedEffect
                    }
                    StreamsRepository.consumeAutoPlay()
                    StreamsRepository.cancelLoading()
                    StreamPickerController.close(clearStreams = false)
                    navController.navigateToPlayer(playerLaunch)
                }

                // Drawn only while the screen that opened it is current. Starting playback or
                // leaving that screen closes the picker, but only on the next frame — without
                // this, that one frame drew it as the modal over the screen being navigated to.
                val streamPickerEntry = currentBackStackEntry
                val streamPickerHidden = streamPickerEntry?.id != request.ownerEntryId
                // Which dock this request may use: its own details page, or the TV-mode home hero
                // when it was opened from Home (continue watching). Anything else is the modal.
                val streamPickerDockKey = when {
                    streamPickerEntry?.id != request.ownerEntryId -> null
                    streamPickerEntry?.destination?.hasRoute<DetailRoute>() == true ->
                        StreamPickerDock.forMeta(launch.parentMetaId ?: launch.videoId)
                    streamPickerEntry?.destination?.hasRoute<TabsRoute>() == true &&
                        selectedTab == AppScreenTab.Home -> StreamPickerDock.HOME
                    else -> null
                }
                val streamPickerPlacement = streamPickerDock
                    ?.takeIf { dock -> streamPickerDockKey != null && dock.ownerKey == streamPickerDockKey }
                    ?.let { dock -> StreamPickerPlacement.Docked(dock.bounds) }
                    ?: StreamPickerPlacement.Modal

                if (!hasResolvedVideoId) {
                    if (!streamPickerHidden) {
                        StreamPickerFrame(
                            placement = streamPickerPlacement,
                            onDismiss = { StreamPickerController.close() },
                            animateInKey = request.launchId,
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(color = MaterialTheme.nuvio.colors.accent)
                            }
                        }
                    }
                    return@session
                }

                fun openSelectedStream(
                    stream: StreamItem,
                    resolvedResumePositionMs: Long?,
                    resolvedResumeProgressFraction: Float?,
                    forceExternal: Boolean,
                    forceInternal: Boolean,
                    sourceIdentityKey: String? = stream.playerSourceIdentityKey(),
                    // False for a watch-party host's shared link: it can carry their debrid key,
                    // so it must not end up in this user's "reuse last link" cache.
                    saveForReuse: Boolean = true,
                    // "Play on device…": resolve as usual, then hand the stream to the cast picker.
                    castToDevice: Boolean = false,
                ) {
                    if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) {
                        if (resolvingDebridStream) return
                        streamRouteScope.launch {
                            resolvingDebridStream = true
                            val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(
                                stream = stream,
                                season = streamLookupSeasonNumber,
                                episode = streamLookupEpisodeNumber,
                            )
                            resolvingDebridStream = false
                            when (resolved) {
                                is DirectDebridPlayableResult.Success -> openSelectedStream(
                                    stream = resolved.stream,
                                    resolvedResumePositionMs = resolvedResumePositionMs,
                                    resolvedResumeProgressFraction = resolvedResumeProgressFraction,
                                    forceExternal = forceExternal,
                                    forceInternal = forceInternal,
                                    sourceIdentityKey = sourceIdentityKey,
                                    castToDevice = castToDevice,
                                )
                                else -> {
                                    resolved.toastMessage()?.let { NuvioToastController.show(it) }
                                    if (resolved == DirectDebridPlayableResult.Stale) {
                                        StreamsRepository.reload(
                                            type = launch.type,
                                            videoId = effectiveStreamVideoId,
                                            parentMetaId = launch.parentMetaId,
                                            title = launch.title,
                                            season = streamLookupSeasonNumber,
                                            episode = streamLookupEpisodeNumber,
                                            manualSelection = launch.manualSelection,
                                            preferLocalStreams = launch.preferLocalStreams,
                                        )
                                    }
                                }
                            }
                        }
                        return
                    }
                    if (stream.needsLocalDebridResolve && stream.p2pInfoHash != null) {
                        if (castToDevice) {
                            NuvioToastController.show("P2P sources can't be played on another device yet")
                            return
                        }
                        requestOrOpenP2pStream(
                            stream = stream,
                            resolvedResumePositionMs = resolvedResumePositionMs,
                            resolvedResumeProgressFraction = resolvedResumeProgressFraction,
                            forceExternal = forceExternal,
                            forceInternal = forceInternal,
                            isAutoPlay = false,
                        )
                        return
                    }
                    val sourceUrl = stream.playableDirectUrl ?: return
                    if (StreamLinkCacheRepository.shouldSaveLinks(playerSettings.streamReuseLastLinkEnabled) && saveForReuse) {
                        val cacheKey = StreamLinkCacheRepository.contentKey(
                            type = launch.type,
                            videoId = effectiveStreamVideoId,
                            parentMetaId = launch.parentMetaId,
                            season = streamLookupSeasonNumber,
                            episode = streamLookupEpisodeNumber,
                        )
                        StreamLinkCacheRepository.save(
                            contentKey = cacheKey,
                            url = sourceUrl,
                            streamName = stream.streamLabel,
                            addonName = stream.addonName,
                            addonId = stream.addonId,
                            requestHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request),
                            responseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
                            filename = stream.behaviorHints.filename,
                            videoSize = stream.behaviorHints.videoSize,
                            bingeGroup = stream.behaviorHints.bingeGroup,
                            streamType = stream.streamType,
                            sourceIdentityKey = sourceIdentityKey,
                        )
                    }
                    val playerLaunch = PlayerLaunch(
                        title = launch.title,
                        sourceUrl = sourceUrl,
                        sourceHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request),
                        sourceResponseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
                        streamType = stream.streamType,
                        sourceAffinity = if (stream.streamType.equals("local", ignoreCase = true)) {
                            PlayerSourceAffinity.Local
                        } else {
                            launch.sourceAffinity
                        },
                        logo = launch.logo,
                        poster = launch.poster,
                        background = launch.background,
                        releaseYear = launch.releaseYear,
                        seasonNumber = canonicalSeasonNumber,
                        episodeNumber = canonicalEpisodeNumber,
                        episodeTitle = launch.episodeTitle,
                        episodeThumbnail = launch.episodeThumbnail,
                        streamTitle = stream.streamLabel,
                        streamFilename = stream.mediaFilename,
                        streamSubtitle = stream.streamSubtitle,
                        sourceIdentityKey = sourceIdentityKey,
                        bingeGroup = stream.behaviorHints.bingeGroup,
                        pauseDescription = pauseDescription,
                        providerName = stream.addonName,
                        providerAddonId = stream.addonId,
                        contentType = launch.type,
                        videoId = effectiveVideoId,
                        parentMetaId = launch.parentMetaId ?: effectiveVideoId,
                        parentMetaType = launch.parentMetaType ?: launch.type,
                        watchProgressSource = launch.watchProgressSource,
                        initialPositionMs = resolvedResumePositionMs ?: 0L,
                        initialProgressFraction = resolvedResumeProgressFraction,
                        disableProgressTracking = launch.disableProgressTracking,
                        autoPlayMode = launch.autoPlayMode,
                        stream = stream,
                    )

                    if (castToDevice) {
                        CastController.request(playerLaunch.toCastRequest())
                        return
                    }

                    if (!forceInternal && (forceExternal || playerSettings.externalPlayerEnabled)) {
                        coroutineScope.launch { openExternalPlayback(playerLaunch) }
                        StreamsRepository.cancelLoading()
                        return
                    }

                    StreamsRepository.cancelLoading()
                    // Closed for the in-app player only; an external player leaves the user here,
                    // where the list is still the way to try another source.
                    StreamPickerController.close(clearStreams = false)
                    navController.navigateToPlayer(playerLaunch)
                }

                // Hide overlay when reuse navigated to external player (prevents reload from showing it again)
                LaunchedEffect(reuseNavigated) {
                    if (reuseNavigated) {
                        StreamsRepository.setOverlayVisible(false)
                    }
                }

                val streamsOnBack: () -> Unit = { StreamPickerController.close() }
                if (streamPickerHidden) return@session
                // Watch party: mark the party's source and offer the host's shared stream when the
                // party is watching what this picker is for.
                val partyState by com.nuvio.app.features.party.PartyRepository.state.collectAsStateWithLifecycle()
                val partyStream by com.nuvio.app.features.party.PartyRepository.sharedStream.collectAsStateWithLifecycle()
                val party = partyState.party
                val partyContentKey = com.nuvio.app.features.party.partyMediaKey(
                    metaType = launch.parentMetaType ?: launch.type,
                    metaId = launch.parentMetaId ?: effectiveVideoId,
                    season = canonicalSeasonNumber,
                    episode = canonicalEpisodeNumber,
                )
                val partySource = party?.media?.takeIf { it.key == partyContentKey }?.source
                val partySourceLabel = stringResource(Res.string.party_source)
                val streamHighlight: (StreamItem) -> String? = remember(partySource, partySourceLabel) {
                    { stream ->
                        partySourceLabel.takeIf { partySource?.matches(stream) == true }
                    }
                }
                val sharedByLabel = stringResource(
                    Res.string.party_shared_by,
                    party?.member(party.hostMemberId)?.displayName.orEmpty(),
                )
                val partySharedStream = remember(partyStream, sharedByLabel, party?.isHost, partyContentKey) {
                    partyStream
                        ?.takeIf { party?.isHost == false && it.mediaKey == partyContentKey }
                        ?.let { com.nuvio.app.features.streams.SharedStream(label = sharedByLabel, stream = it.toStreamItem()) }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.nuvio.app.features.streams.LocalStreamHighlight provides streamHighlight,
                        com.nuvio.app.features.streams.LocalSharedStream provides partySharedStream,
                    ) {
                    StreamPickerFrame(
                        placement = streamPickerPlacement,
                        onDismiss = streamsOnBack,
                        animateInKey = request.launchId,
                    ) {
                        StreamsScreen(
                            type = launch.type,
                            videoId = effectiveStreamVideoId,
                            parentMetaId = launch.parentMetaId ?: effectiveVideoId,
                            parentMetaType = launch.parentMetaType ?: launch.type,
                            title = launch.title,
                            logo = launch.logo,
                            poster = launch.poster,
                            background = launch.background,
                            releaseYear = launch.releaseYear,
                            seasonNumber = canonicalSeasonNumber,
                            episodeNumber = canonicalEpisodeNumber,
                            episodeTitle = launch.episodeTitle,
                            episodeThumbnail = launch.episodeThumbnail,
                            resumePositionMs = launch.resumePositionMs,
                            resumeProgressFraction = launch.resumeProgressFraction,
                            manualSelection = launch.manualSelection,
                            preferLocalStreams = launch.preferLocalStreams,
                            startFromBeginning = launch.startFromBeginning,
                            onStreamSelected = { stream, resolvedResumePositionMs, resolvedResumeProgressFraction ->
                                openSelectedStream(
                                    stream = stream,
                                    resolvedResumePositionMs = resolvedResumePositionMs,
                                    resolvedResumeProgressFraction = resolvedResumeProgressFraction,
                                    forceExternal = false,
                                    forceInternal = false,
                                    saveForReuse = stream !== partySharedStream?.stream,
                                )
                            },
                            onStreamActionOpen = { stream, openExternally, resolvedResumePositionMs, resolvedResumeProgressFraction ->
                                openSelectedStream(
                                    stream = stream,
                                    resolvedResumePositionMs = resolvedResumePositionMs,
                                    resolvedResumeProgressFraction = resolvedResumeProgressFraction,
                                    forceExternal = openExternally,
                                    forceInternal = !openExternally,
                                )
                            },
                            onStreamActionCast = if (CastController.isSupported) {
                                { stream, resolvedResumePositionMs, resolvedResumeProgressFraction ->
                                    openSelectedStream(
                                        stream = stream,
                                        resolvedResumePositionMs = resolvedResumePositionMs,
                                        resolvedResumeProgressFraction = resolvedResumeProgressFraction,
                                        forceExternal = false,
                                        forceInternal = false,
                                        castToDevice = true,
                                    )
                                }
                            } else {
                                null
                            },
                            onBack = streamsOnBack,
                            presentation = StreamsPresentation.Panel,
                            // Docked, the panel fills the trailer area out to the window edges,
                            // so its content needs more room from them than the modal card's.
                            panelContentPadding = if (streamPickerPlacement is StreamPickerPlacement.Docked) {
                                // Top lines the chip row up with the hero beside it: the logo on
                                // the details page, the "Starring" header on the TV home hero.
                                PaddingValues(
                                    start = 28.dp,
                                    top = if (streamPickerDockKey == StreamPickerDock.HOME) 18.dp else 70.dp,
                                    end = 28.dp,
                                    bottom = 12.dp,
                                )
                            } else {
                                PaddingValues(16.dp)
                            },
                            panelGlass = streamPickerPlacement is StreamPickerPlacement.Docked,
                            // Two source cards side by side need ~600dp; a dock squeezed by a
                            // small window falls back to one column (the toggle stays saved).
                            panelTwoColumnsFit = (streamPickerPlacement as? StreamPickerPlacement.Docked)
                                ?.let { with(LocalDensity.current) { it.bounds.width.toDp() } >= 600.dp }
                                ?: true,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    }
                    pendingP2pStreamOpen?.let { pending ->
                        P2pConsentDialog(
                            onEnableP2p = {
                                P2pSettingsRepository.setP2pEnabled(true)
                                pendingP2pStreamOpen = null
                                openP2pStream(
                                    stream = pending.stream,
                                    resolvedResumePositionMs = pending.resumePositionMs,
                                    resolvedResumeProgressFraction = pending.resumeProgressFraction,
                                )
                            },
                            onDismiss = {
                                if (pending.isAutoPlay) {
                                    StreamsRepository.skipAutoPlayStream(pending.stream)
                                    StreamsRepository.consumeAutoPlay()
                                }
                                pendingP2pStreamOpen = null
                            },
                        )
                    }
                    if (resolvingDebridStream) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.nuvio.colors.overlayScrim.copy(alpha = MaterialTheme.nuvio.opacity.overlayHeavy)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(MaterialTheme.nuvio.spacing.cardPadding),
                            ) {
                                CircularProgressIndicator(color = MaterialTheme.nuvio.colors.playerControlsForeground)
                                Text(
                                    text = stringResource(Res.string.streams_finding_source),
                                    color = MaterialTheme.nuvio.colors.playerControlsForeground.copy(alpha = MaterialTheme.nuvio.opacity.overlayHeavy),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }

            streamPickerRequest?.let { request ->
                key(request.launchId) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(15f),
                    ) {
                        streamPickerSession(request)
                    }
                }
            }

            // Held back while the player is on screen: the prompt is queued the moment a title
            // finishes, which during a binge is mid auto-play into the next episode.
            RatingPromptHost(
                isSuppressed = currentBackStackEntry?.destination?.hasRoute<PlayerRoute>() == true ||
                    streamPickerRequest != null,
            )

            NuvioToastHost(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(20f),
            )

            AppUpdaterHost(
                controller = appUpdaterController,
                modifier = Modifier
                    .align(Alignment.Center)
                    .zIndex(25f),
            )

            ApiKeysOnboardingHost(
                modifier = Modifier
                    .align(Alignment.Center)
                    .zIndex(26f),
            )

            // Above the other startup overlays, though StartupOverlayCoordinator means only one of
            // the three is ever composed at a time.
            FirstRunWizardHost(
                modifier = Modifier
                    .align(Alignment.Center)
                    .zIndex(27f),
                onOpenKeyboardShortcuts = {
                    requestedSettingsPageName = "KeyboardShortcuts"
                    selectedTab = AppScreenTab.Settings
                },
            )

            // Game mode sits on top of a still-composed app rather than replacing it, so toggling
            // back lands on exactly the row and scroll position the user left. The wrapper is a
            // pointer-input node covering the whole window: without it, clicks and wheel events
            // over empty areas of the game library would fall through to the screen underneath.
            if (gameModeActive) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(40f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                ) {
                    GameModeScreen(
                        onExit = { GameModeController.setActive(false) },
                        onOpenSettings = {
                            GameModeController.setActive(false)
                            requestedSettingsPageName = "Games"
                            // The app behind game mode may be parked on a details route, so the
                            // tab host has to be brought forward before selecting a tab in it.
                            navController.navigate(TabsRoute) {
                                popUpTo<TabsRoute> { inclusive = false }
                                launchSingleTop = true
                            }
                            handleRootTabClick(AppScreenTab.Settings)
                        },
                    )
                }
            }
        }
}

@Composable
private fun rememberGuardedPopBackStack(
    navController: NavHostController,
    backStackEntry: NavBackStackEntry,
    beforePop: () -> Unit = {},
): () -> Unit {
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    var popHandled by remember(backStackEntry) { mutableStateOf(false) }

    return remember(navController, backStackEntry, currentBackStackEntry, popHandled, beforePop) {
        {
            if (!popHandled && currentBackStackEntry == backStackEntry) {
                popHandled = true
                beforePop()
                navController.popBackStack()
            }
        }
    }
}

private const val PLAYER_BACK_DUPLICATE_WINDOW_MS = 750L
private const val DETAIL_AUTO_PLAY_CONSUMED_KEY = "detailAutoPlayConsumed"

@Composable
private fun BindDiscordBrowsingPresence(
    currentBackStackEntry: NavBackStackEntry?,
    selectedTab: AppScreenTab,
    searchOverlayActive: Boolean,
    searchQuery: String,
    submittedSearchQuery: String,
) {
    DiscordPresenceSettingsRepository.ensureLoaded()
    val discordSettings by DiscordPresenceSettingsRepository.uiState.collectAsStateWithLifecycle()
    val detailsUiState by MetaDetailsRepository.uiState.collectAsStateWithLifecycle()
    val streamPickerRequest by StreamPickerController.request.collectAsStateWithLifecycle()
    val activity = remember(
        streamPickerRequest,
        currentBackStackEntry,
        selectedTab,
        searchOverlayActive,
        searchQuery,
        submittedSearchQuery,
        detailsUiState.meta,
        detailsUiState.isLoading,
    ) {
        val pickerLaunch = streamPickerRequest?.launch
        if (pickerLaunch != null && currentBackStackEntry?.destination?.hasRoute<PlayerRoute>() != true) {
            return@remember browsingActivity(
                title = "Choosing a stream for ${pickerLaunch.title}",
                subtitle = "Sources",
                imageUrl = pickerLaunch.poster ?: detailsUiState.meta?.poster,
            )
        }
        currentBackStackEntry?.toDiscordBrowsingActivity(
            selectedTab = selectedTab,
            searchOverlayActive = searchOverlayActive,
            searchQuery = searchQuery,
            submittedSearchQuery = submittedSearchQuery,
            detailTitle = detailsUiState.meta?.name,
            detailPoster = detailsUiState.meta?.poster,
        )
    }

    LaunchedEffect(discordSettings.showBrowsingPresence, activity) {
        if (discordSettings.showBrowsingPresence) {
            DiscordRichPresenceController.setBrowsingActivity(activity)
        } else {
            // Watching/Disabled: clear any previously-set browsing activity so it can't leak.
            DiscordRichPresenceController.setBrowsingActivity(null)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            DiscordRichPresenceController.setBrowsingActivity(null)
        }
    }
}

/**
 * The id a details route opens with for a TMDB-sourced card (cast page, company / badge browse).
 * The IMDb id when it is known within [DETAIL_ID_LOOKUP_BUDGET_MS] - instantly when cached - else the
 * `tmdb:` id as is, which the details page resolves itself (MetaDetailsRepository.resolveMetaLookupId,
 * same cached lookup) the way Home and Discover cards already open. These screens used to await the
 * TMDB external-ids request before navigating at all, so an uncached title sat on the old screen for
 * a full round trip before anything moved.
 */
private suspend fun detailRouteIdFor(preview: MetaPreview): String {
    val tmdbId = preview.id.takeIf { it.startsWith("tmdb:") }
        ?.removePrefix("tmdb:")
        ?.toIntOrNull()
        ?: return preview.id
    return withTimeoutOrNull(DETAIL_ID_LOOKUP_BUDGET_MS) {
        TmdbService.tmdbToImdb(tmdbId = tmdbId, mediaType = preview.type)
    } ?: preview.id
}

private const val DETAIL_ID_LOOKUP_BUDGET_MS = 150L

private fun NavBackStackEntry.toDiscordBrowsingActivity(
    selectedTab: AppScreenTab,
    searchOverlayActive: Boolean,
    searchQuery: String,
    submittedSearchQuery: String,
    detailTitle: String?,
    detailPoster: String?,
): DiscordRichPresenceActivity? {
    if (destination.hasRoute<PlayerRoute>()) return null

    return when {
        destination.hasRoute<TabsRoute>() -> tabsDiscordBrowsingActivity(
            selectedTab = selectedTab,
            searchOverlayActive = searchOverlayActive,
            searchQuery = searchQuery,
            submittedSearchQuery = submittedSearchQuery,
        )
        destination.hasRoute<DetailRoute>() -> {
            val route = routeOrNull<DetailRoute>()
            val title = detailTitle
                ?.takeIf { route == null || MetaDetailsRepository.peek(route.type, route.id)?.name == it }
                ?: route?.let { MetaDetailsRepository.peek(it.type, it.id)?.name }
            browsingActivity(
                title = title?.let { "Viewing $it" } ?: "Viewing details",
                subtitle = "Details",
                imageUrl = detailPoster,
            )
        }
        destination.hasRoute<PersonDetailRoute>() -> {
            val route = routeOrNull<PersonDetailRoute>()
            browsingActivity(
                title = route?.personName?.let { "Viewing $it" } ?: "Viewing a person",
                subtitle = "Person",
            )
        }
        destination.hasRoute<EntityBrowseRoute>() -> {
            val route = routeOrNull<EntityBrowseRoute>()
            browsingActivity(
                title = route?.entityName?.let { "Browsing $it" } ?: "Browsing titles",
                subtitle = "Discovery",
            )
        }
        destination.hasRoute<BadgeBrowseRoute>() -> {
            val route = routeOrNull<BadgeBrowseRoute>()
            browsingActivity(
                title = route?.title?.let { "Browsing $it" } ?: "Browsing titles",
                subtitle = "Discovery",
            )
        }
        destination.hasRoute<CatalogRoute>() -> {
            val route = routeOrNull<CatalogRoute>()
            browsingActivity(
                title = route?.title?.let { "Browsing $it" } ?: "Browsing a catalog",
                subtitle = route?.subtitle?.takeIf { it.isNotBlank() },
            )
        }
        destination.hasRoute<CalendarRoute>() -> browsingActivity("Viewing Calendar")
        destination.hasRoute<CollectionEditorRoute>() -> browsingActivity("Editing Collections")
        destination.hasRoute<FolderDetailRoute>() -> browsingActivity("Viewing a collection")
        destination.hasRoute<HomescreenSettingsRoute>() -> settingsBrowsingActivity("Home Screen")
        destination.hasRoute<MetaScreenSettingsRoute>() -> settingsBrowsingActivity("Meta Screen")
        destination.hasRoute<ContinueWatchingSettingsRoute>() -> settingsBrowsingActivity("Continue Watching")
        destination.hasRoute<AddonsSettingsRoute>() -> settingsBrowsingActivity("Addons")
        destination.hasRoute<PluginsSettingsRoute>() -> settingsBrowsingActivity("Plugins")
        destination.hasRoute<AccountSettingsRoute>() -> settingsBrowsingActivity("Account")
        destination.hasRoute<SupportersContributorsSettingsRoute>() -> settingsBrowsingActivity("Supporters")
        destination.hasRoute<LicensesAttributionsSettingsRoute>() -> settingsBrowsingActivity("Licenses")
        else -> browsingActivity("Browsing Nuvio")
    }
}

private fun tabsDiscordBrowsingActivity(
    selectedTab: AppScreenTab,
    searchOverlayActive: Boolean,
    searchQuery: String,
    submittedSearchQuery: String,
): DiscordRichPresenceActivity {
    val query = submittedSearchQuery.ifBlank { searchQuery }.trim()
    return when {
        searchOverlayActive || selectedTab == AppScreenTab.Search -> browsingActivity(
            title = query.takeIf { it.isNotBlank() }?.let { "Searching for $it" } ?: "Searching",
        )
        selectedTab == AppScreenTab.Library -> browsingActivity("Viewing Library")
        selectedTab == AppScreenTab.Discover -> browsingActivity("Browsing Discover")
        selectedTab == AppScreenTab.LiveTv -> browsingActivity("Browsing Live TV")
        selectedTab == AppScreenTab.Settings -> settingsBrowsingActivity()
        else -> browsingActivity("Browsing Nuvio")
    }
}

private fun settingsBrowsingActivity(page: String? = null): DiscordRichPresenceActivity =
    browsingActivity(
        title = "Changing settings",
        subtitle = page,
    )

private fun browsingActivity(
    title: String,
    subtitle: String? = null,
    imageUrl: String? = null,
): DiscordRichPresenceActivity =
    DiscordRichPresenceActivity(
        title = title,
        subtitle = subtitle,
        imageUrl = imageUrl,
        imageFit = if (imageUrl.isNullOrBlank()) {
            DiscordRichPresenceImageFit.Cover
        } else {
            DiscordRichPresenceImageFit.Contain
        },
        type = DiscordRichPresenceActivityType.Browsing,
    )

private inline fun <reified T : Any> NavBackStackEntry.routeOrNull(): T? =
    runCatching { toRoute<T>() }.getOrNull()

@Composable
private fun AppTabHost(
    selectedTab: AppScreenTab,
    modifier: Modifier = Modifier,
    topChromePadding: Dp? = null,
    searchFocusRequestCount: Int = 0,
    navigateToContentCount: Int = 0,
    searchQuery: String = "",
    submittedSearchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    searchSubmitRequests: Flow<String> = emptyFlow(),
    rootActionsEnabled: Boolean = true,
    homeScrollToTopRequests: Flow<Unit>,
    searchScrollToTopRequests: Flow<Unit>,
    libraryScrollToTopRequests: Flow<Unit>,
    discoverScrollToTopRequests: Flow<Unit>,
    settingsRootActionRequests: Flow<Unit>,
    animateHomeCollectionGifs: Boolean = true,
    resumePromptItem: ContinueWatchingItem? = null,
    resumePromptLabel: String = "",
    onResumePromptAction: (() -> Unit)? = null,
    onResumePromptDismiss: (() -> Unit)? = null,
    continueWatchingHeroDismissed: Boolean = false,
    onContinueWatchingHeroDismiss: () -> Unit = {},
    onCatalogClick: ((HomeCatalogSection) -> Unit)? = null,
    onCastClick: ((HeroCastMember) -> Unit)? = null,
    onBadgeClick: ((HeroDiscoveryFact, MetaPreview) -> Unit)? = null,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
    onLibraryPosterClick: ((LibraryItem) -> Unit)? = null,
    onLocalLibraryPosterClick: ((MetaPreview) -> Unit)? = null,
    onLibraryPosterLongClick: ((LibraryItem, LibrarySection) -> Unit)? = null,
    onLibrarySectionViewAllClick: ((LibrarySection) -> Unit)? = null,
    onCloudFilePlay: ((CloudLibraryItem, CloudLibraryFile) -> Unit)? = null,
    onConnectCloudClick: (() -> Unit)? = null,
    onContinueWatchingClick: ((ContinueWatchingItem) -> Unit)? = null,
    onContinueWatchingPlay: ((ContinueWatchingItem) -> Unit)? = null,
    onContinueWatchingSourceShortcut: ((ContinueWatchingItem) -> Unit)? = null,
    onContinueWatchingLongPress: ((ContinueWatchingItem) -> Unit)? = null,
    onSwitchProfile: (() -> Unit)? = null,
    onHomescreenSettingsClick: () -> Unit = {},
    onMetaScreenSettingsClick: () -> Unit = {},
    onContinueWatchingSettingsClick: () -> Unit = {},
    onOpenDownload: (DownloadItem) -> Unit = {},
    onOpenPartyMedia: (com.nuvio.app.features.party.PartyMedia) -> Unit = {},
    onAddonsSettingsClick: () -> Unit = {},
    onPluginsSettingsClick: () -> Unit = {},
    onAccountSettingsClick: () -> Unit = {},
    onSupportersContributorsSettingsClick: () -> Unit = {},
    onLicensesAttributionsSettingsClick: () -> Unit = {},
    onCheckForUpdatesClick: (() -> Unit)? = null,
    onShowLatestChangelogClick: (() -> Unit)? = null,
    onOpenCollectionEditor: (String?) -> Unit = {},
    onFolderClick: ((collectionId: String, folderId: String) -> Unit)? = null,
    requestedSettingsPageName: String? = null,
    onRequestedSettingsPageConsumed: () -> Unit = {},
    onInitialHomeContentRendered: () -> Unit = {},
    onNavigateToSearch: (() -> Unit)? = null,
    onNavigateToSearchTab: (() -> Unit)? = null,
    onNavigateToLibrary: (() -> Unit)? = null,
    onNavigateToDiscover: (() -> Unit)? = null,
    onNavigateToHome: (() -> Unit)? = null,
    onNavigateToCalendar: (() -> Unit)? = null,
    onNavigateToLiveTv: (() -> Unit)? = null,
    onLiveTvSettingsClick: () -> Unit = {},
) {
    val tabStateHolder = rememberSaveableStateHolder()
    // A cloud item can be a folder (a season pack), so a poster click has to ask which file rather
    // than start one. Held here because the poster grid is shared by Home, Search and Library.
    var cloudFileChooserTarget by remember { mutableStateOf<CloudLibraryItem?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        val settingsSelected = selectedTab == AppScreenTab.Settings
        // Live TV is its own screen, built and disposed like Settings, with HomeScreen kept behind it.
        val liveTvSelected = selectedTab == AppScreenTab.LiveTv
        val homeHidden = settingsSelected || liveTvSelected
        // The content tab HomeScreen shows; kept while Settings is up, so the hidden screen does
        // not change mode underneath it.
        var contentTab by remember {
            mutableStateOf(selectedTab.takeIf { it != AppScreenTab.Settings && it != AppScreenTab.LiveTv } ?: AppScreenTab.Home)
        }
        if (!homeHidden) contentTab = selectedTab
        // HomeScreen stays composed (hidden) while Settings is open, so switching back shows it at
        // once instead of rebuilding Home; Settings is still built and disposed as before.
        tabStateHolder.SaveableStateProvider("HomeSearchLibrary") {
            KeptAliveLayer(
                visible = !homeHidden,
                lifecycleOwner = rememberChildLifecycleOwner(active = !homeHidden),
                modifier = Modifier.fillMaxSize(),
            ) {
                run {
                    // Keep one HomeScreen in the composition while its mode changes. AnimatedContent
                    // retained the outgoing screen for the fade, leaving two focusable HomeScreens
                    // and two HomeTvKeyboardBridge collectors alive at once. On Search -> Home the
                    // outgoing Search screen could reclaim/clear focus as it was disposed, freezing
                    // the Continue Watching shelf until another navigation resynchronised it.
                    val isSearch = contentTab == AppScreenTab.Search
                    val isLib = contentTab == AppScreenTab.Library
                    val isDiscover = contentTab == AppScreenTab.Discover
                    // Home-only surfaces: everything else about Discover is meant to match Home.
                    val isHomeOnlySurface = !isSearch && !isLib && !isDiscover
                    HomeScreen(
                        modifier = Modifier.fillMaxSize(),
                        topChromePadding = topChromePadding,
                        contentMode = when {
                            isSearch -> com.nuvio.app.features.home.HomeContentMode.Search(
                                autoFocusCount = searchFocusRequestCount,
                            )
                            isLib -> com.nuvio.app.features.home.HomeContentMode.Library
                            isDiscover -> com.nuvio.app.features.home.HomeContentMode.Discover
                            else -> com.nuvio.app.features.home.HomeContentMode.Normal
                        },
                        searchQuery = if (isSearch) submittedSearchQuery else "",
                        searchSubmitRequests = if (isSearch) searchSubmitRequests else emptyFlow(),
                        animateCollectionGifs = animateHomeCollectionGifs && isHomeOnlySurface && !homeHidden,
                        scrollToTopRequests = when {
                            isLib -> libraryScrollToTopRequests
                            isSearch -> searchScrollToTopRequests
                            isDiscover -> discoverScrollToTopRequests
                            else -> homeScrollToTopRequests
                        },
                        // Search and Library need this too: with "See more arrows" on, their rows
                        // render a capped preview whose arrow opens the full catalog.
                        onCatalogClick = onCatalogClick,
                        onCastClick = onCastClick,
                        onBadgeClick = onBadgeClick,
                        onPosterClick = { meta ->
                            if (meta.type.equals(CloudLibraryContentType, ignoreCase = true)) {
                                meta.findCloudLibraryItemForPreview()?.let { item ->
                                    val playableFiles = item.playableFiles
                                    // One file is unambiguous; anything else is a folder, and
                                    // picking its first file silently played episode 1 of a season.
                                    if (playableFiles.size == 1) {
                                        onCloudFilePlay?.invoke(item, playableFiles.first())
                                    } else if (playableFiles.size > 1) {
                                        cloudFileChooserTarget = item
                                    }
                                }
                            } else {
                                onPosterClick?.invoke(meta)
                            }
                        },
                        onPosterLongClick = onPosterLongClick,
                        onLocalLibraryPosterClick = onLocalLibraryPosterClick,
                        onContinueWatchingClick = if (isHomeOnlySurface) onContinueWatchingClick else null,
                        onContinueWatchingPlay = if (isHomeOnlySurface) onContinueWatchingPlay else null,
                        onContinueWatchingSourceShortcut = if (isHomeOnlySurface) onContinueWatchingSourceShortcut else null,
                        onContinueWatchingLongPress = if (isHomeOnlySurface) onContinueWatchingLongPress else null,
                        onFolderClick = if (isHomeOnlySurface) onFolderClick else null,
                        onFirstCatalogRendered = if (isHomeOnlySurface) onInitialHomeContentRendered else null,
                        onNavigateToSearch = onNavigateToSearch,
                        onNavigateToLibrary = if (!isLib) onNavigateToLibrary else null,
                        onNavigateToDiscover = if (!isDiscover) onNavigateToDiscover else null,
                        onNavigateToCalendar = onNavigateToCalendar,
                        onNavigateToHome = if (!isHomeOnlySurface) onNavigateToHome else null,
                        navigateToContentCount = navigateToContentCount,
                        resumePromptItem = if (isHomeOnlySurface) resumePromptItem else null,
                        resumePromptLabel = resumePromptLabel,
                        onResumePromptAction = onResumePromptAction,
                        onResumePromptDismiss = onResumePromptDismiss,
                        continueWatchingHeroDismissed = if (isHomeOnlySurface) continueWatchingHeroDismissed else false,
                        onContinueWatchingHeroDismiss = onContinueWatchingHeroDismiss,
                    )
                }
            }
        }

        if (liveTvSelected) {
            tabStateHolder.SaveableStateProvider(AppScreenTab.LiveTv.name) {
                com.nuvio.app.features.iptv.IptvScreen(
                    modifier = Modifier.fillMaxSize(),
                    topChromePadding = topChromePadding,
                    onOpenSettings = onLiveTvSettingsClick,
                )
            }
        }

        if (settingsSelected) {
            tabStateHolder.SaveableStateProvider(AppScreenTab.Settings.name) {
                run {
                    SettingsScreen(
                        modifier = Modifier.fillMaxSize(),
                        rootActionRequests = settingsRootActionRequests,
                        requestedPageName = requestedSettingsPageName,
                        onRequestedPageConsumed = onRequestedSettingsPageConsumed,
                        rootActionsEnabled = rootActionsEnabled,
                        onSwitchProfile = onSwitchProfile,
                        onHomescreenClick = onHomescreenSettingsClick,
                        onMetaScreenClick = onMetaScreenSettingsClick,
                        onContinueWatchingClick = onContinueWatchingSettingsClick,
                        onOpenDownload = onOpenDownload,
                        onOpenPartyMedia = onOpenPartyMedia,
                        onAddonsClick = onAddonsSettingsClick,
                        onPluginsClick = onPluginsSettingsClick,
                        onAccountClick = onAccountSettingsClick,
                        onSupportersContributorsClick = onSupportersContributorsSettingsClick,
                        onLicensesAttributionsClick = onLicensesAttributionsSettingsClick,
                        onCheckForUpdatesClick = onCheckForUpdatesClick,
                        onShowLatestChangelogClick = onShowLatestChangelogClick,
                        onOpenCollectionEditor = onOpenCollectionEditor,
                        onNavigateToHome = onNavigateToHome,
                        onNavigateToSearch = onNavigateToSearchTab,
                        onNavigateToLibrary = onNavigateToLibrary,
                        onNavigateToDiscover = onNavigateToDiscover,
                        onNavigateToCalendar = onNavigateToCalendar,
                        onNavigateToLiveTv = onNavigateToLiveTv,
                    )
                }
            }
        }

        cloudFileChooserTarget?.let { item ->
            CloudLibraryFileChooserDialog(
                item = item,
                onFileSelected = { file ->
                    cloudFileChooserTarget = null
                    onCloudFilePlay?.invoke(item, file)
                },
                onDismiss = { cloudFileChooserTarget = null },
            )
        }
    }
}

@Composable
private fun DesktopFullscreenHoverButton(
    modifier: Modifier = Modifier,
) {
    var hovered by remember { mutableStateOf(false) }
    val fullscreen = isAppFullscreen()

    Box(
        modifier = modifier
            .size(52.dp)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = hovered,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(120)),
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.nuvio.colors.surfaceCard.copy(alpha = 0.88f),
                contentColor = MaterialTheme.nuvio.colors.textPrimary,
            ) {
                IconButton(onClick = ::toggleAppFullscreen) {
                    Icon(
                        imageVector = if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = stringResource(
                            if (fullscreen) {
                                Res.string.action_exit_fullscreen
                            } else {
                                Res.string.action_enter_fullscreen
                            },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopHoverSidebar(
    selectedTab: AppScreenTab,
    onTabSelected: (AppScreenTab) -> Unit,
    onProfileSelected: (NuvioProfile) -> Unit,
    onAddProfileRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val avatars by AvatarRepository.avatars.collectAsStateWithLifecycle()
    val activeProfile = profileState.activeProfile
    val activeProfileName = activeProfile?.name ?: stringResource(Res.string.compose_nav_profile)
    val discoverTabVisible by remember {
        ThemeSettingsRepository.desktopDiscoverTabVisible
    }.collectAsStateWithLifecycle()
    val liveTvTabVisible = com.nuvio.app.features.iptv.rememberLiveTvTabVisible()
    var profileStackVisible by remember { mutableStateOf(false) }
    val profileTopPadding = statusBarPadding + 18.dp
    fun selectTab(tab: AppScreenTab) {
        profileStackVisible = false
        onTabSelected(tab)
    }

    Surface(
        modifier = modifier
            .width(DesktopSidebarCollapsedWidth)
            .fillMaxHeight()
            .zIndex(NuvioTokens.Z.navigation),
        color = tokens.colors.background,
        contentColor = tokens.colors.textPrimary,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = profileTopPadding)
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { profileStackVisible = !profileStackVisible },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                DesktopSidebarProfileTrigger(
                    profile = activeProfile,
                    avatars = avatars,
                    label = activeProfileName,
                    expanded = false,
                )
            }

            if (profileStackVisible) {
                SidebarProfileSwitcherStack(
                    onProfileSelected = onProfileSelected,
                    onAddProfileRequested = onAddProfileRequested,
                    onDismissRequest = { profileStackVisible = false },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = profileTopPadding + 58.dp)
                        .width(DesktopSidebarExpandedContentWidth),
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DesktopSidebarItem(
                    label = stringResource(Res.string.compose_nav_home),
                    selected = selectedTab == AppScreenTab.Home,
                    expanded = false,
                    onClick = { selectTab(AppScreenTab.Home) },
                ) { color ->
                    Icon(
                        imageVector = Icons.Filled.Home,
                        contentDescription = stringResource(Res.string.compose_nav_home),
                        modifier = Modifier.size(DesktopSidebarIconSize),
                        tint = color,
                    )
                }
                DesktopSidebarItem(
                    label = stringResource(Res.string.compose_nav_search),
                    selected = selectedTab == AppScreenTab.Search,
                    expanded = false,
                    onClick = { selectTab(AppScreenTab.Search) },
                ) { color ->
                    Icon(
                        painter = painterResource(Res.drawable.sidebar_search),
                        contentDescription = stringResource(Res.string.compose_nav_search),
                        modifier = Modifier.size(DesktopSidebarIconSize),
                        tint = color,
                    )
                }
                if (discoverTabVisible) {
                    DesktopSidebarItem(
                        label = stringResource(Res.string.compose_nav_discover),
                        selected = selectedTab == AppScreenTab.Discover,
                        expanded = false,
                        onClick = { selectTab(AppScreenTab.Discover) },
                    ) { color ->
                        Icon(
                            imageVector = Icons.Rounded.Explore,
                            contentDescription = stringResource(Res.string.compose_nav_discover),
                            modifier = Modifier.size(DesktopSidebarIconSize),
                            tint = color,
                        )
                    }
                }
                if (liveTvTabVisible) {
                    DesktopSidebarItem(
                        label = stringResource(Res.string.iptv_settings_page),
                        selected = selectedTab == AppScreenTab.LiveTv,
                        expanded = false,
                        onClick = { selectTab(AppScreenTab.LiveTv) },
                    ) { color ->
                        Icon(
                            imageVector = Icons.Rounded.LiveTv,
                            contentDescription = stringResource(Res.string.iptv_settings_page),
                            modifier = Modifier.size(DesktopSidebarIconSize),
                            tint = color,
                        )
                    }
                }
                LibraryNavigationContextMenu(modifier = Modifier.fillMaxWidth()) { contextModifier ->
                    DesktopSidebarItem(
                        label = stringResource(Res.string.compose_nav_library),
                        selected = selectedTab == AppScreenTab.Library,
                        expanded = false,
                        onClick = { selectTab(AppScreenTab.Library) },
                        modifier = contextModifier,
                    ) { color ->
                        Icon(
                            painter = painterResource(Res.drawable.sidebar_library),
                            contentDescription = stringResource(Res.string.compose_nav_library),
                            modifier = Modifier.size(DesktopSidebarIconSize),
                            tint = color,
                        )
                    }
                }
                DesktopSidebarItem(
                    label = stringResource(Res.string.compose_settings_page_root),
                    selected = selectedTab == AppScreenTab.Settings,
                    expanded = false,
                    onClick = { selectTab(AppScreenTab.Settings) },
                ) { color ->
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = stringResource(Res.string.compose_settings_page_root),
                        modifier = Modifier.size(DesktopSidebarIconSize),
                        tint = color,
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopSidebarProfileTrigger(
    profile: NuvioProfile?,
    avatars: List<AvatarCatalogItem>,
    label: String,
    expanded: Boolean,
) {
    val tokens = MaterialTheme.nuvio

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Transparent,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.width(
                    if (expanded) DesktopSidebarExpandedContentWidth else DesktopSidebarIconSlotSize,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(DesktopSidebarIconSlotSize),
                    contentAlignment = Alignment.Center,
                ) {
                    ActiveProfileMiniAvatar(
                        profile = profile,
                        avatars = avatars,
                        selected = false,
                        size = 28,
                    )
                }
                if (expanded) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        color = tokens.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopSidebarItem(
    label: String,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (Color) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val contentColor = if (selected) tokens.colors.textPrimary else tokens.colors.textMuted
    val iconColor = if (selected) tokens.colors.onAccent else contentColor

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        color = Color.Transparent,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.width(
                    if (expanded) DesktopSidebarExpandedContentWidth else DesktopSidebarIconSlotSize,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier
                        .size(DesktopSidebarIconSlotSize)
                        .background(
                            if (selected) tokens.colors.accentFill else SolidColor(Color.Transparent),
                            RoundedCornerShape(14.dp),
                        ),
                    color = Color.Transparent,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        icon(iconColor)
                    }
                }
                if (expanded) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * The search bar's dropdown only offers recent queries now. Catalog and genre selection moved to
 * the Discover tab's row-1 header, where it browses in place instead of hiding behind the bar.
 */
private data class SearchPickerEntry(val query: String) {
    val key: String = "history:$query"
    val label: String = query
}

@Composable
private fun TabletFloatingTopBar(
    selectedTab: AppScreenTab,
    onTabSelected: (AppScreenTab) -> Unit,
    onProfileSelected: (NuvioProfile) -> Unit,
    onAddProfileRequested: () -> Unit,
    modifier: Modifier = Modifier,
    dimUntilHovered: Boolean = false,
    hidden: Boolean = false,
    searchOverlayActive: Boolean = false,
    onSearchOverlayOpen: () -> Unit = {},
    onSearchOverlayDismiss: () -> Unit = {},
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    searchFocusRequestCount: Int = 0,
    onSearchSubmit: (String) -> Unit = {},
    onNavigateToContent: () -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    val focusManager = LocalFocusManager.current
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var activationZoneHovered by remember { mutableStateOf(false) }
    val isSearchActive = searchOverlayActive || selectedTab == AppScreenTab.Search
    val discoverTabVisible by remember {
        ThemeSettingsRepository.desktopDiscoverTabVisible
    }.collectAsStateWithLifecycle()
    val liveTvTabVisible = com.nuvio.app.features.iptv.rememberLiveTvTabVisible()
    val topBarWidth = FloatingTopBarWidth -
        (if (discoverTabVisible) 0.dp else FloatingTopBarQuadrantWidth) +
        (if (liveTvTabVisible) FloatingTopBarQuadrantWidth else 0.dp)
    // Right-clicking the Library quadrant hangs its quick-access menu off the bar the same way the
    // discover picker does. Only the nav row can open it, so entering search closes it.
    var libraryMenuVisible by remember(isSearchActive) { mutableStateOf(false) }
    val barAlpha by animateFloatAsState(
        // The menu is a popup, so it isn't covered by this alpha: let the bar dim out from under
        // it and the menu is left floating over nothing.
        targetValue = when {
            hidden -> 0f
            dimUntilHovered && !isSearchActive && !activationZoneHovered && !libraryMenuVisible -> 0f
            else -> 1f
        },
        animationSpec = tween(durationMillis = 200),
    )
    // Faded out it would still take clicks meant for whatever is underneath.
    if (hidden && barAlpha == 0f) return

    var searchFieldState by remember(isSearchActive) {
        mutableStateOf(TextFieldValue(searchQuery))
    }
    LaunchedEffect(searchQuery) {
        if (searchFieldState.text != searchQuery) {
            searchFieldState = searchFieldState.copy(
                text = searchQuery,
                selection = TextRange(searchQuery.length),
            )
        }
    }

    val searchBarFocusRequester = remember { FocusRequester() }
    var suppressSearchActivationKey by remember(searchFocusRequestCount) { mutableStateOf(false) }
    var searchBarHasFocus by remember { mutableStateOf(false) }
    val searchHistory by com.nuvio.app.features.search.SearchHistoryRepository.uiState.collectAsState()
    val discoverUiState by SearchRepository.discoverUiState.collectAsStateWithLifecycle()
    var pickerSelectedIndex by remember { mutableStateOf(-1) }
    val visibleHistory = searchHistory.take(5)
    // Render and activate one immutable snapshot. A separate count plus live-list indexing allowed
    // refreshes to invalidate an index while LazyColumn was measuring.
    val pickerEntries = remember(visibleHistory) {
        visibleHistory.map(::SearchPickerEntry)
    }
    val pickerItemCount = pickerEntries.size
    val pickerVisible = isSearchActive && searchBarHasFocus && visibleHistory.isNotEmpty()
    var historyShapeVisible by remember { mutableStateOf(false) }
    val attachedPanelVisible = historyShapeVisible || libraryMenuVisible

    LaunchedEffect(pickerEntries) {
        if (pickerSelectedIndex !in pickerEntries.indices) {
            pickerSelectedIndex = -1
        }
    }

    LaunchedEffect(pickerVisible) {
        if (pickerVisible) {
            historyShapeVisible = true
        } else {
            kotlinx.coroutines.delay(180)
            historyShapeVisible = false
        }
    }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            com.nuvio.app.features.search.SearchHistoryRepository.ensureLoaded()
        }
    }

    LaunchedEffect(searchFocusRequestCount) {
        if (searchFocusRequestCount > 0 && isSearchActive) {
            suppressSearchActivationKey = true
            try { searchBarFocusRequester.requestFocus() } catch (_: Exception) {}
            kotlinx.coroutines.delay(150)
            suppressSearchActivationKey = false
        }
    }

    val pickerListState = rememberLazyListState()
    LaunchedEffect(pickerSelectedIndex, pickerEntries) {
        if (pickerSelectedIndex < 0) {
            if (pickerEntries.isNotEmpty()) {
                pickerListState.scrollToItem(0)
            }
            return@LaunchedEffect
        }
        val targetIndex = pickerSelectedIndex.coerceIn(pickerEntries.indices)
        val layoutInfo = pickerListState.layoutInfo
        val targetInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
        val fullyVisible = targetInfo != null &&
            targetInfo.offset >= layoutInfo.viewportStartOffset &&
            targetInfo.offset + targetInfo.size <= layoutInfo.viewportEndOffset
        if (!fullyVisible) {
            pickerListState.animateScrollToItem(targetIndex)
        }
    }

    fun activatePickerItem(entry: SearchPickerEntry) {
        onSearchQueryChange(entry.query)
        focusManager.clearFocus()
        onSearchSubmit(entry.query)
    }

    val dividerColor = Color.White.copy(alpha = 0.13f)
    val activeQuadColor = Color.White.copy(alpha = 0.18f)
    val activeIconTint = Color.White
    val inactiveIconTint = Color.White.copy(alpha = 0.55f)
    val floatingSearchSurfaceColor = tokens.colors.surface.copy(alpha = tokens.opacity.strong)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = statusBarPadding + NuvioTokens.Space.s10, bottom = tokens.spacing.controlGap)
            .alpha(barAlpha),
        contentAlignment = Alignment.TopCenter,
    ) {
        val pickerMaxHeight = (
            maxHeight - statusBarPadding - NuvioTokens.Space.s10 - 44.dp - tokens.spacing.controlGap - 8.dp
        ).coerceIn(120.dp, 360.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
            color = floatingSearchSurfaceColor,
            shape = if (attachedPanelVisible) androidx.compose.foundation.shape.RoundedCornerShape(topStartPercent = 50, topEndPercent = 50) else tokens.shapes.chip,
            tonalElevation = if (attachedPanelVisible) 0.dp else tokens.elevation.playerControls,
            shadowElevation = if (attachedPanelVisible) 0.dp else tokens.elevation.overlay,
            border = BorderStroke(0.5.dp, dividerColor),
            modifier = Modifier
                .height(44.dp)
                .width(topBarWidth)
                .onPointerEvent(PointerEventType.Enter) {
                    activationZoneHovered = true
                }
                .onPointerEvent(PointerEventType.Move) {
                    activationZoneHovered = true
                }
                .onPointerEvent(PointerEventType.Exit) {
                    activationZoneHovered = false
                },
        ) {
            AnimatedContent(
                targetState = isSearchActive,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                label = "nav_content",
            ) { searchActive ->
                if (searchActive) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.sidebar_search),
                            contentDescription = null,
                            modifier = Modifier.size(NuvioTokens.Space.s18),
                            tint = inactiveIconTint,
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .onPreviewKeyEvent { event ->
                                    when (event.key) {
                                        Key.Enter -> {
                                            if (event.type == KeyEventType.KeyUp) {
                                                if (pickerSelectedIndex in 0 until pickerItemCount) {
                                                    pickerEntries.getOrNull(pickerSelectedIndex)
                                                        ?.let(::activatePickerItem)
                                                } else {
                                                    focusManager.clearFocus()
                                                    onSearchSubmit(searchQuery)
                                                }
                                            }
                                            true
                                        }
                                        Key.Escape -> {
                                            if (event.type == KeyEventType.KeyUp) {
                                                onSearchOverlayDismiss()
                                                pickerSelectedIndex = -1
                                            }
                                            true
                                        }
                                        Key.DirectionDown -> {
                                            if (event.type == KeyEventType.KeyDown && isSearchActive && pickerItemCount > 0) {
                                                pickerSelectedIndex = (pickerSelectedIndex + 1).coerceAtMost(pickerItemCount - 1)
                                                pickerEntries.getOrNull(pickerSelectedIndex)?.let { historyEntry ->
                                                    val query = historyEntry.query
                                                    searchFieldState = searchFieldState.copy(text = query, selection = TextRange(query.length))
                                                }
                                                return@onPreviewKeyEvent true
                                            }
                                            false
                                        }
                                        Key.DirectionUp -> {
                                            if (event.type == KeyEventType.KeyDown && isSearchActive && pickerItemCount > 0) {
                                                if (pickerSelectedIndex > -1) {
                                                    pickerSelectedIndex--
                                                    pickerEntries.getOrNull(pickerSelectedIndex)?.let { historyEntry ->
                                                        val query = historyEntry.query
                                                        searchFieldState = searchFieldState.copy(text = query, selection = TextRange(query.length))
                                                    }
                                                    return@onPreviewKeyEvent true
                                                }
                                            }
                                            false
                                        }
                                        else -> false
                                    }
                                },
                        ) {
                            BasicTextField(
                                value = searchFieldState,
                                onValueChange = { value ->
                                    if (suppressSearchActivationKey &&
                                        value.text.length == 1 && value.text.equals("s", ignoreCase = true) &&
                                        searchQuery.isBlank()) {
                                        suppressSearchActivationKey = false
                                    } else {
                                        searchFieldState = value
                                        onSearchQueryChange(value.text)
                                        pickerSelectedIndex = -1
                                    }
                                },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchBarFocusRequester)
                                // This floating search field lives above the active content screen,
                                // but desktop app shortcuts are handled at the window root. Mark it
                                // as an active text editor so typed shortcut letters are not routed.
                                .trackTextInputFocus()
                                .onFocusChanged { state ->
                                    searchBarHasFocus = state.isFocused
                                    if (state.isFocused) {
                                        searchFieldState = searchFieldState.copy(
                                            selection = TextRange(0, searchFieldState.text.length)
                                        )
                                    }
                                },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = activeIconTint),
                            cursorBrush = SolidColor(tokens.colors.accent),
                            decorationBox = { inner ->
                                if (searchFieldState.text.isEmpty()) {
                                    Text(
                                        text = stringResource(Res.string.compose_search_placeholder),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = inactiveIconTint,
                                    )
                                }
                                inner()
                            },
                        )
                    }
                        // Mirrors the Tab key inside the field: toggles the discover-catalog
                        // picker so discovery mode is reachable by mouse as well.
                        if (discoverTabVisible) {
                            IconButton(
                                onClick = {
                                    onTabSelected(AppScreenTab.Discover)
                                    // Keep keyboard navigation live in the picker after a mouse click.
                                    try { searchBarFocusRequester.requestFocus() } catch (_: Exception) {}
                                },
                                modifier = Modifier.size(NuvioTokens.Space.s32),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Explore,
                                    contentDescription = stringResource(Res.string.cd_browse_catalogs),
                                    tint = inactiveIconTint,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        IconButton(
                            onClick = onSearchOverlayDismiss,
                            modifier = Modifier.size(NuvioTokens.Space.s32),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(Res.string.cd_exit_search),
                                tint = inactiveIconTint,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                } else {
                    Row(modifier = Modifier.fillMaxSize()) {
                        NavQuadrant(
                            selected = selectedTab == AppScreenTab.Home,
                            activeColor = activeQuadColor,
                            onClick = { onTabSelected(AppScreenTab.Home) },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Home,
                                contentDescription = stringResource(Res.string.compose_nav_home),
                                modifier = Modifier.size(NuvioTokens.Space.s18).offset(x = 2.dp),
                                tint = if (selectedTab == AppScreenTab.Home) activeIconTint else inactiveIconTint,
                            )
                        }
                        Box(Modifier.width(0.5.dp).fillMaxHeight().background(dividerColor))
                        NavQuadrant(
                            selected = isSearchActive,
                            activeColor = activeQuadColor,
                            onClick = onSearchOverlayOpen,
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.sidebar_search),
                                contentDescription = stringResource(Res.string.compose_nav_search),
                                modifier = Modifier.size(NuvioTokens.Space.s18),
                                tint = if (isSearchActive) activeIconTint else inactiveIconTint,
                            )
                        }
                        if (discoverTabVisible) {
                            Box(Modifier.width(0.5.dp).fillMaxHeight().background(dividerColor))
                            NavQuadrant(
                                selected = selectedTab == AppScreenTab.Discover,
                                activeColor = activeQuadColor,
                                onClick = { onTabSelected(AppScreenTab.Discover) },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Explore,
                                    contentDescription = stringResource(Res.string.compose_nav_discover),
                                    modifier = Modifier.size(NuvioTokens.Space.s18),
                                    tint = if (selectedTab == AppScreenTab.Discover) {
                                        activeIconTint
                                    } else {
                                        inactiveIconTint
                                    },
                                )
                            }
                        }
                        if (liveTvTabVisible) {
                            Box(Modifier.width(0.5.dp).fillMaxHeight().background(dividerColor))
                            NavQuadrant(
                                selected = selectedTab == AppScreenTab.LiveTv,
                                activeColor = activeQuadColor,
                                onClick = { onTabSelected(AppScreenTab.LiveTv) },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.LiveTv,
                                    contentDescription = stringResource(Res.string.iptv_settings_page),
                                    modifier = Modifier.size(NuvioTokens.Space.s18),
                                    tint = if (selectedTab == AppScreenTab.LiveTv) activeIconTint else inactiveIconTint,
                                )
                            }
                        }
                        Box(Modifier.width(0.5.dp).fillMaxHeight().background(dividerColor))
                        NavQuadrant(
                            selected = selectedTab == AppScreenTab.Library,
                            activeColor = activeQuadColor,
                            onClick = { onTabSelected(AppScreenTab.Library) },
                            onSecondaryClick = { libraryMenuVisible = true },
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.sidebar_library),
                                contentDescription = stringResource(Res.string.compose_nav_library),
                                modifier = Modifier.size(NuvioTokens.Space.s18),
                                tint = if (selectedTab == AppScreenTab.Library) activeIconTint else inactiveIconTint,
                            )
                        }
                        Box(Modifier.width(0.5.dp).fillMaxHeight().background(dividerColor))
                        NavQuadrant(
                            selected = selectedTab == AppScreenTab.Settings,
                            activeColor = activeQuadColor,
                            onClick = { onTabSelected(AppScreenTab.Settings) },
                        ) {
                            Box(modifier = Modifier.offset(x = (-2).dp)) {
                                ProfileSwitcherTab(
                                    selected = selectedTab == AppScreenTab.Settings,
                                    onClick = { onTabSelected(AppScreenTab.Settings) },
                                    onProfileSelected = onProfileSelected,
                                    onAddProfileRequested = onAddProfileRequested,
                                )
                            }
                        }
                    }
                }
            }
        } // closes Surface

        // A Popup, not a sibling in this Column, so a click anywhere else dismisses it. The
        // offset hangs it off the bottom edge of the 44dp bar, overlapping the hairline border
        // by half a pixel so the two surfaces read as one shape.
        if (libraryMenuVisible) {
            val density = LocalDensity.current
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(0, with(density) { (44.dp - 0.5.dp).roundToPx() }),
                properties = PopupProperties(focusable = true),
                onDismissRequest = { libraryMenuVisible = false },
            ) {
                LibraryQuickAccessMenuPanel(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
                    // The full bar's width (which counts the Discover quadrant when it is shown),
                    // like the search history panel, so it reads as part of the pill.
                    modifier = Modifier.width(topBarWidth),
                    onDismissRequest = { libraryMenuVisible = false },
                )
            }
        }

        androidx.compose.animation.AnimatedVisibility(
                visible = pickerVisible,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(expandFrom = Alignment.Top),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Top),
            ) {
                Surface(
                    color = floatingSearchSurfaceColor,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, dividerColor),
                    modifier = Modifier.width(topBarWidth).offset(y = (-0.5).dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        LazyColumn(
                            state = pickerListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                // Leave the clear-history footer its own room inside the height the
                                // panel was already budgeted.
                                .heightIn(max = (pickerMaxHeight - 32.dp).coerceAtLeast(88.dp))
                                .padding(vertical = 8.dp),
                        ) {
                            items(
                                items = pickerEntries,
                                key = SearchPickerEntry::key,
                            ) { entry ->
                                val index = pickerEntries.indexOf(entry)
                                val isSelected = index == pickerSelectedIndex
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (isSelected) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                                        .clickable { activatePickerItem(entry) }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(Res.drawable.sidebar_search),
                                        contentDescription = null,
                                        modifier = Modifier.size(NuvioTokens.Space.s16),
                                        tint = inactiveIconTint,
                                    )
                                    androidx.compose.material3.Text(
                                        text = entry.label,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = activeIconTint,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }

                        // Mouse-only footer: the arrow keys walk the history entries above, so this
                        // stays out of pickerEntries and pickerSelectedIndex.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Text(
                                text = stringResource(Res.string.compose_search_clear_history),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.55f),
                                modifier = Modifier
                                    .clickable {
                                        com.nuvio.app.features.search.SearchHistoryRepository.clearHistory()
                                        pickerSelectedIndex = -1
                                    }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.NavQuadrant(
    selected: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onSecondaryClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .weight(1f)
            .fillMaxHeight()
            .background(if (selected) activeColor else Color.Transparent)
            .then(if (onSecondaryClick != null) Modifier.secondaryClick(onSecondaryClick) else Modifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private fun Float.isInCenteredActivationBand(widthPx: Int): Boolean {
    if (widthPx <= 0) return false
    val horizontalInset = widthPx * 0.125f
    return this >= horizontalInset && this <= widthPx - horizontalInset
}

private fun ContinueWatchingItem.isCloudLibraryContinueWatchingItem(): Boolean =
    parentMetaType.equals(CloudLibraryContentType, ignoreCase = true)

private fun MetaPreview.findCloudLibraryItemForPreview(): CloudLibraryItem? {
    if (!type.equals(CloudLibraryContentType, ignoreCase = true)) return null
    return CloudLibraryRepository.uiState.value.items.firstOrNull { item -> item.stableKey == id }
}

@Composable
private fun TabletTopPillItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        onClick = onClick,
        color = if (selected) tokens.colors.overlaySelected else tokens.colors.surface,
        shape = tokens.shapes.chip,
        tonalElevation = if (selected) tokens.elevation.raised else tokens.elevation.flat,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = tokens.components.chipHorizontalPadding, vertical = NuvioTokens.Space.s10),
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) {
                    tokens.colors.textPrimary
                } else {
                    tokens.colors.textMuted
                },
            )
        }
    }
}

/**
 * How the frames actually flowed while the launch overlay was up, as one log line when it goes:
 * how long it was visible, how many frames it drew, the worst gap, and every gap of 50ms or more
 * with its offset from the first frame. The overlay sits over the app's first composition, so its
 * animation only moves when that composition yields a frame — this is the record of when it did.
 *
 * Bounded to the overlay's lifetime, so it is not a permanent frame-clock awaiter (the overlay's
 * own drift keeps the clock awake for exactly as long anyway).
 */
@Composable
private fun LaunchOverlayFrameGapProbe() {
    LaunchedEffect(Unit) {
        val shownAt = TimeSource.Monotonic.markNow()
        val stalls = com.nuvio.app.core.ui.LaunchStallSampler()
        var frames = 0
        var firstNanos = -1L
        var firstFrameEpochMs = 0L
        var lastNanos = -1L
        var worstGapMs = 0L
        val gaps = ArrayList<String>()
        try {
            while (true) {
                withFrameNanos { now ->
                    if (lastNanos >= 0) {
                        val gapMs = (now - lastNanos) / 1_000_000
                        if (gapMs > worstGapMs) worstGapMs = gapMs
                        if (gapMs >= 50 && gaps.size < 40) {
                            gaps += "${gapMs}ms@+${(lastNanos - firstNanos) / 1_000_000}"
                        }
                    } else {
                        firstNanos = now
                        // Wall clock too, so a gap can be cut out of a JFR recording by time.
                        firstFrameEpochMs = System.currentTimeMillis()
                    }
                    lastNanos = now
                    frames++
                    stalls.markFrame()
                }
            }
        } finally {
            val upMs = shownAt.elapsedNow().inWholeMilliseconds
            val drawnMs = if (firstNanos >= 0) (lastNanos - firstNanos) / 1_000_000 else 0
            println(
                "Info: (LaunchOverlay) up ${upMs}ms, $frames frames over ${drawnMs}ms, " +
                    "worst gap ${worstGapMs}ms, firstFrameEpochMs=$firstFrameEpochMs, gaps>=50ms: " +
                    (if (gaps.isEmpty()) "none" else gaps.joinToString(" ")),
            )
            stalls.stopAndReport().forEach { line -> println("Info: (LaunchStall) $line") }
        }
    }
}

@Composable
private fun AppLaunchOverlay(
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val drift = rememberNuvioLaunchDrift()
    LaunchOverlayFrameGapProbe()
    Box(
        modifier = modifier
            .nuvioLaunchBackdrop(tokens.colors.background, drift)
            .zIndex(NuvioTokens.Z.dialog),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The lettering alone, no glyph: the night-sky wash already is the icon. No spinner
            // either — startup blocks the UI thread, so it froze on its first frame and only ever
            // signalled "stuck".
            Image(
                painter = painterResource(Res.drawable.app_wordmark_text),
                contentDescription = stringResource(Res.string.app_brand_name),
                modifier = Modifier
                    .fillMaxWidth(0.48f)
                    .height(30.dp),
                contentScale = ContentScale.Fit,
            )
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s12))
            Text(
                text = "HOME THEATRE",
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 4.sp),
                color = tokens.colors.textMuted,
                // Tracking trails the last letter too; pad the start by the same amount so the
                // line sits centred under the wordmark instead of half a letter to its left.
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** Upper bound on waiting for the outgoing player to dispose before opening the next entry. */
private const val PLAYLIST_HANDOFF_DISPOSE_TIMEOUT_MS = 3_000L
