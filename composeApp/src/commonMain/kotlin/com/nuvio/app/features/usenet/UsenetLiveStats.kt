package com.nuvio.app.features.usenet

import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Live per-provider counters from the Usenet engine's session diagnostics endpoint. */
data class UsenetProviderLive(
    val server: Int,
    val articles: Long,
    val bytes: Long,
    val missing: Long,
    val errors: Long,
    val ttfbMs: Double?,
) {
    val completion: Double?
        get() {
            val asked = articles + missing
            if (asked <= 0) return null
            return Math.round(articles * 1000.0 / asked) / 10.0
        }
}

/** Snapshot of an active Usenet playback session's live diagnostics for the mpv stats overlay. */
data class UsenetLiveStats(
    val providers: List<UsenetProviderLive>,
    val filledArticles: Long,
    val failure: String?,
    val providerNames: List<String> = emptyList(),
) {
    val bytes: Long get() = providers.sumOf { it.bytes }
    val missing: Long get() = providers.sumOf { it.missing }
    val errors: Long get() = providers.sumOf { it.errors }
    val ttfbMs: Double? get() = providers.filter { it.ttfbMs != null }.maxByOrNull { it.bytes }?.ttfbMs
    val articles: Long get() = providers.sumOf { it.articles }

    val completion: Double?
        get() {
            val asked = articles + missing
            if (asked <= 0) return null
            return Math.round(articles * 1000.0 / asked) / 10.0
        }

    fun providerName(provider: UsenetProviderLive): String? = providerNames.getOrNull(provider.server)

    companion object {
        internal const val SLOW_TTFB_MS = 300.0

        fun fromDiagnostics(
            root: JsonObject,
            providerNames: List<String> = emptyList(),
        ): UsenetLiveStats {
            fun JsonObject.long(name: String) = this[name]?.jsonPrimitive?.longOrNull ?: 0L
            val providers = (root["providers"] as? JsonArray).orEmpty().mapNotNull { item ->
                val provider = item as? JsonObject ?: return@mapNotNull null
                UsenetProviderLive(
                    server = provider["server"]?.jsonPrimitive?.longOrNull?.toInt() ?: -1,
                    articles = provider.long("articles"),
                    bytes = provider.long("bytes"),
                    missing = provider.long("missing"),
                    errors = provider.long("errors"),
                    ttfbMs = provider["ttfbMs"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 },
                )
            }
            val failure = (root["streamFailure"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.takeIf { it.isNotBlank() }
            return UsenetLiveStats(
                providers = providers,
                filledArticles = root.long("filledArticles"),
                failure = failure,
                providerNames = providerNames,
            )
        }
    }
}

/** Human-readable provider name for a session server URL, falling back to its host. */
internal fun usenetServerDisplayName(serverUrl: String): String =
    runCatching { URI(serverUrl).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "?"

/**
 * Formats [live] as a single-line JSON object written to mpv's `user-data/nuvio-usenet` property
 * and rendered by `player-scripts/stats.lua` under the Connection section.
 */
internal fun formatUsenetMpvDiagnostics(
    live: UsenetLiveStats,
    lastMissing: Long = -1L,
    lastErrors: Long = -1L,
): String {
    fun clean(value: String): String = value.replace('\r', ' ').replace('\n', ' ').trim()

    val asked = live.providers.filter { it.articles + it.missing > 0 }.sortedByDescending { it.bytes }
    val showCompletion = asked.size > 1 && asked.any { it.missing > 0 }
    val providersText = asked.mapNotNull { provider ->
        live.providerName(provider)?.let { name ->
            val requested = provider.articles + provider.missing
            val pct = if (!showCompletion || requested <= 0L) {
                ""
            } else {
                " (${if (provider.missing == 0L) 100 else provider.articles * 100 / requested}%)"
            }
            name + pct
        }
    }.ifEmpty { live.providerNames.distinct() }.joinToString(" • ")

    val completion = live.completion
    val articlesText = when {
        completion == null -> "..."
        live.filledArticles > 0 -> String.format(
            Locale.US,
            "%.1f%% (%d missing, %d filled)",
            completion,
            live.missing,
            live.filledArticles,
        )
        else -> String.format(Locale.US, "%.1f%% (%d missing)", completion, live.missing)
    }
    val articlesSev = when {
        live.failure != null -> "danger"
        live.missing > live.filledArticles -> "warn"
        else -> "normal"
    }

    val stallReason = when {
        live.failure != null -> usenetFailureLabel(live.failure)
        lastMissing >= 0L && live.missing > lastMissing -> "missing articles"
        lastErrors >= 0L && live.errors > lastErrors -> "provider errors"
        else -> "waiting on Usenet provider"
    }

    return buildJsonObject {
        if (providersText.isNotEmpty()) put("providers", clean(providersText))
        put("articles", clean(articlesText))
        put("articles_sev", articlesSev)
        live.ttfbMs?.let { ms ->
            put("ttfb", String.format(Locale.US, "%.0f ms", ms))
            put("ttfb_sev", if (ms >= UsenetLiveStats.SLOW_TTFB_MS) "warn" else "normal")
        }
        if (live.errors > 0 || live.failure != null) {
            val failing = live.providers.filter { it.errors > 0 }
            val who = when {
                live.providerNames.size <= 1 -> null
                failing.size == 1 -> live.providerName(failing.single())
                else -> failing.mapNotNull { provider ->
                    live.providerName(provider)?.let { "$it ${provider.errors}" }
                }.joinToString(", ").ifEmpty { null }
            }
            val parts = listOfNotNull(
                live.errors.takeIf { it > 0 }?.toString(),
                who,
                live.failure?.let(::usenetFailureLabel),
            )
            if (parts.isNotEmpty()) {
                put("nntp_err", clean(parts.joinToString(" · ")))
                put("nntp_err_sev", if (live.failure != null) "danger" else "warn")
            }
        }
        put("stall_hint", clean(stallReason))
        if (live.failure != null) {
            put("failure", clean(usenetFailureLabel(live.failure)))
        }
    }.toString()
}

private fun usenetFailureLabel(code: String): String = when (code) {
    "missing-articles" -> "missing articles"
    "provider-auth" -> "provider auth failed"
    "provider-quota" -> "provider quota reached"
    "provider-timeout" -> "provider timed out"
    "blank-content" -> "blank content"
    "archive-compressed" -> "compressed archive too large"
    else -> code.replace('-', ' ')
}
