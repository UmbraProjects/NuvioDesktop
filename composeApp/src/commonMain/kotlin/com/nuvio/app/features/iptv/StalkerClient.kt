package com.nuvio.app.features.iptv

import co.touchlab.kermit.Logger
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Stalker / MAC portal client, live TV only. Requests come from this machine and present it as a
 * MAG250 set-top box with the MAC the provider registered. Channel links can expire, so the ones
 * that need it are asked for each time a channel opens ([streamCandidates]).
 */
internal object StalkerClient {
    private val log = Logger.withTag("IptvStalker")

    /** Limit on `get_ordered_list` pages per genre, in case a portal's totals never add up. */
    private const val MAX_PAGES = 200
    private const val LINK_TIMEOUT_MS = 10_000L

    private class Session(val endpoint: String, val token: String)

    private val sessions = ConcurrentHashMap<IptvPlaylist, Session>()
    private val loginLock = Mutex()

    /** A login is only about these fields, so the form's unsaved draft has a session of its own. */
    private fun IptvPlaylist.sessionKey() = copy(id = "", name = "", epgUrl = null)

    suspend fun load(playlist: IptvPlaylist): IptvPlaylistContent {
        sessions.remove(playlist.sessionKey())
        val genres = api(playlist, "itv", "get_genres") { readJs(it) as? JsonArray }.orEmpty().mapNotNull { it as? JsonObject }
        val categories = genres.mapNotNull { genre ->
            val id = genre.text("id")?.takeIf { it != "*" } ?: return@mapNotNull null
            IptvCategory(id, genre.text("title") ?: id)
        }

        val raw = LinkedHashMap<String, StalkerChannel>()
        val all = api(playlist, "itv", "get_all_channels", listOf("force_ch_link_check" to "", "include_censored" to "1"), ::readChannels)
        all.data.forEach { channel -> channel.id?.takeIf { it.isNotBlank() }?.let { raw[it] = channel } }
        // Adult genres often hand their channels over only page by page.
        genres.filter { it.text("censored") == "1" }.forEach { genre ->
            genre.text("id")?.let { loadGenrePages(playlist, it, raw) }
        }

        val channels = raw.values.mapIndexedNotNull { i, channel -> channel.toChannel(i, playlist) }
        val (grouped, dividers) = if (categories.isEmpty()) StalkerDividers.group(channels) else channels to emptyList()
        return IptvPlaylistContent.build(
            playlist = playlist,
            categories = categories + dividers,
            channels = grouped,
            guideUrl = playlist.epgUrl?.takeIf { it.isNotBlank() },
        )
    }

    private suspend fun loadGenrePages(playlist: IptvPlaylist, genreId: String, into: MutableMap<String, StalkerChannel>) {
        for (page in 1..MAX_PAGES) {
            val params = listOf(
                "genre" to genreId, "p" to page.toString(), "fav" to "0", "sortby" to "number", "hd" to "0", "force_ch_link_check" to "",
            )
            val result = api(playlist, "itv", "get_ordered_list", params, ::readChannels)
            result.data.forEach { channel -> channel.id?.takeIf { it.isNotBlank() }?.let { into.getOrPut(it) { channel } } }
            val total = result.totalItems?.toIntOrNull() ?: 0
            val perPage = result.maxPageItems?.toIntOrNull()?.takeIf { it > 0 } ?: result.data.size
            if (result.data.isEmpty() || page * perPage >= total) return
        }
    }

    /**
     * The URL to open [channel] with right now. Without an answer from the portal it is the one in
     * the channel's command: the player then shows the error like for any other dead stream.
     */
    suspend fun streamCandidates(playlist: IptvPlaylist, channel: IptvChannel): List<IptvStreamCandidate> {
        val command = channel.url ?: return emptyList()
        val link = try {
            withTimeoutOrNull(LINK_TIMEOUT_MS) {
                val params = listOf("cmd" to command, "series" to "", "forced_storage" to "undefined", "disable_ad" to "0", "download" to "0")
                api(playlist, "itv", "create_link", params) { (readJs(it) as? JsonObject)?.text("cmd") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w { "Stalker link failed: ${describeIptvFailure(e)}" }
            null
        }
        return listOf(StalkerUrls.candidate(link ?: command, playlist))
    }

    suspend fun shortEpg(playlist: IptvPlaylist, streamId: String, size: Int = 4): List<IptvEpgEntry> {
        val params = listOf("ch_id" to streamId, "size" to size.toString())
        val entries = api(playlist, "itv", "get_short_epg", params) { readJs(it) as? JsonArray }.orEmpty()
        return entries.mapNotNull { entry ->
            val item = entry as? JsonObject ?: return@mapNotNull null
            val start = item.text("start_timestamp")?.toLongOrNull() ?: return@mapNotNull null
            val stop = item.text("stop_timestamp")?.toLongOrNull() ?: return@mapNotNull null
            IptvEpgEntry(item.text("name").orEmpty(), start * 1000, stop * 1000)
        }.sortedBy { it.startMs }
    }

    /** One request with the session; when the token has expired, logs in again once and retries. */
    private suspend fun <T> api(
        playlist: IptvPlaylist,
        type: String,
        action: String,
        params: List<Pair<String, String>> = emptyList(),
        read: (BufferedInputStream) -> T,
    ): T {
        val identity = StalkerIdentity.of(playlist)
        val session = session(playlist, identity)
        return try {
            request(session.endpoint, identity, session.token, type, action, params, read)
        } catch (e: ExpiredToken) {
            sessions.remove(playlist.sessionKey(), session)
            val again = session(playlist, identity)
            try {
                request(again.endpoint, identity, again.token, type, action, params, read)
            } catch (e: ExpiredToken) {
                throw rejected()
            }
        }
    }

    private suspend fun session(playlist: IptvPlaylist, identity: StalkerIdentity): Session = loginLock.withLock {
        sessions[playlist.sessionKey()]?.let { return@withLock it }
        var failure: IptvSourceException? = null
        var noToken = false
        for (endpoint in StalkerUrls.endpoints(StalkerUrls.base(playlist))) {
            try {
                val session = login(endpoint, identity)
                if (session == null) {
                    noToken = true
                    continue
                }
                sessions[playlist.sessionKey()] = session
                return@withLock session
            } catch (e: ExpiredToken) {
                throw rejected()
            } catch (e: IptvSourceException) {
                // The same host answers every address, so a connection failure is not worth trying again.
                if (e.kind == IptvSourceException.Kind.BadMac || e.kind == IptvSourceException.Kind.Network) throw e
                failure = e
            }
        }
        throw if (noToken) rejected() else failure ?: IptvSourceException(IptvSourceException.Kind.BadResponse, "No Stalker portal answered")
    }

    /**
     * Handshake and profile; null when this address gave no token. A token alone proves nothing: an
     * unregistered MAC can get one and HTTP 200, then a blank profile and no channels.
     */
    private suspend fun login(endpoint: String, identity: StalkerIdentity): Session? {
        val hello = request(endpoint, identity, "", "stb", "handshake", listOf("token" to "")) { readJs(it) as? JsonObject }
        val token = hello?.text("token") ?: return null
        val params = identity.profileParams(random = hello.text("random").orEmpty(), timestamp = System.currentTimeMillis() / 1000)
        val profile = request(endpoint, identity, token, "stb", "get_profile", params) { readJs(it) as? JsonObject }
        if (StalkerIdentity.isRejectedProfile(profile)) throw rejected()
        return Session(endpoint, profile?.text("token") ?: token)
    }

    private suspend fun <T> request(
        endpoint: String,
        identity: StalkerIdentity,
        token: String,
        type: String,
        action: String,
        params: List<Pair<String, String>>,
        read: (BufferedInputStream) -> T,
    ): T {
        val url = StalkerUrls.request(endpoint, type, action, params)
        val headers = buildMap {
            put("User-Agent", STALKER_USER_AGENT)
            put("X-User-Agent", "Model: MAG250; Link: WiFi")
            put("Referer", StalkerUrls.referer(endpoint))
            put("Cookie", StalkerUrls.cookie(identity.mac, token, endpoint, action, TimeZone.getDefault().id))
            if (token.isNotEmpty()) put("Authorization", "Bearer $token")
        }
        return try {
            IptvHttp.get(url, headers) { input ->
                val buffered = BufferedInputStream(input, 64 * 1024)
                if (buffered.peekText().contains("Authorization failed", ignoreCase = true)) throw ExpiredToken()
                read(buffered)
            }
        } catch (e: IptvSourceException) {
            if (e.httpCode == 401) throw ExpiredToken()
            throw e
        }
    }

    /** Not an IOException, so [IptvHttp] lets it through instead of reporting a network failure. */
    private class ExpiredToken : RuntimeException()

    private fun rejected() = IptvSourceException(IptvSourceException.Kind.BadMac, "Stalker portal rejected the MAC")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** The `js` of a short response. */
    private fun readJs(input: InputStream): JsonElement? =
        try {
            json.parseToJsonElement(input.readBytes().decodeToString()).jsonObject["js"]
        } catch (e: SerializationException) {
            throw badResponse(e)
        } catch (e: IllegalArgumentException) {
            throw badResponse(e)
        }

    /**
     * A channel list, read as it arrives: a big portal's runs to tens of MB and must not become one
     * tree. It is `js` itself or `js.data`, and the start of the body tells which.
     */
    @OptIn(ExperimentalSerializationApi::class)
    internal fun readChannels(input: BufferedInputStream): StalkerChannelPage =
        try {
            when (JsStart.find(input.peekText())?.groupValues?.get(1)) {
                null -> throw IptvSourceException(IptvSourceException.Kind.BadResponse, "Unexpected Stalker response")
                "[" -> StalkerChannelPage(data = json.decodeFromStream(StalkerChannelList.serializer(), input).js)
                "{" -> json.decodeFromStream(StalkerChannelResponse.serializer(), input).js
                else -> StalkerChannelPage() // Portals answer an empty list with false, null or "".
            }
        } catch (e: SerializationException) {
            throw badResponse(e)
        } catch (e: IllegalArgumentException) {
            throw badResponse(e)
        }

    private val JsStart = Regex("\"js\"\\s*:\\s*(\\S)")

    private fun badResponse(cause: Throwable) =
        IptvSourceException(IptvSourceException.Kind.BadResponse, "Unexpected Stalker response", cause = cause)

    private fun BufferedInputStream.peekText(): String {
        mark(PEEK_BYTES)
        val head = readNBytes(PEEK_BYTES)
        reset()
        return head.decodeToString()
    }

    private const val PEEK_BYTES = 4096

    private fun StalkerChannel.toChannel(index: Int, playlist: IptvPlaylist): IptvChannel? {
        val id = id?.takeIf { it.isNotBlank() } ?: return null
        val command = cmd?.takeIf { it.isNotBlank() } ?: firstCommandUrl() ?: return null
        return IptvChannel(
            uid = index,
            key = id,
            num = number?.toDoubleOrNull()?.toInt() ?: (index + 1),
            name = name?.trim().orEmpty(),
            logo = logo?.trim()?.takeIf { it.contains("://") || it.startsWith("/") }?.let { StalkerUrls.commandUrl(it, playlist) },
            categoryId = genreId.orEmpty(),
            streamId = id,
            url = command,
            userAgent = STALKER_USER_AGENT,
            epgId = xmltvId?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun StalkerChannel.firstCommandUrl(): String? =
        ((cmds as? JsonArray)?.firstOrNull() as? JsonObject)?.text("url")
}

private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

/** What the portal knows the "box" by: what the user typed or, failing that, worked out from the MAC. */
internal class StalkerIdentity(
    val mac: String,
    val serial: String,
    val deviceId: String,
    val deviceId2: String,
    val signature: String,
) {
    fun profileParams(random: String, timestamp: Long): List<Pair<String, String>> {
        val metrics = buildJsonObject {
            put("mac", mac)
            put("sn", serial)
            put("model", "MAG250")
            put("type", "STB")
            put("uid", "")
            put("random", random)
        }
        return listOf(
            "hd" to "1",
            "ver" to "ImageDescription: 0.2.18-r14-pub-250; ImageDate: Fri Jan 15 15:20:44 EET 2016; PORTAL version: 5.1.0; " +
                "API Version: JS API version: 328; STB API version: 134; Player Engine version: 0x566",
            "num_banks" to "2",
            "sn" to serial,
            "stb_type" to "MAG250",
            "client_type" to "STB",
            "image_version" to "218",
            "video_out" to "hdmi",
            "device_id" to deviceId,
            "device_id2" to deviceId2,
            "signature" to signature,
            "auth_second_step" to "1",
            "hw_version" to "1.7-BD-" + md5(mac).take(2),
            "hw_version_2" to md5((serial + mac).lowercase()).lowercase(),
            "metrics" to metrics.toString(),
            "not_valid_token" to "0",
            "api_signature" to "263",
            "prehash" to "0",
            "timestamp" to timestamp.toString(),
            "random" to random,
        )
    }

    companion object {
        private val MacSeparators = Regex("[:\\-.\\s]")
        private val TwelveHex = Regex("[0-9A-F]{12}")

        /** `AA:BB:CC:DD:EE:FF` from any usual way of writing a MAC; null if it is not one. */
        fun normalizeMac(raw: String?): String? {
            val hex = raw.orEmpty().replace(MacSeparators, "").uppercase()
            return if (TwelveHex.matches(hex)) hex.chunked(2).joinToString(":") else null
        }

        fun of(playlist: IptvPlaylist): StalkerIdentity {
            val mac = normalizeMac(playlist.username)
                ?: throw IptvSourceException(IptvSourceException.Kind.BadMac, "Not a MAC address")
            val serial = playlist.serial.ifNotBlank() ?: md5(mac).take(13)
            val deviceId = playlist.deviceId.ifNotBlank() ?: sha256(mac)
            return StalkerIdentity(
                mac = mac,
                serial = serial,
                deviceId = deviceId,
                deviceId2 = playlist.deviceId2.ifNotBlank() ?: deviceId,
                signature = playlist.signature.ifNotBlank() ?: sha256(serial + mac),
            )
        }

        /** `status` 1 or no `id`: the portal does not know this box, whatever the handshake said. */
        fun isRejectedProfile(profile: JsonObject?): Boolean =
            profile == null || profile.text("status") == "1" || profile.text("id") == null

        private fun String?.ifNotBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

        private fun md5(text: String) = hash("MD5", text)

        private fun sha256(text: String) = hash("SHA-256", text)

        private fun hash(algorithm: String, text: String): String =
            MessageDigest.getInstance(algorithm).digest(text.toByteArray()).joinToString("") { "%02X".format(it) }
    }
}

internal object StalkerUrls {

    /**
     * Hosts of the portals in use and of their stream links, which can hold a login in the path:
     * [redactIptvCredentials] keeps these hosts and their URLs out of the logs.
     */
    val portalHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** What the user typed with a scheme; a bare `host:port` gets `http://`. */
    fun base(playlist: IptvPlaylist): String {
        val raw = playlist.server?.trim().orEmpty()
        if (raw.isEmpty()) throw IptvSourceException(IptvSourceException.Kind.NoUrl, "No Stalker portal")
        val withScheme = if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) raw else "http://$raw"
        val host = runCatching { URI(withScheme).host }.getOrNull()
        if (host.isNullOrEmpty()) throw IptvSourceException(IptvSourceException.Kind.BadUrl, "Bad Stalker portal")
        portalHosts += host
        return withScheme.substringBefore('#')
    }

    /** Where the portal may answer, in the order to try. */
    fun endpoints(base: String): List<String> {
        if (base.substringBefore('?').endsWith(".php", ignoreCase = true)) return listOf(base)
        val portal = base.substringBefore('?').trimEnd('/').removeSuffix("/c").trimEnd('/')
        val at = portal.indexOf("/stalker_portal")
        if (at >= 0) return listOf(portal.substring(0, at) + "/stalker_portal/server/load.php")
        return listOf("$portal/portal.php", "$portal/server/load.php", "$portal/stalker_portal/server/load.php")
    }

    fun request(endpoint: String, type: String, action: String, params: List<Pair<String, String>>): String {
        val all = listOf("type" to type, "action" to action) + params + ("JsHttpRequest" to "1-xml")
        val separator = if ('?' in endpoint) '&' else '?'
        return endpoint + separator + all.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
    }

    fun referer(endpoint: String): String {
        val path = endpoint.substringBefore('?')
        val base = if (path.endsWith("/server/load.php")) path.removeSuffix("/server/load.php") else path.substringBeforeLast('/')
        return "$base/c/"
    }

    /**
     * `/stalker_portal/` portals take the MAC URL-encoded and no token on `get_profile`; the others
     * take the MAC as it is and the token whenever there is one.
     */
    fun cookie(mac: String, token: String, endpoint: String, action: String, timezone: String): String = buildString {
        val stalkerPortal = "/stalker_portal/" in endpoint
        append("mac=").append(if (stalkerPortal) mac.replace(":", "%3A") else mac)
        append("; stb_lang=en; timezone=").append(timezone)
        if (token.isNotEmpty() && !(stalkerPortal && action == "get_profile")) append("; token=").append(token)
    }

    /** Only `/ch/` commands have to be asked of the portal; any other already holds the link. */
    fun needsLink(command: String?): Boolean = command != null && "/ch/" in command

    /**
     * The URL in a command (`ffmpeg http://...`, the last part). One with no host, or naming the box
     * itself as `localhost`, points at the portal.
     */
    fun commandUrl(command: String, playlist: IptvPlaylist): String {
        val url = command.trim().substringAfterLast(' ')
        val parts = UrlParts.matchEntire(url)
            ?: return origin(playlist) + "/" + url.trimStart('/')
        val (authority, rest) = parts.destructured
        val host = authority.substringAfterLast('@').substringBefore(':').lowercase()
        return if (host.isEmpty() || host == "localhost" || host == "127.0.0.1") origin(playlist) + rest else url
    }

    /** One candidate: a Stalker link is `.m3u8` for HLS and MPEG-TS for anything else. */
    fun candidate(command: String, playlist: IptvPlaylist): IptvStreamCandidate {
        val url = commandUrl(command, playlist)
        runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { portalHosts += it }
        val hls = url.substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true)
        return IptvStreamCandidate(url, if (hls) IptvStreamFormat.HLS else IptvStreamFormat.TS)
    }

    private fun origin(playlist: IptvPlaylist): String {
        val uri = URI(base(playlist))
        return "${uri.scheme}://${uri.rawAuthority}"
    }

    private val UrlParts = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#]*)(.*)$")
}

/**
 * Portals with no real genres, whose list has divider entries instead (`✦●✦ SPORT ✦●✦`): the
 * dividers become groups and stop counting as channels. It is one provider's habit, so any other
 * list stays as it is.
 */
internal object StalkerDividers {
    private const val MIN_DIVIDERS = 2
    private const val MIN_EDGE = 2
    private const val BRACKETS = "()[]{}<>"

    /** The name inside a divider, or null for an ordinary channel name. */
    fun title(name: String): String? {
        val trimmed = name.trim()
        val first = trimmed.indexOfFirst { it.isLetterOrDigit() }
        if (first < MIN_EDGE) return null
        val last = trimmed.indexOfLast { it.isLetterOrDigit() }
        val left = trimmed.substring(0, first)
        val right = trimmed.substring(last + 1)
        if (right.length < MIN_EDGE || (left + right).any { it in BRACKETS }) return null
        return trimmed.substring(first, last + 1)
    }

    fun group(channels: List<IptvChannel>): Pair<List<IptvChannel>, List<IptvCategory>> {
        if (channels.count { title(it.name) != null } < MIN_DIVIDERS) return channels to emptyList()
        val categories = LinkedHashMap<String, IptvCategory>()
        var current = ""
        val grouped = channels.mapNotNull { channel ->
            val title = title(channel.name)
            if (title != null) {
                current = title
                categories.getOrPut(title) { IptvCategory(title, title) }
                null
            } else {
                channel.copy(categoryId = current)
            }
        }
        return grouped.mapIndexed { i, channel -> channel.copy(uid = i) } to categories.values.toList()
    }
}

/** The user agent of a MAG250, sent to the portal and to the player: some portals block any other. */
internal const val STALKER_USER_AGENT =
    "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

@Serializable
internal class StalkerChannelList(val js: List<StalkerChannel> = emptyList())

@Serializable
internal class StalkerChannelResponse(val js: StalkerChannelPage = StalkerChannelPage())

@Serializable
internal class StalkerChannelPage(
    @SerialName("total_items") @Serializable(with = LooseString::class) val totalItems: String? = null,
    @SerialName("max_page_items") @Serializable(with = LooseString::class) val maxPageItems: String? = null,
    val data: List<StalkerChannel> = emptyList(),
)

@Serializable
internal class StalkerChannel(
    @Serializable(with = LooseString::class) val id: String? = null,
    @Serializable(with = LooseString::class) val name: String? = null,
    @Serializable(with = LooseString::class) val number: String? = null,
    @Serializable(with = LooseString::class) val logo: String? = null,
    @Serializable(with = LooseString::class) val cmd: String? = null,
    val cmds: JsonElement? = null,
    @SerialName("tv_genre_id") @Serializable(with = LooseString::class) val genreId: String? = null,
    @SerialName("xmltv_id") @Serializable(with = LooseString::class) val xmltvId: String? = null,
)
