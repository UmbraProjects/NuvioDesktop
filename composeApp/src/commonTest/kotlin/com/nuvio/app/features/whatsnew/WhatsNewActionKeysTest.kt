package com.nuvio.app.features.whatsnew

import com.nuvio.app.features.settings.SettingsPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhatsNewActionKeysTest {

    private val turnOn = WhatsNewAction.TurnOn(WHATS_NEW_KEY_PREVIOUSLY_ON)
    private val settingsPageNames = SettingsPage.entries.map { it.name }.toSet()

    // ---- which button a card draws ----

    @Test
    fun `a card with no action has no button`() {
        listOf(false, true).forEach { isOn ->
            listOf(false, true).forEach { consent ->
                assertNull(whatsNewButtonFor(WhatsNewAction.None, isOn, consent))
            }
        }
    }

    @Test
    fun `a switch that is off and needs nothing first is turned on in one step`() {
        assertEquals(WhatsNewButton.TurnOn, whatsNewButtonFor(turnOn, isOn = false, needsConsent = false))
    }

    @Test
    fun `a switch behind the consent step opens its page instead`() {
        assertEquals(WhatsNewButton.SetUp, whatsNewButtonFor(turnOn, isOn = false, needsConsent = true))
    }

    @Test
    fun `a feature that is already on shows as on whatever the consent state`() {
        assertEquals(WhatsNewButton.On, whatsNewButtonFor(turnOn, isOn = true, needsConsent = false))
        assertEquals(WhatsNewButton.On, whatsNewButtonFor(turnOn, isOn = true, needsConsent = true))
    }

    @Test
    fun `a turn-on key the screen does not know gets no button`() {
        val unknown = WhatsNewAction.TurnOn("no-such-feature")
        assertNull(whatsNewButtonFor(unknown, isOn = false, needsConsent = false))
        assertNull(whatsNewButtonFor(unknown, isOn = true, needsConsent = true))
    }

    @Test
    fun `an open-settings action is always a set-up button`() {
        val open = WhatsNewAction.OpenSettings("Music")
        listOf(false, true).forEach { isOn ->
            listOf(false, true).forEach { consent ->
                assertEquals(WhatsNewButton.SetUp, whatsNewButtonFor(open, isOn, consent))
            }
        }
    }

    // ---- where an action leads ----

    @Test
    fun `an action leads to its settings page`() {
        assertNull(whatsNewSettingsPage(WhatsNewAction.None))
        assertEquals("Preshow", whatsNewSettingsPage(turnOn))
        assertEquals("Preshow", whatsNewSettingsPage(WhatsNewAction.TurnOn(WHATS_NEW_KEY_POST_SHOW)))
        assertNull(whatsNewSettingsPage(WhatsNewAction.TurnOn("no-such-feature")))
        assertEquals("Music", whatsNewSettingsPage(WhatsNewAction.OpenSettings("Music")))
    }

    // ---- the keys against what exists ----

    @Test
    fun `every switch a card can flip names a settings page that exists`() {
        whatsNewTurnOnPages.forEach { (key, page) ->
            assertTrue(page in settingsPageNames, "$key points at '$page', which is not a SettingsPage")
        }
    }

    @Test
    fun `every shipped action leads somewhere real`() {
        whatsNewEntries.forEach { entry ->
            when (val action = entry.action) {
                WhatsNewAction.None -> Unit
                is WhatsNewAction.TurnOn ->
                    assertTrue(action.featureKey in whatsNewTurnOnPages, "entry ${entry.number}: unknown key")
                is WhatsNewAction.OpenSettings ->
                    assertTrue(action.pageKey in settingsPageNames, "entry ${entry.number}: no page '${action.pageKey}'")
            }
        }
    }

    @Test
    fun `every toggle has a unique key and a lookup that finds it`() {
        assertEquals(whatsNewToggles.size, whatsNewToggles.map { it.key }.toSet().size)
        whatsNewToggles.forEach { assertEquals(it.key, whatsNewToggleFor(it.key)?.key) }
        assertNull(whatsNewToggleFor("no-such-feature"))
    }

    @Test
    fun `only the pre-show cards wait on the AI agreement`() {
        val gated = whatsNewToggles.filter { it.needsAiConsent }.map { it.key }.toSet()
        assertEquals(setOf(WHATS_NEW_KEY_PREVIOUSLY_ON, WHATS_NEW_KEY_POST_SHOW), gated)
    }

    @Test
    fun `the checker reports a turn-on key the screen cannot switch on`() {
        val entry = WhatsNewEntry(
            number = 1,
            title = "Title",
            pitch = "Pitch.",
            action = WhatsNewAction.TurnOn("no-such-feature"),
        )
        val problems = whatsNewEntryProblems(listOf(entry))
        assertEquals(1, problems.size)
        assertTrue("no-such-feature" in problems.single())
    }

    @Test
    fun `the checker accepts every key the screen knows`() {
        whatsNewTurnOnPages.keys.forEachIndexed { index, key ->
            val entry = WhatsNewEntry(
                number = index + 1,
                title = "Title",
                pitch = "Pitch.",
                action = WhatsNewAction.TurnOn(key),
            )
            assertEquals(emptyList(), whatsNewEntryProblems(listOf(entry)), key)
        }
    }
}
