package com.nuvio.app.features.home

import androidx.compose.ui.text.intl.Locale
import com.nuvio.app.core.i18n.localizedMediaTypeLabel
import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.catalog.supportsPagination
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.home_catalog_default_title
import org.jetbrains.compose.resources.getString

data class HomeCatalogDefinition(
    val key: String,
    val defaultTitle: String,
    val addonName: String,
    val manifestUrl: String,
    val type: String,
    val catalogId: String,
    val genre: String? = null,
    val supportsPagination: Boolean,
)

/**
 * Memoized: Home calls this two or three times on every entry (settings sync, HomeRepository.refresh,
 * Random Play), and each catalog's title is a `runBlocking` resource lookup on the UI thread
 * (2026-10-19: these frames were in most Home-return stalls). The result depends only on each
 * addon's URL, manifest, name and enabled flag, and on the locale the titles are in.
 */
fun buildHomeCatalogDefinitions(addons: List<ManagedAddon>): List<HomeCatalogDefinition> {
    val locale = Locale.current.toLanguageTag()
    homeDefinitionsMemo?.let { memo ->
        if (memo.locale == locale && memo.addons.sameDefinitionInputs(addons)) return memo.result
    }
    val result = computeHomeCatalogDefinitions(addons)
    homeDefinitionsMemo = HomeDefinitionsMemo(addons, locale, result)
    return result
}

private class HomeDefinitionsMemo(
    val addons: List<ManagedAddon>,
    val locale: String,
    val result: List<HomeCatalogDefinition>,
)

// Replaced whole, never mutated: a race between threads only costs a recomputation.
private var homeDefinitionsMemo: HomeDefinitionsMemo? = null

private fun List<ManagedAddon>.sameDefinitionInputs(other: List<ManagedAddon>): Boolean {
    if (this === other) return true
    if (size != other.size) return false
    for (index in indices) {
        val a = this[index]
        val b = other[index]
        if (a === b) continue
        // Manifests are compared by reference: a refetch builds a new one, so a changed catalog
        // list always misses, and comparing the parsed manifests deeply would cost more than this.
        if (a.manifestUrl != b.manifestUrl || a.manifest !== b.manifest ||
            a.userSetName != b.userSetName || a.enabled != b.enabled
        ) {
            return false
        }
    }
    return true
}

private fun computeHomeCatalogDefinitions(addons: List<ManagedAddon>): List<HomeCatalogDefinition> =
    addons.enabledAddons().mapNotNull { addon ->
        val manifest = addon.manifest ?: return@mapNotNull null
        addon to manifest
    }.flatMap { (addon, manifest) ->
        manifest.catalogs
            .filter(AddonCatalog::showInHome)
            .mapNotNull { catalog ->
                val defaultGenre = catalog.defaultRequiredGenre()
                val hasUnsupportedRequiredExtra = catalog.extra.any { extra ->
                    extra.isRequired &&
                        (!extra.name.equals("genre", ignoreCase = true) || defaultGenre == null)
                }
                if (hasUnsupportedRequiredExtra) return@mapNotNull null
                HomeCatalogDefinition(
                    key = buildString {
                        append("${manifest.id}:${catalog.type}:${catalog.id}")
                        defaultGenre?.let { append(":genre=$it") }
                    },
                    defaultTitle = runBlocking {
                        getString(
                            Res.string.home_catalog_default_title,
                            catalog.name,
                            localizedMediaTypeLabel(catalog.type),
                        )
                    },
                    addonName = addon.displayTitle,
                    manifestUrl = addon.manifestUrl,
                    type = catalog.type,
                    catalogId = catalog.id,
                    genre = defaultGenre,
                    supportsPagination = catalog.supportsPagination(),
                )
            }
    }.distinctBy(HomeCatalogDefinition::key)

internal fun String.displayLabel(): String = localizedMediaTypeLabel(this)

private fun AddonCatalog.defaultRequiredGenre(): String? =
    extra.firstOrNull { property ->
        property.isRequired && property.name.equals("genre", ignoreCase = true)
    }?.options?.firstOrNull()
