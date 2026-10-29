package com.nuvio.app.features.preroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BbfcAdvisoryTest {
    @Test
    fun readsTheShortFormInsightWhateverTheClassHash() {
        val html = """<div class="Release_Rating-12__2FxNW Release_ShortFormInsight__3qRUw">moderate threat, occasional bloody moments, action violence</div>"""
        assertEquals("moderate threat, occasional bloody moments, action violence", parseBbfcShortFormInsight(html))
        val rehashed = """<p class="x Release_ShortFormInsight__zzz9"> strong <b>bloody</b> violence &amp; gore </p>"""
        assertEquals("strong bloody violence & gore", parseBbfcShortFormInsight(rehashed))
    }

    @Test
    fun noInsightElementIsNull() {
        assertNull(parseBbfcShortFormInsight("<div class=\"Release_Rating-18__abc\">18</div>"))
        assertNull(parseBbfcShortFormInsight("""<div class="Release_ShortFormInsight__a">  </div>"""))
    }

    @Test
    fun formatsLikeTheCinemaCard() {
        assertEquals("Contains Strong Bloody Violence and Gore", formatBbfcAdvisory("strong bloody violence and gore"))
        assertEquals(
            "Contains Moderate Threat, Occasional Bloody Moments, Action Violence",
            formatBbfcAdvisory("moderate threat, occasional bloody moments, action violence"),
        )
        assertEquals("Contains Mild Threat", formatBbfcAdvisory("Contains mild threat"))
    }

    @Test
    fun picksTheReleaseForTheRightYear() {
        val html = """
            <a href="/release/halloween-abc">Halloween</a><span>1978</span>
            <a href="/release/halloween-2018-def">Halloween</a><span>2018</span>
            <a href="/release/halloween-kills-ghi">Halloween Kills</a><span>2021</span>
        """.trimIndent()
        assertEquals("/release/halloween-2018-def", findBbfcReleasePath(html, "Halloween", "2018"))
        assertEquals("/release/halloween-abc", findBbfcReleasePath(html, "Halloween", null))
        assertNull(findBbfcReleasePath(html, "Halloween", "1999"))
        assertNull(findBbfcReleasePath(html, "Scream", null))
    }

    @Test
    fun readsTheAdvisoryFromTheSearchResultCard() {
        val html = """
            <a href="https://www.bbfc.co.uk/release/everything-everywhere-all-at-once-q29sbgvjdglvbjpwwc0xmdaznzg0" class="SearchResultItem_Container__13UK_ SearchResultItem_ReleaseContainer__4_wH6"><div class="SearchResultItem_Poster__z4C-H"><img alt="Everything Everywhere All At Once (2022)"></div><div class="SearchResultItem_DesktopContentWrap__Ue1t3"><div><h2 class="SearchResultItem_Title__3lgj-">Everything Everywhere All At Once (2022)</h2><div><p class="SearchResultItem_Description__2DA4X SearchResultItem_Rating-15__1wllI">strong violence, sex references, language</p></div></div><p class="SearchResultItem_Type__2JSdc">Film</p></div></a>
        """.trimIndent()
        val card = findBbfcSearchCard(html, "Everything Everywhere All at Once", "2022")
        assertEquals("/release/everything-everywhere-all-at-once-q29sbgvjdglvbjpwwc0xmdaznzg0", card?.path)
        assertEquals("strong violence, sex references, language", card?.insight)
        assertNull(findBbfcSearchCard(html, "Everything Everywhere All at Once", "1999"))
        assertNull(findBbfcSearchCard(html, "Scream", null))
    }
}
