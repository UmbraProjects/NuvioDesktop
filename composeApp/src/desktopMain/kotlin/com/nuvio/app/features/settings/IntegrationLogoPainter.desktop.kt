package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.introdb_favicon
import nuvio.composeapp.generated.resources.kitsu_logo
import nuvio.composeapp.generated.resources.mdblist_logo
import nuvio.composeapp.generated.resources.nuvio_app_icon
import nuvio.composeapp.generated.resources.rating_tmdb
import nuvio.composeapp.generated.resources.simkl_logo
import nuvio.composeapp.generated.resources.trakt_tv_favicon
import nuvio.composeapp.generated.resources.tvdb_logo
import org.jetbrains.compose.resources.painterResource

@Composable
internal actual fun integrationLogoPainter(logo: IntegrationLogo): Painter =
    painterResource(
        when (logo) {
            IntegrationLogo.Nuvio -> Res.drawable.nuvio_app_icon
            IntegrationLogo.Tmdb -> Res.drawable.rating_tmdb
            IntegrationLogo.Trakt -> Res.drawable.trakt_tv_favicon
            IntegrationLogo.MdbList -> Res.drawable.mdblist_logo
            IntegrationLogo.IntroDb -> Res.drawable.introdb_favicon
            IntegrationLogo.Tvdb -> Res.drawable.tvdb_logo
            IntegrationLogo.Simkl -> Res.drawable.simkl_logo
            IntegrationLogo.Kitsu -> Res.drawable.kitsu_logo
        },
    )
