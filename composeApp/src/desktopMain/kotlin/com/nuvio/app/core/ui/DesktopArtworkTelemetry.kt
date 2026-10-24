package com.nuvio.app.core.ui

import co.touchlab.kermit.Logger

private val artworkLog = Logger.withTag("Artwork")

/** Long enough that a browsing session produces a handful of lines rather than a wall of them. */
private const val SummaryIntervalMs = 30_000L

/** A sample is one (source, draw size) pair; the cap stops a long session growing the set forever. */
private const val MaxDrawSamples = 4_096

/** What the request pin used to be, kept here only as the "was this worth doing" reference point. */
private const val LegacyPinPx = 1536

/** Above this a decode is a hero backdrop, not a card. */
private const val LargeImageBytes = 4L * 1024 * 1024

/**
 * Answers the one question the still-artwork work could not previously be judged on: are decoded
 * bitmaps actually close to the size they are drawn at, and what is that worth in cache bytes?
 *
 * Two independent halves, because either can be wrong on its own. [recordDecode] sees what the
 * decoder produced against what the source held - the saving. [recordDraw] sees what the draw path
 * was handed against what it drew - whether the size that was requested was the right one. A change
 * that reduces beautifully to the wrong size looks perfect in the first half and bad in the second.
 *
 * Everything here is counters behind one monitor and a summary emitted at most every
 * [SummaryIntervalMs] from the decode path, so there is no timer thread and nothing logs while the
 * app is idle. `recordDraw` runs inside `onDraw`, so it does the least work that can still answer
 * the question: a set membership check on an already-built key.
 */
internal object DesktopArtworkTelemetry {
    private var decodes = 0
    private var decodedBytes = 0L
    private var sourceBytes = 0L
    private var legacyPinBytes = 0L
    private var aboveLegacyPin = 0

    // Split, because the two halves answer different questions and moved in opposite directions.
    // Sized: artwork the destination sizing governs, where the 1536 px pin is the honest baseline.
    // Unsized: the hero paths, which the pin never applied to - crediting them against it is fiction.
    private var sizedDecodes = 0
    private var sizedBytes = 0L
    private var sizedPinBytes = 0L
    private var unsizedDecodes = 0
    private var unsizedBytes = 0L
    private var largestBytes = 0L
    private var largestDetail = ""

    // A URL loaded again from outside memory is an entry the cache had and lost.
    private val loadedUrls = HashSet<String>()
    private var reloads = 0
    private var loads = 0

    private val drawSamples = HashSet<String>()
    private var draws = 0
    private var oversizedDraws = 0
    private var drawnBytes = 0L
    private var suppliedBytes = 0L
    private var worstRatio = 0f
    private var worstDetail = ""

    /** Null until the first decode opens a window; 0 is a legitimate clock value, not a sentinel. */
    private var lastSummaryAtMs: Long? = null

    @Synchronized
    fun recordDecode(
        sourceWidth: Int,
        sourceHeight: Int,
        outWidth: Int,
        outHeight: Int,
        destinationSized: Boolean,
    ) {
        val outBytes = argbBytes(outWidth, outHeight)
        // What the same source would have cost under the old unconditional 1536 px pin. That is the
        // baseline this change has to beat for destination-sized artwork - and the one it must NOT
        // be credited against for the hero paths, which the pin never governed.
        val pinScale = minOf(
            1.0,
            LegacyPinPx.toDouble() / maxOf(sourceWidth, sourceHeight).toDouble(),
        )
        val pinBytes = argbBytes(
            (sourceWidth * pinScale).toInt().coerceAtLeast(1),
            (sourceHeight * pinScale).toInt().coerceAtLeast(1),
        )

        decodes++
        decodedBytes += outBytes
        sourceBytes += argbBytes(sourceWidth, sourceHeight)
        legacyPinBytes += pinBytes
        if (maxOf(outWidth, outHeight) > LegacyPinPx) aboveLegacyPin++
        if (outBytes > largestBytes) {
            largestBytes = outBytes
            largestDetail = "${outWidth}x$outHeight${if (destinationSized) "" else " unsized"}"
        }
        if (destinationSized) {
            sizedDecodes++
            sizedBytes += outBytes
            sizedPinBytes += pinBytes
        } else {
            unsizedDecodes++
            unsizedBytes += outBytes
        }
    }

    /**
     * One completed load as the UI saw it. [fromMemoryCache] false for a URL already loaded this
     * session means the cache held it and evicted it, which is what a scroll-back flash looks like
     * from here.
     */
    @Synchronized
    fun recordLoad(
        sourceUrl: String?,
        fromMemoryCache: Boolean,
        widthPx: Int = 0,
        heightPx: Int = 0,
        dataSource: String = "",
    ) {
        loads++
        // Hero backdrops are the only artwork this big, they are 83% of all decoded bytes, and the
        // memory cache runs pinned at its ceiling so they evict each other constantly. Basic mode -
        // the one display mode with a STATIC backdrop - is also the one that does not flicker, so
        // when a backdrop actually lands, and from where, is worth knowing to the frame.
        val bytes = widthPx.toLong() * heightPx.toLong() * 4L
        if (bytes >= LargeImageBytes) {
            artworkLog.i {
                "BACKDROP ${widthPx}x$heightPx ${bytes / (1024 * 1024)}MB from=$dataSource" +
                    (if (fromMemoryCache) "" else " (COLD - decoded now)")
            }
        }
        val url = sourceUrl ?: return
        if (loadedUrls.size >= MaxDrawSamples) loadedUrls.clear()
        if (!loadedUrls.add(url) && !fromMemoryCache) reloads++
    }

    /**
     * [sourceKey] identifies the bitmap, not the painter: the same decoded image drawn at the same
     * size by a rebuilt card is the same sample and must not be counted twice.
     */
    @Synchronized
    fun recordDraw(
        sourceKey: String?,
        suppliedWidth: Int,
        suppliedHeight: Int,
        drawWidth: Int,
        drawHeight: Int,
    ) {
        if (drawWidth <= 0 || drawHeight <= 0) return
        val key = "${sourceKey ?: "$suppliedWidth.$suppliedHeight"}|${drawWidth}x$drawHeight"
        if (drawSamples.size >= MaxDrawSamples) drawSamples.clear()
        if (!drawSamples.add(key)) return

        draws++
        drawnBytes += argbBytes(drawWidth, drawHeight)
        suppliedBytes += argbBytes(suppliedWidth, suppliedHeight)
        val ratio = maxOf(
            suppliedWidth.toFloat() / drawWidth.toFloat(),
            suppliedHeight.toFloat() / drawHeight.toFloat(),
        )
        // 2x on either axis is four times the pixels the screen can show: the symptom the request
        // sizing exists to remove, so it is worth naming the worst offender rather than averaging
        // it away.
        if (ratio >= 2f) oversizedDraws++
        if (ratio > worstRatio) {
            worstRatio = ratio
            worstDetail = "${suppliedWidth}x$suppliedHeight->${drawWidth}x$drawHeight"
        }
    }

    /**
     * Called from the decode path, which is exactly when there is something new to say. Emits at
     * most one line per interval and resets, so each line describes its own window rather than a
     * running total that flattens out.
     */
    fun maybeLogSummary(nowMs: Long = System.currentTimeMillis()) {
        summaryOrNull(nowMs)?.let { line -> artworkLog.i { line } }
    }

    /** The summary as a string, so its arithmetic can be asserted rather than eyeballed in a log. */
    @Synchronized
    fun summaryOrNull(nowMs: Long): String? {
        val openedAtMs = lastSummaryAtMs
        if (openedAtMs == null) {
            lastSummaryAtMs = nowMs
            return null
        }
        if (nowMs - openedAtMs < SummaryIntervalMs) return null
        val windowSeconds = (nowMs - openedAtMs) / 1000
        lastSummaryAtMs = nowMs
        if (decodes == 0 && draws == 0) return null

        val decodeLine = if (decodes == 0) "decodes=0" else {
            "decodes=$decodes decoded=${mb(decodedBytes)} src=${mb(sourceBytes)} " +
                "sized=$sizedDecodes/${mb(sizedBytes)} vsPin=${mb(sizedPinBytes)} " +
                "savedVsPin=${percentSaved(sizedBytes, sizedPinBytes)} " +
                "unsized=$unsizedDecodes/${mb(unsizedBytes)} above1536=$aboveLegacyPin " +
                "largest=${mb(largestBytes)}($largestDetail)"
        }
        val drawLine = if (draws == 0) "draws=0" else {
            "draws=$draws supplied=${mb(suppliedBytes)} drawn=${mb(drawnBytes)} " +
                "overdraw=${ratio(suppliedBytes, drawnBytes)} atLeast2x=$oversizedDraws " +
                "worst=${"%.2f".format(worstRatio)}x($worstDetail)"
        }
        val loadLine = "loads=$loads reloads=$reloads"
        val cacheLine = DesktopArtworkCaches.memorySummary() ?: "cache=unregistered"
        reset()
        return "${windowSeconds}s $decodeLine | $drawLine | $loadLine | $cacheLine"
    }

    @Synchronized
    fun resetForTest() {
        lastSummaryAtMs = null
        reset()
    }

    private fun reset() {
        decodes = 0
        decodedBytes = 0L
        sourceBytes = 0L
        legacyPinBytes = 0L
        aboveLegacyPin = 0
        sizedDecodes = 0
        sizedBytes = 0L
        sizedPinBytes = 0L
        unsizedDecodes = 0
        unsizedBytes = 0L
        largestBytes = 0L
        largestDetail = ""
        loadedUrls.clear()
        reloads = 0
        loads = 0
        drawSamples.clear()
        draws = 0
        oversizedDraws = 0
        drawnBytes = 0L
        suppliedBytes = 0L
        worstRatio = 0f
        worstDetail = ""
    }

    private fun argbBytes(width: Int, height: Int): Long = width.toLong() * height.toLong() * 4L

    private fun mb(bytes: Long): String = "${bytes / (1024 * 1024)}MB"

    private fun percentSaved(actual: Long, baseline: Long): String =
        if (baseline <= 0L) "n/a" else "${((baseline - actual) * 100 / baseline)}%"

    private fun ratio(numerator: Long, denominator: Long): String =
        if (denominator <= 0L) "n/a" else "%.2fx".format(numerator.toDouble() / denominator.toDouble())
}

