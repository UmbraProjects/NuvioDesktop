package com.nuvio.app.features.streams

import kotlinx.serialization.Serializable
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_stream_scoring_answer_audio_advanced
import nuvio.composeapp.generated.resources.settings_stream_scoring_answer_audio_basic
import nuvio.composeapp.generated.resources.settings_stream_scoring_answer_cached_high
import nuvio.composeapp.generated.resources.settings_stream_scoring_answer_cached_medium
import nuvio.composeapp.generated.resources.settings_stream_scoring_answer_cached_none
import nuvio.composeapp.generated.resources.settings_stream_scoring_answer_hdr_high
import nuvio.composeapp.generated.resources.settings_stream_scoring_answer_hdr_medium
import nuvio.composeapp.generated.resources.settings_stream_scoring_answer_hdr_prefer_sdr
import nuvio.composeapp.generated.resources.settings_stream_scoring_answer_language_extremely
import nuvio.composeapp.generated.resources.settings_stream_scoring_answer_language_medium
import nuvio.composeapp.generated.resources.settings_stream_scoring_answer_language_not_at_all
import nuvio.composeapp.generated.resources.settings_stream_scoring_answer_low_quality_high
import nuvio.composeapp.generated.resources.settings_stream_scoring_answer_low_quality_medium
import nuvio.composeapp.generated.resources.settings_stream_scoring_answer_low_quality_none
import nuvio.composeapp.generated.resources.settings_stream_scoring_answer_size_balanced
import nuvio.composeapp.generated.resources.settings_stream_scoring_answer_size_quality
import nuvio.composeapp.generated.resources.settings_stream_scoring_answer_size_small
import nuvio.composeapp.generated.resources.settings_stream_scoring_answer_three_d_no
import nuvio.composeapp.generated.resources.settings_stream_scoring_answer_three_d_yes
import nuvio.composeapp.generated.resources.settings_stream_scoring_answer_unknown_high
import nuvio.composeapp.generated.resources.settings_stream_scoring_answer_unknown_medium
import nuvio.composeapp.generated.resources.settings_stream_scoring_answer_unknown_none
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_ai_enhanced
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_ai_enhanced_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_audio_aac
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_audio_atmos
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_audio_dd_plus
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_audio_dts
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_audio_dts_hd_ma
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_audio_dts_x
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_audio_flac
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_audio_truehd
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_ch_2_0
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_ch_5_1
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_ch_7_1
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_codec_av1
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_codec_av1_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_codec_avc
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_codec_hevc
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_debrid_cached
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_debrid_cached_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_extras_bonus
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_extras_bonus_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_full_disc
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_full_disc_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_group_low_quality
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_group_low_quality_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_group_trusted
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_group_trusted_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_group_unknown
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_group_unknown_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_hdr_10
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_hdr_10_bit
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_hdr_10_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_hdr_10_plus
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_hdr_dolby_vision
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_hdr_dolby_vision_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_hdr_hlg
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_hdr_sdr
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_hybrid
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_hybrid_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_implausible_size
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_implausible_size_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_language_preferred
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_language_preferred_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_missing_metadata
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_missing_metadata_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_quality_1080p_bluray
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_quality_1080p_remux
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_quality_1080p_web_dl
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_quality_1080p_webrip
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_quality_2160p_bluray
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_quality_2160p_remux
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_quality_2160p_web_dl
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_quality_2160p_webrip
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_quality_720p_bluray
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_quality_720p_remux
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_quality_720p_web_dl
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_quality_720p_webrip
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_quality_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_repack_proper
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_repack_proper_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_season_pack
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_season_pack_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_size_far_out
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_size_far_out_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_size_in_band
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_size_in_band_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_special_edition
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_special_edition_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_src_hdtv
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_src_junk
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_src_junk_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_three_d
import nuvio.composeapp.generated.resources.settings_stream_scoring_trait_three_d_caption
import org.jetbrains.compose.resources.StringResource

/**
 * Stream scoring: the user assigns points to release traits, and the highest-scoring stream wins.
 *
 * The deliberate simplification versus a regex+rule system is that **the app owns the detectors**.
 * Every scorable thing is a [StreamScoreTrait] with a built-in matcher, so a user only ever supplies
 * a number. There is no pattern authoring, no rule ordering, and no precedence to reason about.
 *
 * Scoring is purely additive, which is why there are no combination traits: Atmos +40 and TrueHD +25
 * means a release with both scores 65 and outranks either alone, without enumerating pairs.
 */
@Serializable
data class StreamScoreProfile(
    val enabled: Boolean = false,
    /**
     * Trait id → points. Keyed by [StreamScoreTrait.id] rather than the enum so that retiring or
     * renaming a trait later cannot make a stored profile fail to decode.
     *
     * This is the single source of truth the scorer reads. It is *derived* from [answers] whenever a
     * setup question is answered (see the with* methods), but manual per-trait edits also write here —
     * so a stored map can hold both question-driven defaults and hand-tuned overrides.
     */
    val points: Map<String, Int> = ScoreAnswers().toPointsMap(),
    /**
     * The setup-question answers this profile was configured from. Each answer owns a **disjoint**
     * slice of [points]; changing one re-derives only its own traits, leaving every other trait —
     * including the traits of other questions and any manual edits — untouched.
     */
    val answers: ScoreAnswers = ScoreAnswers(),
    /** Streams scoring below this are discarded entirely. null accepts everything. */
    val minimumScore: Int? = null,
    /** Which stream plays when you hit play. */
    val useForFirstStream: Boolean = true,
    val useForAutoDownload: Boolean = true,
    val useForFailover: Boolean = true,
    /**
     * Let the score decide the next episode instead of the binge group.
     *
     * Binge groups are the addon's opinion, copied verbatim from `behaviorHints.bingeGroup` — the app
     * never computes one. Preferring that group normally beats every selection rule the app has,
     * including this profile, which is how a stream this profile scores at 290 ends up playing while
     * a 1076 sits in the same list. Off by default so binge affinity keeps working as it always has;
     * on, the score wins wherever it is already deciding the pick.
     */
    val overrideBingeGroup: Boolean = false,
    val sizeBand: StreamSizeBand = StreamSizeBand(),
    /**
     * Show each stream's score on its card in the source lists. Off by default — it is a diagnostic,
     * not something most people want cluttering the picker — but invaluable for sanity-checking a
     * profile against a large real sample.
     */
    val showScoreOnStreams: Boolean = false,
    /**
     * Reorder the visible source list by score instead of provider order. Separate from
     * [useForFirstStream] on purpose: picking the best stream automatically and *presenting* the
     * list in score order are different wants, and someone may sensibly enable one without the other.
     */
    val sortStreamList: Boolean = false,
    /**
     * Collapse every addon's results into one score-ordered list instead of a section per addon.
     * Only meaningful alongside [sortStreamList] — without a sort the merged list would be an
     * arbitrary concatenation — so the UI gates it on that.
     */
    val mergeSources: Boolean = false,
    /**
     * Offer the collapsed, score-ordered list as an **extra** source tab ("HTPC") beside the real
     * addon tabs, instead of reordering or replacing them.
     *
     * Deliberately independent of [sortStreamList] and [mergeSources]: the point is to keep every
     * addon's own ordering exactly as that addon sent it while still having one list ranked by this
     * profile, so it has to work with the sort turned off.
     */
    val htpcSourceTab: Boolean = false,
) {
    fun pointsFor(trait: StreamScoreTrait): Int = points[trait.id] ?: 0

    fun withPoints(trait: StreamScoreTrait, value: Int): StreamScoreProfile {
        val clamped = value.coerceIn(MIN_POINTS, MAX_POINTS)
        // Zero is the absence of an opinion, so it is stored as absence — this keeps a profile that
        // has been reset back to defaults from carrying a map full of noise.
        return copy(points = if (clamped == 0) points - trait.id else points + (trait.id to clamped))
    }

    /** True when this profile should influence the given consumer. */
    fun appliesToFirstStream(): Boolean = enabled && useForFirstStream
    fun appliesToAutoDownload(): Boolean = enabled && useForAutoDownload
    fun appliesToFailover(): Boolean = enabled && useForFailover

    /**
     * Deliberately not gated on [useForFirstStream]: what matters is whether scoring is the thing
     * choosing the stream, which callers establish by checking the effective mode is SCORED — that
     * covers both this toggle and an explicitly selected "best score" mode. See
     * [StreamAutoPlayPolicy.scoreOverridesBingeGroup].
     */
    fun appliesToBingeGroupOverride(): Boolean = enabled && overrideBingeGroup

    // --- Setup questions ------------------------------------------------------------------------
    // Each answer writes only its own question's traits (and Q4 the size band), leaving every other
    // trait — including manual edits — untouched. withPoints clamps and drops zeroed traits, so an
    // option that sets a trait to 0 (e.g. "Prefer SDR" zeroing the HDR rows) removes it cleanly.

    fun withAudioDevice(answer: AudioDeviceSupport): StreamScoreProfile =
        patchAnswers(answers.copy(audioDevice = answer), answer.points())

    fun withHdrPreference(answer: HdrPreference): StreamScoreProfile =
        patchAnswers(answers.copy(hdr = answer), answer.points())

    fun withThreeDEquipment(answer: ThreeDEquipment): StreamScoreProfile =
        patchAnswers(answers.copy(threeD = answer), answer.points())

    fun withSizeQuality(answer: SizeQualityPreference): StreamScoreProfile =
        patchAnswers(answers.copy(sizeQuality = answer), answer.points(), answer.sizeBand(sizeBand))

    fun withLanguageImportance(answer: LanguageImportance): StreamScoreProfile =
        patchAnswers(answers.copy(language = answer), answer.points())

    fun withDebridCachedBoost(answer: DebridCachedBoost): StreamScoreProfile =
        patchAnswers(answers.copy(debridCached = answer), answer.points())

    fun withUnknownGroupTrust(answer: UnknownGroupTrust): StreamScoreProfile =
        patchAnswers(answers.copy(unknownGroup = answer), answer.points())

    fun withLowQualityPenalty(answer: LowQualityGroupPenalty): StreamScoreProfile =
        patchAnswers(answers.copy(lowQualityGroup = answer), answer.points())

    /**
     * Rebuilds every score from the answers as they currently stand: question-owned traits go back to
     * what their answer implies, and the traits no question controls go back to their defaults.
     *
     * This is the escape hatch from hand-tuning — the answers are kept exactly as chosen, only the
     * manual per-trait edits are discarded. The size band follows the same rule the size/quality
     * question uses, so a band the user has enabled and tuned is left alone.
     */
    fun resetPointsToAnswers(): StreamScoreProfile = copy(
        points = answers.toPointsMap(),
        sizeBand = answers.sizeQuality.sizeBand(sizeBand),
    )

    private fun patchAnswers(
        nextAnswers: ScoreAnswers,
        slice: Map<StreamScoreTrait, Int>,
        nextBand: StreamSizeBand = sizeBand,
    ): StreamScoreProfile {
        var next = copy(answers = nextAnswers, sizeBand = nextBand)
        slice.forEach { (trait, value) -> next = next.withPoints(trait, value) }
        return next
    }

    companion object {
        const val MIN_POINTS = -10000
        const val MAX_POINTS = 10000
    }
}

/**
 * Target size window, used by [StreamScoreTrait.SIZE_IN_BAND] / [StreamScoreTrait.SIZE_FAR_OUT].
 *
 * Movies and episodes carry separate bounds because a 40GB movie and a 40GB episode are entirely
 * different judgements. This is about taste; [StreamScoreTrait.IMPLAUSIBLE_SIZE] is about validity.
 */
@Serializable
data class StreamSizeBand(
    val enabled: Boolean = false,
    val movieMinGb: Double = 8.0,
    val movieMaxGb: Double = 40.0,
    val episodeMinGb: Double = 1.0,
    val episodeMaxGb: Double = 8.0,
    /**
     * How far outside the band still scores neutral, as a fraction of the bound. Stops a 41GB file
     * being punished for missing a 40GB ceiling by a rounding error.
     */
    val graceFraction: Double = 0.5,
) {
    fun minBytes(isEpisode: Boolean): Long =
        ((if (isEpisode) episodeMinGb else movieMinGb) * BYTES_PER_GB).toLong()

    fun maxBytes(isEpisode: Boolean): Long =
        ((if (isEpisode) episodeMaxGb else movieMaxGb) * BYTES_PER_GB).toLong()

    companion object {
        const val BYTES_PER_GB = 1_000_000_000.0
    }
}

enum class StreamScoreTraitGroup {
    /** Resolution and source combined into one matrix — see the QUALITY_* traits. */
    QUALITY,
    HDR,
    AUDIO,
    CHANNELS,
    CODEC,
    RELEASE,
    RELEASE_FLAGS,
    EDITION,
    LANGUAGE,
    AVAILABILITY,
    SIZE,
}

/**
 * The fixed catalogue of scorable traits. Adding one here is all it takes to make it scorable —
 * [StreamScorer] matches on the enum and the settings page renders from it.
 *
 * [defaultPoints] is the "Balanced" preset value; 0 means the preset has no opinion on it.
 */
enum class StreamScoreTrait(
    val id: String,
    val group: StreamScoreTraitGroup,
    val labelRes: StringResource,
    val defaultPoints: Int = 0,
    /** Shown under the label where the trait's behaviour is not obvious from its name. */
    val captionRes: StringResource? = null,
) {
    // Quality — resolution and source as a single matrix.
    //
    // These are the **baseline**: they carry hundreds of points, and everything else in the model
    // nudges a stream up or down from the row it lands on. Combining the two axes means you set the
    // value of "4K WEB-DL" directly instead of working out what a resolution score plus a source
    // score happens to add up to.
    //
    // A row only fires when **both** axes are known. A stream with a resolution but no source (or
    // vice versa) deliberately scores nothing here and sinks on its own, which is why there is no
    // explicit penalty for SD, HDTV or unlabelled releases in this section — 0 is already last.
    QUALITY_2160P_REMUX(
        "quality_2160p_remux",
        StreamScoreTraitGroup.QUALITY,
        Res.string.settings_stream_scoring_trait_quality_2160p_remux,
        350,
        Res.string.settings_stream_scoring_trait_quality_caption,
    ),
    QUALITY_2160P_BLURAY("quality_2160p_bluray", StreamScoreTraitGroup.QUALITY, Res.string.settings_stream_scoring_trait_quality_2160p_bluray, 900),
    QUALITY_2160P_WEB_DL("quality_2160p_web_dl", StreamScoreTraitGroup.QUALITY, Res.string.settings_stream_scoring_trait_quality_2160p_web_dl, 800),
    QUALITY_2160P_WEBRIP("quality_2160p_webrip", StreamScoreTraitGroup.QUALITY, Res.string.settings_stream_scoring_trait_quality_2160p_webrip, 700),
    QUALITY_1080P_REMUX("quality_1080p_remux", StreamScoreTraitGroup.QUALITY, Res.string.settings_stream_scoring_trait_quality_1080p_remux, 775),
    QUALITY_1080P_BLURAY("quality_1080p_bluray", StreamScoreTraitGroup.QUALITY, Res.string.settings_stream_scoring_trait_quality_1080p_bluray, 600),
    QUALITY_1080P_WEB_DL("quality_1080p_web_dl", StreamScoreTraitGroup.QUALITY, Res.string.settings_stream_scoring_trait_quality_1080p_web_dl, 500),
    QUALITY_1080P_WEBRIP("quality_1080p_webrip", StreamScoreTraitGroup.QUALITY, Res.string.settings_stream_scoring_trait_quality_1080p_webrip, 400),
    QUALITY_720P_REMUX("quality_720p_remux", StreamScoreTraitGroup.QUALITY, Res.string.settings_stream_scoring_trait_quality_720p_remux, 300),
    QUALITY_720P_BLURAY("quality_720p_bluray", StreamScoreTraitGroup.QUALITY, Res.string.settings_stream_scoring_trait_quality_720p_bluray, 200),
    QUALITY_720P_WEB_DL("quality_720p_web_dl", StreamScoreTraitGroup.QUALITY, Res.string.settings_stream_scoring_trait_quality_720p_web_dl, 150),
    QUALITY_720P_WEBRIP("quality_720p_webrip", StreamScoreTraitGroup.QUALITY, Res.string.settings_stream_scoring_trait_quality_720p_webrip, 100),
    SRC_HDTV("src_hdtv", StreamScoreTraitGroup.QUALITY, Res.string.settings_stream_scoring_trait_src_hdtv, 0),
    // Tier 2 of the four penalty tiers — see StreamScoreTrait's class doc.
    SRC_JUNK(
        "src_junk",
        StreamScoreTraitGroup.QUALITY,
        Res.string.settings_stream_scoring_trait_src_junk,
        -3000,
        Res.string.settings_stream_scoring_trait_src_junk_caption,
    ),

    // HDR
    // These four do not stack: a release has one HDR format, so only the highest-scoring match
    // counts. On mpv they mostly render identically anyway — see StreamScorer.collapseHdrFormats.
    HDR_DOLBY_VISION(
        "hdr_dolby_vision",
        StreamScoreTraitGroup.HDR,
        Res.string.settings_stream_scoring_trait_hdr_dolby_vision,
        25,
        Res.string.settings_stream_scoring_trait_hdr_dolby_vision_caption,
    ),
    HDR_10_PLUS("hdr_10_plus", StreamScoreTraitGroup.HDR, Res.string.settings_stream_scoring_trait_hdr_10_plus, 25),
    HDR_10(
        "hdr_10",
        StreamScoreTraitGroup.HDR,
        Res.string.settings_stream_scoring_trait_hdr_10,
        25,
        Res.string.settings_stream_scoring_trait_hdr_10_caption,
    ),
    HDR_HLG("hdr_hlg", StreamScoreTraitGroup.HDR, Res.string.settings_stream_scoring_trait_hdr_hlg, 0),
    HDR_10_BIT("hdr_10_bit", StreamScoreTraitGroup.HDR, Res.string.settings_stream_scoring_trait_hdr_10_bit, 0),
    HDR_SDR("hdr_sdr", StreamScoreTraitGroup.HDR, Res.string.settings_stream_scoring_trait_hdr_sdr),

    // Audio
    AUDIO_ATMOS("audio_atmos", StreamScoreTraitGroup.AUDIO, Res.string.settings_stream_scoring_trait_audio_atmos, 40),
    AUDIO_TRUEHD("audio_truehd", StreamScoreTraitGroup.AUDIO, Res.string.settings_stream_scoring_trait_audio_truehd, 25),
    AUDIO_DTS_X("audio_dts_x", StreamScoreTraitGroup.AUDIO, Res.string.settings_stream_scoring_trait_audio_dts_x, 30),
    AUDIO_DTS_HD_MA("audio_dts_hd_ma", StreamScoreTraitGroup.AUDIO, Res.string.settings_stream_scoring_trait_audio_dts_hd_ma, 20),
    AUDIO_DD_PLUS("audio_dd_plus", StreamScoreTraitGroup.AUDIO, Res.string.settings_stream_scoring_trait_audio_dd_plus, 10),
    AUDIO_DTS("audio_dts", StreamScoreTraitGroup.AUDIO, Res.string.settings_stream_scoring_trait_audio_dts, 5),
    AUDIO_FLAC("audio_flac", StreamScoreTraitGroup.AUDIO, Res.string.settings_stream_scoring_trait_audio_flac),
    AUDIO_AAC("audio_aac", StreamScoreTraitGroup.AUDIO, Res.string.settings_stream_scoring_trait_audio_aac),

    // Channels
    CH_7_1("ch_7_1", StreamScoreTraitGroup.CHANNELS, Res.string.settings_stream_scoring_trait_ch_7_1, 10),
    CH_5_1("ch_5_1", StreamScoreTraitGroup.CHANNELS, Res.string.settings_stream_scoring_trait_ch_5_1, 5),
    CH_2_0("ch_2_0", StreamScoreTraitGroup.CHANNELS, Res.string.settings_stream_scoring_trait_ch_2_0),

    // Codec
    CODEC_AV1(
        "codec_av1",
        StreamScoreTraitGroup.CODEC,
        Res.string.settings_stream_scoring_trait_codec_av1,
        -1000,
        Res.string.settings_stream_scoring_trait_codec_av1_caption,
    ),
    CODEC_HEVC("codec_hevc", StreamScoreTraitGroup.CODEC, Res.string.settings_stream_scoring_trait_codec_hevc, 10),
    CODEC_AVC("codec_avc", StreamScoreTraitGroup.CODEC, Res.string.settings_stream_scoring_trait_codec_avc),

    // Release group
    GROUP_TRUSTED(
        "group_trusted",
        StreamScoreTraitGroup.RELEASE,
        Res.string.settings_stream_scoring_trait_group_trusted,
        35,
        Res.string.settings_stream_scoring_trait_group_trusted_caption,
    ),
    GROUP_UNKNOWN(
        "group_unknown",
        StreamScoreTraitGroup.RELEASE,
        Res.string.settings_stream_scoring_trait_group_unknown,
        -20,
        Res.string.settings_stream_scoring_trait_group_unknown_caption,
    ),
    GROUP_LOW_QUALITY(
        "group_low_quality",
        StreamScoreTraitGroup.RELEASE,
        Res.string.settings_stream_scoring_trait_group_low_quality,
        -1000,
        Res.string.settings_stream_scoring_trait_group_low_quality_caption,
    ),

    // Release flags — miscellaneous release-level attributes, detected from the name.
    REPACK_PROPER(
        "repack_proper",
        StreamScoreTraitGroup.RELEASE_FLAGS,
        Res.string.settings_stream_scoring_trait_repack_proper,
        1,
        Res.string.settings_stream_scoring_trait_repack_proper_caption,
    ),
    HYBRID(
        "hybrid",
        StreamScoreTraitGroup.RELEASE_FLAGS,
        Res.string.settings_stream_scoring_trait_hybrid,
        10,
        Res.string.settings_stream_scoring_trait_hybrid_caption,
    ),
    SEASON_PACK(
        "season_pack",
        StreamScoreTraitGroup.RELEASE_FLAGS,
        Res.string.settings_stream_scoring_trait_season_pack,
        1,
        Res.string.settings_stream_scoring_trait_season_pack_caption,
    ),
    // Reuses the existing 3D visual tags; renders as a squashed side-by-side / over-under picture on
    // a normal 2D display, so most desktop users want it out of the way.
    THREE_D(
        "three_d",
        StreamScoreTraitGroup.RELEASE_FLAGS,
        Res.string.settings_stream_scoring_trait_three_d,
        -4000,
        Res.string.settings_stream_scoring_trait_three_d_caption,
    ),
    // AI upscales and AI-generated HDR/DV frequently look worse than a genuine lower-resolution
    // release, so this is a strong penalty. Many AI-upscale groups are also in the low-quality list,
    // and the two penalties deliberately stack.
    AI_ENHANCED(
        "ai_enhanced",
        StreamScoreTraitGroup.RELEASE_FLAGS,
        Res.string.settings_stream_scoring_trait_ai_enhanced,
        -2000,
        Res.string.settings_stream_scoring_trait_ai_enhanced_caption,
    ),
    // A full untouched disc image (ISO / BDMV / BD25-100) is awkward or impossible to stream and
    // play through this client.
    FULL_DISC(
        "full_disc",
        StreamScoreTraitGroup.RELEASE_FLAGS,
        Res.string.settings_stream_scoring_trait_full_disc,
        -4000,
        Res.string.settings_stream_scoring_trait_full_disc_caption,
    ),
    // Fires only when neither the resolution nor the source could be identified — the mark of a
    // lazily or deceptively named release. Missing just one tag is common and usually still fine.
    MISSING_METADATA(
        "missing_metadata",
        StreamScoreTraitGroup.RELEASE_FLAGS,
        Res.string.settings_stream_scoring_trait_missing_metadata,
        -100,
        Res.string.settings_stream_scoring_trait_missing_metadata_caption,
    ),

    // Editions & extras
    SPECIAL_EDITION(
        "special_edition",
        StreamScoreTraitGroup.EDITION,
        Res.string.settings_stream_scoring_trait_special_edition,
        1,
        Res.string.settings_stream_scoring_trait_special_edition_caption,
    ),
    EXTRAS_BONUS(
        "extras_bonus",
        StreamScoreTraitGroup.EDITION,
        Res.string.settings_stream_scoring_trait_extras_bonus,
        -60,
        Res.string.settings_stream_scoring_trait_extras_bonus_caption,
    ),

    // Language
    LANGUAGE_PREFERRED(
        "language_preferred",
        StreamScoreTraitGroup.LANGUAGE,
        Res.string.settings_stream_scoring_trait_language_preferred,
        50,
        Res.string.settings_stream_scoring_trait_language_preferred_caption,
    ),

    // Availability
    DEBRID_CACHED(
        "debrid_cached",
        StreamScoreTraitGroup.AVAILABILITY,
        Res.string.settings_stream_scoring_trait_debrid_cached,
        50,
        Res.string.settings_stream_scoring_trait_debrid_cached_caption,
    ),

    // Size
    IMPLAUSIBLE_SIZE(
        "implausible_size",
        StreamScoreTraitGroup.SIZE,
        Res.string.settings_stream_scoring_trait_implausible_size,
        -4000,
        Res.string.settings_stream_scoring_trait_implausible_size_caption,
    ),
    SIZE_IN_BAND(
        "size_in_band",
        StreamScoreTraitGroup.SIZE,
        Res.string.settings_stream_scoring_trait_size_in_band,
        20,
        Res.string.settings_stream_scoring_trait_size_in_band_caption,
    ),
    SIZE_FAR_OUT(
        "size_far_out",
        StreamScoreTraitGroup.SIZE,
        Res.string.settings_stream_scoring_trait_size_far_out,
        -50,
        Res.string.settings_stream_scoring_trait_size_far_out_caption,
    ),
    ;

    companion object {
        private val byId = entries.associateBy { it.id }

        fun fromId(id: String): StreamScoreTrait? = byId[id]

        fun inGroup(group: StreamScoreTraitGroup): List<StreamScoreTrait> = entries.filter { it.group == group }
    }
}

/**
 * The setup questionnaire that replaces the old monolithic presets.
 *
 * Rather than one lever that moves everything at once, the profile is configured by a handful of
 * **orthogonal** questions, each owning a *disjoint* slice of traits. That decoupling is the whole
 * point: asking for a better picture no longer drags audio up with it, and answering the audio
 * question never touches HDR. A question's neutral option reuses each trait's
 * [StreamScoreTrait.defaultPoints]; the other options are expressed as deltas on top, so the per-trait
 * baseline lives in exactly one place.
 */
interface ScoreAnswerOption {
    /** Label shown in the settings dropdown. */
    val labelRes: StringResource
}

/** Q1 — does the audio chain handle advanced formats, or is it monitor speakers / cheap headphones? */
@Serializable
enum class AudioDeviceSupport(override val labelRes: StringResource) : ScoreAnswerOption {
    ADVANCED(Res.string.settings_stream_scoring_answer_audio_advanced),
    BASIC(Res.string.settings_stream_scoring_answer_audio_basic),
    ;

    fun points(): Map<StreamScoreTrait, Int> = when (this) {
        // The full-fat values live on the traits themselves.
        ADVANCED -> OWNED.associateWith { it.defaultPoints }
        // Down-weighted to a tiebreak: top audio (Atmos 12) now sits below top HDR (20) instead of
        // doubling it. On basic gear a lossless track should not outrank a better picture.
        BASIC -> mapOf(
            StreamScoreTrait.AUDIO_ATMOS to 12,
            StreamScoreTrait.AUDIO_TRUEHD to 8,
            StreamScoreTrait.AUDIO_DTS_X to 10,
            StreamScoreTrait.AUDIO_DTS_HD_MA to 6,
            StreamScoreTrait.AUDIO_DD_PLUS to 3,
            StreamScoreTrait.AUDIO_DTS to 2,
            StreamScoreTrait.AUDIO_FLAC to 0,
            StreamScoreTrait.AUDIO_AAC to 0,
            StreamScoreTrait.CH_7_1 to 3,
            StreamScoreTrait.CH_5_1 to 1,
            StreamScoreTrait.CH_2_0 to 0,
        )
    }

    companion object {
        private val OWNED = StreamScoreTrait.inGroup(StreamScoreTraitGroup.AUDIO) +
            StreamScoreTrait.inGroup(StreamScoreTraitGroup.CHANNELS)
    }
}

/** Q2 — how hard to prefer HDR. */
@Serializable
enum class HdrPreference(override val labelRes: StringResource) : ScoreAnswerOption {
    HIGH(Res.string.settings_stream_scoring_answer_hdr_high),
    MEDIUM(Res.string.settings_stream_scoring_answer_hdr_medium),
    PREFER_SDR(Res.string.settings_stream_scoring_answer_hdr_prefer_sdr),
    ;

    fun points(): Map<StreamScoreTrait, Int> = when (this) {
        MEDIUM -> OWNED.associateWith { it.defaultPoints }
        // A large, deliberate swing: HDR is the one picture difference big enough to reorder the
        // quality baseline, so "Strongly" can lift a lower row above a higher one.
        HIGH -> mapOf(
            StreamScoreTrait.HDR_DOLBY_VISION to 100,
            StreamScoreTrait.HDR_10_PLUS to 100,
            StreamScoreTrait.HDR_10 to 100,
            StreamScoreTrait.HDR_HLG to 0,
            StreamScoreTrait.HDR_10_BIT to 0,
            StreamScoreTrait.HDR_SDR to 0,
        )
        // For an SDR display: don't chase HDR (mpv must tone-map it, often for the worse) and reward
        // a natively-SDR grade by the same margin instead.
        PREFER_SDR -> mapOf(
            StreamScoreTrait.HDR_DOLBY_VISION to 0,
            StreamScoreTrait.HDR_10_PLUS to 0,
            StreamScoreTrait.HDR_10 to 0,
            StreamScoreTrait.HDR_HLG to 0,
            StreamScoreTrait.HDR_10_BIT to 0,
            StreamScoreTrait.HDR_SDR to 100,
        )
    }

    companion object {
        private val OWNED = StreamScoreTrait.inGroup(StreamScoreTraitGroup.HDR)
    }
}

/** Q3 — 3D display equipment. No 3D display ⇒ bury 3D releases. */
@Serializable
enum class ThreeDEquipment(override val labelRes: StringResource) : ScoreAnswerOption {
    YES(Res.string.settings_stream_scoring_answer_three_d_yes),
    NO(Res.string.settings_stream_scoring_answer_three_d_no),
    ;

    fun points(): Map<StreamScoreTrait, Int> = mapOf(
        // -1000 (junk tier) sinks a 3D release below everything, but does not hard-reject it: if a
        // half-SBS file is the only stream, it still plays rather than nothing. A true veto would
        // require a minimum score to be set.
        StreamScoreTrait.THREE_D to if (this == NO) -4000 else 0,
    )
}

/** Q4 — the size-vs-quality trade. Owns resolution / source / codec / size only (not audio or HDR). */
@Serializable
enum class SizeQualityPreference(override val labelRes: StringResource) : ScoreAnswerOption {
    QUALITY(Res.string.settings_stream_scoring_answer_size_quality),
    BALANCED(Res.string.settings_stream_scoring_answer_size_balanced),
    SMALL(Res.string.settings_stream_scoring_answer_size_small),
    ;

    fun points(): Map<StreamScoreTrait, Int> = when (this) {
        BALANCED -> OWNED.associateWith { it.defaultPoints }
        // Straight quality order: the disc sources on top, resolution outranking source.
        QUALITY -> OWNED.associateWith { it.defaultPoints } + mapOf(
            StreamScoreTrait.QUALITY_2160P_REMUX to 900,
            StreamScoreTrait.QUALITY_2160P_BLURAY to 800,
            StreamScoreTrait.QUALITY_2160P_WEB_DL to 700,
            StreamScoreTrait.QUALITY_2160P_WEBRIP to 600,
            StreamScoreTrait.QUALITY_1080P_REMUX to 675,
            StreamScoreTrait.QUALITY_1080P_BLURAY to 500,
            StreamScoreTrait.QUALITY_1080P_WEB_DL to 400,
            StreamScoreTrait.QUALITY_1080P_WEBRIP to 300,
            StreamScoreTrait.QUALITY_720P_REMUX to 200,
            StreamScoreTrait.QUALITY_720P_BLURAY to 150,
            StreamScoreTrait.QUALITY_720P_WEB_DL to 100,
            StreamScoreTrait.QUALITY_720P_WEBRIP to 50,
        )
        // Inverted: efficient 1080p encodes win outright and remuxes are worthless, because the
        // whole point is bytes on disk.
        SMALL -> OWNED.associateWith { it.defaultPoints } + mapOf(
            StreamScoreTrait.QUALITY_2160P_REMUX to 0,
            StreamScoreTrait.QUALITY_2160P_BLURAY to 50,
            StreamScoreTrait.QUALITY_2160P_WEB_DL to 100,
            StreamScoreTrait.QUALITY_2160P_WEBRIP to 150,
            StreamScoreTrait.QUALITY_1080P_REMUX to 200,
            StreamScoreTrait.QUALITY_1080P_BLURAY to 700,
            StreamScoreTrait.QUALITY_1080P_WEB_DL to 800,
            StreamScoreTrait.QUALITY_1080P_WEBRIP to 900,
            StreamScoreTrait.QUALITY_720P_REMUX to 300,
            StreamScoreTrait.QUALITY_720P_BLURAY to 600,
            StreamScoreTrait.QUALITY_720P_WEB_DL to 500,
            StreamScoreTrait.QUALITY_720P_WEBRIP to 400,
            StreamScoreTrait.CODEC_HEVC to 30,
            StreamScoreTrait.SIZE_FAR_OUT to -100,
        )
    }

    /** Sets the size-band bounds to match, but only while the user has not enabled their own band. */
    fun sizeBand(current: StreamSizeBand): StreamSizeBand {
        if (current.enabled) return current
        return when (this) {
            BALANCED -> StreamSizeBand()
            QUALITY -> StreamSizeBand(movieMinGb = 15.0, movieMaxGb = 80.0, episodeMinGb = 2.0, episodeMaxGb = 20.0)
            SMALL -> StreamSizeBand(movieMinGb = 1.0, movieMaxGb = 10.0, episodeMinGb = 0.3, episodeMaxGb = 3.0)
        }
    }

    companion object {
        // The 12-row quality matrix plus codec and size. SRC_JUNK is deliberately NOT here — it is
        // an always-applied penalty tier, not something this question should be able to soften.
        private val OWNED = listOf(
            StreamScoreTrait.QUALITY_2160P_REMUX,
            StreamScoreTrait.QUALITY_2160P_BLURAY,
            StreamScoreTrait.QUALITY_2160P_WEB_DL,
            StreamScoreTrait.QUALITY_2160P_WEBRIP,
            StreamScoreTrait.QUALITY_1080P_REMUX,
            StreamScoreTrait.QUALITY_1080P_BLURAY,
            StreamScoreTrait.QUALITY_1080P_WEB_DL,
            StreamScoreTrait.QUALITY_1080P_WEBRIP,
            StreamScoreTrait.QUALITY_720P_REMUX,
            StreamScoreTrait.QUALITY_720P_BLURAY,
            StreamScoreTrait.QUALITY_720P_WEB_DL,
            StreamScoreTrait.QUALITY_720P_WEBRIP,
            StreamScoreTrait.SRC_HDTV,
            StreamScoreTrait.CODEC_AV1, StreamScoreTrait.CODEC_HEVC, StreamScoreTrait.CODEC_AVC,
            StreamScoreTrait.SIZE_IN_BAND, StreamScoreTrait.SIZE_FAR_OUT,
        )
    }
}

/** Q5 — how important a preferred-language audio match is. */
@Serializable
enum class LanguageImportance(override val labelRes: StringResource) : ScoreAnswerOption {
    EXTREMELY(Res.string.settings_stream_scoring_answer_language_extremely),
    MEDIUM(Res.string.settings_stream_scoring_answer_language_medium),
    NOT_AT_ALL(Res.string.settings_stream_scoring_answer_language_not_at_all),
    ;

    fun points(): Map<StreamScoreTrait, Int> = mapOf(
        StreamScoreTrait.LANGUAGE_PREFERRED to when (this) {
            EXTREMELY -> 100
            MEDIUM -> StreamScoreTrait.LANGUAGE_PREFERRED.defaultPoints
            NOT_AT_ALL -> 0
        },
    )
}

/** Q6 — boost for streams already cached on debrid (instant playback). */
@Serializable
enum class DebridCachedBoost(override val labelRes: StringResource) : ScoreAnswerOption {
    HIGH(Res.string.settings_stream_scoring_answer_cached_high),
    MEDIUM(Res.string.settings_stream_scoring_answer_cached_medium),
    NONE(Res.string.settings_stream_scoring_answer_cached_none),
    ;

    fun points(): Map<StreamScoreTrait, Int> = mapOf(
        StreamScoreTrait.DEBRID_CACHED to when (this) {
            HIGH -> 100
            MEDIUM -> StreamScoreTrait.DEBRID_CACHED.defaultPoints
            NONE -> 0
        },
    )
}

/** Q7 — how much to trust releases from groups we do not recognise. */
@Serializable
enum class UnknownGroupTrust(override val labelRes: StringResource) : ScoreAnswerOption {
    HIGH(Res.string.settings_stream_scoring_answer_unknown_high),
    MEDIUM(Res.string.settings_stream_scoring_answer_unknown_medium),
    NONE(Res.string.settings_stream_scoring_answer_unknown_none),
    ;

    fun points(): Map<StreamScoreTrait, Int> = mapOf(
        StreamScoreTrait.GROUP_UNKNOWN to when (this) {
            HIGH -> 0
            MEDIUM -> StreamScoreTrait.GROUP_UNKNOWN.defaultPoints
            NONE -> -100
        },
    )
}

/** Q8 — how hard to penalise known low-quality groups. */
@Serializable
enum class LowQualityGroupPenalty(override val labelRes: StringResource) : ScoreAnswerOption {
    HIGH(Res.string.settings_stream_scoring_answer_low_quality_high),
    MEDIUM(Res.string.settings_stream_scoring_answer_low_quality_medium),
    NONE(Res.string.settings_stream_scoring_answer_low_quality_none),
    ;

    fun points(): Map<StreamScoreTrait, Int> = mapOf(
        StreamScoreTrait.GROUP_LOW_QUALITY to when (this) {
            HIGH -> StreamScoreTrait.GROUP_LOW_QUALITY.defaultPoints
            MEDIUM -> -100
            NONE -> 0
        },
    )
}

/**
 * The full set of answers, and the single place they compose into a points map.
 *
 * Defaults encode the "typical desktop viewer": basic audio (most people are on monitor speakers or
 * cheap headphones), medium HDR, no 3D gear, balanced size/quality, medium language and cache
 * weighting, medium trust in unknown groups, and a firm penalty on known-bad ones.
 */
@Serializable
data class ScoreAnswers(
    val audioDevice: AudioDeviceSupport = AudioDeviceSupport.BASIC,
    val hdr: HdrPreference = HdrPreference.MEDIUM,
    val threeD: ThreeDEquipment = ThreeDEquipment.NO,
    val sizeQuality: SizeQualityPreference = SizeQualityPreference.BALANCED,
    val language: LanguageImportance = LanguageImportance.MEDIUM,
    val debridCached: DebridCachedBoost = DebridCachedBoost.MEDIUM,
    val unknownGroup: UnknownGroupTrust = UnknownGroupTrust.MEDIUM,
    val lowQualityGroup: LowQualityGroupPenalty = LowQualityGroupPenalty.HIGH,
) {
    /**
     * The effective per-trait points: a fixed base (traits no question controls) overlaid with each
     * question's slice. Zero means "no opinion" and is dropped, matching [StreamScoreProfile.withPoints].
     */
    fun toPointsMap(): Map<String, Int> {
        val out = HashMap<StreamScoreTrait, Int>()
        BASE_TRAITS.forEach { out[it] = it.defaultPoints }
        listOf(
            audioDevice.points(), hdr.points(), threeD.points(), sizeQuality.points(),
            language.points(), debridCached.points(), unknownGroup.points(), lowQualityGroup.points(),
        ).forEach { slice -> slice.forEach { (trait, value) -> out[trait] = value } }
        return out.filterValues { it != 0 }.mapKeys { it.key.id }
    }

    companion object {
        /**
         * Traits no question owns — always-on safety (junk rip, implausible size) plus release-quality
         * signals (trusted group, repack, hybrid, AI upscale, full disc, unlabelled, bonus extras).
         * Their baseline is each trait's [StreamScoreTrait.defaultPoints]. Any of these that default
         * to 0 would simply be absent from the map — zero means "no opinion".
         */
        private val BASE_TRAITS = listOf(
            StreamScoreTrait.GROUP_TRUSTED,
            StreamScoreTrait.SRC_JUNK,
            StreamScoreTrait.IMPLAUSIBLE_SIZE,
            StreamScoreTrait.REPACK_PROPER,
            StreamScoreTrait.HYBRID,
            StreamScoreTrait.AI_ENHANCED,
            StreamScoreTrait.FULL_DISC,
            StreamScoreTrait.MISSING_METADATA,
            StreamScoreTrait.EXTRAS_BONUS,
            StreamScoreTrait.SEASON_PACK,
            StreamScoreTrait.SPECIAL_EDITION,
        )
    }
}

/**
 * A profile together with the content it is scoring.
 *
 * The two travel as one because they are meaningless apart: a profile applied with the wrong content
 * type measures episodes against the movie size band and marks every one of them "far from preferred
 * size". Helpers that optionally score take this rather than a bare profile, so it is not possible to
 * supply one without the other.
 */
data class StreamScoring(
    val profile: StreamScoreProfile,
    val context: StreamScoreContext,
)

/** One trait that fired, and what it contributed. */
data class StreamScoreComponent(
    val trait: StreamScoreTrait,
    val points: Int,
)

/**
 * A scored stream. [components] is what the settings preview and the stream-card breakdown render,
 * so explanation and ranking always come from the same computation.
 */
data class StreamScore(
    val total: Int,
    val components: List<StreamScoreComponent>,
    /** True when [total] fell below the profile's minimum score. */
    val rejected: Boolean,
) {
    companion object {
        val NEUTRAL = StreamScore(total = 0, components = emptyList(), rejected = false)
    }
}

/**
 * What is being scored. Size judgements need to know whether this is a film or an episode, and how
 * long it runs, neither of which can be read off the stream alone.
 */
data class StreamScoreContext(
    val isEpisode: Boolean = false,
    val runtimeMinutes: Int? = null,
    /**
     * Normalised audio language codes the user prefers, for [StreamScoreTrait.LANGUAGE_PREFERRED].
     *
     * Supplied by the caller rather than stored on the profile: language preference already lives in
     * Playback settings, and a second copy here would be two places to set one thing. Use
     * [StreamScoreContexts.forPlayback] to build a context that reads it.
     */
    val preferredLanguages: List<String> = emptyList(),
    /**
     * Animation (anime or Western cartoon). Flat colour, little grain and long static holds mean a
     * perfectly good animated encode lands far below the bitrate a live-action encode of the same
     * resolution needs, so [StreamScoreTrait.IMPLAUSIBLE_SIZE] relaxes its floors when this is set.
     * Nothing else in scoring reads it.
     */
    val isAnimation: Boolean = false,
) {
    companion object {
        val MOVIE = StreamScoreContext(isEpisode = false)
        val EPISODE = StreamScoreContext(isEpisode = true)
    }
}
