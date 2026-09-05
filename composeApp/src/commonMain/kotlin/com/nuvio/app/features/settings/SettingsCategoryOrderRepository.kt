package com.nuvio.app.features.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class StoredSettingsCategoryOrderPayload(
    val pages: List<String> = emptyList(),
)

internal object SettingsCategoryOrderRepository {
    private val json = Json { ignoreUnknownKeys = true }

    private val _order = MutableStateFlow<List<String>>(emptyList())
    val order: StateFlow<List<String>> = _order.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true

        val payload = SettingsCategoryOrderStorage.loadPayload()
        val parsed = payload?.let {
            runCatching {
                json.decodeFromString<StoredSettingsCategoryOrderPayload>(it)
            }.getOrNull()
        }
        _order.value = parsed?.pages.orEmpty()
    }

    fun onProfileChanged() {
        hasLoaded = false
        _order.value = emptyList()
        ensureLoaded()
    }

    /**
     * Reorders the sidebar. [visiblePages] is what the user can actually drag; [allPages] is every
     * category including the ones hidden via [SettingsHiddenCategoriesRepository].
     *
     * Hidden categories hold their slot in the stored order and the visible slots take the new
     * sequence, so dragging while something is hidden cannot quietly drop it to the bottom of the
     * list the moment it is switched back on.
     */
    fun moveByIndex(
        fromIndex: Int,
        toIndex: Int,
        visiblePages: List<String>,
        allPages: List<String> = visiblePages,
    ) {
        ensureLoaded()
        if (fromIndex !in visiblePages.indices || toIndex !in visiblePages.indices || fromIndex == toIndex) return
        val reordered = ArrayDeque(visiblePages)
        reordered.add(toIndex, reordered.removeAt(fromIndex))
        val visible = visiblePages.toSet()
        val next = buildList {
            allPages.forEach { page ->
                if (page in visible) {
                    reordered.removeFirstOrNull()?.let(::add)
                } else {
                    add(page)
                }
            }
            // Anything the caller left out of allPages still has to survive the write.
            addAll(reordered)
        }
        _order.value = next
        persist()
    }

    private fun persist() {
        SettingsCategoryOrderStorage.savePayload(
            json.encodeToString(StoredSettingsCategoryOrderPayload(pages = _order.value)),
        )
    }
}
