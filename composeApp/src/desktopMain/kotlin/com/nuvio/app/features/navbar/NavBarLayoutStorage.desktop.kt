package com.nuvio.app.features.navbar

import com.nuvio.app.core.storage.DesktopStorage

internal actual object NavBarLayoutStorage {
    // Per account, like the tab switches it sits beside.
    private val store = DesktopStorage.store("nuvio_navbar_layout")
    private const val ORDER_KEY = "order"
    private const val GAMES_KEY = "games_in_nav"

    actual fun loadOrder(): String? = store.getString(ORDER_KEY)?.takeIf { it.isNotBlank() }

    actual fun saveOrder(value: String?) = store.putString(ORDER_KEY, value)

    actual fun loadGamesInNav(): Boolean? = store.getBoolean(GAMES_KEY)

    actual fun saveGamesInNav(value: Boolean) = store.putBoolean(GAMES_KEY, value)
}
