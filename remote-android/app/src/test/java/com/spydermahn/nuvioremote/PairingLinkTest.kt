package com.spydermahn.nuvioremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingLinkTest {
    @Test
    fun readsThePcsQrLink() {
        // The exact link PhoneRemotePairingLinkTest on the PC side produces.
        assertEquals(
            PairingLink("NIMILY Lounge & Den", 47321, "042917", listOf("192.168.1.20", "10.0.0.5")),
            PairingLink.parse("nuvioremote://pair?n=NIMILY%20Lounge%20%26%20Den&p=47321&c=042917&a=192.168.1.20,10.0.0.5"),
        )
    }

    @Test
    fun turnsAwayAnythingElse() {
        assertNull(PairingLink.parse("https://example.com/pair?p=1&c=123456&a=1.2.3.4"))
        assertNull(PairingLink.parse("nuvioremote://pair?n=X&p=47321&c=12345&a=192.168.1.20"))
        assertNull(PairingLink.parse("nuvioremote://pair?n=X&p=47321&c=123456"))
        assertNull(PairingLink.parse(null))
    }
}
