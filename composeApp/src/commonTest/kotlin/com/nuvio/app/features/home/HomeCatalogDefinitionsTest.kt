package com.nuvio.app.features.home

import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.ManagedAddon
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeCatalogDefinitionsTest {
    @Test
    fun `home definitions skip catalogs hidden from home`() {
        val addon = ManagedAddon(
            manifestUrl = "https://example.test/manifest.json",
            manifest = AddonManifest(
                id = "example",
                name = "Example",
                description = "",
                version = "1.0.0",
                resources = listOf(AddonResource(name = "catalog", types = listOf("movie"))),
                types = listOf("movie"),
                catalogs = listOf(
                    AddonCatalog(type = "movie", id = "visible", name = "Visible"),
                    AddonCatalog(type = "movie", id = "collection-only", name = "Collection Only", showInHome = false),
                ),
                transportUrl = "https://example.test/manifest.json",
            ),
        )

        val definitions = buildHomeCatalogDefinitions(listOf(addon))

        assertEquals(listOf("example:movie:visible"), definitions.map(HomeCatalogDefinition::key))
    }
}
