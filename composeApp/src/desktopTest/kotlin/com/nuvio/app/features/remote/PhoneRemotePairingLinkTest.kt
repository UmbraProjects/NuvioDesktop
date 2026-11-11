package com.nuvio.app.features.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneRemotePairingLinkTest {
    @Test
    fun theQrHoldsTheNamePortCodeAndAddresses() {
        val state = PhoneRemoteState(
            enabled = true, listening = true, port = 47321, pin = "042917",
            addresses = listOf("192.168.1.20", "10.0.0.5"), pcName = "NIMILY Lounge & Den",
        )
        assertEquals(
            "nuvioremote://pair?n=NIMILY%20Lounge%20%26%20Den&p=47321&c=042917&a=192.168.1.20,10.0.0.5",
            state.pairingLink(),
        )
    }

    @Test
    fun nothingToScanUntilItIsListening() {
        assertNull(PhoneRemoteState(enabled = true, listening = false, port = 47321, pin = "042917", addresses = listOf("192.168.1.20")).pairingLink())
        assertNull(PhoneRemoteState(enabled = true, listening = true, port = 47321, pin = "042917").pairingLink())
    }
}
