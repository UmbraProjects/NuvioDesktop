package com.nuvio.app.core.sync

import co.touchlab.kermit.Logger
import com.nuvio.app.isDesktop
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.collection.CollectionMobileSettingsRepository
import com.nuvio.app.features.collection.CollectionMobileSettingsStorage
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DebridSettingsStorage
import com.nuvio.app.features.details.MetaScreenSettingsStorage
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.mdblist.MdbListMetadataService
import com.nuvio.app.features.mdblist.MdbListSettingsStorage
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsRepository
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.core.ui.PosterCardStyleRepository
import com.nuvio.app.core.ui.CardDepthSync
import com.nuvio.app.core.ui.PosterCardStyleStorage
import com.nuvio.app.features.posterservice.CustomPosterSettingsRepository
import com.nuvio.app.features.posterservice.CustomPosterSync
import com.nuvio.app.features.settings.ThemeSettingsStorage
import com.nuvio.app.features.settings.ThemeSettingsRepository
import com.nuvio.app.features.streams.StreamBadgeSettingsRepository
import com.nuvio.app.features.streams.StreamBadgeSettingsStorage
import com.nuvio.app.features.tmdb.TmdbSettingsStorage
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.trakt.TraktCommentsStorage
import com.nuvio.app.features.trakt.TraktCommentsSettings
import com.nuvio.app.features.trakt.TraktSettingsRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesStorage
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

private const val PUSH_DEBOUNCE_MS = 1500L

object ProfileSettingsSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("ProfileSettingsSync")
    private val syncMutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var isApplyingRemoteBlob: Boolean = false

    @Volatile
    private var isServerSyncInFlight: Boolean = false

    @Volatile
    private var skipNextPushSignature: String? = null

    @Volatile
    private var preservedRemotePlayerSettings: JsonObject? = null

    @Volatile
    private var preservedRemotePlayerSettingsProfileId: Int? = null

    private var observeJob: Job? = null

    private val syncPlayerSettings: Boolean
        get() = !isDesktop

    // The desktop fork extends poster sizing (e.g. Extra Large Posters); keep it local so the
    // fork value doesn't overwrite the official apps' poster size in the shared cloud profile.
    private val syncPosterCardStyle: Boolean
        get() = !isDesktop

    private val preferences: SynchronizationPreferencesUiState
        get() = SynchronizationPreferencesRepository.uiState.value

    fun startObserving() {
        if (observeJob?.isActive == true) return
        ensureRepositoriesLoaded()
        MediaServerSync.startObserving()
        observeLocalChangesAndPush()
    }

    suspend fun pull(profileId: Int): Boolean {
        ensureRepositoriesLoaded()
        val applied = pullLocked(profileId)
        // The observer refuses to push while a sync is in flight; a change made during the pull
        // is still marked pending, so send it now instead of leaving it for the next pull to
        // discover (and by then possibly revert).
        if (ProfileRepository.activeProfileId == profileId && hasPendingLocalChange(profileId)) {
            log.i { "pull(profileId=$profileId) — pushing settings edited while the pull was in flight" }
            pushCurrentProfileToRemote()
        }
        return applied
    }

    private suspend fun pullLocked(profileId: Int): Boolean {
        return syncMutex.withLock {
            isServerSyncInFlight = true
            try {
                val remoteJson = fetchRemoteSettingsJson(profileId)

                if (remoteJson == null) {
                    log.i { "pull(profileId=$profileId) — no remote settings blob found" }
                    clearPreservedRemotePlayerSettings(profileId)
                    val localBlob = exportSettingsBlob(profileId)
                    // Only the sections the user opted into survive the unsynced-section filter, so
                    // measure what would actually be written rather than the raw local blob. With
                    // every permission off that collapses to defaults, and creating the profile's
                    // first settings row from it would hand an all-default payload to the official
                    // apps on their next pull.
                    val blobToCreate = withUnsyncedSectionsFrom(
                        blob = localBlob,
                        remoteFeatures = MobileProfileSettingsFeatures(),
                    )
                    if (buildSignature(blobToCreate) != defaultSignature()) {
                        pushToRemoteLocked(profileId, localBlob)
                    }
                    return@withLock false
                }

                // A local edit that never reached the server (push failed, was dropped while
                // another sync was in flight, or was made while the session was signed-out-but-
                // cached) must not be silently reverted by the remote copy. This pull is most often
                // the blocking one after a fresh sign-in — e.g. the first launch after a Windows
                // restart — which is exactly when users reported a saved custom theme vanishing.
                if (hasPendingLocalChange(profileId)) {
                    log.i {
                        "pull(profileId=$profileId) — local settings changed since the last successful " +
                            "push; pushing local instead of applying remote"
                    }
                    pushToRemoteLocked(profileId, exportSettingsBlob(profileId))
                    return@withLock false
                }

                isApplyingRemoteBlob = true
                try {
                    val remoteBlob = runCatching {
                        json.decodeFromJsonElement(MobileProfileSettingsBlob.serializer(), remoteJson)
                    }.getOrElse { error ->
                        log.e(error) { "pull(profileId=$profileId) — failed to decode remote settings blob" }
                        return@withLock false
                    }

                    preserveRemotePlayerSettings(profileId, remoteBlob)

                    // Compared as this device would write it: a section it may not sync (or a
                    // poster template it keeps local) differing from the profile is not a change
                    // to pull, and would otherwise re-apply the whole blob on every pull.
                    // The version is the writer's, not a setting (upstream is on 4 since adding
                    // poster screens), so it never counts as a difference either.
                    val localBlob = withUnsyncedSectionsFrom(
                        blob = exportSettingsBlob(profileId),
                        remoteFeatures = remoteBlob.features,
                    ).copy(version = remoteBlob.version)
                    val localSignature = buildSignature(localBlob)
                    val remoteSignature = buildSignature(remoteBlob)
                    if (remoteSignature == localSignature) {
                        log.d { "pull(profileId=$profileId) — remote matches local" }
                        return@withLock false
                    }

                    if (preferences.appearanceEnabled) {
                        // Name the theme swap explicitly: "my theme reset" reports otherwise have
                        // nothing in the log to distinguish a sync overwrite from a storage failure.
                        val localTheme = localBlob.features.themeSettings
                        val remoteTheme = remoteBlob.features.themeSettings
                        if (localTheme != remoteTheme) {
                            log.i {
                                "pull(profileId=$profileId) — remote theme settings replace local: " +
                                    "local=$localTheme remote=$remoteTheme"
                            }
                        }
                    }

                    applyRemoteBlob(profileId, remoteBlob, remoteJson.featureKeys())
                    skipNextPushSignature = currentObservedStateSignature()
                } finally {
                    isApplyingRemoteBlob = false
                }

                log.i { "pull(profileId=$profileId) — applied remote settings blob" }
                true
            } catch (error: Exception) {
                log.e(error) { "pull(profileId=$profileId) — FAILED" }
                false
            } finally {
                isServerSyncInFlight = false
            }
        }
    }

    suspend fun pushCurrentProfileToRemote() {
        ensureRepositoriesLoaded()
        syncMutex.withLock {
            runCatching {
                val profileId = ProfileRepository.activeProfileId
                pushToRemoteLocked(profileId, exportSettingsBlob(profileId))
            }.onFailure { error ->
                log.e(error) { "pushCurrentProfileToRemote() — FAILED" }
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeLocalChangesAndPush() {
        val signatureFlows = buildList {
            add(ThemeSettingsRepository.selectedTheme.map { "theme" })
            add(ThemeSettingsRepository.customTheme.map { "custom_theme" })
            add(ThemeSettingsRepository.amoledEnabled.map { "amoled" })
            add(PosterCardStyleRepository.uiState.map { "poster_card_style" })
            add(CustomPosterSettingsRepository.uiState.map { "custom_poster" })
            if (syncPlayerSettings) {
                add(PlayerSettingsRepository.uiState.map { "player" })
            }
            add(StreamBadgeSettingsRepository.uiState.map { "stream_badges" })
            add(DebridSettingsRepository.uiState.map { "debrid" })
            add(TmdbSettingsRepository.uiState.map { "tmdb" })
            add(MdbListSettingsRepository.uiState.map { "mdblist" })
            add(MetaScreenSettingsRepository.uiState.map { "meta" })
            add(CollectionMobileSettingsRepository.uiState.map { "collection_mobile_settings" })
            add(ContinueWatchingPreferencesRepository.uiState.map { "continue_watching" })
            add(TraktSettingsRepository.uiState.map { "trakt_settings" })
            add(TraktCommentsSettings.enabled.map { "trakt_comments" })
            add(EpisodeReleaseNotificationsRepository.uiState.map { "episode_release_alerts" })
        }

        observeJob = scope.launch {
            combine(signatureFlows) { currentObservedStateSignature() }
                .distinctUntilChanged()
                .drop(1)
                .debounce(PUSH_DEBOUNCE_MS)
                .collect { signature ->
                    if (isApplyingRemoteBlob) return@collect
                    if (signature == skipNextPushSignature) {
                        skipNextPushSignature = null
                        return@collect
                    }
                    // Past this point the change is the user's own. Record it before anything can
                    // refuse the push, so it survives until a push actually succeeds.
                    markLocalChangePending()
                    val authState = AuthRepository.state.value
                    if (authState !is AuthState.Authenticated || authState.isAnonymous) return@collect
                    if (isServerSyncInFlight) return@collect
                    pushCurrentProfileToRemote()
                }
        }
    }

    private suspend fun pushToRemoteLocked(profileId: Int, blob: MobileProfileSettingsBlob) {
        val remoteJson = fetchRemoteForPush(profileId)
        val blobToPush = withPreservedUnsyncedSettings(profileId, blob, remoteJson)
        val encoded = json.encodeToJsonElement(MobileProfileSettingsBlob.serializer(), blobToPush).jsonObject
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            put("p_platform", MOBILE_SYNC_PLATFORM)
            put("p_settings_json", withUnmodelledRemoteKeys(remote = remoteJson, local = encoded))
        }
        SupabaseProvider.client.postgrest.rpc("sync_push_profile_settings_blob", params)
        clearPendingLocalChange(profileId)
        log.d { "pushToRemoteLocked(profileId=$profileId) — success" }
    }

    /**
     * Records that the active profile's settings differ from what the server last received.
     * Anonymous (local-only) accounts have no server copy to reconcile, so they never mark. A
     * Loading/Unauthenticated session falls back to the cached profile owner: a session that dies
     * mid-use keeps the user inside the app, and edits made there are still theirs.
     */
    private fun markLocalChangePending() {
        val authState = AuthRepository.state.value
        if (authState is AuthState.Authenticated && authState.isAnonymous) return
        val userId = (authState as? AuthState.Authenticated)?.userId
            ?: ProfileRepository.cachedUserId
            ?: return
        val marker = pendingMarker(userId, ProfileRepository.activeProfileId)
        if (SynchronizationPreferencesStorage.loadPendingPushMarker() == marker) return
        SynchronizationPreferencesStorage.savePendingPushMarker(marker)
    }

    private fun hasPendingLocalChange(profileId: Int): Boolean {
        val marker = SynchronizationPreferencesStorage.loadPendingPushMarker() ?: return false
        val userId = (AuthRepository.state.value as? AuthState.Authenticated)
            ?.takeUnless { it.isAnonymous }
            ?.userId
            ?: return false
        return marker == pendingMarker(userId, profileId)
    }

    private fun clearPendingLocalChange(profileId: Int) {
        val marker = SynchronizationPreferencesStorage.loadPendingPushMarker() ?: return
        // Only the marker for the profile just pushed is settled; a stale marker for another
        // account/profile still describes an unsent edit.
        if (marker.endsWith("|$profileId")) {
            SynchronizationPreferencesStorage.savePendingPushMarker(null)
        }
    }

    private fun pendingMarker(userId: String, profileId: Int): String = "$userId|$profileId"

    private fun exportSettingsBlob(profileId: Int = ProfileRepository.activeProfileId): MobileProfileSettingsBlob {
        ensureRepositoriesLoaded()
        return MobileProfileSettingsBlob(
            features = MobileProfileSettingsFeatures(
                themeSettings = ThemeSettingsStorage.exportToSyncPayload(),
                posterCardStyleSettingsPayload = PosterCardStyleStorage.loadPayload().orEmpty().trim(),
                // A template this device must not publish exports blank here and is replaced by
                // the profile's own value in withUnsyncedSectionsFrom before any push.
                customPosterUrlPattern = CustomPosterSync.exportPattern().orEmpty(),
                customPosterEnabledScreens = CustomPosterSync.exportScreens(),
                cardDepthStyleSettingsPayload = CardDepthSync.exportPayload(),
                playerSettings = exportPlayerSettingsPayload(profileId),
                streamBadgeSettings = StreamBadgeSettingsStorage.exportToSyncPayload(),
                debridSettings = DebridSettingsStorage.exportToSyncPayload(),
                tmdbSettings = TmdbSettingsStorage.exportToSyncPayload(),
                mdbListSettings = MdbListSettingsStorage.exportToSyncPayload(),
                metaScreenSettingsPayload = MetaScreenSettingsStorage.loadPayload().orEmpty().trim(),
                collectionMobileSettingsPayload = CollectionMobileSettingsStorage.loadPayload().orEmpty().trim(),
                continueWatchingSettingsPayload = ContinueWatchingPreferencesStorage.loadPayload().orEmpty().trim(),
                traktSettingsPayload = TraktSettingsRepository.exportToSyncPayload(),
                traktCommentsSettings = TraktCommentsStorage.exportToSyncPayload(),
                notificationsSettings = NotificationsSettingsPayload(
                    episodeReleaseAlertsEnabled = EpisodeReleaseNotificationsRepository.uiState.value.isEnabled,
                ),
            ),
        )
    }

    private fun applyRemoteBlob(
        profileId: Int,
        blob: MobileProfileSettingsBlob,
        remoteFeatureKeys: Set<String>,
    ) {
        if (preferences.appearanceEnabled) {
            ThemeSettingsStorage.replaceFromSyncPayload(blob.features.themeSettings)
            ThemeSettingsRepository.onProfileChanged()

            val keepLocalPoster = CustomPosterSync.applyRemote(
                pattern = blob.features.customPosterUrlPattern
                    .takeIf { CustomPosterSync.PATTERN_KEY in remoteFeatureKeys },
                screens = blob.features.customPosterEnabledScreens
                    .takeIf { CustomPosterSync.SCREENS_KEY in remoteFeatureKeys },
            )
            if (keepLocalPoster) {
                log.i { "pull(profileId=$profileId) — keeping local custom poster settings for first sync" }
                markLocalChangePending()
            }

            if (CardDepthSync.PAYLOAD_KEY in remoteFeatureKeys) {
                CardDepthSync.applyPayload(blob.features.cardDepthStyleSettingsPayload)
            }
        }

        if (syncPosterCardStyle) {
            PosterCardStyleStorage.savePayload(blob.features.posterCardStyleSettingsPayload)
            PosterCardStyleRepository.onProfileChanged()
        }

        if (syncPlayerSettings) {
            PlayerSettingsStorage.replaceFromSyncPayload(blob.features.playerSettings)
            PlayerSettingsRepository.onProfileChanged()
        } else {
            preserveRemotePlayerSettings(profileId, blob)
        }

        if (preferences.streamDisplayEnabled) {
            StreamBadgeSettingsStorage.replaceFromSyncPayload(blob.features.streamBadgeSettings)
            StreamBadgeSettingsRepository.onProfileChanged()
        }

        if (preferences.debridEnabled) {
            DebridSettingsStorage.replaceFromSyncPayload(blob.features.debridSettings)
            DebridSettingsRepository.onProfileChanged()
        }

        if (preferences.metadataEnabled) {
            TmdbSettingsStorage.replaceFromSyncPayload(blob.features.tmdbSettings)
            TmdbSettingsRepository.onProfileChanged()
            com.nuvio.app.features.tvdb.TvdbSettingsRepository.onProfileChanged()
            MdbListSettingsStorage.replaceFromSyncPayload(blob.features.mdbListSettings)
            MdbListSettingsRepository.onProfileChanged()
        }

        if (preferences.contentPreferencesEnabled) {
            MetaScreenSettingsStorage.savePayload(blob.features.metaScreenSettingsPayload)
            MetaScreenSettingsRepository.onProfileChanged()
            CollectionMobileSettingsStorage.savePayload(blob.features.collectionMobileSettingsPayload)
            CollectionMobileSettingsRepository.onProfileChanged()
            ContinueWatchingPreferencesStorage.savePayload(blob.features.continueWatchingSettingsPayload)
            ContinueWatchingPreferencesRepository.onProfileChanged()
        }

        if (preferences.traktEnabled) {
            TraktSettingsRepository.replaceFromSyncPayload(blob.features.traktSettingsPayload)
            TraktCommentsStorage.replaceFromSyncPayload(blob.features.traktCommentsSettings)
            TraktCommentsSettings.onProfileChanged()
        }

        if (preferences.notificationsEnabled) {
            EpisodeReleaseNotificationsRepository.applyFromSyncEnabled(
                blob.features.notificationsSettings.episodeReleaseAlertsEnabled,
            )
        }
    }

    private fun ensureRepositoriesLoaded() {
        SynchronizationPreferencesRepository.ensureLoaded()
        ThemeSettingsRepository.ensureLoaded()
        PosterCardStyleRepository.ensureLoaded()
        CustomPosterSettingsRepository.ensureLoaded()
        if (syncPlayerSettings) {
            PlayerSettingsRepository.ensureLoaded()
        }
        StreamBadgeSettingsRepository.ensureLoaded()
        DebridSettingsRepository.ensureLoaded()
        TmdbSettingsRepository.ensureLoaded()
        MdbListSettingsRepository.ensureLoaded()
        MetaScreenSettingsRepository.ensureLoaded()
        CollectionMobileSettingsRepository.ensureLoaded()
        ContinueWatchingPreferencesRepository.ensureLoaded()
        TraktSettingsRepository.ensureLoaded()
        TraktCommentsSettings.ensureLoaded()
        EpisodeReleaseNotificationsRepository.ensureLoaded()
    }

    private fun buildSignature(blob: MobileProfileSettingsBlob): String =
        json.encodeToString(MobileProfileSettingsBlob.serializer(), blob)

    private fun defaultSignature(): String =
        buildSignature(MobileProfileSettingsBlob())

    private fun currentObservedStateSignature(): String = buildList {
        if (preferences.appearanceEnabled) {
            add("theme=${ThemeSettingsRepository.selectedTheme.value.name}")
            add("custom_theme=${ThemeSettingsRepository.customTheme.value}")
            add("amoled=${ThemeSettingsRepository.amoledEnabled.value}")
            add("custom_poster=${CustomPosterSync.signature()}")
            add("card_depth=${CardDepthSync.signature()}")
        }
        if (syncPosterCardStyle) {
            add("poster_card_style=${PosterCardStyleRepository.uiState.value}")
        }
        if (syncPlayerSettings) {
            add("player=${PlayerSettingsRepository.uiState.value}")
        }
        if (preferences.streamDisplayEnabled) {
            add("stream_badges=${StreamBadgeSettingsRepository.uiState.value}")
        }
        if (preferences.debridEnabled) add("debrid=${DebridSettingsRepository.uiState.value}")
        if (preferences.metadataEnabled) {
            add("tmdb=${TmdbSettingsRepository.uiState.value}")
            add("mdblist=${MdbListSettingsRepository.uiState.value}")
        }
        if (preferences.contentPreferencesEnabled) {
            add("meta=${MetaScreenSettingsRepository.uiState.value}")
            add("collection_mobile_settings=${CollectionMobileSettingsRepository.uiState.value}")
            add("continue=${ContinueWatchingPreferencesRepository.uiState.value}")
        }
        if (preferences.traktEnabled) {
            add("trakt_settings=${TraktSettingsRepository.uiState.value}")
            add("trakt_comments=${TraktCommentsSettings.enabled.value}")
        }
        if (preferences.notificationsEnabled) {
            add("episode_release_alerts=${EpisodeReleaseNotificationsRepository.uiState.value.isEnabled}")
        }
    }.joinToString(separator = "||")

    private fun exportPlayerSettingsPayload(profileId: Int): JsonObject =
        if (syncPlayerSettings) {
            PlayerSettingsStorage.exportToSyncPayload()
        } else {
            preservedRemotePlayerSettingsFor(profileId) ?: JsonObject(emptyMap())
        }

    private fun preserveRemotePlayerSettings(profileId: Int, blob: MobileProfileSettingsBlob) {
        if (!syncPlayerSettings) {
            preservedRemotePlayerSettingsProfileId = profileId
            preservedRemotePlayerSettings = blob.features.playerSettings
        }
    }

    private fun clearPreservedRemotePlayerSettings(profileId: Int) {
        if (!syncPlayerSettings) {
            preservedRemotePlayerSettingsProfileId = profileId
            preservedRemotePlayerSettings = null
        }
    }

    private fun preservedRemotePlayerSettingsFor(profileId: Int): JsonObject? =
        preservedRemotePlayerSettings
            ?.takeIf { preservedRemotePlayerSettingsProfileId == profileId }

    // Every section this device may not write is carried over from the profile as it stands, so
    // without a readable remote blob there is no safe push to build. Failing here leaves the remote
    // untouched; guessing would overwrite those sections with defaults.
    private suspend fun fetchRemoteForPush(profileId: Int): JsonObject? =
        runCatching { fetchRemoteSettingsJson(profileId) }.getOrElse { error ->
            log.e(error) { "pushToRemoteLocked(profileId=$profileId) — failed to read remote settings; skipping push" }
            throw error
        }

    private fun withPreservedUnsyncedSettings(
        profileId: Int,
        blob: MobileProfileSettingsBlob,
        remoteJson: JsonObject?,
    ): MobileProfileSettingsBlob {
        val remoteBlob = remoteJson?.let {
            runCatching { json.decodeFromJsonElement(MobileProfileSettingsBlob.serializer(), it) }.getOrElse { error ->
                log.e(error) { "pushToRemoteLocked(profileId=$profileId) — failed to decode remote settings; skipping push" }
                throw error
            }
        }

        val remoteFeatures = remoteBlob?.features ?: MobileProfileSettingsFeatures()
        preservedRemotePlayerSettingsProfileId = profileId
        preservedRemotePlayerSettings = remoteFeatures.playerSettings
        return withUnsyncedSectionsFrom(blob = blob, remoteFeatures = remoteFeatures)
    }

    /**
     * Replaces every section this device is not allowed to write with the value already on the
     * profile, so a push only ever carries the sections the user opted into.
     */
    private fun withUnsyncedSectionsFrom(
        blob: MobileProfileSettingsBlob,
        remoteFeatures: MobileProfileSettingsFeatures,
    ): MobileProfileSettingsBlob {
        return blob.copy(
            features = blob.features.copy(
                themeSettings = if (preferences.appearanceEnabled) {
                    blob.features.themeSettings
                } else {
                    remoteFeatures.themeSettings
                },
                playerSettings = if (syncPlayerSettings) {
                    blob.features.playerSettings
                } else {
                    remoteFeatures.playerSettings
                },
                posterCardStyleSettingsPayload = if (syncPosterCardStyle) {
                    blob.features.posterCardStyleSettingsPayload
                } else {
                    remoteFeatures.posterCardStyleSettingsPayload
                },
                customPosterUrlPattern = if (preferences.appearanceEnabled && CustomPosterSync.exportPattern() != null) {
                    blob.features.customPosterUrlPattern
                } else {
                    remoteFeatures.customPosterUrlPattern
                },
                customPosterEnabledScreens = if (preferences.appearanceEnabled) {
                    blob.features.customPosterEnabledScreens
                } else {
                    remoteFeatures.customPosterEnabledScreens
                },
                cardDepthStyleSettingsPayload = if (preferences.appearanceEnabled) {
                    blob.features.cardDepthStyleSettingsPayload
                } else {
                    remoteFeatures.cardDepthStyleSettingsPayload
                },
                streamBadgeSettings = if (preferences.streamDisplayEnabled) {
                    blob.features.streamBadgeSettings
                } else {
                    remoteFeatures.streamBadgeSettings
                },
                debridSettings = if (preferences.debridEnabled) {
                    blob.features.debridSettings
                } else {
                    remoteFeatures.debridSettings
                },
                tmdbSettings = if (preferences.metadataEnabled) {
                    blob.features.tmdbSettings
                } else {
                    remoteFeatures.tmdbSettings
                },
                mdbListSettings = if (preferences.metadataEnabled) {
                    blob.features.mdbListSettings
                } else {
                    remoteFeatures.mdbListSettings
                },
                metaScreenSettingsPayload = if (preferences.contentPreferencesEnabled) {
                    blob.features.metaScreenSettingsPayload
                } else {
                    remoteFeatures.metaScreenSettingsPayload
                },
                collectionMobileSettingsPayload = if (preferences.contentPreferencesEnabled) {
                    blob.features.collectionMobileSettingsPayload
                } else {
                    remoteFeatures.collectionMobileSettingsPayload
                },
                continueWatchingSettingsPayload = if (preferences.contentPreferencesEnabled) {
                    blob.features.continueWatchingSettingsPayload
                } else {
                    remoteFeatures.continueWatchingSettingsPayload
                },
                traktSettingsPayload = if (preferences.traktEnabled) {
                    blob.features.traktSettingsPayload
                } else {
                    remoteFeatures.traktSettingsPayload
                },
                traktCommentsSettings = if (preferences.traktEnabled) {
                    blob.features.traktCommentsSettings
                } else {
                    remoteFeatures.traktCommentsSettings
                },
                notificationsSettings = if (preferences.notificationsEnabled) {
                    blob.features.notificationsSettings
                } else {
                    remoteFeatures.notificationsSettings
                },
            ),
        )
    }

    /**
     * The blob this build encodes only knows the keys it models, so a push used to drop every key
     * the official apps added since (their custom poster pattern, card depth style, …) from the
     * shared profile. Those are carried over from the remote copy untouched; keys this build does
     * model always come from [local]. The version never moves backwards.
     */
    private fun withUnmodelledRemoteKeys(remote: JsonObject?, local: JsonObject): JsonObject {
        if (remote == null) return local
        val remoteFeatures = remote["features"] as? JsonObject ?: JsonObject(emptyMap())
        val localFeatures = local["features"] as? JsonObject ?: JsonObject(emptyMap())
        val remoteVersion = (remote["version"] as? JsonPrimitive)?.intOrNull
        val localVersion = (local["version"] as? JsonPrimitive)?.intOrNull
        val version = listOfNotNull(remoteVersion, localVersion).maxOrNull()
        return JsonObject(
            remote + local +
                ("features" to JsonObject(remoteFeatures + localFeatures)) +
                (version?.let { mapOf("version" to JsonPrimitive(it)) } ?: emptyMap()),
        )
    }

    private fun JsonObject.featureKeys(): Set<String> =
        (this["features"] as? JsonObject)?.keys.orEmpty()

    private suspend fun fetchRemoteSettingsJson(profileId: Int): JsonObject? {
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            put("p_platform", MOBILE_SYNC_PLATFORM)
        }
        val result = SupabaseProvider.client.postgrest.rpc("sync_pull_profile_settings_blob", params)
        return result.decodeList<SettingsBlobResponse>().firstOrNull()?.settingsJson
    }
}

@Serializable
private data class MobileProfileSettingsBlob(
    val version: Int = 3,
    val features: MobileProfileSettingsFeatures = MobileProfileSettingsFeatures(),
)

@Serializable
private data class MobileProfileSettingsFeatures(
    @SerialName("theme_settings") val themeSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("poster_card_style_settings_payload") val posterCardStyleSettingsPayload: String = "",
    // Upstream's custom poster URL pattern and screen selection — see CustomPosterSync.
    @SerialName(CustomPosterSync.PATTERN_KEY) val customPosterUrlPattern: String = "",
    @SerialName(CustomPosterSync.SCREENS_KEY) val customPosterEnabledScreens: String = "",
    // Upstream's card depth JSON string — see CardDepthSync.
    @SerialName(CardDepthSync.PAYLOAD_KEY) val cardDepthStyleSettingsPayload: String = "",
    @SerialName("player_settings") val playerSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("stream_badge_settings") val streamBadgeSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("debrid_settings") val debridSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("tmdb_settings") val tmdbSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("mdblist_settings") val mdbListSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("meta_screen_settings_payload") val metaScreenSettingsPayload: String = "",
    @SerialName("collection_mobile_settings_payload") val collectionMobileSettingsPayload: String = "",
    @SerialName("continue_watching_settings_payload") val continueWatchingSettingsPayload: String = "",
    @SerialName("trakt_settings_payload") val traktSettingsPayload: String = "",
    @SerialName("trakt_comments_settings") val traktCommentsSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("notifications_settings") val notificationsSettings: NotificationsSettingsPayload = NotificationsSettingsPayload(),
)

@Serializable
private data class NotificationsSettingsPayload(
    @SerialName("episode_release_alerts_enabled") val episodeReleaseAlertsEnabled: Boolean = false,
)

@Serializable
private data class SettingsBlobResponse(
    @SerialName("profile_id") val profileId: Int = 0,
    @SerialName("settings_json") val settingsJson: JsonObject? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)
