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
import com.nuvio.app.features.plugins.PluginScraper
import com.nuvio.app.features.plugins.pluginContentId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * How hard a headless search is allowed to hit providers.
 *
 * The distinction is about the *shape of the caller*, not about how fast anyone would like results.
 */
internal enum class ProviderPacing {
    /**
     * One provider at a time, [StreamSearchService.PROVIDER_DELAY_MS] apart.
     *
     * For sweeps that walk many titles in a row — a library monitor check, a season-pack grab. That
     * rapid-fire shape is what previously 429-looped debrid providers, and the delay is the fix.
     */
    Sequential,

    /**
     * All providers at once, exactly as the interactive screens query them.
     *
     * For a search over a *single* title that stands in for one the user is about to run anyway.
     * The request count is the same as one visit to the streams screen, so pacing it would only make
     * it slower without making it lighter.
     */
    Concurrent,
}

/**
 * Lets a caller see each provider's request begin and end, rather than only the finished set.
 *
 * Exists so a background sweep can register what it is already fetching: an interactive load that
 * starts mid-sweep can then wait on that request instead of sending its own copy of it.
 */
internal interface ProviderFetchObserver {
    /** Before a provider's request goes out. */
    fun onProviderStarted(providerId: String)

    /**
     * Once that provider is done. [streams] is its answer — possibly empty, which is a real answer —
     * or **null** when it failed, was cancelled or timed out and never answered at all. Always
     * called for a provider that started, including when the search is being torn down.
     */
    fun onProviderFinished(providerId: String, streams: List<StreamItem>?)
}

/**
 * Headless stream search for background callers (library auto-download, stream prefetch). It reuses
 * the same fetch primitives the interactive [StreamsRepository] uses — [InstalledStreamAddonTarget]
 * selection, [buildAddonResourceUrl] + [httpGetTextWithHeaders] + [StreamParser],
 * [filterForRequestedEpisode], and the plugin scraper path — but without any of that object's UI
 * state, single-active-job constraint, or auto-play/overlay machinery, so a sweep can run
 * concurrently with (and independently of) the streams screen.
 *
 * Callers choose their own [ProviderPacing]; the default stays [ProviderPacing.Sequential] so an
 * existing caller cannot become rapid-fire by accident.
 */
internal object StreamSearchService {
    private val log = Logger.withTag("StreamSearchService")

    /** Delay between successive provider queries under [ProviderPacing.Sequential]. */
    private const val PROVIDER_DELAY_MS = 3_000L

    /**
     * Flat results, ordered provider by provider. Retained as the shape every existing caller wants.
     */
    suspend fun search(
        type: String,
        videoId: String,
        parentMetaId: String,
        season: Int?,
        episode: Int?,
        /** Restricts a follow-up search to the addon whose row initiated it. */
        addonId: String? = null,
        pacing: ProviderPacing = ProviderPacing.Sequential,
    ): List<StreamItem> = searchGrouped(
        type = type,
        videoId = videoId,
        parentMetaId = parentMetaId,
        season = season,
        episode = episode,
        addonId = addonId,
        pacing = pacing,
    ).flatMap { it.streams }

    /**
     * Results kept per provider, which is what a caller needs when it intends to store or attribute
     * them one provider at a time rather than merge them into a single list.
     *
     * A provider that failed comes back as a group with an [AddonStreamGroup.error] and no streams,
     * so the caller can tell "nothing found" apart from "never answered".
     */
    suspend fun searchGrouped(
        type: String,
        videoId: String,
        parentMetaId: String,
        season: Int?,
        episode: Int?,
        addonId: String? = null,
        pacing: ProviderPacing = ProviderPacing.Sequential,
        observer: ProviderFetchObserver? = null,
    ): List<AddonStreamGroup> {
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

        suspend fun fetchAddonInner(addon: InstalledStreamAddonTarget): AddonStreamGroup {
            val url = buildAddonResourceUrl(
                manifestUrl = addon.manifest.transportUrl,
                resource = "stream",
                type = type,
                id = effectiveVideoId,
            )
            return runCatchingUnlessCancelled {
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
            }.fold(
                onSuccess = { streams ->
                    AddonStreamGroup(
                        addonName = addon.addonName,
                        addonId = addon.addonId,
                        streams = streams,
                        isLoading = false,
                    )
                },
                onFailure = { error ->
                    log.w(error) { "headless addon fetch failed: ${addon.addonName}" }
                    AddonStreamGroup(
                        addonName = addon.addonName,
                        addonId = addon.addonId,
                        streams = emptyList(),
                        isLoading = false,
                        // Never blank: a null message here would make a failure indistinguishable
                        // from a provider that answered with nothing, and it would then be cached.
                        error = error.message ?: "stream fetch failed",
                    )
                },
            )
        }

        suspend fun fetchScraperInner(scraper: PluginScraper): AddonStreamGroup {
            val groupId = "plugin:${scraper.id}"
            return PluginRepository.executeScraper(
                scraper = scraper,
                tmdbId = pluginContentId(
                    videoId = effectiveVideoId,
                    season = effectiveSeason,
                    episode = effectiveEpisode,
                ),
                mediaType = type,
                season = effectiveSeason,
                episode = effectiveEpisode,
            ).fold(
                onSuccess = { runtimeResults ->
                    AddonStreamGroup(
                        addonName = scraper.name,
                        addonId = groupId,
                        streams = runtimeResults.map { it.toStreamItem(scraper = scraper) },
                        isLoading = false,
                    )
                },
                onFailure = { error ->
                    log.w(error) { "headless scraper failed: ${scraper.name}" }
                    AddonStreamGroup(
                        addonName = scraper.name,
                        addonId = groupId,
                        streams = emptyList(),
                        isLoading = false,
                        error = error.message ?: "scraper failed",
                    )
                },
            )
        }

        suspend fun fetchAddon(addon: InstalledStreamAddonTarget): AddonStreamGroup {
            observer?.onProviderStarted(addon.addonId)
            // Stays null unless the provider genuinely answered, so a torn-down or failed fetch is
            // never mistaken for one that simply found nothing.
            var answered: List<StreamItem>? = null
            try {
                return fetchAddonInner(addon).also { if (it.error == null) answered = it.streams }
            } finally {
                // In a finally so a cancelled search still releases whatever it registered; a waiter
                // left hanging on a claim nobody completes is worse than no prefetch at all.
                observer?.onProviderFinished(addon.addonId, answered)
            }
        }

        suspend fun fetchScraper(scraper: PluginScraper): AddonStreamGroup {
            val groupId = "plugin:${scraper.id}"
            observer?.onProviderStarted(groupId)
            var answered: List<StreamItem>? = null
            try {
                return fetchScraperInner(scraper).also { if (it.error == null) answered = it.streams }
            } finally {
                observer?.onProviderFinished(groupId, answered)
            }
        }

        return when (pacing) {
            ProviderPacing.Sequential -> {
                val groups = mutableListOf<AddonStreamGroup>()
                var queriedAny = false
                for (addon in streamAddons) {
                    if (queriedAny) delay(PROVIDER_DELAY_MS)
                    queriedAny = true
                    groups += fetchAddon(addon)
                }
                for (scraper in pluginScrapers) {
                    if (queriedAny) delay(PROVIDER_DELAY_MS)
                    queriedAny = true
                    groups += fetchScraper(scraper)
                }
                groups
            }

            ProviderPacing.Concurrent -> coroutineScope {
                val addonJobs = streamAddons.map { addon -> async { fetchAddon(addon) } }
                val scraperJobs = pluginScrapers.map { scraper -> async { fetchScraper(scraper) } }
                addonJobs.awaitAll() + scraperJobs.awaitAll()
            }
        }
    }
}
