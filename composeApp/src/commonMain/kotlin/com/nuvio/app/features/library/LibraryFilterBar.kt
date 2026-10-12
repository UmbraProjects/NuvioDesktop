package com.nuvio.app.features.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.FilterAltOff
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.nuvio.app.features.discover.BrowseFilterCaption
import com.nuvio.app.features.discover.BrowseFilterMenu
import com.nuvio.app.features.discover.BrowseFilterSeparator
import com.nuvio.app.features.discover.BrowseFilterStrip
import com.nuvio.app.features.discover.BrowseFilterIconAction
import com.nuvio.app.features.discover.BrowseFilterTitle
import com.nuvio.app.features.discover.BrowseLanguages
import com.nuvio.app.features.discover.BrowseMenuCheck
import com.nuvio.app.features.discover.BrowseMenuCheckColumns
import com.nuvio.app.features.discover.BrowseMenuChoices
import com.nuvio.app.features.discover.BrowseMenuNote
import com.nuvio.app.features.discover.BrowseRatings
import com.nuvio.app.features.discover.BrowseRuntimes
import com.nuvio.app.features.discover.BrowseStudioPicker
import com.nuvio.app.features.discover.BrowseVotes
import com.nuvio.app.features.discover.BrowseYearsEditor
import com.nuvio.app.features.discover.TmdbBrowse
import com.nuvio.app.features.discover.TmdbBrowseCompany
import com.nuvio.app.features.discover.TmdbBrowseGenre
import com.nuvio.app.features.discover.TmdbBrowseMedia
import com.nuvio.app.features.discover.TmdbBrowseNetworks
import com.nuvio.app.features.discover.TmdbBrowseStatus
import com.nuvio.app.features.discover.browseFilterSummary
import com.nuvio.app.features.discover.browseRatingLabel
import com.nuvio.app.features.discover.browseRuntimeLabel
import com.nuvio.app.features.discover.browseYearLabel
import com.nuvio.app.features.discover.formatRating

private val WatchChoices = listOf(
    LibraryWatchFilter.All to "Everything",
    LibraryWatchFilter.Unwatched to "Not started",
    LibraryWatchFilter.InProgress to "In progress",
    LibraryWatchFilter.Watched to "Watched",
)

private val LibraryStatusChoices = listOf(
    TmdbBrowseStatus.Any to "Any status",
    TmdbBrowseStatus.Returning to "Still running",
    TmdbBrowseStatus.Ended to "Ended",
    TmdbBrowseStatus.HomeRelease to "Released",
    TmdbBrowseStatus.Upcoming to "Coming soon",
)

/**
 * The Library's filter bar (the tracker's library page): sort and where you are with a title, then
 * the filters TMDB's details can answer. Drawn in place of a library row's title, which it keeps as
 * its first element — so in TV Mode, where one row shows at a time, the bar is always on screen.
 */
@Composable
fun LibraryFilterBar(
    rowTitle: String?,
    settings: LibraryDisplaySettingsUiState,
    sourceMode: LibrarySourceMode,
    shownCount: Int,
    factsLoading: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filters = settings.filters
    val update = LibraryDisplaySettingsRepository::updateFilters
    val tmdbAvailable = TmdbBrowse.isAvailable
    var genres by remember { mutableStateOf<List<TmdbBrowseGenre>>(emptyList()) }
    LaunchedEffect(tmdbAvailable) {
        if (tmdbAvailable) genres = runCatching { TmdbBrowse.genres(TmdbBrowseMedia.All) }.getOrDefault(emptyList())
    }

    BrowseFilterStrip(modifier = modifier) {
        rowTitle?.takeIf(String::isNotBlank)?.let { BrowseFilterTitle(it) }
        val sort = effectiveLibrarySortOption(settings.sortOption, sourceMode)
        val sortChoices = LibrarySortEntries.filter { (option, _) -> tmdbAvailable || !option.needsFacts() }
        BrowseFilterMenu(
            icon = Icons.AutoMirrored.Rounded.Sort,
            label = "Sort: ${sortChoices.firstOrNull { it.first == sort }?.second ?: "Default order"}",
            on = false,
            onOpenChange = onMenuOpenChange,
        ) { close ->
            BrowseMenuChoices(
                options = sortChoices.map { it.first.name to it.second },
                value = sort.name,
                onPick = { picked ->
                    LibraryDisplaySettingsRepository.setSortOption(LibrarySortOption.valueOf(picked))
                    close()
                },
            )
        }
        BrowseFilterMenu(
            icon = Icons.Rounded.Visibility,
            label = "Show: ${WatchChoices.first { it.first == filters.watch }.second}",
            on = filters.watch != LibraryWatchFilter.All,
            onOpenChange = onMenuOpenChange,
        ) { close ->
            BrowseMenuChoices(
                options = WatchChoices.map { it.first.name to it.second },
                value = filters.watch.name,
                onPick = { picked ->
                    update { it.copy(watch = LibraryWatchFilter.valueOf(picked)) }
                    close()
                },
            )
        }
        if (!tmdbAvailable) {
            BrowseFilterCaption("Add a TMDB API key in Settings to filter by genre, studio, year and more")
        } else {
            BrowseFilterSeparator()

            BrowseFilterMenu(icon = Icons.Rounded.TheaterComedy, label = browseFilterSummary("Genres", filters.genres), on = filters.genres.isNotEmpty(), onOpenChange = onMenuOpenChange) {
                BrowseMenuCheck(on = filters.allGenres, onClick = { update { it.copy(allGenres = !it.allGenres) } }) {
                    Text("Must match every genre picked")
                }
                BrowseMenuCheckColumns(
                    items = genres,
                    isOn = { it.name in filters.genres },
                    onToggle = { g -> update { it.copy(genres = if (g.name in it.genres) it.genres - g.name else it.genres + g.name) } },
                    label = { it.name },
                )
            }

            val networkNames = filters.networks.mapNotNull { id -> TmdbBrowseNetworks.firstOrNull { it.id == id }?.name }
            BrowseFilterMenu(icon = Icons.Rounded.Tv, label = browseFilterSummary("Network", networkNames), on = filters.networks.isNotEmpty(), onOpenChange = onMenuOpenChange) {
                BrowseMenuCheckColumns(
                    items = TmdbBrowseNetworks,
                    isOn = { it.id in filters.networks },
                    onToggle = { n -> update { it.copy(networks = if (n.id in it.networks) it.networks - n.id else it.networks + n.id) } },
                    label = { it.name },
                )
            }

            BrowseFilterMenu(icon = Icons.Rounded.Business, label = browseFilterSummary("Studio", filters.companies.map { it.name }), on = filters.companies.isNotEmpty(), onOpenChange = onMenuOpenChange) {
                filters.companies.forEach { company ->
                    BrowseMenuCheck(on = true, onClick = { update { it.copy(companies = it.companies.filterNot { c -> c.id == company.id }) } }) {
                        Text(company.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                BrowseStudioPicker(onPick = { studio ->
                    update { it.copy(companies = (it.companies + TmdbBrowseCompany(studio.id, studio.name)).distinctBy { c -> c.id }.take(12)) }
                })
            }

            BrowseFilterMenu(icon = Icons.Rounded.Event, label = browseYearLabel(filters.yearFrom, filters.yearTo), on = filters.yearFrom != null || filters.yearTo != null, onOpenChange = onMenuOpenChange) {
                BrowseYearsEditor(yearFrom = filters.yearFrom, yearTo = filters.yearTo, onChange = { from, to ->
                    update { it.copy(yearFrom = from, yearTo = to) }
                })
            }

            BrowseFilterMenu(icon = Icons.Rounded.Star, label = browseRatingLabel(filters.minRating, filters.minVotes), on = filters.minRating != null || filters.minVotes != null, onOpenChange = onMenuOpenChange) {
                BrowseMenuNote("Rating")
                BrowseMenuChoices(options = BrowseRatings, value = filters.minRating?.formatRating().orEmpty(), onPick = { picked ->
                    update { it.copy(minRating = picked.toDoubleOrNull()) }
                })
                BrowseMenuNote("Votes")
                BrowseMenuChoices(options = BrowseVotes, value = filters.minVotes?.toString().orEmpty(), onPick = { picked ->
                    update { it.copy(minVotes = picked.toIntOrNull()) }
                })
            }

            BrowseFilterMenu(icon = Icons.Rounded.Schedule, label = browseRuntimeLabel(filters.runtimeMin, filters.runtimeMax), on = filters.runtimeMin != null || filters.runtimeMax != null, onOpenChange = onMenuOpenChange) { close ->
                BrowseMenuChoices(
                    options = BrowseRuntimes.map { (min, max, label) -> "${min ?: ""}-${max ?: ""}" to label },
                    value = "${filters.runtimeMin ?: ""}-${filters.runtimeMax ?: ""}",
                    onPick = { picked ->
                        val (min, max) = picked.split("-").map { it.toIntOrNull() }
                        update { it.copy(runtimeMin = min, runtimeMax = max) }
                        close()
                    },
                )
            }

            BrowseFilterMenu(
                icon = Icons.Rounded.Language,
                label = BrowseLanguages.firstOrNull { it.first == filters.language }?.second?.let { "Language: $it" } ?: "Language",
                on = filters.language != null,
                onOpenChange = onMenuOpenChange,
            ) { close ->
                BrowseMenuChoices(options = listOf("" to "Any language") + BrowseLanguages, value = filters.language.orEmpty(), onPick = { picked ->
                    update { it.copy(language = picked.ifBlank { null }) }
                    close()
                })
            }

            BrowseFilterMenu(
                icon = Icons.Rounded.Update,
                label = LibraryStatusChoices.firstOrNull { it.first == filters.status && it.first != TmdbBrowseStatus.Any }?.second?.let { "Status: $it" } ?: "Status",
                on = filters.status != TmdbBrowseStatus.Any,
                onOpenChange = onMenuOpenChange,
            ) { close ->
                BrowseMenuChoices(options = LibraryStatusChoices.map { it.first.name to it.second }, value = filters.status.name, onPick = { picked ->
                    update { it.copy(status = TmdbBrowseStatus.valueOf(picked)) }
                    close()
                })
            }

            if (filters.hasFilters) {
                BrowseFilterIconAction(Icons.Rounded.FilterAltOff, "Clear filters") { update { it.clearedFilters() } }
            }
            val needsFacts = filters.needsFacts || settings.sortOption.needsFacts()
            BrowseFilterCaption(
                when {
                    needsFacts && factsLoading -> "Looking up titles…"
                    filters.needsFacts || filters.watch != LibraryWatchFilter.All -> "%,d titles".format(shownCount)
                    else -> ""
                },
            )
        }
    }
}
