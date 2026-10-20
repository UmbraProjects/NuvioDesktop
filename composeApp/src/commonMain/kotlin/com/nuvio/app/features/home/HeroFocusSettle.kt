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
 * [target], except while it is changing fast: then the last settled value, until [target] has
 * stayed put for [settleMs].
 *
 * The immersive hero (Home TV mode, Discover, Search, Library) follows focus, and every title it
 * is pointed at starts a metadata lookup, TMDB requests, a trailer gate and a backdrop decode. With
 * an arrow key held, focus crossed a title every ~30 ms and each of those started and was thrown
 * away (2026-10-19 Discover session: ~550 hero lookups, ~490 cancelled TMDB requests and ~150 cold
 * backdrop decodes in two minutes, and a natural full GC every 10-15 s). Holding the hero while
 * focus is moving fast leaves one lookup per title the user actually stops on.
 *
 * An isolated change (none in the previous [settleMs]) is returned on the same frame, so a single
 * press still updates the hero at once.
 */
@Composable
internal fun <T> rememberSettledHeroFocus(
    target: T,
    settleMs: Long = HERO_FOCUS_SETTLE_MS,
    nowMs: () -> Long = { System.currentTimeMillis() },
): T {
    val tracker = remember { SettleTracker() }
    var settled by remember { mutableStateOf(target) }
    val now = nowMs()
    val changed = target != tracker.lastTarget
    val fast = changed && now - tracker.lastChangeAtMs < settleMs
    SideEffect {
        if (target != tracker.lastTarget) {
            tracker.lastTarget = target
            tracker.lastChangeAtMs = now
        }
    }
    LaunchedEffect(target) {
        // Restarted (so the wait starts over) by every further change of target.
        if (fast) delay(settleMs)
        settled = target
    }
    return if (target == settled || (changed && !fast)) target else settled
}

private class SettleTracker {
    var lastTarget: Any? = Unset
    var lastChangeAtMs: Long = Long.MIN_VALUE / 2

    object Unset
}
