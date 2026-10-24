package com.nuvio.app.features.preroll

/**
 * Keeps the system audio output busy while the pre-show runs.
 *
 * Each trailer and clip is a separate player that opens and closes the audio output, so between
 * items nothing is playing for a moment. An AV receiver on HDMI treats that as the end of the
 * stream, drops the input and announces it again when the next item starts (the amplifier flashes
 * its Dolby/format indicator and the first moments of sound are lost). A continuous, inaudible
 * stream from here means the output never goes quiet in between.
 *
 * [start] and [stopAfter] are safe to call repeatedly: a [start] cancels a stop that is pending.
 * Failure to open the device is logged and otherwise ignored.
 */
internal expect object PreshowAudioKeepAlive {
    fun start()

    /** Stops [delayMs] from now, unless [start] is called again first. */
    fun stopAfter(delayMs: Long)
}
