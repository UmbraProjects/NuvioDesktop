package com.nuvio.app.features.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioViewAllPillSize
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.features.home.components.HomePosterCard
import com.nuvio.app.features.home.components.HomeSkeletonRow

@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    onPosterClick: ((LibraryItem) -> Unit)? = null,
    onSectionViewAllClick: ((LibrarySection) -> Unit)? = null,
) {
    val uiState by remember {
        LibraryRepository.ensureLoaded()
        LibraryRepository.uiState
    }.collectAsStateWithLifecycle()
    var pendingRemovalItem by remember { mutableStateOf<LibraryItem?>(null) }
    val isTraktSource = uiState.sourceMode == LibrarySourceMode.TRAKT

    NuvioScreen(
        modifier = modifier,
        horizontalPadding = 0.dp,
    ) {
        stickyHeader {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                NuvioScreenHeader(
                    title = if (isTraktSource) "Trakt Library" else "Library",
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
        }

        when {
            !uiState.isLoaded || (uiState.isLoading && uiState.sections.isEmpty()) -> {
                items(3) {
                    HomeSkeletonRow(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }

            !uiState.errorMessage.isNullOrBlank() && uiState.sections.isEmpty() -> {
                item {
                    HomeEmptyStateCard(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = if (isTraktSource) "Couldn't load Trakt library" else "Couldn't load library",
                        message = uiState.errorMessage.orEmpty(),
                    )
                }
            }

            uiState.sections.isEmpty() -> {
                item {
                    HomeEmptyStateCard(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = if (isTraktSource) "Your Trakt library is empty" else "Your library is empty",
                        message = if (isTraktSource) {
                            "Connect Trakt and save titles to your watchlist or personal lists."
                        } else {
                            "Saved titles will appear here after you tap Save on a details screen."
                        },
                    )
                }
            }

            else -> {
                librarySections(
                    sections = uiState.sections,
                    onPosterClick = onPosterClick,
                    onSectionViewAllClick = onSectionViewAllClick,
                    onPosterLongClick = { item ->
                        if (!isTraktSource) {
                            pendingRemovalItem = item
                        }
                    },
                )
            }
        }
    }

    NuvioStatusModal(
        title = "Remove from Library?",
        message = pendingRemovalItem?.let { "Remove ${it.name} from your library?" }.orEmpty(),
        isVisible = pendingRemovalItem != null,
        confirmText = "Remove",
        dismissText = "Cancel",
        onConfirm = {
            pendingRemovalItem?.id?.let(LibraryRepository::remove)
            pendingRemovalItem = null
        },
        onDismiss = { pendingRemovalItem = null },
    )
}

private fun LazyListScope.librarySections(
    sections: List<LibrarySection>,
    onPosterClick: ((LibraryItem) -> Unit)?,
    onSectionViewAllClick: ((LibrarySection) -> Unit)?,
    onPosterLongClick: (LibraryItem) -> Unit,
) {
    items(
        items = sections,
        key = { section -> section.type },
    ) { section ->
        val previewItems = section.items.take(LIBRARY_SECTION_PREVIEW_LIMIT)
        NuvioShelfSection(
            title = section.displayTitle,
            entries = previewItems,
            headerHorizontalPadding = 16.dp,
            rowContentPadding = PaddingValues(horizontal = 16.dp),
            onViewAllClick = if (section.items.size > LIBRARY_SECTION_PREVIEW_LIMIT) {
                onSectionViewAllClick?.let { { it(section) } }
            } else {
                null
            },
            viewAllPillSize = NuvioViewAllPillSize.Compact,
            key = { item -> "${item.type}:${item.id}" },
        ) { item ->
            HomePosterCard(
                item = item.toMetaPreview(),
                onClick = onPosterClick?.let { { it(item) } },
                onLongClick = { onPosterLongClick(item) },
            )
        }
    }
}

private const val LIBRARY_SECTION_PREVIEW_LIMIT = 18
