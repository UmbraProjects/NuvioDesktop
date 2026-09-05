package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The app-font setting stores a plain family name, and everything downstream trusts
 * [systemFontFamilyOrNull] to say whether the host can actually draw it. If validation lets an
 * unknown name through, the setting reads as applied while the text stack silently substitutes
 * something else — which is exactly the failure a user cannot diagnose.
 */
class SystemFontFamiliesTest {

    @Test
    fun `enumerated families are all resolvable`() {
        val families = systemFontFamilies()
        assertTrue(families.isNotEmpty(), "no installed fonts were enumerated")
        assertTrue(families.none { it.isBlank() }, "the list must carry no blank sentinel")
        // All of them, not a sample: the picker offers every one of these, and the list and the
        // validator agreeing is the whole reason this enumerates through Skia rather than AWT.
        families.forEach { family ->
            assertNotNull(systemFontFamilyOrNull(family), "listed family did not resolve: $family")
        }
    }

    @Test
    fun `unknown and blank families do not resolve`() {
        assertNull(systemFontFamilyOrNull(""))
        assertNull(systemFontFamilyOrNull("   "))
        assertNull(systemFontFamilyOrNull("Nuvio Definitely Not An Installed Font"))
    }

    @Test
    fun `family names are matched without regard to case or surrounding space`() {
        val family = systemFontFamilies().firstOrNull() ?: return
        assertNotNull(systemFontFamilyOrNull("  $family  "))
        assertNotNull(systemFontFamilyOrNull(family.uppercase()))
    }
}
