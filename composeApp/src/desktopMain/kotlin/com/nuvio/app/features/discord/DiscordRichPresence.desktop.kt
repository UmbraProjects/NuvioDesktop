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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong

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
                    activeConnection.setActivity(activity.toDiscordActivity())
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
    }

    fun setActivity(activity: JsonObject?) {
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
            put("nonce", UUID.randomUUID().toString())
        }
        writeFrame(OPCODE_FRAME, command)
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
}

private fun DiscordRichPresenceActivity.toDiscordActivity(): JsonObject {
    val titleText = title.trim().takeIf { it.isNotBlank() } ?: "Nuvio"
    val subtitleText = subtitle?.trim()?.takeIf { it.isNotBlank() }
    if (type == DiscordRichPresenceActivityType.Browsing) {
        return buildJsonObject {
            put("details", truncateDiscordText(titleText))
            subtitleText?.let { put("state", truncateDiscordText(it)) }
            put("assets", discordPresenceAssets(titleText, imageUrl))
        }
    }

    val nowMs = System.currentTimeMillis()
    val safeSpeed = playbackSpeed.takeIf { it > 0.05f } ?: 1f
    val hasTimeline = isPlaying && durationMs > 0L && positionMs >= 0L && positionMs < durationMs
    val startEpochSeconds = if (hasTimeline) {
        ((nowMs - (positionMs / safeSpeed).roundToLong()) / 1000L).coerceAtLeast(0L)
    } else {
        null
    }
    val endEpochSeconds = if (hasTimeline) {
        ((nowMs + ((durationMs - positionMs) / safeSpeed).roundToLong()) / 1000L).coerceAtLeast(0L)
    } else {
        null
    }

    return buildJsonObject {
        // Watching activities render start + end as a media progress bar in Discord clients.
        // Newer clients also honour the per-activity name; older RPC clients retain the
        // application name configured for this client ID and still show the title in details.
        put("type", DISCORD_ACTIVITY_TYPE_WATCHING)
        put("name", truncateDiscordText(titleText))
        put("details", if (isPlaying) truncateDiscordText(titleText) else truncateDiscordText("Paused: $titleText"))
        val progressText = discordProgressText(positionMs, durationMs)
        listOfNotNull(subtitleText, progressText)
            .joinToString(" · ")
            .takeIf { it.isNotBlank() }
            ?.let { put("state", truncateDiscordText(it)) }
        put("assets", discordPresenceAssets(titleText, imageUrl))
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

private fun discordPresenceAssets(title: String, imageUrl: String?): JsonObject =
    buildJsonObject {
        val externalImage = imageUrl
            ?.trim()
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        put("large_image", externalImage ?: DISCORD_LARGE_IMAGE_KEY)
        put("large_text", truncateDiscordText(if (externalImage != null) title else "Nuvio"))
        if (externalImage != null) {
            put("small_image", DISCORD_LARGE_IMAGE_KEY)
            put("small_text", "Nuvio")
        }
    }

private fun discordProgressText(positionMs: Long, durationMs: Long): String? {
    if (durationMs <= 0L) return null
    return "${formatDiscordDuration(positionMs.coerceIn(0L, durationMs))} / ${formatDiscordDuration(durationMs)}"
}

private fun formatDiscordDuration(valueMs: Long): String {
    val totalSeconds = valueMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

private fun DiscordRichPresenceActivity.toPayloadKey(): String =
    listOf(
        type.name,
        title.trim(),
        subtitle.orEmpty().trim(),
        imageUrl.orEmpty().trim(),
        isPlaying.toString(),
        (positionMs.coerceAtLeast(0L) / 15_000L).toString(),
        durationMs.coerceAtLeast(0L).toString(),
        (playbackSpeed * 100f).roundToLong().toString(),
    ).joinToString("|")

private fun truncateDiscordText(value: String): String =
    value.take(DISCORD_TEXT_LIMIT).ifBlank { "Nuvio" }

private const val OPCODE_HANDSHAKE = 0
private const val OPCODE_FRAME = 1
private const val DISCORD_ACTIVITY_TYPE_WATCHING = 3
private const val DISCORD_TEXT_LIMIT = 128
private const val DISCORD_APPLICATION_ID = "1522129829363843195"
private const val DISCORD_LARGE_IMAGE_KEY = "nuvio_logo"
