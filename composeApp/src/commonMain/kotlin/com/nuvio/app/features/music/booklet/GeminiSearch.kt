package com.nuvio.app.features.music.booklet
// FORK UI (Spyder): Nick's Now Playing song notes, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.music.library.MusicLibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock

/**
 * fork:story - Google's Gemini as a backup writer for the song story, with its "grounding with Google Search": the model
 * searches Google itself and answers from what it read, which is what AI Mode does, through the official API. Used only
 * for a story the person opens (never the background filler), when the main AI key cannot answer: Groq's free daily
 * allowance runs out by the afternoon. Needs a free key from aistudio.google.com (Settings > Music); without one nothing
 * changes.
 */
internal object GeminiSearch {
    private val log = Logger.withTag("GeminiStory")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

    /** The current Flash model under Google's moving alias first, then a fixed one should the alias be refused. */
    private val MODELS = listOf("gemini-flash-latest", "gemini-2.5-flash")

    /** After a 429, how long to leave it when Google does not say. */
    private const val DEFAULT_WAIT_MS = 10 * 60_000L

    private const val CALL_TIMEOUT_MS = 60_000L

    @Volatile
    private var limitedUntilMs = 0L

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    private val key: String get() = MusicLibraryRepository.state.value.settings.geminiApiKey.trim()

    /** A key is set and Google has not just said to wait. */
    fun available(): Boolean = key.isNotEmpty() && now() >= limitedUntilMs

    /** The model's text answer to [prompt] under [system], having searched Google for it; a failure says why. */
    suspend fun complete(system: String, prompt: String): Result<String> {
        val apiKey = key.ifEmpty { return Result.failure(IllegalStateException("no Gemini key")) }
        if (now() < limitedUntilMs) return Result.failure(IllegalStateException("Gemini rate limited"))
        val body = buildJsonObject {
            putJsonObject("system_instruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", prompt) }) }
                })
            }
            putJsonArray("tools") { add(buildJsonObject { putJsonObject("google_search") {} }) }
        }.toString()
        for (model in MODELS) {
            val startedAt = now()
            val response = try {
                httpRequestRaw(
                    method = "POST",
                    url = "$BASE$model:generateContent",
                    headers = mapOf("Content-Type" to "application/json", "x-goog-api-key" to apiKey),
                    body = body,
                    callTimeoutMs = CALL_TIMEOUT_MS,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.w { "$model: ${error.message}" }
                return Result.failure(error)
            }
            when (response.status) {
                200 -> {
                    val text = answerText(response.body)
                        ?: return Result.failure(IllegalStateException("Gemini gave no text"))
                    log.i { "$model answered in ${now() - startedAt}ms after ${searchCount(response.body)} Google searches, ${text.length} chars" }
                    return Result.success(text)
                }
                404 -> {
                    log.i { "$model is not offered to this key; trying the next" }
                    continue
                }
                429 -> {
                    val waitMs = retryDelayMs(response.body) ?: DEFAULT_WAIT_MS
                    limitedUntilMs = now() + waitMs
                    log.w { "rate limited; not asking Gemini for ${waitMs / 1000}s" }
                    return Result.failure(IllegalStateException("Gemini rate limited"))
                }
                400, 401, 403 -> {
                    log.w { "Gemini refused the request (${response.status}); check the key in Settings > Music: ${response.body.take(200)}" }
                    return Result.failure(IllegalStateException("Gemini refused (${response.status})"))
                }
                else -> {
                    log.w { "$model: HTTP ${response.status} ${response.body.take(200)}" }
                    return Result.failure(IllegalStateException("Gemini HTTP ${response.status}"))
                }
            }
        }
        return Result.failure(IllegalStateException("no Gemini model answered"))
    }

    private fun candidate(body: String): JsonObject? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        return (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
    }

    /** The first candidate's text parts, joined. */
    internal fun answerText(body: String): String? {
        val content = candidate(body)?.get("content") as? JsonObject ?: return null
        val parts = (content["parts"] as? JsonArray).orEmpty()
        return parts.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.content }
            .joinToString("").trim().takeIf { it.isNotEmpty() }
    }

    private fun searchCount(body: String): Int {
        val grounding = candidate(body)?.get("groundingMetadata") as? JsonObject ?: return 0
        return (grounding["webSearchQueries"] as? JsonArray)?.size ?: 0
    }

    /** Google's "retryDelay": "37s" in a 429's details, in milliseconds. */
    internal fun retryDelayMs(body: String): Long? =
        Regex(""""retryDelay"\s*:\s*"(\d+)(?:\.\d+)?s"""").find(body)?.groupValues?.get(1)?.toLongOrNull()?.let { it * 1000 }
}
