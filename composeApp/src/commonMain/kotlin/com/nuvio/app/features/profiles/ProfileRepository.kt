package com.nuvio.app.features.profiles

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.isAnonymous
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.search.SearchHistoryRepository
import com.nuvio.app.features.settings.ThemeSettingsRepository
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

@Serializable
private data class StoredProfilePayload(
    val userId: String,
    val activeProfileIndex: Int = 1,
    val profiles: List<NuvioProfile> = emptyList(),
)

object ProfileRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("ProfileRepository")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    private var activeProfileIndex: Int = 1
    private var loadedCacheForUserId: String? = null

    val activeProfileId: Int get() = activeProfileIndex

    fun ensureLoaded(userId: String) {
        if (loadedCacheForUserId == userId && _state.value.isLoaded) return

        loadedCacheForUserId = userId
        val payload = ProfileStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) {
            _state.value = ProfileState()
            activeProfileIndex = 1
            return
        }

        val stored = runCatching {
            json.decodeFromString<StoredProfilePayload>(payload)
        }.getOrNull() ?: return

        if (stored.userId != userId) {
            _state.value = ProfileState()
            activeProfileIndex = 1
            return
        }

        val profiles = stored.profiles.sortedBy { it.profileIndex }
        activeProfileIndex = stored.activeProfileIndex
        _state.value = ProfileState(
            profiles = profiles,
            activeProfile = profiles.find { it.profileIndex == activeProfileIndex } ?: profiles.firstOrNull(),
            isLoaded = profiles.isNotEmpty(),
        )
        _state.value.activeProfile?.let { activeProfileIndex = it.profileIndex }
    }

    fun clearInMemory() {
        loadedCacheForUserId = null
        activeProfileIndex = 1
        _state.value = ProfileState()
    }

    suspend fun pullProfiles() {
        if (AuthRepository.state.value.isAnonymous) {
            if (!_state.value.isLoaded) {
                _state.value = _state.value.copy(isLoaded = true)
            }
            return
        }
        runCatching {
            val result = SupabaseProvider.client.postgrest.rpc("sync_pull_profiles")
            val profiles = result.decodeList<NuvioProfile>()
            _state.value = _state.value.copy(
                profiles = profiles.sortedBy { it.profileIndex },
                isLoaded = true,
                activeProfile = profiles.find { it.profileIndex == activeProfileIndex }
                    ?: profiles.firstOrNull(),
            )
            if (_state.value.activeProfile != null) {
                activeProfileIndex = _state.value.activeProfile!!.profileIndex
            }
            persist()
        }.onFailure { e ->
            log.e(e) { "Failed to pull profiles" }
            if (!_state.value.isLoaded) {
                _state.value = _state.value.copy(isLoaded = true)
            }
        }
    }

    fun selectProfile(profileIndex: Int) {
        activeProfileIndex = profileIndex
        _state.value = _state.value.copy(
            activeProfile = _state.value.profiles.find { it.profileIndex == profileIndex },
        )
        persist()
        WatchedRepository.onProfileChanged(profileIndex)
        LibraryRepository.onProfileChanged(profileIndex)
        WatchProgressRepository.onProfileChanged(profileIndex)
        AddonRepository.onProfileChanged(profileIndex)
        if (com.nuvio.app.core.build.AppFeaturePolicy.pluginsEnabled) {
            PluginRepository.onProfileChanged(profileIndex)
        }
        ThemeSettingsRepository.onProfileChanged()
        PlayerSettingsRepository.onProfileChanged()
        HomeCatalogSettingsRepository.onProfileChanged()
        MetaScreenSettingsRepository.onProfileChanged()
        ContinueWatchingPreferencesRepository.onProfileChanged()
        EpisodeReleaseNotificationsRepository.onProfileChanged()
        TmdbSettingsRepository.onProfileChanged()
        MdbListSettingsRepository.onProfileChanged()
        TraktAuthRepository.onProfileChanged()
        SearchHistoryRepository.onProfileChanged()
        CollectionRepository.onProfileChanged()
        DownloadsRepository.onProfileChanged()
    }

    suspend fun pushProfiles(profiles: List<ProfilePushPayload>) {
        if (AuthRepository.state.value.isAnonymous) {
            applyPayloadsLocally(profiles)
            return
        }
        runCatching {
            val params = buildJsonObject {
                put("p_profiles", json.encodeToJsonElement(profiles))
            }
            SupabaseProvider.client.postgrest.rpc("sync_push_profiles", params)
            pullProfiles()
        }.onFailure { e ->
            log.e(e) { "Failed to push profiles" }
        }
    }

    suspend fun createProfile(
        name: String,
        avatarColorHex: String,
        avatarId: String? = null,
        usesPrimaryAddons: Boolean = false,
    ) {
        val existing = _state.value.profiles
        val nextIndex = ((1..4).toSet() - existing.map { it.profileIndex }.toSet()).minOrNull() ?: return

        val allPayloads = existing.map { profile ->
            ProfilePushPayload(
                profileIndex = profile.profileIndex,
                name = profile.name,
                avatarColorHex = profile.avatarColorHex,
                usesPrimaryAddons = profile.usesPrimaryAddons,
                usesPrimaryPlugins = profile.usesPrimaryPlugins,
                avatarId = profile.avatarId,
            )
        } + ProfilePushPayload(
            profileIndex = nextIndex,
            name = name,
            avatarColorHex = avatarColorHex,
            usesPrimaryAddons = usesPrimaryAddons,
            avatarId = avatarId,
        )

        pushProfiles(allPayloads)
    }

    suspend fun updateProfile(
        profileIndex: Int,
        name: String,
        avatarColorHex: String,
        avatarId: String? = null,
        usesPrimaryAddons: Boolean = false,
    ) {
        val allPayloads = _state.value.profiles.map { profile ->
            if (profile.profileIndex == profileIndex) {
                ProfilePushPayload(
                    profileIndex = profileIndex,
                    name = name,
                    avatarColorHex = avatarColorHex,
                    usesPrimaryAddons = usesPrimaryAddons,
                    avatarId = avatarId ?: profile.avatarId,
                )
            } else {
                ProfilePushPayload(
                    profileIndex = profile.profileIndex,
                    name = profile.name,
                    avatarColorHex = profile.avatarColorHex,
                    usesPrimaryAddons = profile.usesPrimaryAddons,
                    usesPrimaryPlugins = profile.usesPrimaryPlugins,
                    avatarId = profile.avatarId,
                )
            }
        }

        pushProfiles(allPayloads)
    }

    suspend fun deleteProfile(profileIndex: Int) {
        if (AuthRepository.state.value.isAnonymous) {
            val remaining = _state.value.profiles.filter { it.profileIndex != profileIndex }
            _state.value = _state.value.copy(
                profiles = remaining,
                activeProfile = if (_state.value.activeProfile?.profileIndex == profileIndex) remaining.firstOrNull() else _state.value.activeProfile,
            )
            if (_state.value.activeProfile != null) {
                activeProfileIndex = _state.value.activeProfile!!.profileIndex
            }
            persist()
            return
        }
        runCatching {
            val params = buildJsonObject { put("p_profile_id", profileIndex) }
            SupabaseProvider.client.postgrest.rpc("sync_delete_profile_data", params)
            pullProfiles()
        }.onFailure { e ->
            log.e(e) { "Failed to delete profile $profileIndex" }
        }
    }

    suspend fun verifyPin(profileIndex: Int, pin: String): PinVerifyResult {
        return runCatching {
            val params = buildJsonObject {
                put("p_profile_id", profileIndex)
                put("p_pin", pin)
            }
            val result = SupabaseProvider.client.postgrest.rpc("verify_profile_pin", params)
            result.decodeSingle<PinVerifyResult>()
        }.getOrElse { e ->
            log.e(e) { "Failed to verify pin" }
            PinVerifyResult(unlocked = false, retryAfterSeconds = 0, message = "Couldn't verify PIN. Try again.")
        }
    }

    suspend fun setPin(profileIndex: Int, pin: String, currentPin: String? = null): PinVerifyResult {
        return runCatching {
            val params = buildJsonObject {
                put("p_profile_id", profileIndex)
                put("p_pin", pin)
                currentPin?.let { put("p_current_pin", it) }
            }
            SupabaseProvider.client.postgrest.rpc("set_profile_pin", params)
            pullProfiles()
            PinVerifyResult(unlocked = true)
        }.onFailure { e ->
            log.e(e) { "Failed to set pin" }
        }.getOrElse {
            PinVerifyResult(unlocked = false, message = "Couldn't set PIN. Try again.")
        }
    }

    suspend fun clearPin(profileIndex: Int, currentPin: String? = null): PinVerifyResult {
        return runCatching {
            val params = buildJsonObject {
                put("p_profile_id", profileIndex)
                currentPin?.let { put("p_current_pin", it) }
            }
            SupabaseProvider.client.postgrest.rpc("clear_profile_pin", params)
            pullProfiles()
            PinVerifyResult(unlocked = true)
        }.onFailure { e ->
            log.e(e) { "Failed to clear pin" }
        }.getOrElse {
            PinVerifyResult(unlocked = false, message = "Couldn't remove PIN lock. Try again.")
        }
    }

    suspend fun clearPinWithPassword(profileIndex: Int, accountPassword: String) {
        runCatching {
            val params = buildJsonObject {
                put("p_account_password", accountPassword)
                put("p_profile_id", profileIndex)
            }
            SupabaseProvider.client.postgrest.rpc("clear_profile_pin_with_account_password", params)
            pullProfiles()
        }.onFailure { e ->
            log.e(e) { "Failed to clear pin with password" }
        }
    }

    suspend fun pullProfileLocks(): List<ProfileLockState> {
        return runCatching {
            val result = SupabaseProvider.client.postgrest.rpc("sync_pull_profile_locks")
            result.decodeList<ProfileLockState>()
        }.getOrElse { e ->
            log.e(e) { "Failed to pull profile locks" }
            emptyList()
        }
    }

    private fun applyPayloadsLocally(payloads: List<ProfilePushPayload>) {
        val authState = AuthRepository.state.value as? AuthState.Authenticated ?: return
        val profiles = payloads.map { p ->
            NuvioProfile(
                id = "",
                userId = authState.userId,
                profileIndex = p.profileIndex,
                name = p.name,
                avatarColorHex = p.avatarColorHex,
                avatarId = p.avatarId,
                usesPrimaryAddons = p.usesPrimaryAddons,
                usesPrimaryPlugins = p.usesPrimaryPlugins,
            )
        }.sortedBy { it.profileIndex }
        _state.value = _state.value.copy(
            profiles = profiles,
            isLoaded = true,
            activeProfile = profiles.find { it.profileIndex == activeProfileIndex } ?: profiles.firstOrNull(),
        )
        if (_state.value.activeProfile != null) {
            activeProfileIndex = _state.value.activeProfile!!.profileIndex
        }
        persist()
    }

    private fun persist() {
        val authState = AuthRepository.state.value as? AuthState.Authenticated ?: return
        ProfileStorage.savePayload(
            json.encodeToString(
                StoredProfilePayload(
                    userId = authState.userId,
                    activeProfileIndex = activeProfileIndex,
                    profiles = _state.value.profiles,
                ),
            ),
        )
    }
}

@kotlinx.serialization.Serializable
data class ProfileLockState(
    @kotlinx.serialization.SerialName("profile_index") val profileIndex: Int,
    @kotlinx.serialization.SerialName("pin_enabled") val pinEnabled: Boolean = false,
    @kotlinx.serialization.SerialName("pin_locked_until") val pinLockedUntil: String? = null,
)
