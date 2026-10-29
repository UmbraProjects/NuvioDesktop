package com.nuvio.app.features.preroll

import co.touchlab.kermit.Logger
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.concurrent.thread
import kotlin.random.Random

private val log = Logger.withTag("PreshowAudioKeepAlive")

private const val SAMPLE_RATE = 48_000f
private const val FRAME_BYTES = 4 // 16-bit stereo
private const val CHUNK_FRAMES = 4_800 // 100 ms: also how quickly a stop is noticed
private const val CHUNK_BYTES = CHUNK_FRAMES * FRAME_BYTES

internal actual object PreshowAudioKeepAlive {
    private val lock = Any()
    private var worker: Worker? = null

    /**
     * Every owner that currently wants the output held, with the token of its latest request. A
     * delayed stop only releases its owner if the token is still the one it was given, so a later
     * [start] for that owner cancels it. The stream runs while this map is not empty, so two owners
     * (the pre-show and the music player) cannot stop each other's hold.
     */
    private val holders = HashMap<String, Long>()
    private val generation = AtomicLong(0)

    actual fun start(owner: String) {
        synchronized(lock) {
            holders[owner] = generation.incrementAndGet()
            if (worker?.isAlive == true) return
            worker = Worker().also { it.start() }
        }
    }

    actual fun stopAfter(delayMs: Long, owner: String) {
        val requested = synchronized(lock) { generation.incrementAndGet().also { if (owner in holders) holders[owner] = it } }
        thread(isDaemon = true, name = "Nuvio-Preshow-AudioKeepAlive-Stop") {
            runCatching { Thread.sleep(delayMs.coerceAtLeast(0L)) }
            synchronized(lock) {
                if (holders[owner] != requested) return@thread
                holders.remove(owner)
                if (holders.isEmpty()) {
                    worker?.requestStop()
                    worker = null
                }
            }
        }
    }

    private class Worker : Thread("Nuvio-Preshow-AudioKeepAlive") {
        @Volatile private var stopRequested = false

        init {
            isDaemon = true
        }

        fun requestStop() {
            stopRequested = true
        }

        override fun run() {
            val format = AudioFormat(SAMPLE_RATE, 16, 2, true, false)
            val line = try {
                AudioSystem.getSourceDataLine(format).also {
                    it.open(format, CHUNK_BYTES * 3)
                    it.start()
                }
            } catch (error: Throwable) {
                log.w { "could not open an audio line: ${error.message}" }
                return
            }
            log.i { "holding the audio output open" }
            val chunk = ByteArray(CHUNK_BYTES)
            try {
                while (!stopRequested) {
                    // One least-significant bit of noise (about -90 dBFS): far below anything
                    // audible, but not digital silence, which some receivers treat as "no signal".
                    // write() blocks while the line's buffer is full, which paces the loop in real time.
                    var i = 0
                    while (i < CHUNK_BYTES) {
                        val sample = Random.nextInt(-1, 2)
                        val low = (sample and 0xFF).toByte()
                        val high = (if (sample < 0) 0xFF else 0x00).toByte()
                        chunk[i] = low; chunk[i + 1] = high // left
                        chunk[i + 2] = low; chunk[i + 3] = high // right
                        i += FRAME_BYTES
                    }
                    line.write(chunk, 0, CHUNK_BYTES)
                }
            } catch (error: Throwable) {
                log.w { "audio line stopped: ${error.message}" }
            } finally {
                runCatching { line.stop() }
                runCatching { line.flush() }
                runCatching { line.close() }
                log.i { "released the audio output" }
            }
        }
    }
}
