package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddonManifestParserTest {
    @Test
    fun `catalog showInHome is parsed and defaults to true`() {
        val manifest = AddonManifestParser.parse(
            manifestUrl = "https://example.test/manifest.json",
            payload = """
                {
                  "id": "example",
                  "name": "Example",
                  "version": "1.0.0",
                  "resources": ["catalog"],
                  "types": ["movie"],
                  "catalogs": [
                    { "type": "movie", "id": "visible", "name": "Visible", "showInHome": true },
                    { "type": "movie", "id": "collection-only", "name": "Collection Only", "showInHome": false },
                    { "type": "series", "id": "default-visible", "name": "Default Visible" }
                  ]
                }
            """.trimIndent(),
        )

        assertTrue(manifest.catalogs.first { it.id == "visible" }.showInHome)
        assertFalse(manifest.catalogs.first { it.id == "collection-only" }.showInHome)
        assertTrue(manifest.catalogs.first { it.id == "default-visible" }.showInHome)
    }
}
