package com.nuvio.app.features.livescores

import com.nuvio.app.features.player.desktop.NativeAudioDuck
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * fork:live-scores - over a playing film, episode or Live TV channel the video window covers the Compose overlay, so
 * the same card is drawn by the player's HUD (`window.nuvioShowScoreCard` in controls.js), like the memory overlay.
 * A hero trailer's HUD ignores it. The chime is two soft notes made here, so no sound file ships.
 */
internal actual object LiveScorePlatform {
    actual fun showOnPlayers(event: ScoreEvent?, look: ScoreLook, durationMs: Long) {
        val handles = runCatching { NativeAudioDuck.liveHandles() }.getOrDefault(emptyList())
        if (handles.isEmpty()) return
        val script = "window.nuvioShowScoreCard&&window.nuvioShowScoreCard(${scoreCardJson(event, look, durationMs)})"
        handles.forEach { handle -> runCatching { NativePlayerBridge.runJavaScript(handle, script) } }
    }

    @Volatile
    private var lastChimeMs = 0L

    actual fun chime(favourite: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastChimeMs < 1_500L) return
        lastChimeMs = now
        kotlin.concurrent.thread(isDaemon = true, name = "live-score-chime") {
            runCatching { playChime(favourite) }
        }
    }

    private fun playChime(favourite: Boolean) {
        val rate = 44_100f
        // A rising fifth for favourites, a single soft note otherwise.
        val notes = if (favourite) listOf(659.25 to 0.16, 987.77 to 0.34) else listOf(783.99 to 0.28)
        val samples = notes.flatMap { (frequency, seconds) ->
            val count = (rate * seconds).toInt()
            (0 until count).map { i ->
                val t = i / rate.toDouble()
                val envelope = minOf(1.0, i / (rate * 0.008)) * exp(-t * 7.0)
                (sin(2 * PI * frequency * t) * envelope * 0.22 * Short.MAX_VALUE).toInt().toShort()
            }
        }
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = (sample.toInt() and 0xff).toByte()
            bytes[index * 2 + 1] = ((sample.toInt() shr 8) and 0xff).toByte()
        }
        val format = AudioFormat(rate, 16, 1, true, false)
        val line = AudioSystem.getSourceDataLine(format)
        line.open(format)
        line.start()
        line.write(bytes, 0, bytes.size)
        line.drain()
        line.close()
    }
}

/** The card as the HUD script reads it; null hides it. */
internal fun scoreCardJson(event: ScoreEvent?, look: ScoreLook, durationMs: Long): String {
    event ?: return "null"
    val match = event.match
    val fight = match.sport == LiveSport.Mma || match.sport == LiveSport.Boxing
    val fields = linkedMapOf(
        "look" to jsonString(look.name),
        "kind" to jsonString(event.kind.label),
        "group" to jsonString(event.kind.group.name),
        "favourite" to event.favourite.toString(),
        "competition" to jsonString(match.competitionName ?: match.sport.label),
        "home" to jsonString(if (fight) match.headline ?: match.home.name else match.home.shortName),
        "away" to jsonString(if (fight) "" else match.away.shortName),
        "homeScore" to (match.homeScore?.toString() ?: "null"),
        "awayScore" to (match.awayScore?.toString() ?: "null"),
        "side" to when (event.forHome) { true -> "\"home\""; false -> "\"away\""; null -> "null" },
        "clock" to jsonString(event.clock.orEmpty()),
        "detail" to jsonString(event.detail.orEmpty()),
        "durationMs" to durationMs.toString(),
    )
    return fields.entries.joinToString(prefix = "{", postfix = "}") { (key, value) -> "\"$key\":$value" }
}

private fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { c ->
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> Unit
            '<' -> append("\\u003c")
            ' ' -> append("\\u2028")
            ' ' -> append("\\u2029")
            else -> append(c)
        }
    }
    append('"')
}

/** The phone remote's SCORE line for [event] (fork:live-scores). */
internal fun phoneScoreLine(event: ScoreEvent): String {
    val (title, text) = scoreNotificationText(event)
    return com.nuvio.app.features.remote.PhoneRemoteProtocol.scoreLine(event.match.sport.name, event.kind.name, title, text)
}
