package com.nuvio.app.features.music

import kotlin.test.Test
import kotlin.test.assertEquals

class NeteaseEapiTest {
    @Test
    fun md5IsLowerCaseHex() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", NeteaseCrypto.md5Hex(""))
    }

    /** The expected text was made with an independent AES/MD5 implementation (Python), not with this code. */
    @Test
    fun theRequestBodyMatchesAnIndependentEncryption() {
        val body = neteaseEapiBody("/api/song/lyric/v1", """{"id":"1","cp":"false"}""")
        assertEquals(
            "params=04AE33D34A93FE3EC22DA8FA305D290AB337D0FE5F36D211DE0D338CC6AA89D06C6DAEF5B7F1F8A34E36D7AEF3A75875" +
                "777F148571E5FA0A103B19EDF3A353A90ED9402DE2DB769D0E0F75558065F9C44C2BE9254E89BF3AEB1AD04DA07D88872B7" +
                "2603A906EEF0843341A2034FEEA5E",
            body,
        )
    }
}
