package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.NuvioModalDialog
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.usenet.BuiltInUsenet
import com.nuvio.app.features.usenet.UsenetInfoFormat as F
import com.nuvio.app.features.usenet.UsenetSourceConfiguration
import com.nuvio.app.features.usenet.UsenetStats
import com.nuvio.app.features.usenet.mostIncompleteIndexer
import com.nuvio.app.features.usenet.slowestIndexer
import java.text.DateFormat
import java.util.Date
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Settings > Usenet > Usenet info: what each provider and indexer has done since the counts were
 * reset ([UsenetStats]). Providers and indexers are listed in the settings' order; a removed one's
 * counts are kept but not shown.
 */
@Composable
internal fun UsenetInfoDialog(sources: UsenetSourceConfiguration, onDismiss: () -> Unit) {
    val book by UsenetStats.book.collectAsStateWithLifecycle()
    val indexerRevision by BuiltInUsenet.client.statusChanges.collectAsStateWithLifecycle()
    val dates = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val colors = MaterialTheme.nuvio.colors

    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.usenet_info_title),
        subtitle = stringResource(Res.string.usenet_info_since, dates.format(Date(book.since))),
        maxWidth = 720.dp,
        actions = {
            DialogButton(text = stringResource(Res.string.usenet_info_reset), onClick = { UsenetStats.reset() })
            DialogButton(text = stringResource(Res.string.usenet_info_close), style = DialogButtonStyle.Primary, onClick = onDismiss)
        },
    ) {
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val providers = sources.providers.mapNotNull { p -> book.providers[p.id]?.let { p to it } }
            val indexers = sources.indexers.mapNotNull { i -> book.indexers[i.id]?.let { i to it } }
            if (providers.isEmpty() && indexers.isEmpty()) {
                Text(stringResource(Res.string.usenet_info_nothing), style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
                return@Column
            }

            // The answers to "what is slowing it down" and "which one has dead releases", first.
            slowestIndexer(book, sources.indexers)?.let { (indexer, ms) ->
                Callout(stringResource(Res.string.usenet_info_slowest, indexer.name, F.duration(ms)))
            }
            mostIncompleteIndexer(book, sources.indexers)?.let { (indexer, share) ->
                Callout(stringResource(Res.string.usenet_info_most_missing, indexer.name, F.percent(share)))
            }

            if (providers.isNotEmpty()) {
                Heading(stringResource(Res.string.usenet_info_providers))
                providers.forEach { (provider, stats) ->
                    Entry(
                        name = provider.name,
                        lines = listOf(
                            stringResource(Res.string.usenet_info_downloaded, F.bytes(stats.bytes), F.count(stats.articles), stats.sessions.toInt()),
                            stringResource(Res.string.usenet_info_missing, F.percent(stats.missingPercent), F.percent(stats.errorPercent)),
                            stringResource(Res.string.usenet_info_speed, F.duration(stats.averageFirstByteMs), F.duration(stats.averageConnectMs)),
                            lastUsed(stats.lastUsed, dates),
                        ),
                    )
                }
            }

            if (indexers.isNotEmpty()) {
                Heading(stringResource(Res.string.usenet_info_indexers))
                indexers.forEach { (indexer, stats) ->
                    val status = remember(indexer, indexerRevision) { BuiltInUsenet.client.status(indexer) }
                    Entry(
                        name = indexer.name,
                        lines = listOfNotNull(
                            stringResource(
                                Res.string.usenet_info_response,
                                F.duration(stats.averageRequestMs), F.duration(stats.slowestMs.takeIf { stats.requests > 0 }),
                                F.percent(stats.failedRequestPercent),
                            ),
                            stringResource(
                                Res.string.usenet_info_searches,
                                stats.searches.toInt(), F.duration(stats.averageSearchMs), stats.releases.toInt(),
                                stats.emptySearches.toInt(), stats.failedSearches.toInt(),
                            ),
                            stringResource(
                                Res.string.usenet_info_grabs,
                                stats.grabs.toInt(), stats.played.toInt(), stats.incomplete.toInt(),
                                F.percent(stats.incompletePercent), stats.failed.toInt(),
                            ),
                            stringResource(Res.string.usenet_info_limits, stats.limits.toInt()).takeIf { stats.limits > 0 },
                            if (status.apiHitsLeft != null || status.grabsLeft != null) {
                                stringResource(
                                    Res.string.usenet_info_left,
                                    status.apiHitsLeft?.toString() ?: F.NONE, status.grabsLeft?.toString() ?: F.NONE,
                                )
                            } else null,
                            lastUsed(stats.lastUsed, dates),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun lastUsed(at: Long, dates: DateFormat): String? =
    if (at > 0) stringResource(Res.string.usenet_info_last_used, dates.format(Date(at))) else null

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.nuvio.colors.textMuted)
}

@Composable
private fun Callout(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.nuvio.colors.warning)
}

@Composable
private fun Entry(name: String, lines: List<String?>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.nuvio.colors.textPrimary)
        lines.filterNotNull().forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.textMuted)
        }
    }
}
