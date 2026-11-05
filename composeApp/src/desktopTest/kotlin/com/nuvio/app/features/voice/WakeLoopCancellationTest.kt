package com.nuvio.app.features.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shape of the wake-word supervisor (VoiceControlRepository.superviseWakeWord): collectLatest
 * over a state, the block a loop of blocking 20 ms reads. collectLatest starts the block
 * undispatched, so without a suspension point in the loop the collector never sees the state change
 * that should stop it, and the microphone is never handed to push-to-talk.
 */
class WakeLoopCancellationTest {

    private fun stopsWhenWanted(suspendEachRead: Boolean): Boolean = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val wanted = MutableStateFlow(true)
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        scope.launch {
            wanted.collectLatest { on ->
                if (!on) return@collectLatest
                coroutineScope {
                    try {
                        started.complete(Unit)
                        while (isActive) {
                            if (suspendEachRead) yield()
                            Thread.sleep(20) // a blocking microphone read
                        }
                    } finally {
                        stopped.complete(Unit)
                    }
                }
            }
        }
        started.await()
        wanted.value = false
        val result = withTimeoutOrNull(1_000) { stopped.await() } != null
        scope.cancel()
        result
    }

    @Test
    fun aLoopThatNeverSuspendsIsNeverStopped() {
        assertFalse(stopsWhenWanted(suspendEachRead = false))
    }

    @Test
    fun yieldingEachReadLetsItStop() {
        assertTrue(stopsWhenWanted(suspendEachRead = true))
    }
}
