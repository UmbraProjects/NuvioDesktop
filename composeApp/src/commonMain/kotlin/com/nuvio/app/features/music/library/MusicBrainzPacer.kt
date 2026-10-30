package com.nuvio.app.features.music.library

import com.nuvio.app.features.library.LibraryClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Marks a coroutine's MusicBrainz calls as background work (a ladder built before anyone asked for it). Add it with
 * `withContext(BackgroundWork())`: every paced call made inside waits for ordinary calls (a search, a Library refresh)
 * to finish first, and gives way to one that arrives while it is waiting out the pacing gap.
 */
internal class BackgroundWork : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<BackgroundWork>
}

/**
 * One call at a time, at least [gapMs] apart (MusicBrainz asks for one request a second), with two priorities. An
 * ordinary call ([run] with `background = false`) takes the next turn. A background call starts only when no ordinary
 * call is waiting or running, and steps aside if one arrives while it waits for the gap, so a search never queues behind
 * a ladder that was built on spec. A background call can still be held up by one call already in flight: nothing is
 * interrupted once it has started.
 *
 * The clock and the sleep are injected so the rules are unit-tested without waiting.
 */
internal class BackgroundAwarePacer(
    private val gapMs: Long,
    private val now: () -> Long = { LibraryClock.nowEpochMs() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val gate = Mutex()
    private var lastCallMs = 0L
    private val ordinary = MutableStateFlow(0)

    private class Done<T>(val value: T)

    suspend fun <T> run(background: Boolean, call: suspend () -> T): T {
        if (!background) {
            ordinary.update { it + 1 }
            try {
                return checkNotNull(turn(yieldToOrdinary = false, call)).value
            } finally {
                ordinary.update { it - 1 }
            }
        }
        while (true) {
            ordinary.first { it == 0 }
            turn(yieldToOrdinary = true, call)?.let { return it.value }
        }
    }

    /** One go at the gate: waits out the gap, then makes the call. Null when a background caller gave way. */
    private suspend fun <T> turn(yieldToOrdinary: Boolean, call: suspend () -> T): Done<T>? = gate.withLock {
        val wait = lastCallMs + gapMs - now()
        if (wait > 0) sleep(wait)
        if (yieldToOrdinary && ordinary.value > 0) return@withLock null
        try {
            Done(call())
        } finally {
            lastCallMs = now()
        }
    }
}
