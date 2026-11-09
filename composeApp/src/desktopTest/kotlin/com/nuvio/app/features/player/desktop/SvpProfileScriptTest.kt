package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SvpProfileScriptTest {
    private val script: String =
        checkNotNull(javaClass.getResourceAsStream("/player-scripts/svp_main.vpy")) { "svp_main.vpy" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun shippedScriptDefaultsToTheAnimeProfile() {
        assertTrue(script.lines().any { it.trimEnd() == "SVP_PROFILE = \"anime\"" })
    }

    @Test
    fun cinemaExportSwitchesExactlyTheProfileLine() {
        val cinema = assertNotNull(withSvpProfile(script, "cinema"))
        val before = script.lines()
        val after = cinema.lines()
        assertEquals(before.size, after.size)
        val changed = before.indices.filter { before[it] != after[it] }
        assertEquals(1, changed.size)
        assertEquals("SVP_PROFILE = \"cinema\"", after[changed.single()].trimEnd())
    }

    @Test
    fun crlfScriptStillSwitches() {
        val crlf = script.replace("\r\n", "\n").replace("\n", "\r\n")
        val cinema = assertNotNull(withSvpProfile(crlf, "cinema"))
        assertTrue(cinema.contains("SVP_PROFILE = \"cinema\"\r\n"))
    }

    @Test
    fun scriptWithoutTheLineIsRejected() {
        assertNull(withSvpProfile("print('no profile')\n", "cinema"))
    }
}
