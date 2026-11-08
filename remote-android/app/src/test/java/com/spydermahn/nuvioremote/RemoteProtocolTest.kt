package com.spydermahn.nuvioremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteProtocolTest {
    @Test
    fun readsADiscoveryReply() {
        assertEquals(
            FoundPc(name = "Living Room PC", host = "192.168.1.20", port = 47321),
            Discovery.parseReply("NUVIO_REMOTE 1 47321 Living%20Room%20PC", "192.168.1.20"),
        )
    }

    @Test
    fun ignoresAnythingElse() {
        assertNull(Discovery.parseReply("HELLO there", "192.168.1.20"))
        assertNull(Discovery.parseReply("NUVIO_REMOTE 1 notaport Name", "192.168.1.20"))
    }

    @Test
    fun textSurvivesTheRoundTrip() {
        val text = "Amélie & the 3+1 crew\n"
        assertEquals(text, RemoteProtocol.decode(RemoteProtocol.encode(text)))
        assertEquals(false, RemoteProtocol.encode(text).contains(' '))
    }
}
