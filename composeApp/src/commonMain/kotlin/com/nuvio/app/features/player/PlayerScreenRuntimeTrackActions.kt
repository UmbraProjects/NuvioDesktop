package com.nuvio.app.features.player

import com.nuvio.app.isDesktop

internal val PlayerScreenRuntime.subtitleStyle: SubtitleStyleState
    get() = playerSettingsUiState.subtitleStyle

internal val PlayerScreenRuntime.activeAddonSubtitleType: String
    get() = contentType ?: parentMetaType

internal val PlayerScreenRuntime.addonSubtitleFetchKey: String?
    get() = buildAddonSubtitleFetchKey(
        addons = addonsUiState.addons,
        type = activeAddonSubtitleType,
        videoId = activeVideoId,
    )

internal val PlayerScreenRuntime.visibleAddonSubtitles: List<AddonSubtitle>
    get() = filterAddonSubtitlesForSettings(
        subtitles = addonSubtitles,
        settings = playerSettingsUiState,
        selectedAddonSubtitleId = selectedAddonSubtitleId,
    )

internal val PlayerScreenRuntime.selectedAddonSubtitle: AddonSubtitle?
    get() = addonSubtitles.firstOrNull { subtitle ->
        subtitle.id == selectedAddonSubtitleId || subtitle.url == selectedAddonSubtitleId
    }

internal fun PlayerScreenRuntime.updateTrackPreference(
    update: (PersistedPlayerTrackPreference) -> PersistedPlayerTrackPreference,
) {
    if (parentMetaId.isBlank()) return
    val current = PlayerTrackPreferenceStorage.load(parentMetaId) ?: PersistedPlayerTrackPreference()
    PlayerTrackPreferenceStorage.save(parentMetaId, update(current))
}

internal fun PlayerScreenRuntime.persistAudioPreference(track: AudioTrack?) {
    updateTrackPreference { current ->
        current.copy(
            audioLanguage = track?.language,
            audioName = track?.label,
            audioTrackId = track?.id,
        )
    }
}

internal fun PlayerScreenRuntime.persistInternalSubtitlePreference(track: SubtitleTrack?) {
    updateTrackPreference { current ->
        current.copy(
            subtitleType = if (track == null) {
                PersistedSubtitleSelectionType.DISABLED
            } else {
                PersistedSubtitleSelectionType.INTERNAL
            },
            subtitleLanguage = track?.language,
            subtitleName = track?.label,
            subtitleTrackId = track?.id,
            addonSubtitleId = null,
            addonSubtitleUrl = null,
            addonSubtitleAddonName = null,
        )
    }
}

internal fun PlayerScreenRuntime.persistAddonSubtitlePreference(subtitle: AddonSubtitle) {
    updateTrackPreference { current ->
        current.copy(
            subtitleType = PersistedSubtitleSelectionType.ADDON,
            subtitleLanguage = subtitle.language,
            subtitleName = subtitle.display,
            subtitleTrackId = null,
            addonSubtitleId = subtitle.id,
            addonSubtitleUrl = subtitle.url,
            addonSubtitleAddonName = subtitle.addonName,
        )
    }
}

internal fun PlayerScreenRuntime.restorePersistedTrackPreferenceIfNeeded() {
    if (trackPreferenceRestoreApplied) return
    val preference = PlayerTrackPreferenceStorage.load(parentMetaId)
    if (preference == null) {
        trackPreferenceRestoreApplied = true
        return
    }

    if (
        audioTracks.isNotEmpty() &&
        (!preference.audioTrackId.isNullOrBlank() ||
            !preference.audioLanguage.isNullOrBlank() ||
            !preference.audioName.isNullOrBlank())
    ) {
        val restoredAudioIndex = findPersistedAudioTrackIndex(audioTracks, preference)
        if (restoredAudioIndex >= 0 && restoredAudioIndex != selectedAudioIndex) {
            playerController?.selectAudioTrack(restoredAudioIndex)
            selectedAudioIndex = restoredAudioIndex
        }
        preferredAudioSelectionApplied = true
    }

    when (preference.subtitleType) {
        PersistedSubtitleSelectionType.DISABLED -> {
            playerController?.selectSubtitleTrack(-1)
            selectedSubtitleIndex = -1
            selectedAddonSubtitleId = null
            useCustomSubtitles = false
            preferredSubtitleSelectionApplied = true
        }
        PersistedSubtitleSelectionType.INTERNAL -> {
            if (subtitleTracks.isNotEmpty()) {
                val restoredSubtitleIndex = findPersistedSubtitleTrackIndex(subtitleTracks, preference)
                if (restoredSubtitleIndex >= 0) {
                    if (useCustomSubtitles) {
                        playerController?.clearExternalSubtitleAndSelect(restoredSubtitleIndex)
                    } else {
                        playerController?.selectSubtitleTrack(restoredSubtitleIndex)
                    }
                    selectedSubtitleIndex = restoredSubtitleIndex
                    selectedAddonSubtitleId = null
                    useCustomSubtitles = false
                    preferredSubtitleSelectionApplied = true
                }
            }
        }
        PersistedSubtitleSelectionType.ADDON -> {
            val url = preference.addonSubtitleUrl?.takeIf { it.isNotBlank() }
            if (url != null) {
                selectedAddonSubtitleId = preference.addonSubtitleId ?: url
                selectedSubtitleIndex = -1
                useCustomSubtitles = true
                playerController?.setSubtitleUri(url)
                preferredSubtitleSelectionApplied = true
            }
        }
    }

    trackPreferenceRestoreApplied = true
}

internal fun PlayerScreenRuntime.refreshTracks() {
    val ctrl = playerController ?: return
    audioTracks = ctrl.getAudioTracks()
    subtitleTracks = ctrl.getSubtitleTracks()
    val selectedAudio = audioTracks.firstOrNull { it.isSelected }
    if (selectedAudio != null) selectedAudioIndex = selectedAudio.index
    val selectedSub = subtitleTracks.firstOrNull { it.isSelected }
    if (selectedSub != null && !useCustomSubtitles) selectedSubtitleIndex = selectedSub.index

    restorePersistedTrackPreferenceIfNeeded()

    if (!preferredAudioSelectionApplied) {
        val preferredAudioTargets = resolvePreferredAudioLanguageTargets(
            preferredAudioLanguage = playerSettingsUiState.preferredAudioLanguage,
            secondaryPreferredAudioLanguage = playerSettingsUiState.secondaryPreferredAudioLanguage,
            deviceLanguages = DeviceLanguagePreferences.preferredLanguageCodes(),
        )
        if (preferredAudioTargets.isEmpty()) {
            preferredAudioSelectionApplied = true
        } else if (audioTracks.isNotEmpty()) {
            val preferredAudioIndex = findPreferredTrackIndex(
                tracks = audioTracks,
                targets = preferredAudioTargets,
                language = { track -> track.language },
            )
            if (preferredAudioIndex >= 0 && preferredAudioIndex != selectedAudioIndex) {
                playerController?.selectAudioTrack(preferredAudioIndex)
                selectedAudioIndex = preferredAudioIndex
            }
            preferredAudioSelectionApplied = true
        }
    }

    if (!preferredSubtitleSelectionApplied) {
        val preferredSubtitleTargets = resolvePreferredSubtitleLanguageTargets(
            preferredSubtitleLanguage = if (subtitleStyle.useForcedSubtitles) {
                SubtitleLanguageOption.FORCED
            } else {
                playerSettingsUiState.preferredSubtitleLanguage
            },
            secondaryPreferredSubtitleLanguage = if (playerSettingsUiState.dualSubtitlesEnabled) {
                null
            } else {
                playerSettingsUiState.secondaryPreferredSubtitleLanguage
            },
            deviceLanguages = DeviceLanguagePreferences.preferredLanguageCodes(),
        )

        if (preferredSubtitleTargets.isEmpty()) {
            if (selectedSubtitleIndex != -1 || subtitleTracks.any { it.isSelected }) {
                playerController?.selectSubtitleTrack(-1)
            }
            selectedSubtitleIndex = -1
            selectedAddonSubtitleId = null
            useCustomSubtitles = false
            preferredSubtitleSelectionApplied = true
        } else if (subtitleTracks.isNotEmpty()) {
            val preferredSubtitleIndex = findPreferredSubtitleTrackIndex(
                tracks = subtitleTracks,
                targets = preferredSubtitleTargets,
            )
            if (preferredSubtitleIndex >= 0 && preferredSubtitleIndex != selectedSubtitleIndex) {
                playerController?.selectSubtitleTrack(preferredSubtitleIndex)
                selectedSubtitleIndex = preferredSubtitleIndex
                selectedAddonSubtitleId = null
                useCustomSubtitles = false
            } else if (
                preferredSubtitleIndex < 0 &&
                (subtitleStyle.useForcedSubtitles ||
                    normalizeLanguageCode(playerSettingsUiState.preferredSubtitleLanguage) ==
                    SubtitleLanguageOption.FORCED)
            ) {
                if (selectedSubtitleIndex != -1 || subtitleTracks.any { it.isSelected }) {
                    playerController?.selectSubtitleTrack(-1)
                }
                selectedSubtitleIndex = -1
                selectedAddonSubtitleId = null
                useCustomSubtitles = false
            }
            preferredSubtitleSelectionApplied = true
        }
    }

    applySecondarySubtitleSelectionIfNeeded()
}

internal fun PlayerScreenRuntime.applySecondarySubtitleSelectionIfNeeded() {
    if (secondarySubtitleSelectionApplied) return
    val controller = playerController ?: return
    val secondaryLanguage = normalizeLanguageCode(playerSettingsUiState.secondaryPreferredSubtitleLanguage)
    val hasPrimarySubtitle = selectedSubtitleIndex >= 0 || useCustomSubtitles || subtitleTracks.any { it.isSelected }

    if (!isDesktop ||
        !playerSettingsUiState.dualSubtitlesEnabled ||
        secondaryLanguage == null ||
        secondaryLanguage == SubtitleLanguageOption.NONE ||
        !hasPrimarySubtitle
    ) {
        controller.selectSecondarySubtitleTrack(-1)
        secondarySubtitleSelectionApplied = true
        return
    }

    if (subtitleTracks.isEmpty()) return
    val candidates = subtitleTracks.filterNot { track ->
        !useCustomSubtitles && track.index == selectedSubtitleIndex
    }
    val candidatePosition = findPreferredSubtitleTrackIndex(
        tracks = candidates,
        targets = listOf(secondaryLanguage),
    )
    val secondaryTrack = candidates.getOrNull(candidatePosition)
    controller.selectSecondarySubtitleTrack(secondaryTrack?.index ?: -1)
    secondarySubtitleSelectionApplied = true
}

internal fun PlayerScreenRuntime.cycleAudioTrackFromKeyboard() {
    refreshTracks()
    if (audioTracks.isEmpty()) return
    val currentIndex = audioTracks.indexOfFirst { it.index == selectedAudioIndex || it.isSelected }
    val next = audioTracks[(currentIndex + 1).mod(audioTracks.size)]
    selectedAudioIndex = next.index
    persistAudioPreference(next)
    playerController?.selectAudioTrack(next.index)
    showGestureMessage("Audio: ${next.label.ifBlank { next.language ?: "Track ${next.index + 1}" }}")
}

internal fun PlayerScreenRuntime.cycleSubtitleTrackFromKeyboard() {
    refreshTracks()
    val addons = visibleAddonSubtitles
    if (subtitleTracks.isEmpty() && addons.isEmpty()) {
        fetchAddonSubtitlesForActiveItem()
        return
    }

    val builtInPosition = if (!useCustomSubtitles) {
        subtitleTracks.indexOfFirst { it.index == selectedSubtitleIndex || it.isSelected }
    } else {
        -1
    }
    val addonPosition = if (useCustomSubtitles) {
        addons.indexOfFirst { it.id == selectedAddonSubtitleId || it.url == selectedAddonSubtitleId }
    } else {
        -1
    }
    val currentPosition = when {
        builtInPosition >= 0 -> builtInPosition
        addonPosition >= 0 -> subtitleTracks.size + addonPosition
        else -> -1
    }
    val nextPosition = (currentPosition + 1).mod(subtitleTracks.size + addons.size)

    if (nextPosition < subtitleTracks.size) {
        val track = subtitleTracks[nextPosition]
        val wasCustom = useCustomSubtitles
        selectedSubtitleIndex = track.index
        selectedAddonSubtitleId = null
        useCustomSubtitles = false
        persistInternalSubtitlePreference(track)
        if (wasCustom) {
            playerController?.clearExternalSubtitleAndSelect(track.index)
        } else {
            playerController?.selectSubtitleTrack(track.index)
        }
        secondarySubtitleSelectionApplied = false
        applySecondarySubtitleSelectionIfNeeded()
        showGestureMessage("Subtitles: ${track.label.ifBlank { track.language ?: "Track ${track.index + 1}" }}")
    } else {
        val subtitle = addons[nextPosition - subtitleTracks.size]
        selectedAddonSubtitleId = subtitle.id
        selectedSubtitleIndex = -1
        useCustomSubtitles = true
        persistAddonSubtitlePreference(subtitle)
        playerController?.setSubtitleUri(subtitle.url)
        secondarySubtitleSelectionApplied = false
        applySecondarySubtitleSelectionIfNeeded()
        showGestureMessage("Subtitles: ${subtitle.display.ifBlank { subtitle.language }}")
    }
}
