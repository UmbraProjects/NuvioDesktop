package com.nuvio.app.features.streams

import kotlinx.serialization.json.JsonObject

internal expect object StreamBadgeSettingsStorage {
    fun loadStreamBadgeRules(): String?
    fun saveStreamBadgeRules(rules: String)
    fun loadShowFileSizeBadges(): Boolean?
    fun saveShowFileSizeBadges(enabled: Boolean)
    fun loadShowAddonLogo(): Boolean?
    fun saveShowAddonLogo(enabled: Boolean)
    fun loadStreamBadgePlacement(): String?
    fun saveStreamBadgePlacement(placement: String)
    fun loadStreamListSortOrder(): String?
    fun saveStreamListSortOrder(order: String)
    fun loadStreamListCachedFirst(): Boolean?
    fun saveStreamListCachedFirst(enabled: Boolean)
    fun loadStreamListTwoColumns(): Boolean?
    fun saveStreamListTwoColumns(enabled: Boolean)
    /** The user-resized modal picker, "widthFraction,heightFraction" of the window; null = default. */
    fun loadStreamPickerModalSize(): String?
    fun saveStreamPickerModalSize(size: String?)
    fun loadLegacyDebridStreamBadgeRules(): String?
    fun clearLegacyDebridStreamBadgeRules()
    fun exportToSyncPayload(): JsonObject
    fun replaceFromSyncPayload(payload: JsonObject)
}
