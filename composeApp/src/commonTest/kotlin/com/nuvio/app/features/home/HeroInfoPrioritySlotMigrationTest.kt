package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeroInfoPrioritySlotMigrationTest {
    private val stinger = listOf(HeroInfoPrioritySlotMigration(slot = "stinger", after = listOf("true_story")))

    @Test
    fun `a new slot is inserted after its anchor and recorded`() {
        val result = migrateHeroInfoPrioritySlots(
            priority = "wins,cult,true_story,short_film",
            appliedMigrations = emptySet(),
            migrations = stinger,
        )

        assertTrue(result.changed)
        assertEquals("wins,cult,true_story,stinger,short_film", result.priority)
        assertEquals(setOf("stinger"), result.appliedMigrations)
    }

    @Test
    fun `a slot the user turned off is not re-added`() {
        // The whole point of recording the migration: this is the state after someone unticks
        // "Credits scene" in settings, and it has to survive every later load.
        val result = migrateHeroInfoPrioritySlots(
            priority = "wins,cult,true_story,short_film",
            appliedMigrations = setOf("stinger"),
            migrations = stinger,
        )

        assertFalse(result.changed)
        assertFalse("stinger" in result.priority)
        assertEquals("wins,cult,true_story,short_film", result.priority)
    }

    @Test
    fun `an already-present slot is recorded without being duplicated`() {
        val result = migrateHeroInfoPrioritySlots(
            priority = "wins,stinger,cult",
            appliedMigrations = emptySet(),
            migrations = stinger,
        )

        assertEquals("wins,stinger,cult", result.priority)
        assertEquals(setOf("stinger"), result.appliedMigrations)
        // Still "changed", because the applied set has to be written back.
        assertTrue(result.changed)
    }

    @Test
    fun `a missing anchor appends rather than dropping the slot`() {
        val result = migrateHeroInfoPrioritySlots(
            priority = "wins,cult",
            appliedMigrations = emptySet(),
            migrations = stinger,
        )

        assertEquals("wins,cult,stinger", result.priority)
    }

    @Test
    fun `the shipped migrations are idempotent over the default priority`() {
        val defaults = HomeCatalogSettingsUiState().heroInfoPriority
        val first = migrateHeroInfoPrioritySlots(defaults, emptySet())
        val second = migrateHeroInfoPrioritySlots(first.priority, first.appliedMigrations)

        assertEquals(defaults, first.priority)
        assertFalse(second.changed)
        assertEquals(first.priority, second.priority)
    }
}
