package com.nuvio.app.features.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Theaters
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.i18n.localizedMediaTypeLabel
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.discover.BrowseDecades
import com.nuvio.app.features.discover.BrowseFilterMenu
import com.nuvio.app.features.discover.BrowseFilterSeparator
import com.nuvio.app.features.discover.BrowseFilterStrip
import com.nuvio.app.features.discover.BrowseMenuCheck
import com.nuvio.app.features.discover.BrowseMenuChoices
import com.nuvio.app.features.discover.BrowseMenuNote
import com.nuvio.app.features.discover.BrowseYearsEditor
import com.nuvio.app.features.home.MetaPreview

/**
 * See more's own filters, applied to what has loaded (an addon can only filter by its genre list, which goes to
 * the addon instead: [addonCatalogGenres]). Infinite scroll keeps fetching while too few loaded titles pass.
 */
data class CatalogLocalFilters(
    val type: String? = null,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val minRating: Double? = null,
    val hideWatched: Boolean = false,
) {
    val active: Boolean get() = this != CatalogLocalFilters()
}

/** The year a catalog item came out, from its release date or the start of its release info ("2019–2023"). */
internal fun MetaPreview.catalogYear(): Int? =
    (rawReleaseDate ?: releaseInfo)?.trim()?.take(4)?.toIntOrNull()

/** [items] that pass [filters]. A year or rating filter leaves out titles that carry none. */
internal fun applyCatalogFilters(
    items: List<MetaPreview>,
    filters: CatalogLocalFilters,
    isWatched: (MetaPreview) -> Boolean,
): List<MetaPreview> {
    if (!filters.active) return items
    return items.filter { item ->
        if (filters.type != null && !item.type.equals(filters.type, ignoreCase = true)) return@filter false
        if (filters.yearFrom != null || filters.yearTo != null) {
            val year = item.catalogYear() ?: return@filter false
            if (filters.yearFrom != null && year < filters.yearFrom) return@filter false
            if (filters.yearTo != null && year > filters.yearTo) return@filter false
        }
        if (filters.minRating != null) {
            val rating = item.imdbRating?.trim()?.toDoubleOrNull() ?: return@filter false
            if (rating < filters.minRating) return@filter false
        }
        if (filters.hideWatched && isWatched(item)) return@filter false
        true
    }
}

/** The genre list an addon catalog takes (its `genre` extra), and whether one must be picked. Null: none. */
internal data class AddonCatalogGenres(val options: List<String>, val required: Boolean)

internal fun addonCatalogGenres(target: CatalogTarget.Addon): AddonCatalogGenres? {
    val catalog = AddonRepository.uiState.value.addons
        .mapNotNull { it.manifest }
        .firstOrNull { it.transportUrl == target.manifestUrl }
        ?.catalogs
        ?.firstOrNull { it.id == target.catalogId && it.type == target.contentType }
        ?: return null
    val genre = catalog.extra.firstOrNull { it.name.equals("genre", ignoreCase = true) } ?: return null
    if (genre.options.isEmpty()) return null
    return AddonCatalogGenres(genre.options, genre.isRequired)
}

private val CatalogRatingChoices = listOf(null to "Any rating", 6.0 to "6+", 7.0 to "7+", 8.0 to "8+")

/**
 * The page's top line, which owns the whole top of the screen (no navbar here): Back and the filters on the left,
 * the catalog's name small and out of the way on the right ("Name · Subname").
 */
@Composable
internal fun CatalogTopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backModifier: Modifier = Modifier,
    filters: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 20.dp, top = 16.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NuvioBackButton(onClick = onBack, modifier = backModifier.size(40.dp), iconSize = 24.dp)
        Spacer(Modifier.width(12.dp))
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { filters() }
        Spacer(Modifier.width(16.dp))
        Text(
            text = listOf(title, subtitle).filter { it.isNotBlank() }.joinToString(" · "),
            modifier = Modifier.widthIn(max = 420.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.nuvio.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}

/** An addon catalog's (or a library row's) filters: the addon's genres, then type, year, rating and watched. */
@Composable
internal fun CatalogFilterMenus(
    genres: AddonCatalogGenres?,
    genre: String?,
    onGenre: (String?) -> Unit,
    typeOptions: List<String>,
    filters: CatalogLocalFilters,
    onFilters: (CatalogLocalFilters) -> Unit,
    allLabel: String,
    modifier: Modifier = Modifier,
) {
    BrowseFilterStrip(modifier = modifier) {
        if (genres != null) {
            BrowseFilterMenu(
                icon = Icons.Rounded.FilterList,
                label = "Genre: ${genre ?: allLabel}",
                on = genre != null && !genres.required,
            ) { close ->
                val options = buildList {
                    if (!genres.required) add("" to allLabel)
                    genres.options.forEach { add(it to it) }
                }
                BrowseMenuChoices(options = options, value = genre.orEmpty(), onPick = { picked ->
                    onGenre(picked.ifBlank { null })
                    close()
                })
            }
            BrowseFilterSeparator()
        }
        if (typeOptions.size > 1) {
            BrowseFilterMenu(
                icon = Icons.Rounded.Theaters,
                label = "Type: ${filters.type?.let(::localizedMediaTypeLabel) ?: allLabel}",
                on = filters.type != null,
            ) { close ->
                BrowseMenuChoices(
                    options = listOf("" to allLabel) + typeOptions.map { it to localizedMediaTypeLabel(it) },
                    value = filters.type.orEmpty(),
                    onPick = { picked ->
                        onFilters(filters.copy(type = picked.ifBlank { null }))
                        close()
                    },
                )
            }
        }
        val yearLabel = BrowseDecades.firstOrNull { it.second == filters.yearFrom && it.third == filters.yearTo }?.first
            ?: when {
                filters.yearFrom == null && filters.yearTo == null -> "Year"
                else -> listOf(filters.yearFrom?.toString() ?: "…", filters.yearTo?.toString() ?: "…").joinToString("–")
            }
        BrowseFilterMenu(
            icon = Icons.Rounded.CalendarMonth,
            label = yearLabel,
            on = filters.yearFrom != null || filters.yearTo != null,
        ) {
            BrowseYearsEditor(yearFrom = filters.yearFrom, yearTo = filters.yearTo) { from, to ->
                onFilters(filters.copy(yearFrom = from, yearTo = to))
            }
        }
        BrowseFilterMenu(
            icon = Icons.Rounded.Star,
            label = CatalogRatingChoices.first { it.first == filters.minRating }.second.let { if (filters.minRating == null) "Rating" else "Rating $it" },
            on = filters.minRating != null,
        ) { close ->
            BrowseMenuNote("IMDb rating")
            BrowseMenuChoices(
                options = CatalogRatingChoices.map { (it.first?.toString() ?: "") to it.second },
                value = filters.minRating?.toString().orEmpty(),
                onPick = { picked ->
                    onFilters(filters.copy(minRating = picked.toDoubleOrNull()))
                    close()
                },
            )
        }
        BrowseFilterMenu(
            icon = Icons.Rounded.Visibility,
            label = if (filters.hideWatched) "Unwatched" else "Watched: shown",
            on = filters.hideWatched,
        ) {
            BrowseMenuCheck(on = filters.hideWatched, onClick = { onFilters(filters.copy(hideWatched = !filters.hideWatched)) }) {
                Text("Hide watched")
            }
        }
    }
}
