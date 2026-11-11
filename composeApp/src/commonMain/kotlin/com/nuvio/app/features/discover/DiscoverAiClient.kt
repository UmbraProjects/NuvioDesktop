package com.nuvio.app.features.discover

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Talks to whichever provider the user configured — plan §5.
 *
 * Raw HTTP over the app's own [httpRequestRaw], the same path TMDB, MDBList, Trakt and Simkl take.
 * A vendor SDK would be JVM-only and could not live in `commonMain` beside the prompt building and
 * parsing this calls, and the OpenAI-compatible half needs hand-written requests either way.
 *
 * **Anthropic is a real Messages API client, not an OpenAI-shaped request pointed at a different
 * host.** Different endpoint, different auth header, `system` as its own top-level field rather
 * than a message, and a `content` array of blocks in the reply.
 */
object DiscoverAiClient {
    private val log = Logger.withTag("DiscoverAi")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * One request at a time, across every row.
     *
     * Rows are generated on an explicit action, so the realistic concurrent case is an impatient
     * second click — and the cost of serving it is a second billable call to the user's own
     * account. Waiting is the cheaper answer.
     */
    private val inFlight = Mutex()

    private val tokenCount = MutableStateFlow(0L)

    /**
     * fork:facts-filler - tokens the provider has reported for answered requests since the app started (OpenAI-compatible
     * replies only, which is what Groq speaks). The filler reads it before and after a step to learn what the step cost,
     * because a free key is limited by tokens (a day's and a minute's), not by how many requests it makes.
     */
    val tokensUsed: Long get() = tokenCount.value

    /**
     * fork:ai-minute - the tokens each answered request cost in the last minute or so, per account and model (the key's limits
     * are counted against that), oldest first. Every feature's requests are here, the person's own included, so the background
     * filler can keep the whole key under its minute limit rather than only its own share.
     */
    private val recentSpends = MutableStateFlow<Map<String, List<AiTokenSpend>>>(emptyMap())

    /** fork:ai-minute - what [settings]'s account and model spent in the last [AI_SPEND_WINDOW_MS], oldest first. */
    fun recentTokenSpends(settings: DiscoverAiSettings): List<AiTokenSpend> {
        val cutoff = System.currentTimeMillis() - AI_SPEND_WINDOW_MS
        return recentSpends.value[gateKeyOf(settings)].orEmpty().filter { it.atMs > cutoff }
    }

    private fun recordSpend(settings: DiscoverAiSettings, tokens: Long) {
        val now = System.currentTimeMillis()
        val key = gateKeyOf(settings)
        recentSpends.update { all ->
            all + (key to (all[key].orEmpty().filter { it.atMs > now - AI_SPEND_WINDOW_MS } + AiTokenSpend(now, tokens)))
        }
    }

    private fun gateKeyOf(settings: DiscoverAiSettings): String = "${settings.effectiveBaseUrl}|${settings.effectiveModel}"

    /**
     * The ceiling on one request.
     *
     * Enforced by [httpRequestRaw]'s `callTimeoutMs` as well as by `withTimeoutOrNull`. The
     * coroutine timeout alone is not enough and never was: the HTTP call underneath blocks a
     * thread, so cancellation is not delivered until it returns on its own, and the per-read
     * timeouts it does have are refreshed by any traffic at all.
     */
    private const val TIMEOUT_MS = 90_000L

    /**
     * Anthropic's ceiling. Generous on purpose: on current models thinking is on by default and its
     * tokens count against this, so a tight cap truncates the answer rather than saving money —
     * `max_tokens` is a ceiling, not a charge, and only tokens actually produced are billed.
     */
    private const val ANTHROPIC_MAX_TOKENS = 16000

    /** Compat endpoints have no hidden reasoning budget to leave room for, bar a reasoning model. */
    private const val OPENAI_MAX_TOKENS = 8192

    /**
     * Keeps the thinking short on models that reason by default. This is a browse row funded by the
     * user's own key, not an intelligence-sensitive task — one constant to raise if the suggestions
     * disappoint.
     */
    private const val ANTHROPIC_EFFORT = "low"

    private const val ANTHROPIC_VERSION = "2023-06-01"

    /**
     * fork:story - true when [settings] can search the web inside the request: Groq's gpt-oss models have a server-side
     * `browser_search` tool (powered by Exa), which the answer then draws on. Nothing else here does.
     */
    fun canSearchWeb(settings: DiscoverAiSettings): Boolean =
        settings.provider == DiscoverAiProvider.Groq && settings.effectiveModel.startsWith("openai/gpt-oss")

    /** When each account's model may be asked again after a 429 (see the gate in [complete]); touched only under [inFlight]. */
    private val rateLimitedUntilMs = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Seconds until [settings]'s model may be asked again after a 429 (0 when it may be asked now); the background filler waits on this. */
    fun rateLimitedForSeconds(settings: DiscoverAiSettings): Int {
        val until = rateLimitedUntilMs.value["${settings.effectiveBaseUrl}|${settings.effectiveModel}"] ?: return 0
        return ((until - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(0)
    }
    private const val DEFAULT_RATE_LIMIT_WAIT_SECONDS = 60
    private const val MAX_RATE_LIMIT_WAIT_SECONDS = 3_600

    suspend fun complete(
        settings: DiscoverAiSettings,
        prompt: DiscoverAiPromptText,
        /** fork:story - let the model search the web first (see [canSearchWeb]); ignored where it cannot. The reply then holds the pages' snippets before the answer. */
        webSearch: Boolean = false,
    ): Result<String> {
        // `isConfigured`, not `isReady`: this checks that a request *can* be made, and leaves
        // "should this feature make one" to the caller. Recaps and Discover rows share the
        // credential and have separate switches.
        if (!settings.isConfigured) {
            return Result.failure(DiscoverAiException(DiscoverAiError.NotConfigured))
        }
        val startedAt = System.currentTimeMillis()
        return inFlight.withLock {
            // fork:ai-gate - after a 429 the provider has said when to come back (Groq's daily allowance: minutes to hours), and
            // every feature used to ask anyway, one request per song, each answered with another 429. Until that time, answer
            // the same way without a request. The key is the account's model, which is what the limits are counted against.
            val gateKey = gateKeyOf(settings)
            rateLimitedUntilMs.value[gateKey]?.let { until ->
                val left = ((until - System.currentTimeMillis() + 999) / 1000).toInt()
                if (left > 0) {
                    return@withLock Result.failure(
                        DiscoverAiException(DiscoverAiError.RateLimited(left, "Rate limited a moment ago; not asking again for ${left}s.")),
                    )
                }
                rateLimitedUntilMs.update { it - gateKey }
            }
            // Logged on both sides of the wait. A request that never comes back used to leave no trace
            // at all, which made "it just spun forever" indistinguishable from "nothing was ever sent".
            log.i { "request -> ${settings.effectiveModel} at ${settings.effectiveBaseUrl}" }
            val outcome = withTimeoutOrNull(TIMEOUT_MS) {
                runCatching {
                    when (settings.provider) {
                        DiscoverAiProvider.Anthropic -> callAnthropic(settings, prompt)
                        DiscoverAiProvider.OpenAiCompat,
                        DiscoverAiProvider.Groq -> callOpenAiCompat(settings, prompt, webSearch && canSearchWeb(settings))
                    }
                }
            }
            if (outcome == null) {
                log.w { "timed out after ${System.currentTimeMillis() - startedAt}ms" }
                return@withLock Result.failure(DiscoverAiException(DiscoverAiError.Timeout))
            }

            outcome
                .onSuccess { log.i { "reply in ${System.currentTimeMillis() - startedAt}ms, ${it.length} chars" } }
                .onFailure { error ->
                    val limited = (error as? DiscoverAiException)?.error as? DiscoverAiError.RateLimited
                    if (limited != null) {
                        val wait = (limited.retryAfterSeconds ?: DEFAULT_RATE_LIMIT_WAIT_SECONDS).coerceIn(1, MAX_RATE_LIMIT_WAIT_SECONDS)
                        rateLimitedUntilMs.update { it + (gateKey to System.currentTimeMillis() + wait * 1000L) }
                        log.w { "rate limited; no requests to ${settings.effectiveModel} for ${wait}s" }
                    }
                }
                // Anything that is not already typed is a transport failure — a socket timeout, a
                // DNS or TLS error. Left raw it reached the error mapper as "not a
                // DiscoverAiException" and was reported to the user as *not configured*, sending
                // them to check a key that was fine.
                .recoverCatching { error ->
                    if (error is DiscoverAiException) throw error
                    log.w(error) { "transport failure after ${System.currentTimeMillis() - startedAt}ms" }
                    throw DiscoverAiException(
                        DiscoverAiError.Transport(error::class.simpleName.orEmpty(), error.message.orEmpty()),
                    )
                }
        }
    }

    private suspend fun callAnthropic(
        settings: DiscoverAiSettings,
        prompt: DiscoverAiPromptText,
    ): String {
        val body = buildJsonObject {
            put("model", settings.effectiveModel)
            put("max_tokens", ANTHROPIC_MAX_TOKENS)
            // `system` is its own field here, not a message with role "system".
            put("system", prompt.system)
            put("output_config", buildJsonObject { put("effort", ANTHROPIC_EFFORT) })
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", prompt.user)
                })
            })
        }.toString()

        val response = httpRequestRaw(
            method = "POST",
            url = "${settings.effectiveBaseUrl}/v1/messages",
            headers = mapOf(
                "Content-Type" to "application/json",
                "x-api-key" to settings.apiKey,
                "anthropic-version" to ANTHROPIC_VERSION,
            ),
            body = body,
            callTimeoutMs = TIMEOUT_MS,
        )
        if (response.status !in 200..299) throw httpFailure(response)

        val parsed = json.parseToJsonElement(response.body) as? JsonObject
            ?: throw DiscoverAiException(DiscoverAiError.Unreadable)

        // A safety decline arrives as HTTP 200 with stop_reason "refusal", so checking the status
        // alone would read it as an empty answer and report "the model returned nothing".
        val stopReason = (parsed["stop_reason"] as? JsonPrimitive)?.contentOrNull
        if (stopReason == "refusal") {
            val explanation = (parsed["stop_details"] as? JsonObject)
                ?.let { (it["explanation"] as? JsonPrimitive)?.contentOrNull }
            throw DiscoverAiException(DiscoverAiError.Refused(explanation.orEmpty()))
        }

        // `content` is an array of blocks; only the text ones carry the answer.
        val text = (parsed["content"] as? JsonArray)
            ?.mapNotNull { block ->
                val obj = block as? JsonObject ?: return@mapNotNull null
                if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "text") return@mapNotNull null
                (obj["text"] as? JsonPrimitive)?.contentOrNull
            }
            ?.joinToString("\n")
            .orEmpty()

        if (text.isBlank()) {
            if (stopReason == "max_tokens") throw DiscoverAiException(DiscoverAiError.Truncated)
            // Logged because "nothing usable" is otherwise indistinguishable from the parse failure
            // in DiscoverAiGenerator, and the two want opposite fixes.
            log.w {
                "Anthropic returned no text: model=${settings.effectiveModel} " +
                    "stop=$stopReason blocks=${(parsed["content"] as? JsonArray)?.size ?: 0}"
            }
            throw DiscoverAiException(DiscoverAiError.Empty)
        }
        return text
    }

    private suspend fun callOpenAiCompat(
        settings: DiscoverAiSettings,
        prompt: DiscoverAiPromptText,
        webSearch: Boolean = false,
    ): String {
        val body = buildJsonObject {
            put("model", settings.effectiveModel)
            if (webSearch) {
                // Groq runs the search itself. Low effort: higher levels make long browsing sessions and burn tokens.
                put("tools", buildJsonArray { add(buildJsonObject { put("type", "browser_search") }) })
                put("tool_choice", "required")
                put("reasoning_effort", "low")
            }
            // `max_tokens` rather than `max_completion_tokens`: this field is what every
            // OpenAI-compatible server implements. OpenAI's own newest reasoning models want the
            // other name — if someone points this at one, that is the setting to revisit.
            put("max_tokens", OPENAI_MAX_TOKENS)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", prompt.system)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", prompt.user)
                })
            })
        }.toString()

        val response = httpRequestRaw(
            method = "POST",
            url = "${settings.effectiveBaseUrl}/chat/completions",
            headers = mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer ${settings.apiKey}",
            ),
            body = body,
            callTimeoutMs = TIMEOUT_MS,
        )
        if (response.status !in 200..299) throw httpFailure(response)

        val parsed = json.parseToJsonElement(response.body) as? JsonObject
            ?: throw DiscoverAiException(DiscoverAiError.Unreadable)

        // **A 2xx is not proof of success here.** OpenRouter answers some upstream failures — a
        // free-tier daily cap, an exhausted credit balance, no provider currently serving the
        // model — with HTTP 200 and an `error` object where `choices` should be. Checked before
        // the content is read, because the alternative is what actually shipped: the real reason
        // sitting in the body while the user is told the model "replied but not with suggestions".
        //
        // This is the same lesson as Anthropic's `stop_reason: "refusal"` above, on the other
        // provider. Status alone answers neither.
        (parsed["error"] as? JsonObject)?.let { error ->
            val message = (error["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val code = (error["code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            log.w { "OpenAI-compat returned an error inside a ${response.status}: code=$code $message" }
            throw DiscoverAiException(
                when (code) {
                    401, 403 -> DiscoverAiError.Unauthorized(message)
                    429 -> DiscoverAiError.RateLimited(retryAfterSeconds = retryAfterInMessage(message), detail = message)
                    else -> DiscoverAiError.Http(code ?: response.status, message)
                },
            )
        }

        ((parsed["usage"] as? JsonObject)?.get("total_tokens") as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            ?.takeIf { it > 0 }?.let { used ->
                tokenCount.update { it + used }
                recordSpend(settings, used)
            }

        val message = (parsed["choices"] as? JsonArray)
            ?.firstNotNullOfOrNull { choice -> (choice as? JsonObject)?.get("message") as? JsonObject }
        val text = (message?.get("content") as? JsonPrimitive)?.contentOrNull.orEmpty()

        if (text.isBlank()) {
            val finishReason = (parsed["choices"] as? JsonArray)
                ?.firstNotNullOfOrNull { choice ->
                    ((choice as? JsonObject)?.get("finish_reason") as? JsonPrimitive)?.contentOrNull
                }
            // Reasoning models put their thinking in a sibling field. Content empty *and*
            // reasoning present means the budget went entirely on thinking — a different fix
            // (raise the cap, or pick a non-reasoning model) from "the provider sent nothing".
            val reasoningLength =
                (message?.get("reasoning") as? JsonPrimitive)?.contentOrNull?.length ?: 0

            // The body is logged, not just measured. A length alone cost a whole round trip of
            // diagnosis when the answer was sitting in 158 characters of JSON; this is a provider
            // response, so it carries no prompt and no watch history.
            log.w {
                "OpenAI-compat returned no content: model=${settings.effectiveModel} " +
                    "host=${settings.effectiveBaseUrl} finishReason=$finishReason " +
                    "reasoningChars=$reasoningLength body=${response.body.take(400)}"
            }
            if (finishReason == "length" || reasoningLength > 0) {
                throw DiscoverAiException(DiscoverAiError.Truncated)
            }
            throw DiscoverAiException(DiscoverAiError.Empty)
        }
        return text
    }

    /**
     * Turns a non-2xx into a typed failure, carrying the provider's own message where there is one.
     *
     * Both shapes are tried because the two providers disagree: Anthropic answers
     * `{"error":{"message":…}}` and most compat servers do too, but some return a bare string.
     * The body is truncated — an HTML error page from a misconfigured base URL can be enormous.
     */
    private fun httpFailure(response: com.nuvio.app.features.addons.RawHttpResponse): DiscoverAiException {
        val status = response.status
        val detail = runCatching {
            ((json.parseToJsonElement(response.body) as? JsonObject)?.get("error") as? JsonObject)
                ?.let { (it["message"] as? JsonPrimitive)?.contentOrNull }
        }.getOrNull() ?: response.body.take(200)
        log.w { "AI provider returned $status" }
        return DiscoverAiException(
            when (status) {
                401, 403 -> DiscoverAiError.Unauthorized(detail)
                // 429 is its own outcome because its fix is unlike every other failure here: wait,
                // or stop using a rate-capped model. Telling someone to check their key — which is
                // what a generic HTTP error reads as — sends them to the one thing that is fine.
                429 -> DiscoverAiError.RateLimited(
                    retryAfterSeconds = response.retryAfterSeconds() ?: retryAfterInMessage(detail),
                    detail = detail,
                )

                else -> DiscoverAiError.Http(status, detail)
            }
        )
    }

    /**
     * Seconds until the limit lifts, if the provider said.
     *
     * `Retry-After` first — it is the standard and OpenRouter sets it when every upstream provider
     * returned a retry hint. `X-RateLimit-Reset` is the fallback, and it is an *epoch* value rather
     * than a duration, which is why it is converted rather than used directly. Header names are
     * matched case-insensitively because nothing guarantees the casing a server sends.
     */
    private fun com.nuvio.app.features.addons.RawHttpResponse.retryAfterSeconds(): Int? {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()

        header("Retry-After")?.toIntOrNull()?.let { return it.coerceAtLeast(0) }

        val reset = header("X-RateLimit-Reset")?.toLongOrNull() ?: return null
        // Sent in milliseconds by OpenRouter; treat a value too small to be millis as seconds.
        val resetMs = if (reset > 100_000_000_000L) reset else reset * 1000
        val remaining = (resetMs - System.currentTimeMillis()) / 1000
        return remaining.takeIf { it in 1..86_400 }?.toInt()
    }
}

/** Why a generation failed. Each needs a different thing from the user, so none of them is null. */
sealed interface DiscoverAiError {
    /** No key, no model, consent not given, or the feature is off. */
    data object NotConfigured : DiscoverAiError

    /** The key was rejected — a different fix from every other failure here. */
    data class Unauthorized(val detail: String) : DiscoverAiError

    data class Http(val status: Int, val detail: String) : DiscoverAiError

    /**
     * 429. Carries how long to wait when the provider said, because "try later" without a number
     * is not advice.
     */
    data class RateLimited(val retryAfterSeconds: Int?, val detail: String) : DiscoverAiError

    /** [DiscoverAiClient.TIMEOUT_MS] with no answer. */
    data object Timeout : DiscoverAiError

    /**
     * The request never reached a reply: socket timeout, DNS, TLS, connection reset.
     *
     * Its own case because the three plausible fixes — check the network, check the base URL, wait
     * and retry — are none of the things any other failure here asks for.
     */
    data class Transport(val kind: String, val detail: String) : DiscoverAiError

    /** 2xx whose body was not the documented shape — usually a base URL pointing at something else. */
    data object Unreadable : DiscoverAiError

    /** 2xx with no text in it. */
    data object Empty : DiscoverAiError

    /** The answer was cut off by the token ceiling before any text arrived. */
    data object Truncated : DiscoverAiError

    /** The provider's safety classifiers declined. Not an error to retry. */
    data class Refused(val explanation: String) : DiscoverAiError

    /**
     * The model answered with titles and none of them exist. Distinct from [Empty]: the fix is a
     * different prompt or a better model, not a retry.
     */
    data object NothingResolved : DiscoverAiError

    /**
     * A phase-8 preset was asked to run with no history slice behind it.
     *
     * Caught **before** a request rather than after one, because the prompt would be incoherent:
     * both phase-8 instructions open with "The list below is…" and [buildDiscoverAiPrompt] omits the
     * list when there are no seeds, so the model receives a reference to nothing and answers with a
     * question instead of an array — which then surfaces as [Empty] and reads as a provider fault.
     * The proposal path cannot reach this (it gates on the slice being big enough); picking the
     * preset by hand in the editor can.
     */
    data object NoHistorySlice : DiscoverAiError
}

class DiscoverAiException(val error: DiscoverAiError) : Exception(error.toString())

/** fork:ai-minute - one answered request's cost: when it came back and the tokens the provider reported for it. */
data class AiTokenSpend(val atMs: Long, val tokens: Long)

/** How far back [DiscoverAiClient.recentTokenSpends] looks: the provider's minute. */
const val AI_SPEND_WINDOW_MS = 60_000L

/**
 * fork:ai-minute - the wait a rate-limit message names when no header gave one. Groq says it in words: "Please try again in
 * 7.26s", "in 1m30.5s", "in 2h4m", "in 860ms". Rounded up to whole seconds; null when the message names no wait.
 */
internal fun retryAfterInMessage(message: String): Int? {
    val match = Regex("""try again in\s+((?:\d+(?:\.\d+)?(?:ms|h|m|s))+)""", RegexOption.IGNORE_CASE).find(message) ?: return null
    var seconds = 0.0
    Regex("""(\d+(?:\.\d+)?)(ms|h|m|s)""", RegexOption.IGNORE_CASE).findAll(match.groupValues[1]).forEach { part ->
        val n = part.groupValues[1].toDouble()
        seconds += when (part.groupValues[2].lowercase()) { "h" -> n * 3600; "m" -> n * 60; "ms" -> n / 1000; else -> n }
    }
    return kotlin.math.ceil(seconds).toInt().takeIf { it > 0 }
}
