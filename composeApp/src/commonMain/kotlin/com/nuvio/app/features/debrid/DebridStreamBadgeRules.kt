package com.nuvio.app.features.debrid

import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamBadge
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class DebridStreamBadgeRules(
    val sourceUrl: String = "",
    val filters: List<DebridStreamBadgeFilter> = emptyList(),
    val groups: List<DebridStreamBadgeGroup> = emptyList(),
) {
    val hasImport: Boolean
        get() = filters.isNotEmpty()

    override fun toString(): String =
        "DebridStreamBadgeRules(sourceUrl=$sourceUrl, filters=${filters.size}, groups=${groups.size})"
}

@Serializable
data class DebridStreamBadgeFilter(
    val id: String = "",
    val groupId: String = "",
    val name: String = "",
    val pattern: String = "",
    val imageURL: String = "",
    val isEnabled: Boolean = true,
    val tagColor: String = "",
    val tagStyle: String = "",
    val textColor: String = "",
    val borderColor: String = "",
)

@Serializable
data class DebridStreamBadgeGroup(
    val id: String = "",
    val name: String = "",
    val color: String = "",
    val isExpanded: Boolean = true,
)

sealed interface DebridStreamBadgeImportResult {
    data class Success(val rules: DebridStreamBadgeRules) : DebridStreamBadgeImportResult
    data class Error(val message: String) : DebridStreamBadgeImportResult
}

internal object DebridStreamBadgeRulesParser {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun parse(sourceUrl: String, payload: String): DebridStreamBadgeRules {
        val decoded = try {
            json.decodeFromString<DebridStreamBadgePayload>(payload)
        } catch (error: SerializationException) {
            throw IllegalArgumentException("Invalid badge JSON: ${error.message.orEmpty()}")
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid badge JSON: ${error.message.orEmpty()}")
        }

        val filters = decoded.filters.mapNotNull { filter ->
            val name = filter.name.orEmpty().trim()
            val pattern = filter.pattern.orEmpty().trim()
            if (name.isBlank() || pattern.isBlank()) {
                return@mapNotNull null
            }

            DebridStreamBadgeFilter(
                id = filter.id.orEmpty(),
                groupId = filter.groupId.orEmpty(),
                name = name,
                pattern = pattern,
                imageURL = filter.imageURL.orEmpty(),
                isEnabled = filter.isEnabled ?: true,
                tagColor = filter.tagColor.orEmpty(),
                tagStyle = filter.tagStyle.orEmpty(),
                textColor = filter.textColor.orEmpty(),
                borderColor = filter.borderColor.orEmpty(),
            )
        }

        if (filters.isEmpty()) {
            throw IllegalArgumentException("Badge import did not contain any usable filters.")
        }

        val groups = decoded.groups.map { group ->
            DebridStreamBadgeGroup(
                id = group.id.orEmpty(),
                name = group.name.orEmpty(),
                color = group.color.orEmpty(),
                isExpanded = group.isExpanded ?: true,
            )
        }

        return DebridStreamBadgeRules(
            sourceUrl = sourceUrl.trim(),
            filters = filters,
            groups = groups,
        )
    }
}

internal object DebridStreamBadgeMatcher {
    fun compile(rules: DebridStreamBadgeRules): List<DebridCompiledStreamBadgeFilter> {
        if (!rules.hasImport) return emptyList()
        return rules.filters.mapNotNull { filter ->
            if (!filter.isEnabled || filter.name.isBlank() || filter.pattern.isBlank()) {
                return@mapNotNull null
            }
            val regex = runCatching { Regex(filter.pattern) }.getOrNull() ?: return@mapNotNull null
            DebridCompiledStreamBadgeFilter(
                name = filter.name,
                badge = StreamBadge(
                    name = filter.name,
                    imageURL = filter.imageURL,
                    tagColor = filter.tagColor,
                    tagStyle = filter.tagStyle,
                    textColor = filter.textColor,
                    borderColor = filter.borderColor,
                ),
                regex = regex,
            )
        }
    }

    fun matchedNames(stream: StreamItem, rules: DebridStreamBadgeRules): List<String> =
        matchedNames(stream, compile(rules))

    fun matchedNames(stream: StreamItem, filters: List<DebridCompiledStreamBadgeFilter>): List<String> {
        return matchedBadges(stream, filters).map { it.name }
    }

    fun matchedBadges(stream: StreamItem, filters: List<DebridCompiledStreamBadgeFilter>): List<StreamBadge> {
        if (filters.isEmpty()) return emptyList()
        val candidates = badgeMatchCandidates(stream)
        if (candidates.isEmpty()) return emptyList()

        val matched = linkedMapOf<String, StreamBadge>()
        filters.forEach { filter ->
            if (candidates.any { candidate -> filter.regex.containsMatchIn(candidate) }) {
                val key = filter.badge.dedupeKey()
                if (key !in matched) matched[key] = filter.badge
            }
        }
        return matched.values.toList()
    }

    private fun badgeMatchCandidates(stream: StreamItem): List<String> {
        val resolve = stream.clientResolve
        val raw = resolve?.stream?.raw
        val parsed = raw?.parsed
        val candidates = listOfNotNull(
            raw?.filename,
            resolve?.filename,
            stream.behaviorHints.filename,
            stream.debridCacheStatus?.cachedName,
            raw?.torrentName,
            resolve?.torrentName,
            stream.name,
            stream.title,
            stream.description,
            parsed?.rawTitle,
            parsed?.parsedTitle,
            parsed?.resolution,
            parsed?.quality,
            parsed?.codec,
            parsed?.edition,
            parsed?.audio?.joinToString(" "),
            parsed?.channels?.joinToString(" "),
            parsed?.hdr?.joinToString(" "),
            parsed?.group,
            stream.sourceName,
            stream.addonName,
        )
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        return if (candidates.size <= 1) {
            candidates
        } else {
            candidates + candidates.joinToString(" ")
        }
    }
}

data class DebridCompiledStreamBadgeFilter(
    val name: String,
    val badge: StreamBadge,
    val regex: Regex,
)

private fun StreamBadge.dedupeKey(): String =
    imageURL.takeIf { it.isNotBlank() } ?: name

@Serializable
private data class DebridStreamBadgePayload(
    val filters: List<DebridStreamBadgeFilterPayload> = emptyList(),
    val groups: List<DebridStreamBadgeGroupPayload> = emptyList(),
)

@Serializable
private data class DebridStreamBadgeFilterPayload(
    val id: String? = null,
    val groupId: String? = null,
    val name: String? = null,
    val pattern: String? = null,
    val imageURL: String? = null,
    val isEnabled: Boolean? = null,
    val tagColor: String? = null,
    val tagStyle: String? = null,
    val textColor: String? = null,
    val borderColor: String? = null,
    val type: String? = null,
)

@Serializable
private data class DebridStreamBadgeGroupPayload(
    val id: String? = null,
    val name: String? = null,
    val color: String? = null,
    val isExpanded: Boolean? = null,
)
