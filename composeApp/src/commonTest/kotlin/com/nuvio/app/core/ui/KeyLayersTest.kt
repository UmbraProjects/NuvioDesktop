package com.nuvio.app.core.ui

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyLayersTest {
    private val added = mutableListOf<KeyLayers.Layer>()

    private fun layer(name: String, active: Boolean = true, hasFocus: Boolean = false, takes: Boolean = true, onReseat: () -> Unit = {}) =
        KeyLayers.Layer(name).also {
            it.active = active
            it.hasFocus = hasFocus
            it.reseat = { onReseat(); takes }
            KeyLayers.add(it)
            added += it
        }

    @AfterTest
    fun cleanUp() {
        added.forEach(KeyLayers::remove)
    }

    @Test
    fun theScreenOnTopTakesFocusBack() {
        var homeAsked = 0
        var detailsAsked = 0
        layer("Home") { homeAsked++ }
        layer("Details") { detailsAsked++ }
        assertEquals("Details", KeyLayers.reclaim("Down"))
        assertEquals(0, homeAsked)
        assertEquals(1, detailsAsked)
    }

    @Test
    fun aCoveredOrPausedScreenIsSkipped() {
        layer("Home")
        layer("Details", active = false)
        assertEquals("Home", KeyLayers.reclaim("Down"))
    }

    @Test
    fun focusAlreadyInsideTheScreenIsLeftAlone() {
        var asked = 0
        layer("Home", hasFocus = true) { asked++ }
        assertNull(KeyLayers.reclaim("Left"))
        assertEquals(0, asked)
    }

    @Test
    fun aRefusedRequestIsNotReportedAsMoved() {
        layer("Catalog", takes = false)
        assertNull(KeyLayers.reclaim("Enter"))
    }

    @Test
    fun nothingRegisteredDoesNothing() {
        assertNull(KeyLayers.reclaim("Up"))
    }
}
