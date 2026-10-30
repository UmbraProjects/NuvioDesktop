package com.nuvio.app.features.preroll

import com.nuvio.app.features.home.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreshowLogoTest {
    private fun card(id: String = "tt0388629", logo: String? = null) =
        MetaPreview(id = id, type = "series", name = "Wild 'N Out", logo = logo)

    @Test
    fun theCardsOwnLogoWins() {
        assertEquals("own", preshowLogoFor(card(logo = "own"), detailsLogo = "meta"))
    }

    @Test
    fun metadataLogoIsUsedWhenTheCardHasNone() {
        assertEquals("meta", preshowLogoFor(card(), detailsLogo = "meta"))
        assertEquals("meta", preshowLogoFor(card(logo = "  "), detailsLogo = "meta"))
    }

    @Test
    fun anImdbIdFallsBackToMetahub() {
        assertEquals(
            "https://images.metahub.space/logo/medium/tt0388629/img",
            preshowLogoFor(card(), detailsLogo = null),
        )
        assertEquals(
            "https://images.metahub.space/logo/medium/tt0388629/img",
            preshowLogoFor(card(), detailsLogo = " "),
        )
    }

    @Test
    fun noLogoAnywhereKeepsTheTextTitle() {
        assertNull(preshowLogoFor(card(id = "tmdb:1054867"), detailsLogo = null))
        assertNull(preshowLogoFor(card(id = "tt123:1:2"), detailsLogo = null))
    }
}
