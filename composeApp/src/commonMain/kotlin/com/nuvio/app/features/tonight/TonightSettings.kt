package com.nuvio.app.features.tonight

import com.nuvio.app.features.discover.DiscoverRowFamily
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/* fork:tonight - the picker's settings, per profile (store `nuvio_tonight`). */

internal expect object TonightStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

data class TonightSettings(
    /** Off by default: new screens stay behind a switch until the user is happy with them. */
    val enabled: Boolean = false,
    /** Bedtime as minutes after midnight; 23:30 by default. */
    val bedMinuteOfDay: Int = 23 * 60 + 30,
    val lastMode: TonightMode = TonightMode.ThreeQuestions,
    /** The night (ISO date) whose one reshuffle has been used, if any. */
    val reshuffleUsedNight: String? = null,
    /** The Map: let the user's AI provider refine each title's Light/Heavy place (off by default, needs consent). */
    val mapAiEnabled: Boolean = false,
    /** Discover rows whose titles join the pool (user, Nov 12; all off by default). */
    val discoverSources: Set<TonightDiscoverSource> = emptySet(),
    /** Three Cards picks each card at random from this many of its best matches (user, Nov 12: was fixed at 5). */
    val pickFromTop: Int = TONIGHT_PICK_FROM_TOP_DEFAULT,
)

const val TONIGHT_PICK_FROM_TOP_DEFAULT = 5
val TonightPickFromTopRange = 1..20

/** A Discover row family the pool can borrow titles from, with the line a card shows for one of its titles. */
enum class TonightDiscoverSource(val family: DiscoverRowFamily, val storageKey: String) {
    TopPicks(DiscoverRowFamily.TopPicks, "srcTopPicks"),
    BecauseYouWatched(DiscoverRowFamily.Because, "srcBecause"),
    Favourites(DiscoverRowFamily.Favourites, "srcFavourites"),
    ;

    /** [rowTitle] is the Discover row's own heading, which for Because you watched names the title. */
    fun why(rowTitle: String): String = when (this) {
        TopPicks -> "A top pick for you"
        BecauseYouWatched -> rowTitle.ifBlank { "Because of what you watched" }
        Favourites -> "More like your favourites"
    }
}

object TonightSettingsRepository {
    private val _state = MutableStateFlow(TonightSettings())
    val state: StateFlow<TonightSettings> = _state.asStateFlow()
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _state.value = runCatching {
            TonightSettings(
                enabled = TonightStorage.getString("enabled") == "true",
                bedMinuteOfDay = TonightStorage.getString("bed")?.toIntOrNull()?.coerceIn(0, 24 * 60 - 1)
                    ?: TonightSettings().bedMinuteOfDay,
                lastMode = TonightStorage.getString("lastMode")
                    ?.let { name -> TonightMode.entries.firstOrNull { it.name == name } }
                    ?: TonightMode.ThreeQuestions,
                reshuffleUsedNight = TonightStorage.getString("reshuffleNight")?.takeIf { it.isNotBlank() },
                mapAiEnabled = TonightStorage.getString("mapAi") == "true",
                discoverSources = TonightDiscoverSource.entries.filter { TonightStorage.getString(it.storageKey) == "true" }.toSet(),
                pickFromTop = TonightStorage.getString("pickFromTop")?.toIntOrNull()
                    ?.coerceIn(TonightPickFromTopRange.first, TonightPickFromTopRange.last)
                    ?: TONIGHT_PICK_FROM_TOP_DEFAULT,
            )
        }.getOrDefault(TonightSettings())
    }

    /** Re-read after a profile switch (the store is profile-scoped). */
    fun reload() {
        loaded = false
        ensureLoaded()
    }

    fun setEnabled(on: Boolean) = update { it.copy(enabled = on) }.also { TonightStorage.putString("enabled", on.toString()) }

    /** Bedtime moves in quarter hours. */
    fun nudgeBedtime(quarters: Int) {
        val next = ((state.value.bedMinuteOfDay + quarters * 15) % (24 * 60) + 24 * 60) % (24 * 60)
        update { it.copy(bedMinuteOfDay = next) }
        TonightStorage.putString("bed", next.toString())
    }

    fun setMapAiEnabled(on: Boolean) {
        update { it.copy(mapAiEnabled = on) }
        TonightStorage.putString("mapAi", on.toString())
    }

    /** Turns a Discover source on or off; the pool is gathered again on the next visit. */
    fun setDiscoverSource(source: TonightDiscoverSource, on: Boolean) {
        update { it.copy(discoverSources = if (on) it.discoverSources + source else it.discoverSources - source) }
        TonightStorage.putString(source.storageKey, on.toString())
        TonightPool.markStale()
    }

    fun setPickFromTop(count: Int) {
        val next = count.coerceIn(TonightPickFromTopRange.first, TonightPickFromTopRange.last)
        update { it.copy(pickFromTop = next) }
        TonightStorage.putString("pickFromTop", next.toString())
    }

    fun setBedtime(minuteOfDay: Int) {
        val next = ((minuteOfDay % (24 * 60)) + 24 * 60) % (24 * 60)
        update { it.copy(bedMinuteOfDay = next) }
        TonightStorage.putString("bed", next.toString())
    }

    fun setLastMode(mode: TonightMode) {
        if (state.value.lastMode == mode) return
        update { it.copy(lastMode = mode) }
        TonightStorage.putString("lastMode", mode.name)
    }

    fun markReshuffleUsed(night: String) {
        update { it.copy(reshuffleUsedNight = night) }
        TonightStorage.putString("reshuffleNight", night)
    }

    private fun update(change: (TonightSettings) -> TonightSettings) {
        ensureLoaded()
        _state.value = change(_state.value)
    }
}
