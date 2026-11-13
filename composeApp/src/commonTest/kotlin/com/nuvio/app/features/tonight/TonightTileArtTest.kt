package com.nuvio.app.features.tonight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TonightTileArtTest {
    private fun title(id: String, backdrop: Boolean = true, library: Boolean = false, added: Long? = null, runtime: Int? = 100) =
        TonightTitle(
            type = "movie", id = id, name = id, kind = TonightKind.Film,
            backdrop = if (backdrop) "https://img/$id.jpg" else null,
            runtimeMinutes = runtime,
            origins = if (library) setOf(TonightOrigin.Library) else setOf(TonightOrigin.Trending),
            addedAtEpochMs = added,
        )

    private fun art(pool: List<TonightTitle>) = tonightTileArt(pool, pool, TonightTaste.Empty, 7L, 200, 10_000_000L)

    @Test
    fun everyTileGetsADifferentTitleWhenThereAreEnough() {
        val pool = (1..20).map { title("t$it") }
        val result = art(pool)
        assertEquals(TonightMode.entries.toSet(), result.keys)
        assertEquals(result.size, result.values.map { it.key }.toSet().size)
    }

    @Test
    fun titlesWithoutABackdropAreNeverUsed() {
        val pool = (1..6).map { title("bare$it", backdrop = false) } + title("art")
        val result = art(pool)
        assertEquals(listOf("art"), result.values.map { it.id })
    }

    @Test
    fun thePileShowsSomethingFromThePile() {
        val pool = (1..10).map { title("t$it") } + title("waiting", library = true, added = 1_000L)
        assertEquals("waiting", art(pool)[TonightMode.ThePile]?.id)
    }

    @Test
    fun aDarkGreenBackdropStillGlowsBrightGreen() {
        val glow = glowColourOf(androidx.compose.ui.graphics.Color(0.05f, 0.22f, 0.08f))
        assertTrue(glow.green > 0.8f, "lifted to a bright green, got $glow")
        assertTrue(glow.green > glow.red && glow.green > glow.blue)
    }

    @Test
    fun theSameNightGivesTheSameArt() {
        val pool = (1..20).map { title("t$it") }
        assertEquals(art(pool), art(pool))
        assertTrue(art(emptyList()).isEmpty())
    }
}
