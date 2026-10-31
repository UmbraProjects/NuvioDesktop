package com.nuvio.app.features.preroll

import kotlinx.serialization.Serializable

/**
 * What the post-show shows once the credits start: a few cards looking back at what was just
 * watched, then a short quiz. The questions and theme reuse the pre-show's types so the quiz screen
 * and the palette code need no second copy.
 */
@Serializable
data class PostShowPack(
    val cards: List<PostShowCard> = emptyList(),
    val questions: List<TriviaQuestion> = emptyList(),
    val theme: TriviaTheme? = null,
) {
    val isEmpty: Boolean get() = cards.isEmpty() && questions.isEmpty()
}

@Serializable
data class PostShowCard(
    val kind: PostShowCardKind,
    val text: String,
)

/** The headings the cards carry. The screen turns each into a localised title. */
@Serializable
enum class PostShowCardKind {
    Ending,
    EasterEgg,
    WatchAgain,
    Motivation,
    Connection,
    BehindTheScenes,

    /** Wikipedia fallback only: what the film is about. The model's cards use Motivation. */
    Themes,

    /** Wikipedia fallback only: the attribution its licence asks for. Never produced by [from]. */
    Source;

    companion object {
        /** Null for anything unrecognised: a card mislabelled "Easter egg" is worse than no card. */
        fun from(raw: String?): PostShowCardKind? =
            when (raw?.trim()?.lowercase()?.replace(Regex("[\\s_-]+"), "")) {
                "ending", "endingexplained" -> Ending
                "easteregg", "hiddendetail", "hiddendetails" -> EasterEgg
                "watchagain", "rewatch", "lookoutfor" -> WatchAgain
                "motivation", "character", "charactermotivation" -> Motivation
                "connection", "connections", "reference", "references" -> Connection
                "behindthescenes", "production", "making", "makingof" -> BehindTheScenes
                else -> null
            }
    }
}

/** The pack as a pre-show stage machine sees it: slides are cards. */
fun PostShowPack.askableQuestions(random: kotlin.random.Random = kotlin.random.Random.Default): List<ShuffledQuestion> =
    questions.mapNotNull { it.shuffled(random) }
