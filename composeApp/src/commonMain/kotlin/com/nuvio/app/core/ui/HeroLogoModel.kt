package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable

/**
 * The image request for a hero title logo, with its transparent margins trimmed **at decode time**.
 *
 * Logo art arrives with whatever empty border its source gave it: some are cut tight, others carry
 * a band of transparent rows below or beside the lettering. The hero places a logo by its image
 * bounds, so two logos placed identically looked as if they sat at different heights (one resting
 * on the cast row, the next floating well above it). Trimmed, the bounds are the artwork, and the
 * placement means the same thing for every title.
 */
@Composable
internal expect fun rememberHeroLogoModel(logoUrl: String): Any
