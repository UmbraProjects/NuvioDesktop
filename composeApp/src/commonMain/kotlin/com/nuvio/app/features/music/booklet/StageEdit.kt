package com.nuvio.app.features.music.booklet
// FORK UI (Spyder): Nick's album page facts, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

/*
 * fork:pins - what the Stage's Edit dialog offers for the page of the Liner Notes deck that is showing. The dialog is a
 * window over the Stage, so nothing on the Stage moves or resizes while the person types; this file only decides which
 * pins a page can write, and with what text to start. It is free of the screen so it can be tested.
 */

/**
 * One thing a page lets the person write: a pin at [pinKey] (heading [heading]) whose text starts as [text]. A trivia card
 * also has a [question] and a [kind]; for the rest [question] is null. [pinned] is true when a pin is already there, so
 * Reset is offered.
 */
internal data class StageEditGroup(
    val heading: String,
    val pinKey: String,
    val pinned: Boolean,
    val text: String,
    val question: String? = null,
    val kind: FactKind? = null,
)

/** The groups [page] can be edited as; empty for a page with nothing to edit (guests, the timeline). */
internal fun stageEditGroups(page: LinerPage, booklet: AlbumBooklet, request: BookletRequest): List<StageEditGroup> = when (page) {
    LinerPage.Cover -> listOf(
        StageEditGroup("Introduction", PinKeys.intro(request.artist, request.album), "intro" in booklet.pinned, booklet.intro),
    )
    is LinerPage.Facts -> page.facts.mapIndexedNotNull { i, fact ->
        // A card is pinned by its place in the deck; a card past the fifth has no place to be kept.
        val slot = page.startIndex + i + 1
        if (slot > MAX_FACT_SLOTS) return@mapIndexedNotNull null
        StageEditGroup(
            heading = "Card $slot · ${if (fact.kind == FactKind.Artist) "Artist" else "Album"}",
            pinKey = PinKeys.fact(request.artist, request.album, slot),
            pinned = "fact:$slot" in booklet.pinned,
            text = fact.answer,
            question = fact.question,
            kind = fact.kind,
        )
    }
    is LinerPage.Track -> {
        val story = page.story
        if (story == null) {
            if (page.note.isBlank()) emptyList() else listOf(
                StageEditGroup("Note on this song", PinKeys.trackNote(request.artist, request.album, page.number), "tnote:${page.number}" in booklet.pinned, page.note),
            )
        } else listOfNotNull(
            story.made?.let { storyGroup("How it was made", request, page.title, SONG_PART_MADE, it, story) },
            story.meaning?.let { storyGroup("What it means", request, page.title, SONG_PART_MEANING, it, story) },
            story.after?.let { storyGroup("Sampled, covered, remembered", request, page.title, SONG_PART_AFTER, it, story) },
        )
    }
    is LinerPage.Guests, is LinerPage.Timeline -> emptyList()
}

private fun storyGroup(heading: String, request: BookletRequest, song: String, part: String, text: String, story: SongStory) =
    StageEditGroup(heading, PinKeys.songPart(request.artist, song, part), part in story.pinned, text)

/** What saving [group] with the typed [question] and [answer] should do: null for no change, else the pin to write. */
internal data class StageEditSave(val pinKey: String, val text: String, val question: String?, val kind: FactKind?)

internal fun stageEditSave(group: StageEditGroup, question: String, answer: String): StageEditSave? {
    val changed = answer.trim() != group.text.trim() || (group.question != null && question.trim() != group.question.trim())
    return if (changed) StageEditSave(group.pinKey, answer, group.question?.let { question }, group.kind) else null
}
