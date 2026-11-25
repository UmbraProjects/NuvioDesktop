package com.nuvio.app

import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.ProfileState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StartupBootstrapTest {

    private fun profile(index: Int, pinEnabled: Boolean = false) = NuvioProfile(
        id = "p-$index",
        profileIndex = index,
        name = "Profile $index",
        pinEnabled = pinEnabled,
    )

    @Test
    fun resolveAutoStartupProfileSelectsRememberedNonPinProfile() {
        val p1 = profile(1)
        val p2 = profile(2)
        val state = ProfileState(
            profiles = listOf(p1, p2),
            activeProfile = p2,
            isLoaded = true,
            hasEverSelectedProfile = true,
            rememberLastProfileEnabled = true,
        )
        assertEquals(p2, ProfileRepository.resolveAutoStartupProfile(state, activeId = 2))
    }

    @Test
    fun resolveAutoStartupProfileSkipsPinProtectedRememberedProfile() {
        val p1 = profile(1, pinEnabled = true)
        val p2 = profile(2)
        val state = ProfileState(
            profiles = listOf(p1, p2),
            activeProfile = p1,
            isLoaded = true,
            hasEverSelectedProfile = true,
            rememberLastProfileEnabled = true,
        )
        assertNull(ProfileRepository.resolveAutoStartupProfile(state, activeId = 1))
    }

    @Test
    fun resolveAutoStartupProfileSelectsSoleNonPinProfileWithoutRememberSetting() {
        val only = profile(1)
        val state = ProfileState(
            profiles = listOf(only),
            isLoaded = true,
            hasEverSelectedProfile = false,
            rememberLastProfileEnabled = false,
        )
        assertEquals(only, ProfileRepository.resolveAutoStartupProfile(state, activeId = 1))
    }

    @Test
    fun resolveAutoStartupProfileReturnsNullForSolePinProfileOrMultipleProfilesWithoutRemember() {
        val solePin = ProfileState(profiles = listOf(profile(1, pinEnabled = true)), isLoaded = true)
        assertNull(ProfileRepository.resolveAutoStartupProfile(solePin, activeId = 1))

        val multi = ProfileState(
            profiles = listOf(profile(1), profile(2)),
            isLoaded = true,
            hasEverSelectedProfile = true,
            rememberLastProfileEnabled = false,
        )
        assertNull(ProfileRepository.resolveAutoStartupProfile(multi, activeId = 1))
    }

    @Test
    fun executeBootstrapBecomesReadyForAuthenticatedAutoProfile() = runBlocking {
        val steps = mutableListOf<String>()
        val p1 = profile(1)
        val auth = AuthState.Authenticated(userId = "u1", email = "a@b.c", isAnonymous = false)

        val result = executeBootstrap(
            loadCachedProfiles = { steps += "cached"; true },
            initializeAuth = { steps += "initAuth" },
            awaitSettledAuth = { steps += "awaitAuth"; auth },
            currentAuth = { auth },
            ensureProfilesLoaded = { steps += "ensure:$it" },
            launchProfilePull = { steps += "pull:$it" },
            resolveAutoProfile = { p1 },
            activateAndWarmProfile = { steps += "warm:${it.profileIndex}" },
        )

        assertEquals(StartupBootstrapState.Ready(userId = "u1", profileIndex = 1), result)
        assertEquals(listOf("cached", "initAuth", "awaitAuth", "ensure:u1", "pull:u1", "warm:1"), steps)
    }

    @Test
    fun executeBootstrapHandsOffToGateWhenUnauthenticatedOrPickerNeeded() = runBlocking {
        var warmed = false
        val unauthResult = executeBootstrap(
            loadCachedProfiles = { true },
            initializeAuth = {},
            awaitSettledAuth = { AuthState.Unauthenticated },
            currentAuth = { AuthState.Unauthenticated },
            ensureProfilesLoaded = {},
            launchProfilePull = {},
            resolveAutoProfile = { profile(1) },
            activateAndWarmProfile = { warmed = true },
        )
        assertEquals(StartupBootstrapState.NeedsGate, unauthResult)
        assertFalse(warmed)

        val auth = AuthState.Authenticated(userId = "u1", email = null, isAnonymous = false)
        var pulled = false
        val pickerResult = executeBootstrap(
            loadCachedProfiles = { true },
            initializeAuth = {},
            awaitSettledAuth = { auth },
            currentAuth = { auth },
            ensureProfilesLoaded = {},
            launchProfilePull = { pulled = true },
            resolveAutoProfile = { null },
            activateAndWarmProfile = { warmed = true },
        )
        assertEquals(StartupBootstrapState.NeedsGate, pickerResult)
        assertTrue(pulled)
        assertFalse(warmed)
    }

    @Test
    fun executeBootstrapHandsOffIfSignedOutDuringWarm() = runBlocking {
        val auth = AuthState.Authenticated(userId = "u1", email = null, isAnonymous = false)
        var current: AuthState = auth

        val result = executeBootstrap(
            loadCachedProfiles = { true },
            initializeAuth = {},
            awaitSettledAuth = { auth },
            currentAuth = { current },
            ensureProfilesLoaded = {},
            launchProfilePull = {},
            resolveAutoProfile = { profile(1) },
            activateAndWarmProfile = { current = AuthState.Unauthenticated },
        )

        assertEquals(StartupBootstrapState.NeedsGate, result)
    }
}
