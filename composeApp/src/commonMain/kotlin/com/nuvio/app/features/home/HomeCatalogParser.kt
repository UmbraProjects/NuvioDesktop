package com.nuvio.app.features.home

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object HomeCatalogParser {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun parseCatalog(
        payload: String,
        maxItems: Int? = null,
    ): List<MetaPreview> {
        return parseCatalogResponse(
            payload = payload,
            maxItems = maxItems,
        ).items
    }

    fun parseCatalogResponse(
        payload: String,
        maxItems: Int? = null,
    ): ParsedCatalogResponse {
        val root = json.parseToJsonElement(payload).jsonObject
        val metas = root.array("metas")
        val parsedItems = buildList {
            val seenKeys = mutableSetOf<String>()
            metas.forEach { element ->
                if (maxItems != null && size >= maxItems) return@forEach

                val meta = element as? JsonObject ?: return@forEach
                val id = meta.string("id")
                val type = meta.string("type")
                val name = meta.string("name")

                if (id.isNullOrBlank() || type.isNullOrBlank() || name.isNullOrBlank()) {
                    return@forEach
                }

                val item = MetaPreview(
                    id = id,
                    type = type,
                    name = name,
                    poster = meta.string("poster"),
                    banner = meta.string("banner") ?: meta.string("background"),
                    landscapePoster = meta.string("landscapePoster"),
                    logo = meta.string("logo"),
                    posterShape = meta.string("posterShape").toPosterShape(),
                    description = meta.string("description"),
                    releaseInfo = meta.string("releaseInfo"),
                    rawReleaseDate = meta.string("released"),
                    imdbRating = meta.string("imdbRating"),
                    ageRating = meta.string("ageRating") ?: meta.string("certification"),
                    runtime = meta.string("runtime"),
                    genres = meta.array("genres").mapNotNull { genre ->
                        (genre as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    },
                    cast = meta.stringListOrCsv("cast").ifEmpty {
                        meta.stringListOrCsv("actors")
                    }.map { name -> HeroCastMember(name = name) },
                    defaultVideoId = (meta["behaviorHints"] as? JsonObject)
                        ?.string("defaultVideoId")
                        ?.takeIf(String::isNotBlank),
                    animeType = (meta.string("animeType") ?: meta.string("anime_type"))
                        ?.trim()
                        ?.takeIf(String::isNotBlank),
                    carriesAnimeCatalogueId = ANIME_CATALOGUE_ID_FIELDS.any { field ->
                        !meta.string(field).isNullOrBlank()
                    },
                )
                if (seenKeys.add(item.stableKey())) {
                    add(item)
                }
            }
        }
        return ParsedCatalogResponse(
            items = parsedItems,
            rawItemCount = metas.size,
        )
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.array(name: String): JsonArray =
        this[name] as? JsonArray ?: JsonArray(emptyList())

    private fun JsonObject.stringListOrCsv(name: String): List<String> {
        val values = array(name).mapNotNull { value ->
            (value as? JsonPrimitive)?.contentOrNull
                ?.trim()
                ?.takeIf(String::isNotBlank)
        }
        if (values.isNotEmpty()) return values
        return string(name)
            ?.split(',')
            .orEmpty()
            .map(String::trim)
            .filter(String::isNotBlank)
    }

    private fun String?.toPosterShape(): PosterShape =
        when (this?.lowercase()) {
            "square" -> PosterShape.Square
            "landscape" -> PosterShape.Landscape
            else -> PosterShape.Poster
        }
}

/**
 * Anime-catalogue ids as side fields. A Kitsu-backed meta carries `kitsu_id` next to `imdb_id`
 * whichever of the two it is addressed by, so these keep saying "anime" after the primary id has
 * been translated into a franchise namespace. Numbers and strings both read fine — the parser takes
 * the primitive's content either way.
 */
private val ANIME_CATALOGUE_ID_FIELDS = listOf(
    "kitsu_id",
    "kitsuId",
    "mal_id",
    "malId",
    "myanimelist_id",
    "anilist_id",
    "anilistId",
    "anidb_id",
    "anidbId",
)

data class ParsedCatalogResponse(
    val items: List<MetaPreview>,
    val rawItemCount: Int,
)
