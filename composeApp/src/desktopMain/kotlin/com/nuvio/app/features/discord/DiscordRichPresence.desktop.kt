package com.nuvio.app.features.discord

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal actual object DiscordRichPresencePlatform {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val unsupportedOsLogged = AtomicBoolean(false)
    private val unavailableLogged = AtomicBoolean(false)
    private var connection: DiscordIpcConnection? = null
    private var connectedClientId: String? = null
    private var lastPayloadKey: String? = null
    private val artworkProbeCache = object : LinkedHashMap<String, ArtworkProbe>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArtworkProbe>): Boolean =
            size > ARTWORK_PROBE_CACHE_LIMIT
    }

    actual fun update(activity: DiscordRichPresenceActivity?) {
        scope.launch {
            mutex.withLock {
                val payloadKey = activity?.toPayloadKey() ?: "clear"
                if (payloadKey == lastPayloadKey) return@withLock
                lastPayloadKey = payloadKey

                if (activity == null) {
                    runCatching { connection?.setActivity(null) }
                    println("[nuvio-discord] activity cleared")
                    return@withLock
                }

                val activeConnection = activeConnection(DISCORD_APPLICATION_ID)
                if (activeConnection == null) {
                    if (unavailableLogged.compareAndSet(false, true)) {
                        println("[nuvio-discord] Discord IPC pipe is not available; presence updates will retry.")
                    }
                    return@withLock
                }

                runCatching {
                    val activity = activity.withResolvedArtwork()
                    val discordActivity = activity.toDiscordActivity()
                    try {
                        activeConnection.setActivity(discordActivity)
                    } catch (error: DiscordRpcCommandException) {
                        // A catalogue controls its artwork URL. Discord may reject an otherwise
                        // valid activity when that particular external asset is unsupported or
                        // inaccessible, which previously made MAL/Kitsu playback look as though
                        // Rich Presence itself was broken. Preserve the useful playback fields and
                        // retry with the bundled Nuvio asset; metadata and artwork resolution stay
                        // completely outside the RPC transport.
                        if (activity.imageUrl.isNullOrBlank()) throw error
                        // Step down one artwork at a time rather than straight to the bundled
                        // asset: the rejected URL is usually the poster, and the episode thumbnail
                        // or backdrop behind it is a plain CDN image Discord accepts happily.
                        val nextArtwork = activity.fallbackImageUrl
                            ?.takeIf { it.isNotBlank() && it != activity.imageUrl }
                        println(
                            "[nuvio-discord] external artwork rejected by Discord " +
                                "(${error.safeDescription}); retrying with " +
                                if (nextArtwork != null) "the next artwork" else "bundled artwork",
                        )
                        val bundled = activity.copy(imageUrl = null, fallbackImageUrl = null)
                        if (nextArtwork == null) {
                            activeConnection.setActivity(bundled.toDiscordActivity())
                        } else {
                            try {
                                activeConnection.setActivity(
                                    activity.copy(imageUrl = nextArtwork, fallbackImageUrl = null)
                                        .toDiscordActivity(),
                                )
                            } catch (_: DiscordRpcCommandException) {
                                activeConnection.setActivity(bundled.toDiscordActivity())
                            }
                        }
                    }
                    println(
                        "[nuvio-discord] activity updated title=\"${activity.title}\" " +
                            "subtitle=\"${activity.subtitle.orEmpty()}\" type=${activity.type} playing=${activity.isPlaying}",
                    )
                }.onFailure { error ->
                    closeConnection()
                    println("[nuvio-discord] activity update failed: ${error.message}")
                }
            }
        }
    }

    actual fun shutdown() {
        scope.launch {
            mutex.withLock {
                runCatching { connection?.setActivity(null) }
                closeConnection()
            }
        }
        scope.cancel()
    }

    private fun activeConnection(clientId: String): DiscordIpcConnection? {
        val existing = connection
        if (existing != null && connectedClientId == clientId) return existing
        if (existing != null) {
            closeConnection()
        }

        val pipePath = discordIpcPipeCandidates().firstNotNullOfOrNull { path ->
            runCatching {
                DiscordIpcConnection(RandomAccessFile(path, "rw")).also { it.handshake(clientId) }
            }.getOrNull()
        }
        if (pipePath == null) {
            return null
        }

        unavailableLogged.set(false)
        println("[nuvio-discord] connected to Discord IPC")
        connection = pipePath
        connectedClientId = clientId
        return pipePath
    }

    private fun closeConnection() {
        runCatching { connection?.close() }
        connection = null
        connectedClientId = null
    }

    /**
     * Turns the artwork candidates into the URLs Discord is actually handed.
     *
     * A portrait poster goes through the resizing proxy so Discord's square slot letterboxes it
     * instead of centre-cropping it. The proxy is asked first whether it can serve the image,
     * because its failure modes are otherwise invisible: it refuses whole TLDs and hosts by
     * policy (`.cc` among them) and with a `default=` attached it would quietly 302 Discord to
     * the raw fallback — a plain poster, un-letterboxed — which looked like the custom poster
     * service being ignored. So the fallback walk happens here, one HEAD per URL, cached:
     *   proxy 2xx  → the proxied URL;
     *   proxy 400  → the proxy's own policy refusal, not the origin's — send the raw URL and
     *                accept the crop, Discord fetches it itself;
     *   proxy 404  → the origin cannot be fetched at all — step to the next candidate;
     *   no answer  → the proxy is down; the raw URL is still better than the logo.
     * Landscape art (`Cover`) is sent raw as before.
     *
     * Discord refuses the whole activity when `large_image` exceeds [DISCORD_ASSET_URL_LIMIT]
     * characters, so over-long candidates never reach it: a raw URL that is too long is dropped,
     * and a poster whose *proxied* URL is too long is sent raw (cropped beats the logo).
     */
    private fun DiscordRichPresenceActivity.withResolvedArtwork(): DiscordRichPresenceActivity {
        val candidates = listOfNotNull(imageUrl, fallbackImageUrl)
            .map { it.trim() }
            .filter { it.startsWith("https://") || it.startsWith("http://") }
            .filter { fitsDiscordAssetLimit(it) }
            .distinct()
        if (imageFit != DiscordRichPresenceImageFit.Contain) {
            return copy(imageUrl = candidates.getOrNull(0), fallbackImageUrl = candidates.getOrNull(1))
        }
        candidates.forEachIndexed { index, candidate ->
            val remaining = candidates.drop(index + 1).firstOrNull()
            val proxied = fittedDiscordImageUrl(candidate)
            val proxiedFits = fitsDiscordAssetLimit(proxied)
            val probe = artworkProbeCache[proxied] ?: probeArtworkProxy(proxied).also { result ->
                artworkProbeCache[proxied] = result
                println(
                    "[nuvio-discord] artwork ${artworkHost(candidate)}: ${result.name.lowercase()}" +
                        if (proxiedFits) "" else " (proxied URL exceeds $DISCORD_ASSET_URL_LIMIT chars; sending raw)",
                )
            }
            when (probe) {
                ArtworkProbe.Proxied -> return copy(
                    imageUrl = if (proxiedFits) proxied else candidate,
                    fallbackImageUrl = remaining,
                )
                ArtworkProbe.ProxyRefused,
                ArtworkProbe.ProxyUnavailable,
                -> return copy(imageUrl = candidate, fallbackImageUrl = remaining)
                ArtworkProbe.Unfetchable -> Unit
            }
        }
        return copy(imageUrl = null, fallbackImageUrl = null)
    }

    private fun probeArtworkProxy(proxiedUrl: String): ArtworkProbe {
        val connection = runCatching {
            (URL(proxiedUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = ARTWORK_PROBE_TIMEOUT_MS
                readTimeout = ARTWORK_PROBE_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Nuvio")
            }
        }.getOrElse { return ArtworkProbe.ProxyUnavailable }
        return try {
            when (connection.responseCode) {
                in 200..299 -> ArtworkProbe.Proxied
                400 -> ArtworkProbe.ProxyRefused
                in 500..599 -> ArtworkProbe.ProxyUnavailable
                else -> ArtworkProbe.Unfetchable
            }
        } catch (_: IOException) {
            ArtworkProbe.ProxyUnavailable
        } finally {
            connection.disconnect()
        }
    }

    private fun artworkHost(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore('?')

    private fun discordIpcPipeCandidates(): List<String> {
        val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        return if (osName.contains("win")) {
            (0..9).map { "\\\\?\\pipe\\discord-ipc-$it" }
        } else {
            if (unsupportedOsLogged.compareAndSet(false, true)) {
                println("[nuvio-discord] Discord Rich Presence IPC is currently implemented for Windows desktop only.")
            }
            emptyList()
        }
    }
}

private class DiscordIpcConnection(
    private val file: RandomAccessFile,
) {
    fun handshake(clientId: String) {
        writeFrame(
            opcode = OPCODE_HANDSHAKE,
            payload = buildJsonObject {
                put("v", 1)
                put("client_id", clientId)
            },
        )
        val response = readFramePayload()
        response.commandErrorOrNull()?.let { throw it }
    }

    fun setActivity(activity: JsonObject?) {
        val nonce = UUID.randomUUID().toString()
        val command = buildJsonObject {
            put("cmd", "SET_ACTIVITY")
            put(
                "args",
                buildJsonObject {
                    put("pid", ProcessHandle.current().pid())
                    if (activity == null) {
                        put("activity", null)
                    } else {
                        put("activity", activity)
                    }
                },
            )
            put("nonce", nonce)
        }
        writeFrame(OPCODE_FRAME, command)
        while (true) {
            val response = readFramePayload()
            response.commandErrorOrNull()?.let { throw it }
            if (response.stringValue("nonce") == nonce) return
        }
    }

    fun close() {
        file.close()
    }

    private fun writeFrame(opcode: Int, payload: JsonObject) {
        val payloadBytes = Json.encodeToString(payload).toByteArray(StandardCharsets.UTF_8)
        val header = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(opcode)
            .putInt(payloadBytes.size)
            .array()
        file.write(header)
        file.write(payloadBytes)
    }

    private fun readFramePayload(): JsonObject {
        while (true) {
            val header = ByteArray(DISCORD_FRAME_HEADER_BYTES)
            file.readFully(header)
            val headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val opcode = headerBuffer.int
            val payloadLength = headerBuffer.int
            if (payloadLength !in 0..DISCORD_MAX_FRAME_BYTES) {
                throw IOException("Discord IPC returned an invalid frame length: $payloadLength")
            }
            val payloadBytes = ByteArray(payloadLength)
            file.readFully(payloadBytes)
            val payloadText = payloadBytes.toString(StandardCharsets.UTF_8)
            val payload = runCatching {
                Json.parseToJsonElement(payloadText) as? JsonObject
            }.getOrNull() ?: throw IOException("Discord IPC returned malformed JSON")

            when (opcode) {
                OPCODE_FRAME -> return payload
                OPCODE_PING -> writeFrame(OPCODE_PONG, payload)
                OPCODE_CLOSE -> {
                    val reason = payload.commandErrorOrNull()?.safeDescription
                        ?: payload.stringValue("message")
                        ?: "Discord closed the IPC connection"
                    throw IOException(reason)
                }
                else -> throw IOException("Discord IPC returned unsupported opcode $opcode")
            }
        }
    }
}

private class DiscordRpcCommandException(
    val code: Int?,
    message: String?,
) : IOException(message ?: "Discord rejected the Rich Presence command") {
    val safeDescription: String
        get() = listOfNotNull(code?.let { "code $it" }, message).joinToString(": ")
            .ifBlank { "unknown RPC error" }
}

private fun JsonObject.commandErrorOrNull(): DiscordRpcCommandException? {
    if (!stringValue("evt").equals("ERROR", ignoreCase = true)) return null
    val data = this["data"] as? JsonObject
    return DiscordRpcCommandException(
        code = data?.get("code")?.jsonPrimitive?.intOrNull,
        message = data?.get("message")?.jsonPrimitive?.contentOrNull,
    )
}

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonElement)?.jsonPrimitive?.contentOrNull

private fun DiscordRichPresenceActivity.toDiscordActivity(): JsonObject {
    val titleText = title.trim().takeIf { it.isNotBlank() } ?: "Nuvio"
    val subtitleText = subtitle?.trim()?.takeIf { it.isNotBlank() }
    val episodeLabelText = episodeLabel?.trim()?.takeIf { it.isNotBlank() }
    val episodeTitleText = episodeTitle?.trim()?.takeIf { it.isNotBlank() }
    if (type == DiscordRichPresenceActivityType.Browsing) {
        return buildJsonObject {
            // Watching rather than Playing so the header carries an eye icon instead of a game
            // controller while browsing; no per-activity name, so Discord shows the application
            // name here. (Contributed by codeine.)
            put("type", DISCORD_ACTIVITY_TYPE_WATCHING)
            put("details", truncateDiscordText(titleText))
            subtitleText?.let { put("state", truncateDiscordText(it)) }
            put("assets", discordPresenceAssets(titleText, imageUrl))
        }
    }

    val nowMs = System.currentTimeMillis()
    // Discord timestamps have no paused state. Supplying them while paused makes Discord continue
    // moving the bar and can render a bogus play/pause-looking control over the poster, so paused
    // activities use an explicit state label and badge without a live wall-clock timeline.
    val hasTimeline = durationMs > 0L && positionMs >= 0L && positionMs < durationMs
    val startEpochSeconds = if (hasTimeline && isPlaying) {
        ((nowMs - positionMs) / 1000L).coerceAtLeast(0L)
    } else {
        null
    }
    val endEpochSeconds = if (hasTimeline && isPlaying) {
        ((nowMs + (durationMs - positionMs)) / 1000L).coerceAtLeast(0L)
    } else {
        null
    }

    val pausedText = if (!isPlaying) {
        discordPausedPresenceText(
            title = titleText,
            releaseYear = subtitleText.takeIf { episodeLabelText == null },
            episodeLabel = episodeLabelText,
            episodeTitle = episodeTitleText,
            positionMs = positionMs,
            durationMs = durationMs,
        )
    } else {
        null
    }
    // Playing presence remains title + the existing movie year / episode description. Paused
    // presence deliberately uses both custom rows for an explicit state and frozen time summary.
    val displayedDetails = pausedText?.details ?: titleText
    val displayedState = pausedText?.state ?: subtitleText

    return buildJsonObject {
        // Watching and Listening both render start + end as a media progress bar. Discord
        // prefixes the name with the type's verb, so the header reads "Watching on Nuvio HTPC" /
        // "Listening to Nuvio HTPC" and the title lives on the details line beneath it instead of
        // being repeated. status_display_type = DETAILS keeps the member-list status on the title
        // ("Watching Inception") rather than the name. Older RPC clients ignore both and fall
        // back to the application name. (Layout contributed by codeine.)
        val listening = activityStyle == DiscordActivityStyle.Listening
        put("type", if (listening) DISCORD_ACTIVITY_TYPE_LISTENING else DISCORD_ACTIVITY_TYPE_WATCHING)
        put(
            "name",
            when {
                activityName == DiscordActivityName.Title -> truncateDiscordText(titleText)
                listening -> DISCORD_LISTENING_ACTIVITY_NAME
                else -> DISCORD_WATCHING_ACTIVITY_NAME
            },
        )
        put("status_display_type", DISCORD_STATUS_DISPLAY_DETAILS)
        put("details", truncateDiscordText(displayedDetails))
        displayedState?.let { put("state", truncateDiscordText(it)) }
        // The Listening layout prints large_text as a third row (Spotify's album line), which
        // for us would just repeat the title, so it carries no hover text in that style.
        put(
            "assets",
            discordPresenceAssets(
                title = titleText,
                imageUrl = imageUrl,
                isPaused = !isPlaying,
                includeLargeText = !listening,
            ),
        )
        if (startEpochSeconds != null && endEpochSeconds != null && endEpochSeconds > startEpochSeconds) {
            put(
                "timestamps",
                buildJsonObject {
                    put("start", startEpochSeconds)
                    put("end", endEpochSeconds)
                },
            )
        }
    }
}

internal data class DiscordPausedPresenceText(
    val details: String,
    val state: String,
)

internal fun discordPausedPresenceText(
    title: String,
    releaseYear: String?,
    episodeLabel: String?,
    episodeTitle: String?,
    positionMs: Long,
    durationMs: Long,
): DiscordPausedPresenceText {
    val timeSummary = durationMs.takeIf { it > 0L }?.let { duration ->
        val position = positionMs.coerceIn(0L, duration)
        "${formatDiscordPlaybackTime(position)} / ${formatDiscordPlaybackTime(duration)}"
    }
    val stateParts = if (!episodeLabel.isNullOrBlank()) {
        listOfNotNull(
            episodeLabel.trim(),
            episodeTitle?.trim()?.takeIf { it.isNotBlank() },
            timeSummary,
        )
    } else {
        listOfNotNull(
            releaseYear?.trim()?.takeIf { it.isNotBlank() },
            timeSummary,
        )
    }
    return DiscordPausedPresenceText(
        details = "Paused: ${title.trim().ifBlank { "Nuvio" }}",
        state = stateParts.joinToString(" · ").ifBlank { "Paused" },
    )
}

private fun formatDiscordPlaybackTime(timeMs: Long): String {
    val totalSeconds = (timeMs / 1_000L).coerceAtLeast(0L)
    val seconds = totalSeconds % 60L
    val minutes = (totalSeconds / 60L) % 60L
    val hours = totalSeconds / 3_600L
    return if (hours > 0L) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}

private fun discordPresenceAssets(
    title: String,
    imageUrl: String?,
    isPaused: Boolean = false,
    includeLargeText: Boolean = true,
): JsonObject =
    buildJsonObject {
        // Already resolved by `withResolvedArtwork`: proxied, raw, or absent.
        val externalImage = imageUrl
            ?.trim()
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            // Last line of defence: an over-long URL makes Discord reject the entire activity.
            ?.takeIf { fitsDiscordAssetLimit(it) }
        put("large_image", externalImage ?: DISCORD_LARGE_IMAGE_KEY)
        if (includeLargeText) {
            put("large_text", truncateDiscordText(if (externalImage != null) title else "Nuvio"))
        }
        // Keep the poster unobstructed while paused; the text rows already communicate that state.
        if (externalImage != null && !isPaused) {
            put("small_image", DISCORD_LARGE_IMAGE_KEY)
            put("small_text", "Nuvio")
        }
    }

private enum class ArtworkProbe { Proxied, ProxyRefused, Unfetchable, ProxyUnavailable }

private fun DiscordRichPresenceActivity.toPayloadKey(): String =
    listOf(
        type.name,
        title.trim(),
        subtitle.orEmpty().trim(),
        episodeLabel.orEmpty().trim(),
        episodeTitle.orEmpty().trim(),
        imageUrl.orEmpty().trim(),
        fallbackImageUrl.orEmpty().trim(),
        imageFit.name,
        isPlaying.toString(),
        (positionMs.coerceAtLeast(0L) / 15_000L).toString(),
        durationMs.coerceAtLeast(0L).toString(),
        refreshNonce.toString(),
        activityStyle.name,
        activityName.name,
    ).joinToString("|")

private fun truncateDiscordText(value: String): String =
    value.take(DISCORD_TEXT_LIMIT).ifBlank { "Nuvio" }

private const val OPCODE_HANDSHAKE = 0
private const val OPCODE_FRAME = 1
private const val OPCODE_CLOSE = 2
private const val OPCODE_PING = 3
private const val OPCODE_PONG = 4
private const val DISCORD_FRAME_HEADER_BYTES = 8
private const val DISCORD_MAX_FRAME_BYTES = 1_048_576
private const val DISCORD_ACTIVITY_TYPE_LISTENING = 2
private const val DISCORD_ACTIVITY_TYPE_WATCHING = 3
private const val DISCORD_STATUS_DISPLAY_DETAILS = 2
private const val DISCORD_WATCHING_ACTIVITY_NAME = "on Nuvio HTPC"
private const val DISCORD_LISTENING_ACTIVITY_NAME = "Nuvio HTPC"
private const val DISCORD_TEXT_LIMIT = 128
private const val ARTWORK_PROBE_TIMEOUT_MS = 5_000
private const val ARTWORK_PROBE_CACHE_LIMIT = 128
private const val DISCORD_APPLICATION_ID = "1522129829363843195"
private const val DISCORD_LARGE_IMAGE_KEY = "nuvio_logo"
