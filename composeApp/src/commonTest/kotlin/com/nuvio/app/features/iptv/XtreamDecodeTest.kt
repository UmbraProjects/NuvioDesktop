package com.nuvio.app.features.iptv

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Gson → kotlinx.serialization rewrite of GoatTV's Xtream models: servers are loose with JSON types. */
class XtreamDecodeTest {

    private fun <T> list(body: String, serializer: kotlinx.serialization.KSerializer<T>) =
        XtreamClient.decodeList(ByteArrayInputStream(body.toByteArray()), serializer)

    @Test
    fun `numbers and strings both read as text`() {
        val streams = list(
            """
            [
              {"num": 1, "name": "One", "stream_id": 101, "category_id": "5", "tv_archive": 1, "tv_archive_duration": "7"},
              {"num": "2", "name": "Two", "stream_id": "102", "category_id": 5, "epg_channel_id": null, "extra": {"a": [1]}}
            ]
            """.trimIndent(),
            XtreamStream.serializer(),
        )
        assertEquals(listOf("101", "102"), streams.map { it.streamId })
        assertEquals(listOf("5", "5"), streams.map { it.categoryId })
        assertEquals("1", streams[0].tvArchive)
        assertNull(streams[1].epgChannelId)
    }

    @Test
    fun `an object or array where a scalar belongs reads as empty`() {
        val streams = list("""[{"stream_id": 7, "name": {"en": "x"}, "stream_icon": []}]""", XtreamStream.serializer())
        assertEquals("", streams.single().name)
        assertEquals("", streams.single().streamIcon)
    }

    @Test
    fun `empty lists sent as null, object or nothing are empty`() {
        assertTrue(list("null", XtreamCategory.serializer()).isEmpty())
        assertTrue(list("  {}", XtreamCategory.serializer()).isEmpty())
        assertTrue(list("", XtreamCategory.serializer()).isEmpty())
        assertTrue(list("﻿[]", XtreamCategory.serializer()).isEmpty())
    }

    @Test
    fun `an html error page is a bad response`() {
        val error = assertFailsWith<IptvSourceException> { list("<html>Forbidden</html>", XtreamCategory.serializer()) }
        assertEquals(IptvSourceException.Kind.BadResponse, error.kind)
    }

    @Test
    fun `allowed output formats as list or string`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val asList = json.decodeFromString(XtreamLogin.serializer(), """{"user_info": {"auth": 1, "allowed_output_formats": ["m3u8", "TS"]}}""")
        assertEquals(setOf("m3u8", "ts"), asList.userInfo!!.allowedFormats())
        assertEquals("1", asList.userInfo!!.auth)
        val asString = json.decodeFromString(XtreamLogin.serializer(), """{"user_info": {"allowed_output_formats": "ts, m3u8"}}""")
        assertEquals(setOf("ts", "m3u8"), asString.userInfo!!.allowedFormats())
    }

    @Test
    fun `failure descriptions never carry credentials`() {
        val p = IptvPlaylist(id = "x", type = IptvPlaylistType.Xtream, name = "x", server = "http://h:80", username = "me", password = "s3cret")
        val api = XtreamUrls.api(p, "get_live_streams")
        val stream = XtreamUrls.streamUrl(p, "7", "ts")
        val text = describeIptvFailure(java.io.IOException("failed $api and $stream", RuntimeException("again $api")))
        assertTrue("s3cret" !in text && "username=me" !in text && "/me/" !in text, text)
        assertTrue("username=***" in text && "/live/***/***/7.ts" in text, text)
        assertTrue("s3cret" !in RuntimeException("x $stream").asIptvError().message!!)
    }

    @Test
    fun `connect failures read as unreachable, others do not`() {
        fun wrap(cause: Throwable) = cause.asIptvError()
        assertTrue(wrap(java.net.SocketTimeoutException("Connect timed out")).isUnreachable())
        assertTrue(wrap(java.net.ConnectException("Connection refused")).isUnreachable())
        assertTrue(!wrap(java.net.SocketTimeoutException("Read timed out")).isUnreachable())
        assertTrue(!wrap(java.net.UnknownHostException("nope.example")).isUnreachable())
    }

    @Test
    fun `server urls are validated`() {
        val p = IptvPlaylist(id = "x", type = IptvPlaylistType.Xtream, name = "x")
        assertEquals(IptvSourceException.Kind.NoUrl, assertFailsWith<IptvSourceException> { XtreamUrls.base(p) }.kind)
        assertEquals(IptvSourceException.Kind.BadUrl, assertFailsWith<IptvSourceException> { XtreamUrls.base(p.copy(server = "http://")) }.kind)
        assertEquals("https://host.tv", XtreamUrls.base(p.copy(server = " https://host.tv/ ")))
    }
}
