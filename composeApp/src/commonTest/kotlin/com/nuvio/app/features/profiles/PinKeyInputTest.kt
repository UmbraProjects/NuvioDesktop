package com.nuvio.app.features.profiles

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PinKeyInputTest {
    @Test
    fun topRowAndNumberPadTypeTheSameDigits() {
        assertEquals("0", pinDigitFor(Key.Zero))
        assertEquals("0", pinDigitFor(Key.NumPad0))
        assertEquals("7", pinDigitFor(Key.Seven))
        assertEquals("7", pinDigitFor(Key.NumPad7))
        assertEquals("9", pinDigitFor(Key.NumPad9))
    }

    @Test
    fun otherKeysTypeNothing() {
        assertNull(pinDigitFor(Key.A))
        assertNull(pinDigitFor(Key.Backspace))
        assertNull(pinDigitFor(Key.Enter))
    }
}
