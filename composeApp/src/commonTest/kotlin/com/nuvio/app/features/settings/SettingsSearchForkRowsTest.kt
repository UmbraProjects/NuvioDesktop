package com.nuvio.app.features.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsSearchForkRowsTest {

    @Test
    fun keysAreUniqueAndPrefixed() {
        val keys = forkSearchRows.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "duplicate fork search keys")
        assertTrue(keys.all { it.startsWith("x-") }, "fork search keys start with x- so they never clash with upstream keys")
    }

    @Test
    fun everyRowHasAPageAndSection() {
        assertTrue(forkSearchRows.isNotEmpty())
        // Page rows are scroll targets by section, so a row's section must be a real string resource
        // (the compiler checks that) and its page must be a page the user can open.
        assertTrue(forkSearchRows.none { it.page == SettingsPage.Root })
    }
}
