package com.nuvio.app.features.home.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeroDiscoveryFlagIconTest {
    @Test
    fun `language aliases map to the intended flag designs`() {
        val expected = mapOf(
            "spa" to HeroDiscoveryFlagDesign.SPAIN,
            "fra" to HeroDiscoveryFlagDesign.FRANCE,
            "deu" to HeroDiscoveryFlagDesign.GERMANY,
            "ita" to HeroDiscoveryFlagDesign.ITALY,
            "por" to HeroDiscoveryFlagDesign.PORTUGAL,
            "jpn" to HeroDiscoveryFlagDesign.JAPAN,
            "kor" to HeroDiscoveryFlagDesign.SOUTH_KOREA,
            "zho" to HeroDiscoveryFlagDesign.CHINA,
            "dan" to HeroDiscoveryFlagDesign.DENMARK,
            "swe" to HeroDiscoveryFlagDesign.SWEDEN,
            "nor" to HeroDiscoveryFlagDesign.NORWAY,
            "fin" to HeroDiscoveryFlagDesign.FINLAND,
            "nld" to HeroDiscoveryFlagDesign.NETHERLANDS,
            "pol" to HeroDiscoveryFlagDesign.POLAND,
            "rus" to HeroDiscoveryFlagDesign.RUSSIA,
            "tur" to HeroDiscoveryFlagDesign.TURKEY,
            "ara" to HeroDiscoveryFlagDesign.SAUDI_ARABIA,
            "hin" to HeroDiscoveryFlagDesign.INDIA,
            "fas" to HeroDiscoveryFlagDesign.IRAN,
            "ron" to HeroDiscoveryFlagDesign.ROMANIA,
            "hun" to HeroDiscoveryFlagDesign.HUNGARY,
            "ces" to HeroDiscoveryFlagDesign.CZECHIA,
            "heb" to HeroDiscoveryFlagDesign.ISRAEL,
            "ell" to HeroDiscoveryFlagDesign.GREECE,
        )

        expected.forEach { (code, design) ->
            assertEquals(design, code.heroDiscoveryFlagDesign(), code)
        }
    }

    @Test
    fun `mapping is normalized and unknown languages retain the globe fallback`() {
        assertEquals(HeroDiscoveryFlagDesign.JAPAN, " JA ".heroDiscoveryFlagDesign())
        assertEquals(HeroDiscoveryFlagDesign.SOUTH_KOREA, "KoR".heroDiscoveryFlagDesign())
        assertNull("xx".heroDiscoveryFlagDesign())
    }
}
