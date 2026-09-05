package com.nuvio.app.features.discover

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One saved AI prompt — plan §5, phase 6.
 *
 * **A separate type from [CustomDiscoverRow] and [ImportedDiscoverRow], for the third time and the
 * same reason.** An AI row has a prompt, not filters; it costs money to refresh rather than a TMDB
 * call; it holds its last answer because regenerating is an explicit act. Folding it into the custom
 * row behind a `source` flag gives a row whose editable fields depend on that flag, which is how you
 * get controls that silently do nothing. The plan's §5 sketch said to share the model; §4.4 had
 * already dropped the `source` enum, and the `imported:` split proved the shape — this follows it.
 *
 * [items] is the last generation, cached in settings the way an imported list is. An AI row with no
 * items has never been generated and renders nothing rather than an error: it is not broken, it is
 * unasked.
 */
@Serializable
data class AiDiscoverRow(
    val id: String,
    val title: String = "",
    val preset: AiDiscoverPreset = AiDiscoverPreset.HiddenGems,
    /** Only meaningful when [preset] is [AiDiscoverPreset.Custom]. */
    val customInstruction: String = "",
    val enabled: Boolean = true,
    /** Epoch millis of the last successful generation, 0 when never generated. */
    val generatedAtEpochMs: Long = 0L,
    val items: List<AiDiscoverItem> = emptyList(),
) {
    val hasGenerated: Boolean get() = generatedAtEpochMs > 0L

    /** The prompt enum this row maps onto. Kept apart so the stored name can outlive a rename. */
    val promptPreset: DiscoverAiPreset
        get() = when (preset) {
            AiDiscoverPreset.HiddenGems -> DiscoverAiPreset.HiddenGems
            AiDiscoverPreset.ComfortWatches -> DiscoverAiPreset.ComfortWatches
            AiDiscoverPreset.CriticallyAcclaimed -> DiscoverAiPreset.CriticallyAcclaimed
            AiDiscoverPreset.Custom -> DiscoverAiPreset.Custom
            AiDiscoverPreset.JustWatched -> DiscoverAiPreset.JustWatched
            AiDiscoverPreset.ClusteredFavourites -> DiscoverAiPreset.ClusteredFavourites
        }
}

/**
 * The saved prompt kinds.
 *
 * Serialised by an explicit name rather than the enum's own, so renaming a preset in code does not
 * silently reset every row that used it to the default.
 */
@Serializable
enum class AiDiscoverPreset {
    @SerialName("hidden_gems")
    HiddenGems,

    @SerialName("comfort")
    ComfortWatches,

    @SerialName("acclaimed")
    CriticallyAcclaimed,

    @SerialName("custom")
    Custom,

    /**
     * The last few titles, asked for continuations of a current mood — plan §19.3, phase 8.
     *
     * Not a variant of [HiddenGems] with a different adjective: the difference is the *slice of
     * history the prompt carries*, which is why phase 8 arrived as presets rather than a fourth row
     * type. See [aiSeedsForPreset].
     */
    @SerialName("just_watched")
    JustWatched,

    /** The most-committed titles handed over as a group, to be read for a shared shape. */
    @SerialName("clustered_favourites")
    ClusteredFavourites,
}

/** True for the presets phase 8 added, which the app can propose off the history by itself. */
val AiDiscoverPreset.isProposable: Boolean
    get() = this == AiDiscoverPreset.JustWatched || this == AiDiscoverPreset.ClusteredFavourites

/**
 * One resolved suggestion, stored.
 *
 * Deliberately the same narrow set of fields the row cache keeps, plus [reason] — which is the
 * whole point of an AI row and the one thing no other row has. A resolved item is stored rather
 * than re-resolved on every launch because the resolution cost a TMDB search per title.
 */
@Serializable
data class AiDiscoverItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    /**
     * TMDB's vote average for the title this resolved to, pre-formatted for the poster badge.
     *
     * The model never supplies this and is never asked to — it is read off the TMDB result the
     * suggestion matched, which is the same place every other Discover row gets it. Null for rows
     * generated before this field existed; they simply show no badge until regenerated.
     */
    val rating: String? = null,
    /** Why the model picked this, one sentence. Null when it did not say. */
    val reason: String? = null,
)

/** Ceilings, enforced on read and write — these rows carry their contents in the settings payload. */
const val AI_DISCOVER_ROW_LIMIT = 8
const val AI_DISCOVER_ITEMS_PER_ROW = 40

/** Drops one item by position. Same reasoning as `ImportedDiscoverRow.withoutItemAt`. */
fun AiDiscoverRow.withoutItemAt(index: Int): AiDiscoverRow =
    if (index !in items.indices) this else copy(items = items.filterIndexed { at, _ -> at != index })

/** Applies the per-row item ceiling. */
fun AiDiscoverRow.capped(): AiDiscoverRow =
    if (items.size <= AI_DISCOVER_ITEMS_PER_ROW) this else copy(items = items.take(AI_DISCOVER_ITEMS_PER_ROW))
