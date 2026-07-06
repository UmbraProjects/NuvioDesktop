package com.nuvio.app.features.home

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

fun buildHomeCatalogDefinitions(addons: List<ManagedAddon>): List<HomeCatalogDefinition> =
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
