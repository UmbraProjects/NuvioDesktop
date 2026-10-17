package com.nuvio.app.features.collection

import com.nuvio.app.features.home.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals

class FolderFailedSourcesTest {

    @Test
    fun `only finished sources with an error and nothing to show count as failed`() {
        val state = FolderDetailUiState(
            tabs = listOf(
                FolderTab(label = "All", isAllTab = true, isLoading = false, error = "boom"),
                FolderTab(label = "failed", isLoading = false, error = "Add a TMDB API key"),
                FolderTab(label = "still loading", isLoading = true, error = null),
                FolderTab(label = "loaded", isLoading = false, items = listOf(MetaPreview(id = "a", type = "movie", name = "a"))),
                FolderTab(label = "empty, no error", isLoading = false),
                FolderTab(
                    label = "later page failed",
                    isLoading = false,
                    items = listOf(MetaPreview(id = "b", type = "movie", name = "b")),
                    error = "HTTP 500",
                ),
            ),
        )
        assertEquals(listOf("failed"), state.failedSources.map { it.label })
    }
}
