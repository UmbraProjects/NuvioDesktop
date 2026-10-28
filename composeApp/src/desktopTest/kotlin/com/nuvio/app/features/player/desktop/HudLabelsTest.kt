package com.nuvio.app.features.player.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The HTML HUD names string resources by key (`data-i18n*` in controls.html, `hudText`/`hudFormat`/
 * `textKey` in controls.js) and Kotlin resolves only the keys listed in HudLabels.kt. A key the HUD
 * uses but the map lacks silently stays English, so the two lists are checked against each other.
 */
class HudLabelsTest {

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader.getResource("player-ui/$name")) { "missing player-ui/$name" }.readText()

    private fun referencedKeys(): Set<String> {
        val html = resource("controls.html")
        val js = resource("controls.js")
        return buildSet {
            Regex("""data-i18n(?:-[a-z-]+)?="([a-z0-9_]+)"""").findAll(html).forEach { add(it.groupValues[1]) }
            Regex("""hud(?:Text|Format)\("([a-z0-9_]+)"""").findAll(js).forEach { add(it.groupValues[1]) }
            Regex("""textKey: "([a-z0-9_]+)"""").findAll(js).forEach { add(it.groupValues[1]) }
            Regex("""\["[a-z]+", "[^"]+", "(player_hud_[a-z0-9_]+)"]""").findAll(js).forEach { add(it.groupValues[1]) }
        }
    }

    @Test
    fun everyKeyTheHudUsesIsResolvedByKotlin() {
        val missing = referencedKeys() - hudLabelKeys
        assertTrue(missing.isEmpty(), "HUD keys missing from HudLabels.kt: $missing")
    }

    @Test
    fun subtitleColourNamesAreResolved() {
        // describeSubtitleColor builds these keys from the colour name, so no literal names them.
        listOf("white", "gold", "cyan", "red", "green", "purple", "orange", "emerald", "blue", "black").forEach {
            assertTrue("player_hud_color_$it" in hudLabelKeys, "player_hud_color_$it")
        }
    }

    @Test
    fun labelsArePrependedAsValidJson() {
        val payload = """{"title":"Some Title"}""".withHudLabels()
        val root = Json.parseToJsonElement(payload).jsonObject
        assertEquals("Some Title", root.getValue("title").jsonPrimitive.content)
        val labels = root.getValue("hudLabels").jsonObject
        assertEquals("Seek back 10 seconds", labels.getValue("player_hud_seek_back_10_seconds").jsonPrimitive.content)
        assertEquals(hudLabelKeys, labels.keys)
        // Templates reach the HUD with their placeholders intact; hudFormat fills them in.
        assertEquals("Seek back %1\$s seconds", labels.getValue("player_hud_seek_back_1_s_seconds").jsonPrimitive.content)
        assertTrue(controlsJsonIsWellFormed("{}".withHudLabels()))
    }
}
