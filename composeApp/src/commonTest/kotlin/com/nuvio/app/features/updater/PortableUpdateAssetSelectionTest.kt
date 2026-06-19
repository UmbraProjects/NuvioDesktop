package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PortableUpdateAssetSelectionTest {
    @Test
    fun `selects the portable zip used by fork releases`() {
        val selected = selectBestPortableUpdateAsset(
            listOf(
                asset("checksums.txt", "text/plain"),
                asset("Nuvio.zip", "application/x-zip-compressed"),
            ),
        )

        assertEquals("Nuvio.zip", selected?.name)
    }

    @Test
    fun `does not treat installers as portable updates`() {
        val selected = selectBestPortableUpdateAsset(
            listOf(
                asset("Nuvio.msi", "application/octet-stream"),
                asset("Nuvio.exe", "application/octet-stream"),
            ),
        )

        assertNull(selected)
    }

    private fun asset(name: String, contentType: String) =
        GitHubAssetDto(
            name = name,
            browserDownloadUrl = "https://example.test/$name",
            contentType = contentType,
        )
}
