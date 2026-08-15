package com.nuvio.app.features.settings

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSettingsBackupTest {
    @Test
    fun removesCredentialKeysAtPropertyAndNestedJsonLevels() {
        val source = Properties().apply {
            setProperty("theme", "dark")
            setProperty("profile_1|api_key", "secret")
            setProperty("provider_settings", """{"baseUrl":"https://example.test","apiToken":"hidden"}""")
            setProperty(
                "installed_addon_urls_1",
                """["https://public.test/manifest.json","https://configured.test/realdebrid=hidden/manifest.json"]""",
            )
        }

        val sanitized = credentialFreeSettingsProperties(source)

        assertEquals("dark", sanitized.getProperty("theme"))
        assertFalse(sanitized.containsKey("profile_1|api_key"))
        assertTrue(sanitized.getProperty("provider_settings").contains("baseUrl"))
        assertFalse(sanitized.getProperty("provider_settings").contains("hidden"))
        assertTrue(sanitized.getProperty("installed_addon_urls_1").contains("public.test"))
        assertFalse(sanitized.getProperty("installed_addon_urls_1").contains("configured.test"))
    }

    @Test
    fun `configured addon urls are dropped whatever shape the credential takes`() {
        // The denylist can only name credential shapes someone anticipated. These are the ones it
        // never did: userinfo, an opaque path token, and a provider parameter not on the list.
        val source = Properties().apply {
            setProperty(
                "installed_addon_urls_1",
                """[
                    "https://public.test/manifest.json",
                    "https://user:pass@userinfo.test/manifest.json",
                    "https://opaque.test/a1b2c3d4e5f6/manifest.json",
                    "https://query.test/manifest.json?session=abc123",
                    "https://provider.test/newdebrid=hidden/manifest.json"
                ]""".trimIndent(),
            )
        }

        val urls = credentialFreeSettingsProperties(source).getProperty("installed_addon_urls_1")

        assertTrue(urls.contains("public.test"))
        assertFalse(urls.contains("userinfo.test"))
        assertFalse(urls.contains("opaque.test"))
        assertFalse(urls.contains("query.test"))
        assertFalse(urls.contains("provider.test"))
    }

    @Test
    fun `configured urls used as json keys are dropped too`() {
        // addon_enabled_states_<profile> is a map keyed by manifest URL, so the credential is the
        // key rather than the value.
        val source = Properties().apply {
            setProperty(
                "addon_enabled_states_1",
                """{"https://public.test/manifest.json":true,"https://cfg.test/tok3n/manifest.json":false}""",
            )
        }

        val states = credentialFreeSettingsProperties(source).getProperty("addon_enabled_states_1")

        assertTrue(states.contains("public.test"))
        assertFalse(states.contains("cfg.test"))
    }

    @Test
    fun `plain hosts and unconfigured manifests survive`() {
        assertTrue(isCredentialFreeUrl("https://public.test/manifest.json"))
        assertTrue(isCredentialFreeUrl("http://127.0.0.1:8090"))
        assertTrue(isCredentialFreeUrl("https://public.test/"))
        assertFalse(isCredentialFreeUrl("https://user@host.test/manifest.json"))
        assertFalse(isCredentialFreeUrl("https://host.test/deeper/manifest.json"))
        assertFalse(isCredentialFreeUrl("https://host.test/manifest.json?key=abc"))
        // Unparseable input cannot be cleared, so it is treated as configured.
        assertFalse(isCredentialFreeUrl("https://host.test/ not a url"))
    }
}
