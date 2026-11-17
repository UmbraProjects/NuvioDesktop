package com.nuvio.app.features.usenet

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The few URL operations the Newznab code needs, on java.net.URI. NuvioTV uses OkHttp's HttpUrl,
 * which this app's shared code cannot reach; the behaviour kept here is what that code relies on:
 * http(s) only, query parameters set or replaced by name, path segments appended, links resolved.
 */
class UsenetUrl private constructor(private val uri: URI) {
    val scheme: String get() = uri.scheme
    val host: String get() = uri.host.orEmpty()
    val username: String get() = uri.rawUserInfo?.substringBefore(':')?.let(::decode).orEmpty()
    val password: String get() = uri.rawUserInfo?.substringAfter(':', "")?.let(::decode).orEmpty()
    val fragment: String? get() = uri.rawFragment

    /** Decoded path segments; empty segments (a trailing slash) are kept, as HttpUrl does. */
    val pathSegments: List<String>
        get() = uri.rawPath.orEmpty().removePrefix("/").split('/').map(::decode)

    private val query: List<Pair<String, String?>>
        get() = uri.rawQuery?.takeIf { it.isNotEmpty() }?.split('&')?.map { part ->
            val name = decode(part.substringBefore('='))
            val value = if ('=' in part) decode(part.substringAfter('=')) else null
            name to value
        }.orEmpty()

    fun queryParameter(name: String): String? = query.firstOrNull { it.first == name }?.second

    fun resolve(link: String): UsenetUrl? = runCatching { parse(uri.resolve(link.trim()).toString()) }.getOrNull()

    fun newBuilder(): Builder = Builder(
        scheme = uri.scheme,
        rawAuthority = uri.rawAuthority,
        rawPath = uri.rawPath.orEmpty(),
        query = query.toMutableList(),
        rawFragment = uri.rawFragment,
    )

    override fun toString(): String = uri.toString()
    override fun equals(other: Any?): Boolean = other is UsenetUrl && other.uri == uri
    override fun hashCode(): Int = uri.hashCode()

    class Builder internal constructor(
        private val scheme: String,
        private val rawAuthority: String,
        private var rawPath: String,
        private val query: MutableList<Pair<String, String?>>,
        private val rawFragment: String?,
    ) {
        /** Replaces every value of [name] with [value]. */
        fun setQueryParameter(name: String, value: String?): Builder = apply {
            query.removeAll { it.first == name }
            query += name to value
        }

        fun addQueryParameter(name: String, value: String?): Builder = apply { query += name to value }

        /** Appends "a/b" as two segments, each encoded. */
        fun addPathSegments(segments: String): Builder = apply {
            val base = rawPath.trimEnd('/')
            rawPath = base + segments.split('/').filter { it.isNotEmpty() }.joinToString("") { "/" + encode(it) }
        }

        fun build(): UsenetUrl {
            val text = buildString {
                append(scheme).append("://").append(rawAuthority)
                append(rawPath)
                if (query.isNotEmpty()) {
                    append('?')
                    append(query.joinToString("&") { (name, value) -> encode(name) + (value?.let { "=" + encode(it) } ?: "") })
                }
                rawFragment?.let { append('#').append(it) }
            }
            return UsenetUrl(URI(text))
        }
    }

    companion object {
        /** An http or https URL with a host, or null. */
        fun parse(text: String): UsenetUrl? = runCatching {
            val uri = URI(text.trim())
            val scheme = uri.scheme?.lowercase()
            if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank()) null else UsenetUrl(uri)
        }.getOrNull()

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        private fun decode(value: String): String = runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
    }
}

fun String.toUsenetUrlOrNull(): UsenetUrl? = UsenetUrl.parse(this)

fun String.toUsenetUrl(): UsenetUrl = requireNotNull(UsenetUrl.parse(this)) { "Invalid URL" }
