package com.nuvio.app.features.music.potoken

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One loaded BotGuard: a page that has run the challenge and holds an integrity token. */
internal interface PoTokenMinter {
    /** Past the integrity token's lifetime, less a margin. */
    val isExpired: Boolean

    /** The browser exited, or the page failed after start-up. */
    val isDead: Boolean

    /** A PO token bound to [identifier]: a visitor data or a video id. */
    suspend fun mint(identifier: String): String

    fun close()
}

/** The two calls to the attestation service. */
internal fun interface BotGuardHttp {
    suspend fun post(url: String, body: String): String
}

internal class PoTokens(
    /** Bound to the session's visitor data: goes in the player request. */
    val sessionToken: String,
    /** Bound to the video id: the streaming (`pot=`) token. */
    val videoToken: String,
)

internal object JdkBotGuardHttp : BotGuardHttp {
    private val client: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }

    override suspend fun post(url: String, body: String): String = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .apply { PoTokenProtocol.requestHeaders.forEach { (name, value) -> header(name, value) } }
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200 || response.body().isNullOrEmpty()) {
            throw PoTokenException("${url.substringAfterLast('/')} answered HTTP ${response.statusCode()}")
        }
        response.body()
    }
}

/** A [PoTokenMinter] backed by [BotGuardBrowser]. */
internal class BotGuardMinter private constructor(
    private val browser: BotGuardBrowser,
    private val expiresAtMs: Long,
    private val now: () -> Long,
) : PoTokenMinter {
    override val isExpired: Boolean get() = now() >= expiresAtMs
    override val isDead: Boolean get() = browser.isDead

    override suspend fun mint(identifier: String): String {
        val token = browser.evaluate(BotGuardPage.mintExpression(identifier), MINT_TIMEOUT_MS)
        if (token.length < MIN_TOKEN_CHARS) throw PoTokenException("The minter returned a token that is too short")
        return token
    }

    override fun close() = browser.close()

    companion object {
        private val log = Logger.withTag("PoToken")
        private const val RUN_TIMEOUT_MS = 25_000L
        private const val MINT_TIMEOUT_MS = 10_000L
        private const val MIN_TOKEN_CHARS = 40

        /** The integrity token's life is hours; leave this much unused so a mint never races its expiry. */
        private const val EXPIRY_MARGIN_MS = 10 * 60_000L

        suspend fun create(
            browserPath: String,
            http: BotGuardHttp = JdkBotGuardHttp,
            now: () -> Long = System::currentTimeMillis,
        ): BotGuardMinter {
            val started = now()
            val browser = BotGuardBrowser.launch(browserPath)
            try {
                val challenge = PoTokenProtocol.parseChallenge(http.post(PoTokenProtocol.CREATE_URL, PoTokenProtocol.createBody()))
                val response = browser.evaluate(BotGuardPage.runExpression(challenge), RUN_TIMEOUT_MS)
                val integrity = PoTokenProtocol.parseIntegrityToken(
                    http.post(PoTokenProtocol.GENERATE_IT_URL, PoTokenProtocol.generateItBody(response)),
                )
                browser.evaluate(
                    BotGuardPage.initExpression(PoTokenProtocol.encodeWebSafe(integrity.bytes)),
                    RUN_TIMEOUT_MS,
                )
                val expiresAt = now() + (integrity.ttlSeconds * 1000L - EXPIRY_MARGIN_MS).coerceAtLeast(60_000L)
                log.i { "BotGuard ready in ${now() - started}ms, valid for ${integrity.ttlSeconds / 60} min" }
                return BotGuardMinter(browser, expiresAt, now)
            } catch (error: Throwable) {
                browser.close()
                throw error
            }
        }
    }
}

/**
 * PO tokens for YouTube's web clients, minted in a browser page that is kept for the session.
 *
 * One page per session (visitor data): the session token is minted once when the page is made and each
 * video's token from the same page. A page that has expired, died, or belongs to another session is
 * replaced; a mint that fails on an old page is retried once on a fresh one. After repeated start-up
 * failures (no browser, attestation refused) it stops trying for a while, so a missing browser does not
 * mean a process launch per track, and a refused attestation is not hammered. An idle page is closed.
 *
 * Shape follows BitChord's `PoTokenGenerator` (GPL-3.0).
 *
 * @param createMinter loads a new page.
 * @param available false when this machine cannot host one (no browser).
 */
internal class PoTokenGenerator(
    private val createMinter: suspend () -> PoTokenMinter,
    private val available: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
    private val idleCloseMs: Long = IDLE_CLOSE_MS,
    private val idleCheckMs: Long = IDLE_CHECK_MS,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var idleJob: Job? = null

    private val log = Logger.withTag("PoToken")
    private val lock = Mutex()
    private var minter: PoTokenMinter? = null
    private var sessionToken: String? = null
    private var sessionId: String? = null
    private var lastUsedMs = 0L
    private var failures = 0

    @Volatile
    private var pausedUntilMs = 0L

    /**
     * True when a token can be asked for now: this machine can host a page and the generator is not
     * paused after failures. A caller that would ask for a token first uses this to decide whether to.
     */
    val ready: Boolean get() = available() && now() >= pausedUntilMs

    /**
     * Tokens for [videoId] in the session [visitorData], or null when none can be made now (no browser,
     * paused after failures, too slow). Cancellation is passed on.
     */
    suspend fun tokens(videoId: String, visitorData: String): PoTokens? {
        if (!available()) return null
        if (now() < pausedUntilMs) return null
        return try {
            withTimeoutOrNull(OVERALL_TIMEOUT_MS) { obtain(videoId, visitorData, forceNew = false) }
                ?: run {
                    log.w { "PO token generation timed out; continuing without one" }
                    discard()
                    null
                }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w { "PO token generation failed: ${error.message}" }
            null
        }
    }

    /** Loads the page ahead of the first track, so playback does not pay the browser's start-up. */
    suspend fun prewarm() {
        if (!available() || now() < pausedUntilMs) return
        lock.withLock {
            if (minter == null || minter!!.isDead || minter!!.isExpired) {
                runCatching { replaceMinter() }.onFailure { if (it is CancellationException) throw it }
            }
        }
    }

    /** Closes the page when nothing has asked for a token for a while. Called by a timer. */
    suspend fun closeIfIdle() {
        lock.withLock {
            if (minter != null && now() - lastUsedMs >= idleCloseMs) {
                log.i { "Closing the idle BotGuard page" }
                dropMinter()
            }
        }
    }

    /** Drops the page. The generator stays usable: the next request loads a new one (also used to reset attestation). */
    suspend fun close() {
        lock.withLock { dropMinter() }
    }

    private suspend fun discard() {
        // A timeout can leave the lock held by the cancelled call for a moment; do not wait for it.
        if (lock.tryLock()) {
            try { dropMinter() } finally { lock.unlock() }
        }
    }

    private suspend fun obtain(videoId: String, visitorData: String, forceNew: Boolean): PoTokens {
        val (page, session, fresh) = lock.withLock {
            lastUsedMs = now()
            var fresh = false
            val current = minter
            // A prewarmed page has no session yet and is simply adopted by the first one that asks.
            val usable = !forceNew && current != null && !current.isExpired && !current.isDead &&
                (sessionId == null || sessionId == visitorData)
            if (!usable) {
                dropMinter()
                replaceMinter()
                fresh = true
            }
            if (sessionId != visitorData) {
                val token = try {
                    minter!!.mint(visitorData)
                } catch (error: Throwable) {
                    dropMinter()
                    recordFailure()
                    throw error
                }
                sessionToken = token
                sessionId = visitorData
                failures = 0
                log.i { "Session PO token ready" }
            }
            Triple(minter!!, sessionToken!!, fresh)
        }
        val video = try {
            page.mint(videoId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (fresh) {
                lock.withLock { dropMinter(); recordFailure() }
                throw error
            }
            log.w { "Minting on the old page failed (${error.message}); starting a fresh one" }
            return obtain(videoId, visitorData, forceNew = true)
        }
        return PoTokens(sessionToken = session, videoToken = video)
    }

    /** Must hold [lock]. */
    private suspend fun replaceMinter(): PoTokenMinter {
        val created = try {
            createMinter()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // fork:potoken-log — a page that will not start was silent until the second failure paused tokens.
            log.w { "BotGuard page could not start: ${error.message}" }
            recordFailure()
            throw error
        }
        minter = created
        sessionToken = null
        sessionId = null
        startIdleTimer()
        return created
    }

    private fun startIdleTimer() {
        if (idleJob?.isActive == true) return
        idleJob = scope.launch {
            while (isActive) {
                delay(idleCheckMs)
                closeIfIdle()
            }
        }
    }

    private fun dropMinter() {
        idleJob?.cancel()
        idleJob = null
        runCatching { minter?.close() }
        minter = null
        sessionToken = null
        sessionId = null
    }

    private fun recordFailure() {
        failures += 1
        if (failures >= FAILURES_BEFORE_PAUSE) {
            val pause = (BASE_PAUSE_MS shl (failures - FAILURES_BEFORE_PAUSE).coerceAtMost(3)).coerceAtMost(MAX_PAUSE_MS)
            pausedUntilMs = now() + pause
            log.w { "PO tokens paused for ${pause / 60_000} min after $failures failures" }
        }
    }

    companion object {
        const val OVERALL_TIMEOUT_MS = 40_000L
        const val IDLE_CLOSE_MS = 10 * 60_000L
        const val IDLE_CHECK_MS = 60_000L
        const val FAILURES_BEFORE_PAUSE = 2
        const val BASE_PAUSE_MS = 10 * 60_000L
        const val MAX_PAUSE_MS = 60 * 60_000L
    }
}
