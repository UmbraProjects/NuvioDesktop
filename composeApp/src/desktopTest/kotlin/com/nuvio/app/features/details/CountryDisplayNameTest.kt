package com.nuvio.app.features.details

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CountryDisplayNameTest {
    private val original = Locale.getDefault()

    @BeforeTest
    fun english() = Locale.setDefault(Locale.ENGLISH)

    @AfterTest
    fun restore() = Locale.setDefault(original)

    @Test
    fun namesAlpha2Alpha3AndUk() {
        assertEquals("United Kingdom", countryDisplayName("GB"))
        assertEquals("United Kingdom", countryDisplayName("GBR"))
        assertEquals("United Kingdom", countryDisplayName("UK"))
        assertEquals("Germany", countryDisplayName("DEU"))
        assertEquals("Australia", countryDisplayName("aus"))
        assertEquals("United States", countryDisplayName("USA"))
    }

    @Test
    fun englishUsesTheAdjective() {
        assertEquals("American", countryLabel("US", language = "en"))
        assertEquals("British", countryLabel("uk", language = "en"))
        assertEquals("Australian", countryLabel("aus", language = "en"))
        assertEquals("German", countryLabel("DEU", language = "en"))
        assertEquals("American", countryLabel("United States", language = "en"))
    }

    @Test
    fun otherLanguagesUseTheCountryName() {
        Locale.setDefault(Locale.GERMAN)
        assertEquals("Australien", countryLabel("AU", language = "de"))
    }

    @Test
    fun englishWithoutAnAdjectiveFallsBackToTheName() {
        assertEquals("Andorra", countryLabel("AD", language = "en"))
    }

    @Test
    fun unknownCodeIsNull() {
        assertNull(countryDisplayName("ZZZ"))
    }

    @Test
    fun followsAppLanguage() {
        Locale.setDefault(Locale.GERMAN)
        assertEquals("Deutschland", countryDisplayName("DEU"))
    }
}
