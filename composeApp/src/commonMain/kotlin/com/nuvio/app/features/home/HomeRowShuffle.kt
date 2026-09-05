package com.nuvio.app.features.home

import co.touchlab.kermit.Logger
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.discover.DISCOVER_PLACEHOLDER_KEY_PREFIX
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * How deep a paginating row is pulled before its items are shuffled.
 *
 * A row renders at most a preview slice at once, so shuffling only what is already loaded would
 * re-deal the same faces: the user sees a reorder, not new content. Pulling a few pages first is
 * what makes the button feel like "show me something else".
 */
const val HOME_ROW_SHUFFLE_POOL_TARGET = 72

/** Upper bound on pages fetched per shuffle, so a deep catalog cannot turn one click into a crawl. */
const val HOME_ROW_SHUFFLE_MAX_PAGES = 3

/** Rows shorter than this have nothing to shuffle worth animating. */
const val HOME_ROW_SHUFFLE_MIN_ITEMS = 4

/**
 * A row's shuffled order, held for this session only.
 *
 * Deliberately *not* persisted and deliberately not written through
 * [HomeCatalogSettingsRepository]: that repository owns the user's saved row configuration and
 * pushes part of it to cross-device sync, so routing a shuffle through it would overwrite a
 * deliberate arrangement with a random one and propagate it to their other clients.
 *
 * [generation] increments on every re-roll and drives the deal animation; the UI has no other way
 * to tell "these are the same items in a new order" from an ordinary recomposition.
 */
data class HomeRowShuffleOrder(
    val generation: Int,
    val keys: List<String>,
)

/**
 * Reorders [items] to match [order], leaving anything the order does not mention at the end in its
 * original catalog order.
 *
 * The tail matters: infinite scroll keeps appending pages after a shuffle is rolled, and a refresh
 * can widen the row underneath it. Re-rolling to absorb those would churn the posters the user is
 * currently looking at, so late arrivals queue up behind the shuffled block instead.
 */
fun applyHomeRowShuffle(
    items: List<MetaPreview>,
    order: HomeRowShuffleOrder?,
): List<MetaPreview> {
    if (order == null || order.keys.isEmpty() || items.isEmpty()) return items
    // Two entries in one row can legitimately share a stableKey — an addon listing the same title
    // twice, which is exactly what withDuplicateSafeLazyKeys exists to survive. Consuming from
    // per-key index queues keeps both copies; a key -> item map would silently drop one and shrink
    // the row every time it was shuffled.
    val pending = HashMap<String, ArrayDeque<Int>>(items.size)
    items.forEachIndexed { index, item ->
        pending.getOrPut(item.stableKey()) { ArrayDeque() }.addLast(index)
    }
    val taken = BooleanArray(items.size)
    val reordered = ArrayList<MetaPreview>(items.size)
    order.keys.forEach { key ->
        val index = pending[key]?.removeFirstOrNull() ?: return@forEach
        taken[index] = true
        reordered += items[index]
    }
    items.forEachIndexed { index, item -> if (!taken[index]) reordered += item }
    return reordered
}

/** The row's items in their current shuffled order, or untouched when it has never been shuffled. */
fun HomeCatalogSection.shuffled(order: HomeRowShuffleOrder?): List<MetaPreview> =
    applyHomeRowShuffle(items, order)

/**
 * Whether a shuffle button is worth offering on this row.
 *
 * The Discover browser row is excluded because its header is already the catalog/genre picker —
 * there is no room, and "shuffle" there would compete with the control that is the point of the row.
 */
fun HomeCatalogSection.canShuffleRow(): Boolean =
    items.size >= HOME_ROW_SHUFFLE_MIN_ITEMS &&
        key != DISCOVER_BROWSER_ROW_KEY &&
        !key.startsWith(DISCOVER_PLACEHOLDER_KEY_PREFIX)

/** True when the row can supply further pages, i.e. deepening the pool is worth attempting. */
internal fun HomeCatalogSection.canDeepenForShuffle(): Boolean =
    target is CatalogTarget.Addon && nextSkip != null

/**
 * Builds the key order for a shuffle, retrying until the first visible poster actually changes.
 *
 * A permutation that happens to leave the head alone reads as a dead button, and on a short row
 * that is not rare. [attempts] caps the retries so a row whose entries all share one key still
 * terminates.
 */
internal fun rollHomeRowShuffleKeys(
    items: List<MetaPreview>,
    previousHeadKey: String?,
    random: Random,
    attempts: Int = 4,
): List<String> {
    val keys = items.map { it.stableKey() }
    if (keys.size < 2) return keys
    var rolled = keys
    repeat(attempts) {
        rolled = keys.shuffled(random)
        if (rolled.firstOrNull() != previousHeadKey) return rolled
    }
    return rolled
}

/**
 * Session-scoped shuffle state for home rows: which rows are shuffled, in what order, and which are
 * mid-deal while their pool is being deepened.
 */
object HomeRowShuffleState {
    private val log = Logger.withTag("HomeRowShuffle")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _orders = MutableStateFlow<Map<String, HomeRowShuffleOrder>>(emptyMap())
    val orders: StateFlow<Map<String, HomeRowShuffleOrder>> = _orders.asStateFlow()

    private val _shuffling = MutableStateFlow<Set<String>>(emptySet())
    val shuffling: StateFlow<Set<String>> = _shuffling.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    fun orderFor(sectionKey: String): HomeRowShuffleOrder? = _orders.value[sectionKey]

    /**
     * Deepens the row (paginating rows only) and then re-deals it.
     *
     * Idempotent while a shuffle is running: the button stays live and pressable during the pool
     * fetch, which on a slow addon is several seconds, so repeat presses have to be absorbed rather
     * than queued into a stack of re-rolls.
     */
    fun shuffle(sectionKey: String) {
        if (jobs[sectionKey]?.isActive == true) {
            log.d { "shuffle skipped ($sectionKey): already dealing" }
            return
        }
        val section = HomeRepository.uiState.value.sections.firstOrNull { it.key == sectionKey }
        if (section == null) {
            log.d { "shuffle skipped ($sectionKey): section is not in the published state" }
            return
        }
        if (!section.canShuffleRow()) {
            log.d { "shuffle skipped ($sectionKey): row is too short or not shuffleable" }
            return
        }
        _shuffling.update { it + sectionKey }
        jobs[sectionKey] = scope.launch {
            try {
                val pooled = HomeRepository.deepenRowForShuffle(
                    sectionKey = sectionKey,
                    targetItems = HOME_ROW_SHUFFLE_POOL_TARGET,
                    maxPages = HOME_ROW_SHUFFLE_MAX_PAGES,
                )
                val current = HomeRepository.uiState.value.sections
                    .firstOrNull { it.key == sectionKey }
                if (current == null) {
                    log.i { "shuffle dropped ($sectionKey): section vanished while deepening" }
                    return@launch
                }
                val previous = _orders.value[sectionKey]
                val previousHeadKey = applyHomeRowShuffle(current.items, previous)
                    .firstOrNull()
                    ?.stableKey()
                val keys = rollHomeRowShuffleKeys(
                    items = current.items,
                    previousHeadKey = previousHeadKey,
                    random = Random.Default,
                )
                val generation = (previous?.generation ?: 0) + 1
                _orders.update {
                    it + (sectionKey to HomeRowShuffleOrder(generation = generation, keys = keys))
                }
                log.i {
                    "shuffle done ($sectionKey) gen=$generation pool=$pooled items=${current.items.size}"
                }
            } finally {
                _shuffling.update { it - sectionKey }
            }
        }
    }

    /** Drops the shuffle for one row, restoring the catalog's own order. */
    fun clear(sectionKey: String) {
        jobs.remove(sectionKey)?.cancel()
        _orders.update { it - sectionKey }
        _shuffling.update { it - sectionKey }
    }

    /**
     * Drops shuffles for rows that are no longer in the active catalog set.
     *
     * Called from the same place [HomeRepository] prunes its paging state: an order pinned to a
     * catalog the user has since removed would come back with it, in an arrangement they rolled in
     * some earlier session and have no reason to remember.
     */
    fun retain(sectionKeys: Set<String>) {
        (jobs.keys - sectionKeys).toList().forEach { key -> jobs.remove(key)?.cancel() }
        _orders.update { orders -> orders.filterKeys(sectionKeys::contains) }
        _shuffling.update { keys -> keys.intersect(sectionKeys) }
    }

    fun clearAll() {
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        _orders.value = emptyMap()
        _shuffling.value = emptySet()
    }
}
