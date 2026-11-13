package com.nuvio.app.features.tonight

/*
 * fork:tonight - the Movies & TV "Tonight" picker (design-notes/tonight-picker.md).
 *
 * Everything in this file is pure: the pool is gathered by TonightPool, then every rule here is a
 * plain function of it, so the CI tests (features.tonight.*) can pin each one down.
 */

enum class TonightKind { Film, Show }

/** Where a title in the pool came from. One title can come from several places. */
enum class TonightOrigin { Library, ContinueWatching, Trending, Recommended }

/**
 * One title the picker can offer. [runtimeMinutes] is the whole film, or one episode for a show;
 * null when no source said. [resumeMinutesLeft] is set for a film that is part-watched.
 */
data class TonightTitle(
    val type: String,
    val id: String,
    val name: String,
    val kind: TonightKind,
    val poster: String? = null,
    val backdrop: String? = null,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    val rating: Double? = null,
    val origins: Set<TonightOrigin> = emptySet(),
    /** Fully watched before (a film, or a show the user has marked watched). */
    val watched: Boolean = false,
    /** In Continue watching: a film half-watched, or a show with a next episode waiting. */
    val started: Boolean = false,
    /** For a started show, the episode that comes next ("S2 E4"). */
    val nextEpisodeLabel: String? = null,
    val resumeMinutesLeft: Int? = null,
    val addedAtEpochMs: Long? = null,
    val tmdbId: Int? = null,
    /** For a [TonightOrigin.Recommended] title, why Discover suggested it ("Because you watched Fargo"). */
    val recommendedBy: String? = null,
) {
    val key: String get() = tonightKey(type, id)
}

fun tonightKey(type: String, id: String): String = "${type.lowercase()}:$id"

/** The three cards Three Cards deals. */
enum class TonightSlot { Safe, New, Wildcard }

data class TonightCard(
    val slot: TonightSlot,
    val title: TonightTitle,
    /** One short line saying why this card, e.g. "You're on S2 E4". */
    val why: String,
)

/** Which mode tile was used last; stored by name so a renamed tile simply falls back. */
enum class TonightMode(val built: Boolean) {
    ThreeQuestions(true),
    ThreeCards(true),
    Knockout(true),
    TheMap(true),
    EndsBy(true),
    ChannelFlick(true),
    CoinToss(true),
    ThePile(true),
}
