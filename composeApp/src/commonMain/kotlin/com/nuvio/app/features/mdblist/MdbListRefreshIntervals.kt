package com.nuvio.app.features.mdblist

/**
 * One phase of a title's release with its own ratings refresh interval. [defaultDays] are tuned
 * for a free key's daily request limit; see [MdbListRatingsFreshness] for when each one applies.
 */
enum class MdbListRefreshTier(val storageKey: String, val defaultDays: Double) {
    /** Also the longest an unreleased film waits for its cinema date. */
    MovieUnreleased("movie_unreleased", 30.0),
    MovieCinemaFirstWeek("movie_cinema_first_week", 2.0),
    /** Longest a film in cinemas waits for its published streaming date. */
    MovieCinemaAwaitingStreaming("movie_cinema_awaiting_streaming", 30.0),
    MovieCinemaNoStreamingDate("movie_cinema_no_streaming_date", 14.0),
    MovieEarlyStreaming("movie_early_streaming", 3.0),
    MovieStreaming("movie_streaming", 14.0),
    MovieSettled("movie_settled", 60.0),
    ShowFirstSeason("show_first_season", 3.0),
    ShowAiring("show_airing", 7.0),
    /** Also the longest an idle show waits for its next dated episode. */
    ShowIdle("show_idle", 30.0),
    NotFound("not_found", 7.0),
    ;

    companion object {
        fun fromStorageKey(key: String): MdbListRefreshTier? = entries.firstOrNull { it.storageKey == key }
    }
}

/**
 * How hard the ratings cache leans on the key. Presets scale every default; Custom sets each; Auto
 * is Standard on a free key and Faster on a paid one, read from the key's daily limit.
 */
enum class MdbListRefreshPreset(val storageValue: String, val scale: Double?) {
    Auto("auto", null),
    Standard("standard", 1.0),
    Faster("faster", 0.5),
    Fastest("fastest", 0.25),
    Custom("custom", null),
    ;

    companion object {
        fun fromStorage(value: String?): MdbListRefreshPreset =
            entries.firstOrNull { it.storageValue == value } ?: Auto
    }
}

/** The days a refresh interval can be set to: dropdown steps, so a controller can set them too. */
val MDBLIST_REFRESH_DAY_CHOICES: List<Double> =
    listOf(0.5, 1.0, 2.0, 3.0, 5.0, 7.0, 10.0, 14.0, 21.0, 30.0, 45.0, 60.0, 90.0, 180.0)

internal object MdbListRefreshIntervalsCodec {
    /** `tier=days,tier=days`; unknown tiers and bad numbers are skipped. */
    fun encode(days: Map<MdbListRefreshTier, Double>): String =
        days.entries.joinToString(",") { (tier, value) -> "${tier.storageKey}=$value" }

    fun decode(raw: String?): Map<MdbListRefreshTier, Double> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(',').mapNotNull { part ->
            val tier = MdbListRefreshTier.fromStorageKey(part.substringBefore('=').trim()) ?: return@mapNotNull null
            val days = part.substringAfter('=', "").trim().toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }
                ?: return@mapNotNull null
            tier to days
        }.toMap()
    }
}
