package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertTrue

class StreamMetadataRequestHeadersTest {

    @Test
    fun requestsAioStreamsStructuredMetadataWithoutDependingOnItsFormatter() {
        val userAgent = STREAM_METADATA_REQUEST_HEADERS.entries
            .firstOrNull { (name, _) -> name.equals("User-Agent", ignoreCase = true) }
            ?.value
            .orEmpty()

        assertTrue("AIOStreams/" in userAgent)
    }
}
