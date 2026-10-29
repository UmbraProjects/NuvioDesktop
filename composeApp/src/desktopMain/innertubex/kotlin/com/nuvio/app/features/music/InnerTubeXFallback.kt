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
        if (event.level == InnerTubeLogLevel.DEBUG) return@InnerTubeLogger
        val details = if (event.details.isEmpty()) "" else
            event.details.entries.joinToString(prefix = " [", postfix = "]") { "${it.key}=${it.value}" }
        val line = "${event.tag}: ${event.message}$details"
        if (event.level == InnerTubeLogLevel.INFO) log.i { line } else log.w { line }
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

    override suspend fun resolve(videoId: String): TrailerPlaybackSource? {
        innerTube.cookie = YouTubeMusicAuth.authHeaders("https://www.youtube.com")["cookie"]
        innerTube.visitorData = TrailerPlaybackResolver.visitorData().orEmpty()
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
