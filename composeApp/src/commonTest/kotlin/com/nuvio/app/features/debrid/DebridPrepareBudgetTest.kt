package com.nuvio.app.features.debrid

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The speculative allowance exists so a browsing session cannot spend the budget a real play is
 * about to need — preparing links ahead is self-defeating if the guesses exhaust the allowance
 * before the actual request arrives.
 */
class DebridPrepareBudgetTest {

    @BeforeTest
    fun setUp() = runBlocking { DirectDebridStreamPreparer.resetBudgets() }

    @AfterTest
    fun tearDown() = runBlocking { DirectDebridStreamPreparer.resetBudgets() }

    @Test
    fun `exhausting the speculative allowance leaves the interactive one untouched`() = runBlocking {
        repeat(2) {
            assertTrue(DirectDebridStreamPreparer.consumeBackgroundBudget(DebridPrepareBudget.Speculative))
        }
        assertFalse(DirectDebridStreamPreparer.consumeBackgroundBudget(DebridPrepareBudget.Speculative))

        repeat(6) {
            assertTrue(DirectDebridStreamPreparer.consumeBackgroundBudget(DebridPrepareBudget.Interactive))
        }
    }

    @Test
    fun `exhausting the interactive allowance leaves the speculative one untouched`() = runBlocking {
        repeat(6) {
            assertTrue(DirectDebridStreamPreparer.consumeBackgroundBudget(DebridPrepareBudget.Interactive))
        }
        assertFalse(DirectDebridStreamPreparer.consumeBackgroundBudget(DebridPrepareBudget.Interactive))

        assertTrue(DirectDebridStreamPreparer.consumeBackgroundBudget(DebridPrepareBudget.Speculative))
    }

    @Test
    fun `the speculative allowance is the smaller of the two`() = runBlocking {
        var speculative = 0
        while (DirectDebridStreamPreparer.consumeBackgroundBudget(DebridPrepareBudget.Speculative)) speculative++
        var interactive = 0
        while (DirectDebridStreamPreparer.consumeBackgroundBudget(DebridPrepareBudget.Interactive)) interactive++

        assertTrue(speculative < interactive, "speculative=$speculative interactive=$interactive")
    }

    @Test
    fun `reset restores both allowances`() = runBlocking {
        while (DirectDebridStreamPreparer.consumeBackgroundBudget(DebridPrepareBudget.Speculative)) Unit
        while (DirectDebridStreamPreparer.consumeBackgroundBudget(DebridPrepareBudget.Interactive)) Unit

        DirectDebridStreamPreparer.resetBudgets()

        assertTrue(DirectDebridStreamPreparer.consumeBackgroundBudget(DebridPrepareBudget.Speculative))
        assertTrue(DirectDebridStreamPreparer.consumeBackgroundBudget(DebridPrepareBudget.Interactive))
    }
}
