package com.nuvio.app.features.iptv

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Stalker portals: everything that decides what is sent to a portal and what is made of its answers. */
class StalkerClientTest {

    private val mac = "00:1A:79:AB:CD:EF"
    private val portal = IptvPlaylist(id = "s", type = IptvPlaylistType.Stalker, name = "S", server = "http://stb.invalid:8080/c/", username = mac)

    @BeforeTest
    fun forgetHosts() = StalkerUrls.portalHosts.clear()

    private fun hash(algorithm: String, text: String) =
        MessageDigest.getInstance(algorithm).digest(text.toByteArray()).joinToString("") { "%02X".format(it) }

    private fun channel(name: String, uid: Int = 0) = IptvChannel(uid = uid, key = name, num = uid + 1, name = name, categoryId = "")

    private fun channels(body: String) = StalkerClient.readChannels(BufferedInputStream(ByteArrayInputStream(body.toByteArray())))

    @Test
    fun `endpoints are tried in order and a known one is used alone`() {
        assertEquals(
            listOf("http://h:80/portal.php", "http://h:80/server/load.php", "http://h:80/stalker_portal/server/load.php"),
            StalkerUrls.endpoints("http://h:80/c/"),
        )
        assertEquals(StalkerUrls.endpoints("http://h:80"), StalkerUrls.endpoints("http://h:80/"))
        assertEquals(listOf("http://h/stalker_portal/server/load.php"), StalkerUrls.endpoints("http://h/stalker_portal/c/"))
        assertEquals(listOf("http://h/stalker_portal/server/load.php"), StalkerUrls.endpoints("http://h/stalker_portal"))
        assertEquals(listOf("http://h/api/load.php"), StalkerUrls.endpoints("http://h/api/load.php"))
    }

    @Test
    fun `a bare portal address gets a scheme and an empty or broken one is refused`() {
        assertEquals("http://stb.invalid:8080", StalkerUrls.base(portal.copy(server = "stb.invalid:8080")))
        assertEquals(IptvSourceException.Kind.NoUrl, assertFailsWith<IptvSourceException> { StalkerUrls.base(portal.copy(server = " ")) }.kind)
        assertEquals(IptvSourceException.Kind.BadUrl, assertFailsWith<IptvSourceException> { StalkerUrls.base(portal.copy(server = "http://")) }.kind)
    }

    @Test
    fun `requests carry their parameters encoded and end with JsHttpRequest`() {
        val url = StalkerUrls.request("http://h/portal.php", "itv", "create_link", listOf("cmd" to "ffmpeg http://localhost/ch/1"))
        assertEquals("http://h/portal.php?type=itv&action=create_link&cmd=ffmpeg+http%3A%2F%2Flocalhost%2Fch%2F1&JsHttpRequest=1-xml", url)
        assertTrue(StalkerUrls.request("http://h/a.php?x=1", "stb", "handshake", emptyList()).startsWith("http://h/a.php?x=1&type=stb"))
    }

    @Test
    fun `referer is the portal's c folder`() {
        assertEquals("http://h/c/", StalkerUrls.referer("http://h/portal.php"))
        assertEquals("http://h/c/", StalkerUrls.referer("http://h/server/load.php"))
        assertEquals("http://h/stalker_portal/c/", StalkerUrls.referer("http://h/stalker_portal/server/load.php"))
    }

    @Test
    fun `stalker_portal cookies encode the MAC and leave the token out of get_profile`() {
        val endpoint = "http://h/stalker_portal/server/load.php"
        assertEquals("mac=00%3A1A%3A79%3AAB%3ACD%3AEF; stb_lang=en; timezone=Europe/Lisbon", StalkerUrls.cookie(mac, "", endpoint, "handshake", "Europe/Lisbon"))
        assertEquals("mac=00%3A1A%3A79%3AAB%3ACD%3AEF; stb_lang=en; timezone=UTC", StalkerUrls.cookie(mac, "tok", endpoint, "get_profile", "UTC"))
        assertEquals("mac=00%3A1A%3A79%3AAB%3ACD%3AEF; stb_lang=en; timezone=UTC; token=tok", StalkerUrls.cookie(mac, "tok", endpoint, "get_genres", "UTC"))
    }

    @Test
    fun `other portals keep the MAC as it is and always send the token`() {
        val endpoint = "http://h/portal.php"
        assertEquals("mac=$mac; stb_lang=en; timezone=UTC; token=tok", StalkerUrls.cookie(mac, "tok", endpoint, "get_profile", "UTC"))
        assertEquals("mac=$mac; stb_lang=en; timezone=UTC", StalkerUrls.cookie(mac, "", endpoint, "handshake", "UTC"))
    }

    @Test
    fun `MACs normalise from any usual form and anything else is not one`() {
        for (written in listOf("00:1a:79:ab:cd:ef", " 00-1A-79-AB-CD-EF ", "001a79abcdef", "00.1A.79.AB.CD.EF")) {
            assertEquals(mac, StalkerIdentity.normalizeMac(written), written)
        }
        for (wrong in listOf(null, "", "00:1A:79:AB:CD", "00:1A:79:AB:CD:EF:00", "GG:1A:79:AB:CD:EF")) {
            assertNull(StalkerIdentity.normalizeMac(wrong), wrong)
        }
    }

    @Test
    fun `a blank identity is derived from the MAC`() {
        val id = StalkerIdentity.of(portal.copy(username = "00-1a-79-ab-cd-ef", serial = " "))
        val serial = hash("MD5", mac).take(13)
        assertEquals(mac, id.mac)
        assertEquals(serial, id.serial)
        assertEquals(hash("SHA-256", mac), id.deviceId)
        assertEquals(hash("SHA-256", mac), id.deviceId2)
        assertEquals(hash("SHA-256", serial + mac), id.signature)
    }

    @Test
    fun `what the user typed wins over what is derived`() {
        val id = StalkerIdentity.of(portal.copy(serial = "SER", deviceId = "D1", deviceId2 = "D2", signature = "SIG"))
        assertEquals(listOf("SER", "D1", "D2", "SIG"), listOf(id.serial, id.deviceId, id.deviceId2, id.signature))
        assertEquals(hash("SHA-256", "SER$mac"), StalkerIdentity.of(portal.copy(serial = "SER")).signature)
    }

    @Test
    fun `without a valid MAC there is no identity`() {
        val error = assertFailsWith<IptvSourceException> { StalkerIdentity.of(portal.copy(username = "nope")) }
        assertEquals(IptvSourceException.Kind.BadMac, error.kind)
    }

    @Test
    fun `the profile request identifies a MAG250`() {
        val id = StalkerIdentity.of(portal)
        val params = id.profileParams(random = "abc", timestamp = 1700000000).toMap()
        assertEquals(id.serial, params["sn"])
        assertEquals("MAG250", params["stb_type"])
        assertEquals("STB", params["client_type"])
        assertEquals("1.7-BD-" + hash("MD5", mac).take(2), params["hw_version"])
        assertEquals(hash("MD5", (id.serial + mac).lowercase()).lowercase(), params["hw_version_2"])
        assertEquals("1700000000", params["timestamp"])
        assertEquals("abc", params["random"])
        assertEquals(
            """{"mac":"$mac","sn":"${id.serial}","model":"MAG250","type":"STB","uid":"","random":"abc"}""",
            params["metrics"],
        )
    }

    @Test
    fun `a status of 1 or a profile with no id is a bad login, whatever the handshake said`() {
        fun rejected(body: String) = StalkerIdentity.isRejectedProfile(Json.parseToJsonElement(body).jsonObject)
        assertTrue(StalkerIdentity.isRejectedProfile(null))
        assertTrue(rejected("""{"status": 1, "id": "5"}"""))
        assertTrue(rejected("""{"status": "1", "id": "5"}"""))
        assertTrue(rejected("""{"status": 0, "id": null}"""))
        assertTrue(rejected("""{"status": 0}"""))
        assertFalse(rejected("""{"status": 0, "id": "5"}"""))
        assertFalse(rejected("""{"id": 5}"""))
    }

    @Test
    fun `a command's URL is its last part, pointed at the portal when it names no real host`() {
        fun url(command: String) = StalkerUrls.commandUrl(command, portal)
        assertEquals("http://cdn.example.net/live/1.ts", url("ffmpeg http://cdn.example.net/live/1.ts"))
        assertEquals("http://cdn.example.net/live/1.ts", url("http://cdn.example.net/live/1.ts"))
        assertEquals("http://stb.invalid:8080/ch/1_", url("ffmpeg http://localhost/ch/1_"))
        assertEquals("http://stb.invalid:8080/ch/1?a=b", url("auto http://127.0.0.1:88/ch/1?a=b"))
        assertEquals("http://stb.invalid:8080/ch/1", url("ffmpeg http:///ch/1"))
        assertEquals("http://stb.invalid:8080/ch/1", url("/ch/1"))
    }

    @Test
    fun `only ch commands ask the portal for a link`() {
        assertTrue(StalkerUrls.needsLink("ffmpeg http://localhost/ch/1_"))
        assertFalse(StalkerUrls.needsLink("ffmpeg http://cdn.example.net/live/1.ts"))
        assertFalse(StalkerUrls.needsLink(null))
    }

    @Test
    fun `m3u8 is HLS and anything else is MPEG-TS`() {
        val hls = StalkerUrls.candidate("ffmpeg http://cdn.example.net/live/1.m3u8?t=1", portal)
        assertEquals(IptvStreamFormat.HLS, hls.format)
        assertEquals("http://cdn.example.net/live/1.m3u8?t=1", hls.url)
        assertEquals(IptvStreamFormat.TS, StalkerUrls.candidate("ffmpeg http://cdn.example.net/live.php?stream=1", portal).format)
        assertEquals(IptvStreamFormat.TS, StalkerUrls.candidate("http://cdn.example.net/1.ts", portal).format)
    }

    @Test
    fun `a Stalker channel has one candidate, whatever format is preferred`() {
        val channel = channel("News").copy(url = "ffmpeg http://cdn.example.net/live/1.ts")
        val candidates = iptvStreamCandidates(portal, channel, IptvStreamFormat.HLS)
        assertEquals(listOf(IptvStreamCandidate("http://cdn.example.net/live/1.ts", IptvStreamFormat.TS)), candidates)
        assertFalse(IptvPlaybackSession.needsLink(portal, channel))
        assertTrue(IptvPlaybackSession.needsLink(portal, channel.copy(url = "ffmpeg http://localhost/ch/1_")))
    }

    @Test
    fun `divider names are found by their edges`() {
        assertEquals("SPORT", StalkerDividers.title("✦●✦ SPORT ✦●✦"))
        assertEquals("UK", StalkerDividers.title("=== UK ==="))
        assertEquals("UK - HD", StalkerDividers.title("  == UK - HD ==  "))
        assertNull(StalkerDividers.title("BBC One"))
        assertNull(StalkerDividers.title("[VIP] Sport [HD]"))
        assertNull(StalkerDividers.title("(( Sport ))"))
        assertNull(StalkerDividers.title("== UK (HD) =="))
        assertNull(StalkerDividers.title("*Sport*"))
        assertNull(StalkerDividers.title("== Sport"))
        assertNull(StalkerDividers.title("Sport =="))
        assertNull(StalkerDividers.title("-----"))
        assertNull(StalkerDividers.title(""))
    }

    @Test
    fun `dividers become groups and stop being channels`() {
        val list = listOf("First", "✦●✦ SPORT ✦●✦", "Sport 1", "Sport 2", "=== EMPTY ===", "=== NEWS ===", "News 1").mapIndexed { i, n -> channel(n, i) }
        val (grouped, categories) = StalkerDividers.group(list)
        assertEquals(listOf("First", "Sport 1", "Sport 2", "News 1"), grouped.map { it.name })
        assertEquals(listOf(0, 1, 2, 3), grouped.map { it.uid })
        assertEquals(listOf("", "SPORT", "SPORT", "NEWS"), grouped.map { it.categoryId })
        assertEquals(listOf("SPORT", "EMPTY", "NEWS"), categories.map { it.name })

        val content = IptvPlaylistContent.build(portal, categories, grouped)
        assertEquals(listOf("SPORT", "NEWS", IptvPlaylistContent.OTHER_CATEGORY), content.categories.map { it.id })
    }

    @Test
    fun `a list with fewer than two dividers stays flat`() {
        val one = listOf("=== UK ===", "BBC One").mapIndexed { i, n -> channel(n, i) }
        assertEquals(one to emptyList(), StalkerDividers.group(one))
        val none = listOf("BBC One", "BBC Two").mapIndexed { i, n -> channel(n, i) }
        assertEquals(none to emptyList(), StalkerDividers.group(none))
    }

    @Test
    fun `channel lists read from js or js data, with loose types`() {
        val direct = channels("""{"js": [{"id": 7, "name": "One", "number": "3", "cmd": "c", "tv_genre_id": 2, "extra": {"a": [1]}}]}""")
        assertEquals("7", direct.data.single().id)
        assertEquals("2", direct.data.single().genreId)
        val paged = channels("""{"text": "ok", "js": {"total_items": "50", "max_page_items": 14, "data": [{"id": "8", "cmds": [{"url": "u"}]}]}}""")
        assertEquals("50", paged.totalItems)
        assertEquals("14", paged.maxPageItems)
        assertEquals("8", paged.data.single().id)
    }

    @Test
    fun `an empty channel list may be false, null or an empty string`() {
        for (body in listOf("""{"js": false}""", """{"js": null}""", """{"js": ""}""", """{"js": []}""", """{"js": {"data": null}}""")) {
            assertTrue(channels(body).data.isEmpty(), body)
        }
    }

    @Test
    fun `a body with no js is a bad response`() {
        for (body in listOf("<html>Forbidden</html>", "", """{"js": {"data": "nope"}}""")) {
            assertEquals(IptvSourceException.Kind.BadResponse, assertFailsWith<IptvSourceException>(body) { channels(body) }.kind)
        }
    }

    @Test
    fun `saved playlists from before Stalker still load, and new ones keep their fields`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = """{"playlists": [{"id": "x", "type": "Xtream", "name": "N", "server": "http://h", "username": "u", "password": "p"}]}"""
        val settings = json.decodeFromString(IptvSettings.serializer(), old)
        assertEquals(IptvPlaylistType.Xtream, settings.playlists.single().type)
        assertNull(settings.playlists.single().serial)

        val saved = portal.copy(serial = "SER", deviceId = "D1", deviceId2 = "D2", signature = "SIG")
        assertEquals(saved, json.decodeFromString(IptvPlaylist.serializer(), json.encodeToString(IptvPlaylist.serializer(), saved)))
    }

    @Test
    fun `the MAC, device identity, token and portal never reach a log`() {
        StalkerUrls.base(portal.copy(server = "http://secret-portal.invalid:8080/c/"))
        val redacted = redactIptvCredentials(
            "http://secret-portal.invalid:8080/server/load.php?type=stb&action=get_profile&sn=ABC123&device_id=DEV1&device_id2=DEV2" +
                "&signature=SIG9&hw_version_2=HW2&metrics=%7Bx%7D&token=TOK7 mac=$mac; token=TOK8 " +
                "http://cdn.example.net/live.php?mac=00%3A1A%3A79%3AAB%3ACD%3AEF&stream=1&play_token=PLAY " +
                "failed to connect to secret-portal.invalid/10.0.0.1 AA-BB-CC-DD-EE-FF",
        )
        for (secret in listOf("secret-portal", "ABC123", "DEV1", "DEV2", "SIG9", "HW2", "%7Bx%7D", "TOK7", "TOK8", "PLAY", "1A", "AA-BB")) {
            assertFalse(secret in redacted, "$secret in $redacted")
        }
        assertTrue("stream=1" in redacted, redacted)
    }

    @Test
    fun `a direct link with a login in its path is masked whole, on the portal or elsewhere`() {
        StalkerUrls.base(portal.copy(server = "http://secret-portal.invalid:80/c/"))
        val onPortal = redactIptvCredentials("sourceUrl=http://secret-portal.invalid:80/USER1/PASS1/30647 next")
        assertEquals("sourceUrl=*** next", onPortal)

        StalkerUrls.candidate("ffmpeg http://cdn-secret.invalid/USER2/PASS2/1.ts", portal)
        assertEquals("sourceUrl=*** host=***", redactIptvCredentials("sourceUrl=http://cdn-secret.invalid/USER2/PASS2/1.ts host=cdn-secret.invalid"))
    }

    @Test
    fun `redaction leaves ordinary words alone`() {
        assertEquals("kind=Network http=null unsigned=1 design=x", redactIptvCredentials("kind=Network http=null unsigned=1 design=x"))
    }
}
