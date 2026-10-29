package com.nuvio.app.features.music

import co.touchlab.kermit.Logger
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.models.YouTubeLocale
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.trailer.TrailerPlaybackResolver
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import com.nuvio.app.features.youtubemusic.YouTubeMusicAuth
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.io.File

/**
 * fork:innertubex — stream lookup through InnerTubeX, the library BitChord resolves with: a catalog
 * of YouTube clients picked by what each can currently play, plus cipher / `n`-parameter decoding
 * that the in-app extractor does not have. Used only after the extractor has failed a track, and
 * with the signed-in Google account's cookies, so an age-gated track opens the way it does in
 * BitChord. Written against the shape of BitChord's `InnerTubeXResolver` (GPL-3.0).
 *
 * No BotGuard PoToken minter is installed (BitChord runs a browser page for that), so clients that
 * need a PoToken are left out of the catalog; the log says which client opened the track.
 *
 * Compiled in only with `-Pnuvio.innertubex=true`; found by name by [MusicStreamFallbacks].
 */
internal object InnerTubeXFallback : MusicStreamFallback {
    override val name = "InnerTubeX"

    private val log = Logger.withTag("InnerTubeX")

    private val dir: File by lazy { DesktopStorage.rootDir.resolve("innertubex").toFile().apply { mkdirs() } }

    /** The remote player config's cache: small files next to each other, written whole. */
    private fun read(key: String): String? = File(dir, key).takeIf { it.isFile }?.readText()

    private fun write(key: String, value: String) {
        val tmp = File(dir, "$key.tmp")
        tmp.writeText(value)
        tmp.renameTo(File(dir, key))
    }

    private val http = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 20_000
        }
        expectSuccess = false
    }

    private val logger = InnerTubeLogger { event ->
        val details = if (event.details.isEmpty()) "" else
            event.details.entries.joinToString(prefix = " [", postfix = "]") { "${it.key}=${it.value}" }
        val line = "${event.tag}: ${event.message}$details"
        if (event.level == InnerTubeLogLevel.INFO || event.level == InnerTubeLogLevel.DEBUG) log.i { line } else log.w { line }
    }

    private const val PLAYER_CONFIG_URL =
        "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"

    private val repository = object : PlayerConfigRepository {
        override val enabled: Boolean get() = true
        override val sourceUrl: String = PLAYER_CONFIG_URL
        override val defaultSourceUrl: String = PLAYER_CONFIG_URL
        override var cachedJson: String
            get() = read("config.json").orEmpty()
            set(value) { write("config.json", value) }
        override var cachedAtMs: Long
            get() = read("cached_at_ms")?.trim()?.toLongOrNull() ?: 0L
            set(value) { write("cached_at_ms", value.toString()) }
        override var cachedSourceUrl: String
            get() = read("source_url").orEmpty()
            set(value) { write("source_url", value) }
        override var cachedEtag: String
            get() = read("etag").orEmpty()
            set(value) { write("etag", value) }
    }

    /** Nothing here can mint a PoToken; see the class comment. */
    private val noTokens = object : TokenProvider {
        override val capabilities: TokenProviderCapabilities
            get() = TokenProviderCapabilities(providers = emptySet(), usesWebView = false)

        override suspend fun getPoToken(videoId: String, visitorData: String, cookie: String?): PoTokenResult? = null

        override suspend fun close() {}
    }

    private val innerTube = InnerTube(http, logger = logger)
    private val remoteStore = RemotePlayerConfigStore(http, repository, logger)
    private val cipherService = YouTubeCipherService(http, remoteStore, logger)
    private val extractor = InnerTubeExtractor(
        configParser = YtConfigParserImpl(http, innerTube, remoteStore, logger),
        cipherService = cipherService,
        innerTube = innerTube,
        tokenProvider = noTokens,
        logger = logger,
    )

    private class AccountSession(val cookie: String, val visitorData: String?, val loggedIn: Boolean, val atMs: Long)

    @Volatile
    private var account: AccountSession? = null

    /**
     * The visitor data YouTube issues to the signed-in account. The in-app extractor's visitor data
     * belongs to an anonymous session, and pairing it with the account's cookies is a mismatch
     * YouTube can answer with a refusal, so for a signed-in request it is read from the account's own
     * page. The same fetch tells the log whether the saved cookies are still a live session.
     */
    private suspend fun accountSession(cookie: String): AccountSession {
        account?.takeIf { it.cookie == cookie && System.currentTimeMillis() - it.atMs < SESSION_TTL_MS }?.let { return it }
        val names = cookie.split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
        val session = try {
            val html = http.get("https://www.youtube.com/") {
                header("cookie", cookie)
                header("user-agent", BROWSER_UA)
                header("accept-language", "en-US,en;q=0.9")
            }.bodyAsText()
            val loggedIn = Regex(""""LOGGED_IN":\s*(true|false)""").find(html)?.groupValues?.get(1) == "true"
            val visitor = Regex(""""VISITOR_DATA":\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            log.i { "account check: loggedIn=$loggedIn visitorData=${visitor != null} cookies=${names.sorted()}" }
            AccountSession(cookie, visitor, loggedIn, System.currentTimeMillis())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.i { "account check failed: ${error.message}" }
            AccountSession(cookie, null, false, System.currentTimeMillis())
        }
        account = session
        return session
    }

    private const val SESSION_TTL_MS = 30 * 60 * 1000L
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    override suspend fun resolve(videoId: String): TrailerPlaybackSource? {
        val cookie = YouTubeMusicAuth.authHeaders("https://www.youtube.com")["cookie"]
        innerTube.cookie = cookie
        val signedIn = cookie?.let { accountSession(it) }
        innerTube.visitorData = signedIn?.visitorData?.takeIf { signedIn.loggedIn }
            ?: TrailerPlaybackResolver.visitorData().orEmpty()
        innerTube.locale = YouTubeLocale(gl = "US", hl = "en")
        val stream = try {
            extractor.extract(
                videoId = videoId,
                hints = ContentHints(isUploaded = null)
                    .withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = true),
                excludedClients = emptySet(),
                audioQuality = AudioQuality.AUTO,
                clientPlaybackNonce = generateClientPlaybackNonce(),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.i { "no stream for $videoId: ${error.message}" }
            return null
        } ?: return null
        if (stream.sabrBootstrap != null) {
            log.i { "$videoId only offers SABR, which the player cannot open" }
            return null
        }
        log.i { "$videoId: ${stream.clientName} (${stream.profileId}), ${(stream.bitrate ?: 0) / 1000} kbps" }
        return TrailerPlaybackSource(videoUrl = stream.audioUrl, headers = stream.headers)
    }
}
