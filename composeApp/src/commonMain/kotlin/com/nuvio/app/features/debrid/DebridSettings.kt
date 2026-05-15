package com.nuvio.app.features.debrid

data class DebridSettings(
    val enabled: Boolean = false,
    val torboxApiKey: String = "",
    val realDebridApiKey: String = "",
    val preResolveLimit: Int = 0,
    val streamNameTemplate: String = DebridStreamFormatterDefaults.NAME_TEMPLATE,
    val streamDescriptionTemplate: String = DebridStreamFormatterDefaults.DESCRIPTION_TEMPLATE,
) {
    val hasAnyApiKey: Boolean
        get() = DebridProviders.configuredServices(this).isNotEmpty()
}

internal const val DEBRID_PRE_RESOLVE_DEFAULT_LIMIT = 2
internal const val DEBRID_PRE_RESOLVE_MAX_LIMIT = 5

internal fun normalizeDebridPreResolveLimit(value: Int): Int =
    value.coerceIn(0, DEBRID_PRE_RESOLVE_MAX_LIMIT)
