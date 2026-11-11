package com.nuvio.app.features.navbar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.settings.ThemeSettingsRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * The navigation's entries, their order and which optional ones show: one list behind the sidebar,
 * the floating top bar and the Settings screen's bar, set in Settings > Appearance > Navigation order.
 *
 * Films and series come first by default (Library before Live TV), the other video after them, then
 * Music, then Games, which does not play inside Nuvio at all. Profile / Settings always closes the bar.
 */

/** A navigation entry that can be moved. [key] is what is stored. */
enum class NavBarEntry(val key: String, val label: String) {
    Home("home", "Home"),
    Search("search", "Search"),
    /** fork:tonight - not a tab: opens the Tonight picker over the content tabs. */
    Tonight("tonight", "What to watch"),
    Discover("discover", "Discover"),
    Library("library", "Library"),
    LiveTv("livetv", "Live TV"),
    YouTube("youtube", "YouTube"),
    Music("music", "Music"),
    Games("games", "Games"),
}

internal val DEFAULT_NAV_BAR_ORDER = listOf(
    NavBarEntry.Home,
    NavBarEntry.Search,
    NavBarEntry.Tonight,
    NavBarEntry.Discover,
    NavBarEntry.Library,
    NavBarEntry.LiveTv,
    NavBarEntry.YouTube,
    NavBarEntry.Music,
    NavBarEntry.Games,
)

internal expect object NavBarLayoutStorage {
    /** The order as comma-separated keys; null when never changed. */
    fun loadOrder(): String?
    fun saveOrder(value: String?)
    fun loadGamesInNav(): Boolean?
    fun saveGamesInNav(value: Boolean)
}

object NavBarLayoutRepository {
    private val _order by lazy { MutableStateFlow(parseNavBarOrder(NavBarLayoutStorage.loadOrder())) }

    /** Every entry, in the order the bars show the ones that are on. */
    val order: StateFlow<List<NavBarEntry>>
        get() = _order.asStateFlow()

    /** Puts [entry] at 1-based [position] (clamped). */
    fun moveTo(entry: NavBarEntry, position: Int) {
        val current = _order.value
        if (entry !in current) return
        val without = current - entry
        val index = (position - 1).coerceIn(0, without.size)
        val next = without.toMutableList().apply { add(index, entry) }
        if (next == current) return
        _order.value = next
        NavBarLayoutStorage.saveOrder(next.joinToString(",") { it.key })
    }

    fun resetOrder() {
        _order.value = DEFAULT_NAV_BAR_ORDER
        NavBarLayoutStorage.saveOrder(null)
    }

    private val _gamesInNav by lazy { MutableStateFlow(NavBarLayoutStorage.loadGamesInNav() ?: false) }

    /** Settings > Games > Show in navigation: an entry that opens game mode. Off unless switched on. */
    val gamesInNav: StateFlow<Boolean>
        get() = _gamesInNav.asStateFlow()

    fun setGamesInNav(enabled: Boolean) {
        NavBarLayoutStorage.saveGamesInNav(enabled)
        _gamesInNav.value = enabled
    }
}

/**
 * A stored order: unknown keys dropped, and any entry it does not name (one added since it was saved,
 * Games for an order kept from before it existed) put back in its default place, after the entry that
 * comes before it by default.
 */
internal fun parseNavBarOrder(stored: String?): List<NavBarEntry> {
    val byKey = NavBarEntry.entries.associateBy { it.key }
    val saved = stored?.split(',')?.mapNotNull { byKey[it.trim()] }?.distinct().orEmpty()
    if (saved.isEmpty()) return DEFAULT_NAV_BAR_ORDER
    val result = saved.toMutableList()
    DEFAULT_NAV_BAR_ORDER.forEachIndexed { defaultIndex, entry ->
        if (entry in result) return@forEachIndexed
        val before = DEFAULT_NAV_BAR_ORDER.subList(0, defaultIndex).lastOrNull { it in result }
        result.add(before?.let { result.indexOf(it) + 1 } ?: 0, entry)
    }
    return result
}

/**
 * The width of one slot of a bar holding [slotCount] (the top bar and the Settings screen's bar, which
 * lines up under it): 64 dp up to six slots, then 4 dp less for each one more, never under 50 dp. A bar
 * that keeps gaining entries grows more slowly instead of running across the screen, and a short one
 * keeps its full slots rather than shrinking into a stub.
 */
fun navBarSlotWidth(slotCount: Int): Dp {
    val extra = (slotCount - 6).coerceAtLeast(0)
    return (64 - extra * 4).coerceAtLeast(50).dp
}

/** The entries that show now, in order: optional ones only while their switch says so. */
@Composable
fun rememberNavBarEntries(): List<NavBarEntry> {
    val order by remember { NavBarLayoutRepository.order }.collectAsStateWithLifecycle()
    val discover by remember { ThemeSettingsRepository.desktopDiscoverTabVisible }.collectAsStateWithLifecycle()
    val games by remember { NavBarLayoutRepository.gamesInNav }.collectAsStateWithLifecycle()
    val liveTv = com.nuvio.app.features.iptv.rememberLiveTvTabVisible()
    val youTube = com.nuvio.app.features.youtube.rememberYouTubeTabVisible()
    val music = com.nuvio.app.features.music.rememberMusicTabVisible()
    return order.filter { entry ->
        when (entry) {
            NavBarEntry.Discover -> discover
            NavBarEntry.LiveTv -> liveTv
            NavBarEntry.YouTube -> youTube
            NavBarEntry.Music -> music
            NavBarEntry.Games -> games
            NavBarEntry.Home, NavBarEntry.Search, NavBarEntry.Library, NavBarEntry.Tonight -> true
        }
    }
}

/**
 * Asks the app to go to an entry, for a bar outside the tab host (the Settings screen's) that has no
 * callback of its own for it. App collects these and opens the tab as a click on it would.
 */
object NavBarNavigation {
    private val _requests = MutableSharedFlow<NavBarEntry>(extraBufferCapacity = 1)
    val requests: SharedFlow<NavBarEntry> = _requests.asSharedFlow()

    fun request(entry: NavBarEntry) {
        _requests.tryEmit(entry)
    }
}
