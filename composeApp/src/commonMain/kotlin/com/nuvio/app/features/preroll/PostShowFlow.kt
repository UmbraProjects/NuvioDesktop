package com.nuvio.app.features.preroll

import com.nuvio.app.features.player.skip.SkipInterval

/** What the playback clock asks the post-show to do. */
enum class PostShowSignal { Prefetch, Start, Stop }

/**
 * Turns the player's position ticks into [PostShowSignal]s.
 *
 * Pure and stateful per playback: one instance is reset when a new playback attempt begins. It
 * fires [PostShowSignal.Prefetch] once past the halfway point, [PostShowSignal.Start] once when
 * playback *plays into* the credits window (a seek or a resume into it never starts the cards),
 * and [PostShowSignal.Stop] when the cards are up and the position leaves the window or jumps forward. After a
 * start the post-show is spent for this playback, so seeking back into the credits does not
 * bring it round again.
 */
class PostShowTracker {
    private var previousMs = -1L
    private var prefetched = false
    private var started = false

    fun reset() {
        previousMs = -1L
        prefetched = false
        started = false
    }

    fun onTick(
        positionMs: Long,
        durationMs: Long,
        intervals: List<SkipInterval>,
        showing: Boolean,
    ): List<PostShowSignal> {
        val previous = previousMs
        previousMs = positionMs
        val out = mutableListOf<PostShowSignal>()
        if (!prefetched && shouldPrefetchPostShow(positionMs, durationMs)) {
            prefetched = true
            out += PostShowSignal.Prefetch
        }
        val window = postShowWindow(intervals, durationMs) ?: return out
        if (showing) {
            // Leaving the window, or a forward jump inside it (skip credits, a scrub): not playback, so the cards go.
            val jumped = previous >= 0L && positionMs - previous > MAX_PLAYING_STEP_MS
            if (positionMs !in window || jumped) out += PostShowSignal.Stop
        } else if (!started && previous >= 0L && postShowArrivedByPlaying(previous, positionMs, window)) {
            started = true
            out += PostShowSignal.Start
        }
        return out
    }
}

/** Remote/keyboard presses the post-show understands, whichever surface saw them. */
enum class PostShowKey {
    Left, Right, Up, Down, Select, Back, Option1, Option2, Option3, Option4;

    companion object {
        /** The numbers the controls page sends. Unknown numbers are ignored. */
        fun fromCode(code: Int): PostShowKey? = when (code) {
            1 -> Left
            2 -> Right
            3 -> Up
            4 -> Down
            5 -> Select
            6 -> Back
            11 -> Option1
            12 -> Option2
            13 -> Option3
            14 -> Option4
            else -> null
        }
    }
}

/** Where the cards are, the answer under the cursor, and whether a card is being held. */
data class PostShowView(
    val stage: PreShowStage,
    val cursor: Int = 0,
    val held: Boolean = false,
)

/**
 * One key press against the cards, mirroring the pre-show's keys: Select / Right advance (or pick
 * the highlighted answer), Left goes back, Up / Down move the answer cursor or hold a card, 1-4
 * answer directly. [PostShowKey.Back] is the caller's (it closes everything). A change of stage
 * clears the cursor and the hold, as it does in the pre-show.
 */
fun PostShowView.press(
    key: PostShowKey,
    cardCount: Int,
    questionCount: Int,
    optionCount: Int,
): PostShowView {
    val unanswered = stage is PreShowStage.Question && stage.picked == null
    val next: PreShowStage = when (key) {
        PostShowKey.Back -> stage
        PostShowKey.Left -> stage.back(cardCount)
        PostShowKey.Right -> stage.advance(cardCount, questionCount)
        PostShowKey.Select ->
            if (unanswered) stage.answer(cursor) else stage.advance(cardCount, questionCount)
        PostShowKey.Up, PostShowKey.Down -> stage
        PostShowKey.Option1 -> if (optionCount > 0) stage.answer(0) else stage
        PostShowKey.Option2 -> if (optionCount > 1) stage.answer(1) else stage
        PostShowKey.Option3 -> if (optionCount > 2) stage.answer(2) else stage
        PostShowKey.Option4 -> if (optionCount > 3) stage.answer(3) else stage
    }
    if (next != stage) return PostShowView(next)
    return when (key) {
        PostShowKey.Up, PostShowKey.Down -> when {
            unanswered -> copy(cursor = moveAnswerCursor(cursor, if (key == PostShowKey.Up) -1 else 1, optionCount))
            stage is PreShowStage.Slide -> copy(held = !held)
            else -> this
        }
        else -> this
    }
}
