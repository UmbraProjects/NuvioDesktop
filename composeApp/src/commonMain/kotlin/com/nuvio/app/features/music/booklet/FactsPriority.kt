package com.nuvio.app.features.music.booklet
// FORK UI (Spyder): Nick's album page facts, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

/*
 * fork:facts-filler - the album page goes before the background filler. Every facts loader runs its work through
 * [FactsPriority.foreground]; a call made from the filler carries [FillerWork] and is let through as it is, any other call
 * (the album page, the Stage, Cut to fit, Start here) counts as the person waiting. While anyone waits, the filler cuts its
 * own lookup short (freeing the loader's lock at once) and holds off until the page has been quiet for a moment.
 */

/** Marks the filler's own coroutine, so its calls into the loaders are not counted as the person waiting. */
internal class FillerWork : AbstractCoroutineContextElement(FillerWork) {
    companion object Key : CoroutineContext.Key<FillerWork>
}

internal object FactsPriority {
    private val _waiting = MutableStateFlow(0)

    /** How many lookups the person is waiting on right now. */
    val waiting: StateFlow<Int> = _waiting.asStateFlow()

    @kotlin.concurrent.Volatile
    private var lastEndMs = 0L

    /** When the person's last lookup ended (epoch ms): the filler stays back a little after it, as pages ask in bursts. */
    val lastForegroundEndMs: Long get() = lastEndMs

    /** Runs [block]; counted as the person waiting unless it is the filler's own call. */
    suspend fun <T> foreground(block: suspend () -> T): T {
        if (currentCoroutineContext()[FillerWork] != null) return block()
        _waiting.update { it + 1 }
        try {
            return block()
        } finally {
            _waiting.update { it - 1 }
            lastEndMs = Clock.System.now().toEpochMilliseconds()
        }
    }
}

/** How long the filler stays back after the person's last lookup ended. */
internal const val FILL_YIELD_QUIET_MS = 5_000L
