package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ExternalPlayerDiagnosticsTest {

    @Test
    fun sourceSummaryKeepsOnlyOrigin() {
        val source = "https://media.example.test:8443/private/token/video.mkv?api_key=secret"

        val summary = externalPlayerSourceSummary(source)

        assertEquals("https://media.example.test:8443", summary)
        assertFalse(summary.contains("token"))
        assertFalse(summary.contains("secret"))
    }

    @Test
    fun sourceSummaryDoesNotExposeLocalPaths() {
        assertEquals("file:(no-host)", externalPlayerSourceSummary("file:///C:/private/video.mkv"))
    }

    @Test
    fun malformedSourceIsReportedWithoutEchoingIt() {
        assertEquals("invalid-uri", externalPlayerSourceSummary("https://bad host/?token=secret"))
    }
}
