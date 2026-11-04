package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's album page lenses, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import com.nuvio.app.features.music.booklet.SONG_PART_MADE
import com.nuvio.app.features.music.booklet.SongStory

/*
 * fork:lenses - the Song by song story box's text. A story kept from Wikipedia is cut to a few hundred characters, and the
 * cut can fall mid-sentence (even mid-quote) with an ellipsis. The box has room for more, so it is given whole sentences
 * only, from how the song was made and then what it means, up to [LENS_STORY_MAX]. Pure, so it is tested without Compose.
 */

/** How much of the story the box is given: about what fits the box on a 1080p window. */
internal const val LENS_STORY_MAX = 900

private val SENTENCE_END = Regex("""[.!?]["”’)]*(?=\s|$)""")

/**
 * [text] ended on a whole sentence: a trailing "…" cut is taken back to the last full stop, and a quote left open by the
 * cut is taken back to before the sentence it opens in. Text with no full stop to go back to is left as it is.
 */
internal fun wholeSentences(text: String): String {
    var t = text.replace(Regex("""\s+"""), " ").trim()
    if (t.endsWith("…") || t.endsWith("...")) {
        val cut = t.removeSuffix("…").removeSuffix("...")
        val end = SENTENCE_END.findAll(cut).lastOrNull()?.range?.last ?: return t
        t = cut.substring(0, end + 1).trim()
    }
    // A quote opened but not closed: go back to the sentence end before it.
    val opens = openQuoteAt(t)
    if (opens >= 0) {
        val end = SENTENCE_END.findAll(t.substring(0, opens)).lastOrNull()?.range?.last
        if (end != null) t = t.substring(0, end + 1).trim()
    }
    return t
}

/** Where the quote left open in [t] starts, or -1 when every quote is closed. */
private fun openQuoteAt(t: String): Int {
    var straightOpen = -1
    var curlyOpen = -1
    t.forEachIndexed { i, c ->
        when (c) {
            '"' -> straightOpen = if (straightOpen < 0) i else -1
            '“' -> curlyOpen = i
            '”' -> curlyOpen = -1
        }
    }
    return maxOf(straightOpen, curlyOpen)
}

/**
 * The box's text from a story's [made] and [meaning] parts: whole sentences of each, [meaning] added only when both fit in
 * [max]. [usedMeaning] says the meaning is in the box, so the small card below shows something else.
 */
internal data class LensStoryText(val text: String, val usedMeaning: Boolean)

internal fun lensStoryText(made: String?, meaning: String?, max: Int = LENS_STORY_MAX): LensStoryText? {
    val first = made?.let(::wholeSentences)?.takeIf { it.isNotBlank() }
    val second = meaning?.let(::wholeSentences)?.takeIf { it.isNotBlank() }
    return when {
        first == null && second == null -> null
        first == null -> LensStoryText(second!!, usedMeaning = true)
        second != null && first.length + 1 + second.length <= max -> LensStoryText("$first\n\n$second", usedMeaning = true)
        else -> LensStoryText(first, usedMeaning = false)
    }
}

/**
 * What the story box shows for [story]: the person's own text word for word when they pinned the "made" part (it stands
 * alone, so the meaning moves down to the small card and nothing of theirs is cut or run together), else whole sentences of
 * how it was made and what it means, else the article's last part. Null when there is nothing to show.
 */
internal fun lensStoryBox(story: SongStory?): LensStoryText? {
    if (story == null) return null
    if (SONG_PART_MADE in story.pinned) {
        story.made?.takeIf { it.isNotBlank() }?.let { return LensStoryText(it, usedMeaning = false) }
    }
    return lensStoryText(story.made, story.meaning)
        ?: story.after?.let { LensStoryText(wholeSentences(it), usedMeaning = false) }
}

/** True when [draft] is a change worth pinning: not blank-for-blank and different from what the box showed, ignoring edge space. */
internal fun isPinChange(shown: String?, draft: String): Boolean = draft.trim() != shown.orEmpty().trim()
