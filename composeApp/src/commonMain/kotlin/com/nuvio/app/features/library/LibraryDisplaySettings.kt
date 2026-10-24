package com.nuvio.app.features.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class LibraryLayoutMode { SHELVES, GRID }

enum class LibrarySortOption {
    DEFAULT, ADDED_DESC, ADDED_ASC, TITLE_ASC, TITLE_DESC,
    // From the Nuvio Tracker's library: these read TMDB details (LibraryTitleFactsRepository).
    POPULAR, TOP_RATED, MOST_VOTED, NEWEST, OLDEST,
}

data class LibraryDisplaySettingsUiState(
    val layoutMode: LibraryLayoutMode = LibraryLayoutMode.SHELVES,
    val sortOption: LibrarySortOption = LibrarySortOption.DEFAULT,
    val filters: LibraryBrowseFilters = LibraryBrowseFilters(),
)

object LibraryDisplaySettingsRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _uiState = MutableStateFlow(LibraryDisplaySettingsUiState())
    val uiState: StateFlow<LibraryDisplaySettingsUiState> = _uiState.asStateFlow()
    private var loaded = false

    fun ensureLoaded() { if (!loaded) load() }
    fun onProfileChanged() = load()
    fun clearLocalState() { loaded = false; _uiState.value = LibraryDisplaySettingsUiState() }

    fun setLayoutMode(value: LibraryLayoutMode) = update { it.copy(layoutMode = value) }
    fun setSortOption(value: LibrarySortOption) = update { it.copy(sortOption = value) }
    fun updateFilters(transform: (LibraryBrowseFilters) -> LibraryBrowseFilters) =
        update { it.copy(filters = transform(it.filters)) }

    private fun update(transform: (LibraryDisplaySettingsUiState) -> LibraryDisplaySettingsUiState) {
        ensureLoaded()
        val next = transform(_uiState.value)
        if (next == _uiState.value) return
        _uiState.value = next
        LibraryDisplaySettingsStorage.savePayload(json.encodeToString(next.toStored()))
    }

    private fun load() {
        loaded = true
        val stored = LibraryDisplaySettingsStorage.loadPayload()?.takeIf(String::isNotBlank)?.let {
            runCatching { json.decodeFromString<StoredLibraryDisplaySettings>(it) }.getOrNull()
        }
        _uiState.value = LibraryDisplaySettingsUiState(
            layoutMode = LibraryLayoutMode.SHELVES,
            sortOption = stored?.sortOption?.let { name -> LibrarySortOption.entries.firstOrNull { it.name == name } }
                ?: LibrarySortOption.DEFAULT,
            filters = stored?.filters ?: LibraryBrowseFilters(),
        )
    }
}

@Serializable
private data class StoredLibraryDisplaySettings(
    val layoutMode: String,
    val sortOption: String,
    val filters: LibraryBrowseFilters = LibraryBrowseFilters(),
)

private fun LibraryDisplaySettingsUiState.toStored() =
    StoredLibraryDisplaySettings(layoutMode.name, sortOption.name, filters)

internal fun effectiveLibrarySortOption(
    option: LibrarySortOption,
    sourceMode: LibrarySourceMode,
): LibrarySortOption = if (option == LibrarySortOption.DEFAULT && sourceMode == LibrarySourceMode.LOCAL) {
    LibrarySortOption.ADDED_DESC
} else option

internal fun sortLibraryItems(
    items: List<LibraryItem>,
    option: LibrarySortOption,
    sourceMode: LibrarySourceMode,
    facts: Map<String, LibraryTitleFacts> = emptyMap(),
): List<LibraryItem> = when (effectiveLibrarySortOption(option, sourceMode)) {
    LibrarySortOption.DEFAULT -> items.sortedWith(compareBy<LibraryItem> { it.traktRank ?: Int.MAX_VALUE }
        .thenByDescending { it.savedAtEpochMs }.thenBy { it.sortTitle() }.thenBy { it.id })
    LibrarySortOption.ADDED_DESC -> items.sortedWith(compareByDescending<LibraryItem> { it.savedAtEpochMs }
        .thenBy { it.sortTitle() }.thenBy { it.id })
    LibrarySortOption.ADDED_ASC -> items.sortedWith(compareBy<LibraryItem> { it.savedAtEpochMs }
        .thenBy { it.sortTitle() }.thenBy { it.id })
    LibrarySortOption.TITLE_ASC -> items.sortedWith(compareBy<LibraryItem> { it.sortTitle() }.thenBy { it.id })
    LibrarySortOption.TITLE_DESC -> items.sortedWith(compareByDescending<LibraryItem> { it.sortTitle() }.thenBy { it.id })
    // Unknown values last, whichever way the sort runs — as the tracker's `nulls last`.
    LibrarySortOption.POPULAR -> items.sortedByFact(facts, descending = true) { it.popularity }
    LibrarySortOption.TOP_RATED -> items.sortedWith(
        compareBy<LibraryItem> { facts.factFor(it)?.voteAverage == null }
            .thenByDescending { facts.factFor(it)?.voteAverage }
            .thenByDescending { facts.factFor(it)?.voteCount }
            .thenBy { it.sortTitle() },
    )
    LibrarySortOption.MOST_VOTED -> items.sortedByFact(facts, descending = true) { it.voteCount }
    LibrarySortOption.NEWEST -> items.sortedByFact(facts, descending = true) { it.releaseDate }
    LibrarySortOption.OLDEST -> items.sortedByFact(facts, descending = false) { it.releaseDate }
}

private fun Map<String, LibraryTitleFacts>.factFor(item: LibraryItem): LibraryTitleFacts? =
    this[libraryItemKey(item.id, item.type)]?.takeUnless { it.unresolved }

private fun <T : Comparable<T>> List<LibraryItem>.sortedByFact(
    facts: Map<String, LibraryTitleFacts>,
    descending: Boolean,
    value: (LibraryTitleFacts) -> T?,
): List<LibraryItem> {
    val byValue = compareBy<LibraryItem, T?>(nullsLast()) { item -> facts.factFor(item)?.let(value) }
    val ordered = if (descending) {
        compareBy<LibraryItem> { item -> facts.factFor(item)?.let(value) == null }
            .thenByDescending { item -> facts.factFor(item)?.let(value) }
    } else {
        byValue
    }
    return sortedWith(ordered.thenBy { it.sortTitle() }.thenBy { it.id })
}

internal fun sortLibrarySections(
    sections: List<LibrarySection>,
    option: LibrarySortOption,
    sourceMode: LibrarySourceMode,
    facts: Map<String, LibraryTitleFacts> = emptyMap(),
): List<LibrarySection> = sections.map { it.copy(items = sortLibraryItems(it.items, option, sourceMode, facts)) }

internal data class LibraryGridEntry(val item: LibraryItem, val section: LibrarySection)

internal fun libraryGridEntries(
    sections: List<LibrarySection>,
    option: LibrarySortOption,
    sourceMode: LibrarySourceMode,
): List<LibraryGridEntry> {
    val unique = linkedMapOf<String, LibraryGridEntry>()
    sections.forEach { section -> section.items.forEach { item ->
        unique.putIfAbsent("${item.type.trim().lowercase()}:${item.id.trim()}", LibraryGridEntry(item, section))
    } }
    val byKey = unique.values.associateBy { "${it.item.type.trim().lowercase()}:${it.item.id.trim()}" }
    return sortLibraryItems(unique.values.map { it.item }, option, sourceMode).mapNotNull {
        byKey["${it.type.trim().lowercase()}:${it.id.trim()}"]
    }
}

private val LeadingArticle = Regex("^(the|an|a)\\s+", RegexOption.IGNORE_CASE)
private fun LibraryItem.sortTitle(): String = name.ifBlank { id }.trim().replace(LeadingArticle, "").lowercase()
