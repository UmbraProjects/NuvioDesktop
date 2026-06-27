package com.nuvio.app.features.home.components

import com.nuvio.app.features.home.HeroCastMember
import com.nuvio.app.features.home.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeHeroSectionTest {

    @Test
    fun `mobile hero height follows viewport height when provided`() {
        val layout = homeHeroLayout(
            maxWidthDp = 390f,
            viewportHeightDp = 844f,
        )

        assertEquals(false, layout.isTablet)
        assertEquals(692.08f, layout.heroHeight.value, 0.001f)
    }

    @Test
    fun `tablet hero height remains width driven even with viewport height`() {
        val layout = homeHeroLayout(
            maxWidthDp = 840f,
            viewportHeightDp = 1200f,
        )

        assertEquals(true, layout.isTablet)
        assertEquals(386.4f, layout.heroHeight.value, 0.001f)
    }

    @Test
    fun `hero cast keeps actors with mixed production credits`() {
        val item = MetaPreview(
            id = "tt0386676",
            type = "series",
            name = "The Office",
            cast = listOf(
                HeroCastMember(name = "Producer Only", role = "Executive Producer"),
                HeroCastMember(name = "Steve Carell", role = "Executive Producer, Michael Scott"),
                HeroCastMember(name = "Ricky Gervais", role = "Creator / David Brent"),
            ),
        )

        val cast = heroDisplayCast(item, maxCount = 4)

        assertEquals(listOf("Steve Carell", "Ricky Gervais"), cast.map { it.name })
    }

    @Test
    fun `hero crew role detector distinguishes pure crew from mixed acting roles`() {
        assertTrue("Executive Producer".isHeroCrewRole())
        assertTrue("Creator / Writer".isHeroCrewRole())
        assertFalse("Executive Producer, Michael Scott".isHeroCrewRole())
        assertFalse("Creator / David Brent".isHeroCrewRole())
    }
}
