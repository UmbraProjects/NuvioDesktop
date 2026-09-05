package com.nuvio.app.features.mdblist

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MDBList static lists — the publish half of Discover's export (plan §6.2, §11).
 *
 * A static list is the only place a *frozen* Discover row can live on a service: neither
 * AIOMetadata nor TMDB Discover+ has a catalog kind that holds a list of titles, and both read an
 * MDBList list as an ordinary `mdblist.*` catalog once it exists. So one publish here is what makes
 * a generated row — recommendations, trending, hidden gems — reachable from any Stremio client.
 *
 * **The free tier allows four static lists.** That is small enough to be a design constraint rather
 * than an error path: updating an existing list is the normal operation and creating one is the
 * exception, which is why [createStaticList] is a separate call the UI only makes when the user
 * asks for a new list rather than something [publish] does on its own.
 */
object MdbListStaticListRepository {
    private val log = Logger.withTag("MdbListStaticLists")
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /**
     * Items per request. The API documents no batch ceiling; this keeps a fifty-item row to one
     * request while stopping a 2000-item import from becoming a single enormous body.
     */
    private const val ITEMS_PER_REQUEST = 250

    suspend fun fetchUserLists(): List<MdbListUserList> {
        val apiKey = requireApiKey()
        val url = "$MDBLIST_BASE_URL/lists/user?apikey=${apiKey.encodeURLParameter()}"
        val response = httpRequestRaw(
            method = "GET",
            url = url,
            headers = mapOf("Accept" to "application/json"),
            body = "",
        )
        if (response.status !in 200..299) throw response.toFailure()
        return runCatching { json.decodeFromString<List<MdbListUserListDto>>(response.body) }
            .getOrElse { error ->
                log.w(error) { "MDBList returned an unreadable list index" }
                throw MdbListPublishException(MdbListPublishError.Unreadable(error.message.orEmpty()))
            }
            .map { it.toModel() }
    }

    /** Creates an empty static list and returns its id. 403 here means the quota, not auth. */
    suspend fun createStaticList(name: String, isPrivate: Boolean = false): Long {
        val apiKey = requireApiKey()
        val url = "$MDBLIST_BASE_URL/lists/user/add?apikey=${apiKey.encodeURLParameter()}"
        val body = buildJsonObject {
            put("name", name)
            put("private", isPrivate)
        }.toString()
        val response = httpRequestRaw(
            method = "POST",
            url = url,
            headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json"),
            body = body,
        )
        if (response.status !in 200..299) throw response.toFailure()
        val created = runCatching { json.decodeFromString<MdbListCreatedDto>(response.body) }
            .getOrElse { error ->
                throw MdbListPublishException(MdbListPublishError.Unreadable(error.message.orEmpty()))
            }
        return created.id
    }

    /**
     * Adds [refs] to an existing static list.
     *
     * **Add, not replace.** A published row is a snapshot, so replacing would be the more faithful
     * verb, but the list is the user's own and may hold titles they put there themselves; removing
     * what our snapshot does not contain would delete their work to make our row tidy. The returned
     * counts say plainly what happened instead.
     */
    suspend fun addItems(listId: Long, refs: List<MdbListItemRef>): MdbListPublishOutcome {
        if (refs.isEmpty()) return MdbListPublishOutcome()
        val apiKey = requireApiKey()
        val url = "$MDBLIST_BASE_URL/lists/$listId/items/add?apikey=${apiKey.encodeURLParameter()}"
        var total = MdbListPublishOutcome()
        for (chunk in refs.chunked(ITEMS_PER_REQUEST)) {
            val response = httpRequestRaw(
                method = "POST",
                url = url,
                headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json"),
                body = encodeStaticListItems(chunk),
            )
            if (response.status !in 200..299) throw response.toFailure()
            val outcome = runCatching { json.decodeFromString<MdbListAddResponseDto>(response.body) }
                .getOrElse { error ->
                    throw MdbListPublishException(MdbListPublishError.Unreadable(error.message.orEmpty()))
                }
            total = total + outcome.toModel()
        }
        return total
    }

    private fun requireApiKey(): String =
        MdbListSettingsRepository.trackingApiKey()
            ?: throw MdbListPublishException(MdbListPublishError.NotConnected)

    private fun com.nuvio.app.features.addons.RawHttpResponse.toFailure(): MdbListPublishException {
        val detail = runCatching { json.decodeFromString<MdbListErrorDto>(body) }.getOrNull()
        return MdbListPublishException(
            when (status) {
                // The create endpoint answers 403 for the static-list quota, and the same code for a
                // key that cannot write. `limit` is present only on the quota reply, so it is what
                // tells the two apart — without it this would tell a user out of lists to go and
                // check their API key.
                403 -> detail?.limit?.let { MdbListPublishError.QuotaReached(it) }
                    ?: MdbListPublishError.Forbidden(detail?.detail.orEmpty())

                else -> MdbListPublishError.Http(status, detail?.detail ?: body.take(200))
            }
        )
    }
}

/** One of the user's own lists, as `GET /lists/user` reports it. */
data class MdbListUserList(
    val id: Long,
    val name: String,
    val slug: String,
    val itemCount: Int,
    val isDynamic: Boolean,
    val isPrivate: Boolean,
) {
    /** Only static lists can be written to; a dynamic list is defined by its filters. */
    val acceptsItems: Boolean get() = !isDynamic
}

/**
 * One title on its way into a static list.
 *
 * MDBList takes TMDB and IMDb ids and nothing else, which is why Discover exports item ids in the
 * namespace Nuvio already addresses them in (`tmdb:1234`) rather than paying a request per item to
 * convert them — see [com.nuvio.app.features.discover.DiscoverCatalogItem].
 */
data class MdbListItemRef(
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val isMovie: Boolean,
)

data class MdbListPublishOutcome(
    val added: Int = 0,
    val existing: Int = 0,
    val notFound: Int = 0,
) {
    operator fun plus(other: MdbListPublishOutcome) = MdbListPublishOutcome(
        added = added + other.added,
        existing = existing + other.existing,
        notFound = notFound + other.notFound,
    )
}

/** Why a publish failed. Each needs a different action from the user, so none of them is null. */
sealed interface MdbListPublishError {
    /** No API key, or tracking switched off. */
    data object NotConnected : MdbListPublishError

    /** Out of static lists — four on the free tier. Carries the limit the API reported. */
    data class QuotaReached(val limit: Int) : MdbListPublishError

    /** 403 without a limit: the key cannot write, which is a different fix from the quota. */
    data class Forbidden(val detail: String) : MdbListPublishError

    data class Http(val status: Int, val detail: String) : MdbListPublishError

    /** A 2xx whose body was not what the API documents. */
    data class Unreadable(val reason: String) : MdbListPublishError
}

class MdbListPublishException(val error: MdbListPublishError) : Exception(error.toString())

/**
 * Encodes the request body for `POST /lists/{id}/items/{action}`.
 *
 * **This is not the shape the watchlist endpoint takes.** `/watchlist/items/{action}` wraps ids in a
 * nested `ids` object; the static-list endpoint takes them flat on the item. The two live one path
 * segment apart and silently accept each other's shape by adding nothing, so the difference is
 * asserted in a test rather than left to memory.
 */
fun encodeStaticListItems(refs: List<MdbListItemRef>): String {
    val (movies, shows) = refs.partition { it.isMovie }
    return buildJsonObject {
        put("movies", buildJsonArray { movies.forEach { add(it.toJson()) } })
        put("shows", buildJsonArray { shows.forEach { add(it.toJson()) } })
    }.toString()
}

private fun MdbListItemRef.toJson() = buildJsonObject {
    tmdbId?.let { put("tmdb", it) }
    imdbId?.let { put("imdb", it) }
}

/**
 * Turns a Nuvio content id into the ids MDBList understands, or null when it understands none.
 *
 * Anime addressed by a native id (`kitsu:`, `mal:`) and anything an addon invented resolve to
 * nothing here. That is reported as a count rather than silently dropped: a row that publishes
 * eleven of its fifty titles has not done what the user asked, and they need to know which
 * direction the loss went.
 */
fun mdbListItemRef(contentId: String, type: String): MdbListItemRef? {
    val isMovie = !type.equals("series", ignoreCase = true) && !type.equals("tv", ignoreCase = true)
    val trimmed = contentId.trim()
    return when {
        trimmed.startsWith("tmdb:", ignoreCase = true) ->
            trimmed.removePrefix("tmdb:").removePrefix("TMDB:").toIntOrNull()
                ?.let { MdbListItemRef(tmdbId = it, isMovie = isMovie) }

        trimmed.startsWith("tt") && trimmed.drop(2).all { it.isDigit() } && trimmed.length > 2 ->
            MdbListItemRef(imdbId = trimmed, isMovie = isMovie)

        else -> null
    }
}

@Serializable
private data class MdbListUserListDto(
    val id: Long,
    val name: String = "",
    val slug: String = "",
    val items: Int = 0,
    val dynamic: Boolean = false,
    val private: Boolean = false,
) {
    fun toModel() = MdbListUserList(
        id = id,
        name = name,
        slug = slug,
        itemCount = items,
        isDynamic = dynamic,
        isPrivate = private,
    )
}

@Serializable
private data class MdbListCreatedDto(val id: Long, val slug: String = "", val url: String = "")

@Serializable
private data class MdbListAddResponseDto(
    val added: MdbListCountsDto = MdbListCountsDto(),
    val existing: MdbListCountsDto = MdbListCountsDto(),
    @SerialName("not_found") val notFound: MdbListCountsDto = MdbListCountsDto(),
) {
    fun toModel() = MdbListPublishOutcome(
        added = added.total,
        existing = existing.total,
        notFound = notFound.total,
    )
}

@Serializable
private data class MdbListCountsDto(val movies: Int = 0, val shows: Int = 0) {
    val total: Int get() = movies + shows
}

@Serializable
private data class MdbListErrorDto(val detail: String = "", val limit: Int? = null)
