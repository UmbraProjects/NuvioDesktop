package com.nuvio.app.features.trakt

import kotlinx.serialization.Serializable

@Serializable
data class TraktAuthState(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val tokenType: String? = null,
    val createdAt: Long? = null,
    val expiresIn: Int? = null,
    val username: String? = null,
    val userSlug: String? = null,
    /**
     * The Client ID that minted this grant. A token only works with its own app, so pasting a new
     * Client ID must not silently pair it with the old token. Null = minted before the 2026-10
     * auth migration, whose refresh tokens Trakt no longer exchanges.
     */
    val clientId: String? = null,
    /** Trakt stopped accepting the grant; history and lists stay, requests stop until reconnect. */
    val needsReconnect: Boolean = false,
) {
    val isAuthenticated: Boolean
        get() = !accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()
}

enum class TraktConnectionMode {
    DISCONNECTED,
    AWAITING_APPROVAL,
    CONNECTED,
}

data class TraktAuthUiState(
    val mode: TraktConnectionMode = TraktConnectionMode.DISCONNECTED,
    val credentialsConfigured: Boolean = true,
    val isLoading: Boolean = false,
    val username: String? = null,
    val tokenExpiresAtMillis: Long? = null,
    val needsReconnect: Boolean = false,
    /** Device-code sign-in in progress: the code the user types at [pendingVerificationUrl]. */
    val pendingUserCode: String? = null,
    val pendingVerificationUrl: String? = null,
    val pendingExpiresAtMillis: Long? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

enum class TraktBrandAsset {
    Glyph,
    Wordmark,
}

@Serializable
enum class TraktListType {
    WATCHLIST,
    PERSONAL,
}

@Serializable
enum class TraktListPrivacy(val apiValue: String) {
    PRIVATE("private"),
    LINK("link"),
    FRIENDS("friends"),
    PUBLIC("public");

    companion object {
        fun fromApi(value: String?): TraktListPrivacy =
            entries.firstOrNull { it.apiValue.equals(value, ignoreCase = true) } ?: PRIVATE
    }
}

@Serializable
data class TraktListTab(
    val key: String,
    val title: String,
    val type: TraktListType,
    val traktListId: Long? = null,
    val slug: String? = null,
    val description: String? = null,
    val privacy: TraktListPrivacy? = null,
    val sortBy: String? = null,
    val sortHow: String? = null,
)

data class TraktMembershipSnapshot(
    val listMembership: Map<String, Boolean> = emptyMap(),
)

data class TraktMembershipChanges(
    val desiredMembership: Map<String, Boolean>,
)
