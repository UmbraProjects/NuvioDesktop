package com.nuvio.app.features.setup

import com.nuvio.app.core.build.AppVersionPolicy
import com.nuvio.app.core.ui.LargePosterCardWidthDp
import com.nuvio.app.core.ui.PosterCardStyleRepository
import com.nuvio.app.core.ui.PosterHighlightMode
import com.nuvio.app.features.details.MetaHeroTrailerPlaybackMode
import com.nuvio.app.features.details.MetaScreenBackgroundMode
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.home.DISCOVER_BECAUSE_ROWS_RANGE
import com.nuvio.app.features.home.DISCOVER_TRENDING_GENRE_ROWS_RANGE
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeDisplayMode
import com.nuvio.app.features.player.DesktopColorProfile
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.updater.AppUpdaterPlatform
import com.nuvio.app.features.updater.UpdateChannel
import com.nuvio.app.isDesktop

private const val HERO_TRAILER_DELAY_SECONDS = 5
private const val HERO_BADGE_COUNT = 6

/**
 * The experience a brand-new Nuvio HTPC installation starts in.
 *
 * The fork's own defaults, not upstream's: someone who has found this build has almost certainly
 * used Nuvio or Stremio already, so the first launch should show what this fork actually adds
 * rather than the neutral configuration they have seen before.
 *
 * **Fresh installations only.** Every value here is one an existing user may have deliberately left
 * alone, so applying them on upgrade would silently rearrange a working setup. The gate is the
 * persisted fresh-install answer behind [FirstRunWizardStorage.isEligible], plus a marker so the
 * pass runs exactly once even if the first session is killed before the wizard is finished.
 *
 * Note this is an *install*-level decision, not a profile-level one: creating a new profile resets
 * profile-scoped state but leaves the installation alone, so it does not re-run this.
 */
internal object NewInstallDefaults {

    fun applyIfNeeded() {
        if (!isDesktop) return
        if (!FirstRunWizardStorage.isEligible()) return
        if (FirstRunWizardStorage.defaultsApplied()) return
        FirstRunWizardStorage.markDefaultsApplied()
        apply()
    }

    private fun apply() {
        // Home
        HomeCatalogSettingsRepository.setDisplayMode(HomeDisplayMode.TvMode)
        HomeCatalogSettingsRepository.setTvRowDotsEnabled(true)
        HomeCatalogSettingsRepository.setHeroInfoLines(HERO_BADGE_COUNT)
        HomeCatalogSettingsRepository.setCatalogRowShuffleEnabled(true)
        HomeCatalogSettingsRepository.setDiscoverBecauseYouWatchedRows(DISCOVER_BECAUSE_ROWS_RANGE.last)
        HomeCatalogSettingsRepository.setDiscoverTrendingGenreRows(DISCOVER_TRENDING_GENRE_ROWS_RANGE.last)

        // Home hero trailer. In the hero rather than full screen, because the default display mode
        // is TV Mode; Adaptive Ambient is the mode whose trailers want the whole window.
        PlayerSettingsRepository.setHeroTvTrailerFullscreen(false)
        PlayerSettingsRepository.setHeroTvTrailerEnabled(true)
        PlayerSettingsRepository.setHeroTvTrailerDelaySeconds(HERO_TRAILER_DELAY_SECONDS)
        PlayerSettingsRepository.setHeroTvTrailerSoundEnabled(true)

        // Details screen
        MetaScreenSettingsRepository.setBackgroundMode(MetaScreenBackgroundMode.Cinematic)
        MetaScreenSettingsRepository.setHeroTrailerPlayback(true)
        MetaScreenSettingsRepository.setHeroTrailerPlaybackMode(MetaHeroTrailerPlaybackMode.Hero)
        MetaScreenSettingsRepository.setHeroTrailerDelaySeconds(HERO_TRAILER_DELAY_SECONDS)
        MetaScreenSettingsRepository.setHeroTrailerSoundEnabled(true)

        // Posters
        PosterCardStyleRepository.setWidthDp(LargePosterCardWidthDp)
        PosterCardStyleRepository.setPosterHighlightMode(PosterHighlightMode.Sweep)

        // Playback
        PlayerSettingsRepository.setDesktopPlaybackInfoPanelEnabled(true)
        PlayerSettingsRepository.setDesktopColorProfile(DesktopColorProfile.Cinematic)
        PlayerSettingsRepository.setStreamFailoverEnabled(true)

        // Updates follow whatever image was actually downloaded, so a nightly user is not silently
        // parked on the stable channel until they notice the picker. An unpackaged run (gradlew
        // run) has no stamp to read and is treated as stable, same as everywhere else.
        AppUpdaterPlatform.setUpdateChannel(
            if (AppVersionPolicy.packagedBuild?.isNightly == true) {
                UpdateChannel.Nightly
            } else {
                UpdateChannel.Stable
            },
        )

        // Already correct out of the box, listed so the intended default is recorded in one place:
        //  - Continue Watching source: ContinueWatchingSource.LOCAL ("Nuvio Sync").
        //  - Discord rich presence: DiscordPresenceMode.Disabled.
        //  - Always show top bar: seeded by ThemeSettingsStorage.loadDesktopTopBarAlwaysVisible.
    }
}
