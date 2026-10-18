package com.nuvio.app.features.details.filler

import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pairing against AnimeFillerList, on excerpts of the real data (AnimeFillerList pages, anime-kitsu
 * and Cinemeta metas, fetched 2026-10-18). Each excerpt holds a trap that a simpler rule fell into
 * when the full lists were measured.
 */
class AnimeFillerMatchingTest {
    @Test
    fun `parses the episode table with categories, dates and entities`() {
        val html = """
            <div class="filler"><span class="Label">Filler Episodes:</span><span class="Episodes">26</span></div>
            <table class="EpisodeList">
             <thead><tr><th>#</th><th>Title</th><th>Type</th><th class="Date">Airdate</th> </tr></thead>
            <tbody>
             <tr class="mixed_canon/filler odd" id="eps-5"><td class="Number">5</td><td class="Title"><a href="/shows/naruto/x">You Failed! Kakashi&#039;s Final Decision</a></td><td class="Type"><span>Mixed Canon/Filler</span></td><td class="Date">2002-10-31</td> </tr>
             <tr class="filler even" id="eps-26"><td class="Number">26</td><td class="Title"><a href="/shows/naruto/y">Special Report: Live from the Forest of Death!</a></td><td class="Type"><span>Filler</span></td><td class="Date">2003-04-09</td> </tr>
             <tr class="anime_canon odd" id="eps-27"><td class="Number">27</td><td class="Title">A &amp; B</td><td class="Type"><span>Anime Canon</span></td><td class="Date"></td> </tr>
            </tbody></table>
        """.trimIndent()
        val rows = parseAnimeFillerListPage(html)
        assertEquals(
            listOf(
                AnimeFillerListRow(5, "You Failed! Kakashi's Final Decision", AnimeEpisodeKind.MixedCanonFiller, "2002-10-31"),
                AnimeFillerListRow(26, "Special Report: Live from the Forest of Death!", AnimeEpisodeKind.Filler, "2003-04-09"),
                AnimeFillerListRow(27, "A & B", AnimeEpisodeKind.AnimeCanon, null),
            ),
            rows,
        )
    }

    @Test
    fun `a page without the episode table yields nothing`() {
        assertEquals(emptyList(), parseAnimeFillerListPage("<html><body>Page not found</body></html>"))
    }

    @Test
    fun `One Piece 683-689 pair by title although the page dates them a week or two early`() {
        // Pairing by "the only row aired within a day" sent 684 to 686 and so on through 689.
        val matches = matchAnimeFillerRows(onePieceKitsu, onePieceRows)
        onePieceKitsu.forEach { video ->
            matches[video.id]?.let { assertEquals(video.episode, it.row.number, video.id) }
        }
        (683..689).forEach { episode ->
            assertEquals(episode, matches["kitsu:12:$episode"]?.row?.number, "kitsu:12:$episode")
        }
    }

    @Test
    fun `Naruto 13 pairs with 13 although Kitsu dates it five days late`() {
        // The date rule alone put Haku's Secret Jutsu on 17 and White Past on 18.
        val matches = matchAnimeFillerRows(narutoKitsu, narutoRows)
        assertEquals(narutoKitsu.size, matches.size)
        narutoKitsu.forEach { video -> assertEquals(video.episode, matches.getValue(video.id).row.number, video.id) }
        assertEquals(AnimeEpisodeKind.MixedCanonFiller, matches.getValue("kitsu:11:9").row.kind)
    }

    @Test
    fun `a truncated title only competes with rows aired near it`() {
        // Cinemeta names Bleach 114 exactly as episode 41; both must land on their own rows.
        val matches = matchAnimeFillerRows(bleachImdb, bleachEarlyRows + bleachLateRows)
        assertEquals(41, matches["tt0434665:2:21"]?.row?.number)
        assertEquals(114, matches["tt0434665:6:5"]?.row?.number)
    }

    @Test
    fun `another show's page pairs with nothing`() {
        assertTrue(matchAnimeFillerRows(onePieceKitsu, narutoRows).isEmpty())
        assertTrue(matchAnimeFillerRows(narutoKitsu, onePieceRows).isEmpty())
    }

    @Test
    fun `generic titles need the date and the number to agree`() {
        val rows = listOf(
            row(1, "Opening", AnimeEpisodeKind.MangaCanon, "2020-01-01"),
            row(2, "Beach Day", AnimeEpisodeKind.Filler, "2020-01-08"),
            row(3, "Return", AnimeEpisodeKind.MangaCanon, "2020-01-15"),
            row(4, "Duel", AnimeEpisodeKind.MangaCanon, "2020-01-22"),
            row(5, "Finale", AnimeEpisodeKind.MangaCanon, "2020-01-29"),
        )
        val videos = listOf(
            video("x:1:1", 1, 1, "Episode 1", "2020-01-01"),
            video("x:1:2", 1, 2, "Episode 2", "2020-01-08"),
            // Number 3, but aired on 4's date: neither signal is enough on its own.
            video("x:1:3", 1, 3, "Episode 3", "2020-01-22"),
            video("x:1:4", 1, 4, "Episode 4", "2020-01-29"),
            video("x:1:5", 1, 5, "Episode 5", "2020-01-29"),
        )
        val matches = matchAnimeFillerRows(videos, rows)
        assertEquals(1, matches["x:1:1"]?.row?.number)
        assertEquals(AnimeEpisodeKind.Filler, matches["x:1:2"]?.row?.kind)
        assertEquals(null, matches["x:1:3"])
        assertEquals(null, matches["x:1:4"])
        assertEquals(5, matches["x:1:5"]?.row?.number)
    }

    @Test
    fun `episodes after the page are noticed so a sibling page can be tried`() {
        val rows = listOf(row(1, "A", AnimeEpisodeKind.MangaCanon, "2020-01-01"))
        assertTrue(hasEpisodesAfterPage(listOf(video("x:2:1", 2, 1, "B", "2022-10-11")), rows))
        assertTrue(!hasEpisodesAfterPage(listOf(video("x:1:1", 1, 1, "A", "2020-01-02")), rows))
    }

    @Test
    fun `only pure filler is skippable`() {
        val kinds = mapOf(
            "a" to AnimeEpisodeKind.Filler,
            "b" to AnimeEpisodeKind.MixedCanonFiller,
            "c" to AnimeEpisodeKind.AnimeCanon,
        )
        assertTrue(kinds.isSkippableFiller("a"))
        assertTrue(!kinds.isSkippableFiller("b"))
        assertTrue(!kinds.isSkippableFiller("c"))
        assertTrue(!kinds.isSkippableFiller("d"))
        assertTrue(!kinds.isSkippableFiller(null))
    }

    private fun row(number: Int, title: String, kind: AnimeEpisodeKind, airDate: String) =
        AnimeFillerListRow(number = number, title = title, kind = kind, airDate = airDate.ifEmpty { null })

    private fun video(id: String, season: Int, episode: Int, title: String, released: String) =
        MetaVideo(id = id, title = title, released = released.ifEmpty { null }, season = season, episode = episode)

    private val onePieceRows = listOf(
        row(676, "Operation Failed! Usoland the Hero Dies!?", AnimeEpisodeKind.MangaCanon, "2014-12-28"),
        row(677, "The Legend Is Back! Kyros' All-Out Attack!", AnimeEpisodeKind.MangaCanon, "2015-01-11"),
        row(678, "The Fire Fist Strikes! The Flare-Flare Fruit Power Returns!", AnimeEpisodeKind.MangaCanon, "2015-01-18"),
        row(679, "Dashing Onto the Scene! The Chief of Staff of the Revolutionary Army, Sabo!", AnimeEpisodeKind.MixedCanonFiller, "2015-01-25"),
        row(680, "The Devil's Trap! A Dressrosa Extermination Plan!", AnimeEpisodeKind.MangaCanon, "2015-02-01"),
        row(681, "The 500 Million Berry Man! Target: Usoland!", AnimeEpisodeKind.MangaCanon, "2015-02-07"),
        row(682, "Breaking Through Enemy Lines! Luffy and Zoro Launch the Counter-Attack!", AnimeEpisodeKind.MangaCanon, "2015-02-14"),
        row(683, "With a Rumbling of the Ground! The God of Destruction - Giant Pica Descends!", AnimeEpisodeKind.MangaCanon, "2015-02-21"),
        row(684, "Gathering Into a Powerful Front! Luffy and a Group of Brutal Warriors!", AnimeEpisodeKind.MangaCanon, "2015-02-28"),
        row(685, "Steady Progress! Luffy's Army vs. Pica!", AnimeEpisodeKind.MangaCanon, "2015-03-07"),
        row(686, "A Shocking Confession! Law’s Soulful Vow!", AnimeEpisodeKind.MangaCanon, "2015-03-14"),
        row(687, "A Big Collision! Chief of Staff - Sabo vs. Admiral Fujitora!", AnimeEpisodeKind.MangaCanon, "2015-03-21"),
        row(688, "A Desperate Situation! Luffy Gets Caught in a Trap!", AnimeEpisodeKind.MangaCanon, "2015-03-28"),
        row(689, "A Great Escape! Luffy's Tide-Turning Elephant Gun!", AnimeEpisodeKind.MangaCanon, "2015-04-04"),
        row(690, "A United Front! Luffy's Breakthrough to the Victory!", AnimeEpisodeKind.MixedCanonFiller, "2015-04-11"),
        row(691, "The Second Samurai! Evening Shower Kanjuro Appears", AnimeEpisodeKind.MangaCanon, "2015-04-18"),
        row(692, "A Hard-Fought Battle Against Pica! Zoro's Deadly Attack!", AnimeEpisodeKind.MangaCanon, "2015-05-10"),
        row(693, "The Little People's Princess! Captive Mansherry!", AnimeEpisodeKind.MangaCanon, "2015-05-17"),
        row(694, "Invincible! A Gruesome Army of Headcracker Dolls!", AnimeEpisodeKind.MangaCanon, "2015-05-24"),
        row(695, "Risking Their Lives! Luffy Is the Trump Card for Victory!", AnimeEpisodeKind.MangaCanon, "2015-05-31"),
        row(696, "A Tearful Reunion! Rebecca and Kyros!", AnimeEpisodeKind.MangaCanon, "2015-06-07"),
    )
    private val onePieceKitsu = listOf(
        video("kitsu:12:676", 1, 676, "Operation Failed! Usoland the Hero Dies!?", "2014-12-28"),
        video("kitsu:12:677", 1, 677, "The Legend is Back! Kyros' All-Out Attack!", "2015-01-11"),
        video("kitsu:12:678", 1, 678, "The Fire Fist Strikes! The Flare-Flare Fruit Power Returns!", "2015-01-18"),
        video("kitsu:12:679", 1, 679, "Dashing onto the Scene! The Chief of Staff of the Revolutionary Army - Sabo!", "2015-01-25"),
        video("kitsu:12:680", 1, 680, "The Devil's Trap! A Dressrosa Extermination Plan!", "2015-02-01"),
        video("kitsu:12:681", 1, 681, "The 500 Million Berry Man! Target: Usoland!", "2015-02-08"),
        video("kitsu:12:682", 1, 682, "Breaking through Enemy Lines! Luffy and Zoro Launch the Counter-Attack!", "2015-02-15"),
        video("kitsu:12:683", 1, 683, "With a Rumbling of the Ground! The God of Destruction - Giant Pica Descends!", "2015-03-01"),
        video("kitsu:12:684", 1, 684, "Gathering into a Powerful Front! Luffy and a Group of Brutal Warriors!", "2015-03-15"),
        video("kitsu:12:685", 1, 685, "Steady Progress! Luffy's Army vs. Pica!", "2015-03-22"),
        video("kitsu:12:686", 1, 686, "A Shocking Confession! Law's Soulful Vow!", "2015-03-29"),
        video("kitsu:12:687", 1, 687, "A Big Collision! Chief of Staff - Sabo vs. Admiral Fujitora!", "2015-04-05"),
        video("kitsu:12:688", 1, 688, "A Desperate Situation! Luffy Gets Caught in a Trap!", "2015-04-12"),
        video("kitsu:12:689", 1, 689, "A Great Escape! Luffy's Tide-turning Elephant Gun!", "2015-04-19"),
        video("kitsu:12:690", 1, 690, "A United Front – Luffy's Breakthrough Towards Victory!", "2015-04-26"),
        video("kitsu:12:691", 1, 691, "The Second Samurai – Evening Shower Kanjuro Appears", "2015-05-03"),
        video("kitsu:12:692", 1, 692, "The Struggle with Pica – Zoro's One Finishing Move", "2015-05-10"),
        video("kitsu:12:693", 1, 693, "The Little People's Princess! Captive Mansherry!", "2015-05-17"),
        video("kitsu:12:694", 1, 694, "Invincible! A Gruesome Army of Headcracker Dolls!", "2015-05-24"),
        video("kitsu:12:695", 1, 695, "Risking Their Lives! Luffy is the Trump Card for Victory!", "2015-05-31"),
        video("kitsu:12:696", 1, 696, "A Tearful Reunion! Rebecca and Kyros!", "2015-06-07"),
    )
    private val narutoRows = listOf(
        row(9, "Kakashi: Sharingan Warrior", AnimeEpisodeKind.MixedCanonFiller, "2002-11-28"),
        row(10, "The Forest of Chakra", AnimeEpisodeKind.MangaCanon, "2002-12-05"),
        row(11, "The Land Where a Hero Once Lived", AnimeEpisodeKind.MangaCanon, "2002-12-12"),
        row(12, "Battle on the Bridge! Zabuza Returns!!", AnimeEpisodeKind.MangaCanon, "2002-12-19"),
        row(13, "Haku's Secret Jutsu: Crystal Ice Mirrors", AnimeEpisodeKind.MangaCanon, "2002-12-21"),
        row(14, "The Number One Hyperactive, Knucklehead Ninja Joins the Fight!!", AnimeEpisodeKind.MixedCanonFiller, "2002-12-22"),
        row(15, "Zero Visibility: The Sharingan Shatters", AnimeEpisodeKind.MixedCanonFiller, "2002-12-23"),
        row(16, "The Broken Seal", AnimeEpisodeKind.MixedCanonFiller, "2002-12-24"),
        row(17, "White Past: Hidden Ambition", AnimeEpisodeKind.MangaCanon, "2002-12-25"),
        row(18, "The Weapons Known as Shinobi", AnimeEpisodeKind.MixedCanonFiller, "2003-01-31"),
        row(19, "The Demon in the Snow", AnimeEpisodeKind.MixedCanonFiller, "2003-02-13"),
        row(20, "A New Chapter Begins: The Chunin Exam!", AnimeEpisodeKind.MixedCanonFiller, "2003-02-20"),
        row(21, "Identify Yourself: Powerful New Rivals", AnimeEpisodeKind.MixedCanonFiller, "2003-02-27"),
        row(22, "Chunin Challenge: Rock Lee vs. Sasuke!", AnimeEpisodeKind.MangaCanon, "2003-03-06"),
    )
    private val narutoKitsu = listOf(
        video("kitsu:11:9", 1, 9, "Kakashi: Sharingan Warrior", "2002-11-28"),
        video("kitsu:11:10", 1, 10, "The Forest of Chakra", "2002-12-05"),
        video("kitsu:11:11", 1, 11, "The Land Where a Hero Once Lived", "2002-12-12"),
        video("kitsu:11:12", 1, 12, "Battle on the Bridge! Zabuza Returns!!", "2002-12-19"),
        video("kitsu:11:13", 1, 13, "Haku's Secret Jutsu: Crystal Ice Mirrors", "2002-12-26"),
        video("kitsu:11:14", 1, 14, "The Number One Hyperactive, Knucklehead Ninja Joins the Fight!!", "2003-01-09"),
        video("kitsu:11:15", 1, 15, "Zero Visibility: The Sharingan Shatters", "2003-01-16"),
        video("kitsu:11:16", 1, 16, "The Broken Seal", "2003-01-23"),
        video("kitsu:11:17", 1, 17, "White Past: Hidden Ambition", "2003-01-30"),
        video("kitsu:11:18", 1, 18, "The Weapons Known as Shinobi", "2003-02-06"),
        video("kitsu:11:19", 1, 19, "The Demon in the Snow", "2003-02-13"),
        video("kitsu:11:20", 1, 20, "A New Chapter Begins: The Chunin Exam!", "2003-02-20"),
        video("kitsu:11:21", 1, 21, "Identify Yourself: Powerful New Rivals", "2003-02-27"),
        video("kitsu:11:22", 1, 22, "Chunin Challenge: Rock Lee vs. Sasuke!", "2003-03-06"),
    )
    private val bleachEarlyRows = listOf(
        row(36, "Kenpachi Zaraki Approaches!", AnimeEpisodeKind.MangaCanon, "2005-06-14"),
        row(37, "Motive of the Fist", AnimeEpisodeKind.MangaCanon, "2005-06-21"),
        row(38, "Desperation! The Broken Zangetsu", AnimeEpisodeKind.MangaCanon, "2005-06-28"),
        row(39, "The Immortal Man", AnimeEpisodeKind.MangaCanon, "2005-07-05"),
        row(40, "The Shinigami whom Ganju Met", AnimeEpisodeKind.MangaCanon, "2005-07-12"),
        row(41, "Reunion, Ichigo and Rukia", AnimeEpisodeKind.MangaCanon, "2005-07-19"),
        row(42, "Yoruichi, Goddess of Flash, Dances!", AnimeEpisodeKind.MangaCanon, "2005-07-26"),
        row(43, "The Despicable Shinigami", AnimeEpisodeKind.MangaCanon, "2005-08-02"),
        row(44, "Ishida Ultimate Power!", AnimeEpisodeKind.MangaCanon, "2005-08-09"),
        row(45, "Overcome Your Limits!", AnimeEpisodeKind.MangaCanon, "2005-08-16"),
        row(46, "Authentic Records! School of Shinigami", AnimeEpisodeKind.MixedCanonFiller, "2005-08-23"),
    )
    private val bleachLateRows = listOf(
        row(109, "Ichigo and Rukia, Thoughts in the Revolving Around Heaven", AnimeEpisodeKind.MixedCanonFiller, "2007-01-04"),
        row(110, "Reopening of the Substitute Business! The Terrifying Transfer Student", AnimeEpisodeKind.MangaCanon, "2007-01-10"),
        row(111, "Shock! The Father's True Identity", AnimeEpisodeKind.MixedCanonFiller, "2007-01-17"),
        row(112, "The Commencement of War, Visoreds and the Arrancars", AnimeEpisodeKind.MangaCanon, "2007-01-24"),
        row(113, "Prelude to the Apocalypse, The Arrancar's Offensive", AnimeEpisodeKind.MangaCanon, "2007-01-31"),
        row(114, "Reunion, Ichigo and Rukia and Soul Reapers", AnimeEpisodeKind.MangaCanon, "2007-02-07"),
        row(115, "Mission! The Soul Reapers Have Come", AnimeEpisodeKind.MangaCanon, "2007-02-14"),
        row(116, "The Evil Eye, Aizen Returns", AnimeEpisodeKind.MixedCanonFiller, "2007-02-21"),
        row(117, "Rukia's Battle Commences! The Freezing White Blade", AnimeEpisodeKind.MangaCanon, "2007-02-28"),
        row(118, "Ikkaku's Bankai! The Power That Breaks Everything", AnimeEpisodeKind.MangaCanon, "2007-03-07"),
        row(119, "Zaraki Division's Secret Story! The Lucky Men", AnimeEpisodeKind.MixedCanonFiller, "2007-03-21"),
    )
    private val bleachImdb = listOf(
        video("tt0434665:2:16", 2, 16, "Kenpachi Zaraki Approaches!", "2005-06-15"),
        video("tt0434665:2:17", 2, 17, "Reason of the Fist", "2005-06-22"),
        video("tt0434665:2:18", 2, 18, "Desperation! The Broken Zangetsu", "2005-06-29"),
        video("tt0434665:2:19", 2, 19, "The Man of Immortality", "2005-07-06"),
        video("tt0434665:2:20", 2, 20, "The Shinigami Whom Ganju Met", "2005-07-13"),
        video("tt0434665:2:21", 2, 21, "Reunion, Ichigo and Rukia", "2005-07-20"),
        video("tt0434665:6:1", 6, 1, "Reopening of the Substitute Business! The Terrifying Transfer Student", "2007-01-11"),
        video("tt0434665:6:2", 6, 2, "Shock! The Father's True Character", "2007-01-18"),
        video("tt0434665:6:3", 6, 3, "The Commencement of War, the Vizards and the Arrancars", "2007-01-25"),
        video("tt0434665:6:4", 6, 4, "Prelude to the Apocalypse, the Arrancar Offensive!", "2007-02-01"),
        video("tt0434665:6:5", 6, 5, "Reunion, Ichigo and Rukia", "2007-02-08"),
        video("tt0434665:6:6", 6, 6, "Mission! The Shinigamis Who Came", "2007-02-15"),
        video("tt0434665:6:7", 6, 7, "The Evil Eye, Aizen Again", "2007-02-22"),
        video("tt0434665:6:8", 6, 8, "Rukia's Battle Begins! The Freezing White Blade", "2007-03-01"),
        video("tt0434665:6:9", 6, 9, "Ikkaku's Bankai! The Power that Breaks Everything", "2007-03-08"),
    )
}
