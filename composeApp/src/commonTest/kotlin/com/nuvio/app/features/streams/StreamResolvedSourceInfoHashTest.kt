package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamResolvedSourceInfoHashTest {

    private fun stream(url: String?, infoHash: String? = null): StreamItem =
        StreamItem(url = url, infoHash = infoHash, addonName = "AIOStreams", addonId = "addon:aio")

    @Test
    fun `recovers infohash embedded in an AIOStreams torz path`() {
        val url = "https://st.myaio.xyz/stremio/torz/eyJzdG9yZXMi/_/strem/tt14367168:1:1/tb/" +
            "b7e7f03a174fdfda828cbb0f43d83886ecd652be/0/" +
            "Let%20the%20Right%20One%20In%20%282022%29%20-%20S01E01.mkv"

        assertEquals("b7e7f03a174fdfda828cbb0f43d83886ecd652be", stream(url).resolvedSourceInfoHash)
    }

    @Test
    fun `structured infohash still wins over the path`() {
        // p2pInfoHash is authoritative; only fall through to the path when it is absent.
        val url = "https://st.myaio.xyz/x/tb/b7e7f03a174fdfda828cbb0f43d83886ecd652be/0/name.mkv"
        val structured = "0bc6905c8b8efc521bd4511949367453e067e8db"

        assertEquals(structured, stream(url, infoHash = structured).resolvedSourceInfoHash)
    }

    @Test
    fun `plain debrid url with no hash segment stays null`() {
        val url = "https://store-042.torbox.app/download/12345/0/episode.mkv?token=abc"

        assertNull(stream(url).resolvedSourceInfoHash)
    }

    @Test
    fun `query and fragment are ignored when scanning for the hash`() {
        // A 40-hex value in the query must not be mistaken for a path segment.
        val url = "https://host/strem/tt1/tb/name.mkv?h=b7e7f03a174fdfda828cbb0f43d83886ecd652be"

        assertNull(stream(url).resolvedSourceInfoHash)
    }
}
