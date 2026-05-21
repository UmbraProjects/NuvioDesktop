package com.nuvio.app.features.cloud

import com.nuvio.app.features.debrid.DebridProvider
import com.nuvio.app.features.debrid.DebridProviderCapability
import com.nuvio.app.features.debrid.DebridServiceCredential
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudLibraryStoreTest {
    @Test
    fun `refresh aggregates multiple providers without provider-specific assumptions`() = runBlocking {
        val firstProvider = cloudProvider(id = "alpha", name = "Alpha")
        val secondProvider = cloudProvider(id = "beta", name = "Beta")
        val store = CloudLibraryStore(
            credentialsProvider = {
                listOf(
                    DebridServiceCredential(firstProvider, "alpha-token"),
                    DebridServiceCredential(secondProvider, "beta-token"),
                )
            },
            providerApis = listOf(
                FakeCloudProviderApi(
                    provider = firstProvider,
                    items = listOf(cloudItem(firstProvider, "one")),
                ),
                FakeCloudProviderApi(
                    provider = secondProvider,
                    items = listOf(cloudItem(secondProvider, "two")),
                ),
            ),
        )

        val state = store.refresh()

        assertTrue(state.isLoaded)
        assertEquals(listOf("alpha", "beta"), state.providers.map { it.providerId })
        assertEquals(listOf("one", "two"), state.items.map { it.id })
    }

    @Test
    fun `refresh ignores connected providers without cloud library capability`() = runBlocking {
        val cloudProvider = cloudProvider(id = "cloud", name = "Cloud")
        val unsupportedProvider = DebridProvider(
            id = "plain",
            displayName = "Plain",
            shortName = "P",
            capabilities = setOf(DebridProviderCapability.ClientResolve),
        )
        val store = CloudLibraryStore(
            credentialsProvider = {
                listOf(
                    DebridServiceCredential(cloudProvider, "cloud-token"),
                    DebridServiceCredential(unsupportedProvider, "plain-token"),
                )
            },
            providerApis = listOf(
                FakeCloudProviderApi(
                    provider = cloudProvider,
                    items = listOf(cloudItem(cloudProvider, "cloud-item")),
                ),
            ),
        )

        val state = store.refresh()

        assertEquals(listOf("cloud"), state.providers.map { it.providerId })
        assertEquals(listOf("cloud-item"), state.items.map { it.id })
    }
}

private class FakeCloudProviderApi(
    override val provider: DebridProvider,
    private val items: List<CloudLibraryItem>,
) : CloudLibraryProviderApi {
    override suspend fun listItems(apiKey: String): Result<List<CloudLibraryItem>> =
        Result.success(items)

    override suspend fun resolvePlayback(
        apiKey: String,
        item: CloudLibraryItem,
        file: CloudLibraryFile,
    ): CloudLibraryPlaybackResult =
        CloudLibraryPlaybackResult.Success(url = "https://example.test/${item.id}/${file.id}")
}

private fun cloudProvider(id: String, name: String): DebridProvider =
    DebridProvider(
        id = id,
        displayName = name,
        shortName = name.take(1),
        capabilities = setOf(DebridProviderCapability.CloudLibrary),
    )

private fun cloudItem(provider: DebridProvider, id: String): CloudLibraryItem =
    CloudLibraryItem(
        providerId = provider.id,
        providerName = provider.displayName,
        id = id,
        type = CloudLibraryItemType.Torrent,
        name = id,
        files = listOf(
            CloudLibraryFile(
                id = "file-$id",
                name = "$id.mkv",
                playable = true,
            ),
        ),
    )
