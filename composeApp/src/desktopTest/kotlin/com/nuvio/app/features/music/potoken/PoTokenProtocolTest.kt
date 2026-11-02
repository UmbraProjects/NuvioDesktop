package com.nuvio.app.features.music.potoken

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PoTokenProtocolTest {
    private val plain =
        """["msg-1",[null,null,"var trayride={};"],[null,null,"https://x/y.js"],"hash","PROGRAM","trayride",null,"blob"]"""

    /** The service's scrambling: base64 of each byte minus 97. */
    private fun scramble(text: String): String =
        Base64.getEncoder().encodeToString(ByteArray(text.length) { (text[it].code - 97).toByte() })
            .replace('+', '-').replace('/', '_')

    @Test
    fun `reads a plain challenge`() {
        val challenge = PoTokenProtocol.parseChallenge("[$plain]")
        assertEquals("msg-1", challenge.messageId)
        assertEquals("var trayride={};", challenge.script)
        assertEquals("PROGRAM", challenge.program)
        assertEquals("trayride", challenge.globalName)
    }

    @Test
    fun `reads a scrambled challenge`() {
        val challenge = PoTokenProtocol.parseChallenge("""[null,"${scramble(plain)}"]""")
        assertEquals("var trayride={};", challenge.script)
        assertEquals("trayride", challenge.globalName)
    }

    @Test
    fun `a script sent only as a url is reported, not mistaken for no script`() {
        val urlOnly = """["m",null,[null,null,"https://x/y.js"],"h","P","g",null,"b"]"""
        val error = assertFailsWith<PoTokenException> { PoTokenProtocol.parseChallenge("[$urlOnly]") }
        assertTrue("URL" in error.message.orEmpty())
    }

    @Test
    fun `a challenge without a program is rejected`() {
        val noProgram = """["m",[null,null,"s"],null,"h",null,"g",null,"b"]"""
        assertFailsWith<PoTokenException> { PoTokenProtocol.parseChallenge("[$noProgram]") }
    }

    @Test
    fun `garbage is a protocol error, not a crash`() {
        assertFailsWith<PoTokenException> { PoTokenProtocol.parseChallenge("<html>nope</html>") }
        assertFailsWith<PoTokenException> { PoTokenProtocol.parseChallenge("[]") }
    }

    @Test
    fun `reads an integrity token and its lifetime`() {
        val bytes = byteArrayOf(1, 2, 3, -1, -2, 127)
        val token = PoTokenProtocol.parseIntegrityToken("""["${PoTokenProtocol.encodeWebSafe(bytes)}",43200,100,"fallback"]""")
        assertContentEquals(bytes, token.bytes)
        assertEquals(43200L, token.ttlSeconds)
    }

    @Test
    fun `a null integrity token means the browser was not trusted`() {
        val error = assertFailsWith<PoTokenException> {
            PoTokenProtocol.parseIntegrityToken("""[null,43200,null,"fallback-only"]""")
        }
        assertTrue("refused" in error.message.orEmpty())
    }

    @Test
    fun `web-safe base64 round-trips and accepts dot padding`() {
        val bytes = ByteArray(40) { (it * 7 - 90).toByte() }
        val encoded = PoTokenProtocol.encodeWebSafe(bytes)
        assertTrue('+' !in encoded && '/' !in encoded)
        assertContentEquals(bytes, PoTokenProtocol.decodeWebSafe(encoded))
        assertContentEquals(byteArrayOf(104, 105), PoTokenProtocol.decodeWebSafe("aGk."))
        assertFailsWith<PoTokenException> { PoTokenProtocol.decodeWebSafe("!!!") }
    }

    @Test
    fun `request bodies carry the request key`() {
        assertEquals("""["O43z0dpjhgX20SCx4KAo"]""", PoTokenProtocol.createBody())
        assertEquals("""["O43z0dpjhgX20SCx4KAo","resp"]""", PoTokenProtocol.generateItBody("resp"))
    }
}
