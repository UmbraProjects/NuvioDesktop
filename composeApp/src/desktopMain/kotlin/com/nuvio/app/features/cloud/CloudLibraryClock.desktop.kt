package com.nuvio.app.features.cloud

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

internal actual object CloudLibraryClock {
    actual fun nowEpochMs(): Long = System.currentTimeMillis()

    actual fun parseIsoDateTimeToEpochMs(value: String): Long? {
        val normalized = value.trim().takeIf { it.isNotBlank() } ?: return null
        parse { Instant.parse(normalized).toEpochMilli() }?.let { return it }
        parse { OffsetDateTime.parse(normalized).toInstant().toEpochMilli() }?.let { return it }
        // No offset at all: TorBox's usenet/webdl rows come back as a bare Python isoformat
        // timestamp, which is UTC in practice.
        return parse { LocalDateTime.parse(normalized).toInstant(ZoneOffset.UTC).toEpochMilli() }
    }

    private inline fun parse(block: () -> Long): Long? =
        try {
            block()
        } catch (_: DateTimeParseException) {
            null
        }
}
