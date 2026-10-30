package com.nuvio.app.features.preroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MpaAdvisoryTest {
    private fun response(vararg nodes: String) =
        """{"data":{"title":{"certificates":{"edges":[${nodes.joinToString(",") { """{"node":$it}""" }}]}}}}"""

    private fun node(rating: String, country: String, reason: String?) =
        """{"rating":"$rating","country":{"id":"$country"},"ratingReason":${reason?.let { "\"$it\"" } ?: "null"}}"""

    @Test
    fun theUsMpaReasonIsFormattedWithoutTheRatedLeadIn() {
        val body = response(
            node("15", "GB", null),
            node("R", "US", "Rated R for strong bloody violence, disturbing behavior, language and brief sexual images"),
        )
        assertEquals(
            "for strong bloody violence, disturbing behavior, language and brief sexual images",
            parseMpaReason(body),
        )
    }

    @Test
    fun otherCountriesAndOtherSystemsAreIgnored() {
        assertNull(parseMpaReason(response(node("R", "CA", "Rated R for violence"))))
        assertNull(parseMpaReason(response(node("TV-MA", "US", "violence"))))
    }

    @Test
    fun aUsCertificateWithNoReasonGivesNothing() {
        assertNull(parseMpaReason(response(node("PG-13", "US", null), node("PG-13", "US", "  "))))
    }

    @Test
    fun brokenOrEmptyResponsesGiveNothing() {
        assertNull(parseMpaReason("not json"))
        assertNull(parseMpaReason("""{"data":{"title":null}}"""))
        assertNull(parseMpaReason(response()))
    }

    @Test
    fun reasonsAreNormalisedToStartWithFor() {
        assertEquals("for language and some violence", formatMpaReason("Rated PG-13 for language and some violence."))
        assertEquals("for thematic elements", formatMpaReason("Rated PG for thematic elements"))
        assertEquals("for strong violence", formatMpaReason("strong violence"))
        assertEquals("for Violence", formatMpaReason("FOR Violence"))
        assertNull(formatMpaReason("Rated R"))
        assertNull(formatMpaReason("   "))
    }
}
