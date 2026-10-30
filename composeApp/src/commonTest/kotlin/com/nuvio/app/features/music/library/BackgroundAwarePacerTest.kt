package com.nuvio.app.features.music.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

class BackgroundAwarePacerTest {
    @Test
    fun aBackgroundCallWaitsForAnOrdinaryCallInFlight() = runBlocking<Unit> {
        val pacer = BackgroundAwarePacer(gapMs = 0, sleep = {})
        val log = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        val ordinary = launch { pacer.run(background = false) { log += "ordinary start"; release.await(); log += "ordinary end" } }
        yield()
        val background = launch { pacer.run(background = true) { log += "background" } }
        yield()
        yield()
        assertEquals(listOf("ordinary start"), log)
        release.complete(Unit)
        joinAll(ordinary, background)
        assertEquals(listOf("ordinary start", "ordinary end", "background"), log)
    }

    @Test
    fun aBackgroundCallGivesWayToAnOrdinaryCallThatArrivesWhileItWaitsForTheGap() = runBlocking<Unit> {
        val clock = 1_000L
        val gapOver = CompletableDeferred<Unit>()
        val pacer = BackgroundAwarePacer(gapMs = 100, now = { clock }, sleep = { gapOver.await() })
        val log = mutableListOf<String>()
        pacer.run(background = false) { log += "first" }
        // The gap has not passed (the clock stands still), so the next call has to wait for it.
        val background = launch { pacer.run(background = true) { log += "background" } }
        yield()
        val ordinary = launch { pacer.run(background = false) { log += "ordinary" } }
        yield()
        gapOver.complete(Unit)
        joinAll(background, ordinary)
        assertEquals(listOf("first", "ordinary", "background"), log)
    }

    @Test
    fun callsAreSpacedByTheGap() = runBlocking<Unit> {
        var clock = 10_000L
        val sleeps = mutableListOf<Long>()
        val pacer = BackgroundAwarePacer(gapMs = 1_100, now = { clock }, sleep = { sleeps += it; clock += it })
        pacer.run(background = false) { }
        pacer.run(background = true) { }
        pacer.run(background = false) { }
        assertEquals(listOf(1_100L, 1_100L), sleeps)
    }

    @Test
    fun theResultOfTheCallComesBack() = runBlocking<Unit> {
        val pacer = BackgroundAwarePacer(gapMs = 0, sleep = {})
        assertEquals("a", pacer.run(background = false) { "a" })
        assertEquals("b", pacer.run(background = true) { "b" })
    }
}
