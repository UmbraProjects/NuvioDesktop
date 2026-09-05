package com.nuvio.app.features.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.InputChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import com.nuvio.app.core.ui.NuvioModalDialog
import com.nuvio.app.core.ui.nuvio
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.discover.DiscoverSeedService
import com.nuvio.app.features.discover.DroppedDiscoverFilter
import com.nuvio.app.features.discover.discoverCollectionFileName
import com.nuvio.app.features.discover.discoverRowCollection
import com.nuvio.app.features.discover.encodeCollectionsExport
import com.nuvio.app.features.discover.toCollectionSources
import com.nuvio.app.features.discover.bingeCatBulkList
import com.nuvio.app.features.discover.withoutItemAt
import com.nuvio.app.features.discover.AiDiscoverRow
import com.nuvio.app.features.discover.aiDiscoverEntryId
import com.nuvio.app.features.discover.aiDiscoverRowId
import com.nuvio.app.features.discover.DiscoverRecommendationRow
import com.nuvio.app.features.mdblist.MdbListItemRef
import com.nuvio.app.features.mdblist.MdbListPublishError
import com.nuvio.app.features.mdblist.MdbListPublishException
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.mdblist.MdbListStaticListRepository
import com.nuvio.app.features.mdblist.MdbListUserList
import com.nuvio.app.features.mdblist.mdbListItemRef
import com.nuvio.app.features.discover.finishGroupKey
import com.nuvio.app.features.discover.finishWhatYouStartedCandidates
import com.nuvio.app.features.discover.finishWhatYouStartedReach
import com.nuvio.app.features.watchprogress.WatchProgressClock
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.discover.CUSTOM_DISCOVER_MIN_RATING_RANGE
import com.nuvio.app.features.discover.CUSTOM_DISCOVER_MIN_VOTES_RANGE
import com.nuvio.app.features.discover.CUSTOM_DISCOVER_MIN_VOTES_STEP
import com.nuvio.app.features.discover.CUSTOM_DISCOVER_REF_LIMIT
import com.nuvio.app.features.discover.CUSTOM_DISCOVER_ROW_LIMIT
import com.nuvio.app.features.discover.CUSTOM_DISCOVER_RUNTIME_RANGE
import com.nuvio.app.features.discover.CUSTOM_DISCOVER_RUNTIME_STEP
import com.nuvio.app.features.discover.CustomDiscoverCertifications
import com.nuvio.app.features.discover.CustomDiscoverLanguages
import com.nuvio.app.features.discover.CustomDiscoverStatus
import com.nuvio.app.features.discover.TmdbRef
import com.nuvio.app.features.tmdb.TmdbCompanyResult
import com.nuvio.app.features.tmdb.TmdbPersonResult
import com.nuvio.app.features.tmdb.TmdbService
import kotlinx.coroutines.delay
import com.nuvio.app.features.discover.CUSTOM_DISCOVER_YEAR_RANGE
import com.nuvio.app.features.discover.CustomDiscoverMediaType
import com.nuvio.app.features.discover.CustomDiscoverRow
import com.nuvio.app.features.discover.CustomDiscoverSort
import com.nuvio.app.features.discover.DiscoverGenreNames
import com.nuvio.app.features.discover.DiscoverRowFamily
import com.nuvio.app.features.discover.customDiscoverRowId
import com.nuvio.app.features.discover.DiscoverCatalogFilePicker
import com.nuvio.app.features.discover.DiscoverCatalogImportError
import com.nuvio.app.features.discover.DiscoverCatalogImportException
import com.nuvio.app.features.discover.DiscoverRecommendationsRepository
import com.nuvio.app.features.discover.IMPORTED_DISCOVER_ROW_LIMIT
import com.nuvio.app.features.discover.discoverCatalogFileName
import com.nuvio.app.features.discover.discoverExportTimestamp
import com.nuvio.app.features.discover.encodeToString
import com.nuvio.app.features.discover.parseDiscoverCatalog
import com.nuvio.app.features.discover.toCatalogDocument
import com.nuvio.app.features.discover.toCustomRow
import com.nuvio.app.features.discover.toImportedRow
import com.nuvio.app.features.discover.ImportedDiscoverRow
import com.nuvio.app.features.discover.importedDiscoverEntryId
import com.nuvio.app.features.discover.importedDiscoverRowId
import com.nuvio.app.features.home.DISCOVER_BECAUSE_ROWS_RANGE
import com.nuvio.app.features.home.DISCOVER_FINISH_IDLE_DAYS_RANGE
import com.nuvio.app.features.home.DISCOVER_TRENDING_GENRE_ROWS_RANGE
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeCatalogSettingsUiState
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import com.nuvio.app.core.ui.NuvioTextField

/**
 * Settings for the Discover tab.
 *
 * Deliberately its own page rather than more rows on Homescreen: Discover's row generators each
 * bring their own knobs, and burying them among the home-hero settings would make both harder to
 * find. Note that "hide unreleased" is *not* duplicated here — that is the app-wide preference on
 * the Homescreen page, and Discover honours it rather than offering a second, conflicting switch.
 *
 * The page is in four parts, and the split is what keeps each control meaning one thing:
 *
 * - **Your rows** owns *whether* and *where*. Every row family and every custom row appears here
 *   exactly once, with a switch and a drag handle — plus the one action that operates on a row you
 *   already have, which is exporting it.
 * - **Add a row** owns *making another*: the app's own suggestions first, then a custom query, an
 *   AI prompt, and an imported file.
 * - **Row options** owns *how much*: the numeric knobs for families that produce a variable number
 *   of shelves, and the idle threshold. There is no switch here, because the switch is upstairs.
 * - **Filters** and **Excluded genres** apply across every generated row.
 *
 * The row-family switches route through [HomeCatalogSettingsRepository.setDiscoverRowFamilyEnabled]
 * rather than the per-family setters, because two of the families express "off" as a count of zero
 * and it is that method's job to know which.
 */
internal fun LazyListScope.discoverSettingsContent(
    isTablet: Boolean,
    settings: HomeCatalogSettingsUiState,
) {
    item {
        // The editing row id, not the row: holding the row itself would freeze the copy taken when
        // the dialog opened, and every control in it writes through the repository immediately.
        var editingRowId by remember { mutableStateOf<String?>(null) }
        var editingImportedRowId by remember { mutableStateOf<String?>(null) }
        var editingAiRowId by remember { mutableStateOf<String?>(null) }
        // Two sections, because they answer different questions. This one is "what have I got, in
        // what order, and what can I do with it"; the next is "make me another". They were one
        // group of eleven rows where the list you came to reorder sat above six buttons you use
        // once a month.
        SettingsSection(
            title = stringResource(Res.string.settings_discover_section_row_order),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                DiscoverRowOrderList(
                    isTablet = isTablet,
                    settings = settings,
                    onEditCustomRow = { rowId -> editingRowId = rowId },
                    onEditImportedRow = { rowId -> editingImportedRowId = rowId },
                    onEditAiRow = { rowId -> editingAiRowId = rowId },
                )
            }
        }
        // Both sections live in one lazy item because the editor state below is remembered here —
        // a LazyListScope has no composition of its own to hold it in. That means the list's own
        // between-item spacing never lands between them, so it is reproduced here; without it the
        // two sections butt together while every other pair on the page is spaced apart.
        Spacer(modifier = Modifier.height(SettingsSectionGap))
        SettingsSection(
            title = stringResource(Res.string.settings_discover_section_row_new),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                // First, because a proposal already knows what it wants to ask — it is the cheapest
                // door into a new row for anyone the app has an opinion about.
                SuggestedAiDiscoverRows(
                    isTablet = isTablet,
                    settings = settings,
                    onRowAdded = { rowId -> editingAiRowId = rowId },
                )
                SettingsGroupDivider(isTablet = isTablet)
                AddCustomDiscoverRowButton(
                    isTablet = isTablet,
                    settings = settings,
                    // Straight into the editor: a new row is an empty query, and leaving the user
                    // to find and open the line that just appeared is a step with no purpose.
                    onRowAdded = { rowId -> editingRowId = rowId },
                )
                SettingsGroupDivider(isTablet = isTablet)
                AddAiDiscoverRowButton(
                    isTablet = isTablet,
                    settings = settings,
                    // Straight into the editor, same as a new custom row: a fresh AI row is an
                    // unasked question and the editor is where it gets asked.
                    onRowAdded = { rowId -> editingAiRowId = rowId },
                )
                SettingsGroupDivider(isTablet = isTablet)
                ImportDiscoverRowButton(
                    isTablet = isTablet,
                    settings = settings,
                    // A file carrying a query lands in the editor for the same reason a new row
                    // does: it is a live query the user will want to look at, not a finished thing.
                    onCustomRowImported = { rowId -> editingRowId = rowId },
                )
                SettingsGroupDivider(isTablet = isTablet)
                // Last, because it is the only one that acts on a row that already exists rather
                // than making another.
                DiscoverExportButton(isTablet = isTablet, settings = settings)
            }
        }
        val editingRow = editingRowId?.let { id -> settings.discoverCustomRows.firstOrNull { it.id == id } }
        if (editingRow != null) {
            CustomDiscoverRowDialog(
                row = editingRow,
                isTablet = isTablet,
                onDismiss = { editingRowId = null },
            )
        }
        val editingAiRow = editingAiRowId?.let { id ->
            settings.discoverAiRows.firstOrNull { it.id == id }
        }
        if (editingAiRow != null) {
            AiDiscoverRowDialog(
                row = editingAiRow,
                isTablet = isTablet,
                onDismiss = { editingAiRowId = null },
            )
        }
        val editingImportedRow = editingImportedRowId?.let { id ->
            settings.discoverImportedRows.firstOrNull { it.id == id }
        }
        if (editingImportedRow != null) {
            ImportedDiscoverRowDialog(
                row = editingImportedRow,
                isTablet = isTablet,
                onDismiss = { editingImportedRowId = null },
            )
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_discover_section_rows),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                val idleDays = settings.discoverFinishIdleDays
                val idleDaysFormat = stringResource(Res.string.settings_discover_finish_idle_days_value, idleDays)
                // Read once per visit to the page, not per slider position: the candidate set does
                // not change while the settings screen is open, and re-deriving it on every drag
                // frame would walk the whole progress store for a label.
                val idleCandidates = remember {
                    finishWhatYouStartedCandidates(
                        entries = WatchProgressRepository.localPlaybackEntries(),
                        continueWatchingParentKeys = WatchProgressRepository.uiState.value
                            .continueWatchingEntries
                            .mapTo(mutableSetOf()) { finishGroupKey(it.parentMetaType, it.parentMetaId) },
                    )
                }
                // Why this exists: most of this slider's range cannot match anything on a normal
                // install. Watch progress is written per playback and cleared as titles finish, so
                // the oldest entry is typically two to three weeks old while the slider offers up
                // to 180 days — and past that point the row simply does not appear, with nothing
                // on screen saying why. Reported here rather than by capping the range, because
                // the horizon is a property of how much the user watches, not a system constant.
                val reach = finishWhatYouStartedReach(
                    entries = idleCandidates,
                    continueWatchingParentKeys = emptySet(),
                    now = WatchProgressClock.nowEpochMs(),
                    minIdleDays = idleDays,
                )
                val reachText = when {
                    reach.matching == 1 -> stringResource(Res.string.settings_discover_finish_idle_reach_one)
                    reach.matching > 0 ->
                        stringResource(Res.string.settings_discover_finish_idle_reach_matching, reach.matching)
                    reach.oldestIdleDays != null -> stringResource(
                        Res.string.settings_discover_finish_idle_reach_too_high,
                        reach.oldestIdleDays,
                    )
                    else -> stringResource(Res.string.settings_discover_finish_idle_reach_none)
                }
                SettingsSliderRow(
                    title = stringResource(Res.string.settings_discover_finish_idle_days),
                    description = stringResource(Res.string.settings_discover_finish_idle_days_description) +
                        " " + reachText,
                    value = idleDays,
                    valueText = idleDaysFormat,
                    // Formatted per candidate value so dragging the slider reads correctly; the
                    // default substring swap would mangle a localised unit.
                    valueTextForValue = { days -> idleDaysFormat.replaceFirst("$idleDays", "$days") },
                    valueRange = DISCOVER_FINISH_IDLE_DAYS_RANGE,
                    step = 1,
                    isTablet = isTablet,
                    enabled = settings.discoverFinishWhatYouStartedEnabled,
                    modifier = Modifier.settingsScrollAnchor(
                        SettingsScrollAnchor.searchKey("discover-finish-idle-days"),
                    ),
                    onValueChange = HomeCatalogSettingsRepository::setDiscoverFinishIdleDays,
                )
                SettingsGroupDivider(isTablet = isTablet)
                val rows = settings.discoverBecauseYouWatchedRows
                SettingsSliderRow(
                    title = stringResource(Res.string.settings_discover_because_rows),
                    description = stringResource(Res.string.settings_discover_because_rows_description),
                    value = rows,
                    valueText = "$rows",
                    valueTextForValue = { "$it" },
                    valueRange = DISCOVER_BECAUSE_ROWS_RANGE,
                    step = 1,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(
                        SettingsScrollAnchor.searchKey("discover-because-rows"),
                    ),
                    onValueChange = HomeCatalogSettingsRepository::setDiscoverBecauseYouWatchedRows,
                )
                SettingsGroupDivider(isTablet = isTablet)
                val trendingRows = settings.discoverTrendingGenreRows
                SettingsSliderRow(
                    title = stringResource(Res.string.settings_discover_trending_rows),
                    description = stringResource(Res.string.settings_discover_trending_rows_description),
                    value = trendingRows,
                    valueText = "$trendingRows",
                    valueTextForValue = { "$it" },
                    valueRange = DISCOVER_TRENDING_GENRE_ROWS_RANGE,
                    step = 1,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(
                        SettingsScrollAnchor.searchKey("discover-trending-rows"),
                    ),
                    onValueChange = HomeCatalogSettingsRepository::setDiscoverTrendingGenreRows,
                )
                SettingsGroupDivider(isTablet = isTablet)
                // Was a "Filters" section of its own holding this single switch. It shapes what the
                // rows above are allowed to contain, which is the same question the rest of this
                // section answers, and one row does not carry a heading of its own.
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_discover_hide_watched),
                    description = stringResource(Res.string.settings_discover_hide_watched_description),
                    checked = settings.discoverHideWatched,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(
                        SettingsScrollAnchor.searchKey("discover-hide-watched"),
                    ),
                    onCheckedChange = HomeCatalogSettingsRepository::setDiscoverHideWatched,
                )
            }
        }
    }
    item {
        // Switched-on means excluded, which is the inverse of the Random Play genre list next door.
        // It reads correctly here because the default is "nothing excluded": a page of genres that
        // all start on, where turning one off is the action, would be a worse lie about the default.
        SettingsSection(
            title = stringResource(Res.string.settings_discover_section_excluded_genres),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                DiscoverGenreNames.forEachIndexed { index, genre ->
                    SettingsSwitchRow(
                        title = genre,
                        // The explanation rides on the first row rather than a section subtitle,
                        // which SettingsSection has no slot for.
                        description = if (index == 0) {
                            stringResource(Res.string.settings_discover_excluded_genres_description)
                        } else {
                            null
                        },
                        checked = genre in settings.discoverExcludedGenres,
                        isTablet = isTablet,
                        modifier = if (index == 0) {
                            Modifier.settingsScrollAnchor(
                                SettingsScrollAnchor.searchKey("discover-excluded-genres"),
                            )
                        } else {
                            Modifier
                        },
                        onCheckedChange = { excluded ->
                            HomeCatalogSettingsRepository.setDiscoverGenreExcluded(genre, excluded)
                        },
                    )
                    if (index != DiscoverGenreNames.lastIndex) {
                        SettingsGroupDivider(isTablet = isTablet)
                    }
                }
            }
        }
    }
    item {
        // Last on the page: the provider settings are configured once and then never touched,
        // while everything above is what a user actually comes here to change.
        DiscoverAiProviderSection(isTablet = isTablet)
    }
}

/** One line of the row-order list: a built-in family, or one of the user's own rows. */
private sealed interface DiscoverRowEntry {
    val entryId: String

    data class Family(val family: DiscoverRowFamily, val enabled: Boolean) : DiscoverRowEntry {
        override val entryId: String get() = family.entryId
    }

    data class Custom(val row: CustomDiscoverRow) : DiscoverRowEntry {
        override val entryId: String get() = row.entryId
    }

    data class Imported(val row: ImportedDiscoverRow) : DiscoverRowEntry {
        override val entryId: String get() = importedDiscoverEntryId(row.id)
    }

    data class Ai(val row: AiDiscoverRow) : DiscoverRowEntry {
        override val entryId: String get() = aiDiscoverEntryId(row.id)
    }
}

/**
 * The saved order, resolved to entries. [HomeCatalogSettingsUiState.discoverRowOrder] is already
 * normalised, so anything it names exists; an id that somehow does not resolve is dropped rather
 * than rendered as a blank row.
 */
@Composable
private fun rememberDiscoverRowEntries(settings: HomeCatalogSettingsUiState): List<DiscoverRowEntry> =
    remember(
        settings.discoverRowOrder,
        settings.discoverCustomRows,
        settings.discoverImportedRows,
        settings.discoverAiRows,
        settings.discoverFamilyEnabledKey(),
    ) {
        settings.discoverRowOrder.mapNotNull { entryId ->
            val customId = customDiscoverRowId(entryId)
            val importedId = importedDiscoverRowId(entryId)
            val aiId = aiDiscoverRowId(entryId)
            if (aiId != null) {
                settings.discoverAiRows.firstOrNull { it.id == aiId }?.let(DiscoverRowEntry::Ai)
            } else if (importedId != null) {
                settings.discoverImportedRows.firstOrNull { it.id == importedId }
                    ?.let(DiscoverRowEntry::Imported)
            } else if (customId != null) {
                settings.discoverCustomRows.firstOrNull { it.id == customId }?.let(DiscoverRowEntry::Custom)
            } else {
                DiscoverRowFamily.entries.firstOrNull { it.entryId == entryId }?.let { family ->
                    DiscoverRowEntry.Family(family, settings.isDiscoverFamilyEnabled(family))
                }
            }
        }
    }

/** Whether a family currently renders. Two of them say so with a count — see the page docs. */
private fun HomeCatalogSettingsUiState.isDiscoverFamilyEnabled(family: DiscoverRowFamily): Boolean =
    when (family) {
        DiscoverRowFamily.Finish -> discoverFinishWhatYouStartedEnabled
        DiscoverRowFamily.Because -> discoverBecauseYouWatchedRows > 0
        DiscoverRowFamily.Favourites -> discoverMoreLikeFavouritesEnabled
        DiscoverRowFamily.Gems -> discoverHiddenGemsEnabled
        DiscoverRowFamily.Trending -> discoverTrendingGenreRows > 0
    }

/** Cheap remember key covering every input to [isDiscoverFamilyEnabled]. */
private fun HomeCatalogSettingsUiState.discoverFamilyEnabledKey(): String =
    DiscoverRowFamily.entries.joinToString(",") { if (isDiscoverFamilyEnabled(it)) "1" else "0" }

@Composable
private fun discoverFamilyTitle(family: DiscoverRowFamily): String = when (family) {
    DiscoverRowFamily.Finish -> stringResource(Res.string.settings_discover_finish_started)
    DiscoverRowFamily.Because -> stringResource(Res.string.settings_discover_because_rows)
    DiscoverRowFamily.Favourites -> stringResource(Res.string.settings_discover_more_like_favourites)
    DiscoverRowFamily.Gems -> stringResource(Res.string.settings_discover_hidden_gems)
    DiscoverRowFamily.Trending -> stringResource(Res.string.settings_discover_trending_rows)
}

@Composable
private fun discoverFamilyDescription(family: DiscoverRowFamily): String = when (family) {
    DiscoverRowFamily.Finish -> stringResource(Res.string.settings_discover_finish_started_description)
    DiscoverRowFamily.Because -> stringResource(Res.string.settings_discover_because_rows_description)
    DiscoverRowFamily.Favourites ->
        stringResource(Res.string.settings_discover_more_like_favourites_description)
    DiscoverRowFamily.Gems -> stringResource(Res.string.settings_discover_hidden_gems_description)
    DiscoverRowFamily.Trending -> stringResource(Res.string.settings_discover_trending_rows_description)
}

/**
 * The search anchor each family keeps, or null for the two whose registered key belongs to a
 * slider in the section below.
 *
 * The three that keep one are the keys the settings search already had when these families were
 * plain switches on this page; the anchor follows the switch here rather than staying behind. The
 * other two are deliberately not duplicated: two elements answering one anchor request both try to
 * scroll themselves into view, and the list ends up wherever the second one lands.
 */
private fun discoverFamilySearchKey(family: DiscoverRowFamily): String? = when (family) {
    DiscoverRowFamily.Finish -> "discover-finish-started"
    DiscoverRowFamily.Favourites -> "discover-more-like-favourites"
    DiscoverRowFamily.Gems -> "discover-hidden-gems"
    DiscoverRowFamily.Because, DiscoverRowFamily.Trending -> null
}

@Composable
private fun DiscoverRowOrderList(
    isTablet: Boolean,
    settings: HomeCatalogSettingsUiState,
    onEditCustomRow: (String) -> Unit,
    onEditImportedRow: (String) -> Unit,
    onEditAiRow: (String) -> Unit,
) {
    val entries = rememberDiscoverRowEntries(settings)
    val hapticFeedback = LocalHapticFeedback.current
    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(lazyListState = lazyListState) { from, to ->
        HomeCatalogSettingsRepository.moveDiscoverRowByIndex(from.index, to.index)
        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            // Bounded for the same reason the homescreen catalog list is: a lazy list inside a lazy
            // list has no height of its own to measure against.
            .heightIn(max = if (isTablet) 900.dp else 680.dp)
            // The list itself answers the section's anchor. A per-row anchor inside a lazy list only
            // works while that row is composed, so anything landing here from search aims at the
            // list rather than at a row that may not exist yet.
            .settingsScrollAnchor(SettingsScrollAnchor.searchKey("discover-row-order")),
        state = lazyListState,
    ) {
        itemsIndexed(entries, key = { _, entry -> entry.entryId }) { index, entry ->
            ReorderableItem(reorderableLazyListState, key = entry.entryId) { isDragging ->
                val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp)
                Surface(
                    modifier = with(this@ReorderableItem) {
                        Modifier.draggableHandle(
                            onDragStarted = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragStopped = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            },
                        )
                    },
                    color = Color.Transparent,
                    shadowElevation = elevation,
                ) {
                    Column {
                        if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                        when (entry) {
                            is DiscoverRowEntry.Family -> DiscoverFamilyRow(
                                entry = entry,
                                isTablet = isTablet,
                            )

                            is DiscoverRowEntry.Custom -> CustomDiscoverRowEntry(
                                row = entry.row,
                                isTablet = isTablet,
                                onEdit = { onEditCustomRow(entry.row.id) },
                            )

                            is DiscoverRowEntry.Imported -> ImportedDiscoverRowEntry(
                                row = entry.row,
                                isTablet = isTablet,
                                onEdit = { onEditImportedRow(entry.row.id) },
                            )

                            is DiscoverRowEntry.Ai -> AiDiscoverRowEntry(
                                row = entry.row,
                                isTablet = isTablet,
                                onEdit = { onEditAiRow(entry.row.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverFamilyRow(
    entry: DiscoverRowEntry.Family,
    isTablet: Boolean,
) {
    DiscoverRowShell(
        title = discoverFamilyTitle(entry.family),
        enabled = entry.enabled,
        isTablet = isTablet,
        modifier = discoverFamilySearchKey(entry.family)
            ?.let { key -> Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey(key)) }
            ?: Modifier,
        onEnabledChange = { enabled ->
            HomeCatalogSettingsRepository.setDiscoverRowFamilyEnabled(entry.family, enabled)
        },
    )
}

/**
 * One line of the reorder list: name, state and switch. The whole line is draggable.
 *
 * **Deliberately two lines of text and no description.** This started out rendering each family's
 * full settings description, which is a paragraph — five of those plus custom rows turned the list
 * into something you had to scroll through to reorder, inside a page you were already scrolling.
 * The descriptions still exist; they are on the controls in the sections below, where there is room
 * for them.
 *
 * [onClick] is null for a family, which has nothing to open — a line that highlights under the
 * cursor and then does nothing when clicked reads as a broken control.
 */
@Composable
private fun DiscoverRowShell(
    title: String,
    enabled: Boolean,
    isTablet: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(
                        if (enabled) {
                            stringResource(Res.string.settings_discover_row_visible)
                        } else {
                            stringResource(Res.string.settings_discover_row_hidden)
                        },
                    )
                    if (!subtitle.isNullOrBlank()) {
                        append(" • ")
                        append(subtitle)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            trailingContent?.invoke()
            SettingsSquareSwitch(checked = enabled, onCheckedChange = onEnabledChange)
        }
    }
}

@Composable
private fun CustomDiscoverRowEntry(
    row: CustomDiscoverRow,
    isTablet: Boolean,
    onEdit: () -> Unit,
) {
    DiscoverRowShell(
        title = row.title.ifBlank { stringResource(Res.string.settings_discover_row_custom) },
        subtitle = row.genres.sorted().joinToString(", ")
            .ifBlank { stringResource(Res.string.settings_discover_custom_genres_any) },
        enabled = row.enabled,
        isTablet = isTablet,
        onEnabledChange = { HomeCatalogSettingsRepository.updateDiscoverCustomRow(row.copy(enabled = it)) },
        onClick = onEdit,
        trailingContent = {
            // The whole line opens the editor as well; the pencil is here because a line that also
            // carries a switch and a drag handle does not look clickable on its own.
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = stringResource(Res.string.settings_discover_custom_edit),
                    tint = MaterialTheme.nuvio.colors.textMuted,
                )
            }
        },
    )
}

/**
 * An imported list in the reorder list.
 *
 * Carries a remove button rather than an editor: there is nothing to edit. The items came from a
 * file and cannot be refreshed — that is exactly what distinguishes this from a custom row, whose
 * query gets re-asked on every build — so offering controls that look editable would be a lie about
 * what the row can do.
 */
@Composable
private fun ImportedDiscoverRowEntry(
    row: ImportedDiscoverRow,
    isTablet: Boolean,
    onEdit: () -> Unit,
) {
    DiscoverRowShell(
        title = row.title.ifBlank { stringResource(Res.string.settings_discover_imported_row) },
        subtitle = stringResource(Res.string.settings_discover_imported_subtitle, row.items.size),
        enabled = row.enabled,
        isTablet = isTablet,
        onEnabledChange = {
            HomeCatalogSettingsRepository.updateDiscoverImportedRow(row.copy(enabled = it))
        },
        onClick = onEdit,
        trailingContent = {
            IconButton(onClick = { HomeCatalogSettingsRepository.removeDiscoverImportedRow(row.id) }) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.settings_discover_imported_remove),
                    tint = MaterialTheme.nuvio.colors.textMuted,
                )
            }
        },
    )
}

/**
 * The query editor, in a modal.
 *
 * It was first built inline, expanding under its row, on the reasoning that every control in it is
 * a settings control and belongs in the settings page. Testing killed that: eight controls unfolding
 * inside a bounded, scrollable, drag-reorderable list that is itself inside the scrolling settings
 * page gives you three nested scroll regions and a row-order list that moves under the cursor while
 * you are editing. The modal has the room, and the list stays still. **Do not fold this back inline.**
 *
 * There is no Cancel, and no draft copy: every control writes straight through to the repository,
 * the same as the rest of the settings page. Close is the only action, plus Delete.
 */
@Composable
private fun CustomDiscoverRowDialog(
    row: CustomDiscoverRow,
    isTablet: Boolean,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    fun update(transform: (CustomDiscoverRow) -> CustomDiscoverRow) {
        HomeCatalogSettingsRepository.updateDiscoverCustomRow(transform(row))
    }

    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = row.title.ifBlank { stringResource(Res.string.settings_discover_row_custom) },
        subtitle = stringResource(Res.string.settings_discover_custom_dialog_subtitle),
        maxWidth = CustomRowDialogWidth,
        modifier = Modifier.width(CustomRowDialogWidth),
        actions = {
            TextButton(
                onClick = {
                    // Dismiss first: the row is about to stop existing, and the dialog reads its
                    // content from the list it is being removed from.
                    onDismiss()
                    HomeCatalogSettingsRepository.removeDiscoverCustomRow(row.id)
                },
                colors = ButtonDefaults.textButtonColors(contentColor = tokens.colors.danger),
            ) { Text(stringResource(Res.string.settings_discover_custom_delete)) }
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_done)) }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // The editor is taller than a short window, so it scrolls inside the panel rather
                // than pushing the action strip off the bottom of the screen.
                .heightIn(max = CustomRowDialogMaxContentHeight)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingsTextRow(
                title = stringResource(Res.string.settings_discover_custom_name),
                description = null,
                value = row.title,
                isTablet = isTablet,
                placeholder = stringResource(Res.string.settings_discover_custom_name_placeholder),
                onValueChange = { title -> update { it.copy(title = title) } },
            )
            SettingsChoiceRow(
                title = stringResource(Res.string.settings_discover_custom_media_type),
                description = null,
                options = listOf(
                    SettingsChoiceOption(
                        CustomDiscoverMediaType.Both,
                        stringResource(Res.string.settings_discover_custom_media_both),
                    ),
                    SettingsChoiceOption(
                        CustomDiscoverMediaType.Movies,
                        stringResource(Res.string.settings_discover_custom_media_movies),
                    ),
                    SettingsChoiceOption(
                        CustomDiscoverMediaType.Shows,
                        stringResource(Res.string.settings_discover_custom_media_shows),
                    ),
                ),
                selectedValue = row.mediaType,
                isTablet = isTablet,
                onSelected = { mediaType -> update { it.copy(mediaType = mediaType) } },
            )
            CustomDiscoverGenrePicker(row = row, isTablet = isTablet, onUpdate = ::update)
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_discover_custom_match_all),
                description = stringResource(Res.string.settings_discover_custom_match_all_description),
                checked = row.matchAllGenres,
                isTablet = isTablet,
                enabled = row.genres.size > 1,
                onCheckedChange = { matchAll -> update { it.copy(matchAllGenres = matchAll) } },
            )
            SettingsChoiceRow(
                title = stringResource(Res.string.settings_discover_custom_sort),
                description = null,
                options = listOf(
                    SettingsChoiceOption(
                        CustomDiscoverSort.Popularity,
                        stringResource(Res.string.settings_discover_custom_sort_popularity),
                    ),
                    SettingsChoiceOption(
                        CustomDiscoverSort.Rating,
                        stringResource(Res.string.settings_discover_custom_sort_rating),
                    ),
                    SettingsChoiceOption(
                        CustomDiscoverSort.Newest,
                        stringResource(Res.string.settings_discover_custom_sort_newest),
                    ),
                    SettingsChoiceOption(
                        CustomDiscoverSort.Oldest,
                        stringResource(Res.string.settings_discover_custom_sort_oldest),
                    ),
                ),
                selectedValue = row.sort,
                isTablet = isTablet,
                onSelected = { sort -> update { it.copy(sort = sort) } },
            )
            val anyLabel = stringResource(Res.string.settings_discover_custom_any)
            val ratingFormat = stringResource(Res.string.settings_discover_custom_min_rating_value, row.minRating)
            SettingsSliderRow(
                title = stringResource(Res.string.settings_discover_custom_min_rating),
                description = stringResource(Res.string.settings_discover_custom_min_rating_description),
                value = row.minRating,
                valueText = if (row.minRating == 0) anyLabel else ratingFormat,
                valueTextForValue = { rating ->
                    if (rating == 0) anyLabel else ratingFormat.replaceFirst("${row.minRating}", "$rating")
                },
                valueRange = CUSTOM_DISCOVER_MIN_RATING_RANGE,
                step = 1,
                isTablet = isTablet,
                onValueChange = { rating -> update { it.copy(minRating = rating) } },
            )
            val votesFormat = stringResource(Res.string.settings_discover_custom_min_votes_value, row.minVotes)
            SettingsSliderRow(
                title = stringResource(Res.string.settings_discover_custom_min_votes),
                description = stringResource(Res.string.settings_discover_custom_min_votes_description),
                value = row.minVotes,
                valueText = if (row.minVotes == 0) anyLabel else votesFormat,
                valueTextForValue = { votes ->
                    if (votes == 0) anyLabel else votesFormat.replaceFirst("${row.minVotes}", "$votes")
                },
                valueRange = CUSTOM_DISCOVER_MIN_VOTES_RANGE,
                step = CUSTOM_DISCOVER_MIN_VOTES_STEP,
                isTablet = isTablet,
                onValueChange = { votes -> update { it.copy(minVotes = votes) } },
            )
            CustomDiscoverYearRow(
                title = stringResource(Res.string.settings_discover_custom_year_from),
                year = row.fromYear,
                anyLabel = anyLabel,
                isTablet = isTablet,
                onYearChange = { year -> update { it.copy(fromYear = year) } },
            )
            CustomDiscoverYearRow(
                title = stringResource(Res.string.settings_discover_custom_year_to),
                year = row.toYear,
                anyLabel = anyLabel,
                isTablet = isTablet,
                onYearChange = { year -> update { it.copy(toYear = year) } },
            )
            SettingsChoiceRow(
                title = stringResource(Res.string.settings_discover_custom_status),
                description = stringResource(Res.string.settings_discover_custom_status_description),
                options = customDiscoverStatusOptions(),
                selectedValue = row.status,
                isTablet = isTablet,
                onSelected = { status -> update { it.copy(status = status) } },
            )
            SettingsChoiceRow(
                title = stringResource(Res.string.settings_discover_custom_language),
                description = stringResource(Res.string.settings_discover_custom_language_description),
                options = remember(anyLabel) {
                    listOf(SettingsChoiceOption("", anyLabel)) +
                        CustomDiscoverLanguages.map { (code, label) -> SettingsChoiceOption(code, label) }
                },
                selectedValue = row.language,
                isTablet = isTablet,
                onSelected = { language -> update { it.copy(language = language) } },
            )
            SettingsChoiceRow(
                title = stringResource(Res.string.settings_discover_custom_certification),
                description = stringResource(Res.string.settings_discover_custom_certification_description),
                options = remember(anyLabel) {
                    listOf(SettingsChoiceOption("", anyLabel)) +
                        CustomDiscoverCertifications.map { SettingsChoiceOption(it, it) }
                },
                selectedValue = row.certification,
                // Nothing to rate on a shows-only row, and TMDB has no certification for shows at
                // all — leaving it live would offer a filter that can only empty the row.
                enabled = row.mediaType != CustomDiscoverMediaType.Shows,
                isTablet = isTablet,
                onSelected = { certification -> update { it.copy(certification = certification) } },
            )
            CustomDiscoverRuntimeRow(
                title = stringResource(Res.string.settings_discover_custom_runtime_min),
                description = stringResource(Res.string.settings_discover_custom_runtime_description),
                minutes = row.minRuntime,
                anyLabel = anyLabel,
                isTablet = isTablet,
                onMinutesChange = { minutes -> update { it.copy(minRuntime = minutes) } },
            )
            CustomDiscoverRuntimeRow(
                title = stringResource(Res.string.settings_discover_custom_runtime_max),
                description = null,
                minutes = row.maxRuntime,
                anyLabel = anyLabel,
                isTablet = isTablet,
                onMinutesChange = { minutes -> update { it.copy(maxRuntime = minutes) } },
            )
            // Companies apply to both namespaces; cast and crew are film-only, so on a shows-only
            // row they are disabled rather than left live to empty the row.
            val filmsOnlyEnabled = row.mediaType != CustomDiscoverMediaType.Shows
            CustomDiscoverRefPicker(
                title = stringResource(Res.string.settings_discover_custom_companies),
                description = stringResource(Res.string.settings_discover_custom_companies_description),
                selected = row.companies,
                enabled = true,
                search = { query -> TmdbService.searchCompanies(query).map { it.toCandidate() } },
                onSelectedChange = { companies -> update { it.copy(companies = companies) } },
            )
            CustomDiscoverRefPicker(
                title = stringResource(Res.string.settings_discover_custom_cast),
                description = stringResource(Res.string.settings_discover_custom_cast_description),
                selected = row.cast,
                enabled = filmsOnlyEnabled,
                search = { query -> TmdbService.searchPeople(query).map { it.toCandidate() } },
                onSelectedChange = { cast -> update { it.copy(cast = cast) } },
            )
            CustomDiscoverRefPicker(
                title = stringResource(Res.string.settings_discover_custom_crew),
                description = stringResource(Res.string.settings_discover_custom_crew_description),
                selected = row.crew,
                enabled = filmsOnlyEnabled,
                search = { query -> TmdbService.searchPeople(query).map { it.toCandidate() } },
                onSelectedChange = { crew -> update { it.copy(crew = crew) } },
            )
        }
    }
}

@Composable
private fun customDiscoverStatusOptions(): List<SettingsChoiceOption<CustomDiscoverStatus>> = listOf(
    SettingsChoiceOption(
        CustomDiscoverStatus.Any,
        stringResource(Res.string.settings_discover_custom_status_any),
    ),
    SettingsChoiceOption(
        CustomDiscoverStatus.InCinemas,
        stringResource(Res.string.settings_discover_custom_status_cinemas),
    ),
    SettingsChoiceOption(
        CustomDiscoverStatus.HomeRelease,
        stringResource(Res.string.settings_discover_custom_status_home),
    ),
    SettingsChoiceOption(
        CustomDiscoverStatus.Returning,
        stringResource(Res.string.settings_discover_custom_status_returning),
    ),
    SettingsChoiceOption(
        CustomDiscoverStatus.Ended,
        stringResource(Res.string.settings_discover_custom_status_ended),
    ),
)

/**
 * Search-as-you-type picker for the three filters TMDB will only accept as numeric ids.
 *
 * Genres could be stored as names because TMDB has a fixed vocabulary to match them against; people
 * and companies have no such thing, so the name has to be resolved to an id **once, here**, and the
 * row stores both halves ([TmdbRef]). That is the whole reason this component exists rather than a
 * text field.
 *
 * Three things it does that a naive version would not:
 *
 * - **Debounces**, and cancels the in-flight search when the query changes. Typing "Scorsese" is
 *   eight keystrokes and would otherwise be eight TMDB requests, arriving out of order.
 * - **Shows what disambiguates**, not just the name — a person's department, a company's country.
 *   TMDB has many same-named records and a bare list of identical names is unpickable.
 * - **Keeps the picked entries visible as chips**, because an id the user cannot see is an id they
 *   cannot tell is wrong. Removing one is a click on its chip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomDiscoverRefPicker(
    title: String,
    description: String?,
    selected: List<TmdbRef>,
    enabled: Boolean,
    search: suspend (String) -> List<RefCandidate>,
    onSelectedChange: (List<TmdbRef>) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<RefCandidate>>(emptyList()) }
    val atLimit = selected.size >= CUSTOM_DISCOVER_REF_LIMIT

    // Keyed on the query, so a new keystroke cancels the previous effect — both its delay and its
    // request. Without the cancel the last response to arrive wins, which is not the last typed.
    LaunchedEffect(query, enabled) {
        val trimmed = query.trim()
        if (!enabled || trimmed.length < MIN_REF_QUERY_LENGTH) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(REF_SEARCH_DEBOUNCE_MS)
        results = runCatching { search(trimmed) }.getOrDefault(emptyList())
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .alpha(if (enabled) 1f else 0.55f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.Medium,
        )
        if (!description.isNullOrBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }
        Box {
            NuvioTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && !atLimit,
                placeholder = if (atLimit) {
                    stringResource(Res.string.settings_discover_custom_ref_limit, CUSTOM_DISCOVER_REF_LIMIT)
                } else {
                    stringResource(Res.string.settings_discover_custom_ref_placeholder)
                },
            )
            DropdownMenu(
                expanded = results.isNotEmpty(),
                // Dismissing clears the query rather than just the menu: leaving the typed text
                // behind with no results under it looks like the search broke.
                onDismissRequest = { query = "" },
                properties = PopupProperties(focusable = false),
                containerColor = tokens.colors.surfaceCard,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                border = BorderStroke(1.dp, tokens.colors.borderDefault),
                modifier = Modifier.heightIn(max = RefResultsMaxHeight),
            ) {
                results.take(MAX_REF_RESULTS).forEach { candidate ->
                    val alreadyPicked = selected.any { it.id == candidate.ref.id }
                    DropdownMenuItem(
                        text = {
                            Text(
                                // The disambiguated label here, the plain name on the chip — see
                                // [RefCandidate].
                                text = candidate.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (alreadyPicked) tokens.colors.textMuted else tokens.colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        enabled = !alreadyPicked,
                        onClick = {
                            onSelectedChange(selected + candidate.ref)
                            query = ""
                        },
                    )
                }
            }
        }
        if (selected.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                selected.forEach { ref ->
                    InputChip(
                        selected = true,
                        onClick = { onSelectedChange(selected.filterNot { it.id == ref.id }) },
                        label = { Text(ref.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(Res.string.settings_discover_custom_ref_remove),
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}

/** Typing fewer characters than this matches half of TMDB and is never what the user meant. */
private const val MIN_REF_QUERY_LENGTH = 2
private const val REF_SEARCH_DEBOUNCE_MS = 300L
private const val MAX_REF_RESULTS = 8
private val RefResultsMaxHeight = 280.dp

/**
 * A search hit: what gets stored, and what the results list shows.
 *
 * The two differ on purpose. The menu needs a disambiguator — TMDB has many same-named people, and
 * "Directing" beside a name is the difference between the director and an extra who shares it — but
 * the chip and the saved row want the plain name. Storing the decorated string would leave
 * "Christopher Nolan · Directing" sitting in a chip forever, describing how it was found rather
 * than what it is.
 */
private data class RefCandidate(val ref: TmdbRef, val label: String)

private fun TmdbPersonResult.toCandidate(): RefCandidate = RefCandidate(
    ref = TmdbRef(id, name),
    label = knownForDepartment?.takeIf { it.isNotBlank() }?.let { "$name · $it" } ?: name,
)

private fun TmdbCompanyResult.toCandidate(): RefCandidate = RefCandidate(
    ref = TmdbRef(id, name),
    label = originCountry?.takeIf { it.isNotBlank() }?.let { "$name · $it" } ?: name,
)

/** Runtime in minutes, with the bottom stop meaning "no bound" the same way the year rows do. */
@Composable
private fun CustomDiscoverRuntimeRow(
    title: String,
    description: String?,
    minutes: Int,
    anyLabel: String,
    isTablet: Boolean,
    onMinutesChange: (Int) -> Unit,
) {
    val minutesFormat = stringResource(Res.string.settings_discover_custom_runtime_value, minutes)
    SettingsSliderRow(
        title = title,
        description = description,
        value = minutes,
        valueText = if (minutes <= 0) anyLabel else minutesFormat,
        valueTextForValue = { value ->
            if (value <= 0) anyLabel else minutesFormat.replaceFirst("$minutes", "$value")
        },
        valueRange = CUSTOM_DISCOVER_RUNTIME_RANGE,
        step = CUSTOM_DISCOVER_RUNTIME_STEP,
        isTablet = isTablet,
        onValueChange = onMinutesChange,
    )
}

/**
 * Wide enough for the settings rows inside it, which put their control in a fixed-width column on
 * the right and need the label to have somewhere to go.
 */
private val CustomRowDialogWidth = 640.dp
private val CustomRowDialogMaxContentHeight = 560.dp

/**
 * A year slider whose bottom stop means "no bound" rather than the year 1949.
 *
 * The sentinel is stored as 0 and displayed one step below [CUSTOM_DISCOVER_YEAR_RANGE], so "any"
 * is reachable by dragging rather than needing a switch beside every date field.
 */
@Composable
private fun CustomDiscoverYearRow(
    title: String,
    year: Int,
    anyLabel: String,
    isTablet: Boolean,
    onYearChange: (Int) -> Unit,
) {
    val anyValue = CUSTOM_DISCOVER_YEAR_RANGE.first - 1
    val sliderValue = if (year <= 0) anyValue else year.coerceIn(anyValue, CUSTOM_DISCOVER_YEAR_RANGE.last)
    SettingsSliderRow(
        title = title,
        value = sliderValue,
        valueText = if (year <= 0) anyLabel else "$year",
        valueTextForValue = { value -> if (value <= anyValue) anyLabel else "$value" },
        valueRange = anyValue..CUSTOM_DISCOVER_YEAR_RANGE.last,
        step = 1,
        isTablet = isTablet,
        onValueChange = { value -> onYearChange(if (value <= anyValue) 0 else value) },
    )
}

/**
 * Genres as a multi-select dropdown.
 *
 * This was a row of `FilterChip`s, one per genre, and with two dozen genres that is a wall of
 * bubbles occupying more of the dialog than every other control combined. The dropdown holds the
 * same choices in one row's worth of space and reads as a filter rather than as a decision the user
 * has to finish before moving on.
 */
@Composable
private fun CustomDiscoverGenrePicker(
    row: CustomDiscoverRow,
    isTablet: Boolean,
    onUpdate: ((CustomDiscoverRow) -> CustomDiscoverRow) -> Unit,
) {
    val genreCountFormat = stringResource(Res.string.settings_discover_custom_genres_count, row.genres.size)
    SettingsMultiSelectRow(
        title = stringResource(Res.string.settings_discover_custom_genres),
        description = stringResource(Res.string.settings_discover_custom_genres_description),
        options = remember { DiscoverGenreNames.map { SettingsChoiceOption(it, it) } },
        selectedValues = row.genres,
        emptyLabel = stringResource(Res.string.settings_discover_custom_genres_any),
        summaryForCount = { count -> genreCountFormat.replaceFirst("${row.genres.size}", "$count") },
        isTablet = isTablet,
        onToggle = { genre ->
            onUpdate { current ->
                val genres = current.genres.toMutableSet()
                if (!genres.add(genre)) genres.remove(genre)
                // One genre cannot be ANDed with anything, and leaving the switch on while it has
                // no effect invites the user to conclude it does nothing.
                current.copy(
                    genres = genres,
                    matchAllGenres = current.matchAllGenres && genres.size > 1,
                )
            }
        },
    )
}

/**
 * Import — plan §6.1.
 *
 * **A file with a query becomes a real custom row, not a frozen list.** The items in a file were
 * already stale when it was written; the query that produced them is not, so recreating the
 * question gives a row that refreshes like any other. Only a file with no query to recreate —
 * anything generated from watch history — is stored as a frozen list, because for those a frozen
 * list is the only honest representation.
 *
 * The outcome is reported in the row's own description rather than a dialog: every failure here is
 * something about the file the user picked, and they are still looking at the control they picked
 * it with.
 */
@OptIn(ExperimentalUuidApi::class)
@Composable
private fun ImportDiscoverRowButton(
    isTablet: Boolean,
    settings: HomeCatalogSettingsUiState,
    onCustomRowImported: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    val atLimit = settings.discoverImportedRows.size >= IMPORTED_DISCOVER_ROW_LIMIT
    val limitMessage = stringResource(
        Res.string.settings_discover_import_row_limit,
        IMPORTED_DISCOVER_ROW_LIMIT,
    )
    val unreadable = stringResource(Res.string.settings_discover_import_error_unreadable)
    val wrongFormat = stringResource(Res.string.settings_discover_import_error_wrong_format)
    val newer = stringResource(Res.string.settings_discover_import_error_newer)
    val empty = stringResource(Res.string.settings_discover_import_error_empty)

    val importDescription = stringResource(Res.string.settings_discover_import_row_description)
    SettingsNavigationRow(
        title = stringResource(Res.string.settings_discover_import_row),
        // Says what the row does until an import has something to report, then gets out of the way
        // for the result or the error.
        description = status.ifBlank { importDescription },
        isTablet = isTablet,
        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("discover-import-row")),
        onClick = {
            scope.launch {
                val raw = runCatching { DiscoverCatalogFilePicker.openForImport() }.getOrNull()
                    ?: return@launch
                val document = parseDiscoverCatalog(raw).getOrElse { error ->
                    status = when ((error as? DiscoverCatalogImportException)?.error) {
                        is DiscoverCatalogImportError.NotADiscoverCatalog -> wrongFormat
                        is DiscoverCatalogImportError.UnsupportedVersion -> newer
                        DiscoverCatalogImportError.Empty -> empty
                        else -> unreadable
                    }
                    return@launch
                }
                val id = Uuid.random().toString()
                val asCustomRow = document.toCustomRow(id)
                if (asCustomRow != null) {
                    HomeCatalogSettingsRepository.addDiscoverCustomRow(id) ?: return@launch
                    HomeCatalogSettingsRepository.updateDiscoverCustomRow(asCustomRow)
                    status = getString(Res.string.settings_discover_import_ok_query, document.name)
                    onCustomRowImported(id)
                    return@launch
                }
                if (atLimit) {
                    status = limitMessage
                    return@launch
                }
                val stored = document.toImportedRow(id)
                status = if (HomeCatalogSettingsRepository.addDiscoverImportedRow(stored)) {
                    getString(
                        Res.string.settings_discover_import_ok_list,
                        document.name,
                        stored.items.size,
                    )
                } else {
                    limitMessage
                }
            }
        },
    )
}

/**
 * Where a built row can go — one entry, then a choice of destination.
 *
 * These were five rows on the settings page: export to file, publish to MDBList, copy for BingeCat,
 * save as Collection, export for AIOMetadata. Five permanent rows for a thing most users do rarely
 * (and several of which are inert without an account) buried the two rows people actually came for.
 * One entry that asks "where?" costs one extra click and gives every destination room to say what
 * it is.
 *
 * Each destination is rendered by its own flow composable, which owns its dialogs and nothing else —
 * the entry row and the status line live here, so only one dialog is ever on screen at a time.
 * Nesting one [NuvioModalDialog] inside another would mean two real platform windows fighting over
 * focus, which is the failure this shape avoids.
 *
 * A flow stays mounted until it calls `onClosed`, **including while its request is in flight**:
 * unmounting it would cancel the `rememberCoroutineScope` its own publish or file write is running
 * on.
 */
@Composable
private fun DiscoverExportButton(
    isTablet: Boolean,
    settings: HomeCatalogSettingsUiState,
) {
    val discover by DiscoverRecommendationsRepository.uiState.collectAsStateWithLifecycle()
    val mdbList by MdbListSettingsRepository.uiState.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<DiscoverExportTarget?>(null) }
    var status by remember { mutableStateOf("") }

    val hasBuiltRows = discover.rows.isNotEmpty()
    val hasCustomRows = settings.discoverCustomRows.isNotEmpty()
    // trackingEnabled, not enabled — publishing writes to their account, and the two consents are
    // deliberately separate (see MdbListSettings).
    val mdbListConnected = mdbList.trackingEnabled && mdbList.apiKey.isNotBlank()

    val nothingBuilt = stringResource(Res.string.settings_discover_export_row_none)
    val noCustomRows = stringResource(Res.string.settings_discover_collection_none)
    val notConnected = stringResource(Res.string.settings_discover_mdblist_not_connected)

    SettingsNavigationRow(
        title = stringResource(Res.string.settings_discover_export),
        description = status.ifBlank {
            if (hasBuiltRows || hasCustomRows) {
                stringResource(Res.string.settings_discover_export_description)
            } else {
                nothingBuilt
            }
        },
        isTablet = isTablet,
        enabled = hasBuiltRows || hasCustomRows,
        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("discover-export-row")),
        onClick = {
            status = ""
            menuOpen = true
        },
    )

    if (menuOpen) {
        NuvioModalDialog(
            onDismissRequest = { menuOpen = false },
            title = stringResource(Res.string.settings_discover_export_pick_title),
            subtitle = stringResource(Res.string.settings_discover_export_pick_subtitle),
            maxWidth = CustomRowDialogWidth,
            modifier = Modifier.width(CustomRowDialogWidth),
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // Each destination states its own precondition rather than vanishing when unmet:
                // "connect MDBList first" is an answer, and a missing row is a mystery.
                DiscoverExportTargetRow(
                    title = stringResource(Res.string.settings_discover_export_row),
                    description = if (hasBuiltRows) "" else nothingBuilt,
                    enabled = hasBuiltRows,
                    isTablet = isTablet,
                    onClick = { menuOpen = false; target = DiscoverExportTarget.File },
                )
                SettingsGroupDivider(isTablet = isTablet)
                DiscoverExportTargetRow(
                    title = stringResource(Res.string.settings_discover_mdblist_publish),
                    description = when {
                        !mdbListConnected -> notConnected
                        !hasBuiltRows -> nothingBuilt
                        else -> stringResource(Res.string.settings_discover_mdblist_publish_description)
                    },
                    enabled = mdbListConnected && hasBuiltRows,
                    isTablet = isTablet,
                    onClick = { menuOpen = false; target = DiscoverExportTarget.MdbList },
                )
                SettingsGroupDivider(isTablet = isTablet)
                DiscoverExportTargetRow(
                    title = stringResource(Res.string.settings_discover_bingecat_copy),
                    description = if (hasBuiltRows) {
                        stringResource(Res.string.settings_discover_bingecat_copy_description)
                    } else {
                        nothingBuilt
                    },
                    enabled = hasBuiltRows,
                    isTablet = isTablet,
                    onClick = { menuOpen = false; target = DiscoverExportTarget.BingeCat },
                )
                SettingsGroupDivider(isTablet = isTablet)
                DiscoverExportTargetRow(
                    title = stringResource(Res.string.settings_discover_collection_save),
                    description = if (hasCustomRows) {
                        stringResource(Res.string.settings_discover_collection_save_description)
                    } else {
                        noCustomRows
                    },
                    enabled = hasCustomRows,
                    isTablet = isTablet,
                    onClick = { menuOpen = false; target = DiscoverExportTarget.Collection },
                )
                SettingsGroupDivider(isTablet = isTablet)
                DiscoverExportTargetRow(
                    title = stringResource(Res.string.settings_discover_aiometadata_export),
                    description = if (hasCustomRows) {
                        stringResource(Res.string.settings_discover_aiometadata_export_description)
                    } else {
                        noCustomRows
                    },
                    enabled = hasCustomRows,
                    isTablet = isTablet,
                    onClick = { menuOpen = false; target = DiscoverExportTarget.AioMetadata },
                )
            }
        }
    }

    val onStatus: (String) -> Unit = { status = it }
    val onClosed: () -> Unit = { target = null }
    when (target) {
        null -> Unit
        DiscoverExportTarget.File ->
            ExportDiscoverRowFlow(isTablet = isTablet, onStatus = onStatus, onClosed = onClosed)

        DiscoverExportTarget.MdbList ->
            PublishRowToMdbListFlow(isTablet = isTablet, onStatus = onStatus, onClosed = onClosed)

        DiscoverExportTarget.BingeCat ->
            CopyRowForBingeCatFlow(isTablet = isTablet, onStatus = onStatus, onClosed = onClosed)

        DiscoverExportTarget.Collection -> SaveDiscoverRowAsCollectionFlow(
            isTablet = isTablet,
            settings = settings,
            export = false,
            onStatus = onStatus,
            onClosed = onClosed,
        )

        DiscoverExportTarget.AioMetadata -> SaveDiscoverRowAsCollectionFlow(
            isTablet = isTablet,
            settings = settings,
            export = true,
            onStatus = onStatus,
            onClosed = onClosed,
        )
    }
}

/** Where a row is being sent. Null while the menu is closed. */
private enum class DiscoverExportTarget { File, MdbList, BingeCat, Collection, AioMetadata }

/** One destination in the export menu. A row that cannot run says why instead of disappearing. */
@Composable
private fun DiscoverExportTargetRow(
    title: String,
    description: String,
    enabled: Boolean,
    isTablet: Boolean,
    onClick: () -> Unit,
) {
    SettingsNavigationRow(
        title = title,
        description = description,
        isTablet = isTablet,
        enabled = enabled,
        onClick = onClick,
    )
}

/**
 * Export — plan §6.1.
 *
 * Lists what Discover **currently has built** rather than what the settings page can configure,
 * because those are different sets: "Because you watched X" and "Trending in Horror" are several
 * rows each, and their names depend on what the taste profile picked this build. Exporting from the
 * built rows is also the only way a generated row can be exported at all — it has no settings entry
 * of its own to hang the action on.
 */
@Composable
private fun ExportDiscoverRowFlow(
    isTablet: Boolean,
    onStatus: (String) -> Unit,
    onClosed: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val discover by DiscoverRecommendationsRepository.uiState.collectAsStateWithLifecycle()
    val settings by HomeCatalogSettingsRepository.uiState.collectAsStateWithLifecycle()
    // Open from the first frame: the destination menu already asked the only question that gates
    // this one. `false` means the work is running with no dialog up — the flow stays mounted so its
    // coroutine scope survives, and [onClosed] is what finally unmounts it.
    var open by remember { mutableStateOf(true) }
    val failed = stringResource(Res.string.settings_discover_export_failed)

    if (!open) return
    NuvioModalDialog(
        onDismissRequest = { open = false; onClosed() },
        title = stringResource(Res.string.settings_discover_export_dialog_title),
        subtitle = stringResource(Res.string.settings_discover_export_dialog_subtitle),
        maxWidth = CustomRowDialogWidth,
        modifier = Modifier.width(CustomRowDialogWidth),
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            discover.rows.forEachIndexed { index, row ->
                if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = row.title,
                    description = stringResource(
                        Res.string.settings_discover_export_item_count,
                        row.items.size,
                    ),
                    isTablet = isTablet,
                    onClick = {
                        open = false
                        scope.launch {
                            // The query travels with the file when the row has one; a generated row
                            // has none and exports as the list it is.
                            val customRow = customDiscoverRowId(row.entryId)?.let { id ->
                                settings.discoverCustomRows.firstOrNull { it.id == id }
                            }
                            val document = row.toCatalogDocument(
                                generatedAt = discoverExportTimestamp(),
                                customRow = customRow,
                            )
                            val saved = runCatching {
                                DiscoverCatalogFilePicker.saveExport(
                                    suggestedName = discoverCatalogFileName(row.title),
                                    contents = document.encodeToString(),
                                )
                            }.getOrNull()
                            onStatus(
                                if (saved != null) {
                                    getString(Res.string.settings_discover_export_ok, saved)
                                } else {
                                    failed
                                },
                            )
                            onClosed()
                        }
                    },
                )
            }
        }
    }
}

/**
 * Publish to MDBList — plan §6.2 / §11.
 *
 * The only export that gets a *frozen* row onto a service. AIOMetadata and TMDB Discover+ have no
 * catalog kind that holds a list of titles, so a generated row — recommendations, trending, hidden
 * gems — can only become a catalog by way of an MDBList static list, which both of them then read
 * as an ordinary `mdblist.*` catalog.
 *
 * Two steps rather than one, because **the free tier allows four static lists**. A one-click
 * "publish" that created a list each time would exhaust the account in an afternoon, so choosing an
 * existing list is the default path and creating one is a deliberate act at the bottom of the list.
 */
@Composable
private fun PublishRowToMdbListFlow(
    isTablet: Boolean,
    onStatus: (String) -> Unit,
    onClosed: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val discover by DiscoverRecommendationsRepository.uiState.collectAsStateWithLifecycle()
    var pickedRow by remember { mutableStateOf<DiscoverRecommendationRow?>(null) }
    var rowPickerOpen by remember { mutableStateOf(true) }
    var lists by remember { mutableStateOf<List<MdbListUserList>?>(null) }
    var busy by remember { mutableStateOf(false) }

    val working = stringResource(Res.string.settings_discover_mdblist_working)
    val noneIdentified = stringResource(Res.string.settings_discover_mdblist_none_identified)

    if (rowPickerOpen) {
        NuvioModalDialog(
            onDismissRequest = { rowPickerOpen = false; onClosed() },
            title = stringResource(Res.string.settings_discover_mdblist_pick_row_title),
            subtitle = stringResource(Res.string.settings_discover_mdblist_pick_row_subtitle),
            maxWidth = CustomRowDialogWidth,
            modifier = Modifier.width(CustomRowDialogWidth),
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                discover.rows.forEachIndexed { index, row ->
                    if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = row.title,
                        description = stringResource(
                            Res.string.settings_discover_export_item_count,
                            row.items.size,
                        ),
                        isTablet = isTablet,
                        onClick = {
                            rowPickerOpen = false
                            // Identify before asking which list: a row that resolves to nothing has
                            // no question worth putting to the user, and finding that out after
                            // they have chosen a list is the wrong order.
                            if (row.items.mapNotNull { mdbListItemRef(it.id, it.type) }.isEmpty()) {
                                onStatus(noneIdentified)
                                onClosed()
                                return@SettingsNavigationRow
                            }
                            pickedRow = row
                            lists = null
                            scope.launch {
                                lists = runCatching { MdbListStaticListRepository.fetchUserLists() }
                                    .getOrElse { error ->
                                        onStatus(mdbListPublishMessage(error))
                                        pickedRow = null
                                        onClosed()
                                        return@launch
                                    }
                                    .filter { it.acceptsItems }
                            }
                        },
                    )
                }
            }
        }
    }

    val target = pickedRow ?: return
    val refs = target.items.mapNotNull { mdbListItemRef(it.id, it.type) }
    val skipped = target.items.size - refs.size

    NuvioModalDialog(
        onDismissRequest = { if (!busy) { pickedRow = null; onClosed() } },
        title = stringResource(Res.string.settings_discover_mdblist_pick_list_title),
        subtitle = stringResource(
            Res.string.settings_discover_mdblist_pick_list_subtitle,
            refs.size,
            target.title,
        ),
        maxWidth = CustomRowDialogWidth,
        modifier = Modifier.width(CustomRowDialogWidth),
    ) {
        val loaded = lists
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            if (loaded == null) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_discover_mdblist_loading_lists),
                    description = "",
                    isTablet = isTablet,
                    enabled = false,
                    onClick = {},
                )
                return@Column
            }

            if (loaded.isEmpty()) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_discover_mdblist_no_static_lists),
                    description = "",
                    isTablet = isTablet,
                    enabled = false,
                    onClick = {},
                )
            }

            loaded.forEach { list ->
                SettingsNavigationRow(
                    title = list.name,
                    description = stringResource(
                        Res.string.settings_discover_mdblist_list_items,
                        list.itemCount,
                    ),
                    isTablet = isTablet,
                    enabled = !busy,
                    onClick = {
                        busy = true
                        onStatus(working)
                        // The dialog closes now and the flow stays mounted with nothing rendered,
                        // so the publish below keeps its coroutine scope. [onClosed] is what
                        // unmounts it, and it is called only once the request has answered.
                        pickedRow = null
                        scope.launch {
                            onStatus(publishToMdbList(refs, skipped, list.id, list.name))
                            busy = false
                            onClosed()
                        }
                    },
                )
                SettingsGroupDivider(isTablet = isTablet)
            }

            SettingsNavigationRow(
                title = stringResource(Res.string.settings_discover_mdblist_create_new),
                description = stringResource(Res.string.settings_discover_mdblist_create_new_description),
                isTablet = isTablet,
                enabled = !busy,
                onClick = {
                    busy = true
                    onStatus(working)
                    val name = target.title
                    pickedRow = null
                    scope.launch {
                        onStatus(
                            runCatching {
                                MdbListStaticListRepository.createStaticList(name)
                            }.fold(
                                onSuccess = { id -> publishToMdbList(refs, skipped, id, name) },
                                onFailure = { error -> mdbListPublishMessage(error) },
                            ),
                        )
                        busy = false
                        onClosed()
                    }
                },
            )
        }
    }
}

/** Adds [refs] to [listId] and turns whatever came back into one line of user-facing truth. */
private suspend fun publishToMdbList(
    refs: List<MdbListItemRef>,
    skipped: Int,
    listId: Long,
    listName: String,
): String {
    val outcome = runCatching { MdbListStaticListRepository.addItems(listId, refs) }
        .getOrElse { error -> return mdbListPublishMessage(error) }

    // "Already on it" is not a failure but it is not success either — a user who publishes twice
    // and is told "added 0" needs to know the list is right rather than the publish broken.
    val base = if (outcome.existing > 0) {
        getString(
            Res.string.settings_discover_mdblist_ok_existing,
            outcome.added,
            listName,
            outcome.existing,
        )
    } else {
        getString(Res.string.settings_discover_mdblist_ok, outcome.added, listName)
    }

    // not_found is MDBList failing to match an id we did send; skipped is an id we could not send at
    // all. Both are titles missing from the list, so they are reported together.
    val missing = skipped + outcome.notFound
    return if (missing > 0) {
        base + " " + getString(Res.string.settings_discover_mdblist_skipped, missing)
    } else {
        base
    }
}

private suspend fun mdbListPublishMessage(error: Throwable): String =
    when (val reason = (error as? MdbListPublishException)?.error) {
        is MdbListPublishError.QuotaReached ->
            getString(Res.string.settings_discover_mdblist_quota, reason.limit)

        is MdbListPublishError.Forbidden ->
            getString(Res.string.settings_discover_mdblist_forbidden, reason.detail)

        is MdbListPublishError.Http ->
            getString(Res.string.settings_discover_mdblist_failed, reason.status, reason.detail)

        is MdbListPublishError.Unreadable, null ->
            getString(Res.string.settings_discover_mdblist_unreadable)

        MdbListPublishError.NotConnected ->
            getString(Res.string.settings_discover_mdblist_not_connected)
    }

/**
 * Copy for BingeCat — plan §6.2 / §11.
 *
 * The cheapest of the three exports: no key, no network, no account, no file. BingeCat's Bulk Add
 * takes `tmdb:550` and `tt0133093`, which is the form Discover rows already store, so this is the
 * row's ids joined and put on the clipboard.
 *
 * A clipboard action rather than a file because the destination is a textarea on a web page, and
 * because it puts the paste in front of the user before they commit it — the one review step the
 * other two exports do not get.
 */
@Composable
private fun CopyRowForBingeCatFlow(
    isTablet: Boolean,
    onStatus: (String) -> Unit,
    onClosed: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val discover by DiscoverRecommendationsRepository.uiState.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(true) }
    val nothingToCopy = stringResource(Res.string.settings_discover_bingecat_none)

    if (!open) return
    NuvioModalDialog(
        onDismissRequest = { open = false; onClosed() },
        title = stringResource(Res.string.settings_discover_bingecat_dialog_title),
        subtitle = stringResource(Res.string.settings_discover_bingecat_dialog_subtitle),
        maxWidth = CustomRowDialogWidth,
        modifier = Modifier.width(CustomRowDialogWidth),
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            discover.rows.forEachIndexed { index, row ->
                if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = row.title,
                    description = stringResource(
                        Res.string.settings_discover_export_item_count,
                        row.items.size,
                    ),
                    isTablet = isTablet,
                    onClick = {
                        open = false
                        val list = bingeCatBulkList(row.items.map { it.id })
                        if (list.included == 0) {
                            onStatus(nothingToCopy)
                            onClosed()
                            return@SettingsNavigationRow
                        }
                        clipboardManager.setText(AnnotatedString(list.text))
                        scope.launch {
                            onStatus(
                                if (list.skipped > 0) {
                                    getString(
                                        Res.string.settings_discover_bingecat_ok_skipped,
                                        list.included,
                                        row.title,
                                        list.skipped,
                                    )
                                } else {
                                    getString(
                                        Res.string.settings_discover_bingecat_ok,
                                        list.included,
                                        row.title,
                                    )
                                },
                            )
                            onClosed()
                        }
                    },
                )
            }
        }
    }
}

/**
 * The editor for an imported list — plan §14.
 *
 * An imported list has no query, so it gets no query editor; what it has is a name and a set of
 * titles, and those are what this edits. That is the line the [ImportedDiscoverRow] /
 * [CustomDiscoverRow] split draws: a frozen list is not a lesser custom row, it is a different
 * thing, and the honest editor for it is the one that matches what it holds. A file that *does*
 * carry a query never arrives here — it becomes a live custom row on import and gets the full
 * editor.
 *
 * Edits save as they are made, like the custom row dialog, so there is no draft state to lose and
 * no save button that could be missed on the way out.
 */
@Composable
private fun ImportedDiscoverRowDialog(
    row: ImportedDiscoverRow,
    isTablet: Boolean,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    fun update(transform: (ImportedDiscoverRow) -> ImportedDiscoverRow) {
        HomeCatalogSettingsRepository.updateDiscoverImportedRow(transform(row))
    }

    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = row.title.ifBlank { stringResource(Res.string.settings_discover_imported_row) },
        subtitle = stringResource(Res.string.settings_discover_imported_edit_subtitle, row.items.size),
        maxWidth = CustomRowDialogWidth,
        modifier = Modifier.width(CustomRowDialogWidth),
        actions = {
            TextButton(
                onClick = {
                    // Dismiss first, for the same reason the custom row dialog does: the row is
                    // about to stop existing and this dialog reads from the list holding it.
                    onDismiss()
                    HomeCatalogSettingsRepository.removeDiscoverImportedRow(row.id)
                },
                colors = ButtonDefaults.textButtonColors(contentColor = tokens.colors.danger),
            ) { Text(stringResource(Res.string.settings_discover_imported_remove)) }
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_done)) }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = CustomRowDialogMaxContentHeight)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingsTextRow(
                title = stringResource(Res.string.settings_discover_custom_name),
                description = null,
                value = row.title,
                isTablet = isTablet,
                placeholder = stringResource(Res.string.settings_discover_imported_row),
                onValueChange = { title -> update { it.copy(title = title) } },
            )

            if (row.items.isEmpty()) {
                Text(
                    text = stringResource(Res.string.settings_discover_imported_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                return@Column
            }

            row.items.forEachIndexed { index, item ->
                if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                ImportedTitleLine(
                    name = item.name.ifBlank { item.id },
                    year = item.releaseInfo,
                    isTablet = isTablet,
                    onRemove = { update { current -> current.withoutItemAt(index) } },
                )
            }
        }
    }
}

/**
 * One title inside the imported-list editor.
 *
 * Deliberately not [DiscoverRowShell], which prefixes its subtitle with "Visible"/"Hidden" — that
 * is the vocabulary of row management, and a title inside a list is neither.
 */
@Composable
private fun ImportedTitleLine(
    name: String,
    year: String?,
    isTablet: Boolean,
    onRemove: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (isTablet) 20.dp else 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!year.isNullOrBlank()) {
                Text(
                    text = year,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(Res.string.settings_discover_imported_remove_title),
                tint = tokens.colors.textMuted,
            )
        }
    }
}

/**
 * Save as Collection (§6.3) and the AIOMetadata export (§6.2) — plan §17.
 *
 * One row picker, one conversion, two destinations: the collection either lands on Home or goes to
 * a file. They are the same artefact, which is what the §6.2 spike established — AIOMetadata's
 * importer reads Nuvio's own collections JSON and rebuilds the catalog from the native TMDB source
 * inside it.
 *
 * **Custom rows only.** A generated row has no query to hand over, and a TMDB discover source is
 * nothing but a query. Frozen rows have their own two exports already (MDBList and BingeCat), so
 * offering them here would produce a collection that resolves to whatever the *empty* query returns
 * rather than what the row contained.
 */
@Composable
private fun SaveDiscoverRowAsCollectionFlow(
    isTablet: Boolean,
    settings: HomeCatalogSettingsUiState,
    export: Boolean,
    onStatus: (String) -> Unit,
    onClosed: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(true) }
    val rows = settings.discoverCustomRows
    val failed = stringResource(Res.string.settings_discover_export_failed)
    val movieSuffix = stringResource(Res.string.collections_editor_media_movies_suffix)
    val seriesSuffix = stringResource(Res.string.collections_editor_media_series_suffix)

    if (!open) return
    NuvioModalDialog(
        onDismissRequest = { open = false; onClosed() },
        title = stringResource(
            if (export) {
                Res.string.settings_discover_aiometadata_export
            } else {
                Res.string.settings_discover_collection_save
            },
        ),
        subtitle = stringResource(Res.string.settings_discover_collection_dialog_subtitle),
        maxWidth = CustomRowDialogWidth,
        modifier = Modifier.width(CustomRowDialogWidth),
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            rows.forEachIndexed { index, row ->
                if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = row.title.ifBlank { stringResource(Res.string.settings_discover_row_custom) },
                    description = row.genres.sorted().joinToString(", ")
                        .ifBlank { stringResource(Res.string.settings_discover_custom_genres_any) },
                    isTablet = isTablet,
                    onClick = {
                        open = false
                        scope.launch {
                            onStatus(
                                saveOrExportDiscoverRow(
                                    row = row,
                                    export = export,
                                    movieSuffix = movieSuffix,
                                    seriesSuffix = seriesSuffix,
                                    failedMessage = failed,
                                ),
                            )
                            onClosed()
                        }
                    },
                )
            }
        }
    }
}

/**
 * Converts [row], then either adds the collection or writes it to a file.
 *
 * Genre ids are resolved here rather than inside the conversion because the same genre name has
 * different ids in TMDB's film and television namespaces, and finding out costs a request.
 */
@OptIn(ExperimentalUuidApi::class)
private suspend fun saveOrExportDiscoverRow(
    row: CustomDiscoverRow,
    export: Boolean,
    movieSuffix: String,
    seriesSuffix: String,
    failedMessage: String,
): String {
    val genreIds = runCatching { DiscoverSeedService.resolveGenreIdsByType(row.genres) }
        .getOrDefault(emptyMap())
        .mapValues { (_, ids) -> ids.toList() }
    val converted = row.toCollectionSources(
        genreIdsByType = genreIds,
        movieSuffix = movieSuffix,
        seriesSuffix = seriesSuffix,
    )
    val title = row.title.ifBlank { getString(Res.string.settings_discover_row_custom) }
    val collection = discoverRowCollection(
        title = title,
        sources = converted.sources,
        collectionId = Uuid.random().toString(),
        folderId = Uuid.random().toString(),
    )

    val outcome = if (export) {
        val saved = runCatching {
            DiscoverCatalogFilePicker.saveExport(
                suggestedName = discoverCollectionFileName(title),
                contents = encodeCollectionsExport(collection),
            )
        }.getOrNull() ?: return failedMessage
        getString(Res.string.settings_discover_aiometadata_export_ok, saved)
    } else {
        CollectionRepository.addCollection(collection)
        getString(Res.string.settings_discover_collection_save_ok, title)
    }

    // The dropped filters are named rather than counted: "status" and "certification" need
    // different things done about them, and the exported query is *broader* without each.
    val dropped = converted.droppedFilters
    if (dropped.isEmpty()) return outcome
    // map is inline and joinToString's transform is not, so the suspend call goes in the map.
    val names = dropped.map { droppedFilterName(it) }.joinToString(", ")
    return outcome + " " + getString(Res.string.settings_discover_collection_dropped, names)
}

private suspend fun droppedFilterName(filter: DroppedDiscoverFilter): String = when (filter) {
    DroppedDiscoverFilter.Status -> getString(Res.string.settings_discover_custom_status)
    DroppedDiscoverFilter.Certification -> getString(Res.string.settings_discover_custom_certification)
    DroppedDiscoverFilter.PeopleOnSeries -> getString(Res.string.settings_discover_collection_dropped_people)
}

@OptIn(ExperimentalUuidApi::class)
@Composable
private fun AddCustomDiscoverRowButton(
    isTablet: Boolean,
    settings: HomeCatalogSettingsUiState,
    onRowAdded: (String) -> Unit,
) {
    val atLimit = settings.discoverCustomRows.size >= CUSTOM_DISCOVER_ROW_LIMIT
    SettingsNavigationRow(
        title = stringResource(Res.string.settings_discover_add_custom_row),
        // Carries its subtext like every other row in the section. Left bare it was the odd one
        // out: a lone title in a list of title-plus-description rows reads as unfinished rather
        // than as deliberately terse. The limit message displaces it, since a row that will refuse
        // the click has something more useful to say.
        description = if (atLimit) {
            stringResource(Res.string.settings_discover_custom_row_limit, CUSTOM_DISCOVER_ROW_LIMIT)
        } else {
            stringResource(Res.string.settings_discover_add_custom_row_description)
        },
        isTablet = isTablet,
        enabled = !atLimit,
        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("discover-custom-rows")),
        onClick = {
            HomeCatalogSettingsRepository.addDiscoverCustomRow(Uuid.random().toString())
                ?.let { added -> onRowAdded(added.id) }
        },
    )
}
