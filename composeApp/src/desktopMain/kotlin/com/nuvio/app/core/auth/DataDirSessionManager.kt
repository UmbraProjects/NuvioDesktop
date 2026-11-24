package com.nuvio.app.core.auth

import com.nuvio.app.core.storage.DesktopStorage
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.json.Json

/**
 * Keeps the Supabase sign-in in the data directory instead of supabase-kt's default store, which
 * is Java preferences (the Windows registry) and so shared by every data directory on the PC.
 *
 * Used only when the app runs against an overridden data directory (`-Dnuvio.desktop.dataDir`,
 * a test profile): such a profile must start signed out and must never sign the real profile in
 * or out. Normal installs keep the registry store, so nobody is signed out by an update.
 */
internal class DataDirSessionManager(backendUrl: String) : SessionManager {
    private val store = DesktopStorage.store(STORE_NAME)
    private val key = "session:" + backendUrl.trim().trimEnd('/')

    /** supabase-kt's own Json is internal; this matches what its session store needs. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun saveSession(session: UserSession) {
        store.putString(key, json.encodeToString(UserSession.serializer(), session))
    }

    override suspend fun loadSession(): UserSession? {
        val raw = store.getString(key) ?: return null
        return runCatching { json.decodeFromString(UserSession.serializer(), raw) }.getOrNull()
    }

    override suspend fun deleteSession() {
        store.remove(key)
    }

    companion object {
        /** A machine store: it decides which account folder the app opens, so it sits above them. */
        const val STORE_NAME = "nuvio_auth_session"
    }
}
