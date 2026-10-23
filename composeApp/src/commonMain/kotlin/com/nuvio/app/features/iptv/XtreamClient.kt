package com.nuvio.app.features.iptv

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.decodeToSequence

/**
 * Xtream Codes client, live TV only.
 *
 * Ported from GoatTV's `XtreamSource`. Its Gson models read every scalar as a String because servers
 * mix numbers and strings (`"stream_id": "123"` vs `123`); here [LooseString] does the same.
 */
internal object XtreamClient {

    suspend fun load(playlist: IptvPlaylist): IptvPlaylistContent = coroutineScope {
        val login = async { runCatching { login(playlist) }.getOrNull() }
        val categories = async { getList(XtreamUrls.api(playlist, "get_live_categories"), XtreamCategory.serializer()) }
        val streams = async { getList(XtreamUrls.api(playlist, "get_live_streams"), XtreamStream.serializer()) }

        val loginResult = login.await()
        val info = loginResult?.userInfo
        if (info?.auth == "0") throw IptvSourceException(IptvSourceException.Kind.BadLogin, "Xtream login rejected")
        val status = info?.status?.takeIf { it.isNotBlank() }
        if (status != null && !status.equals("Active", ignoreCase = true)) {
            throw IptvSourceException(IptvSourceException.Kind.Inactive, "Xtream account $status", status = status)
        }

        val cats = categories.await().mapNotNull { c ->
            val id = c.categoryId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            IptvCategory(id, c.categoryName?.trim().orEmpty().ifEmpty { id })
        }
        val channels = streams.await().mapIndexedNotNull { i, s ->
            val id = s.streamId?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            IptvChannel(
                uid = i,
                key = id,
                num = s.num?.toDoubleOrNull()?.toInt() ?: (i + 1),
                name = s.name?.trim().orEmpty(),
                logo = s.streamIcon?.takeIf { it.isNotBlank() },
                categoryId = s.categoryId.orEmpty(),
                streamId = id,
                epgId = s.epgChannelId?.trim()?.takeIf { it.isNotEmpty() },
                archiveDays = if (s.tvArchive?.trim() == "1") {
                    s.tvArchiveDuration?.trim()?.toDoubleOrNull()?.toInt()?.coerceAtLeast(1) ?: 1
                } else {
                    0
                },
            )
        }
        IptvPlaylistContent.build(
            playlist = playlist,
            categories = cats,
            channels = channels,
            account = info?.toAccount(loginResult.serverInfo?.timezone),
            // Without a guide of its own, the provider's full XMLTV (the one every other player uses).
            guideUrl = playlist.epgUrl?.takeIf { it.isNotBlank() } ?: XtreamUrls.xmltv(playlist),
        )
    }

    /** Account details for "Test connection"; throws on a rejected login. */
    suspend fun account(playlist: IptvPlaylist): XtreamAccount {
        val result = login(playlist)
        val info = result.userInfo ?: throw IptvSourceException(IptvSourceException.Kind.BadResponse, "No user_info")
        if (info.auth == "0") throw IptvSourceException(IptvSourceException.Kind.BadLogin, "Xtream login rejected")
        return info.toAccount(result.serverInfo?.timezone)
    }

    /** Active connections and the account maximum, or null if the server does not say. */
    suspend fun connections(playlist: IptvPlaylist): Pair<Int, Int>? {
        val info = runCatching { login(playlist) }.getOrNull()?.userInfo
        val active = info?.activeCons?.toIntOrNull() ?: return null
        val max = info.maxConnections?.toIntOrNull() ?: return null
        return active to max
    }

    suspend fun shortEpg(playlist: IptvPlaylist, streamId: String, limit: Int = 4): List<IptvEpgEntry> {
        val url = XtreamUrls.api(playlist, "get_short_epg", "stream_id" to streamId, "limit" to limit.toString())
        val listings = IptvHttp.get(url, IPTV_DEFAULT_HEADERS) { decodeObject(it, XtreamShortEpg.serializer()) }.epgListings.orEmpty()
        return listings.mapNotNull { l ->
            val start = l.startTimestamp?.toLongOrNull() ?: return@mapNotNull null
            val stop = l.stopTimestamp?.toLongOrNull() ?: return@mapNotNull null
            IptvEpgEntry(decodeBase64(l.title.orEmpty()), start * 1000, stop * 1000)
        }.sortedBy { it.startMs }
    }

    private suspend fun login(playlist: IptvPlaylist): XtreamLogin =
        IptvHttp.get(XtreamUrls.api(playlist), IPTV_DEFAULT_HEADERS) { decodeObject(it, XtreamLogin.serializer()) }

    private suspend fun <T> getList(url: String, serializer: KSerializer<T>): List<T> =
        IptvHttp.get(url, IPTV_DEFAULT_HEADERS) { decodeList(it, serializer) }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun <T> decodeObject(input: InputStream, deserializer: DeserializationStrategy<T>): T =
        try {
            @OptIn(ExperimentalSerializationApi::class)
            json.decodeFromStream(deserializer, input)
        } catch (e: SerializationException) {
            throw IptvSourceException(IptvSourceException.Kind.BadResponse, "Unexpected Xtream response", cause = e)
        } catch (e: IllegalArgumentException) {
            throw IptvSourceException(IptvSourceException.Kind.BadResponse, "Unexpected Xtream response", cause = e)
        }

    /**
     * Element by element, so a 30 MB `get_live_streams` never becomes one tree. An empty category or
     * stream list comes back from some servers as `null` or `{}` instead of `[]`.
     */
    internal fun <T> decodeList(input: InputStream, serializer: KSerializer<T>): List<T> {
        val buffered = BufferedInputStream(input, 64 * 1024)
        return when (buffered.peekFirstSignificantByte()) {
            '['.code -> try {
                @OptIn(ExperimentalSerializationApi::class)
                json.decodeToSequence(buffered, serializer, DecodeSequenceMode.ARRAY_WRAPPED).toList()
            } catch (e: SerializationException) {
                throw IptvSourceException(IptvSourceException.Kind.BadResponse, "Unexpected Xtream response", cause = e)
            } catch (e: IllegalArgumentException) {
                throw IptvSourceException(IptvSourceException.Kind.BadResponse, "Unexpected Xtream response", cause = e)
            }
            '{'.code, 'n'.code, -1 -> emptyList()
            else -> throw IptvSourceException(IptvSourceException.Kind.BadResponse, "Unexpected Xtream response")
        }
    }

    private fun decodeBase64(s: String): String =
        try {
            String(java.util.Base64.getMimeDecoder().decode(s), Charsets.UTF_8).trim()
        } catch (e: IllegalArgumentException) {
            s
        }
}

internal object XtreamUrls {

    /** `http://host:port` with no trailing slash; a bare `host:port` gets `http://`. */
    fun base(playlist: IptvPlaylist): String {
        val raw = playlist.server?.trim().orEmpty()
        if (raw.isEmpty()) throw IptvSourceException(IptvSourceException.Kind.NoUrl, "No Xtream server")
        val withScheme = if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) raw else "http://$raw"
        val uri = runCatching { URI(withScheme) }.getOrNull()
        if (uri?.host.isNullOrEmpty()) throw IptvSourceException(IptvSourceException.Kind.BadUrl, "Bad Xtream server")
        return withScheme.substringBefore('?').substringBefore('#').trimEnd('/')
    }

    fun api(playlist: IptvPlaylist, action: String? = null, vararg extra: Pair<String, String>): String {
        val params = buildList {
            add("username" to playlist.username.orEmpty())
            add("password" to playlist.password.orEmpty())
            if (action != null) add("action" to action)
            addAll(extra)
        }
        return base(playlist) + "/player_api.php?" + params.joinToString("&") { (k, v) -> "$k=${query(v)}" }
    }

    fun streamUrl(playlist: IptvPlaylist, streamId: String, ext: String): String =
        base(playlist) + "/live/" + segment(playlist.username.orEmpty()) + "/" + segment(playlist.password.orEmpty()) +
            "/" + segment("$streamId.$ext")

    /** The account's full XMLTV guide (`xmltv.php`). */
    fun xmltv(playlist: IptvPlaylist): String =
        base(playlist) + "/xmltv.php?username=" + query(playlist.username.orEmpty()) +
            "&password=" + query(playlist.password.orEmpty())

    private fun query(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    private fun segment(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}

private fun XtreamUserInfo.toAccount(timezone: String?) = XtreamAccount(
    maxConnections = maxConnections?.toIntOrNull(),
    status = status?.takeIf { it.isNotBlank() },
    expiresAtSeconds = expDate?.toLongOrNull()?.takeIf { it > 0 },
    allowedFormats = allowedFormats(),
    timezone = timezone?.takeIf { it.isNotBlank() },
)

/** Any JSON scalar as its text; arrays and objects as "". Servers send ids as numbers or strings. */
internal object LooseString : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("IptvLooseString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeString()
        return (element as? JsonPrimitive)?.contentOrNull.orEmpty()
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

@Serializable
internal class XtreamLogin(
    @SerialName("user_info") val userInfo: XtreamUserInfo? = null,
    @SerialName("server_info") val serverInfo: XtreamServerInfo? = null,
)

@Serializable
internal class XtreamServerInfo(@Serializable(with = LooseString::class) val timezone: String? = null)

@Serializable
internal class XtreamUserInfo(
    @Serializable(with = LooseString::class) val auth: String? = null,
    @Serializable(with = LooseString::class) val status: String? = null,
    @SerialName("exp_date") @Serializable(with = LooseString::class) val expDate: String? = null,
    @SerialName("active_cons") @Serializable(with = LooseString::class) val activeCons: String? = null,
    @SerialName("max_connections") @Serializable(with = LooseString::class) val maxConnections: String? = null,
    // Some servers send a list, others a comma-separated string.
    @SerialName("allowed_output_formats") val allowedOutputFormats: JsonElement? = null,
) {
    fun allowedFormats(): Set<String> {
        val values = when (val e = allowedOutputFormats) {
            is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> e.contentOrNull?.split(',').orEmpty()
            else -> emptyList()
        }
        return values.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }
}

@Serializable
internal class XtreamCategory(
    @SerialName("category_id") @Serializable(with = LooseString::class) val categoryId: String? = null,
    @SerialName("category_name") @Serializable(with = LooseString::class) val categoryName: String? = null,
)

@Serializable
internal class XtreamStream(
    @Serializable(with = LooseString::class) val num: String? = null,
    @Serializable(with = LooseString::class) val name: String? = null,
    @SerialName("stream_id") @Serializable(with = LooseString::class) val streamId: String? = null,
    @SerialName("stream_icon") @Serializable(with = LooseString::class) val streamIcon: String? = null,
    @SerialName("category_id") @Serializable(with = LooseString::class) val categoryId: String? = null,
    @SerialName("epg_channel_id") @Serializable(with = LooseString::class) val epgChannelId: String? = null,
    @SerialName("tv_archive") @Serializable(with = LooseString::class) val tvArchive: String? = null,
    @SerialName("tv_archive_duration") @Serializable(with = LooseString::class) val tvArchiveDuration: String? = null,
)

@Serializable
internal class XtreamShortEpg(@SerialName("epg_listings") val epgListings: List<XtreamEpgListing>? = null)

@Serializable
internal class XtreamEpgListing(
    @Serializable(with = LooseString::class) val title: String? = null,
    @SerialName("start_timestamp") @Serializable(with = LooseString::class) val startTimestamp: String? = null,
    @SerialName("stop_timestamp") @Serializable(with = LooseString::class) val stopTimestamp: String? = null,
)
