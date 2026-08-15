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

internal val PlayerScreenRuntime.visibleSubtitleTracks: List<SubtitleTrack>
    get() = filterBuiltInSubtitlesForSettings(
        tracks = subtitleTracks,
        settings = playerSettingsUiState,
        selectedIndex = selectedSubtitleIndex,
    )

internal val PlayerScreenRuntime.visibleAudioTracks: List<AudioTrack>
    get() = filterAudioTracksForSettings(
        tracks = audioTracks,
        settings = playerSettingsUiState,
        selectedIndex = selectedAudioIndex,
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

/**
 * Records that the viewer chose a subtitle by hand, closing the automatic passes.
 *
 * Both passes are gated on [preferredSubtitleSelectionApplied], and the addon one only runs when
 * its network fetch lands — seconds into playback, long after the subtitle menu is usable. Without
 * this, a track picked (or switched off) inside that window is quietly replaced when the response
 * finally arrives. Deliberately does not touch the persisted-restore flag: that one still owes the
 * audio track its restoration.
 */
internal fun PlayerScreenRuntime.markSubtitleChosenByViewer() {
    preferredSubtitleSelectionApplied = true
    pendingSubtitleSelectionIndex = null
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

    val hasPersistedAudioPreference = !preference.audioTrackId.isNullOrBlank() ||
        !preference.audioLanguage.isNullOrBlank() ||
        !preference.audioName.isNullOrBlank()
    if (audioTracks.isNotEmpty() && hasPersistedAudioPreference) {
        val restoredAudioIndex = findPersistedAudioTrackIndex(audioTracks, preference)
        if (restoredAudioIndex >= 0 && restoredAudioIndex != selectedAudioIndex) {
            if (playerController?.selectAudioTrack(restoredAudioIndex) == true) {
                selectedAudioIndex = restoredAudioIndex
            }
        }
        preferredAudioSelectionApplied = true
    }

    var waitingForPersistedAddonSubtitle = false
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
                    val nativeSelectionConfirmed =
                        subtitleTracks.firstOrNull { it.index == restoredSubtitleIndex }?.isSelected == true
                    if (nativeSelectionConfirmed) {
                        selectedSubtitleIndex = restoredSubtitleIndex
                        selectedAddonSubtitleId = null
                        useCustomSubtitles = false
                        pendingSubtitleSelectionIndex = null
                        preferredSubtitleSelectionApplied = true
                    } else if (pendingSubtitleSelectionIndex != restoredSubtitleIndex) {
                        val selectionIssued = if (useCustomSubtitles) {
                            playerController?.clearExternalSubtitleAndSelect(restoredSubtitleIndex)
                            true
                        } else {
                            playerController?.selectSubtitleTrack(restoredSubtitleIndex) == true
                        }
                        if (selectionIssued) pendingSubtitleSelectionIndex = restoredSubtitleIndex
                    }
                }
            }
        }
        PersistedSubtitleSelectionType.ADDON -> {
            val fetchKey = addonSubtitleFetchKey
            val currentEpisodeSubtitlesReady = fetchKey == null ||
                completedAutoAddonSubtitleFetchForKey == fetchKey
            val restoredSubtitle = if (currentEpisodeSubtitlesReady) {
                findPersistedAddonSubtitle(addonSubtitles, preference)
            } else {
                null
            }
            if (restoredSubtitle != null) {
                selectedAddonSubtitleId = restoredSubtitle.id.ifBlank { restoredSubtitle.url }
                selectedSubtitleIndex = -1
                useCustomSubtitles = true
                playerController?.setSubtitleUri(restoredSubtitle.url)
                preferredSubtitleSelectionApplied = true
            } else {
                waitingForPersistedAddonSubtitle = fetchKey != null &&
                    completedAutoAddonSubtitleFetchForKey != fetchKey
            }
        }
    }

    // The controller exists before mpv has necessarily published its native track list. Do not
    // consume the one-shot restoration flag against that temporary empty list: doing so defers
    // the eventual built-in subtitle selection until after the first frame, and selecting a PGS
    // track at that point makes mpv perform a visible refresh seek and rebuffer.
    val waitingForPersistedAudioTracks = hasPersistedAudioPreference && audioTracks.isEmpty()
    val waitingForPersistedSubtitleTracks =
        preference.subtitleType == PersistedSubtitleSelectionType.INTERNAL && subtitleTracks.isEmpty()
    val waitingForPersistedSubtitleSelection =
        preference.subtitleType == PersistedSubtitleSelectionType.INTERNAL && !preferredSubtitleSelectionApplied
    if (!waitingForPersistedAudioTracks && !waitingForPersistedSubtitleTracks &&
        !waitingForPersistedSubtitleSelection && !waitingForPersistedAddonSubtitle
    ) {
        trackPreferenceRestoreApplied = true
    }
}

internal fun PlayerScreenRuntime.refreshTracks() {
    val ctrl = playerController ?: return
    audioTracks = ctrl.getAudioTracks()
    subtitleTracks = ctrl.getSubtitleTracks()
    val selectedAudio = audioTracks.firstOrNull { it.isSelected }
    if (selectedAudio != null) selectedAudioIndex = selectedAudio.index
    val selectedSub = subtitleTracks.firstOrNull { it.isSelected }
    if (!useCustomSubtitles) {
        selectedSubtitleIndex = selectedSub?.index ?: -1
        if (selectedSub?.index == pendingSubtitleSelectionIndex) {
            pendingSubtitleSelectionIndex = null
        }
    }

    restorePersistedTrackPreferenceIfNeeded()

    if (!preferredAudioSelectionApplied) {
        val preferredAudioTargets = resolvePreferredAudioLanguageTargets(
            preferredAudioLanguage = playerSettingsUiState.preferredAudioLanguage,
            secondaryPreferredAudioLanguage = playerSettingsUiState.secondaryPreferredAudioLanguage,
            deviceLanguages = DeviceLanguagePreferences.preferredLanguageCodes(),
            originalLanguage = OriginalLanguageCache.languageFor(args.parentMetaId),
        )
        if (preferredAudioTargets.isEmpty() && playerSettingsUiState.rejectedAudioKeywords.isEmpty()) {
            preferredAudioSelectionApplied = true
        } else if (audioTracks.isNotEmpty()) {
            val preferredAudioIndex = findPreferredTrackIndex(
                tracks = audioTracks,
                targets = preferredAudioTargets,
                language = { track -> track.language },
                isRejected = { track -> playerSettingsUiState.rejectsAudioTrack(track) },
            )
            val audioIndexToApply = if (preferredAudioIndex >= 0) {
                preferredAudioIndex
            } else {
                // No language matched, but the player (or mpv's own alang pass) may still be sitting
                // on a commentary or audio-description track. Move off it to the first track that
                // isn't rejected; if every track is rejected, leave the selection alone rather than
                // trading a wrong track for no audio.
                val selected = audioTracks.firstOrNull { it.index == selectedAudioIndex || it.isSelected }
                if (selected != null && playerSettingsUiState.rejectsAudioTrack(selected)) {
                    audioTracks.indexOfFirst { !playerSettingsUiState.rejectsAudioTrack(it) }
                } else {
                    -1
                }
            }
            if (audioIndexToApply >= 0 && audioIndexToApply != selectedAudioIndex) {
                if (playerController?.selectAudioTrack(audioIndexToApply) == true) {
                    selectedAudioIndex = audioIndexToApply
                }
            }
            preferredAudioSelectionApplied = true
        }
    }

    if (!preferredSubtitleSelectionApplied && trackPreferenceRestoreApplied) {
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
            originalLanguage = OriginalLanguageCache.languageFor(args.parentMetaId),
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
                isRejected = { track -> playerSettingsUiState.rejectsSubtitleTrack(track) },
                preferHearingImpaired = playerSettingsUiState.preferHearingImpairedSubtitles,
            )
            val nativePreferredSelectionConfirmed = preferredSubtitleIndex >= 0 &&
                subtitleTracks.firstOrNull { it.index == preferredSubtitleIndex }?.isSelected == true
            if (nativePreferredSelectionConfirmed) {
                selectedSubtitleIndex = preferredSubtitleIndex
                selectedAddonSubtitleId = null
                useCustomSubtitles = false
                pendingSubtitleSelectionIndex = null
            } else if (preferredSubtitleIndex >= 0 &&
                pendingSubtitleSelectionIndex != preferredSubtitleIndex
            ) {
                if (playerController?.selectSubtitleTrack(preferredSubtitleIndex) == true) {
                    pendingSubtitleSelectionIndex = preferredSubtitleIndex
                }
            } else if (
                preferredSubtitleIndex < 0 &&
                (subtitleStyle.useForcedSubtitles ||
                    normalizeLanguageCode(playerSettingsUiState.preferredSubtitleLanguage) ==
                    SubtitleLanguageOption.FORCED ||
                    // Nothing acceptable matched and mpv's own slang pass left a rejected track
                    // showing (a signs/songs or forced track). Turning subtitles off is the honest
                    // outcome — the alternative is displaying exactly what was ruled out.
                    subtitleTracks.any {
                        it.isSelected && playerSettingsUiState.rejectsSubtitleTrack(it)
                    })
            ) {
                if (selectedSubtitleIndex != -1 || subtitleTracks.any { it.isSelected }) {
                    playerController?.selectSubtitleTrack(-1)
                }
                selectedSubtitleIndex = -1
                selectedAddonSubtitleId = null
                useCustomSubtitles = false
            }
            // Built-in tracks win. If none match, keep the selection open until the automatic
            // addon request finishes (unless fast startup explicitly opted out of that work).
            preferredSubtitleSelectionApplied =
                nativePreferredSelectionConfirmed ||
                playerSettingsUiState.addonSubtitleStartupMode == AddonSubtitleStartupMode.FAST_STARTUP ||
                addonSubtitleFetchKey == null
        }
    }

    applySecondarySubtitleSelectionIfNeeded()
}

/** Applies an addon subtitle only when no built-in track matches the preferred languages. */
internal fun PlayerScreenRuntime.applyPreferredAddonSubtitleIfReady() {
    // The addon fetch and the first native track refresh complete independently. Always let the
    // refresh restore a persisted built-in/addon choice first; otherwise a completed network fetch
    // can briefly select an addon that the persisted preference immediately removes, and mpv's
    // resulting subtitle refresh seek interrupts playback.
    if (
        !canApplyPreferredAddonSubtitle(
            trackPreferenceRestoreApplied = trackPreferenceRestoreApplied,
            preferredSubtitleSelectionApplied = preferredSubtitleSelectionApplied,
            playbackIsLoading = playbackSnapshot.isLoading,
        )
    ) return
    if (playerSettingsUiState.addonSubtitleStartupMode == AddonSubtitleStartupMode.FAST_STARTUP) {
        preferredSubtitleSelectionApplied = true
        return
    }
    val fetchKey = addonSubtitleFetchKey ?: run {
        preferredSubtitleSelectionApplied = true
        return
    }
    if (completedAutoAddonSubtitleFetchForKey != fetchKey || isLoadingAddonSubtitles) return

    // "Use Forced Subtitles" asks for forced tracks specifically, and no addon serves those. A
    // fallback here would put a full translation on screen for someone who asked to see only the
    // lines the release itself marked as needing one.
    if (subtitleStyle.useForcedSubtitles ||
        normalizeLanguageCode(playerSettingsUiState.preferredSubtitleLanguage) == SubtitleLanguageOption.FORCED
    ) {
        preferredSubtitleSelectionApplied = true
        return
    }

    val targets = primarySubtitleTargetsForSettings(
        settings = playerSettingsUiState,
        originalLanguage = OriginalLanguageCache.languageFor(args.parentMetaId),
    )
    if (targets.isEmpty()) {
        preferredSubtitleSelectionApplied = true
        return
    }
    // A late native track refresh may have discovered a matching built-in track; never let an
    // addon replace it merely because the network response arrived afterward.
    if (findPreferredSubtitleTrackIndex(
            tracks = subtitleTracks,
            targets = targets,
            isRejected = { track -> playerSettingsUiState.rejectsSubtitleTrack(track) },
            preferHearingImpaired = playerSettingsUiState.preferHearingImpairedSubtitles,
        ) >= 0
    ) {
        preferredSubtitleSelectionApplied = true
        return
    }
    val addon = findPreferredAddonSubtitle(
        subtitles = addonSubtitles,
        targets = targets,
        isRejected = { subtitle -> playerSettingsUiState.rejectsAddonSubtitle(subtitle) },
        preferHearingImpaired = playerSettingsUiState.preferHearingImpairedSubtitles,
    )
    if (addon != null) {
        selectedAddonSubtitleId = addon.id.ifBlank { addon.url }
        selectedSubtitleIndex = -1
        useCustomSubtitles = true
        playerController?.setSubtitleUri(addon.url)
    }
    preferredSubtitleSelectionApplied = true
}

internal fun canApplyPreferredAddonSubtitle(
    trackPreferenceRestoreApplied: Boolean,
    preferredSubtitleSelectionApplied: Boolean,
    playbackIsLoading: Boolean,
): Boolean = trackPreferenceRestoreApplied &&
    !preferredSubtitleSelectionApplied &&
    !playbackIsLoading

internal fun PlayerScreenRuntime.applySecondarySubtitleSelectionIfNeeded() {
    if (secondarySubtitleSelectionApplied) return
    val controller = playerController ?: return
    val secondaryLanguage = resolveSecondarySubtitleLanguage(
        language = playerSettingsUiState.secondaryPreferredSubtitleLanguage,
        originalLanguage = OriginalLanguageCache.languageFor(args.parentMetaId),
        deviceLanguages = DeviceLanguagePreferences.preferredLanguageCodes(),
    )
    val hasPrimarySubtitle = selectedSubtitleIndex >= 0 || useCustomSubtitles || subtitleTracks.any { it.isSelected }

    if (!isDesktop ||
        !playerSettingsUiState.dualSubtitlesEnabled ||
        secondaryLanguage == null ||
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
        isRejected = { track -> playerSettingsUiState.rejectsSubtitleTrack(track) },
        preferHearingImpaired = playerSettingsUiState.preferHearingImpairedSubtitles,
    )
    val secondaryTrack = candidates.getOrNull(candidatePosition)
    controller.selectSecondarySubtitleTrack(secondaryTrack?.index ?: -1)
    secondarySubtitleSelectionApplied = true
}

internal fun PlayerScreenRuntime.cycleAudioTrackFromKeyboard() {
    refreshTracks()
    // Cycle over the same list the modal shows, so rejected tracks (commentary, audio description)
    // are skipped here too.
    val tracks = visibleAudioTracks
    if (tracks.isEmpty()) return
    val currentIndex = tracks.indexOfFirst { it.index == selectedAudioIndex || it.isSelected }
    val next = tracks[(currentIndex + 1).mod(tracks.size)]
    if (playerController?.selectAudioTrack(next.index) == true) {
        selectedAudioIndex = next.index
        persistAudioPreference(next)
        showGestureMessage("Audio: ${next.label.ifBlank { next.language ?: "Track ${next.index + 1}" }}")
    }
}

internal fun PlayerScreenRuntime.cycleSubtitleTrackFromKeyboard() {
    refreshTracks()
    // Cycle over the same lists the modal shows so "Show Only Preferred Languages" is honored
    // here too — both the built-in tracks and the addon subs are the filtered, visible sets.
    val builtIn = visibleSubtitleTracks
    val addons = visibleAddonSubtitles
    if (builtIn.isEmpty() && addons.isEmpty()) {
        fetchAddonSubtitlesForActiveItem()
        return
    }

    val builtInPosition = if (!useCustomSubtitles) {
        builtIn.indexOfFirst { it.index == selectedSubtitleIndex || it.isSelected }
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
        addonPosition >= 0 -> builtIn.size + addonPosition
        else -> -1
    }
    val nextPosition = (currentPosition + 1).mod(builtIn.size + addons.size)

    if (nextPosition < builtIn.size) {
        val track = builtIn[nextPosition]
        val wasCustom = useCustomSubtitles
        selectedSubtitleIndex = track.index
        selectedAddonSubtitleId = null
        useCustomSubtitles = false
        markSubtitleChosenByViewer()
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
        val subtitle = addons[nextPosition - builtIn.size]
        selectedAddonSubtitleId = subtitle.id.ifBlank { subtitle.url }
        selectedSubtitleIndex = -1
        useCustomSubtitles = true
        markSubtitleChosenByViewer()
        persistAddonSubtitlePreference(subtitle)
        playerController?.setSubtitleUri(subtitle.url)
        secondarySubtitleSelectionApplied = false
        applySecondarySubtitleSelectionIfNeeded()
        showGestureMessage("Subtitles: ${subtitle.display.ifBlank { subtitle.language }}")
    }
}
