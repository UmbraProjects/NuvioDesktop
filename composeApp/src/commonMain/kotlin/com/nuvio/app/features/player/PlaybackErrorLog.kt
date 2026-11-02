package com.nuvio.app.features.player

import co.touchlab.kermit.Logger

/**
 * One line per playback error and one per decision the player takes on it, so a report that
 * "went back to Home after a minute" explains itself from `nuvio.log`. Before this the only trace
 * of a failed open was in the mpv playback log; the app log just showed Home again. Failover
 * itself keeps its own detailed events in [StreamFailoverLog].
 *
 * Error text can carry a stream URL (mpv's `Failed to open https://…`) and with it a debrid
 * token, so every URL is cut down to its host before it is written.
 */
internal object PlaybackErrorLog {
    private val log = Logger.withTag("PlaybackError")

    fun error(attemptId: Long, sourceUrl: String?, message: String) {
        val failure = playbackErrorFailure(message)?.name ?: "none"
        log.i {
            // The host stays for other sources, but a Stalker portal's address may not reach a log.
            com.nuvio.app.features.iptv.redactIptvCredentials(
                "error attemptId=$attemptId host=${playbackLogHost(sourceUrl) ?: "-"}" +
                    " failure=$failure message=${redactPlaybackLogText(message)}",
            )
        }
    }

    fun decision(attemptId: Long, decision: String) {
        log.i { "decision attemptId=$attemptId -> $decision" }
    }
}

// A URL runs to whitespace or a quote, and never ends on sentence punctuation ("…/x?t=1.").
private val URL_IN_TEXT = Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]*://[^\s"'<>]*[^\s"'<>.,;:)\]]""")

/** [text] with each URL reduced to `scheme://host/<redacted>`; tokens, paths and queries never survive. */
internal fun redactPlaybackLogText(text: String): String =
    URL_IN_TEXT.replace(text) { match ->
        val url = match.value
        val scheme = url.substringBefore("://")
        val host = playbackLogHost(url)
        if (host != null) "$scheme://$host/<redacted>" else "$scheme://<redacted>"
    }

/** The host of a URL, or null for anything without a `scheme://` (a local path has none to log). */
internal fun playbackLogHost(url: String?): String? {
    if (url == null || "://" !in url) return null
    val authority = url.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }
    return authority.substringAfterLast('@')
        .substringBefore(':')
        .trim()
        .lowercase()
        .takeIf { it.isNotBlank() }
}
