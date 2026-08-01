package com.nuvio.app.features.home

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchScrollMemoryTest {

    @BeforeTest
    fun setUp() {
        SearchScrollMemory.resetForTest()
    }

    private fun seedPosition() {
        SearchScrollMemory.firstVisibleItemIndex = 4
        SearchScrollMemory.firstVisibleItemScrollOffset = 120
        SearchScrollMemory.immersiveRowIndex = 3
        SearchScrollMemory.immersiveItemIndex = 7
        SearchScrollMemory.hasImmersivePosition = true
    }

    private fun assertCleared() {
        assertEquals(0, SearchScrollMemory.firstVisibleItemIndex)
        assertEquals(0, SearchScrollMemory.firstVisibleItemScrollOffset)
        assertEquals(0, SearchScrollMemory.immersiveRowIndex)
        assertEquals(0, SearchScrollMemory.immersiveItemIndex)
        assertFalse(SearchScrollMemory.hasImmersivePosition)
    }

    @Test
    fun `a new search clears the remembered position`() {
        SearchScrollMemory.resetIfQueryChanged("naruto")
        seedPosition()

        SearchScrollMemory.resetIfQueryChanged("bleach")

        assertCleared()
        assertEquals("bleach", SearchScrollMemory.query)
    }

    @Test
    fun `re-running the same search keeps the position`() {
        SearchScrollMemory.resetIfQueryChanged("naruto")
        seedPosition()

        SearchScrollMemory.resetIfQueryChanged("naruto")

        assertEquals(4, SearchScrollMemory.firstVisibleItemIndex)
        assertTrue(SearchScrollMemory.hasImmersivePosition)
    }

    @Test
    fun `a blank query does not clear the position`() {
        // Home and Library pass "" while sharing the HomeScreen composable, so a tab switch away
        // and back must not lose where the user was in their results.
        SearchScrollMemory.resetIfQueryChanged("naruto")
        seedPosition()

        SearchScrollMemory.resetIfQueryChanged("")

        assertEquals(4, SearchScrollMemory.firstVisibleItemIndex)
        assertEquals(7, SearchScrollMemory.immersiveItemIndex)
        assertEquals("naruto", SearchScrollMemory.query)
    }

    @Test
    fun `round trip through a blank query still restores the original search`() {
        SearchScrollMemory.resetIfQueryChanged("naruto")
        seedPosition()

        // Search -> Home -> Search
        SearchScrollMemory.resetIfQueryChanged("")
        SearchScrollMemory.resetIfQueryChanged("naruto")

        assertEquals(3, SearchScrollMemory.immersiveRowIndex)
        assertTrue(SearchScrollMemory.hasImmersivePosition)
    }

    @Test
    fun `queries differing only in case are treated as different searches`() {
        SearchScrollMemory.resetIfQueryChanged("naruto")
        seedPosition()

        SearchScrollMemory.resetIfQueryChanged("Naruto")

        assertCleared()
    }
}
