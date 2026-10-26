package com.nuvio.app.features.iptv

import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class IptvRelayRetryTest {
    @Test
    fun `a connect that fails once is retried and reports the rescue`() {
        var calls = 0
        var rescued: Pair<Int, IOException>? = null
        val malformed = SocketException("Malformed reply from SOCKS server")
        val result = retryRelayConnect(delayMs = 0, onRescued = { attempt, first -> rescued = attempt to first }) {
            calls++
            if (calls == 1) throw malformed
            "socket"
        }
        assertEquals("socket", result)
        assertEquals(2, calls)
        assertEquals(1, rescued?.first)
        assertSame(malformed, rescued?.second)
    }

    @Test
    fun `gives up after the retries with the last failure`() {
        var calls = 0
        val error = assertFailsWith<SocketException> {
            retryRelayConnect<Unit>(delayMs = 0) {
                calls++
                throw SocketException("reset $calls")
            }
        }
        assertEquals(1 + RELAY_CONNECT_RETRIES, calls)
        assertEquals("reset ${1 + RELAY_CONNECT_RETRIES}", error.message)
    }

    @Test
    fun `timeouts and refused credentials are not retried`() {
        var calls = 0
        assertFailsWith<SocketTimeoutException> {
            retryRelayConnect<Unit>(delayMs = 0) { calls++; throw SocketTimeoutException("connect timed out") }
        }
        assertFailsWith<IOException> {
            retryRelayConnect<Unit>(delayMs = 0) { calls++; throw IOException("proxy refused CONNECT: 407") }
        }
        assertEquals(2, calls)
    }

    @Test
    fun `a first-time success reports nothing`() {
        var rescued = false
        assertEquals(1, retryRelayConnect(delayMs = 0, onRescued = { _, _ -> rescued = true }) { 1 })
        assertEquals(false, rescued)
    }
}
