package com.nuvio.app.features.streams

import com.nuvio.app.features.debrid.DebridStreamAudioChannel
import com.nuvio.app.features.debrid.DebridStreamAudioTag
import com.nuvio.app.features.debrid.DebridStreamEncode
import com.nuvio.app.features.debrid.DebridStreamQuality
import com.nuvio.app.features.debrid.DebridStreamResolution
import com.nuvio.app.features.debrid.DebridStreamVisualTag
import kotlin.math.roundToInt

/**
 * Turns [StreamTraits] plus a [StreamScoreProfile] into a number.
 *
 * Pure and side-effect free so every consumer (first-stream selection, auto-download, failover) and
 * the settings preview all share one implementation — a stream can never rank one way and explain
 * itself another.
 *
 * [StreamScoreContext] is deliberately **not** defaulted. It used to default to MOVIE, and three
 * separate call sites silently scored episodes against the movie size band as a result — which marks
 * every normal episode "far from preferred size". Callers must state the content type.
 */
object StreamScorer {

    fun score(
        stream: StreamItem,
        profile: StreamScoreProfile,
        context: StreamScoreContext,
    ): StreamScore {
        if (!profile.enabled) return StreamScore.NEUTRAL
        // Non-stream rows an addon injected alongside the results — a diagnostics panel, an
        // age-rating notice, a "notify me on release" link — are not releases. Scoring them would
        // invent a number for something that cannot be played, and the sort would then move them
        // in among the real sources.
        if (!stream.isScorableStream) return StreamScore.NEUTRAL
        return score(StreamTraitDetector.detect(stream), profile, context)
    }

    fun score(
        traits: StreamTraits,
        profile: StreamScoreProfile,
        context: StreamScoreContext,
    ): StreamScore {
        val components = mutableListOf<StreamScoreComponent>()
        StreamScoreTrait.entries.forEach { trait ->
            val points = profile.pointsFor(trait)
            if (points == 0) return@forEach
            if (matches(trait, traits, profile, context)) {
                components += StreamScoreComponent(trait, weighted(trait, points, traits))
            }
        }
        collapseHdrFormats(components)
        val total = components.sumOf { it.points }
        return StreamScore(
            total = total,
            components = components,
            rejected = profile.minimumScore?.let { total < it } == true,
        )
    }

    /**
     * Highest score first, dropping anything below the profile's minimum. Ties keep their incoming
     * order, so an empty or disabled profile returns the list untouched and every consumer's
     * existing behaviour is preserved exactly.
     */
    fun rank(
        streams: List<StreamItem>,
        profile: StreamScoreProfile,
        context: StreamScoreContext,
    ): List<StreamItem> = rankWithScores(streams, profile, context).map { it.first }

    fun rankWithScores(
        streams: List<StreamItem>,
        profile: StreamScoreProfile,
        context: StreamScoreContext,
    ): List<Pair<StreamItem, StreamScore>> {
        if (!profile.enabled || streams.isEmpty()) {
            return streams.map { it to StreamScore.NEUTRAL }
        }
        // Non-stream rows are neither ranked nor dropped: they keep their incoming order and sit
        // after everything rankable. Dropping them would hide an addon's diagnostics or "notify me"
        // row from the picker entirely; ranking them would let one win a selection it cannot serve.
        val (rankable, extras) = streams.partition { it.isScorableStream }
        return rankable
            .map { it to score(it, profile, context) }
            .filterNot { (_, score) -> score.rejected }
            // sortedByDescending is stable, so equal scores keep provider order.
            .sortedByDescending { (_, score) -> score.total } +
            extras.map { it to StreamScore.NEUTRAL }
    }

    /**
     * A release has exactly one HDR format, so the HDR rows do not stack — the highest-scoring
     * match is kept and the others dropped.
     *
     * All three formats still appear separately in settings: people expect to find a Dolby Vision
     * row and would wonder where it had gone. But on this client the distinction is largely
     * cosmetic — mpv ignores HDR10+ dynamic metadata and plays the HDR10 base layer, and Dolby
     * Vision profiles 7 and 8.1 fall back to their HDR10 base too. Scoring them additively let a
     * "DV HDR10" release collect two bonuses for one picture.
     *
     * (Profile 5 is the one case that genuinely renders wrong on mpv, having no HDR10 base layer.
     * It exists only on web releases — disc-sourced DV is profile 7 — so any future penalty for it
     * must be scoped to web sources, and should account for external players such as MPC that may
     * handle DV properly.)
     */
    private fun collapseHdrFormats(components: MutableList<StreamScoreComponent>) {
        val hdr = components.filter { it.trait in HDR_FORMAT_TRAITS }
        if (hdr.size < 2) return
        val best = hdr.maxBy { it.points }
        components.removeAll { it.trait in HDR_FORMAT_TRAITS && it !== best }
    }

    /**
     * Scales a trait's points before they are added.
     *
     * Only the trusted-group row uses this: the TRaSH tier of the matched group weights the single
     * number the user set, so a top-tier remux group is worth meaningfully more than a low-tier one
     * without tiers ever appearing as a setting.
     */
    private fun weighted(trait: StreamScoreTrait, points: Int, traits: StreamTraits): Int {
        val tier = traits.groupTier
        if (trait != StreamScoreTrait.GROUP_TRUSTED || tier == null) return points
        val scaled = (points * StreamReleaseGroups.tierWeight(tier)).roundToInt()
        // Never round a non-zero preference away to nothing.
        return if (scaled == 0) (if (points > 0) 1 else -1) else scaled
    }

    private fun matches(
        trait: StreamScoreTrait,
        traits: StreamTraits,
        profile: StreamScoreProfile,
        context: StreamScoreContext,
    ): Boolean = when (trait) {
        // The quality matrix: resolution × source, and a row fires only when the stream declares
        // BOTH. Anything with one axis missing (or a resolution/source outside the matrix, such as
        // SD or HDRip) scores nothing here on purpose — 0 already ranks last against a baseline
        // measured in hundreds, so no explicit penalty is needed.
        StreamScoreTrait.QUALITY_2160P_REMUX -> traits.matches(ResTier.UHD, SrcTier.REMUX)
        StreamScoreTrait.QUALITY_2160P_BLURAY -> traits.matches(ResTier.UHD, SrcTier.BLURAY)
        StreamScoreTrait.QUALITY_2160P_WEB_DL -> traits.matches(ResTier.UHD, SrcTier.WEB_DL)
        StreamScoreTrait.QUALITY_2160P_WEBRIP -> traits.matches(ResTier.UHD, SrcTier.WEBRIP)
        StreamScoreTrait.QUALITY_1080P_REMUX -> traits.matches(ResTier.FHD, SrcTier.REMUX)
        StreamScoreTrait.QUALITY_1080P_BLURAY -> traits.matches(ResTier.FHD, SrcTier.BLURAY)
        StreamScoreTrait.QUALITY_1080P_WEB_DL -> traits.matches(ResTier.FHD, SrcTier.WEB_DL)
        StreamScoreTrait.QUALITY_1080P_WEBRIP -> traits.matches(ResTier.FHD, SrcTier.WEBRIP)
        StreamScoreTrait.QUALITY_720P_REMUX -> traits.matches(ResTier.HD, SrcTier.REMUX)
        StreamScoreTrait.QUALITY_720P_BLURAY -> traits.matches(ResTier.HD, SrcTier.BLURAY)
        StreamScoreTrait.QUALITY_720P_WEB_DL -> traits.matches(ResTier.HD, SrcTier.WEB_DL)
        StreamScoreTrait.QUALITY_720P_WEBRIP -> traits.matches(ResTier.HD, SrcTier.WEBRIP)

        StreamScoreTrait.SRC_HDTV -> traits.quality == DebridStreamQuality.HDTV
        StreamScoreTrait.SRC_JUNK -> traits.quality in JUNK_QUALITIES

        StreamScoreTrait.HDR_DOLBY_VISION -> DebridStreamVisualTag.DV in traits.visualTags
        StreamScoreTrait.HDR_10_PLUS -> DebridStreamVisualTag.HDR10_PLUS in traits.visualTags
        // Most releases labelled only "HDR" are in fact HDR10 — it is by far the dominant format and
        // the bare tag is very common — so a generic HDR tag counts here. Without this the trait
        // missed the majority of real HDR releases.
        //
        // HDR10+ deliberately matches this row as well; collapseHdrFormats keeps whichever of the
        // two scores higher. HLG is excluded because an HLG release genuinely is not HDR10.
        StreamScoreTrait.HDR_10 ->
            DebridStreamVisualTag.HDR10 in traits.visualTags ||
                DebridStreamVisualTag.HDR10_PLUS in traits.visualTags ||
                (
                    DebridStreamVisualTag.HDR in traits.visualTags &&
                        DebridStreamVisualTag.HLG !in traits.visualTags
                    )
        StreamScoreTrait.HDR_HLG -> DebridStreamVisualTag.HLG in traits.visualTags
        StreamScoreTrait.HDR_10_BIT -> DebridStreamVisualTag.TEN_BIT in traits.visualTags
        StreamScoreTrait.HDR_SDR ->
            DebridStreamVisualTag.SDR in traits.visualTags ||
                traits.visualTags.none { it in HDR_TAGS }

        StreamScoreTrait.AUDIO_ATMOS -> DebridStreamAudioTag.ATMOS in traits.audioTags
        StreamScoreTrait.AUDIO_TRUEHD -> DebridStreamAudioTag.TRUEHD in traits.audioTags
        StreamScoreTrait.AUDIO_DTS_X -> DebridStreamAudioTag.DTS_X in traits.audioTags
        StreamScoreTrait.AUDIO_DTS_HD_MA -> DebridStreamAudioTag.DTS_HD_MA in traits.audioTags
        StreamScoreTrait.AUDIO_DD_PLUS -> DebridStreamAudioTag.DD_PLUS in traits.audioTags
        // The lossy DTS row must not also fire for DTS:X / DTS-HD MA, which score separately.
        StreamScoreTrait.AUDIO_DTS ->
            DebridStreamAudioTag.DTS in traits.audioTags &&
                traits.audioTags.none { it == DebridStreamAudioTag.DTS_X || it == DebridStreamAudioTag.DTS_HD_MA }
        StreamScoreTrait.AUDIO_FLAC -> DebridStreamAudioTag.FLAC in traits.audioTags
        StreamScoreTrait.AUDIO_AAC -> DebridStreamAudioTag.AAC in traits.audioTags

        StreamScoreTrait.CH_7_1 -> DebridStreamAudioChannel.CH_7_1 in traits.audioChannels
        StreamScoreTrait.CH_5_1 -> DebridStreamAudioChannel.CH_5_1 in traits.audioChannels
        StreamScoreTrait.CH_2_0 -> DebridStreamAudioChannel.CH_2_0 in traits.audioChannels

        StreamScoreTrait.CODEC_AV1 -> traits.encode == DebridStreamEncode.AV1
        StreamScoreTrait.CODEC_HEVC -> traits.encode == DebridStreamEncode.HEVC
        StreamScoreTrait.CODEC_AVC -> traits.encode == DebridStreamEncode.AVC

        StreamScoreTrait.GROUP_TRUSTED -> traits.isTrustedGroup
        StreamScoreTrait.GROUP_LOW_QUALITY -> traits.isLowQualityGroup
        // Covers "no group in the name" as well as "a group we have never heard of" — from a
        // scoring point of view those are the same unknown quantity.
        StreamScoreTrait.GROUP_UNKNOWN -> !traits.isTrustedGroup && !traits.isLowQualityGroup

        StreamScoreTrait.REPACK_PROPER -> traits.isRepackOrProper
        StreamScoreTrait.HYBRID -> traits.isHybrid
        // Only meaningful for an episode: the structural pack tells (file count, container size)
        // fire on plenty of movie torrents, and letting them award or penalise here would score a
        // movie on a property it cannot have.
        StreamScoreTrait.SEASON_PACK -> context.isEpisode && traits.isSeasonPack
        StreamScoreTrait.THREE_D ->
            traits.visualTags.any { it in THREE_D_TAGS }
        StreamScoreTrait.AI_ENHANCED ->
            traits.isAiEnhanced || DebridStreamVisualTag.AI in traits.visualTags
        StreamScoreTrait.FULL_DISC -> traits.isFullDisc
        // Only fires when *both* are unknown — a release missing just one tag is common and usually
        // still fine, but one missing both is almost always lazily or deceptively named.
        StreamScoreTrait.MISSING_METADATA ->
            traits.resolution == DebridStreamResolution.UNKNOWN &&
                traits.quality == DebridStreamQuality.UNKNOWN

        StreamScoreTrait.SPECIAL_EDITION -> traits.isSpecialEdition
        StreamScoreTrait.EXTRAS_BONUS -> traits.isExtras

        StreamScoreTrait.LANGUAGE_PREFERRED ->
            context.preferredLanguages.isNotEmpty() &&
                traits.languages.any { language ->
                    context.preferredLanguages.any { it.equals(language.code, ignoreCase = true) }
                }

        StreamScoreTrait.DEBRID_CACHED -> traits.isDebridCached

        StreamScoreTrait.IMPLAUSIBLE_SIZE -> StreamSizeSanity.isImplausiblySmall(traits, context)
        StreamScoreTrait.SIZE_IN_BAND ->
            sizeZone(traits, profile, context) == SizeZone.IN_BAND
        StreamScoreTrait.SIZE_FAR_OUT ->
            sizeZone(traits, profile, context) == SizeZone.FAR_OUT
    }

    private enum class SizeZone { IN_BAND, GRACE, FAR_OUT, UNKNOWN }

    private fun sizeZone(
        traits: StreamTraits,
        profile: StreamScoreProfile,
        context: StreamScoreContext,
    ): SizeZone {
        val band = profile.sizeBand
        if (!band.enabled) return SizeZone.UNKNOWN
        val size = traits.size?.takeIf { it > 0L } ?: return SizeZone.UNKNOWN
        val min = band.minBytes(context.isEpisode)
        val max = band.maxBytes(context.isEpisode)
        if (min > 0 && max > 0 && min > max) return SizeZone.UNKNOWN
        if (size in min..max) return SizeZone.IN_BAND

        val grace = band.graceFraction.coerceAtLeast(0.0)
        val graceMin = (min * (1.0 - grace)).toLong().coerceAtLeast(0L)
        val graceMax = (max * (1.0 + grace)).toLong()
        return if (size in graceMin..graceMax) SizeZone.GRACE else SizeZone.FAR_OUT
    }

    /** The resolution rows of the quality matrix. Anything else (SD, unknown) has no row. */
    private enum class ResTier { UHD, FHD, HD }

    /** The source columns of the quality matrix. Anything else (HDTV, DVDRip, unknown) has no column. */
    private enum class SrcTier { REMUX, BLURAY, WEB_DL, WEBRIP }

    private fun resTier(resolution: DebridStreamResolution): ResTier? = when (resolution) {
        DebridStreamResolution.P2160 -> ResTier.UHD
        // 1440p rides with 1080p, as it did when resolution was scored on its own. It is rare enough
        // not to deserve its own row, and dropping it to "no row" would silently bury those streams.
        DebridStreamResolution.P1080, DebridStreamResolution.P1440 -> ResTier.FHD
        DebridStreamResolution.P720 -> ResTier.HD
        else -> null
    }

    private fun srcTier(quality: DebridStreamQuality): SrcTier? = when (quality) {
        DebridStreamQuality.BLURAY_REMUX -> SrcTier.REMUX
        DebridStreamQuality.BLURAY -> SrcTier.BLURAY
        DebridStreamQuality.WEB_DL -> SrcTier.WEB_DL
        DebridStreamQuality.WEBRIP -> SrcTier.WEBRIP
        else -> null
    }

    /** True only when both axes resolve — the "award nothing unless both are declared" rule. */
    private fun StreamTraits.matches(res: ResTier, src: SrcTier): Boolean =
        resTier(resolution) == res && srcTier(quality) == src

    private val JUNK_QUALITIES = setOf(
        DebridStreamQuality.CAM,
        DebridStreamQuality.TS,
        DebridStreamQuality.TC,
        DebridStreamQuality.SCR,
    )

    /** Any 3D marker — full 3D or a squashed half-SBS / half-OU picture. */
    private val THREE_D_TAGS = setOf(
        DebridStreamVisualTag.THREE_D,
        DebridStreamVisualTag.H_OU,
        DebridStreamVisualTag.H_SBS,
    )

    /** The HDR formats a release picks exactly one of — see [collapseHdrFormats]. */
    private val HDR_FORMAT_TRAITS = setOf(
        StreamScoreTrait.HDR_DOLBY_VISION,
        StreamScoreTrait.HDR_10_PLUS,
        StreamScoreTrait.HDR_10,
        StreamScoreTrait.HDR_HLG,
    )

    private val HDR_TAGS = setOf(
        DebridStreamVisualTag.HDR,
        DebridStreamVisualTag.HDR10,
        DebridStreamVisualTag.HDR10_PLUS,
        DebridStreamVisualTag.HDR_DV,
        DebridStreamVisualTag.HDR_ONLY,
        DebridStreamVisualTag.DV,
        DebridStreamVisualTag.DV_ONLY,
        DebridStreamVisualTag.HLG,
    )
}

/**
 * Detects files too small to plausibly hold the quality they advertise — a 1GB "2160p REMUX" is a
 * mislabel, a sample, or a fake.
 *
 * Uses a **minimum bitrate floor** rather than a flat size floor so it scales across films and
 * episodes without a table per content type. The floors sit well below real-world encodes: this is a
 * lie detector, not a quality preference, and it must never fire on a merely efficient release.
 */
object StreamSizeSanity {

    fun isImplausiblySmall(traits: StreamTraits, context: StreamScoreContext): Boolean {
        val size = traits.size?.takeIf { it > 0L } ?: return false
        // No resolution means no floor to apply. Skipping is deliberate: a false positive silently
        // discards a good stream, which is worse than letting a bad one through.
        val floorMbps = floorBitrateMbps(
            resolution = traits.resolution,
            quality = traits.quality,
            isAnimation = context.isAnimation,
        ) ?: return false
        val seconds = durationSeconds(traits, context)
        val expectedMinBytes = (floorMbps * 1_000_000.0 / 8.0 * seconds).toLong()
        return size < expectedMinBytes
    }

    /**
     * Duration in seconds, preferring real data and biasing the fallback **short**.
     *
     * Under-estimating duration under-estimates the required size, which errs toward not firing.
     * Assuming a 100-minute film and meeting a 20-minute short would flag a perfectly good file.
     */
    internal fun durationSeconds(traits: StreamTraits, context: StreamScoreContext): Long =
        traits.durationSeconds
            ?: context.runtimeMinutes?.takeIf { it > 0 }?.let { it * 60L }
            ?: if (context.isEpisode) ASSUMED_EPISODE_SECONDS else ASSUMED_MOVIE_SECONDS

    /**
     * Minimum plausible bitrate in Mbps. Null when the resolution is unknown, which disables the
     * check entirely for that stream.
     *
     * [isAnimation] halves the floor. The table below is calibrated on live action, where grain and
     * constant camera motion set a hard lower bound on what a codec can do. Animation has neither:
     * flat cel-shaded colour, hard edges and long static holds compress several times better, so a
     * 1080p anime episode at 300 MB is a normal release rather than the fake this check hunts for.
     */
    internal fun floorBitrateMbps(
        resolution: DebridStreamResolution,
        quality: DebridStreamQuality,
        isAnimation: Boolean = false,
    ): Double? {
        val tier = when (quality) {
            DebridStreamQuality.BLURAY_REMUX -> 0
            DebridStreamQuality.BLURAY -> 1
            DebridStreamQuality.WEB_DL, DebridStreamQuality.WEBRIP -> 2
            // Unknown source falls in the most permissive column rather than being skipped: a
            // 1GB file claiming 2160p is implausible whatever the source is said to be.
            else -> 3
        }
        val row = when (resolution) {
            DebridStreamResolution.P2160 -> doubleArrayOf(25.0, 12.0, 8.0, 4.0)
            DebridStreamResolution.P1440 -> doubleArrayOf(12.0, 6.0, 4.0, 2.0)
            DebridStreamResolution.P1080 -> doubleArrayOf(8.0, 4.0, 2.5, 1.5)
            DebridStreamResolution.P720 -> doubleArrayOf(3.0, 2.0, 1.2, 0.8)
            DebridStreamResolution.P576,
            DebridStreamResolution.P480,
            DebridStreamResolution.P360,
            -> doubleArrayOf(1.0, 0.8, 0.5, 0.3)
            DebridStreamResolution.UNKNOWN -> return null
        }
        return row[tier] * if (isAnimation) ANIMATION_FLOOR_FACTOR else 1.0
    }

    private const val ASSUMED_MOVIE_SECONDS = 60L * 60L
    private const val ASSUMED_EPISODE_SECONDS = 20L * 60L

    /**
     * Deliberately generous. This is a lie detector, not a quality preference: letting a genuinely
     * bad animated file through costs one skipped stream, while rejecting a good one silently
     * removes it from every list, auto-play pick and auto-download.
     */
    private const val ANIMATION_FLOOR_FACTOR = 0.5
}
