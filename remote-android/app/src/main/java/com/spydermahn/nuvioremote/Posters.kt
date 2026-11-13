package com.spydermahn.nuvioremote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Posters and covers for the library grids: small (a grid cell, not a full screen), fetched four at a time, and kept
 * in memory up to a budget so scrolling back never fetches again. Separate from [Covers], which holds the few large
 * now-playing pictures.
 */
object Posters {
    private const val MAX_SIDE = 360
    private val loader = Executors.newFixedThreadPool(4) { runnable -> Thread(runnable, "nuvio-remote-posters").apply { isDaemon = true } }
        .asCoroutineDispatcher()

    private val kept = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** URLs that failed, so a missing poster is not asked for again on every scroll past it. */
    private val failed = java.util.Collections.synchronizedSet(HashSet<String>())

    fun cached(url: String): Bitmap? = kept.get(url)

    suspend fun load(url: String): Bitmap? {
        kept.get(url)?.let { return it }
        if (url in failed) return null
        return withContext(loader) {
            kept.get(url) ?: fetch(url)?.also { kept.put(url, it) } ?: run {
                failed += url
                null
            }
        }
    }

    private fun fetch(url: String): Bitmap? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val bytes = runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 6_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = true
            try {
                if (connection.responseCode !in 200..299) null else connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }.getOrNull() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_SIDE && bounds.outHeight / (sample * 2) >= MAX_SIDE) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/**
 * A poster or cover that loads itself: [url] first, then [fallback], and while neither has loaded (or when there is
 * none) a plain panel with [placeholder] written on it.
 */
@Composable
fun RemoteImage(url: String?, fallback: String?, placeholder: String, modifier: Modifier = Modifier) {
    val bitmap by produceState(initialValue = url?.let(Posters::cached) ?: fallback?.let(Posters::cached), url, fallback) {
        // The same slot can be handed a new poster (Tonight's Knockout swaps both titles in place): produceState keeps
        // the old bitmap in `value` when its keys change, so start from the new address's cache, never the old one.
        value = url?.let(Posters::cached) ?: fallback?.let(Posters::cached)
        if (value != null) return@produceState
        value = url?.let { Posters.load(it) } ?: fallback?.let { Posters.load(it) }
    }
    Box(modifier = modifier.background(Panel), contentAlignment = Alignment.Center) {
        val shown = bitmap
        if (shown != null) {
            Image(shown.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                placeholder,
                color = Muted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}
