package com.nuvio.app.features.streams

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Single owner of the app-wide [StreamScoreProfile].
 *
 * One profile, not one per title: this is a single-device desktop app, so per-title overrides would
 * add surface without a payoff. Mirrors the other settings repositories — one lock, one persisted
 * source of truth, a [StateFlow] for the UI.
 */
object StreamScoreRepository {
    private val log = Logger.withTag("StreamScoreRepo")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    private val _uiState = MutableStateFlow(StreamScoreProfile())
    val uiState: StateFlow<StreamScoreProfile> = _uiState.asStateFlow()

    private var hasLoaded = false

    val profile: StreamScoreProfile
        get() {
            ensureLoaded()
            return _uiState.value
        }

    fun ensureLoaded() {
        synchronized(lock) {
            if (hasLoaded) return
            hasLoaded = true
            val stored = runCatching { StreamScoreStorage.loadProfile() }.getOrNull()
            if (stored.isNullOrBlank()) return
            val decoded = runCatching { json.decodeFromString(StreamScoreProfile.serializer(), stored) }
                .onFailure { log.w(it) { "Discarding unreadable stream score profile" } }
                .getOrNull()
                ?: return
            _uiState.value = decoded
        }
    }

    /** Reloads from disk — called when the active user profile changes. */
    fun reload() {
        synchronized(lock) { hasLoaded = false }
        _uiState.value = StreamScoreProfile()
        ensureLoaded()
    }

    fun update(transform: (StreamScoreProfile) -> StreamScoreProfile) {
        synchronized(lock) {
            ensureLoadedLocked()
            val next = transform(_uiState.value)
            if (next == _uiState.value) return
            _uiState.value = next
            runCatching { StreamScoreStorage.saveProfile(json.encodeToString(StreamScoreProfile.serializer(), next)) }
                .onFailure { log.w(it) { "Failed to persist stream score profile" } }
        }
    }

    fun setPoints(trait: StreamScoreTrait, value: Int) = update { it.withPoints(trait, value) }

    private fun ensureLoadedLocked() {
        if (hasLoaded) return
        hasLoaded = true
        val stored = runCatching { StreamScoreStorage.loadProfile() }.getOrNull()
        if (stored.isNullOrBlank()) return
        runCatching { json.decodeFromString(StreamScoreProfile.serializer(), stored) }
            .getOrNull()
            ?.let { _uiState.value = it }
    }
}
