package com.nuvio.app.features.player.skip

import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.addons.httpPostJsonWithHeaders
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.serialization.json.Json

internal object SkipIntroApi {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val ANISKIP_BASE = "https://api.aniskip.com/v2/"
    private const val ARM_BASE = "https://arm.haglund.dev/api/v2/"
    private const val ANIMESKIP_BASE = "https://api.anime-skip.com/"

    // api.skipdb.tv only routes /api/segments; the rest of the API (dump, titles, keys) lives on
    // the apex host, so use that one throughout.
    private const val SKIPDB_BASE = "https://skipdb.tv/api/"

    // Only "conservative" reports the runtime offset without applying it. "greedy" shifts the
    // timings by that offset, which assumes the whole runtime difference sits ahead of the
    // segment — wrong whenever a release simply carries longer credits. Reads stay unshifted and
    // out-of-range answers are dropped instead.
    private const val SKIPDB_ADJUST = "conservative"

    private val ANISKIP_TYPES = listOf("op", "ed", "recap", "mixed-op", "mixed-ed")

    // --- SkipDB ---

    /**
     * Best segment of each kind for one title. Pass a null [season]/[episode] for a movie, and
     * [durationSeconds] whenever the runtime is known — without it SkipDB cannot tell which cut is
     * being played and downgrades every answer to `agnostic`.
     */
    suspend fun getSkipDbSegments(
        imdbId: String,
        season: Int?,
        episode: Int?,
        durationSeconds: Long?,
    ): SkipDbSegmentsResponse? {
        if (imdbId.isBlank()) return null
        val query = buildString {
            append("imdb_id=").append(imdbId)
            if (season != null && episode != null) {
                append("&season=").append(season)
                append("&episode=").append(episode)
            }
            if (durationSeconds != null && durationSeconds > 0L) {
                append("&duration=").append(durationSeconds)
            }
            append("&adjust=").append(SKIPDB_ADJUST)
        }
        return try {
            val text = httpGetText("${SKIPDB_BASE}segments?$query")
            json.decodeFromString<SkipDbSegmentsResponse>(text)
        } catch (_: Exception) {
            null
        }
    }

    // --- IntroDb ---

    suspend fun getIntroDbSegments(
        imdbId: String,
        season: Int,
        episode: Int,
    ): IntroDbSegmentsResponse? {
        val baseUrl = IntroDbConfig.URL.trimEnd('/')
        if (baseUrl.isBlank()) return null
        val url = "$baseUrl/intro?imdb_id=$imdbId&season=$season&episode=$episode"
        return try {
            val text = httpGetText(url)
            json.decodeFromString<IntroDbSegmentsResponse>(text)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun submitIntro(
        apiKey: String,
        request: SubmitIntroRequest,
    ): Boolean {
        val baseUrl = IntroDbConfig.URL.trimEnd('/')
        if (baseUrl.isBlank() || apiKey.isBlank()) return false
        val url = "$baseUrl/submit"
        val body = json.encodeToString(SubmitIntroRequest.serializer(), request)
        val headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json"
        )
        return try {
            val response = com.nuvio.app.features.addons.httpRequestRaw(
                method = "POST",
                url = url,
                headers = headers,
                body = body
            )
            response.status == 200 || response.status == 201
        } catch (_: Exception) {
            false
        }
    }

    suspend fun verifyIntroDbApiKey(apiKey: String): Boolean {
        val baseUrl = IntroDbConfig.URL.trimEnd('/')
        if (baseUrl.isBlank() || apiKey.isBlank()) return false
        val url = "$baseUrl/submit"
        val headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json"
        )
        return try {
            val response = com.nuvio.app.features.addons.httpRequestRaw(
                method = "POST",
                url = url,
                headers = headers,
                body = "{}"
            )
            
            // 400 means Auth passed but payload was empty/invalid -> Key is Valid
            if (response.status == 400) return true
            
            // 200/201 would also mean valid (though unexpected with empty body)
            if (response.status == 200 || response.status == 201) return true
            
            // Explicitly handle auth failures
            if (response.status == 401 || response.status == 403) return false
            
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Contributes one segment. A rejection is as informative as an acceptance here — the server
     * explains overlaps, validation failures and rate limits in the body — so the reply is read on
     * both paths rather than reduced to a success flag.
     */
    suspend fun submitSkipDbSegment(
        apiKey: String,
        request: SkipDbSubmitRequest,
    ): SkipDbSubmitResponse? {
        if (apiKey.isBlank()) return null
        val body = json.encodeToString(SkipDbSubmitRequest.serializer(), request)
        val headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json",
        )
        return try {
            val response = httpRequestRaw(
                method = "POST",
                url = "${SKIPDB_BASE}segments",
                headers = headers,
                body = body,
            )
            runCatching { json.decodeFromString<SkipDbSubmitResponse>(response.body) }
                .getOrNull()
                ?: SkipDbSubmitResponse(
                    error = "HTTP ${response.status}".takeIf { response.status !in 200..299 },
                    status = if (response.status in 200..299) "pending" else null,
                )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Mints a submission key that is not tied to any account. SkipDB allows this so contributors do
     * not have to register; the trade-off is that such keys cannot vote.
     */
    suspend fun createSkipDbAnonymousKey(): String? {
        return try {
            val response = httpRequestRaw(
                method = "POST",
                url = "${SKIPDB_BASE}keys/anonymous",
                headers = mapOf("Content-Type" to "application/json"),
                body = "{}",
            )
            if (response.status !in 200..299) return null
            json.decodeFromString<SkipDbAnonymousKeyResponse>(response.body).key?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    // --- AniSkip ---

    /**
     * AniSkip takes its `types` as a **repeated** query parameter. A single comma-joined value is
     * rejected with HTTP 400, which the catch below turns into a silent "nothing here" — so the
     * comma form does not degrade the answer, it removes AniSkip from every lookup entirely.
     *
     * `episodeLength=0` is AniSkip's wildcard, and it is deliberate. The parameter is matched, not
     * approximated: a runtime a few seconds off a cut AniSkip knows returns no results at all
     * (Monster ep1 answers for 1453s and 1420s, and returns nothing for 1438s). Since our duration
     * comes from whatever release is being played it would miss far more often than it would
     * sharpen, so we take the most-voted timings and let SkipDB be the duration-aware source.
     */
    suspend fun getAniSkipTimes(
        malId: String,
        episode: Int,
    ): AniSkipResponse? {
        val types = ANISKIP_TYPES.joinToString("&") { "types=$it" }
        val url = "${ANISKIP_BASE}skip-times/$malId/$episode?$types&episodeLength=0"
        return try {
            val text = httpGetText(url)
            json.decodeFromString<AniSkipResponse>(text)
        } catch (_: Exception) {
            null
        }
    }

    // --- ARM API (ID resolution) ---

    suspend fun resolveImdbToAll(imdbId: String): List<ArmEntry> {
        val url = "${ARM_BASE}imdb?id=$imdbId&include=myanimelist,anilist,kitsu"
        return try {
            val text = httpGetText(url)
            json.decodeFromString<List<ArmEntry>>(text)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Every id ARM holds for one anime-list entry, in a single request.
     *
     * [source] is an [AnimeIdNamespace.armSource]. Asking for the whole set at once matters: the
     * chain needs the MAL id for AniSkip, the AniList id for Anime-Skip and the IMDb id for
     * SkipDB/IntroDB, and fetching them separately cost three round trips per lookup to answer
     * the same question.
     */
    suspend fun resolveAnimeEntry(source: String, id: String): ArmEntry? {
        if (source.isBlank() || id.isBlank()) return null
        val url = "${ARM_BASE}ids?source=$source&id=$id&include=myanimelist,anilist,kitsu,imdb"
        return try {
            val text = httpGetText(url)
            json.decodeFromString<ArmEntry>(text)
        } catch (_: Exception) {
            null
        }
    }

    // --- Anime-Skip GraphQL ---

    suspend fun queryAnimeSkip(clientId: String, graphqlQuery: String): AnimeSkipGraphqlResponse? {
        val body = json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject {
                put("query", kotlinx.serialization.json.JsonPrimitive(graphqlQuery))
            }
        )
        val headers = mapOf(
            "X-Client-ID" to clientId,
            "Content-Type" to "application/json",
        )
        return try {
            val text = httpPostJsonWithHeaders(ANIMESKIP_BASE + "graphql", body, headers)
            json.decodeFromString<AnimeSkipGraphqlResponse>(text)
        } catch (_: Exception) {
            null
        }
    }
}
