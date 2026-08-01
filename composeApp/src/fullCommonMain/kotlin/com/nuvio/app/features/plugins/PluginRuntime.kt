package com.nuvio.app.features.plugins

import co.touchlab.kermit.Logger
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.select.Elements
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.generic_unknown
import org.jetbrains.compose.resources.getString
import kotlin.random.Random

/** Wall-clock budget for one scraper, including everything it waits on. */
private const val PLUGIN_TIMEOUT_MS = 60_000L

// Separate, much tighter bound on how long a plugin may keep the JS engine *busy*: an infinite
// loop or a pathological regex. quickjs-kt measures only time spent executing JavaScript here, so
// time awaiting __native_fetch does not count against it and a merely slow provider isn't punished.
// QuickJS interrupts itself and throws, which unwinds the native stack cleanly — unlike cancelling
// the coroutine from outside while native code is mid-call, which is what used to corrupt state.
private const val PLUGIN_JS_EXECUTION_TIMEOUT_MS = 15_000L

// Caps the JS heap of a single scraper so a runaway allocation surfaces as a catchable JS error
// instead of exhausting the process. Generous: response bodies are already capped at 1 MB each.
private const val PLUGIN_MEMORY_LIMIT_BYTES = 128L * 1024 * 1024

// Each scraper gets its own QuickJS runtime, and separate runtimes are independent native state, so
// running them at the same time is fine — the access violations that forced this to 1 came from
// quickjs-kt 1.0.5's native lifetime/locking bugs (close racing a binding callback or a pending
// job), fixed upstream in 1.0.7. This cap is now only about resources: a repository like
// All-in-One ships 60+ scrapers, and 60 simultaneous native heaps plus 60 parsed DOMs costs a lot
// of memory for no extra throughput on work that is almost entirely network-bound.
private const val MAX_CONCURRENT_PLUGIN_RUNTIMES = 12

// Deliberately not Dispatchers.Default. That pool is sized to the CPU count and shared with the
// rest of the app, while a scraper spends nearly all its time waiting on provider HTTP. QuickJS's
// own pending-job loop also runs on whatever dispatcher its runtime was created with, so parking
// scrapers on Default starves both the app and the JS event loops driving those same scrapers.
private val pluginDispatcher = Dispatchers.IO

// Cheerio's `:contains("x")` takes quoted text; Ksoup's takes it bare.
private val CHEERIO_CONTAINS_REGEX = Regex(""":contains\([\"']([^\"']+)[\"']\)""")

private const val MAX_FETCH_BODY_CHARS = 1024 * 1024
private const val MAX_FETCH_HEADER_VALUE_CHARS = 8 * 1024
private const val FETCH_TRUNCATION_SUFFIX = "\n...[truncated]"

private val PLUGIN_FORBIDDEN_REQUEST_HEADERS = setOf(
    "accept-encoding",
    "connection",
    "content-length",
    "expect",
    "host",
    "http2-settings",
    "keep-alive",
    "proxy-connection",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
)

internal object PluginRuntime {
    private val log = Logger.withTag("PluginRuntime")
    private val json = Json {
        ignoreUnknownKeys = true
    }

    // Bounds how many QuickJS runtimes exist at once; shared by stream scrapers and settings
    // evaluation. Not a correctness guard — see MAX_CONCURRENT_PLUGIN_RUNTIMES.
    private val runtimeSlots = Semaphore(MAX_CONCURRENT_PLUGIN_RUNTIMES)

    suspend fun executePlugin(
        code: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        scraperId: String,
        scraperSettings: Map<String, Any> = emptyMap(),
    ): List<PluginRuntimeResult> = withContext(pluginDispatcher) {
        // The slot is taken outside the timeout on purpose. Charging queue time to the budget only
        // works when the queue is short; with more enabled scrapers than slots the ones at the back
        // spent their entire budget waiting and timed out having never run a line of JavaScript.
        runtimeSlots.withPermit {
            withConfinedRuntimeThread(scraperId) { confined ->
                withTimeout(PLUGIN_TIMEOUT_MS) {
                    executePluginInternal(
                        code = code,
                        tmdbId = tmdbId,
                        mediaType = mediaType,
                        season = season,
                        episode = episode,
                        scraperId = scraperId,
                        scraperSettings = scraperSettings,
                        runtimeDispatcher = confined,
                    )
                }
            }
        }
    }

    /**
     * Runs [block] on a thread dedicated to one QuickJS runtime, and hands it that dispatcher.
     *
     * QuickJS runtimes are single-threaded by design: the runtime caches a stack boundary for its
     * overflow check, so touching one from a different thread than the last call used compares the
     * current stack pointer against a boundary belonging to some other thread's stack. Running on
     * `Dispatchers.IO` did exactly that — a scraper does three evaluations plus a pending-job loop,
     * each a suspension point free to resume on a different pool thread. The result was an
     * `EXCEPTION_ACCESS_VIOLATION` inside the native evaluate (and, when it stopped short of a
     * crash, a scraper whose result was silently dropped: roughly 1% of runs at 12-way concurrency).
     *
     * Confinement is per execution, not global: runtimes still run concurrently, each on its own
     * thread, so throughput is unchanged. The thread costs about a tenth of a millisecond to start
     * against a scraper that then spends seconds on the network, and [runtimeSlots] already bounds
     * how many can exist at once.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun <T> withConfinedRuntimeThread(
        scraperId: String,
        block: suspend (CoroutineDispatcher) -> T,
    ): T {
        val confined = newSingleThreadContext("plugin-runtime-$scraperId")
        return try {
            withContext(confined) { block(confined) }
        } finally {
            confined.close()
        }
    }

    suspend fun getPluginSettingsLayout(
        code: String,
        scraperId: String,
    ): String? = withContext(pluginDispatcher) {
        runtimeSlots.withPermit {
            withConfinedRuntimeThread(scraperId) { confined ->
                withTimeout(PLUGIN_TIMEOUT_MS) {
                    var layoutJson: String? = null
                    quickJs(confined) {
                        applyPluginRuntimeLimits()
                        // onSettings implementations fetch remote option lists and parse them, so
                        // they need the same host API surface the scraper entry point gets.
                        registerPluginBindings(scraperId, PluginDomCache())
                        function("__capture_settings_result") { args ->
                            layoutJson = args.getOrNull(0)?.toString()
                            null
                        }

                        evaluate<Any?>(buildPolyfillCode(scraperId, "{}"))
                        evaluate<Any?>(wrapPluginModule(code))
                        evaluate<Any?>(
                            """
                                (async function() {
                                    try {
                                        var onSettings = module.exports.onSettings || globalThis.onSettings;
                                        var layout = typeof onSettings === 'function' ? await onSettings() : [];
                                        __capture_settings_result(JSON.stringify(layout || []));
                                    } catch (e) {
                                        console.error("onSettings error:", e);
                                        __capture_settings_result("[]");
                                    }
                                })();
                            """.trimIndent(),
                        )
                    }
                    layoutJson
                }
            }
        }
    }

    private suspend fun executePluginInternal(
        code: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        scraperId: String,
        scraperSettings: Map<String, Any>,
        runtimeDispatcher: CoroutineDispatcher,
    ): List<PluginRuntimeResult> {
        val dom = PluginDomCache()
        var resultJson = "[]"

        try {
            quickJs(runtimeDispatcher) {
                applyPluginRuntimeLimits()
                registerPluginBindings(scraperId, dom)

                function("__capture_result") { args ->
                    resultJson = args.getOrNull(0)?.toString() ?: "[]"
                    null
                }

                val settingsJson = toJsonElement(scraperSettings).toString()
                evaluate<Any?>(buildPolyfillCode(scraperId, settingsJson))
                evaluate<Any?>(wrapPluginModule(code))

                val tmdbIdArg = JsonPrimitive(tmdbId).toString()
                val mediaTypeArg = JsonPrimitive(mediaType).toString()
                val seasonArg = season?.toString() ?: "undefined"
                val episodeArg = episode?.toString() ?: "undefined"
                evaluate<Any?>(
                    """
                        (async function() {
                            try {
                                var getStreams = module.exports.getStreams || globalThis.getStreams;
                                if (!getStreams) {
                                    console.error("getStreams function not found on module.exports or globalThis");
                                    __capture_result(JSON.stringify([]));
                                    return;
                                }
                                var result = await getStreams($tmdbIdArg, $mediaTypeArg, $seasonArg, $episodeArg);
                                __capture_result(JSON.stringify(result || []));
                            } catch (e) {
                                console.error("getStreams error:", e && e.message ? e.message : e, e && e.stack ? e.stack : "");
                                __capture_result(JSON.stringify([]));
                            }
                        })();
                    """.trimIndent(),
                )
            }

            return parseJsonResults(resultJson, scraperId)
        } finally {
            dom.clear()
        }
    }

    private fun QuickJs.applyPluginRuntimeLimits() {
        memoryLimit = PLUGIN_MEMORY_LIMIT_BYTES
        evaluationTimeoutMillis = PLUGIN_JS_EXECUTION_TIMEOUT_MS
    }

    private fun wrapPluginModule(code: String): String = """
        var module = { exports: {} };
        var exports = module.exports;
        (function() {
            $code
        })();
    """.trimIndent()

    /** Everything the polyfill in [buildPolyfillCode] expects to find on the host side. */
    private fun QuickJs.registerPluginBindings(scraperId: String, dom: PluginDomCache) {
        define("console") {
            function("log") { args ->
                log.d { "Plugin:$scraperId ${args.joinToString(" ") { it?.toString() ?: "null" }}" }
                null
            }
            function("error") { args ->
                log.e { "Plugin:$scraperId ${args.joinToString(" ") { it?.toString() ?: "null" }}" }
                null
            }
            function("warn") { args ->
                log.w { "Plugin:$scraperId ${args.joinToString(" ") { it?.toString() ?: "null" }}" }
                null
            }
            function("info") { args ->
                log.i { "Plugin:$scraperId ${args.joinToString(" ") { it?.toString() ?: "null" }}" }
                null
            }
            function("debug") { args ->
                log.d { "Plugin:$scraperId ${args.joinToString(" ") { it?.toString() ?: "null" }}" }
                null
            }
        }

        // Async, not a blocking binding: this returns a JS promise, so the plugin's own
        // Promise.all over several providers actually runs in parallel instead of being flattened
        // into one request at a time, and the thread is free while the network is in flight.
        asyncFunction("__native_fetch") { args ->
            val url = args.getOrNull(0)?.toString() ?: ""
            val method = args.getOrNull(1)?.toString() ?: "GET"
            val headersJson = args.getOrNull(2)?.toString() ?: "{}"
            val body = args.getOrNull(3)?.toString() ?: ""
            val followRedirects = args.getOrNull(4) as? Boolean ?: true
            try {
                performNativeFetch(url, method, headersJson, body, followRedirects)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                log.e(error) { "Plugin:$scraperId fetch failed for $method $url" }
                JsonObject(
                    mapOf(
                        "ok" to JsonPrimitive(false),
                        "status" to JsonPrimitive(0),
                        "statusText" to JsonPrimitive(error.message ?: "Fetch failed"),
                        "url" to JsonPrimitive(url),
                        "body" to JsonPrimitive(""),
                        "headers" to JsonObject(emptyMap()),
                    ),
                ).toString()
            }
        }

        function("__crypto_digest_hex") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: "SHA256"
            val data = args.getOrNull(1)?.toString() ?: ""
            runCatching { pluginDigestHex(algorithm, data) }.getOrDefault("")
        }

        function("__crypto_hmac_hex") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: "SHA256"
            val key = args.getOrNull(1)?.toString() ?: ""
            val data = args.getOrNull(2)?.toString() ?: ""
            runCatching { pluginHmacHex(algorithm, key, data) }.getOrDefault("")
        }

        function("__crypto_base64_encode") { args ->
            val data = args.getOrNull(0)?.toString() ?: ""
            runCatching { pluginBase64Encode(data) }.getOrDefault("")
        }

        function("__crypto_base64_decode") { args ->
            val data = args.getOrNull(0)?.toString() ?: ""
            runCatching { pluginBase64Decode(data) }.getOrDefault("")
        }

        function("__crypto_utf8_to_hex") { args ->
            val data = args.getOrNull(0)?.toString() ?: ""
            runCatching { pluginUtf8ToHex(data) }.getOrDefault("")
        }

        function("__crypto_hex_to_utf8") { args ->
            val data = args.getOrNull(0)?.toString() ?: ""
            runCatching { pluginHexToUtf8(data) }.getOrDefault("")
        }

        function("__parse_url") { args ->
            parseUrl(args.getOrNull(0)?.toString() ?: "")
        }

        function("__cheerio_load") { args ->
            dom.load(args.getOrNull(0)?.toString() ?: "")
        }

        function("__cheerio_select") { args ->
            dom.select(
                docId = args.getOrNull(0)?.toString() ?: "",
                selector = args.getOrNull(1)?.toString() ?: "",
            )
        }

        function("__cheerio_find") { args ->
            dom.find(
                docId = args.getOrNull(0)?.toString() ?: "",
                elementId = args.getOrNull(1)?.toString() ?: "",
                selector = args.getOrNull(2)?.toString() ?: "",
            )
        }

        function("__cheerio_text") { args ->
            dom.text(args.getOrNull(1)?.toString() ?: "")
        }

        function("__cheerio_html") { args ->
            dom.outerHtml(
                docId = args.getOrNull(0)?.toString() ?: "",
                elementId = args.getOrNull(1)?.toString() ?: "",
            )
        }

        function("__cheerio_inner_html") { args ->
            dom.innerHtml(args.getOrNull(1)?.toString() ?: "")
        }

        function("__cheerio_attr") { args ->
            dom.attr(
                elementId = args.getOrNull(1)?.toString() ?: "",
                attrName = args.getOrNull(2)?.toString() ?: "",
            )
        }

        function("__cheerio_next") { args ->
            dom.sibling(
                docId = args.getOrNull(0)?.toString() ?: "",
                elementId = args.getOrNull(1)?.toString() ?: "",
                forward = true,
            )
        }

        function("__cheerio_prev") { args ->
            dom.sibling(
                docId = args.getOrNull(0)?.toString() ?: "",
                elementId = args.getOrNull(1)?.toString() ?: "",
                forward = false,
            )
        }
    }

    /**
     * Ksoup documents and elements stay on the Kotlin side; JavaScript only ever sees opaque ids.
     * One instance per QuickJS runtime, so it needs no synchronization — bindings for a given
     * runtime are only ever invoked from that runtime's own evaluation.
     */
    private class PluginDomCache {
        private val documents = mutableMapOf<String, Document>()
        private val elements = mutableMapOf<String, Element>()
        private var idCounter = 0

        fun load(html: String): String {
            val docId = "doc_${idCounter++}_${Random.nextInt(0, Int.MAX_VALUE)}"
            documents[docId] = Ksoup.parse(html)
            return docId
        }

        fun select(docId: String, selector: String): String {
            val doc = documents[docId] ?: return "[]"
            return runCatching {
                val normalized = selector.replace(CHEERIO_CONTAINS_REGEX, ":contains($1)")
                val matches = if (normalized.isEmpty()) Elements() else doc.select(normalized)
                encodeIds(matches.mapIndexed { index, el -> remember("$docId:$index:${el.hashCode()}", el) })
            }.getOrDefault("[]")
        }

        fun find(docId: String, elementId: String, selector: String): String {
            val element = elements[elementId] ?: return "[]"
            return runCatching {
                val normalized = selector.replace(CHEERIO_CONTAINS_REGEX, ":contains($1)")
                val matches = element.select(normalized)
                encodeIds(matches.mapIndexed { index, el -> remember("$docId:find:$index:${el.hashCode()}", el) })
            }.getOrDefault("[]")
        }

        fun text(elementIds: String): String = elementIds.split(",")
            .filter { it.isNotEmpty() }
            .mapNotNull { elements[it]?.text() }
            .joinToString(" ")

        fun outerHtml(docId: String, elementId: String): String = if (elementId.isEmpty()) {
            documents[docId]?.html().orEmpty()
        } else {
            elements[elementId]?.html().orEmpty()
        }

        fun innerHtml(elementId: String): String = elements[elementId]?.html().orEmpty()

        fun attr(elementId: String, attrName: String): String {
            val value = elements[elementId]?.attr(attrName)
            return if (value.isNullOrEmpty()) "__UNDEFINED__" else value
        }

        fun sibling(docId: String, elementId: String, forward: Boolean): String {
            val element = elements[elementId] ?: return "__NONE__"
            val sibling = if (forward) element.nextElementSibling() else element.previousElementSibling()
            if (sibling == null) return "__NONE__"
            val key = if (forward) "next" else "prev"
            return remember("$docId:$key:${sibling.hashCode()}", sibling)
        }

        fun clear() {
            documents.clear()
            elements.clear()
        }

        private fun remember(id: String, element: Element): String {
            elements[id] = element
            return id
        }

        private fun encodeIds(ids: List<String>): String =
            "[" + ids.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" } + "]"
    }

    private suspend fun performNativeFetch(
        url: String,
        method: String,
        headersJson: String,
        body: String,
        followRedirects: Boolean,
    ): String {
        val headers = sanitizePluginRequestHeaders(parseHeaders(headersJson)).toMutableMap()
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        }

        val response = httpRequestRaw(
            method = method,
            url = url,
            headers = headers,
            body = body,
            followRedirects = followRedirects,
        )

        val truncated = response.body.length > MAX_FETCH_BODY_CHARS
        val responseHeaders = response.headers
            .mapKeys { (key, _) -> key.lowercase() }
            .mapValues { (_, value) -> truncateString(value, MAX_FETCH_HEADER_VALUE_CHARS) }
        return JsonObject(
            mapOf(
                "ok" to JsonPrimitive(response.status in 200..299),
                "status" to JsonPrimitive(response.status),
                "statusText" to JsonPrimitive(response.statusText),
                "url" to JsonPrimitive(response.url),
                "body" to JsonPrimitive(truncateString(response.body, MAX_FETCH_BODY_CHARS)),
                "headers" to JsonObject(responseHeaders.mapValues { JsonPrimitive(it.value) }),
                "truncated" to JsonPrimitive(truncated),
            ),
        ).toString()
    }

    private fun parseHeaders(headersJson: String): Map<String, String> {
        return runCatching {
            val obj = json.parseToJsonElement(headersJson) as? JsonObject ?: JsonObject(emptyMap())
            obj.entries
                .mapNotNull { (key, value) ->
                    value.jsonPrimitive.contentOrNull?.let { key to it }
                }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    private fun parseUrl(urlString: String): String {
        return try {
            val parsed = io.ktor.http.Url(urlString)
            JsonObject(
                mapOf(
                    "protocol" to JsonPrimitive("${parsed.protocol.name}:"),
                    "host" to JsonPrimitive(
                        if (parsed.port != parsed.protocol.defaultPort) {
                            "${parsed.host}:${parsed.port}"
                        } else {
                            parsed.host
                        },
                    ),
                    "hostname" to JsonPrimitive(parsed.host),
                    "port" to JsonPrimitive(
                        if (parsed.port != parsed.protocol.defaultPort) parsed.port.toString() else "",
                    ),
                    "pathname" to JsonPrimitive(parsed.encodedPath.ifBlank { "/" }),
                    "search" to JsonPrimitive(parsed.encodedQuery?.let { "?$it" } ?: ""),
                    "hash" to JsonPrimitive(parsed.encodedFragment?.let { "#$it" } ?: ""),
                ),
            ).toString()
        } catch (_: Exception) {
            JsonObject(
                mapOf(
                    "protocol" to JsonPrimitive(""),
                    "host" to JsonPrimitive(""),
                    "hostname" to JsonPrimitive(""),
                    "port" to JsonPrimitive(""),
                    "pathname" to JsonPrimitive("/"),
                    "search" to JsonPrimitive(""),
                    "hash" to JsonPrimitive(""),
                ),
            ).toString()
        }
    }

    private fun truncateString(value: String, maxChars: Int): String {
        if (value.length <= maxChars) return value
        val end = maxChars - FETCH_TRUNCATION_SUFFIX.length
        if (end <= 0) return FETCH_TRUNCATION_SUFFIX.take(maxChars)
        return value.substring(0, end) + FETCH_TRUNCATION_SUFFIX
    }

    internal fun parseJsonResults(rawJson: String, scraperId: String = "unknown"): List<PluginRuntimeResult> {
        val normalizedJson = normalizePluginJsonPayload(rawJson)
        val array = runCatching { json.parseToJsonElement(normalizedJson) as? JsonArray }
            .getOrNull()
        val elements = array?.toList() ?: salvagePluginJsonArrayItems(normalizedJson).also { salvaged ->
            if (salvaged.isNotEmpty()) {
                log.w { "Plugin:$scraperId salvaged ${salvaged.size} results from malformed JSON (${rawJson.length} chars)" }
            }
        }

        if (elements.isEmpty() && normalizedJson != "[]") {
            val preview = normalizedJson.take(240).replace("\n", "\\n")
            val suffix = normalizedJson.takeLast(120).replace("\n", "\\n")
            log.e { "Plugin:$scraperId failed to parse result JSON (${rawJson.length} chars), preview=$preview suffix=$suffix" }
        }

        return elements.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val url = when (val urlValue = item["url"]) {
                    is JsonPrimitive -> urlValue.contentOrNull?.takeIf { it.isNotBlank() }
                    is JsonObject -> urlValue["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    else -> null
                } ?: return@mapNotNull null

                // Plugins in the mobile ecosystem use both a compact top-level `headers`
                // object and the Stremio-shaped `behaviorHints.proxyHeaders.request` object.
                // Keep both forms: dropping the latter loses Referer/Origin/cookie headers and
                // makes otherwise valid provider links fail with 403 when handed to mpv.
                val nestedHeaders = item
                    .jsonObjectOrNull("behaviorHints")
                    ?.jsonObjectOrNull("proxyHeaders")
                    ?.jsonObjectOrNull("request")
                    .stringMap()
                val headers = (nestedHeaders + item.jsonObjectOrNull("headers").stringMap())
                    .takeIf { it.isNotEmpty() }

                PluginRuntimeResult(
                    title = item.stringOrNull("title") ?: item.stringOrNull("name") ?: unknownResultTitle(),
                    name = item.stringOrNull("name"),
                    url = url,
                    quality = item.stringOrNull("quality"),
                    size = item.stringOrNull("size"),
                    language = item.stringOrNull("language"),
                    provider = item.stringOrNull("provider"),
                    type = item.stringOrNull("type"),
                    seeders = item["seeders"]?.jsonPrimitive?.intOrNull,
                    peers = item["peers"]?.jsonPrimitive?.intOrNull,
                    infoHash = item.stringOrNull("infoHash"),
                    headers = headers,
                )
            }.filter { it.url.isNotBlank() }
    }

    // Resolved once instead of per untitled result: getString is a suspend resource lookup, and
    // blocking on it inside the per-item mapping meant one runBlocking per stream, per scraper.
    @Volatile
    private var cachedUnknownResultTitle: String? = null

    private fun unknownResultTitle(): String =
        cachedUnknownResultTitle
            ?: runBlocking { getString(Res.string.generic_unknown) }.also { cachedUnknownResultTitle = it }

    private fun JsonObject.stringOrNull(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && !it.contains("[object") }
            ?.let(::repairPluginTextEncoding)

    private fun JsonObject.jsonObjectOrNull(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject?.stringMap(): Map<String, String> =
        this
            ?.mapNotNull { (key, value) ->
                (value as? JsonPrimitive)
                    ?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { key to it }
            }
            ?.toMap()
            .orEmpty()

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value.toDouble())
        is Map<*, *> -> JsonObject(
            value.entries
                .filter { it.key is String }
                .associate { (it.key as String) to toJsonElement(it.value) },
        )
        is Iterable<*> -> JsonArray(value.map(::toJsonElement))
        else -> JsonPrimitive(value.toString())
    }

    private fun buildPolyfillCode(scraperId: String, settingsJson: String): String {
        return """
            globalThis.SCRAPER_ID = "$scraperId";
            globalThis.SCRAPER_SETTINGS = $settingsJson;
            if (typeof globalThis.global === 'undefined') globalThis.global = globalThis;
            if (typeof globalThis.window === 'undefined') globalThis.window = globalThis;
            if (typeof globalThis.self === 'undefined') globalThis.self = globalThis;

            var fetch = async function(url, options) {
                options = options || {};
                var method = (options.method || 'GET').toUpperCase();
                var headers = options.headers || {};
                var body = options.body || '';
                var followRedirects = options.redirect !== 'manual';
                var result = await __native_fetch(url, method, JSON.stringify(headers), body, followRedirects);
                var parsed = JSON.parse(result);
                return {
                    ok: parsed.ok,
                    status: parsed.status,
                    statusText: parsed.statusText,
                    url: parsed.url,
                    headers: {
                        get: function(name) {
                            return parsed.headers[name.toLowerCase()] || null;
                        }
                    },
                    text: function() { return Promise.resolve(parsed.body); },
                    json: function() {
                        try {
                            if (parsed.body === null || parsed.body === undefined || parsed.body === '') {
                                return Promise.resolve(null);
                            }
                            return Promise.resolve(JSON.parse(parsed.body));
                        } catch (e) {
                            return Promise.resolve(null);
                        }
                    }
                };
            };

            if (typeof AbortSignal === 'undefined') {
                var AbortSignal = function() { this.aborted = false; this.reason = undefined; this._listeners = []; };
                AbortSignal.prototype.addEventListener = function(type, listener) {
                    if (type !== 'abort' || typeof listener !== 'function') return;
                    this._listeners.push(listener);
                };
                AbortSignal.prototype.removeEventListener = function(type, listener) {
                    if (type !== 'abort') return;
                    this._listeners = this._listeners.filter(function(l) { return l !== listener; });
                };
                AbortSignal.prototype.dispatchEvent = function(event) {
                    if (!event || event.type !== 'abort') return true;
                    for (var i = 0; i < this._listeners.length; i++) {
                        try { this._listeners[i].call(this, event); } catch (e) {}
                    }
                    return true;
                };
                globalThis.AbortSignal = AbortSignal;
            }

            if (typeof AbortController === 'undefined') {
                var AbortController = function() { this.signal = new AbortSignal(); };
                AbortController.prototype.abort = function(reason) {
                    if (this.signal.aborted) return;
                    this.signal.aborted = true;
                    this.signal.reason = reason;
                    this.signal.dispatchEvent({ type: 'abort' });
                };
                globalThis.AbortController = AbortController;
            }

            if (typeof atob === 'undefined') {
                globalThis.atob = function(input) {
                    var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                    var str = String(input).replace(/=+$/, '');
                    if (str.length % 4 === 1) throw new Error('InvalidCharacterError');
                    var output = '';
                    var bc = 0, bs, buffer, idx = 0;
                    while ((buffer = str.charAt(idx++))) {
                        buffer = chars.indexOf(buffer);
                        if (buffer === -1) continue;
                        bs = bc % 4 ? bs * 64 + buffer : buffer;
                        if (bc++ % 4) output += String.fromCharCode(255 & (bs >> ((-2 * bc) & 6)));
                    }
                    return output;
                };
            }

            if (typeof btoa === 'undefined') {
                globalThis.btoa = function(input) {
                    var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                    var str = String(input);
                    var output = '';
                    for (var block, charCode, idx = 0, map = chars;
                         str.charAt(idx | 0) || (map = '=', idx % 1);
                         output += map.charAt(63 & (block >> (8 - (idx % 1) * 8)))) {
                        charCode = str.charCodeAt(idx += 3 / 4);
                        if (charCode > 0xFF) throw new Error('InvalidCharacterError');
                        block = (block << 8) | charCode;
                    }
                    return output;
                };
            }

            var URL = function(urlString, base) {
                var fullUrl = urlString;
                if (base && !/^https?:\/\//i.test(urlString)) {
                    var b = typeof base === 'string' ? base : base.href;
                    if (urlString.charAt(0) === '/') {
                        var m = b.match(/^(https?:\/\/[^\/]+)/);
                        fullUrl = m ? m[1] + urlString : urlString;
                    } else {
                        fullUrl = b.replace(/\/[^\/]*$/, '/') + urlString;
                    }
                }
                var parsed = __parse_url(fullUrl);
                var data = JSON.parse(parsed);
                this.href = fullUrl;
                this.protocol = data.protocol;
                this.host = data.host;
                this.hostname = data.hostname;
                this.port = data.port;
                this.pathname = data.pathname;
                this.search = data.search;
                this.hash = data.hash;
                this.origin = data.protocol + '//' + data.host;
                this.searchParams = new URLSearchParams(data.search || '');
            };
            URL.prototype.toString = function() { return this.href; };

            var URLSearchParams = function(init) {
                this._params = {};
                var self = this;
                if (init && typeof init === 'object' && !Array.isArray(init)) {
                    Object.keys(init).forEach(function(key) { self._params[key] = String(init[key]); });
                } else if (typeof init === 'string') {
                    init.replace(/^\?/, '').split('&').forEach(function(pair) {
                        var parts = pair.split('=');
                        if (parts[0]) self._params[decodeURIComponent(parts[0])] = decodeURIComponent(parts[1] || '');
                    });
                }
            };
            URLSearchParams.prototype.toString = function() {
                var self = this;
                return Object.keys(this._params).map(function(key) {
                    return encodeURIComponent(key) + '=' + encodeURIComponent(self._params[key]);
                }).join('&');
            };
            URLSearchParams.prototype.get = function(key) { return this._params.hasOwnProperty(key) ? this._params[key] : null; };
            URLSearchParams.prototype.set = function(key, value) { this._params[key] = String(value); };
            URLSearchParams.prototype.append = function(key, value) { this._params[key] = String(value); };
            URLSearchParams.prototype.has = function(key) { return this._params.hasOwnProperty(key); };
            URLSearchParams.prototype.delete = function(key) { delete this._params[key]; };
            URLSearchParams.prototype.keys = function() { return Object.keys(this._params); };
            URLSearchParams.prototype.values = function() {
                var self = this;
                return Object.keys(this._params).map(function(k) { return self._params[k]; });
            };
            URLSearchParams.prototype.entries = function() {
                var self = this;
                return Object.keys(this._params).map(function(k) { return [k, self._params[k]]; });
            };
            URLSearchParams.prototype.forEach = function(callback) {
                var self = this;
                Object.keys(this._params).forEach(function(key) { callback(self._params[key], key, self); });
            };
            URLSearchParams.prototype.getAll = function(key) {
                return this._params.hasOwnProperty(key) ? [this._params[key]] : [];
            };
            URLSearchParams.prototype.sort = function() {
                var sorted = {};
                var self = this;
                Object.keys(this._params).sort().forEach(function(k) { sorted[k] = self._params[k]; });
                this._params = sorted;
            };

            function __hexToWords(hex) {
                var words = [];
                for (var i = 0; i < hex.length; i += 8) {
                    var chunk = hex.substring(i, i + 8);
                    while (chunk.length < 8) chunk += '0';
                    words.push(parseInt(chunk, 16) | 0);
                }
                return words;
            }

            function __wordsToHex(words, sigBytes) {
                var hex = '';
                for (var i = 0; i < sigBytes; i++) {
                    var word = words[i >>> 2] || 0;
                    var byte = (word >>> (24 - (i % 4) * 8)) & 0xff;
                    var part = byte.toString(16);
                    if (part.length < 2) part = '0' + part;
                    hex += part;
                }
                return hex;
            }

            function __wordArrayToHex(value) {
                if (!value) return '';
                if (typeof value.__hex === 'string') return value.__hex.toLowerCase();
                if (Array.isArray(value.words) && typeof value.sigBytes === 'number') {
                    return __wordsToHex(value.words, value.sigBytes);
                }
                return __crypto_utf8_to_hex(String(value));
            }

            function __buildWordArray(hex, utf8Override) {
                var normalizedHex = (hex || '').toLowerCase();
                if (normalizedHex.length % 2 !== 0) normalizedHex = '0' + normalizedHex;
                var wordArray = {
                    __hex: normalizedHex,
                    __utf8: utf8Override !== undefined ? utf8Override : __crypto_hex_to_utf8(normalizedHex),
                    sigBytes: normalizedHex.length / 2,
                    words: __hexToWords(normalizedHex),
                    toString: function(encoder) {
                        if (!encoder || encoder === CryptoJS.enc.Hex) return this.__hex;
                        if (encoder === CryptoJS.enc.Utf8) return this.__utf8;
                        if (encoder === CryptoJS.enc.Base64) return __crypto_base64_encode(this.__utf8);
                        return this.__hex;
                    },
                    clamp: function() {
                        return this;
                    },
                    concat: function(other) {
                        var otherHex = __wordArrayToHex(other);
                        this.__hex += otherHex;
                        this.__utf8 = __crypto_hex_to_utf8(this.__hex);
                        this.sigBytes = this.__hex.length / 2;
                        this.words = __hexToWords(this.__hex);
                        return this;
                    }
                };
                return wordArray;
            }

            function __wordArrayFromHex(hex) {
                return __buildWordArray(hex, undefined);
            }

            function __wordArrayFromUtf8(text) {
                var utf8 = text == null ? '' : String(text);
                return __buildWordArray(__crypto_utf8_to_hex(utf8), utf8);
            }

            function __wordArrayFromBase64(base64) {
                return __wordArrayFromUtf8(__crypto_base64_decode(base64 || ''));
            }

            function __normalizeWordArrayInput(value) {
                if (value && typeof value === 'object' && typeof value.__utf8 === 'string') {
                    return value.__utf8;
                }
                if (value && typeof value === 'object' && typeof value.__hex === 'string') {
                    return __crypto_hex_to_utf8(value.__hex);
                }
                if (value && typeof value === 'object' && Array.isArray(value.words) && typeof value.sigBytes === 'number') {
                    return __crypto_hex_to_utf8(__wordsToHex(value.words, value.sigBytes));
                }
                if (value == null) return '';
                return String(value);
            }

            function __cryptoHashWordArray(algorithm, message) {
                var utf8 = __normalizeWordArrayInput(message);
                var hex = __crypto_digest_hex(algorithm, utf8);
                return __wordArrayFromHex(hex);
            }

            function __cryptoHmacWordArray(algorithm, message, key) {
                var utf8Message = __normalizeWordArrayInput(message);
                var utf8Key = __normalizeWordArrayInput(key);
                var hex = __crypto_hmac_hex(algorithm, utf8Key, utf8Message);
                return __wordArrayFromHex(hex);
            }

            var CryptoJS = {
                enc: {
                    Hex: {
                        stringify: function(wordArray) {
                            return __wordArrayToHex(wordArray);
                        },
                        parse: function(hexStr) {
                            return __wordArrayFromHex(hexStr || '');
                        }
                    },
                    Utf8: {
                        stringify: function(wordArray) {
                            if (wordArray && typeof wordArray.__utf8 === 'string') return wordArray.__utf8;
                            if (wordArray && typeof wordArray.__hex === 'string') return __crypto_hex_to_utf8(wordArray.__hex);
                            return __normalizeWordArrayInput(wordArray);
                        },
                        parse: function(text) {
                            return __wordArrayFromUtf8(text);
                        }
                    },
                    Base64: {
                        stringify: function(wordArray) {
                            if (wordArray && typeof wordArray.__utf8 === 'string') {
                                return __crypto_base64_encode(wordArray.__utf8);
                            }
                            return __crypto_base64_encode(__normalizeWordArrayInput(wordArray));
                        },
                        parse: function(base64) {
                            return __wordArrayFromBase64(base64);
                        }
                    }
                },
                MD5: function(message) { return __cryptoHashWordArray('MD5', message); },
                SHA1: function(message) { return __cryptoHashWordArray('SHA1', message); },
                SHA256: function(message) { return __cryptoHashWordArray('SHA256', message); },
                SHA512: function(message) { return __cryptoHashWordArray('SHA512', message); },
                HmacMD5: function(message, key) { return __cryptoHmacWordArray('MD5', message, key); },
                HmacSHA1: function(message, key) { return __cryptoHmacWordArray('SHA1', message, key); },
                HmacSHA256: function(message, key) { return __cryptoHmacWordArray('SHA256', message, key); },
                HmacSHA512: function(message, key) { return __cryptoHmacWordArray('SHA512', message, key); }
            };
            globalThis.CryptoJS = CryptoJS;

            var cheerio = {
                load: function(html) {
                    var docId = __cheerio_load(html);
                    var $ = function(selector, context) {
                        if (selector && selector._elementIds) return selector;
                        if (context && context._elementIds && context._elementIds.length > 0) {
                            var allIds = [];
                            for (var i = 0; i < context._elementIds.length; i++) {
                                var childIdsJson = __cheerio_find(docId, context._elementIds[i], selector);
                                var childIds = JSON.parse(childIdsJson);
                                allIds = allIds.concat(childIds);
                            }
                            return createCheerioWrapperFromIds(docId, allIds);
                        }
                        return createCheerioWrapper(docId, selector);
                    };
                    $.html = function(el) {
                        if (el && el._elementIds && el._elementIds.length > 0) {
                            return __cheerio_html(docId, el._elementIds[0]);
                        }
                        return __cheerio_html(docId, '');
                    };
                    return $;
                }
            };

            function createCheerioWrapper(docId, selector) {
                var elementIds;
                if (typeof selector === 'string') {
                    var idsJson = __cheerio_select(docId, selector);
                    elementIds = JSON.parse(idsJson);
                } else {
                    elementIds = [];
                }
                return createCheerioWrapperFromIds(docId, elementIds);
            }

            function createCheerioWrapperFromIds(docId, ids) {
                var wrapper = {
                    _docId: docId,
                    _elementIds: ids,
                    length: ids.length,
                    each: function(callback) {
                        for (var i = 0; i < ids.length; i++) {
                            var elWrapper = createCheerioWrapperFromIds(docId, [ids[i]]);
                            callback.call(elWrapper, i, elWrapper);
                        }
                        return wrapper;
                    },
                    find: function(sel) {
                        var allIds = [];
                        for (var i = 0; i < ids.length; i++) {
                            var childIdsJson = __cheerio_find(docId, ids[i], sel);
                            var childIds = JSON.parse(childIdsJson);
                            allIds = allIds.concat(childIds);
                        }
                        return createCheerioWrapperFromIds(docId, allIds);
                    },
                    text: function() {
                        if (ids.length === 0) return '';
                        return __cheerio_text(docId, ids.join(','));
                    },
                    html: function() {
                        if (ids.length === 0) return '';
                        return __cheerio_inner_html(docId, ids[0]);
                    },
                    attr: function(name) {
                        if (ids.length === 0) return undefined;
                        var val = __cheerio_attr(docId, ids[0], name);
                        return val === '__UNDEFINED__' ? undefined : val;
                    },
                    first: function() { return createCheerioWrapperFromIds(docId, ids.length > 0 ? [ids[0]] : []); },
                    last: function() { return createCheerioWrapperFromIds(docId, ids.length > 0 ? [ids[ids.length - 1]] : []); },
                    next: function() {
                        var nextIds = [];
                        for (var i = 0; i < ids.length; i++) {
                            var nextId = __cheerio_next(docId, ids[i]);
                            if (nextId && nextId !== '__NONE__') nextIds.push(nextId);
                        }
                        return createCheerioWrapperFromIds(docId, nextIds);
                    },
                    prev: function() {
                        var prevIds = [];
                        for (var i = 0; i < ids.length; i++) {
                            var prevId = __cheerio_prev(docId, ids[i]);
                            if (prevId && prevId !== '__NONE__') prevIds.push(prevId);
                        }
                        return createCheerioWrapperFromIds(docId, prevIds);
                    },
                    eq: function(index) {
                        if (index >= 0 && index < ids.length) return createCheerioWrapperFromIds(docId, [ids[index]]);
                        return createCheerioWrapperFromIds(docId, []);
                    },
                    get: function(index) {
                        if (typeof index === 'number') {
                            if (index >= 0 && index < ids.length) return createCheerioWrapperFromIds(docId, [ids[index]]);
                            return undefined;
                        }
                        return ids.map(function(id) { return createCheerioWrapperFromIds(docId, [id]); });
                    },
                    map: function(callback) {
                        var results = [];
                        for (var i = 0; i < ids.length; i++) {
                            var elWrapper = createCheerioWrapperFromIds(docId, [ids[i]]);
                            var result = callback.call(elWrapper, i, elWrapper);
                            if (result !== undefined && result !== null) results.push(result);
                        }
                        return {
                            length: results.length,
                            get: function(index) { return typeof index === 'number' ? results[index] : results; },
                            toArray: function() { return results; }
                        };
                    },
                    filter: function(selectorOrCallback) {
                        if (typeof selectorOrCallback === 'function') {
                            var filteredIds = [];
                            for (var i = 0; i < ids.length; i++) {
                                var elWrapper = createCheerioWrapperFromIds(docId, [ids[i]]);
                                var result = selectorOrCallback.call(elWrapper, i, elWrapper);
                                if (result) filteredIds.push(ids[i]);
                            }
                            return createCheerioWrapperFromIds(docId, filteredIds);
                        }
                        return wrapper;
                    },
                    children: function(sel) { return this.find(sel || '*'); },
                    parent: function() { return createCheerioWrapperFromIds(docId, []); },
                    toArray: function() { return ids.map(function(id) { return createCheerioWrapperFromIds(docId, [id]); }); }
                };
                return wrapper;
            }

            var require = function(moduleName) {
                if (moduleName === 'cheerio' || moduleName === 'cheerio-without-node-native' || moduleName === 'react-native-cheerio') {
                    return cheerio;
                }
                if (moduleName === 'crypto-js') {
                    return CryptoJS;
                }
                throw new Error("Module '" + moduleName + "' is not available");
            };

            if (!Array.prototype.flat) {
                Array.prototype.flat = function(depth) {
                    depth = depth === undefined ? 1 : Math.floor(depth);
                    if (depth < 1) return Array.prototype.slice.call(this);
                    return (function flatten(arr, d) {
                        return d > 0
                            ? arr.reduce(function(acc, val) { return acc.concat(Array.isArray(val) ? flatten(val, d - 1) : val); }, [])
                            : arr.slice();
                    })(this, depth);
                };
            }

            if (!Array.prototype.flatMap) {
                Array.prototype.flatMap = function(callback, thisArg) { return this.map(callback, thisArg).flat(); };
            }

            if (!Object.entries) {
                Object.entries = function(obj) {
                    var result = [];
                    for (var key in obj) {
                        if (obj.hasOwnProperty(key)) result.push([key, obj[key]]);
                    }
                    return result;
                };
            }

            if (!Object.fromEntries) {
                Object.fromEntries = function(entries) {
                    var result = {};
                    for (var i = 0; i < entries.length; i++) {
                        result[entries[i][0]] = entries[i][1];
                    }
                    return result;
                };
            }

            if (!String.prototype.replaceAll) {
                String.prototype.replaceAll = function(search, replace) {
                    if (search instanceof RegExp) {
                        if (!search.global) throw new TypeError('replaceAll must be called with a global RegExp');
                        return this.replace(search, replace);
                    }
                    return this.split(search).join(replace);
                };
            }
        """.trimIndent()
    }
}

internal fun sanitizePluginRequestHeaders(headers: Map<String, String>): Map<String, String> =
    headers.filter { (name, value) ->
        name.isNotBlank() && value.isNotBlank() && name.lowercase() !in PLUGIN_FORBIDDEN_REQUEST_HEADERS
    }

internal fun normalizePluginJsonPayload(rawJson: String): String {
    val trimmed = rawJson.trim().removePrefix("\uFEFF")
    if (trimmed.isEmpty()) return "[]"

    val sanitized = buildString(trimmed.length) {
        var inString = false
        var escaped = false
        trimmed.forEach { character ->
            when {
                escaped -> {
                    append(character)
                    escaped = false
                }
                inString && character == '\\' -> {
                    append(character)
                    escaped = true
                }
                character == '"' -> {
                    append(character)
                    inString = !inString
                }
                inString && character.code < 0x20 -> when (character) {
                    '\b' -> append("\\b")
                    '\t' -> append("\\t")
                    '\n' -> append("\\n")
                    '\u000C' -> append("\\f")
                    '\r' -> append("\\r")
                    else -> append("\\u${character.code.toString(16).padStart(4, '0')}")
                }
                character != '\u0000' -> append(character)
            }
        }
    }

    val arrayStart = sanitized.indexOf('[')
    val arrayEnd = sanitized.lastIndexOf(']')
    return if (arrayStart >= 0 && arrayEnd > arrayStart) {
        sanitized.substring(arrayStart, arrayEnd + 1)
    } else {
        sanitized
    }
}

internal fun salvagePluginJsonArrayItems(rawJson: String): List<JsonElement> {
    val arrayStart = rawJson.indexOf('[')
    if (arrayStart < 0) return emptyList()

    val recovered = mutableListOf<JsonElement>()
    val recoveryJson = Json { ignoreUnknownKeys = true; isLenient = true }
    var depth = 0
    var itemStart = -1
    var inString = false
    var escaped = false

    fun recoverItem(endExclusive: Int) {
        if (itemStart < 0 || endExclusive <= itemStart) return
        val candidate = rawJson.substring(itemStart, endExclusive).trim()
        if (candidate.isEmpty()) return
        runCatching { recoveryJson.parseToJsonElement(candidate) }
            .getOrNull()
            ?.let(recovered::add)
    }

    for (index in arrayStart until rawJson.length) {
        val character = rawJson[index]
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
            continue
        }

        when (character) {
            '"' -> {
                inString = true
                if (depth == 1 && itemStart < 0) itemStart = index
            }
            '[', '{' -> {
                depth++
                if (depth == 2 && itemStart < 0) itemStart = index
            }
            '}', ']' -> {
                if (character == ']' && depth == 1) {
                    recoverItem(index)
                    break
                }
                depth--
            }
            ',' -> if (depth == 1) {
                recoverItem(index)
                itemStart = -1
            }
            else -> if (depth == 1 && itemStart < 0 && !character.isWhitespace()) {
                itemStart = index
            }
        }
    }

    if (depth > 0 && itemStart >= 0) {
        recoverItem(rawJson.length)
    }
    return recovered
}

internal fun repairPluginTextEncoding(value: String): String {
    var repaired = value
    // Some providers' source bundles have already damaged the text once, then the JS/native
    // bridge applies the same UTF-8-as-Windows-1252 mistake again. Decode conservatively, but
    // allow enough passes to unwind that double encoding.
    repeat(8) {
        val next = repairPluginTextEncodingOnce(repaired)
        if (next == repaired) return repaired
        repaired = next
    }
    return repaired
}

private fun repairPluginTextEncodingOnce(value: String): String {
    return buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val leadByte = pluginMojibakeByteOrNull(value[index])?.toInt()?.and(0xff)
            val sequenceLength = when (leadByte) {
                in 0xC2..0xDF -> 2
                in 0xE0..0xEF -> 3
                in 0xF0..0xF4 -> 4
                else -> 0
            }

            val decoded = if (sequenceLength > 0 && index + sequenceLength <= value.length) {
                val bytes = ByteArray(sequenceLength)
                var valid = true
                repeat(sequenceLength) { offset ->
                    val byte = pluginMojibakeByteOrNull(value[index + offset])
                    if (byte == null || (offset > 0 && byte.toInt().and(0xff) !in 0x80..0xBF)) {
                        valid = false
                    } else {
                        bytes[offset] = byte
                    }
                }
                if (valid) {
                    runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
                } else {
                    null
                }
            } else {
                null
            }

            if (decoded != null) {
                append(decoded)
                index += sequenceLength
                continue
            }

            append(value[index])
            index++
        }
    }
}

private fun pluginMojibakeByteOrNull(character: Char): Byte? {
    if (character.code <= 0xFF) return character.code.toByte()
    val windows1252Byte = when (character) {
        '€' -> 0x80
        '‚' -> 0x82
        'ƒ' -> 0x83
        '„' -> 0x84
        '…' -> 0x85
        '†' -> 0x86
        '‡' -> 0x87
        'ˆ' -> 0x88
        '‰' -> 0x89
        'Š' -> 0x8A
        '‹' -> 0x8B
        'Œ' -> 0x8C
        'Ž' -> 0x8E
        '‘' -> 0x91
        '’' -> 0x92
        '“' -> 0x93
        '”' -> 0x94
        '•' -> 0x95
        '–' -> 0x96
        '—' -> 0x97
        '˜' -> 0x98
        '™' -> 0x99
        'š' -> 0x9A
        '›' -> 0x9B
        'œ' -> 0x9C
        'ž' -> 0x9E
        'Ÿ' -> 0x9F
        else -> return null
    }
    return windows1252Byte.toByte()
}
