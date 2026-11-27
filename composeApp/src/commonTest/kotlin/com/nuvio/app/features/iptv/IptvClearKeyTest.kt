package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IptvClearKeyTest {

    @Test
    fun `recognizes clearkey scheme variants`() {
        assertTrue(IptvClearKeyParser.isClearKeyScheme("clearkey"))
        assertTrue(IptvClearKeyParser.isClearKeyScheme("org.w3.clearkey"))
        assertTrue(IptvClearKeyParser.isClearKeyScheme("e2719d58-a985-b3c9-781a-b030af78d30e"))
        assertFalse(IptvClearKeyParser.isClearKeyScheme("com.widevine.alpha"))
        assertFalse(IptvClearKeyParser.isClearKeyScheme(null))
    }

    @Test
    fun `parses hex kid-key pairs with or without hyphens`() {
        val keys = IptvClearKeyParser.parsePayload(
            "0771502e-ee1e-4de3-ba76-894175f3c5ea:13f23aaf5d66b038385bb2137bf5dcd8",
        )
        assertEquals(
            listOf(IptvClearKey("0771502eee1e4de3ba76894175f3c5ea", "13f23aaf5d66b038385bb2137bf5dcd8")),
            keys,
        )
        assertEquals(
            "demuxer-lavf-o=cenc_decryption_key=13f23aaf5d66b038385bb2137bf5dcd8",
            IptvClearKeyParser.mpvDemuxerOption(keys),
        )
    }

    @Test
    fun `parses W3C ClearKey JWK JSON and builds multi-key mpv option`() {
        val kid1 = "0771502eee1e4de3ba76894175f3c5ea"
        val key1 = "13f23aaf5d66b038385bb2137bf5dcd8"
        val kid2 = "ecf916b6df224e34815c5e5525aee733"
        val key2 = "1e4155be8344b7384493dd58d0642100"
        val jwk = """
            {
              "keys": [
                {"kty": "oct", "kid": "${IptvClearKeyParser.hex32ToBase64Url(kid1)}", "k": "${IptvClearKeyParser.hex32ToBase64Url(key1)}"},
                {"kty": "oct", "kid": "${IptvClearKeyParser.hex32ToBase64Url(kid2)}", "k": "${IptvClearKeyParser.hex32ToBase64Url(key2)}"}
              ],
              "type": "temporary"
            }
        """.trimIndent()
        val parsed = IptvClearKeyParser.parsePayload(jwk)
        assertEquals(listOf(IptvClearKey(kid1, key1), IptvClearKey(kid2, key2)), parsed)
        assertEquals(
            "demuxer-lavf-o=cenc_decryption_keys=[$kid1=$key1:$kid2=$key2],cenc_decryption_key=$key1",
            IptvClearKeyParser.mpvDemuxerOption(parsed),
        )
    }

    @Test
    fun `extracts default_KID from MPD XML and builds W3C license POST body`() {
        val mpd = """
            <MPD>
              <Period>
                <AdaptationSet>
                  <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" cenc:default_KID="0771502e-ee1e-4de3-ba76-894175f3c5ea"/>
                  <ContentProtection schemeIdUri="urn:uuid:e2719d58-a985-b3c9-781a-b030af78d30e" cenc:default_KID="0771502e-ee1e-4de3-ba76-894175f3c5ea"/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val kids = IptvClearKeyParser.extractMpdKidsHex(mpd)
        assertEquals(listOf("0771502eee1e4de3ba76894175f3c5ea"), kids)
        val req = IptvClearKeyParser.buildLicenseRequestJson(kids)
        assertEquals("""{"kids":["${IptvClearKeyParser.hex32ToBase64Url("0771502eee1e4de3ba76894175f3c5ea")}"],"type":"temporary"}""", req)
        assertNull(IptvClearKeyParser.mpvDemuxerOption(emptyList()))
    }
}
