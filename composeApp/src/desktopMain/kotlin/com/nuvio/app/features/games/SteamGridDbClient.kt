package com.nuvio.app.features.games

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class SteamGridDbClient : AutoCloseable {
    private val http = HttpClient(CIO) { expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun logoFor(title: String, apiKey: String): String? {
        return logosFor(title, apiKey).firstOrNull()?.url
    }

    suspend fun logosFor(title: String, apiKey: String): List<LogoCandidate> {
        require(apiKey.isNotBlank()) { "Enter a SteamGridDB API key in Settings." }
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return emptyList()

        val search = get<SearchGame>(
            path = "search/autocomplete/${cleanTitle.encodeURLPathPart()}",
            apiKey = apiKey,
        )
        val game = selectGame(search, cleanTitle) ?: return emptyList()
        val logos = get<SteamGridDbAsset>("logos/game/${game.id}", apiKey)
        return logos
            .asSequence()
            .filter { it.url.startsWith("https://") }
            .distinctBy(SteamGridDbAsset::url)
            .sortedWith(
                compareByDescending<SteamGridDbAsset> { it.language.isPreferredEnglish() }
                    .thenByDescending { it.score }
                    .thenByDescending { it.width.toLong() * it.height },
            )
            .map {
                LogoCandidate(
                    url = it.url,
                    width = it.width,
                    height = it.height,
                    score = it.score,
                    language = it.language,
                    style = it.style,
                )
            }
            .toList()
    }

    /**
     * Wide "hero" art for the library backdrop.
     *
     * SteamGridDB curates heroes at exactly the shape the backdrop wants (1920x620 and up), unlike
     * IGDB artworks and screenshots, which are arbitrary promo stills that have to be cropped into
     * it. Offered alongside them in the picker rather than replacing them — nothing here is chosen
     * automatically.
     */
    suspend fun heroesFor(title: String, apiKey: String): List<ArtworkCandidate> {
        require(apiKey.isNotBlank()) { "Enter a SteamGridDB API key in Settings." }
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return emptyList()

        val search = get<SearchGame>(
            path = "search/autocomplete/${cleanTitle.encodeURLPathPart()}",
            apiKey = apiKey,
        )
        val game = selectGame(search, cleanTitle) ?: return emptyList()
        return get<SteamGridDbAsset>("heroes/game/${game.id}", apiKey)
            .asSequence()
            .filter { it.url.startsWith("https://") }
            .distinctBy(SteamGridDbAsset::url)
            .sortedWith(
                compareByDescending<SteamGridDbAsset> { it.score }
                    .thenByDescending { it.width.toLong() * it.height },
            )
            .map {
                ArtworkCandidate(
                    url = it.url,
                    width = it.width,
                    height = it.height,
                    source = ArtworkSource.STEAMGRIDDB,
                )
            }
            .toList()
    }

    private suspend inline fun <reified T> get(path: String, apiKey: String): List<T> {
        val response = http.get("https://www.steamgriddb.com/api/v2/$path") {
            header(HttpHeaders.Authorization, "Bearer ${apiKey.trim()}")
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw SteamGridDbException(
                "SteamGridDB returned ${response.status.value}: ${body.replace(Regex("\\s+"), " ").trim().take(240)}",
            )
        }
        return json.decodeFromString<ApiResponse<T>>(body).data
    }

    override fun close() = http.close()
}

class SteamGridDbException(message: String) : Exception(message)

@Serializable
private data class ApiResponse<T>(
    val success: Boolean = false,
    val data: List<T> = emptyList(),
)

@Serializable
private data class SearchGame(
    val id: Long,
    val name: String,
    val verified: Boolean = false,
)

@Serializable
private data class SteamGridDbAsset(
    val url: String = "",
    val score: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val language: String? = null,
    val style: String? = null,
)

private fun selectGame(games: List<SearchGame>, requestedTitle: String): SearchGame? {
    val requested = requestedTitle.normalizedTitle()
    return games
        .filter { it.name.normalizedTitle() == requested }
        .maxByOrNull { if (it.verified) 1 else 0 }
}

private fun String.normalizedTitle(): String =
    lowercase().filter(Char::isLetterOrDigit)

private fun String?.isPreferredEnglish(): Boolean =
    this?.trim()?.lowercase() in setOf("en", "eng", "english")
