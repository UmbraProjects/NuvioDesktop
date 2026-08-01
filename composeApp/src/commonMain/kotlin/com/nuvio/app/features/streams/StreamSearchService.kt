package com.nuvio.app.features.streams

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.metadata.MediaIdResolver
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.pluginContentId
import kotlinx.coroutines.delay

/**
 * Headless stream search for background callers (library auto-download). It reuses the same fetch
 * primitives the interactive [StreamsRepository] uses — [InstalledStreamAddonTarget] selection,
 * [buildAddonResourceUrl] + [httpGetText] + [StreamParser], [filterForRequestedEpisode], and the
 * plugin scraper path — but without any of that object's UI state, single-active-job constraint, or
 * auto-play/overlay machinery, so a sweep can run concurrently with (and independently of) the
 * streams screen.
 *
 * Providers are queried **sequentially** with a delay between them: a library sweep is exactly the
 * rapid-fire shape that previously 429-looped debrid providers (see the stream-failover work), so
 * it must not fan out the way the interactive path does.
 */
internal object StreamSearchService {
    private val log = Logger.withTag("StreamSearchService")

    /** Delay between successive provider queries to stay under provider rate limits. */
    private const val PROVIDER_DELAY_MS = 3_000L

    suspend fun search(
        type: String,
        videoId: String,
        parentMetaId: String,
        season: Int?,
        episode: Int?,
        /** Restricts a follow-up search to the addon whose row initiated it. */
        addonId: String? = null,
    ): List<StreamItem> {
        val resolved = MediaIdResolver.resolveLocalEpisodeIdentity(
            contentType = type,
            parentMetaId = parentMetaId,
            videoId = videoId,
            title = null,
            season = season,
            episode = episode,
            isAnimeHint = type.equals("anime", ignoreCase = true),
        )
        val effectiveVideoId = resolved.videoId
        val effectiveSeason = resolved.streamSeason
        val effectiveEpisode = resolved.streamEpisode

        val streamAddons = AddonRepository.uiState.value.addons.enabledAddons()
            .mapNotNull { addon ->
                val manifest = addon.manifest ?: return@mapNotNull null
                val supportsRequestedStream = manifest.resources.any { resource ->
                    resource.name == "stream" &&
                        resource.types.contains(type) &&
                        (resource.idPrefixes.isEmpty() ||
                            resource.idPrefixes.any { effectiveVideoId.startsWith(it) })
                }
                if (!supportsRequestedStream) return@mapNotNull null
                InstalledStreamAddonTarget(
                    addonName = addon.displayTitle.ifBlank { manifest.name },
                    addonId = addon.streamAddonInstanceId(manifest.id),
                    manifest = manifest,
                )
            }
            .filter { addonId == null || it.addonId == addonId }

        val pluginScrapers = if (AppFeaturePolicy.pluginsEnabled) {
            PluginRepository.getEnabledScrapersForType(type)
                .filter { addonId == null || addonId == "plugin:${it.id}" }
        } else {
            emptyList()
        }

        val episodeTitles = MetaDetailsRepository.episodeTitlesByCoordinate(type = type, id = parentMetaId)
        val seriesTitle = MetaDetailsRepository.seriesTitleFor(type = type, id = parentMetaId)
        val results = mutableListOf<StreamItem>()
        var queriedAny = false

        for (addon in streamAddons) {
            if (queriedAny) delay(PROVIDER_DELAY_MS)
            queriedAny = true
            val url = buildAddonResourceUrl(
                manifestUrl = addon.manifest.transportUrl,
                resource = "stream",
                type = type,
                id = effectiveVideoId,
            )
            runCatchingUnlessCancelled {
                val payload = httpGetTextWithHeaders(url, STREAM_METADATA_REQUEST_HEADERS)
                StreamParser.parse(
                    payload = payload,
                    addonName = addon.addonName,
                    addonId = addon.addonId,
                    addonLogo = addon.manifest.logoUrl,
                ).filterForRequestedEpisode(
                    season = effectiveSeason,
                    episode = effectiveEpisode,
                    episodeTitlesByCoordinate = episodeTitles,
                    seriesTitle = seriesTitle,
                )
            }.onSuccess { results += it }
                .onFailure { log.w(it) { "headless addon fetch failed: ${addon.addonName}" } }
        }

        for (scraper in pluginScrapers) {
            if (queriedAny) delay(PROVIDER_DELAY_MS)
            queriedAny = true
            PluginRepository.executeScraper(
                scraper = scraper,
                tmdbId = pluginContentId(
                    videoId = effectiveVideoId,
                    season = effectiveSeason,
                    episode = effectiveEpisode,
                ),
                mediaType = type,
                season = effectiveSeason,
                episode = effectiveEpisode,
            ).onSuccess { runtimeResults ->
                results += runtimeResults.map { it.toStreamItem(scraper = scraper) }
            }.onFailure { log.w(it) { "headless scraper failed: ${scraper.name}" } }
        }

        return results
    }
}
