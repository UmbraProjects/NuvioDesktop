package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Addons whose link turned out to be a placeholder clip ("not cached" and the like) during one
 * player session: the binge from one episode to the next. Next-episode autoplay opens their links
 * last, and only while Preload Next Episode Sources is on, whose early check finds them without
 * costing playback anything. Closing the player drops it; nothing carries to another title.
 *
 * Comet's South Park links gave its clip three times out of three on Nov 21: each episode re-picked
 * it, because nothing remembered. A preference, not a ban: when only marked addons are left, they
 * are still tried.
 */
internal class PlaceholderSourceMemory {
    private val marked = MutableStateFlow<Set<String>>(emptySet())

    private fun StreamItem.addonKey(): String = addonId.ifBlank { addonName }

    fun record(stream: StreamItem?) {
        val addon = stream?.addonKey().orEmpty()
        if (addon.isBlank()) return
        marked.update { it + addon }
    }

    fun isMarked(stream: StreamItem): Boolean = stream.addonKey() in marked.value

    /**
     * [streams] without the marked addons' rows. When that leaves nothing, all of them if
     * [keepMarkedIfOnly] (every answer is in), else none (later answers may bring a better one).
     */
    fun prefer(streams: List<StreamItem>, keepMarkedIfOnly: Boolean = true): List<StreamItem> {
        if (marked.value.isEmpty()) return streams
        val kept = streams.filterNot(::isMarked)
        return if (kept.isEmpty() && keepMarkedIfOnly) streams else kept
    }
}

/**
 * Holds the player's attach for a link that may resolve to a placeholder clip (Comet- and
 * StremThru-style playback endpoints, [isProviderPlaybackEndpoint]) until the probe in
 * BindPlayerRuntimeEffects has answered, so the clip's "not cached" card never reaches the screen.
 *
 * Absent means "attach": only a link the probe is working on waits, and only a link it has found
 * to be a placeholder that failover is replacing is skipped. A skip is used once, so replaying the
 * same URL later attaches normally.
 */
internal object ProviderProbeGate {
    enum class Verdict { Pending, Attach, Skip }

    private val verdicts = MutableStateFlow<Map<String, Verdict>>(emptyMap())

    fun pending(url: String) = set(url, Verdict.Pending)

    fun attach(url: String) = set(url, Verdict.Attach)

    fun skip(url: String) = set(url, Verdict.Skip)

    /** Drops a verdict still pending (the probe was cancelled), leaving answered ones alone. */
    fun abandon(url: String) {
        verdicts.update { if (it[url] == Verdict.Pending) it - url else it }
    }

    private fun set(url: String, verdict: Verdict) {
        verdicts.update { it + (url to verdict) }
    }

    /**
     * Waits while [url] is being probed, up to [timeoutMs] (then attaches anyway, as before the
     * gate). False when the link is a placeholder failover is replacing: the caller must not open it.
     */
    suspend fun awaitAttach(url: String, timeoutMs: Long = PROBE_WAIT_MS): Boolean {
        val answered = withTimeoutOrNull(timeoutMs) {
            verdicts.first { it[url] != Verdict.Pending }[url]
        }
        if (answered == Verdict.Skip) {
            verdicts.update { it - url }
            return false
        }
        return true
    }

    internal fun clearForTest() {
        verdicts.value = emptyMap()
    }

    private const val PROBE_WAIT_MS = 4_000L
}
