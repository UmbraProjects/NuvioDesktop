package com.nuvio.app.features.preroll

import com.nuvio.app.features.details.MetaDetails
import kotlinx.serialization.Serializable

/**
 * What the pre-show shows before a film starts: slides to read and questions to answer.
 *
 * Both lists are optional on their own. A provider that returned good slides and unusable
 * questions still yields a pack worth showing, so emptiness is judged on the pair, not per list.
 */
@Serializable
data class TriviaPack(
    val slides: List<TriviaSlide> = emptyList(),
    val questions: List<TriviaQuestion> = emptyList(),
    /**
     * The film's visual identity, as the model sees it: a palette and an atmosphere the pre-show
     * paints over the film's own fanart. Optional, and not counted by [isEmpty]: a theme with no
     * facts is not a pack worth showing.
     */
    val theme: TriviaTheme? = null,
    /**
     * Where the facts came from, so the pre-show can say so and offer "not right?" on the sources
     * that can be wrong. Defaulted to [TriviaOrigin.Ai] so cache files written before this existed
     * (all of them AI packs) still load and read correctly.
     */
    val origin: TriviaOrigin = TriviaOrigin.Ai,
) {
    val isEmpty: Boolean get() = slides.isEmpty() && questions.isEmpty()
}

/** Which source produced a [TriviaPack]. */
@Serializable
enum class TriviaOrigin { Ai, Wikipedia, Metadata }

/**
 * Art direction for one film's pre-show. Colours are `#RRGGBB` strings (normalised by the parser)
 * so the cache file stays readable and the model's own output round-trips untouched.
 *
 * A text model cannot paint a picture, but it can say what a film *looks like*: Rocky is deep red
 * and gold under a ring spotlight, Alien is cold green-black fog. The renderer turns that into
 * colour grading and drifting particles over real fanart.
 */
@Serializable
data class TriviaTheme(
    val primary: String? = null,
    val secondary: String? = null,
    val atmosphere: TriviaAtmosphere = TriviaAtmosphere.Dust,
    /** A few words naming the look ("ring spotlight, worn leather"). Kept for debugging and future use. */
    val motif: String? = null,
)

/** The drifting-particle treatment laid over the artwork. */
@Serializable
enum class TriviaAtmosphere {
    Embers,
    Snow,
    Rain,
    Dust,
    Stars,
    Fog,
    Neon,
    Sparks,
    Bokeh,
    None;

    companion object {
        /** Unknown words fall back to gentle dust: always tasteful, never wrong for a film. */
        fun from(raw: String?): TriviaAtmosphere {
            val key = raw?.trim()?.lowercase() ?: return Dust
            return entries.firstOrNull { it.name.lowercase() == key } ?: Dust
        }
    }
}

@Serializable
data class TriviaSlide(
    val text: String,
    val category: TriviaCategory = TriviaCategory.Trivia,
)

@Serializable
enum class TriviaCategory {
    Production,
    Cast,
    Legacy,
    Awards,
    Trivia;

    companion object {
        /** Anything unrecognised is plain trivia: a mislabelled fact is still a good fact. */
        fun from(raw: String?): TriviaCategory = when (raw?.trim()?.lowercase()) {
            "production", "making", "behind the scenes", "behind-the-scenes" -> Production
            "cast", "casting", "actor", "actors" -> Cast
            "legacy", "reception", "impact", "influence" -> Legacy
            "awards", "award", "festival" -> Awards
            else -> Trivia
        }
    }
}

@Serializable
data class TriviaQuestion(
    val question: String,
    /** Three or four distinct options, in the order the model gave them. The UI shuffles. */
    val options: List<String>,
    val answerIndex: Int,
    val explanation: String? = null,
)

/**
 * Whether the model may draw on what it already knows about the film.
 *
 * The same trade as recaps make, with different stakes: [MetadataOnly] can only be thin, and
 * [ModelKnowledge] can be wrong. Trivia is mostly *about* things outside the supplied metadata
 * (production, casting stories), so [ModelKnowledge] is the default; [MetadataOnly] exists as the
 * strict option for anyone who would rather have less than risk an invented anecdote.
 */
enum class TriviaMode { MetadataOnly, ModelKnowledge }

/**
 * The facts a prompt is built from, lifted off [MetaDetails] so the prompt builder stays pure and
 * testable without a details record.
 */
data class TriviaSource(
    val title: String,
    val year: String?,
    val synopsis: String?,
    val directors: List<String>,
    val cast: List<String>,
    val genres: List<String>,
    val studios: List<String>,
    val collection: String?,
    val awards: String?,
    val keywords: List<String>,
    val runtime: String?,
    val country: String?,
    val language: String?,
) {
    /**
     * Enough to ask a meaningful question. A title and nothing else would make the model invent
     * the rest, which is the failure this whole feature has to avoid.
     */
    val isUsable: Boolean
        get() = title.isNotBlank() && (!year.isNullOrBlank() || directors.isNotEmpty() || cast.isNotEmpty())

    /**
     * A cheap, stable stand-in for the inputs, for the cache key. `String.hashCode` is specified by
     * the language, so the value is the same on every target and across launches.
     */
    fun fingerprint(): String {
        val material = listOf(
            title, year.orEmpty(), directors.joinToString(","), cast.joinToString(","),
            genres.joinToString(","), studios.joinToString(","), collection.orEmpty(),
            awards.orEmpty(), synopsis?.length?.toString().orEmpty(),
        ).joinToString("\u0000")
        return material.hashCode().toUInt().toString(16)
    }
}

private const val MAX_CAST = 8
private const val MAX_KEYWORDS = 12
private const val MAX_SYNOPSIS_CHARS = 700

fun MetaDetails.toTriviaSource(): TriviaSource = TriviaSource(
    title = name.trim(),
    year = releaseInfo?.trim()?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) },
    synopsis = description?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_SYNOPSIS_CHARS),
    directors = director.map(String::trim).filter(String::isNotEmpty),
    cast = cast.map { it.name.trim() }.filter(String::isNotEmpty).take(MAX_CAST),
    genres = genres.map(String::trim).filter(String::isNotEmpty),
    studios = productionCompanies.map { it.name.trim() }.filter(String::isNotEmpty).take(4),
    collection = collectionName?.trim()?.takeIf { it.isNotEmpty() },
    awards = awards?.trim()?.takeIf { it.isNotEmpty() },
    keywords = tmdbKeywords.map(String::trim).filter(String::isNotEmpty).take(MAX_KEYWORDS),
    runtime = runtime?.trim()?.takeIf { it.isNotEmpty() },
    country = country?.trim()?.takeIf { it.isNotEmpty() },
    language = language?.trim()?.takeIf { it.isNotEmpty() },
)
