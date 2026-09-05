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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.net.URLEncoder
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
            put("details", truncateDiscordText(titleText))
            subtitleText?.let { put("state", truncateDiscordText(it)) }
            put("assets", discordPresenceAssets(titleText, imageUrl, fallbackImageUrl, imageFit))
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
        // Watching activities render start + end as a media progress bar in Discord clients.
        // Newer clients also honour the per-activity name; older RPC clients retain the
        // application name configured for this client ID and still show the title in details.
        put("type", DISCORD_ACTIVITY_TYPE_WATCHING)
        put("name", truncateDiscordText(titleText))
        put("details", truncateDiscordText(displayedDetails))
        displayedState?.let { put("state", truncateDiscordText(it)) }
        put("assets", discordPresenceAssets(titleText, imageUrl, fallbackImageUrl, imageFit, isPaused = !isPlaying))
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
    fallbackImageUrl: String?,
    imageFit: DiscordRichPresenceImageFit,
    isPaused: Boolean = false,
): JsonObject =
    buildJsonObject {
        val externalImage = imageUrl
            ?.trim()
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        val displayImage = externalImage?.let { url ->
            if (imageFit == DiscordRichPresenceImageFit.Contain) {
                fittedDiscordImageUrl(url, fallbackImageUrl)
            } else {
                url
            }
        }
        put("large_image", displayImage ?: DISCORD_LARGE_IMAGE_KEY)
        put("large_text", truncateDiscordText(if (externalImage != null) title else "Nuvio"))
        // Keep the poster unobstructed while paused; the text rows already communicate that state.
        if (externalImage != null && !isPaused) {
            put("small_image", DISCORD_LARGE_IMAGE_KEY)
            put("small_text", "Nuvio")
        }
    }

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
    ).joinToString("|")

/**
 * Squares a portrait poster without cropping it, via the same proxy that also gives us a
 * server-side fallback: `default` is served whenever the primary cannot be fetched, so a poster
 * service that answers 404 for this particular title degrades to the episode thumbnail or backdrop
 * instead of leaving Discord with nothing. Discord follows the redirect the proxy issues for it.
 */
private fun fittedDiscordImageUrl(sourceUrl: String, fallbackUrl: String?): String = buildString {
    append("https://images.weserv.nl/?url=")
    append(URLEncoder.encode(sourceUrl, StandardCharsets.UTF_8))
    fallbackUrl?.takeIf { it.isNotBlank() && it != sourceUrl }?.let { fallback ->
        append("&default=").append(URLEncoder.encode(fallback, StandardCharsets.UTF_8))
    }
    append("&w=512&h=512&fit=contain&bg=transparent")
}

private fun truncateDiscordText(value: String): String =
    value.take(DISCORD_TEXT_LIMIT).ifBlank { "Nuvio" }

private const val OPCODE_HANDSHAKE = 0
private const val OPCODE_FRAME = 1
private const val OPCODE_CLOSE = 2
private const val OPCODE_PING = 3
private const val OPCODE_PONG = 4
private const val DISCORD_FRAME_HEADER_BYTES = 8
private const val DISCORD_MAX_FRAME_BYTES = 1_048_576
private const val DISCORD_ACTIVITY_TYPE_WATCHING = 3
private const val DISCORD_TEXT_LIMIT = 128
private const val DISCORD_APPLICATION_ID = "1522129829363843195"
private const val DISCORD_LARGE_IMAGE_KEY = "nuvio_logo"
