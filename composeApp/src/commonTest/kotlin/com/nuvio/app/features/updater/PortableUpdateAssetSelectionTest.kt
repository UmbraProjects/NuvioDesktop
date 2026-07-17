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

    @Test
    fun `parses github sha256 digest into lowercase hex`() {
        val hex = "a".repeat(64)
        val parsed = GitHubAssetDto(
            name = "Nuvio.zip",
            browserDownloadUrl = "https://example.test/Nuvio.zip",
            digest = "sha256:${hex.uppercase()}",
        ).sha256Hex()

        assertEquals(hex, parsed)
    }

    @Test
    fun `ignores missing malformed or non-sha256 digests`() {
        fun digestOf(value: String?) = GitHubAssetDto(
            name = "Nuvio.zip",
            browserDownloadUrl = "https://example.test/Nuvio.zip",
            digest = value,
        ).sha256Hex()

        assertNull(digestOf(null))
        assertNull(digestOf("sha512:${"a".repeat(128)}")) // wrong algorithm
        assertNull(digestOf("sha256:not-hex"))
        assertNull(digestOf("sha256:${"a".repeat(63)}")) // too short
    }

    private fun asset(name: String, contentType: String) =
        GitHubAssetDto(
            name = name,
            browserDownloadUrl = "https://example.test/$name",
            contentType = contentType,
        )
}
