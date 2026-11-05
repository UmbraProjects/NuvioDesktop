package com.nuvio.app.features.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.features.cloud.PremiumizeCloudLibraryPosterUrl
import com.nuvio.app.features.cloud.TorboxCloudLibraryPosterUrl
import com.nuvio.app.features.cloud.cloudLibraryDisplayArtworkUrl
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.core.ui.accentBrush

private const val TmdbUrl = "https://www.themoviedb.org"
private const val ImdbDatasetsUrl = "https://developer.imdb.com/non-commercial-datasets/"
private const val TraktUrl = "https://trakt.tv"
private const val PremiumizeUrl = "https://www.premiumize.me"
private const val TorboxUrl = "https://torbox.app"
private const val MdbListUrl = "https://mdblist.com"
private const val IntroDbUrl = "https://introdb.app/"
private const val SkipDbUrl = "https://skipdb.tv"
private const val TvdbUrl = "https://thetvdb.com"
private const val SimklUrl = "https://simkl.com"
private const val KitsuUrl = "https://kitsu.app"
private const val AnimeMappingUrl = "https://github.com/Fribb/anime-lists"
private const val NuvioRepositoryUrl = "https://github.com/NuvioMedia/NuvioDesktop"
private const val NuvioContributeUrl = "https://tapframe.space/contribute"
private const val MpvUrl = "https://mpv.io"
private const val FfmpegUrl = "https://ffmpeg.org"
private const val Msys2PackagesUrl = "https://packages.msys2.org"
private const val EclipseMusicUrl = "https://eclipsemusic.app/"
private const val GoatTvUrl = "https://github.com/GOAThings/GoatTV"
private const val StremioKaiUrl = "https://github.com/allecsc/Stremio-Kai"
private const val Anime4kUrl = "https://github.com/bloc97/Anime4K"
private const val TrashGuidesUrl = "https://trash-guides.info/"
private const val WhisperCppUrl = "https://github.com/ggml-org/whisper.cpp"
private const val LibtorrentUrl = "https://libtorrent.org"
private const val QuickJsKtUrl = "https://github.com/dokar3/quickjs-kt"
private const val OnnxRuntimeUrl = "https://onnxruntime.ai"
private const val GoogleFontsUrl = "https://fonts.google.com"
private const val SponsorBlockUrl = "https://sponsor.ajay.app"
private const val ReturnYouTubeDislikeUrl = "https://returnyoutubedislike.com"
private const val WikipediaUrl = "https://en.wikipedia.org"
private const val LrclibUrl = "https://lrclib.net"
private const val MusicBrainzUrl = "https://musicbrainz.org"
private const val TheAudioDbUrl = "https://www.theaudiodb.com"
private const val FanartTvUrl = "https://fanart.tv"
private const val IgdbUrl = "https://www.igdb.com"
private const val AniListUrl = "https://anilist.co"
private const val AniSkipUrl = "https://aniskip.com"

private data class AttributionItem(
    val searchKey: String,
    val titleRes: StringResource,
    val bodyRes: StringResource,
    val logo: IntegrationLogo?,
    val logoUrl: String? = null,
    val logoText: String? = null,
    val link: String,
)

private data class LicenseItem(
    val searchKey: String,
    val titleRes: StringResource,
    val bodyRes: StringResource,
    val licenseRes: StringResource,
    val link: String,
)

@Composable
fun LicensesAttributionsSettingsScreen(
    onBack: () -> Unit,
) {
    NuvioScreen(
        modifier = Modifier.fillMaxSize(),
    ) {
        stickyHeader {
            NuvioScreenHeader(
                title = stringResource(Res.string.compose_settings_page_licenses_attributions),
                onBack = onBack,
            )
        }
        licensesAttributionsContent(isTablet = false)
    }
}

internal fun LazyListScope.licensesAttributionsContent(
    isTablet: Boolean,
) {
    item {
        LicensesAttributionsBody(isTablet = isTablet)
    }
}

@Composable
private fun LicensesAttributionsBody(
    isTablet: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (isTablet) 28.dp else 24.dp),
    ) {
        PlainSettingsStack(
            title = stringResource(Res.string.settings_licenses_attributions_section_app),
            isTablet = isTablet,
        ) {
            AttributionRow(
                item = nuvioAttributionItem(),
                isTablet = isTablet,
            )
            PlainStackDivider()
            LicenseRow(
                item = appLicenseItem(),
                isTablet = isTablet,
            )
        }

        PlainSettingsStack(
            title = stringResource(Res.string.settings_licenses_attributions_section_projects),
            isTablet = isTablet,
        ) {
            val items = projectAttributionItems()
            items.forEachIndexed { index, item ->
                AttributionRow(
                    item = item,
                    isTablet = isTablet,
                )
                if (index != items.lastIndex) {
                    PlainStackDivider()
                }
            }
        }

        PlainSettingsStack(
            title = stringResource(Res.string.settings_licenses_attributions_section_data),
            isTablet = isTablet,
        ) {
            val items = attributionItems()
            items.forEachIndexed { index, item ->
                AttributionRow(
                    item = item,
                    isTablet = isTablet,
                )
                if (index != items.lastIndex) {
                    PlainStackDivider()
                }
            }
        }

        PlainSettingsStack(
            title = stringResource(Res.string.settings_licenses_attributions_section_playback),
            isTablet = isTablet,
        ) {
            val items = playbackLicenseItems()
            items.forEachIndexed { index, item ->
                LicenseRow(
                    item = item,
                    isTablet = isTablet,
                )
                if (index != items.lastIndex) {
                    PlainStackDivider()
                }
            }
        }

        PlainSettingsStack(
            title = stringResource(Res.string.settings_licenses_attributions_section_libraries),
            isTablet = isTablet,
        ) {
            val items = bundledLibraryLicenseItems()
            items.forEachIndexed { index, item ->
                LicenseRow(
                    item = item,
                    isTablet = isTablet,
                    footerRes = Res.string.settings_licenses_attributions_full_texts,
                )
                if (index != items.lastIndex) {
                    PlainStackDivider()
                }
            }
        }
    }
}

@Composable
private fun PlainSettingsStack(
    title: String,
    isTablet: Boolean,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(if (isTablet) 12.dp else 10.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            content()
        }
    }
}

@Composable
private fun AttributionRow(
    item: AttributionItem,
    isTablet: Boolean,
) {
    val uriHandler = LocalUriHandler.current
    val title = stringResource(item.titleRes)
    LinkedPlainRow(
        title = title,
        body = stringResource(item.bodyRes),
        link = item.link,
        isTablet = isTablet,
        searchKey = item.searchKey,
        leading = when {
            item.logo != null -> item.logo.let { logo ->
                {
                    IntegrationLogoImage(
                        painter = integrationLogoPainter(logo),
                        contentDescription = title,
                        isTablet = isTablet,
                    )
                }
            }
            item.logoUrl != null -> item.logoUrl.let { logoUrl ->
                {
                    ProviderLogoImage(
                        url = logoUrl,
                        contentDescription = title,
                        isTablet = isTablet,
                        fallbackText = item.logoText,
                    )
                }
            }
            item.logoText != null -> item.logoText.let { logoText ->
                {
                    ProviderLogoTextBadge(
                        text = logoText,
                        contentDescription = title,
                        isTablet = isTablet,
                    )
                }
            }
            else -> null
        },
        onOpen = { uriHandler.openUri(item.link) },
    )
}

@Composable
private fun LicenseRow(
    item: LicenseItem,
    isTablet: Boolean,
    footerRes: StringResource? = null,
) {
    val uriHandler = LocalUriHandler.current
    val itemBody = stringResource(item.bodyRes)
    val itemLicense = stringResource(item.licenseRes)
    val footer = footerRes?.let { stringResource(it) }
    val body = buildString {
        append(itemBody)
        append("\n")
        append(itemLicense)
        if (footer != null) {
            append(" ")
            append(footer)
        }
    }
    LinkedPlainRow(
        title = stringResource(item.titleRes),
        body = body,
        link = item.link,
        isTablet = isTablet,
        searchKey = item.searchKey,
        onOpen = { uriHandler.openUri(item.link) },
    )
}

@Composable
private fun LinkedPlainRow(
    title: String,
    body: String,
    link: String,
    isTablet: Boolean,
    searchKey: String,
    leading: (@Composable () -> Unit)? = null,
    onOpen: () -> Unit,
) {
    val verticalPadding = if (isTablet) 18.dp else 16.dp
    val horizontalPadding = if (isTablet) 4.dp else 0.dp

    Row(
        modifier = Modifier
            .settingsScrollAnchor(SettingsScrollAnchor.searchKey(searchKey))
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(if (isTablet) 18.dp else 14.dp),
    ) {
        leading?.invoke()
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = link,
                style = MaterialTheme.typography.bodySmall.accentBrush(),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
            contentDescription = null,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(if (isTablet) 22.dp else 20.dp)
                .alpha(0.72f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun IntegrationLogoImage(
    painter: Painter,
    contentDescription: String,
    isTablet: Boolean,
) {
    Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = Modifier
            .padding(top = 2.dp)
            .size(if (isTablet) 46.dp else 40.dp),
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun ProviderLogoImage(
    url: String,
    contentDescription: String,
    isTablet: Boolean,
    fallbackText: String? = null,
) {
    // These logos are hotlinked from the provider, so they are unavailable offline or if the
    // host blocks us. Fall back to the letter badge rather than leaving a blank square.
    var failed by remember(url) { mutableStateOf(false) }
    if (failed && !fallbackText.isNullOrBlank()) {
        ProviderLogoTextBadge(
            text = fallbackText,
            contentDescription = contentDescription,
            isTablet = isTablet,
        )
        return
    }
    AsyncImage(
        model = url,
        contentDescription = contentDescription,
        modifier = Modifier
            .padding(top = 2.dp)
            .size(if (isTablet) 46.dp else 40.dp),
        contentScale = ContentScale.Fit,
        onError = { failed = true },
    )
}

@Composable
private fun ProviderLogoTextBadge(
    text: String,
    contentDescription: String,
    isTablet: Boolean,
) {
    Box(
        modifier = Modifier
            .padding(top = 2.dp)
            .size(if (isTablet) 46.dp else 40.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
                shape = RoundedCornerShape(8.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun PlainStackDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f),
    )
}

private fun attributionItems(): List<AttributionItem> = listOf(
    AttributionItem(
        searchKey = "tmdb-attribution",
        titleRes = Res.string.settings_licenses_attributions_tmdb_title,
        bodyRes = Res.string.settings_licenses_attributions_tmdb_body,
        logo = IntegrationLogo.Tmdb,
        link = TmdbUrl,
    ),
    AttributionItem(
        searchKey = "trakt-attribution",
        titleRes = Res.string.settings_licenses_attributions_trakt_title,
        bodyRes = Res.string.settings_licenses_attributions_trakt_body,
        logo = IntegrationLogo.Trakt,
        link = TraktUrl,
    ),
    AttributionItem(
        searchKey = "premiumize-attribution",
        titleRes = Res.string.settings_licenses_attributions_premiumize_title,
        bodyRes = Res.string.settings_licenses_attributions_premiumize_body,
        logo = null,
        logoUrl = PremiumizeCloudLibraryPosterUrl,
        link = PremiumizeUrl,
    ),
    AttributionItem(
        searchKey = "torbox-attribution",
        titleRes = Res.string.settings_licenses_attributions_torbox_title,
        bodyRes = Res.string.settings_licenses_attributions_torbox_body,
        logo = null,
        logoUrl = cloudLibraryDisplayArtworkUrl(TorboxCloudLibraryPosterUrl),
        link = TorboxUrl,
    ),
    AttributionItem(
        searchKey = "mdblist-attribution",
        titleRes = Res.string.settings_licenses_attributions_mdblist_title,
        bodyRes = Res.string.settings_licenses_attributions_mdblist_body,
        logo = IntegrationLogo.MdbList,
        link = MdbListUrl,
    ),
    AttributionItem(
        searchKey = "skipdb-attribution",
        titleRes = Res.string.settings_licenses_attributions_skipdb_title,
        bodyRes = Res.string.settings_licenses_attributions_skipdb_body,
        logo = null,
        logoText = "SkipDB",
        link = SkipDbUrl,
    ),
    AttributionItem(
        searchKey = "introdb-attribution",
        titleRes = Res.string.settings_licenses_attributions_introdb_title,
        bodyRes = Res.string.settings_licenses_attributions_introdb_body,
        logo = IntegrationLogo.IntroDb,
        link = IntroDbUrl,
    ),
    AttributionItem(
        searchKey = "tvdb-attribution",
        titleRes = Res.string.settings_licenses_attributions_tvdb_title,
        bodyRes = Res.string.settings_licenses_attributions_tvdb_body,
        logo = IntegrationLogo.Tvdb,
        link = TvdbUrl,
    ),
    AttributionItem(
        searchKey = "simkl-attribution",
        titleRes = Res.string.settings_licenses_attributions_simkl_title,
        bodyRes = Res.string.settings_licenses_attributions_simkl_body,
        logo = IntegrationLogo.Simkl,
        link = SimklUrl,
    ),
    AttributionItem(
        searchKey = "kitsu-attribution",
        titleRes = Res.string.settings_licenses_attributions_kitsu_title,
        bodyRes = Res.string.settings_licenses_attributions_kitsu_body,
        logo = IntegrationLogo.Kitsu,
        link = KitsuUrl,
    ),
    AttributionItem(
        searchKey = "anime-mapping-attribution",
        titleRes = Res.string.settings_licenses_attributions_anime_mapping_title,
        bodyRes = Res.string.settings_licenses_attributions_anime_mapping_body,
        logo = null,
        logoText = "ODbL",
        link = AnimeMappingUrl,
    ),
    AttributionItem(
        searchKey = "imdb-datasets",
        titleRes = Res.string.settings_licenses_attributions_imdb_title,
        bodyRes = Res.string.settings_licenses_attributions_imdb_body,
        logo = null,
        link = ImdbDatasetsUrl,
    ),
    AttributionItem(
        searchKey = "sponsorblock-attribution",
        titleRes = Res.string.settings_licenses_attributions_sponsorblock_title,
        bodyRes = Res.string.settings_licenses_attributions_sponsorblock_body,
        logo = null,
        logoText = "SB",
        link = SponsorBlockUrl,
    ),
    AttributionItem(
        searchKey = "ryd-attribution",
        titleRes = Res.string.settings_licenses_attributions_ryd_title,
        bodyRes = Res.string.settings_licenses_attributions_ryd_body,
        logo = null,
        logoText = "RYD",
        link = ReturnYouTubeDislikeUrl,
    ),
    AttributionItem(
        searchKey = "wikipedia-attribution",
        titleRes = Res.string.settings_licenses_attributions_wikipedia_title,
        bodyRes = Res.string.settings_licenses_attributions_wikipedia_body,
        logo = null,
        logoText = "W",
        link = WikipediaUrl,
    ),
    AttributionItem(
        searchKey = "lyrics-attribution",
        titleRes = Res.string.settings_licenses_attributions_lyrics_title,
        bodyRes = Res.string.settings_licenses_attributions_lyrics_body,
        logo = null,
        logoText = "LRC",
        link = LrclibUrl,
    ),
    AttributionItem(
        searchKey = "musicbrainz-attribution",
        titleRes = Res.string.settings_licenses_attributions_musicbrainz_title,
        bodyRes = Res.string.settings_licenses_attributions_musicbrainz_body,
        logo = null,
        logoText = "MB",
        link = MusicBrainzUrl,
    ),
    AttributionItem(
        searchKey = "music-metadata-attribution",
        titleRes = Res.string.settings_licenses_attributions_music_metadata_title,
        bodyRes = Res.string.settings_licenses_attributions_music_metadata_body,
        logo = null,
        logoText = "Music",
        link = TheAudioDbUrl,
    ),
    AttributionItem(
        searchKey = "fanart-attribution",
        titleRes = Res.string.settings_licenses_attributions_fanart_title,
        bodyRes = Res.string.settings_licenses_attributions_fanart_body,
        logo = null,
        logoText = "fanart",
        link = FanartTvUrl,
    ),
    AttributionItem(
        searchKey = "games-attribution",
        titleRes = Res.string.settings_licenses_attributions_games_title,
        bodyRes = Res.string.settings_licenses_attributions_games_body,
        logo = null,
        logoText = "IGDB",
        link = IgdbUrl,
    ),
    AttributionItem(
        searchKey = "anilist-attribution",
        titleRes = Res.string.settings_licenses_attributions_anilist_title,
        bodyRes = Res.string.settings_licenses_attributions_anilist_body,
        logo = null,
        logoText = "AniList",
        link = AniListUrl,
    ),
    AttributionItem(
        searchKey = "aniskip-attribution",
        titleRes = Res.string.settings_licenses_attributions_aniskip_title,
        bodyRes = Res.string.settings_licenses_attributions_aniskip_body,
        logo = null,
        logoText = "Skip",
        link = AniSkipUrl,
    ),
)

// Other people's work the app is built on: protocols, ported code, bundled shaders and data.
private fun projectAttributionItems(): List<AttributionItem> = listOf(
    AttributionItem(
        searchKey = "eclipse-attribution",
        titleRes = Res.string.settings_licenses_attributions_eclipse_title,
        bodyRes = Res.string.settings_licenses_attributions_eclipse_body,
        logo = null,
        logoText = "Eclipse",
        link = EclipseMusicUrl,
    ),
    AttributionItem(
        searchKey = "goattv-attribution",
        titleRes = Res.string.settings_licenses_attributions_goattv_title,
        bodyRes = Res.string.settings_licenses_attributions_goattv_body,
        logo = null,
        logoText = "GoatTV",
        link = GoatTvUrl,
    ),
    AttributionItem(
        searchKey = "stremio-kai-attribution",
        titleRes = Res.string.settings_licenses_attributions_stremio_kai_title,
        bodyRes = Res.string.settings_licenses_attributions_stremio_kai_body,
        logo = null,
        logoText = "Kai",
        link = StremioKaiUrl,
    ),
    AttributionItem(
        searchKey = "anime4k-attribution",
        titleRes = Res.string.settings_licenses_attributions_anime4k_title,
        bodyRes = Res.string.settings_licenses_attributions_anime4k_body,
        logo = null,
        logoText = "MIT",
        link = Anime4kUrl,
    ),
    AttributionItem(
        searchKey = "trash-guides-attribution",
        titleRes = Res.string.settings_licenses_attributions_trash_title,
        bodyRes = Res.string.settings_licenses_attributions_trash_body,
        logo = null,
        logoText = "TRaSH",
        link = TrashGuidesUrl,
    ),
)

private fun nuvioAttributionItem(): AttributionItem =
    AttributionItem(
        searchKey = "nuvio-team",
        titleRes = Res.string.settings_licenses_attributions_nuvio_team_title,
        bodyRes = Res.string.settings_licenses_attributions_nuvio_team_body,
        logo = IntegrationLogo.Nuvio,
        link = NuvioContributeUrl,
    )

private fun appLicenseItem(): LicenseItem =
    LicenseItem(
        searchKey = "nuvio-license",
        titleRes = Res.string.settings_licenses_attributions_nuvio_title,
        bodyRes = Res.string.settings_licenses_attributions_nuvio_body,
        licenseRes = Res.string.settings_licenses_attributions_nuvio_license,
        link = NuvioRepositoryUrl,
    )

// The Windows player ships libmpv, FFmpeg and their dependencies from MSYS2, replacing the
// upstream MPVKit (iOS) / ExoPlayer (Android) entries that don't apply to this build.
private fun playbackLicenseItems(): List<LicenseItem> = listOf(
    LicenseItem(
        searchKey = "mpv-license",
        titleRes = Res.string.settings_licenses_attributions_mpv_title,
        bodyRes = Res.string.settings_licenses_attributions_mpv_body,
        licenseRes = Res.string.settings_licenses_attributions_mpv_license,
        link = MpvUrl,
    ),
    LicenseItem(
        searchKey = "ffmpeg-license",
        titleRes = Res.string.settings_licenses_attributions_ffmpeg_title,
        bodyRes = Res.string.settings_licenses_attributions_ffmpeg_body,
        licenseRes = Res.string.settings_licenses_attributions_ffmpeg_license,
        link = FfmpegUrl,
    ),
    LicenseItem(
        searchKey = "runtime-libraries-license",
        titleRes = Res.string.settings_licenses_attributions_runtime_libraries_title,
        bodyRes = Res.string.settings_licenses_attributions_runtime_libraries_body,
        licenseRes = Res.string.settings_licenses_attributions_runtime_libraries_license,
        link = Msys2PackagesUrl,
    ),
)

// Native and bundled code beyond the player; their licence texts ship in resources/licenses.
private fun bundledLibraryLicenseItems(): List<LicenseItem> = listOf(
    LicenseItem(
        searchKey = "whisper-license",
        titleRes = Res.string.settings_licenses_attributions_whisper_title,
        bodyRes = Res.string.settings_licenses_attributions_whisper_body,
        licenseRes = Res.string.settings_licenses_attributions_whisper_license,
        link = WhisperCppUrl,
    ),
    LicenseItem(
        searchKey = "libtorrent-license",
        titleRes = Res.string.settings_licenses_attributions_libtorrent_title,
        bodyRes = Res.string.settings_licenses_attributions_libtorrent_body,
        licenseRes = Res.string.settings_licenses_attributions_libtorrent_license,
        link = LibtorrentUrl,
    ),
    LicenseItem(
        searchKey = "quickjs-license",
        titleRes = Res.string.settings_licenses_attributions_quickjs_title,
        bodyRes = Res.string.settings_licenses_attributions_quickjs_body,
        licenseRes = Res.string.settings_licenses_attributions_quickjs_license,
        link = QuickJsKtUrl,
    ),
    LicenseItem(
        searchKey = "onnx-license",
        titleRes = Res.string.settings_licenses_attributions_onnx_title,
        bodyRes = Res.string.settings_licenses_attributions_onnx_body,
        licenseRes = Res.string.settings_licenses_attributions_onnx_license,
        link = OnnxRuntimeUrl,
    ),
    LicenseItem(
        searchKey = "fonts-license",
        titleRes = Res.string.settings_licenses_attributions_fonts_title,
        bodyRes = Res.string.settings_licenses_attributions_fonts_body,
        licenseRes = Res.string.settings_licenses_attributions_fonts_license,
        link = GoogleFontsUrl,
    ),
)
