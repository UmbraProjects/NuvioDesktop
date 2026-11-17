package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stremio Usenet streams (nzbUrl + servers) are kept for the built-in Usenet engine. */
class StreamParserUsenetTest {
    @Test
    fun nzbStreamWithServersIsKept() {
        val payload = """{"streams":[{"name":"Usenet 1080p","nzbUrl":"https://indexer.example/get/abc.nzb",
            "servers":["nntps://u:p@news.example.com:563/20"],"fileMustInclude":"S01E02"}]}"""
        val stream = StreamParser.parse(payload, addonName = "Addon", addonId = "addon:a").single()
        assertEquals("https://indexer.example/get/abc.nzb", stream.nzbUrl)
        assertEquals(listOf("nntps://u:p@news.example.com:563/20"), stream.usenetServers)
        assertEquals("S01E02", stream.fileMustInclude)
        assertTrue(stream.needsUsenetPreparation)
        assertTrue(stream.hasPlayableSource)
    }

    @Test
    fun nzbStreamWithoutServersUsesTheUsersProviders() {
        val payload = """{"streams":[{"name":"Usenet","nzbUrl":"https://indexer.example/get/abc.nzb"}]}"""
        val stream = StreamParser.parse(payload, addonName = "Addon", addonId = "addon:a").single()
        assertNull(stream.usenetServers)
    }
}
