package com.nuvio.app.features.home

import co.touchlab.kermit.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val heroDiscoveryConfigJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private val heroDiscoveryConfigLog = Logger.withTag("HeroDiscoveryConfig")

internal data class HeroDiscoveryConfig(
    val studios: Map<String, String> = emptyMap(),
    val directors: Map<String, String> = emptyMap(),
)

@Serializable
private data class HeroDiscoveryConfigPayload(
    val version: Int = 1,
    val mergeWithDefaults: Boolean = true,
    val studios: Map<String, String> = emptyMap(),
    val directors: Map<String, String> = emptyMap(),
)

internal expect object HeroDiscoveryConfigStorage {
    fun loadDefaultConfigText(): String?
    fun loadUserConfigText(): String?
}

internal object HeroDiscoveryConfigRepository {
    // hero_discovery.json (bundled + user override) can't change mid-session — there's no in-app
    // editor for it, only manual file edits that require a restart to pick up — so it's safe to
    // load once and reuse, instead of re-reading + re-parsing both files on every hero item.
    @Volatile
    private var cached: HeroDiscoveryConfig? = null

    fun snapshot(): HeroDiscoveryConfig = cached ?: load().also { cached = it }

    private fun load(): HeroDiscoveryConfig {
        val defaultPayload = HeroDiscoveryConfigStorage.loadDefaultConfigText()
            ?.decodeHeroDiscoveryConfig("default")
            ?: HeroDiscoveryConfigPayload()
        val userPayload = HeroDiscoveryConfigStorage.loadUserConfigText()
            ?.decodeHeroDiscoveryConfig("user")

        val merged = when {
            userPayload == null -> defaultPayload
            userPayload.mergeWithDefaults -> HeroDiscoveryConfigPayload(
                studios = defaultPayload.studios + userPayload.studios,
                directors = defaultPayload.directors + userPayload.directors,
            )
            else -> userPayload
        }

        return HeroDiscoveryConfig(
            studios = merged.studios.cleanDiscoveryMap(),
            directors = merged.directors.cleanDiscoveryMap(),
        )
    }

    private fun String.decodeHeroDiscoveryConfig(source: String): HeroDiscoveryConfigPayload? =
        runCatching {
            heroDiscoveryConfigJson.decodeFromString<HeroDiscoveryConfigPayload>(this)
        }.onFailure { error ->
            heroDiscoveryConfigLog.w { "Failed to parse $source hero_discovery.json: ${error.message}" }
        }.getOrNull()

    private fun Map<String, String>.cleanDiscoveryMap(): Map<String, String> =
        mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim().takeIf(String::isNotBlank) ?: normalizedKey
            if (normalizedKey.isBlank()) null else normalizedKey to normalizedValue
        }.toMap()
}
