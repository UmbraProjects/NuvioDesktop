package com.nuvio.app.core.deeplink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PartyDeepLinkTest {

    @Test
    fun `nuvio party links carry the code`() {
        assertEquals(AppDeepLink.Party("K7Q4MX"), parseAppDeepLink("nuvio://party/k7q4mx"))
        assertEquals(AppDeepLink.Party("K7Q4MX"), parseAppDeepLink("nuvio://party?code=K7Q-4MX"))
    }

    @Test
    fun `nuvio tv share links are invites`() {
        assertEquals(AppDeepLink.Party("K7Q4MX"), parseAppDeepLink("https://nuvio.tv/party/K7Q4MX"))
    }

    @Test
    fun `other links are not invites`() {
        assertNull(parseAppDeepLink("https://nuvio.tv/account"))
        assertNull(parseAppDeepLink("https://example.com/party/K7Q4MX"))
        assertNull(parseAppDeepLink("nuvio://party/ABC"))
    }

    @Test
    fun `existing links still parse`() {
        assertEquals(AppDeepLink.Meta(type = "series", id = "tt0944947"), parseAppDeepLink("nuvio://meta?type=series&id=tt0944947"))
        assertEquals(AppDeepLink.Downloads, parseAppDeepLink("nuvio://downloads"))
    }
}
