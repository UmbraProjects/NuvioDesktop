package com.nuvio.app.features.discover

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.FilterAltOff
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material.icons.rounded.Theaters
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.i18n.localizedMediaTypeLabel
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.search.DiscoverCatalogOption
import com.nuvio.app.features.search.DiscoverUiState
import com.nuvio.app.features.search.SearchRepository
import com.nuvio.app.features.search.discoverCatalogDisplayLabels

/*
 * Discover row 1's header: the Nuvio Tracker's filter bar. An addon catalog offers what the addon
 * protocol has — its type, the catalog, and the one "genre" extra (called Filter, because addons
 * routinely put years, decades or providers in it). The built-in TMDB browser, listed first among
 * the catalogs, brings the tracker's whole set: sort, genres, streaming service, network, studio,
 * year, rating, length, language, status and US rating.
 */

const val DiscoverTmdbCatalogLabel = "TMDB"

private val TmdbMediaChoices = listOf(
    TmdbBrowseMedia.Movie.name to "Movies",
    TmdbBrowseMedia.Tv.name to "Shows",
    TmdbBrowseMedia.All.name to "Movies & shows",
)

private val TmdbSortChoices = listOf(
    TmdbBrowseSort.Popular to "Popular",
    TmdbBrowseSort.Trending to "Trending this week",
    TmdbBrowseSort.TopRated to "Top rated",
    TmdbBrowseSort.MostVoted to "Most voted",
    TmdbBrowseSort.Newest to "Newest",
    TmdbBrowseSort.Oldest to "Oldest",
)

val BrowseLanguages: List<Pair<String, String>> = listOf(
    "en" to "English", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese", "hi" to "Hindi",
    "fr" to "French", "de" to "German", "es" to "Spanish", "it" to "Italian", "pt" to "Portuguese",
    "ru" to "Russian", "sv" to "Swedish", "da" to "Danish", "no" to "Norwegian", "fi" to "Finnish",
    "nl" to "Dutch", "pl" to "Polish", "tr" to "Turkish", "th" to "Thai", "ar" to "Arabic",
)

val BrowseRatings: List<Pair<String, String>> = listOf(
    "" to "Any rating", "5" to "5+", "6" to "6+", "7" to "7+", "7.5" to "7.5+", "8" to "8+",
)

val BrowseVotes: List<Pair<String, String>> = listOf(
    "" to "Any number of votes", "50" to "50+ votes", "200" to "200+ votes", "1000" to "1,000+ votes",
    "5000" to "5,000+ votes", "20000" to "20,000+ votes",
)

/** Length bands: min, max, label. */
val BrowseRuntimes: List<Triple<Int?, Int?, String>> = listOf(
    Triple(null, null, "Any length"), Triple(null, 30, "Under 30 min"), Triple(30, 60, "30–60 min"),
    Triple(60, 90, "60–90 min"), Triple(90, 120, "90–120 min"), Triple(120, 150, "2–2½ hours"),
    Triple(150, null, "Over 2½ hours"),
)

private val UsCertifications = listOf("G", "PG", "PG-13", "R", "NC-17")

/** "Rating: 7+, 1,000+ votes", or just "Rating". */
fun browseRatingLabel(minRating: Double?, minVotes: Int?): String {
    if (minRating == null && minVotes == null) return "Rating"
    val parts = listOfNotNull(
        minRating?.let { "${it.formatRating()}+" },
        minVotes?.let { "%,d+ votes".format(it) },
    )
    return "Rating: ${parts.joinToString(", ")}"
}

fun Double.formatRating(): String = if (this % 1.0 == 0.0) toInt().toString() else toString()

fun browseYearLabel(yearFrom: Int?, yearTo: Int?): String =
    if (yearFrom == null && yearTo == null) "Year" else "Year: ${yearFrom ?: "…"}–${yearTo ?: "…"}"

fun browseRuntimeLabel(min: Int?, max: Int?): String =
    BrowseRuntimes.firstOrNull { it.first == min && it.second == max }?.third?.takeIf { min != null || max != null } ?: "Length"

/**
 * The row-1 header. Rendered through `NuvioShelfSection`'s `titleContent` slot so it keeps the
 * shelf's header line, accent bar and view-all pill. [onMenuOpenChange] reports whether one of its
 * menus is open, so the page can stop scrolling under it.
 */
@Composable
fun DiscoverRowHeader(
    state: DiscoverUiState,
    allFiltersLabel: String,
    onMenuOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    BrowseFilterStrip(modifier = modifier) {
        if (state.tmdbSelected) {
            BrowseFilterTitle(DiscoverTmdbCatalogLabel)
            TmdbMediaMenu(state.tmdbQuery.media, onMenuOpenChange)
            CatalogMenu(state, onMenuOpenChange)
            BrowseFilterSeparator()
            TmdbFilterMenus(state, onMenuOpenChange)
        } else {
            // The icons don't name what's picked, so the title does: the catalog and its filter.
            state.selectedCatalog?.let { catalog ->
                BrowseFilterTitle(listOfNotNull(catalog.catalogName, state.selectedGenre).joinToString(" · "))
            }
            state.selectedType?.let { type ->
                BrowseFilterMenu(icon = Icons.Rounded.Theaters, label = "Type: ${localizedMediaTypeLabel(type)}", on = false, onOpenChange = onMenuOpenChange) { close ->
                    BrowseMenuChoices(
                        options = state.typeOptions.map { it to localizedMediaTypeLabel(it) },
                        value = type,
                        onPick = { picked ->
                            SearchRepository.selectDiscoverType(picked)
                            close()
                        },
                    )
                }
            }
            CatalogMenu(state, onMenuOpenChange)
            val catalog = state.selectedCatalog
            if (catalog != null && catalog.genreOptions.isNotEmpty()) {
                BrowseFilterSeparator()
                BrowseFilterMenu(
                    icon = Icons.Rounded.FilterList,
                    label = "Filter: ${state.selectedGenre ?: allFiltersLabel}",
                    on = state.selectedGenre != null && !catalog.genreRequired,
                    onOpenChange = onMenuOpenChange,
                ) { close ->
                    val options = buildList {
                        if (!catalog.genreRequired) add("" to allFiltersLabel)
                        catalog.genreOptions.forEach { add(it to it) }
                    }
                    BrowseMenuChoices(options = options, value = state.selectedGenre.orEmpty(), onPick = { picked ->
                        SearchRepository.selectDiscoverGenre(picked.ifBlank { null })
                        close()
                    })
                }
            }
        }
    }
}

@Composable
private fun TmdbMediaMenu(media: TmdbBrowseMedia, onMenuOpenChange: (Boolean) -> Unit) {
    BrowseFilterMenu(
        icon = Icons.Rounded.Theaters,
        label = "Type: ${TmdbMediaChoices.first { it.first == media.name }.second}",
        on = false,
        onOpenChange = onMenuOpenChange,
    ) { close ->
        BrowseMenuChoices(options = TmdbMediaChoices, value = media.name, onPick = { picked ->
            val next = TmdbBrowseMedia.valueOf(picked)
            // Genre ids are namespaced per media type, so a switch starts the genres over.
            SearchRepository.updateTmdbQuery { q ->
                if (q.media == next) q else q.copy(media = next, genres = emptyList(), withoutGenres = emptyList())
            }
            close()
        })
    }
}

/**
 * Every catalog in one menu: the TMDB browser first, then each type's addon catalogs under its own
 * heading — so the TMDB browser and any addon catalog are always one pick apart.
 */
@Composable
private fun CatalogMenu(state: DiscoverUiState, onMenuOpenChange: (Boolean) -> Unit) {
    val label = if (state.tmdbSelected) DiscoverTmdbCatalogLabel else state.selectedCatalog.headerLabel("Catalog")
    BrowseFilterMenu(icon = Icons.Rounded.VideoLibrary, label = "Catalog: $label", on = false, onOpenChange = onMenuOpenChange) { close ->
        if (state.tmdbAvailable) {
            BrowseMenuChoices(
                options = listOf("tmdb" to "TMDB — browse & filter everything"),
                value = if (state.tmdbSelected) "tmdb" else "",
                onPick = {
                    SearchRepository.selectTmdbBrowse()
                    close()
                },
            )
        }
        val byType = state.availableCatalogs.groupBy { it.typeGroup }
        byType.forEach { (type, catalogs) ->
            BrowseMenuNote(localizedMediaTypeLabel(type))
            val labels = discoverCatalogDisplayLabels(catalogs)
            BrowseMenuChoices(
                options = catalogs.mapIndexed { index, catalog -> catalog.key to labels.getOrElse(index) { catalog.catalogName } },
                value = if (state.tmdbSelected) "" else state.selectedCatalogKey.orEmpty(),
                onPick = { key ->
                    SearchRepository.selectDiscoverCatalog(key)
                    close()
                },
            )
        }
    }
}

@Composable
private fun TmdbFilterMenus(state: DiscoverUiState, onMenuOpenChange: (Boolean) -> Unit) {
    val q = state.tmdbQuery
    val opts = state.tmdbOptions
    val update = SearchRepository::updateTmdbQuery
    val tv = q.media == TmdbBrowseMedia.Tv
    val movies = q.media == TmdbBrowseMedia.Movie

    BrowseFilterMenu(
        icon = Icons.AutoMirrored.Rounded.Sort,
        label = "Sort: ${TmdbSortChoices.first { it.first == q.sort }.second}",
        on = false,
        onOpenChange = onMenuOpenChange,
    ) { close ->
        BrowseMenuChoices(
            options = TmdbSortChoices.map { it.first.name to it.second },
            value = q.sort.name,
            onPick = { picked ->
                update { it.copy(sort = TmdbBrowseSort.valueOf(picked)) }
                close()
            },
        )
    }
    BrowseFilterSeparator()

    val genreNames = q.genres.mapNotNull { id -> opts.genres.firstOrNull { it.id == id }?.name }
    BrowseFilterMenu(icon = Icons.Rounded.TheaterComedy, label = browseFilterSummary("Genres", genreNames), on = q.genres.isNotEmpty(), onOpenChange = onMenuOpenChange) {
        BrowseMenuCheck(on = q.allGenres, onClick = { update { it.copy(allGenres = !it.allGenres) } }) {
            Text("Must match every genre picked")
        }
        BrowseMenuCheckColumns(
            items = opts.genres,
            isOn = { it.id in q.genres },
            onToggle = { g -> update { it.copy(genres = it.genres.toggle(g.id)) } },
            label = { it.name },
        )
    }

    if (opts.providers.isNotEmpty()) {
        val providerNames = q.providers.mapNotNull { id -> opts.providers.firstOrNull { it.id == id }?.name }
        BrowseFilterMenu(icon = Icons.Rounded.LiveTv, label = browseFilterSummary("Streaming", providerNames), on = q.providers.isNotEmpty(), onOpenChange = onMenuOpenChange) {
            BrowseMenuNote("Subscription services in ${opts.region}")
            opts.providers.forEach { provider ->
                BrowseMenuCheck(on = provider.id in q.providers, onClick = { update { it.copy(providers = it.providers.toggle(provider.id)) } }) {
                    provider.logoUrl?.let { url ->
                        NuvioAsyncImage(
                            model = url,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)),
                        )
                    }
                    Text(provider.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    if (!movies && opts.networks.isNotEmpty()) {
        val networkNames = q.networks.mapNotNull { id -> opts.networks.firstOrNull { it.id == id }?.name }
        BrowseFilterMenu(icon = Icons.Rounded.Tv, label = browseFilterSummary("Network", networkNames), on = q.networks.isNotEmpty(), onOpenChange = onMenuOpenChange) {
            BrowseMenuCheckColumns(
                items = opts.networks,
                isOn = { it.id in q.networks },
                onToggle = { n -> update { it.copy(networks = it.networks.toggle(n.id)) } },
                label = { it.name },
            )
        }
    }

    BrowseFilterMenu(icon = Icons.Rounded.Business, label = browseFilterSummary("Studio", q.companies.map { it.name }), on = q.companies.isNotEmpty(), onOpenChange = onMenuOpenChange) {
        q.companies.forEach { company ->
            BrowseMenuCheck(on = true, onClick = { update { it.copy(companies = it.companies.filterNot { c -> c.id == company.id }) } }) {
                Text(company.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        BrowseStudioPicker(onPick = { studio ->
            update { it.copy(companies = (it.companies + TmdbBrowseCompany(studio.id, studio.name)).distinctBy { c -> c.id }.take(12)) }
        })
    }

    BrowseFilterMenu(icon = Icons.Rounded.Event, label = browseYearLabel(q.yearFrom, q.yearTo), on = q.yearFrom != null || q.yearTo != null, onOpenChange = onMenuOpenChange) {
        BrowseYearsEditor(yearFrom = q.yearFrom, yearTo = q.yearTo, onChange = { from, to ->
            update { it.copy(yearFrom = from, yearTo = to) }
        })
    }

    BrowseFilterMenu(icon = Icons.Rounded.Star, label = browseRatingLabel(q.minRating, q.minVotes), on = q.minRating != null || q.minVotes != null, onOpenChange = onMenuOpenChange) {
        BrowseMenuNote("Rating")
        BrowseMenuChoices(options = BrowseRatings, value = q.minRating?.formatRating().orEmpty(), onPick = { picked ->
            update { it.copy(minRating = picked.toDoubleOrNull()) }
        })
        BrowseMenuNote("Votes")
        BrowseMenuChoices(options = BrowseVotes, value = q.minVotes?.toString().orEmpty(), onPick = { picked ->
            update { it.copy(minVotes = picked.toIntOrNull()) }
        })
    }

    BrowseFilterMenu(icon = Icons.Rounded.Schedule, label = browseRuntimeLabel(q.runtimeMin, q.runtimeMax), on = q.runtimeMin != null || q.runtimeMax != null, onOpenChange = onMenuOpenChange) { close ->
        BrowseMenuChoices(
            options = BrowseRuntimes.map { (min, max, label) -> "${min ?: ""}-${max ?: ""}" to label },
            value = "${q.runtimeMin ?: ""}-${q.runtimeMax ?: ""}",
            onPick = { picked ->
                val (min, max) = picked.split("-").map { it.toIntOrNull() }
                update { it.copy(runtimeMin = min, runtimeMax = max) }
                close()
            },
        )
    }

    BrowseFilterMenu(
        icon = Icons.Rounded.Language,
        label = BrowseLanguages.firstOrNull { it.first == q.language }?.second?.let { "Language: $it" } ?: "Language",
        on = q.language != null,
        onOpenChange = onMenuOpenChange,
    ) { close ->
        BrowseMenuChoices(options = listOf("" to "Any language") + BrowseLanguages, value = q.language.orEmpty(), onPick = { picked ->
            update { it.copy(language = picked.ifBlank { null }) }
            close()
        })
    }

    val statusOptions = buildList {
        add(TmdbBrowseStatus.Any to "Any status")
        if (!movies) {
            add(TmdbBrowseStatus.Returning to "Still running")
            add(TmdbBrowseStatus.Ended to "Ended")
        }
        if (!tv) {
            add(TmdbBrowseStatus.InCinemas to "In cinemas")
            add(TmdbBrowseStatus.HomeRelease to "Out at home")
        }
        add(TmdbBrowseStatus.Upcoming to "Coming soon")
    }
    BrowseFilterMenu(
        icon = Icons.Rounded.Update,
        label = statusOptions.firstOrNull { it.first == q.status && it.first != TmdbBrowseStatus.Any }?.second?.let { "Status: $it" } ?: "Status",
        on = q.status != TmdbBrowseStatus.Any,
        onOpenChange = onMenuOpenChange,
    ) { close ->
        BrowseMenuChoices(options = statusOptions.map { it.first.name to it.second }, value = q.status.name, onPick = { picked ->
            update { it.copy(status = TmdbBrowseStatus.valueOf(picked)) }
            close()
        })
    }

    if (!tv) {
        BrowseFilterMenu(
            icon = Icons.Rounded.Shield,
            label = q.certification?.let { "Rated $it" } ?: "US film rating",
            on = q.certification != null,
            onOpenChange = onMenuOpenChange,
        ) { close ->
            BrowseMenuNote("US film rating")
            BrowseMenuChoices(
                options = listOf("" to "Any") + UsCertifications.map { it to it },
                value = q.certification.orEmpty(),
                onPick = { picked ->
                    update { it.copy(certification = picked.ifBlank { null }) }
                    close()
                },
            )
        }
    }

    if (q.hasFilters) {
        BrowseFilterIconAction(Icons.Rounded.FilterAltOff, "Clear filters") { update { it.clearedFilters() } }
    }
    val count = when {
        state.isLoading && state.items.isEmpty() -> "Loading…"
        state.tmdbTotal >= 10_000 -> "10,000+ titles"
        state.tmdbTotal > 0 -> "%,d titles".format(state.tmdbTotal)
        else -> ""
    }
    if (count.isNotEmpty()) BrowseFilterCaption(count)
}

private fun List<Int>.toggle(id: Int): List<Int> = if (id in this) this - id else this + id

/**
 * Stand-in for the poster strip when the row returns nothing (or is still loading). Occupies real
 * height so the row cannot collapse to a bare header — the header is the only way back to a
 * different catalog, so it must always be visible and clickable.
 */
@Composable
fun BoxScope.DiscoverRowBodyMessage(
    text: String,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 28.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.nuvio.colors.textMuted,
    )
}

/** The row body's loading / empty message; nothing once there are posters to show. */
@Composable
fun BoxScope.DiscoverRowBody(
    state: DiscoverUiState,
    itemsEmpty: Boolean,
    horizontalPadding: Dp,
    emptyText: String,
    loadingText: String,
) {
    if (itemsEmpty) {
        DiscoverRowBodyMessage(
            text = if (state.isLoading) loadingText else emptyText,
            horizontalPadding = horizontalPadding,
        )
    }
}

/** Header label for the currently selected catalog, or a prompt when nothing is selected yet. */
fun DiscoverCatalogOption?.headerLabel(fallback: String): String = this?.catalogName ?: fallback
