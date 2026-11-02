package com.nuvio.app.features.music

import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.nuvio.app.features.music.potoken.BotGuardMinter
import com.nuvio.app.features.music.potoken.BrowserLocator
import com.nuvio.app.features.music.potoken.PoTokenGenerator

/**
 * fork:potoken — InnerTubeX's [TokenProvider], backed by a headless Edge / Chrome that runs BotGuard.
 *
 * Without PO tokens the library leaves every web client out of its catalog and falls back to clients
 * YouTube bot-checks hardest. With them WEB_REMIX and the other BotGuard clients are tried as well, and
 * the library attaches the tokens to the player request and to the stream URL.
 *
 * `-Dnuvio.potoken=off` switches it off. Nothing starts until the first lookup that needs a token; the
 * browser is closed again after ten idle minutes. When no browser is installed [capabilities] is empty,
 * exactly as before this existed.
 */
internal object BrowserPoTokens {
    private val enabled: Boolean = System.getProperty("nuvio.potoken") != "off"

    private val browser: String? by lazy { if (enabled) BrowserLocator.find() else null }

    private val generator by lazy {
        PoTokenGenerator(
            createMinter = { BotGuardMinter.create(requireNotNull(browser) { "No Edge or Chrome was found" }) },
            available = { browser != null },
        )
    }

    val provider: TokenProvider = object : TokenProvider {
        override val capabilities: TokenProviderCapabilities
            get() = if (browser != null) {
                TokenProviderCapabilities(providers = setOf(PoTokenProviderKind.WEB_BOTGUARD), usesWebView = true)
            } else {
                TokenProviderCapabilities(providers = emptySet(), usesWebView = false)
            }

        /** The cookie is not needed: the attestation is anonymous and the tokens bind to [visitorData] and the video. */
        override suspend fun getPoToken(videoId: String, visitorData: String, cookie: String?): PoTokenResult? =
            generator.tokens(videoId, visitorData)?.let { tokens ->
                PoTokenResult(
                    playerRequestToken = tokens.sessionToken,
                    streamingDataToken = tokens.videoToken,
                    visitorData = visitorData,
                )
            }

        override suspend fun prewarm(cookie: String?) = generator.prewarm()

        override suspend fun invalidateAttestation() = generator.close()

        override suspend fun close() = generator.close()
    }

    /** Loads the BotGuard page now, so the first lookup that needs a token does not wait for the browser. */
    suspend fun prewarm() = generator.prewarm()
}
