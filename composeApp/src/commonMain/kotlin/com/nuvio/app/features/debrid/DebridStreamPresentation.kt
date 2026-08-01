package com.nuvio.app.features.debrid

import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamScoring
import com.nuvio.app.features.streams.StreamScorer
import com.nuvio.app.features.streams.StreamTraitDetector
import com.nuvio.app.features.streams.StreamTraits

object DebridStreamPresentation {
    private val formatter = DebridStreamFormatter()

    /**
     * [scoring] defaults to null (no scoring) rather than reading [StreamScoreRepository], so this
     * stays a pure function of its arguments. Reading the repository here made the result depend on
     * whatever the running app had persisted — which silently coupled unit tests to the developer's
     * live settings. Production callers pass the real profile explicitly.
     */
    fun apply(
        groups: List<AddonStreamGroup>,
        settings: DebridSettings,
        scoring: StreamScoring? = null,
    ): List<AddonStreamGroup> {
        if (!settings.canResolvePlayableLinks) return groups
        return groups.map { group ->
            val visibleStreams = group.streams
                .filterNot { stream -> stream.isInactiveResolverStream(settings) }
                .filterNot { stream -> stream.isUncachedDebridStream }
            val debridStreams = visibleStreams.filter { stream -> stream.isManagedDebridStream }
            if (debridStreams.isEmpty()) return@map group.copy(streams = visibleStreams)

            val shouldFormatStreams = settings.hasCustomStreamFormatting ||
                debridStreams.any { stream -> stream.badges.isNotEmpty() }
            val presentedDebridStreams = applyPreferences(debridStreams, settings, scoring)
                .map { stream ->
                    if (shouldFormatStreams) {
                        formatter.format(stream, settings)
                    } else {
                        stream
                    }
                }
            val passthroughStreams = visibleStreams.filterNot { stream -> stream.isManagedDebridStream }

            group.copy(streams = presentedDebridStreams + passthroughStreams)
        }
    }

    /**
     * [scoring] is passed in rather than read from the repository so this stays a pure function
     * of its inputs — a global read here made ordering depend on whatever was persisted, which is
     * both untestable and a surprising coupling for a presentation helper.
     */
    internal fun applyPreferences(
        streams: List<StreamItem>,
        settings: DebridSettings,
        scoring: StreamScoring? = null,
    ): List<StreamItem> {
        val preferences = DebridStreamMetadata.effectivePreferences(settings)
        val matchedStreams = streams.map { it to DebridStreamMetadata.facts(it, preferences) }
            .filter { (_, facts) -> facts.matchesFilters(preferences) }

        // Two ranking systems pointed at one list would be impossible to reason about, so an active
        // score profile supersedes the debrid preference sort entirely. The preference lists stay
        // functional for anyone who never turns scoring on.
        val orderedStreams = when {
            scoring != null && scoring.profile.enabled -> {
                val ranked = StreamScorer.rank(matchedStreams.map { it.first }, scoring.profile, scoring.context)
                val factsByStream = matchedStreams.toMap()
                ranked.mapNotNull { stream -> factsByStream[stream]?.let { stream to it } }
            }
            preferences.sortCriteria.isEmpty() -> matchedStreams
            else -> matchedStreams.sortedWith { left, right ->
                compareFacts(left.second, right.second, preferences.sortCriteria)
            }
        }

        return applyLimits(orderedStreams, preferences)
            .map { it.first }
    }

    internal val StreamItem.isManagedDebridStream: Boolean
        get() {
            val status = debridCacheStatus
            return isAddonDebridCandidate && (isDirectDebridStream || (
                isTorrentStream &&
                    status != null &&
                    DebridProviders.byId(status.providerId)?.supports(DebridProviderCapability.LocalTorrentCacheCheck) == true &&
                    status.state != StreamDebridCacheState.CHECKING
            ))
        }

    private val StreamItem.isUncachedDebridStream: Boolean
        get() = isInstalledAddonStream &&
            DebridProviders.byId(debridCacheStatus?.providerId)?.supports(DebridProviderCapability.LocalTorrentCacheCheck) == true &&
            debridCacheStatus?.state == StreamDebridCacheState.NOT_CACHED

    private fun StreamItem.isInactiveResolverStream(settings: DebridSettings): Boolean {
        val streamProviderId = DebridProviders.byId(clientResolve?.service)?.id ?: return false
        val activeProviderId = settings.activeResolverProviderId ?: return false
        return isDirectDebridStream && streamProviderId != activeProviderId
    }

    private fun applyLimits(
        streams: List<Pair<StreamItem, DebridStreamFacts>>,
        preferences: DebridStreamPreferences,
    ): List<Pair<StreamItem, DebridStreamFacts>> {
        val resolutionCounts = mutableMapOf<DebridStreamResolution, Int>()
        val qualityCounts = mutableMapOf<DebridStreamQuality, Int>()
        val result = mutableListOf<Pair<StreamItem, DebridStreamFacts>>()
        for (stream in streams) {
            if (preferences.maxResults > 0 && result.size >= preferences.maxResults) break
            if (preferences.maxPerResolution > 0) {
                val count = resolutionCounts[stream.second.resolution] ?: 0
                if (count >= preferences.maxPerResolution) continue
            }
            if (preferences.maxPerQuality > 0) {
                val count = qualityCounts[stream.second.quality] ?: 0
                if (count >= preferences.maxPerQuality) continue
            }
            resolutionCounts[stream.second.resolution] = (resolutionCounts[stream.second.resolution] ?: 0) + 1
            qualityCounts[stream.second.quality] = (qualityCounts[stream.second.quality] ?: 0) + 1
            result += stream
        }
        return result
    }

    private fun DebridStreamFacts.matchesFilters(preferences: DebridStreamPreferences): Boolean {
        if (preferences.requiredResolutions.isNotEmpty() && resolution !in preferences.requiredResolutions) return false
        if (resolution in preferences.excludedResolutions) return false
        if (preferences.requiredQualities.isNotEmpty() && quality !in preferences.requiredQualities) return false
        if (quality in preferences.excludedQualities) return false
        if (preferences.requiredVisualTags.isNotEmpty() && visualTags.none { it in preferences.requiredVisualTags }) return false
        if (visualTags.any { it in preferences.excludedVisualTags }) return false
        if (preferences.requiredAudioTags.isNotEmpty() && audioTags.none { it in preferences.requiredAudioTags }) return false
        if (audioTags.any { it in preferences.excludedAudioTags }) return false
        if (preferences.requiredAudioChannels.isNotEmpty() && audioChannels.none { it in preferences.requiredAudioChannels }) return false
        if (audioChannels.any { it in preferences.excludedAudioChannels }) return false
        if (preferences.requiredEncodes.isNotEmpty() && encode !in preferences.requiredEncodes) return false
        if (encode in preferences.excludedEncodes) return false
        if (preferences.requiredLanguages.isNotEmpty() && languages.none { it in preferences.requiredLanguages }) return false
        if (languages.isNotEmpty() && languages.all { it in preferences.excludedLanguages }) return false
        if (preferences.requiredReleaseGroups.isNotEmpty() && preferences.requiredReleaseGroups.none { releaseGroup.equals(it, ignoreCase = true) }) return false
        if (preferences.excludedReleaseGroups.any { releaseGroup.equals(it, ignoreCase = true) }) return false
        // Bound to a local: `size` now delegates to traits, so it no longer smart-casts.
        val sizeBytes = size
        if (preferences.sizeMinGb > 0 && sizeBytes != null && sizeBytes < preferences.sizeMinGb.gigabytes()) return false
        if (preferences.sizeMaxGb > 0 && sizeBytes != null && sizeBytes > preferences.sizeMaxGb.gigabytes()) return false
        return true
    }

    private fun compareFacts(
        left: DebridStreamFacts,
        right: DebridStreamFacts,
        criteria: List<DebridStreamSortCriterion>,
    ): Int {
        for (criterion in criteria) {
            val comparison = compareKey(left, right, criterion)
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun compareKey(
        left: DebridStreamFacts,
        right: DebridStreamFacts,
        criterion: DebridStreamSortCriterion,
    ): Int {
        val direction = if (criterion.direction == DebridStreamSortDirection.ASC) 1 else -1
        return when (criterion.key) {
            DebridStreamSortKey.RESOLUTION -> left.resolutionRank.compareTo(right.resolutionRank) * -direction
            DebridStreamSortKey.QUALITY -> left.qualityRank.compareTo(right.qualityRank) * -direction
            DebridStreamSortKey.VISUAL_TAG -> left.visualRank.compareTo(right.visualRank) * -direction
            DebridStreamSortKey.AUDIO_TAG -> left.audioRank.compareTo(right.audioRank) * -direction
            DebridStreamSortKey.AUDIO_CHANNEL -> left.channelRank.compareTo(right.channelRank) * -direction
            DebridStreamSortKey.ENCODE -> left.encodeRank.compareTo(right.encodeRank) * -direction
            DebridStreamSortKey.SIZE -> (left.size ?: 0L).compareTo(right.size ?: 0L) * direction
            DebridStreamSortKey.LANGUAGE -> left.languageRank.compareTo(right.languageRank) * -direction
            DebridStreamSortKey.RELEASE_GROUP -> left.releaseGroup.compareTo(right.releaseGroup, ignoreCase = true)
        }
    }
}

internal object DebridStreamMetadata {
    fun effectivePreferences(settings: DebridSettings): DebridStreamPreferences {
        val default = DebridStreamPreferences()
        if (settings.streamPreferences != default) return settings.streamPreferences.normalized()
        if (
            settings.streamMaxResults == 0 &&
            settings.streamSortMode == DebridStreamSortMode.DEFAULT &&
            settings.streamMinimumQuality == DebridStreamMinimumQuality.ANY &&
            settings.streamDolbyVisionFilter == DebridStreamFeatureFilter.ANY &&
            settings.streamHdrFilter == DebridStreamFeatureFilter.ANY &&
            settings.streamCodecFilter == DebridStreamCodecFilter.ANY
        ) {
            return default
        }
        var preferences = default.copy(
            maxResults = settings.streamMaxResults,
            sortCriteria = when (settings.streamSortMode) {
                DebridStreamSortMode.DEFAULT -> default.sortCriteria
                DebridStreamSortMode.QUALITY_DESC -> listOf(
                    DebridStreamSortCriterion(DebridStreamSortKey.RESOLUTION, DebridStreamSortDirection.DESC),
                    DebridStreamSortCriterion(DebridStreamSortKey.QUALITY, DebridStreamSortDirection.DESC),
                    DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.DESC),
                )
                DebridStreamSortMode.SIZE_DESC -> listOf(
                    DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.DESC),
                )
                DebridStreamSortMode.SIZE_ASC -> listOf(
                    DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.ASC),
                )
            },
            requiredResolutions = DebridStreamResolution.defaultOrder.filter {
                it.value >= settings.streamMinimumQuality.minResolution && it != DebridStreamResolution.UNKNOWN
            },
        )
        preferences = when (settings.streamDolbyVisionFilter) {
            DebridStreamFeatureFilter.ANY -> preferences
            DebridStreamFeatureFilter.EXCLUDE -> preferences.copy(
                excludedVisualTags = preferences.excludedVisualTags + listOf(
                    DebridStreamVisualTag.DV,
                    DebridStreamVisualTag.DV_ONLY,
                    DebridStreamVisualTag.HDR_DV,
                ),
            )
            DebridStreamFeatureFilter.ONLY -> preferences.copy(
                requiredVisualTags = preferences.requiredVisualTags + listOf(
                    DebridStreamVisualTag.DV,
                    DebridStreamVisualTag.DV_ONLY,
                    DebridStreamVisualTag.HDR_DV,
                ),
            )
        }
        preferences = when (settings.streamHdrFilter) {
            DebridStreamFeatureFilter.ANY -> preferences
            DebridStreamFeatureFilter.EXCLUDE -> preferences.copy(
                excludedVisualTags = preferences.excludedVisualTags + listOf(
                    DebridStreamVisualTag.HDR,
                    DebridStreamVisualTag.HDR10,
                    DebridStreamVisualTag.HDR10_PLUS,
                    DebridStreamVisualTag.HLG,
                    DebridStreamVisualTag.HDR_ONLY,
                    DebridStreamVisualTag.HDR_DV,
                ),
            )
            DebridStreamFeatureFilter.ONLY -> preferences.copy(
                requiredVisualTags = preferences.requiredVisualTags + listOf(
                    DebridStreamVisualTag.HDR,
                    DebridStreamVisualTag.HDR10,
                    DebridStreamVisualTag.HDR10_PLUS,
                    DebridStreamVisualTag.HLG,
                    DebridStreamVisualTag.HDR_ONLY,
                    DebridStreamVisualTag.HDR_DV,
                ),
            )
        }
        return when (settings.streamCodecFilter) {
            DebridStreamCodecFilter.ANY -> preferences
            DebridStreamCodecFilter.H264 -> preferences.copy(requiredEncodes = listOf(DebridStreamEncode.AVC))
            DebridStreamCodecFilter.HEVC -> preferences.copy(requiredEncodes = listOf(DebridStreamEncode.HEVC))
            DebridStreamCodecFilter.AV1 -> preferences.copy(requiredEncodes = listOf(DebridStreamEncode.AV1))
        }.normalized()
    }

    fun facts(stream: StreamItem, preferences: DebridStreamPreferences): DebridStreamFacts {
        val traits = StreamTraitDetector.detect(stream)
        return DebridStreamFacts(
            traits = traits,
            resolutionRank = rank(traits.resolution, preferences.preferredResolutions),
            qualityRank = rank(traits.quality, preferences.preferredQualities),
            visualRank = rankAny(traits.visualTags, preferences.preferredVisualTags),
            audioRank = rankAny(traits.audioTags, preferences.preferredAudioTags),
            channelRank = rankAny(traits.audioChannels, preferences.preferredAudioChannels),
            encodeRank = rank(traits.encode, preferences.preferredEncodes),
            languageRank = if (traits.languages.isEmpty()) {
                Int.MAX_VALUE
            } else {
                traits.languages.minOf { rank(it, preferences.preferredLanguages) }
            },
        )
    }

    private fun <T> rank(value: T, preferred: List<T>): Int {
        val index = preferred.indexOf(value)
        return if (index >= 0) index else Int.MAX_VALUE
    }

    private fun <T> rankAny(values: List<T>, preferred: List<T>): Int =
        values.minOfOrNull { rank(it, preferred) } ?: Int.MAX_VALUE

}

/**
 * [StreamTraits] plus this stream's position in the user's debrid preference ordering.
 *
 * The trait accessors delegate so existing call sites keep reading `facts.resolution` directly.
 */
internal data class DebridStreamFacts(
    val traits: StreamTraits,
    val resolutionRank: Int,
    val qualityRank: Int,
    val visualRank: Int,
    val audioRank: Int,
    val channelRank: Int,
    val encodeRank: Int,
    val languageRank: Int,
) {
    val resolution: DebridStreamResolution get() = traits.resolution
    val quality: DebridStreamQuality get() = traits.quality
    val visualTags: List<DebridStreamVisualTag> get() = traits.visualTags
    val audioTags: List<DebridStreamAudioTag> get() = traits.audioTags
    val audioChannels: List<DebridStreamAudioChannel> get() = traits.audioChannels
    val encode: DebridStreamEncode get() = traits.encode
    val languages: List<DebridStreamLanguage> get() = traits.languages
    val releaseGroup: String get() = traits.releaseGroup
    val size: Long? get() = traits.size
}

private fun Int.gigabytes(): Long = this * 1_000_000_000L
