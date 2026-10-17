package com.nuvio.app.core.deeplink

import com.nuvio.app.features.party.PARTY_CODE_LENGTH
import com.nuvio.app.features.party.normalizePartyCode
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface AppDeepLink {
    data class Meta(
        val type: String,
        val id: String,
    ) : AppDeepLink

    data object Downloads : AppDeepLink

    /** A watch-party invite: `nuvio://party/CODE`, or the `https://nuvio.tv/party/CODE` share link. */
    data class Party(
        val code: String,
    ) : AppDeepLink
}

object AppDeepLinkRepository {
    private val _pendingDeepLink = MutableStateFlow<AppDeepLink?>(null)
    val pendingDeepLink: StateFlow<AppDeepLink?> = _pendingDeepLink.asStateFlow()

    fun handleUrl(url: String) {
        parseAppDeepLink(url)?.let { deepLink ->
            _pendingDeepLink.value = deepLink
        }
    }

    fun markConsumed(deepLink: AppDeepLink) {
        if (_pendingDeepLink.value == deepLink) {
            _pendingDeepLink.value = null
        }
    }
}

fun handleAppUrl(url: String) {
    val normalizedUrl = url.trim()
    if (normalizedUrl.isBlank()) return

    AppDeepLinkRepository.handleUrl(normalizedUrl)
}

fun buildMetaDeepLinkUrl(
    type: String,
    id: String,
): String = buildString {
    append("nuvio://meta?type=")
    append(type.trim().encodeURLParameter())
    append("&id=")
    append(id.trim().encodeURLParameter())
}

fun buildDownloadsDeepLinkUrl(): String = "nuvio://downloads"

internal fun parseAppDeepLink(url: String): AppDeepLink? {
    val parsedUrl = runCatching { Url(url) }.getOrNull() ?: return null
    val pathSegments = parsedUrl.segments.map(String::trim).filter(String::isNotBlank)
    if (parsedUrl.protocol.name.equals("https", ignoreCase = true)) {
        val isInvite = parsedUrl.host.lowercase() == "nuvio.tv" && pathSegments.firstOrNull() == "party"
        return if (isInvite) partyDeepLink(pathSegments.getOrNull(1)) else null
    }
    if (!parsedUrl.protocol.name.equals("nuvio", ignoreCase = true)) return null

    return when (parsedUrl.host.lowercase()) {
        "meta" -> {
            val type = parsedUrl.parameters["type"]?.trim().orEmpty()
            val id = parsedUrl.parameters["id"]?.trim().orEmpty()
            if (type.isBlank() || id.isBlank()) null else AppDeepLink.Meta(type = type, id = id)
        }

        "downloads" -> AppDeepLink.Downloads

        "party" -> partyDeepLink(pathSegments.firstOrNull() ?: parsedUrl.parameters["code"])

        else -> null
    }
}

private fun partyDeepLink(code: String?): AppDeepLink.Party? =
    code?.let(::normalizePartyCode)?.takeIf { it.length == PARTY_CODE_LENGTH }?.let(AppDeepLink::Party)