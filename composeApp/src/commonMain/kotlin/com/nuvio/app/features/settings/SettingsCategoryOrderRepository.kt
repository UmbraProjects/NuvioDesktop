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

    fun moveByIndex(fromIndex: Int, toIndex: Int, visiblePages: List<String>) {
        ensureLoaded()
        if (fromIndex !in visiblePages.indices || toIndex !in visiblePages.indices || fromIndex == toIndex) return
        val next = visiblePages.toMutableList()
        next.add(toIndex, next.removeAt(fromIndex))
        _order.value = next
        persist()
    }

    private fun persist() {
        SettingsCategoryOrderStorage.savePayload(
            json.encodeToString(StoredSettingsCategoryOrderPayload(pages = _order.value)),
        )
    }
}
