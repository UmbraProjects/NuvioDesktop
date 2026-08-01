package com.nuvio.app.features.player

import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.catalog.fetchCatalogPage
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Best-effort artwork lookup for metadata-less direct playback (a pasted stream URL or dropped
 * local file). Normal playback carries a poster from the details screen; ad-hoc playback has only
 * the parsed title, so we query the user's search-capable addon catalogs (Cinemeta et al. — no API
 * key required) for a matching poster. That lets the player HUD and Discord Rich Presence show real
 * art instead of the Nuvio logo, matching how presence looks for library playback.
 *
 * Purely cosmetic: every failure (no addons, no match, network error) degrades silently to null.
 */
internal object PlaybackArtworkResolver {
    // Successful lookups only — a null (miss/failure) is never cached so a later retry (e.g. once
    // addons finish loading) can still succeed.
    private val cache = mutableMapOf<String, String>()

    suspend fun resolvePosterUrl(title: String, year: Int?, isSeries: Boolean): String? {
        val normalizedTitle = title.trim()
        if (normalizedTitle.isBlank()) return null
        val targetType = if (isSeries) "series" else "movie"
        val cacheKey = "$targetType|${normalizedTitle.lowercase()}|${year ?: ""}"
        cache[cacheKey]?.let { return it }

        val result = withContext(Dispatchers.IO) {
            runCatching { queryAddons(normalizedTitle, year, targetType) }.getOrNull()
        }
        if (result != null) cache[cacheKey] = result
        return result
    }

    private suspend fun queryAddons(title: String, year: Int?, targetType: String): String? {
        val candidates = AddonRepository.uiState.value.addons
            .filter { it.isActive }
            .mapNotNull { it.manifest }
            .flatMap { manifest ->
                manifest.catalogs
                    .filter { it.type == targetType && it.supportsSearch() }
                    .map { manifest.transportUrl to it.id }
            }
            .take(MAX_CATALOGS)

        for ((manifestUrl, catalogId) in candidates) {
            val page = runCatching {
                fetchCatalogPage(
                    manifestUrl = manifestUrl,
                    type = targetType,
                    catalogId = catalogId,
                    search = title,
                    maxItems = MAX_ITEMS,
                )
            }.getOrNull() ?: continue
            val art = pickBestMatch(page.items, title, year)?.artworkUrl()
            if (art != null) return art
        }
        return null
    }

    private fun pickBestMatch(items: List<MetaPreview>, title: String, year: Int?): MetaPreview? {
        if (items.isEmpty()) return null
        val normalized = title.lowercase()
        // Prefer an exact title match; within that, prefer a matching release year. Fall back to
        // the addon's own relevance ordering (first result) when nothing matches precisely.
        val pool = items.filter { it.name.trim().lowercase() == normalized }.ifEmpty { items }
        if (year != null) {
            pool.firstOrNull { it.releaseInfo?.contains(year.toString()) == true }?.let { return it }
        }
        return pool.first()
    }

    private fun MetaPreview.artworkUrl(): String? =
        poster?.trim()?.takeIf { it.isHttpUrl() }
            ?: banner?.trim()?.takeIf { it.isHttpUrl() }

    private fun String.isHttpUrl(): Boolean = startsWith("https://") || startsWith("http://")

    private fun AddonCatalog.supportsSearch(): Boolean =
        extra.any { it.name == "search" } &&
            extra.none { it.isRequired && it.name != "search" }

    private const val MAX_CATALOGS = 4
    private const val MAX_ITEMS = 20
}
