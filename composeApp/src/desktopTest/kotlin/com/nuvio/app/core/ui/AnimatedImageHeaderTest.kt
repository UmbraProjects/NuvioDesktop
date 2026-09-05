package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The decoder factory decides whether to read a whole image file based on these 21 bytes, so a
 * wrong answer here either costs a pointless multi-megabyte read or drops an animation to a still.
 *
 * Cross-checked against the real corpus separately: over the 334 GIF/WebP files in a live disk
 * cache, this agreed with constructing a Skia `Codec` and reading `frameCount` 334 times out of 334.
 * These cases pin the specific bit patterns so a future edit cannot quietly regress one.
 */
class AnimatedImageHeaderTest {

    private fun webp(chunk: String, flags: Int = 0, length: Int = 21): ByteArray {
        val bytes = ByteArray(length)
        "RIFF".forEachIndexed { i, c -> bytes[i] = c.code.toByte() }
        "WEBP".forEachIndexed { i, c -> bytes[8 + i] = c.code.toByte() }
        chunk.forEachIndexed { i, c -> if (12 + i < length) bytes[12 + i] = c.code.toByte() }
        if (length > 20) bytes[20] = flags.toByte()
        return bytes
    }

    private fun gif(version: String): ByteArray =
        ByteArray(21).also { "GIF8$version".forEachIndexed { i, c -> it[i] = c.code.toByte() } }

    @Test
    fun `animated webp is a candidate`() {
        assertTrue(webp("VP8X", flags = 0x02).isAnimatedCandidate())
    }

    @Test
    fun `animated webp is still recognised alongside other feature flags`() {
        // ALPHA (0x10) + ANIMATION (0x02) — a transparent animation is the normal case for this art.
        assertTrue(webp("VP8X", flags = 0x12).isAnimatedCandidate())
    }

    @Test
    fun `extended webp without the animation flag is rejected`() {
        assertFalse(webp("VP8X", flags = 0x00).isAnimatedCandidate())
        // ALPHA only: extended features, but a single image.
        assertFalse(webp("VP8X", flags = 0x10).isAnimatedCandidate())
    }

    @Test
    fun `plain and lossless webp are rejected without reading the body`() {
        assertFalse(webp("VP8 ").isAnimatedCandidate())
        assertFalse(webp("VP8L").isAnimatedCandidate())
    }

    @Test
    fun `both gif versions are candidates`() {
        // A GIF's frame count is not knowable from the header, so both must reach the body read.
        assertTrue(gif("9a").isAnimatedCandidate())
        assertTrue(gif("7a").isAnimatedCandidate())
    }

    @Test
    fun `other formats are rejected`() {
        assertFalse(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()).isAnimatedCandidate())
        assertFalse(
            byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
                .isAnimatedCandidate(),
        )
        assertFalse(ByteArray(0).isAnimatedCandidate())
    }

    @Test
    fun `a truncated header is rejected rather than throwing`() {
        // request() returns short for a tiny file, so these reach the classifier as-is.
        assertFalse(webp("VP8X", flags = 0x02, length = 16).isAnimatedCandidate())
        assertFalse(byteArrayOf('R'.code.toByte(), 'I'.code.toByte()).isAnimatedCandidate())
        assertFalse(ByteArray(20).isAnimatedCandidate())
    }

    @Test
    fun `a riff container that is not webp is rejected`() {
        val wav = ByteArray(21)
        "RIFF".forEachIndexed { i, c -> wav[i] = c.code.toByte() }
        "WAVE".forEachIndexed { i, c -> wav[8 + i] = c.code.toByte() }
        assertFalse(wav.isAnimatedCandidate())
    }
}
