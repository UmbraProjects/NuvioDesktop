package com.nuvio.app.features.watchprogress

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

actual object CurrentDateProvider {
    actual fun todayIsoDate(): String = LocalDate.now().toString()

    actual fun localIsoDateAt(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    // atStartOfDay(zone) rather than atStartOfDay().atZone(zone): on a spring-forward date the
    // former returns the first instant that actually exists, the latter shifts by an hour.
    actual fun startOfLocalDayEpochMs(isoDate: String): Long? = runCatching {
        LocalDate.parse(isoDate).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()
}
