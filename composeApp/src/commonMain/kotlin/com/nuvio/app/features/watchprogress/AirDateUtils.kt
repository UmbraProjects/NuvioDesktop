package com.nuvio.app.features.watchprogress

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nuvio.app.core.format.formatReleaseDateWithoutYear
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The countdown shown on an Up Next card, recomputed on a ticker so it stays true while the app
 * sits open — this is an HTPC that runs for days, and the old text was fixed at whatever the date
 * was when the card was first composed.
 */
@Composable
fun computeAirDateBadgeText(
    releasedIso: String?,
    compact: Boolean,
): String? {
    val release = remember(releasedIso) { resolveReleaseInstant(releasedIso) } ?: return null

    return when (val countdown = rememberReleaseCountdown(release)) {
        null -> null
        is ReleaseCountdown.InMinutes ->
            if (compact) stringResource(Res.string.cw_airs_in_minutes_short, countdown.minutes)
            else pluralStringResource(Res.plurals.cw_airs_in_minutes, countdown.minutes, countdown.minutes)
        is ReleaseCountdown.InHours ->
            if (compact) stringResource(Res.string.cw_airs_in_hours_short, countdown.hours)
            else pluralStringResource(Res.plurals.cw_airs_in_hours, countdown.hours, countdown.hours)
        is ReleaseCountdown.InDays ->
            if (compact) pluralStringResource(Res.plurals.cw_airs_in_days_short, countdown.days, countdown.days)
            else pluralStringResource(Res.plurals.cw_airs_in_days, countdown.days, countdown.days)
        ReleaseCountdown.Today ->
            if (compact) stringResource(Res.string.cw_airs_today_short)
            else stringResource(Res.string.cw_airs_today)
        ReleaseCountdown.Tomorrow ->
            if (compact) stringResource(Res.string.cw_airs_tomorrow_short)
            else stringResource(Res.string.cw_airs_tomorrow)
        is ReleaseCountdown.OnDate -> {
            val formattedDate = formatReleaseDateWithoutYear(countdown.localIsoDate)
            if (compact) stringResource(Res.string.cw_airs_date_short, formattedDate)
            else stringResource(Res.string.cw_airs_date, formattedDate)
        }
    }
}

/**
 * Re-reads the clock on an interval that matches the unit on screen.
 *
 * The interval comes from the countdown itself rather than being fixed: a minutes badge has to
 * move every few seconds, a "In 3 days" badge only has to survive midnight.
 */
@Composable
private fun rememberReleaseCountdown(release: ReleaseInstant): ReleaseCountdown? {
    var countdown by remember(release) {
        mutableStateOf(
            releaseCountdown(
                release = release,
                nowMs = WatchProgressClock.nowEpochMs(),
                todayIsoDate = CurrentDateProvider.todayIsoDate(),
            ),
        )
    }

    LaunchedEffect(release) {
        while (true) {
            val current = releaseCountdown(
                release = release,
                nowMs = WatchProgressClock.nowEpochMs(),
                todayIsoDate = CurrentDateProvider.todayIsoDate(),
            )
            countdown = current
            delay(countdownRefreshIntervalMs(current))
        }
    }

    return countdown
}

/**
 * The instant an episode becomes available, in the viewer's timezone.
 *
 * A date-only value resolves to that day's *local* midnight, not UTC midnight — the old behaviour
 * fired "New Episode" five hours early in New York and an hour late in London.
 */
fun parseReleaseDateToEpochMs(raw: String?): Long? = resolveReleaseInstant(raw)?.epochMs

class ReleaseAlertState(
    val isReleaseAlert: Boolean,
    val isNewSeasonRelease: Boolean,
    /**
     * Why the badge was or was not awarded. Diagnostics only — never rendered.
     *
     * Every rule here is invisible from the outside: a missing badge and a suppressed one look
     * identical on the card, which made "it shows on mobile but not here" reports unfalsifiable.
     */
    val reason: String = "",
)

const val ReleaseAlertWindowMs = 60L * 24 * 60 * 60 * 1000

fun calculateReleaseAlertState(
    seedLastUpdatedEpochMs: Long,
    seedSeasonNumber: Int?,
    nextSeasonNumber: Int?,
    releasedIso: String?,
): ReleaseAlertState {
    if (releasedIso.isNullOrBlank()) {
        return ReleaseAlertState(false, false, "no-release-date")
    }

    val releaseEpoch = parseReleaseDateToEpochMs(releasedIso)
        ?: return ReleaseAlertState(false, false, "unparseable-release-date=$releasedIso")

    val nowMs = WatchProgressClock.nowEpochMs()
    if (nowMs < releaseEpoch) {
        return ReleaseAlertState(false, false, "not-aired-yet (air-date badge path instead)")
    }
    if (releaseEpoch <= seedLastUpdatedEpochMs) {
        return ReleaseAlertState(
            false,
            false,
            "seed-newer-than-release (watched the previous episode " +
                "${(seedLastUpdatedEpochMs - releaseEpoch) / 86_400_000L}d after this one aired)",
        )
    }
    if (nowMs - releaseEpoch >= ReleaseAlertWindowMs) {
        return ReleaseAlertState(
            false,
            false,
            "outside-60d-window (aired ${(nowMs - releaseEpoch) / 86_400_000L}d ago)",
        )
    }

    val isNewSeasonRelease =
        seedSeasonNumber != null &&
        nextSeasonNumber != null &&
        nextSeasonNumber != seedSeasonNumber

    return ReleaseAlertState(
        isReleaseAlert = true,
        isNewSeasonRelease = isNewSeasonRelease,
        reason = if (isNewSeasonRelease) {
            "NEW_SEASON (seed S$seedSeasonNumber -> next S$nextSeasonNumber)"
        } else {
            "NEW_EPISODE (aired ${(nowMs - releaseEpoch) / 86_400_000L}d ago)"
        },
    )
}
