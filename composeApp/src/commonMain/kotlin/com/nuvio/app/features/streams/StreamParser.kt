package com.nuvio.app.features.streams

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

object StreamParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(
        payload: String,
        addonName: String,
        addonId: String,
        addonLogo: String? = null,
    ): List<StreamItem> {
        val root = json.parseToJsonElement(payload).jsonObject
        StreamPayloadDiagnostics.logPayload(addonName, root)
        val streamsArray = root["streams"] as? JsonArray ?: return emptyList()
        return streamsArray.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val url = obj.string("url")
            val infoHash = obj.string("infoHash")
            val externalUrl = obj.string("externalUrl")
            val clientResolve = obj.objectValue("clientResolve")?.toClientResolve()

            // Must have at least one playable source
            if (url == null && infoHash == null && externalUrl == null && clientResolve == null) return@mapNotNull null

            val hintsObj = obj["behaviorHints"] as? JsonObject
            val proxyHeaders = hintsObj
                ?.objectValue("proxyHeaders")
                ?.toProxyHeaders()
            StreamItem(
                name = obj.string("name"),
                title = obj.string("title"),
                description = obj.string("description") ?: obj.string("title"),
                url = url,
                infoHash = infoHash,
                fileIdx = obj.int("fileIdx"),
                externalUrl = externalUrl,
                sources = obj.stringList("sources"),
                audioLanguages = (
                    obj.stringList("languages") +
                        listOfNotNull(obj.string("language"))
                    ).distinct(),
                addonName = addonName,
                addonId = addonId,
                addonLogo = addonLogo,
                streamType = normalizeStreamType(obj.string("type")),
                clientResolve = clientResolve,
                streamData = obj.objectValue("streamData")?.toAddonData(),
                behaviorHints = StreamBehaviorHints(
                    bingeGroup = hintsObj?.string("bingeGroup"),
                    notWebReady = (hintsObj?.boolean("notWebReady") ?: false) || proxyHeaders != null,
                    videoHash = hintsObj?.string("videoHash"),
                    videoSize = hintsObj?.long("videoSize"),
                    filename = hintsObj?.string("filename"),
                    proxyHeaders = proxyHeaders,
                ),
            )
        }
    }

    private fun JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(name: String): Int? =
        this[name]?.jsonPrimitive?.let { primitive ->
            primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
        }

    private fun JsonObject.long(name: String): Long? =
        this[name]?.jsonPrimitive?.let { primitive ->
            primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull()
        }

    private fun JsonObject.boolean(name: String): Boolean? =
        this[name]?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.objectValue(name: String): JsonObject? =
        this[name] as? JsonObject

    private fun JsonObject.stringList(name: String): List<String> =
        (this[name] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
            .orEmpty()

    private fun JsonObject.intList(name: String): List<Int> =
        (this[name] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.intOrNull }
            .orEmpty()

    /** First of [names] that is present, for fields whose key casing varies by producer. */
    private fun JsonObject.firstBoolean(vararg names: String): Boolean? =
        names.firstNotNullOfOrNull { boolean(it) }

    private fun JsonObject.firstIntList(vararg names: String): List<Int> =
        names.firstNotNullOfOrNull { intList(it).takeIf(List<Int>::isNotEmpty) }.orEmpty()

    private fun JsonObject.stringMap(): Map<String, String> =
        entries.mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { key to it }
        }.toMap()

    private fun JsonObject.toProxyHeaders(): StreamProxyHeaders? {
        val requestHeaders = objectValue("request")?.stringMap().orEmpty().takeIf { it.isNotEmpty() }
        val responseHeaders = objectValue("response")?.stringMap().orEmpty().takeIf { it.isNotEmpty() }
        if (requestHeaders == null && responseHeaders == null) {
            return null
        }
        return StreamProxyHeaders(
            request = requestHeaders,
            response = responseHeaders,
        )
    }

    /**
     * AIOStreams' `streamData`. Only the service block is modelled: `service.cached` is the
     * structured "cached on debrid" flag, which the addon otherwise only expresses as an emoji in
     * its user-configurable display text.
     */
    private fun JsonObject.toAddonData(): StreamAddonData {
        val service = objectValue("service")
        val torrent = objectValue("torrent")
        return StreamAddonData(
            type = string("type"),
            serviceId = service?.string("id"),
            serviceCached = service?.boolean("cached"),
            nzbUrl = string("nzbUrl"),
            releaseKey = string("releaseKey"),
            torrent = torrent?.let {
                StreamAddonTorrentData(
                    infoHash = it.string("infoHash"),
                    fileIdx = it.int("fileIdx"),
                )
            },
        )
    }

    private fun JsonObject.toClientResolve(): StreamClientResolve =
        StreamClientResolve(
            type = string("type"),
            infoHash = string("infoHash"),
            fileIdx = int("fileIdx"),
            magnetUri = string("magnetUri"),
            sources = stringList("sources"),
            torrentName = string("torrentName"),
            filename = string("filename"),
            mediaType = string("mediaType"),
            mediaId = string("mediaId"),
            mediaOnlyId = string("mediaOnlyId"),
            title = string("title"),
            season = int("season"),
            episode = int("episode"),
            service = string("service"),
            serviceIndex = int("serviceIndex"),
            serviceExtension = string("serviceExtension"),
            isCached = boolean("isCached"),
            stream = objectValue("stream")?.toClientResolveStream(),
        )

    private fun JsonObject.toClientResolveStream(): StreamClientResolveStream =
        StreamClientResolveStream(
            raw = objectValue("raw")?.toClientResolveRaw(),
        )

    private fun JsonObject.toClientResolveRaw(): StreamClientResolveRaw =
        StreamClientResolveRaw(
            torrentName = string("torrentName"),
            filename = string("filename"),
            size = long("size"),
            folderSize = long("folderSize"),
            tracker = string("tracker"),
            indexer = string("indexer"),
            network = string("network"),
            parsed = objectValue("parsed")?.toClientResolveParsed(),
        )

    private fun JsonObject.toClientResolveParsed(): StreamClientResolveParsed =
        StreamClientResolveParsed(
            rawTitle = string("raw_title"),
            parsedTitle = string("parsed_title"),
            year = int("year"),
            resolution = string("resolution"),
            seasons = intList("seasons"),
            episodes = intList("episodes"),
            // Producers disagree on casing for these — this object mixes snake_case ("raw_title")
            // with plain lowercase, while the AIOStreams schema that defines them is camelCase — so
            // both spellings are accepted rather than betting on one.
            seasonPack = firstBoolean("seasonPack", "season_pack"),
            folderSeasons = firstIntList("folderSeasons", "folder_seasons"),
            folderEpisodes = firstIntList("folderEpisodes", "folder_episodes"),
            quality = string("quality"),
            hdr = stringList("hdr"),
            codec = string("codec"),
            audio = stringList("audio"),
            channels = stringList("channels"),
            languages = stringList("languages"),
            group = string("group"),
            network = string("network"),
            edition = string("edition"),
            duration = long("duration"),
            bitDepth = string("bit_depth"),
            extended = boolean("extended"),
            theatrical = boolean("theatrical"),
            remastered = boolean("remastered"),
            unrated = boolean("unrated"),
        )
}
