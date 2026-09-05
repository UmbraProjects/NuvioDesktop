package com.nuvio.app.features.discover

import com.nuvio.app.features.watched.WatchedItem

/**
 * Rows the app offers to build out of the watch history — plan §19.3, phase 8.
 *
 * **The point of the phase, in one sentence:** Nuvio holds something metadata addons structurally
 * cannot see — this person's own viewing, in full, locally — and phase 6 spent it on a single
 * generic "here is what they watched, suggest things" prompt per row. The rows worth having ask a
 * *specific* question of a *specific* slice of that history, and the app can work out which slices
 * are worth asking about without the user writing a prompt at all.
 *
 * **Proposing is not generating.** §5 deliberately refused to spend the user's money on a schedule,
 * and that refusal survives here intact: everything in this file is pure, local and free. A proposal
 * is an offer. Accepting one creates an ordinary saved [AiDiscoverRow] with its Generate button
 * unpressed, exactly as if the user had built it by hand — which is what keeps generation an
 * explicit act rather than something the app decided to buy on their behalf.
 */

/** One row the app believes the history can support, offered to the user. */
data class AiRowProposal(
    val preset: AiDiscoverPreset,
    /**
     * The titles that produced this proposal, strongest first.
     *
     * Carried so the offer can show its working — "because you finished Dark, Lost and FROM" is a
     * reason to accept or dismiss, where a bare row name is a guess. Capped at
     * [PROPOSAL_EVIDENCE_COUNT]; the prompt itself gets far more than this.
     */
    val evidence: List<String>,
    /** How many history titles the prompt would actually carry. */
    val seedCount: Int,
)

/** Titles named in a proposal's "because you watched…" line. */
const val PROPOSAL_EVIDENCE_COUNT = 3

/** Seeds the "just watched" slice sends. Small on purpose — see [AiDiscoverPreset.JustWatched]. */
const val JUST_WATCHED_SEEDS = 10

/** Below this the slice is too thin to describe a mood, and the row is not offered. */
const val JUST_WATCHED_MIN_SEEDS = 4

/** How recent "just watched" has to be. A mood two months old is not a current one. */
const val JUST_WATCHED_MAX_AGE_MS: Long = 30L * 24 * 60 * 60 * 1000

/** Seeds the clustered slice sends. Larger: a shape needs several examples to be visible. */
const val COMMITTED_SEEDS = 15

/** Below this there is no cluster to find, only a list. */
const val COMMITTED_MIN_SEEDS = 6

/**
 * The rows worth offering for this history, best first, excluding anything already saved.
 *
 * Pure and free: no network, no TMDB, no provider. Returning an empty list is an ordinary outcome —
 * a fresh install has nothing to say, and offering a row it cannot fill would be worse than
 * offering nothing.
 */
fun proposeAiDiscoverRows(
    history: List<WatchedItem>,
    existing: List<AiDiscoverRow>,
    now: Long,
): List<AiRowProposal> {
    // One row per preset. A second "just watched" row would ask the same question of the same
    // slice and cost a second request to hear the same answer.
    val taken = existing.mapTo(mutableSetOf()) { it.preset }
    if (existing.size >= AI_DISCOVER_ROW_LIMIT) return emptyList()

    return buildList {
        if (AiDiscoverPreset.JustWatched !in taken) {
            val recent = justWatchedCandidates(history, now)
            if (recent.size >= JUST_WATCHED_MIN_SEEDS) {
                add(
                    AiRowProposal(
                        preset = AiDiscoverPreset.JustWatched,
                        evidence = recent.take(PROPOSAL_EVIDENCE_COUNT).map { it.title },
                        seedCount = recent.size,
                    ),
                )
            }
        }
        if (AiDiscoverPreset.ClusteredFavourites !in taken) {
            val committed = committedCandidates(history, now)
            if (committed.size >= COMMITTED_MIN_SEEDS) {
                add(
                    AiRowProposal(
                        preset = AiDiscoverPreset.ClusteredFavourites,
                        evidence = committed.take(PROPOSAL_EVIDENCE_COUNT).map { it.title },
                        seedCount = committed.size,
                    ),
                )
            }
        }
    }
}

/**
 * The slice of history one preset's prompt should carry.
 *
 * This is the whole of what separates the phase-8 presets from the phase-6 ones: they are not new
 * kinds of row and they do not ask the model for anything structurally different — they hand it a
 * *different, deliberately chosen* part of the history. §19.3 preferred that over a fourth row type
 * for exactly this reason.
 */
fun aiSeedsForPreset(
    preset: AiDiscoverPreset,
    history: List<WatchedItem>,
    now: Long,
): List<DiscoverAiSeed> = when (preset) {
    AiDiscoverPreset.JustWatched -> justWatchedCandidates(history, now).map { it.toSeed() }
    AiDiscoverPreset.ClusteredFavourites -> committedCandidates(history, now).map { it.toSeed() }
    // The phase-6 presets describe an all-time taste, and the whole recent history is that.
    AiDiscoverPreset.HiddenGems,
    AiDiscoverPreset.ComfortWatches,
    AiDiscoverPreset.CriticallyAcclaimed,
    AiDiscoverPreset.Custom,
    -> aiSeedsFromHistory(history, now)
}

/**
 * The last few things watched, most recent first.
 *
 * Recency is the entire selection rule, and the age cut is what makes it mean "right now" rather
 * than "lately". [collapseWatchedToSeedCandidates] already returns one candidate per title in
 * most-recent-first order, so this is a window over it rather than a second ranking.
 */
private fun justWatchedCandidates(
    history: List<WatchedItem>,
    now: Long,
): List<DiscoverSeedCandidate> =
    collapseWatchedToSeedCandidates(history, now = now, maxAgeMs = JUST_WATCHED_MAX_AGE_MS)
        .take(JUST_WATCHED_SEEDS)

/**
 * The titles this person has put the most into, most committed first.
 *
 * **Ranked on episodes watched, tie-broken by recency — and that is a proxy, not a measurement.**
 * §19.3 wanted the user's own high ratings here, on the grounds that Lost, FROM and Dark rated
 * highly *together* describe a shape no one of them does. Those ratings are not available: this fork
 * has a [com.nuvio.app.features.tracking.TrackingRatingWriter] and no reader, so ratings the user
 * gave live only in Trakt/Simkl/MDBList and the app cannot see them. §19.3 anticipated that and
 * asked for a degrade to "most rewatched / most completed"; rewatches are not available either,
 * because the watch history is keyed on (type, id, season, episode) and a second viewing overwrites
 * the first rather than counting.
 *
 * What is left is genuine and local: finishing forty episodes of something is a real statement about
 * it, and finishing one film is a much weaker one. So series sort above films, which is the correct
 * bias for the question being asked — and on a films-only history this degrades to recency, where it
 * overlaps [AiDiscoverPreset.JustWatched]'s slice. The two rows still differ in what they *ask*, so
 * that is a duller proposal rather than a wrong one.
 *
 * If a ratings reader ever lands, this function is the one place to change.
 */
private fun committedCandidates(
    history: List<WatchedItem>,
    now: Long,
): List<DiscoverSeedCandidate> =
    collapseWatchedToSeedCandidates(history, now = now)
        .sortedWith(
            compareByDescending<DiscoverSeedCandidate> { it.episodesWatched }
                .thenByDescending { it.lastWatchedAtEpochMs },
        )
        .take(COMMITTED_SEEDS)

private fun DiscoverSeedCandidate.toSeed(): DiscoverAiSeed = DiscoverAiSeed(
    title = title,
    // Same reasoning as aiSeedsFromHistory: the history's `releaseInfo` is free text that is a bare
    // year for some addons and a range for others, and a wrong year in the prompt is worse than
    // none. `rating` stays null because nothing local knows it.
    type = if (type.equals("movie", ignoreCase = true)) "movie" else "series",
)

/**
 * True when this preset cannot be asked its question, because the history it asks *about* is empty.
 *
 * The phase-8 presets are a slice of history plus a question about that slice, and both of their
 * instructions open with "The list below is…". [buildDiscoverAiPrompt] omits the list when there are
 * no seeds, so sending one anyway hands the model a reference to nothing — and a model given that
 * replies with a question or a caveat rather than a JSON array, which arrives back as
 * [DiscoverAiError.Empty] and reads as the provider having failed. It did not; we asked it nothing.
 *
 * The proposal path cannot reach this: [proposeAiDiscoverRows] only offers a preset whose slice is
 * already big enough. Picking the same preset by hand in the row editor can, and that is the hole
 * this closes.
 *
 * The phase-6 presets are exempt because they remain coherent with no seeds — "suggest lesser-known
 * titles" is still a real question, just an impersonal one.
 */
fun aiRowLacksItsHistorySlice(preset: AiDiscoverPreset, seedCount: Int): Boolean =
    preset.isProposable && seedCount <= 0

/** Why [proposeAiDiscoverRows] came back empty. Each wants a different sentence. */
enum class AiProposalEmptyReason {
    /** Nothing watched recently enough, or committed to hard enough, to ask a question about. */
    NotEnoughHistory,

    /** Both proposable presets are already saved rows. Nothing is wrong; there is nothing left. */
    AllAdded,

    /** The AI row limit is reached, so there is nowhere to put an accepted proposal. */
    RowLimitReached,
}

/**
 * The reason there is nothing to offer, for the line shown in place of the offers.
 *
 * Worth its own function because the three cases are indistinguishable from an empty list and want
 * opposite responses from the user: watch something, delete a row, or nothing at all. Telling
 * somebody who has accepted both proposals that they have "not enough watch history" is wrong on
 * its face and invites them to go looking for a fault.
 *
 * Only meaningful when the proposal list is empty; [proposeAiDiscoverRows] returns empty for exactly
 * these three reasons and no other.
 */
fun aiProposalEmptyReason(existing: List<AiDiscoverRow>): AiProposalEmptyReason = when {
    existing.size >= AI_DISCOVER_ROW_LIMIT -> AiProposalEmptyReason.RowLimitReached
    AiDiscoverPreset.entries.filter { it.isProposable }
        .all { preset -> existing.any { it.preset == preset } } -> AiProposalEmptyReason.AllAdded

    else -> AiProposalEmptyReason.NotEnoughHistory
}
