// Ported from NuvioTV PR 4679 (built-in Usenet, haaihond/NuvioTV usenet @ e0255815).
package com.nuvio.app.features.usenet

import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class UsenetProvider(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 563,
    val tls: Boolean = true,
    val username: String = "",
    val password: String = "",
    val connections: Int = 20,
    val enabled: Boolean = true,
    /** Equal priorities share article traffic; lower priorities only fill missing articles. */
    val priority: Int = 1
) {
    fun validate() {
        require(name.isNotBlank() && host.isNotBlank()) { "Name and server host are required" }
        require(port in 1..65535 && connections in 1..4096) { "Invalid port or connection limit" }
        require(priority in USENET_PRIORITIES) { "Invalid priority" }
        require(!host.contains(Regex("[\\s/@?#]"))) { "Enter a server host without a URL or port" }
        require(!username.contains(':') && !(username + password).contains(Regex("[\\r\\n\\u0000]"))) {
            "Invalid provider credentials"
        }
        URI(serverUrl()) // Also validates IPv6 and host syntax.
    }

    fun serverUrl(): String {
        val authority = if (host.contains(':')) "[${host.removeSurrounding("[", "]")}]" else host
        val credentials = if (username.isNotEmpty() || password.isNotEmpty()) {
            "${encode(username)}:${encode(password)}@"
        } else ""
        return "${if (tls) "nntps" else "nntp"}://$credentials$authority:$port/$connections?priority=$priority"
    }

    override fun toString() = "UsenetProvider(id=$id, enabled=$enabled)"
}

@Serializable
data class UsenetIndexer(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    /** The indexer's URL; [apiPath] is appended to it. */
    val apiUrl: String = "",
    val apiKey: String = "",
    val enabled: Boolean = true,
    /** Equal priorities are searched together; lower priorities only when higher ones find nothing. */
    val priority: Int = 1,
    /**
     * Path of the Newznab API under [apiUrl], usually /api. New indexers start with /api; indexers
     * saved before this field existed have none, because their URL is already the complete endpoint.
     */
    val apiPath: String = "",
    /** How this indexer is asked: by ID, by title, or both; see [UsenetSearchMode]. */
    val searchMode: UsenetSearchMode = UsenetSearchMode.AUTO,
) {
    /**
     * The Newznab endpoint: [apiUrl] with [apiPath] appended, keeping the URL's query parameters.
     * A URL that already ends with the path is used as is, so a pasted complete endpoint still works.
     */
    fun endpointUrl(): UsenetUrl? {
        val url = apiUrl.trim().toUsenetUrlOrNull() ?: return null
        val path = apiPath.trim().trim('/')
        if (path.isEmpty()) return url
        val segments = path.split('/')
        if (url.pathSegments.filter { it.isNotEmpty() }.takeLast(segments.size) == segments) return url
        return url.newBuilder().addPathSegments(path).build()
    }

    /** [endpointUrl] as text; the URL as entered while it is invalid or has no path to add. */
    val endpoint: String get() = if (apiPath.isBlank()) apiUrl else endpointUrl()?.toString() ?: apiUrl

    fun validate() {
        val url = endpointUrl()
        require(name.isNotBlank() && url != null) { "A name and valid HTTP(S) API URL are required" }
        require(priority in USENET_PRIORITIES) { "Invalid priority" }
        require(!apiPath.contains(Regex("[?#\\\\\\s]")) && apiPath.split('/').none { it == "." || it == ".." }) {
            "Enter an API path such as /api"
        }
        require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) {
            "Use an API URL without user credentials or a fragment"
        }
    }

    override fun toString() = "UsenetIndexer(id=$id, enabled=$enabled)"
}

@Serializable
enum class UsenetSort { QUALITY, LARGEST, SMALLEST, NEWEST, INDEXER }

/**
 * How an indexer is searched. Some answer IDs well and titles badly, others the other way round.
 * [AUTO]: by ID where the indexer takes one, by title when it does not, or when the ID search found
 * nothing. [ID]: by ID only (titles only where the indexer takes no ID). [TITLE]: by title only.
 * [BOTH]: both at once, merged (twice the API hits).
 */
@Serializable
enum class UsenetSearchMode { AUTO, ID, TITLE, BOTH }

@Serializable
data class UsenetSourceConfiguration(
    val enabled: Boolean = false,
    /** Set once the notice shown before the first switch-on in this profile was accepted. */
    val firstUseAccepted: Boolean = false,
    val providers: List<UsenetProvider> = emptyList(),
    val indexers: List<UsenetIndexer> = emptyList(),
    val sort: UsenetSort = UsenetSort.QUALITY,
    val minResolution: Int = 0,
    val maxSizeGb: Int = 0,
    val maxAgeDays: Int = 0,
    /** [UNLIMITED_RESULTS] lists every result. */
    val maxResults: Int = 50,
    /**
     * How many results each search asks an indexer for. [AUTO_INDEXER_RESULTS] follows [maxResults]
     * as before; a number caps the pages read, each an API hit on most indexers.
     */
    val indexerResultsLimit: Int = AUTO_INDEXER_RESULTS,
    val excludeLowQuality: Boolean = true,
    /** On: whole Blu-ray and DVD releases are hidden. Off by default: a Blu-ray plays from its feature clip. */
    val excludeFullDisc: Boolean = false,
    /**
     * On: releases the indexer flags as passworded are left out. Off (the default): listed. One
     * whose NZB (or name, "Name{{password}}") carries the password plays; one without fails at
     * open within a second or two and the next source is tried.
     */
    val hidePassworded: Boolean = false,
    /** On: lower indexer priorities are only searched when higher ones find no viable release. */
    val indexerFallback: Boolean = false,
    /** Minimum stream score a higher-priority movie release must reach to skip lower-priority indexers. */
    val fallbackMinScoreMovie: Int = DEFAULT_FALLBACK_MIN_SCORE_MOVIE,
    /** Minimum stream score a higher-priority TV release must reach to skip lower-priority indexers. */
    val fallbackMinScoreTv: Int = DEFAULT_FALLBACK_MIN_SCORE_TV,
    /** Minimum stream score a higher-priority anime release must reach to skip lower-priority indexers. */
    val fallbackMinScoreAnime: Int = DEFAULT_FALLBACK_MIN_SCORE_ANIME,
    /** On: a release found by several indexers is shown once, from the highest priority. */
    val hideDuplicates: Boolean = false,
    /** On: each result shows its release name above its size, age and indexer. */
    val showReleaseNames: Boolean = true,
    /** On: releases that played recently are listed first, whatever the sort; fallback reaches them first too. */
    val availableFirst: Boolean = false,
    /** On: results show as each indexer answers. Off: the list shows once all answered, or after a short wait. */
    val progressiveResults: Boolean = false,
    /** Null: after every addon. Otherwise the URLs of the addons placed above it; see [BuiltInUsenetOrder]. */
    val addonsAbove: List<String>? = null
) {
    val ready: Boolean get() = enabled && providers.any { it.enabled } && indexers.any { it.enabled }

    /** Has a provider and an indexer, so it can be listed among the addons even while switched off. */
    val configured: Boolean get() = providers.isNotEmpty() && indexers.isNotEmpty()

    /** How many results are listed. */
    val resultLimit: Int get() = if (maxResults == UNLIMITED_RESULTS) Int.MAX_VALUE else maxResults

    /**
     * How many results each indexer is asked for. Auto asks for more than are listed, so the filters
     * and the sort choose among them, and as many as are listed above that. Unlimited still stops at
     * [MAX_INDEXER_RESULTS]: a search an indexer cannot narrow reports a total in the thousands.
     * A chosen [indexerResultsLimit] is used as it is, fewer API hits for fewer releases.
     */
    val indexerResults: Int get() = when {
        indexerResultsLimit in 1..MAX_INDEXER_RESULTS -> indexerResultsLimit
        maxResults == UNLIMITED_RESULTS -> MAX_INDEXER_RESULTS
        else -> maxOf(DEFAULT_INDEXER_RESULTS, maxResults)
    }

    /** The enabled providers, as the engine is given them. */
    fun servers(): List<String> = providers.filter { it.enabled }.map { it.serverUrl() }

    /** List order is the hierarchy: by priority, then by the user's order within a priority. */
    fun normalized() = copy(providers = providers.sortedBy { it.priority }, indexers = indexers.sortedBy { it.priority })

    fun validate() {
        require(providers.size <= 64 && indexers.size <= 20) { "Too many providers or indexers" }
        require(providers.map { it.id }.distinct().size == providers.size)
        require(indexers.map { it.id }.distinct().size == indexers.size)
        providers.forEach { it.validate() }
        indexers.forEach { it.validate() }
        require(providers.filter { it.enabled }.sumOf { it.connections } <= 4096) { "Total connections exceed 4096" }
        require(minResolution in listOf(0, 480, 720, 1080, 2160))
        require(maxSizeGb in 0..1000 && maxAgeDays in 0..10000 &&
            (maxResults == UNLIMITED_RESULTS || maxResults in 1..MAX_LISTED_RESULTS) &&
            indexerResultsLimit in AUTO_INDEXER_RESULTS..MAX_INDEXER_RESULTS)
        require((addonsAbove?.size ?: 0) <= 500) { "Too many addons above built-in Usenet" }
    }

    fun fallbackMinScoreFor(series: Boolean, isAnime: Boolean): Int = when {
        !series -> fallbackMinScoreMovie
        isAnime -> fallbackMinScoreAnime
        else -> fallbackMinScoreTv
    }

    companion object {
        const val UNLIMITED_RESULTS = 0
        const val MAX_LISTED_RESULTS = 500
        const val DEFAULT_INDEXER_RESULTS = 200
        const val MAX_INDEXER_RESULTS = 1000
        const val AUTO_INDEXER_RESULTS = 0
        const val DEFAULT_FALLBACK_MIN_SCORE_MOVIE = 100
        const val DEFAULT_FALLBACK_MIN_SCORE_TV = 80
        const val DEFAULT_FALLBACK_MIN_SCORE_ANIME = 60
        /** The choices offered for [indexerResultsLimit]; indexers send at most 100 per page. */
        val INDEXER_RESULT_CHOICES = listOf(100, 200, 500, MAX_INDEXER_RESULTS)
    }
}

/** The usual Newznab API path, filled in for new indexers. */
const val DEFAULT_API_PATH = "/api"

/** Replaces the element with the same id as [value], or adds it at the end. */
internal fun <T> List<T>.replaceOrAdd(value: T, id: (T) -> String): List<T> =
    if (any { id(it) == id(value) }) map { if (id(it) == id(value)) value else it } else this + value

/** 1 is the highest priority. */
val USENET_PRIORITIES = 1..5

private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
