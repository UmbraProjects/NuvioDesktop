package com.nuvio.app.features.watchprogress

import androidx.compose.runtime.Composable
import com.nuvio.app.core.format.formatReleaseDateWithoutYear
import com.nuvio.app.features.watching.domain.daysUntilExplicitRelease
import com.nuvio.app.features.watching.domain.isoCalendarDateOrNull
import com.nuvio.app.features.trakt.parseTraktIsoDateTimeToEpochMs
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun computeAirDateBadgeText(
    releasedIso: String?,
    todayIsoDate: String,
    compact: Boolean
): String? {
    if (releasedIso.isNullOrBlank() || todayIsoDate.isBlank()) {
        return null
    }

    val releaseEpoch = parseTraktIsoDateTimeToEpochMs(releasedIso)
    if (releaseEpoch != null && WatchProgressClock.nowEpochMs() >= releaseEpoch) {
        return null
    }

    val daysUntil = daysUntilExplicitRelease(
        todayIsoDate = todayIsoDate,
        releasedDate = releasedIso,
    ) ?: return null

    return when {
        daysUntil < 0 -> null
        daysUntil == 0 -> {
            if (compact) stringResource(Res.string.cw_airs_today_short)
            else stringResource(Res.string.cw_airs_today)
        }
        daysUntil == 1 -> {
            if (compact) stringResource(Res.string.cw_airs_tomorrow_short)
            else stringResource(Res.string.cw_airs_tomorrow)
        }
        daysUntil in 2..7 -> {
            if (compact) pluralStringResource(Res.plurals.cw_airs_in_days_short, daysUntil, daysUntil)
            else pluralStringResource(Res.plurals.cw_airs_in_days, daysUntil, daysUntil)
        }
        else -> {
            val formattedDate = formatReleaseDateWithoutYear(releasedIso)
            if (compact) stringResource(Res.string.cw_airs_date_short, formattedDate)
            else stringResource(Res.string.cw_airs_date, formattedDate)
        }
    }
}

fun parseReleaseDateToEpochMs(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    val trimmed = raw.trim()
    val epochMs = parseTraktIsoDateTimeToEpochMs(trimmed)
    if (epochMs != null) return epochMs

    val datePart = isoCalendarDateOrNull(trimmed) ?: return null
    return parseTraktIsoDateTimeToEpochMs("${datePart}T00:00:00Z")
}

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
