package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

internal enum class IntegrationLogo {
    Nuvio,
    Tmdb,
    Trakt,
    MdbList,
    IntroDb,
    Tvdb,
    Simkl,
    Kitsu,
}

@Composable
internal expect fun integrationLogoPainter(logo: IntegrationLogo): Painter
