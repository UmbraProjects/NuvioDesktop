package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's album page lenses, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import com.nuvio.app.features.music.booklet.SONG_PART_AFTER
import com.nuvio.app.features.music.booklet.SONG_PART_MADE
import com.nuvio.app.features.music.booklet.SONG_PART_MEANING
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
internal data class LensStoryText(val text: String, val usedMeaning: Boolean, val part: String = SONG_PART_MADE)

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
    val made = story.made?.takeIf { it.isNotBlank() }
    val meaning = story.meaning?.takeIf { it.isNotBlank() }
    val after = story.after?.takeIf { it.isNotBlank() }
    return when {
        // The person's own made part stands alone, word for word.
        made != null && SONG_PART_MADE in story.pinned -> LensStoryText(made, usedMeaning = false)
        // A meaning the person wrote is shown whole in the small card, so it is never run together with the made part here.
        made != null && meaning != null && SONG_PART_MEANING in story.pinned ->
            lensStoryText(made, null)?.copy(usedMeaning = false)
        made != null -> lensStoryText(made, meaning)
        meaning != null -> LensStoryText(if (SONG_PART_MEANING in story.pinned) meaning else wholeSentences(meaning), usedMeaning = true, part = SONG_PART_MEANING)
        after != null -> LensStoryText(if (SONG_PART_AFTER in story.pinned) after else wholeSentences(after), usedMeaning = false, part = SONG_PART_AFTER)
        else -> null
    }
}

/** What the small card under the story box says: the article's "what came after" or "what it means", or the album's note on the song. */
internal enum class LensCardKind { After, Meaning, Note }

/** The small card's [text], what it is ([kind]), the story part an Edit would write ([part]) and whether the person wrote it. */
internal data class LensCard(val kind: LensCardKind, val text: String, val part: String, val pinned: Boolean)

/**
 * The small card for [story] when the box showed [told]: a part the person pinned (the later part first), shown word for
 * word; else the article's "after" (when there is a made or meaning part above it), else the meaning when the box left it
 * out, else the album's [noteText] on the song when the box has the article. Null when none of those apply. A card that
 * is not a story part (the note) writes the "after" part when edited.
 */
internal fun lensCard(story: SongStory?, told: LensStoryText?, noteText: String?, boxHasStory: Boolean): LensCard? {
    if (story != null) {
        story.after?.takeIf { SONG_PART_AFTER in story.pinned && it.isNotBlank() && told?.part != SONG_PART_AFTER }
            ?.let { return LensCard(LensCardKind.After, it, SONG_PART_AFTER, pinned = true) }
        story.meaning?.takeIf { SONG_PART_MEANING in story.pinned && it.isNotBlank() && told?.part != SONG_PART_MEANING }
            ?.let { return LensCard(LensCardKind.Meaning, it, SONG_PART_MEANING, pinned = true) }
        story.after?.takeIf { story.made != null || story.meaning != null }?.let(::wholeSentences)?.takeIf { it.isNotBlank() }
            ?.let { return LensCard(LensCardKind.After, it, SONG_PART_AFTER, pinned = false) }
        story.meaning?.takeIf { told?.usedMeaning == false }?.let(::wholeSentences)?.takeIf { it.isNotBlank() }
            ?.let { return LensCard(LensCardKind.Meaning, it, SONG_PART_MEANING, pinned = false) }
    }
    return noteText?.takeIf { boxHasStory && it.isNotBlank() }?.let { LensCard(LensCardKind.Note, it, SONG_PART_AFTER, pinned = false) }
}

/** True when [draft] is a change worth pinning: not blank-for-blank and different from what the box showed, ignoring edge space. */
internal fun isPinChange(shown: String?, draft: String): Boolean = draft.trim() != shown.orEmpty().trim()
