package com.nuvio.app.features.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** The focused card the hero follows, with where it sits. */
internal data class HeroFocusTarget(
    val sectionIndex: Int,
    val itemIndex: Int,
    val rowIndex: Int,
    val item: MetaPreview?,
)

/**
 * A change that comes within this long of the previous one is "focus moving fast": a held arrow
 * key repeats every ~30 ms, a deliberate press is several times slower.
 */
internal const val HERO_FOCUS_SETTLE_MS = 180L

/**
 * Consecutive changes faster than [HERO_SUSTAINED_SCROLL_WINDOW_MS] (for example, mouse-wheel
 * scrolling down rows every ~250-450 ms) engage a whole-hero hold after
 * [HERO_SUSTAINED_SCROLL_STREAK] consecutive steps, then settle once focus pauses for
 * [HERO_SUSTAINED_SCROLL_SETTLE_MS]. Single presses and quick double-taps stay instant (0 ms).
 */
internal const val HERO_SUSTAINED_SCROLL_WINDOW_MS = 480L
internal const val HERO_SUSTAINED_SCROLL_SETTLE_MS = 350L
internal const val HERO_SUSTAINED_SCROLL_STREAK = 2

/**
 * [target], except while it is changing fast: then the last settled value, until [target] has
 * stayed put for [settleMs] (or [sustainedSettleMs] during sustained scrolling).
 *
 * The immersive hero (Home TV mode, Discover, Search, Library) follows focus, and every title it
 * is pointed at starts a metadata lookup, TMDB requests, a trailer gate and a backdrop decode. With
 * an arrow key held, focus crossed a title every ~30 ms and each of those started and was thrown
 * away (2026-10-19 Discover session: ~550 hero lookups, ~490 cancelled TMDB requests and ~150 cold
 * backdrop decodes in two minutes, and a natural full GC every 10-15 s). Holding the whole hero
 * (backdrop, logo, metadata, cast, ambient wash and prefetchers) together while focus is moving
 * fast leaves one coherent lookup per title the user actually stops on, without ever desyncing the
 * backdrop from the title and cast.
 *
 * An isolated change (or a quick two-step press) is returned on the same frame, so regular browsing
 * still updates the hero at once.
 *
 * [hold] keeps the last settled value outright, for a target known to be provisional. Targets seen
 * while holding are not recorded as changes, so the one that ends the hold is judged against the
 * last real change and shows at once instead of being taken for fast movement.
 */
@Composable
internal fun <T> rememberSettledHeroFocus(
    target: T,
    settleMs: Long = HERO_FOCUS_SETTLE_MS,
    sustainedWindowMs: Long = HERO_SUSTAINED_SCROLL_WINDOW_MS,
    sustainedSettleMs: Long = HERO_SUSTAINED_SCROLL_SETTLE_MS,
    sustainedStreak: Int = HERO_SUSTAINED_SCROLL_STREAK,
    nowMs: () -> Long = { System.currentTimeMillis() },
    hold: Boolean = false,
): T {
    val tracker = remember { SettleTracker() }
    var settled by remember { mutableStateOf(target) }
    val now = nowMs()
    val changed = target != tracker.lastTarget
    val gap = now - tracker.lastChangeAtMs
    val fastRepeat = changed && gap < settleMs
    val nextStreak = if (!hold && changed) {
        if (gap < sustainedWindowMs) tracker.streak + 1 else 0
    } else {
        tracker.streak
    }
    val sustainedScroll = !hold && changed && nextStreak >= sustainedStreak
    val fast = fastRepeat || sustainedScroll
    val delayMs = if (fastRepeat) settleMs else sustainedSettleMs
    SideEffect {
        if (!hold && target != tracker.lastTarget) {
            tracker.lastTarget = target
            tracker.lastChangeAtMs = now
            tracker.streak = nextStreak
        }
    }
    LaunchedEffect(target, hold) {
        if (hold) return@LaunchedEffect
        // Restarted (so the wait starts over) by every further change of target.
        if (fast) delay(delayMs)
        settled = target
    }
    return when {
        hold -> settled
        target == settled || (changed && !fast) -> target
        else -> settled
    }
}

private class SettleTracker {
    var lastTarget: Any? = Unset
    var lastChangeAtMs: Long = Long.MIN_VALUE / 2
    var streak: Int = 0

    object Unset
}
