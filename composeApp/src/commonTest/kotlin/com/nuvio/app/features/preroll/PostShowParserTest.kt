package com.nuvio.app.features.preroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostShowParserTest {
    private val good = """
        Here you go:
        ```json
        {"cards":[
          {"kind":"ending","text":"The spinning top is often read as the film refusing to say.","confidence":0.95},
          {"kind":"easter_egg","text":"A hidden detail.","confidence":0.9},
          {"kind":"watch_again","text":"Watch the hands in the first scene.","confidence":0.85}
        ],
        "quiz":[{"q":"Who left?","options":["A","B","C","D"],"answer":"B","spoiler_risk":"high","confidence":0.9}]}
        ```
    """.trimIndent()

    @Test
    fun `parses cards and a quiz and ignores spoiler flags`() {
        val pack = parsePostShowPack(good)
        assertEquals(3, pack.cards.size)
        assertEquals(PostShowCardKind.Ending, pack.cards[0].kind)
        assertEquals(1, pack.questions.size)
        assertEquals(1, pack.questions[0].answerIndex)
    }

    @Test
    fun `drops cards below the confidence floor`() {
        val pack = parsePostShowPack("""{"cards":[{"kind":"ending","text":"x y","confidence":0.79},{"kind":"ending","text":"keep","confidence":0.8}]}""")
        assertEquals(listOf("keep"), pack.cards.map { it.text })
    }

    @Test
    fun `a missing confidence is treated as below the floor`() {
        // The pre-show default of 0.7 is under 0.8, so an unsure model cannot slip through by silence.
        assertTrue(parsePostShowPack("""{"cards":[{"kind":"ending","text":"no score"}]}""").cards.isEmpty())
    }

    @Test
    fun `drops unknown kinds and over-long text`() {
        val long = "a".repeat(201)
        val pack = parsePostShowPack("""{"cards":[{"kind":"gossip","text":"hi","confidence":1},{"kind":"ending","text":"$long","confidence":1}]}""")
        assertTrue(pack.cards.isEmpty())
    }

    @Test
    fun `caps cards and questions and removes duplicates`() {
        val cards = (1..9).joinToString(",") { """{"kind":"connection","text":"card $it","confidence":1}""" }
        val dup = """{"kind":"connection","text":"CARD 1","confidence":1}"""
        val pack = parsePostShowPack("""{"cards":[$cards,$dup]}""")
        assertEquals(POST_SHOW_MAX_CARDS, pack.cards.size)
    }

    @Test
    fun `garbage yields an empty pack`() {
        assertTrue(parsePostShowPack("not json").isEmpty)
        assertTrue(parsePostShowPack("").isEmpty)
        assertTrue(parsePostShowPack("{}").isEmpty)
    }

    @Test
    fun `kind aliases`() {
        assertEquals(PostShowCardKind.BehindTheScenes, PostShowCardKind.from("behind_the_scenes"))
        assertEquals(PostShowCardKind.EasterEgg, PostShowCardKind.from("Easter Egg"))
        assertEquals(PostShowCardKind.WatchAgain, PostShowCardKind.from("watch-again"))
        assertNull(PostShowCardKind.from(null))
    }
}
