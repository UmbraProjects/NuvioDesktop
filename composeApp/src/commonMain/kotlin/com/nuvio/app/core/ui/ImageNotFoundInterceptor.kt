package com.nuvio.app.core.ui

import coil3.Uri
import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.request.ErrorResult
import coil3.request.ImageResult
import kotlinx.coroutines.CancellationException

/**
 * Remembers image URLs the server said do not exist (HTTP 400, 404, 410) and fails them at once
 * for a while instead of asking again.
 *
 * Nothing cached those answers before, so the same missing logo or poster went back to the
 * network every time a card using it was composed again: one myaio poster that answers 400 was
 * requested three times in a five-minute session (2026-11-23), a missing metahub backdrop twice.
 * Each costs a request and 120-350 ms before the artwork chain can fall back to its next choice.
 *
 * Only permanent answers are kept; transient ones (timeouts, 408/429, 5xx) still go through the
 * retry and stale-if-error interceptors. Entries expire after [ttlMillis] so art that appears
 * later (a new title's logo) is picked up within the session.
 */
internal class ImageNotFoundInterceptor(
    private val memory: ImageNotFoundMemory = ImageNotFoundMemory.shared,
) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val key = imageNotFoundKey(request.data)
            ?: return chain.proceed()
        memory.recall(key)?.let { remembered ->
            return ErrorResult(image = null, request = request, throwable = remembered)
        }
        val outcome = try {
            chain.proceed()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (isMissingImageError(error)) memory.remember(key, error)
            throw error
        }
        if (outcome is ErrorResult && isMissingImageError(outcome.throwable)) {
            memory.remember(key, outcome.throwable)
        }
        return outcome
    }
}

/** Only network URLs: a file or resource that fails is not a server's answer. */
internal fun imageNotFoundKey(data: Any?): String? {
    val text = when (data) {
        is String -> data
        is Uri -> data.toString()
        else -> return null
    }
    return text.takeIf { it.startsWith("https://") || it.startsWith("http://") }
}

/** A server answer that the image is not there: 400 (bad id), 404 and 410. */
internal fun isMissingImageError(error: Throwable): Boolean =
    error is HttpException && error.response.code.let { it == 400 || it == 404 || it == 410 }

internal class ImageNotFoundMemory(
    private val ttlMillis: Long = DefaultTtlMillis,
    private val maxEntries: Int = DefaultMaxEntries,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private class Entry(val error: Throwable, val expiresAt: Long)

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    fun recall(key: String): Throwable? = synchronized(lock) {
        val entry = entries[key] ?: return null
        if (now() >= entry.expiresAt) {
            entries.remove(key)
            null
        } else {
            entry.error
        }
    }

    fun remember(key: String, error: Throwable) = synchronized(lock) {
        entries.remove(key)
        entries[key] = Entry(error, now() + ttlMillis)
        // Oldest first: LinkedHashMap keeps insertion order and re-inserting moves a key to the end.
        while (entries.size > maxEntries) {
            entries.remove(entries.keys.first())
        }
    }

    internal val size: Int get() = synchronized(lock) { entries.size }

    companion object {
        const val DefaultTtlMillis = 30L * 60L * 1000L
        const val DefaultMaxEntries = 2_000
        val shared = ImageNotFoundMemory()
    }
}
