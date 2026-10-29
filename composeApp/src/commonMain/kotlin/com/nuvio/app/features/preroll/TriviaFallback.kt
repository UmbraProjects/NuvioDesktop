package com.nuvio.app.features.preroll

import com.nuvio.app.features.details.MetaDetails

private const val MAX_FALLBACK_SLIDES = 6

/**
 * Slides built from the film's own metadata, with no network and no model.
 *
 * The AI pack is better when it arrives, but it can take longer than the pre-show will wait. This
 * is what plays instead: only facts the details record already holds, so nothing can be invented
 * and it is ready the instant the gate asks. The quiz is short and built by [buildMetadataQuestions]
 * from the same record, with plausible wrong answers that are never true. English only for now.
 */
fun buildMetadataTrivia(meta: MetaDetails): TriviaPack? {
    val source = meta.toTriviaSource()
    if (!source.isUsable) return null

    val slides = buildList {
        source.directors.takeIf { it.isNotEmpty() }?.let {
            add(TriviaSlide("Directed by ${joinNames(it.take(3))}.", TriviaCategory.Production))
        }
        source.cast.takeIf { it.size >= 2 }?.let {
            add(TriviaSlide("Starring ${joinNames(it.take(4))}.", TriviaCategory.Cast))
        }
        source.studios.takeIf { it.isNotEmpty() }?.let {
            add(TriviaSlide("Produced by ${joinNames(it.take(3))}.", TriviaCategory.Production))
        }
        source.awards?.trim()?.takeIf { it.isNotEmpty() }?.let {
            add(TriviaSlide(it.trimEnd('.') + ".", TriviaCategory.Awards))
        }
        meta.imdbRating?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let {
            add(TriviaSlide("Rated $it out of 10 by IMDb users.", TriviaCategory.Legacy))
        }
        source.collection?.takeIf { it.isNotBlank() }?.let {
            add(TriviaSlide("Part of the $it.", TriviaCategory.Legacy))
        }
        source.runtime?.takeIf { it.isNotBlank() }?.let {
            add(TriviaSlide("Running time: $it.", TriviaCategory.Trivia))
        }
        listOfNotNull(source.country?.takeIf { it.isNotBlank() }, source.language?.takeIf { it.isNotBlank() })
            .takeIf { it.isNotEmpty() }
            ?.let { add(TriviaSlide("Made in ${it.joinToString(", ")}.", TriviaCategory.Trivia)) }
    }.take(MAX_FALLBACK_SLIDES)

    if (slides.size < 2) return null
    return TriviaPack(
        slides = slides,
        questions = buildMetadataQuestions(source),
        origin = TriviaOrigin.Metadata,
    ).withFallbackTheme(source.genres)
}

private fun joinNames(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
}
