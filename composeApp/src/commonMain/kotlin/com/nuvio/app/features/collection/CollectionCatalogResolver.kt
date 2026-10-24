package com.nuvio.app.features.collection

import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.catalog.CatalogPage
import com.nuvio.app.features.catalog.fetchCatalogPage
import kotlinx.coroutines.CancellationException

internal data class ResolvedCollectionCatalog(
    val addon: ManagedAddon,
    val catalog: AddonCatalog,
)

internal fun List<ManagedAddon>.findCollectionCatalog(
    source: CollectionCatalogSource,
): ResolvedCollectionCatalog? {
    val activeAddons = enabledAddons()
    val declaredAddon = activeAddons.firstOrNull { it.manifest?.id == source.addonId }
    val declaredCatalog = declaredAddon?.manifest?.catalogs?.findSourceCatalog(source)
    if (declaredAddon != null && declaredCatalog != null) {
        return ResolvedCollectionCatalog(addon = declaredAddon, catalog = declaredCatalog)
    }

    return activeAddons.firstNotNullOfOrNull { addon ->
        val catalog = addon.manifest?.catalogs?.find {
            it.id == source.catalogId && it.type == source.type
        } ?: return@firstNotNullOfOrNull null
        ResolvedCollectionCatalog(addon = addon, catalog = catalog)
    }
}

internal fun List<AvailableCatalog>.findAvailableCatalog(
    source: CollectionCatalogSource,
): AvailableCatalog? {
    val declaredCatalogs = filter { it.addonId == source.addonId }
    return declaredCatalogs.findSourceCatalog(source)
        ?: firstOrNull { it.catalogId == source.catalogId && it.type == source.type }
}

private fun List<AddonCatalog>.findSourceCatalog(source: CollectionCatalogSource): AddonCatalog? =
    find { it.id == source.catalogId && it.type == source.type }
        ?: find { it.id == source.catalogId.substringBefore(",") && it.type == source.type }

private fun List<AvailableCatalog>.findSourceCatalog(source: CollectionCatalogSource): AvailableCatalog? =
    find { it.catalogId == source.catalogId && it.type == source.type }
        ?: find { it.catalogId == source.catalogId.substringBefore(",") && it.type == source.type }

/** A collection source's catalog page, and the catalog id that produced it. */
internal data class CollectionCatalogFetch(
    val page: CatalogPage,
    val catalogId: String,
)

/**
 * The catalog id to fall back to when [source]'s own id fails: the matched catalog's id, when
 * [findSourceCatalog] only found it by the part of the source's id before a comma.
 */
internal fun ResolvedCollectionCatalog.fallbackCatalogId(source: CollectionCatalogSource): String? =
    catalog.id.takeIf { it != source.catalogId }

/**
 * Fetches a collection source's page by the id the collection stored, and only if that fails or
 * comes back empty, by [fallbackCatalogId].
 *
 * A source can be matched to an addon catalog by the part of its id before a comma (see
 * [findSourceCatalog]), and the request then went out with the full id, which that addon may not
 * know. What the part after the comma means is not recorded anywhere, so the stored id is still
 * tried first: a source that works today keeps working exactly as it does.
 */
internal suspend fun fetchCollectionCatalogPage(
    manifestUrl: String,
    type: String,
    catalogId: String,
    fallbackCatalogId: String?,
    genre: String?,
    skip: Int? = null,
    maxItems: Int? = null,
): CollectionCatalogFetch {
    val primary = try {
        Result.success(
            fetchCatalogPage(
                manifestUrl = manifestUrl,
                type = type,
                catalogId = catalogId,
                genre = genre,
                skip = skip,
                maxItems = maxItems,
            ),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }
    val primaryPage = primary.getOrNull()
    if (fallbackCatalogId == null || fallbackCatalogId == catalogId || (primaryPage != null && primaryPage.rawItemCount > 0)) {
        return CollectionCatalogFetch(primary.getOrThrow(), catalogId)
    }
    val fallbackPage = try {
        fetchCatalogPage(
            manifestUrl = manifestUrl,
            type = type,
            catalogId = fallbackCatalogId,
            genre = genre,
            skip = skip,
            maxItems = maxItems,
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        null
    }
    return if (fallbackPage != null && fallbackPage.rawItemCount > 0) {
        CollectionCatalogFetch(fallbackPage, fallbackCatalogId)
    } else {
        CollectionCatalogFetch(primary.getOrThrow(), catalogId)
    }
}
