package com.nuvio.app.features.games

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

class IgdbClient : AutoCloseable {
    private val http = HttpClient(CIO) {
        expectSuccess = false
    }
    private val json = Json { ignoreUnknownKeys = true }
    private var token: CachedToken? = null

    suspend fun search(query: String, settings: GameLibrarySettings): List<IgdbGame> {
        require(settings.igdbClientId.isNotBlank()) { "Enter an IGDB Client ID in Settings." }
        require(settings.igdbClientSecret.isNotBlank()) { "Enter an IGDB Client Secret in Settings." }
        val search = query.trim()
        if (search.isEmpty()) return emptyList()
        val accessToken = accessToken(settings)
        val escaped = search.replace("\\", "\\\\").replace("\"", "\\\"")
        val requestBody = """
            fields id,name,summary,storyline,first_release_date,total_rating,rating,aggregated_rating,
                cover.image_id,cover.width,cover.height,
                artworks.image_id,artworks.width,artworks.height,artworks.alpha_channel,artworks.image_type,
                screenshots.image_id,screenshots.width,screenshots.height,
                genres.name,platforms.name;
            search "$escaped";
            limit 20;
        """.trimIndent()
        val response = http.post("https://api.igdb.com/v4/games") {
            header("Client-ID", settings.igdbClientId.trim())
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Text.Plain)
            setBody(requestBody)
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            if (response.status.value == 401) token = null
            throw IgdbException("IGDB returned ${response.status.value}: ${friendlyApiError(body)}")
        }
        return json.decodeFromString<List<IgdbGameDto>>(body).map(IgdbGameDto::toDomain)
    }

    suspend fun game(gameId: Long, settings: GameLibrarySettings): IgdbGame? {
        require(settings.igdbClientId.isNotBlank()) { "Enter an IGDB Client ID in Settings." }
        require(settings.igdbClientSecret.isNotBlank()) { "Enter an IGDB Client Secret in Settings." }
        val accessToken = accessToken(settings)
        val requestBody = """
            fields id,name,summary,storyline,first_release_date,total_rating,rating,aggregated_rating,
                cover.image_id,cover.width,cover.height,
                artworks.image_id,artworks.width,artworks.height,artworks.alpha_channel,artworks.image_type,
                screenshots.image_id,screenshots.width,screenshots.height,
                genres.name,platforms.name;
            where id = $gameId;
            limit 1;
        """.trimIndent()
        val response = http.post("https://api.igdb.com/v4/games") {
            header("Client-ID", settings.igdbClientId.trim())
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Text.Plain)
            setBody(requestBody)
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            if (response.status.value == 401) token = null
            throw IgdbException("IGDB returned ${response.status.value}: ${friendlyApiError(body)}")
        }
        return json.decodeFromString<List<IgdbGameDto>>(body).firstOrNull()?.toDomain()
    }

    private suspend fun accessToken(settings: GameLibrarySettings): String {
        val now = System.currentTimeMillis()
        token?.takeIf {
            it.clientId == settings.igdbClientId.trim() && it.expiresAtMillis > now + 60_000L
        }?.let { return it.value }

        val response = http.post("https://id.twitch.tv/oauth2/token") {
            url {
                parameters.append("client_id", settings.igdbClientId.trim())
                parameters.append("client_secret", settings.igdbClientSecret.trim())
                parameters.append("grant_type", "client_credentials")
            }
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IgdbException("Twitch authentication failed (${response.status.value}): ${friendlyApiError(body)}")
        }
        val parsed = json.decodeFromString<TokenResponse>(body)
        token = CachedToken(
            value = parsed.accessToken,
            clientId = settings.igdbClientId.trim(),
            expiresAtMillis = now + parsed.expiresIn.coerceAtLeast(60L) * 1_000L,
        )
        return parsed.accessToken
    }

    override fun close() = http.close()
}

class IgdbException(message: String) : Exception(message)

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long,
)

private data class CachedToken(
    val value: String,
    val clientId: String,
    val expiresAtMillis: Long,
)

@Serializable
private data class IgdbNamedDto(val name: String = "")

@Serializable
private data class IgdbImageDto(
    @SerialName("image_id") val imageId: String = "",
    val width: Int = 0,
    val height: Int = 0,
    @SerialName("alpha_channel") val alphaChannel: Boolean = false,
    @SerialName("image_type") val imageType: Int? = null,
) {
    val aspectRatio: Double
        get() = if (height > 0) width.toDouble() / height.toDouble() else 0.0
}

@Serializable
private data class IgdbGameDto(
    val id: Long,
    val name: String,
    val summary: String? = null,
    val storyline: String? = null,
    @SerialName("first_release_date") val firstReleaseDate: Long? = null,
    @SerialName("total_rating") val totalRating: Double? = null,
    val rating: Double? = null,
    @SerialName("aggregated_rating") val aggregatedRating: Double? = null,
    val cover: IgdbImageDto? = null,
    val artworks: List<IgdbImageDto> = emptyList(),
    val screenshots: List<IgdbImageDto> = emptyList(),
    val genres: List<IgdbNamedDto> = emptyList(),
    val platforms: List<IgdbNamedDto> = emptyList(),
) {
    fun toDomain(): IgdbGame {
        val artworkBackdrops = artworks
            .asSequence()
            .filter { it.imageId.isNotBlank() && !it.alphaChannel && it.aspectRatio >= 1.25 }
            .sortedByDescending { it.width.toLong() * it.height }
            .map { it.toArtworkCandidate(ArtworkSource.ARTWORK) }
            .toList()
        val screenshotBackdrops = screenshots
            .asSequence()
            .filter { it.imageId.isNotBlank() && it.aspectRatio >= 1.25 }
            .sortedByDescending { it.width.toLong() * it.height }
            .map { it.toArtworkCandidate(ArtworkSource.SCREENSHOT) }
            .toList()
        val backdropCandidates = (artworkBackdrops + screenshotBackdrops).distinctBy(ArtworkCandidate::url)
        return IgdbGame(
            id = id,
            title = name,
            coverUrl = cover?.imageId?.takeIf(String::isNotBlank)?.let { imageUrl(it, "1080p") },
            backdropUrl = backdropCandidates.firstOrNull()?.url,
            logoUrl = null,
            summary = summary?.takeIf(String::isNotBlank) ?: storyline?.takeIf(String::isNotBlank),
            releaseDateEpochSeconds = firstReleaseDate,
            genres = genres.mapNotNull { it.name.trim().takeIf(String::isNotBlank) }.distinct(),
            platforms = platforms.mapNotNull { it.name.trim().takeIf(String::isNotBlank) }.distinct(),
            rating = (totalRating ?: aggregatedRating ?: rating)
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?.let { (it * 10.0).roundToInt() / 10.0 },
            backdrops = backdropCandidates,
        )
    }
}

private fun IgdbImageDto.toArtworkCandidate(source: ArtworkSource): ArtworkCandidate = ArtworkCandidate(
    url = imageUrl(imageId, "1080p"),
    width = width,
    height = height,
    source = source,
)

private fun imageUrl(imageId: String, size: String, extension: String = "jpg"): String =
    "https://images.igdb.com/igdb/image/upload/t_$size/$imageId.$extension"

/**
 * Upgrades persisted IGDB thumbnails without requiring the game to be refreshed or re-added.
 * Non-IGDB artwork is returned untouched.
 */
internal fun highResolutionIgdbCoverUrl(url: String?): String? {
    if (url.isNullOrBlank()) return url
    val variantPrefix = "/igdb/image/upload/t_"
    val variantStart = url.indexOf(variantPrefix)
    if (variantStart < 0) return url
    val sizeStart = variantStart + variantPrefix.length
    val sizeEnd = url.indexOf('/', startIndex = sizeStart)
    if (sizeEnd < 0) return url
    return url.substring(0, sizeStart) + "1080p" + url.substring(sizeEnd)
}

private fun friendlyApiError(body: String): String {
    val compact = body.replace(Regex("\\s+"), " ").trim()
    return compact.take(280).ifBlank { "No response body" }
}
