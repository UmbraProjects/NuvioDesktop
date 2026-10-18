package com.nuvio.app.features.streams

import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real data, 2026-10-18: One Piece as AIOMetadata lists it under `kitsu:12` (absolute numbering, all
 * season 1) and under `tt0388629` (TVDB seasons, the numbering release names use).
 */
class AnimeFranchiseEpisodeMatchTest {
    private fun entry(episode: Int, released: String, title: String) =
        MetaVideo(id = "kitsu:12:$episode", title = title, released = "${released}T11:00:00.000Z", season = 1, episode = episode)

    private fun franchise(season: Int, episode: Int, released: String, title: String) =
        MetaVideo(id = "tt0388629:$season:$episode", title = title, released = "${released}T14:15:00.000Z", season = season, episode = episode)

    private val entry = listOf(
        entry(148, "2003-03-16", "Legendary Family! Noland, the Liar!"),
        entry(149, "2003-03-23", "Steer for the Clouds! Capture the South Bird!"),
        entry(150, "2003-04-13", "Dreams Don't Come True?! Bellamy vs. the Saruyama Alliance!"),
        entry(151, "2003-04-20", "100 Million Man! World's Greatest Power and Pirate Black Beard!"),
        entry(152, "2003-04-27", "Take to the Sky! Ride the Knockup Stream!"),
        entry(203, "2004-09-26", "The Pirate Ship Disappears! Fortress Battle, Round #2!"),
        entry(204, "2004-10-03", "The Gold and Waver Recovery Operations!"),
        entry(205, "2004-10-03", "The One Fell Swoop Plan! Jonathan's Surefire Secret Tactic!"),
        entry(206, "2004-10-10", "Farewell, Marine Fortress! The Last Battle for Escape!"),
        entry(588, "2013-03-24", "Meeting Again After Two Years! Luffy and Law!"),
        entry(589, "2013-03-31", "The Worst in the World! A Scientist of Terror - Caesar!"),
        entry(590, "2013-04-07", "History's Strongest Collaboration vs. Glutton of the Sea"),
        entry(591, "2013-04-14", "Chopper's Fury! The Master's Inhumane Experiment!"),
        entry(592, "2013-04-21", "To Annihilate the Straw Hats! Legendary Assassins Descend!"),
    )

    private val franchise = listOf(
        franchise(9, 4, "2003-03-09", "A Pirate's Height! The Man who Talks of Dreams and the King of Underwater Exploration"),
        franchise(9, 5, "2003-03-16", "The Legendary Family! 'Liar Norland'"),
        franchise(9, 6, "2003-03-23", "Hard Turn to the Clouds! Find the Southbird!"),
        franchise(9, 7, "2003-04-13", "Dreams Don't Come True!? Bellamy vs. The Saruyama Alliance"),
        franchise(9, 8, "2003-04-20", "The 100 Million Man! The World's Greatest Authority and the Pirate Blackbeard"),
        franchise(9, 9, "2003-04-27", "Sail into the Sky! Ride the Knock-Up Stream"),
        franchise(9, 10, "2003-05-04", "This is the Sea of the Sky! The Knight of the Sky and Heaven's Gate"),
        franchise(10, 8, "2004-09-19", "The Pirate Ship Disappears! Stronghold Battle, Round 2"),
        franchise(10, 9, "2004-10-03", "Operation Gold Recovery and Operation Waver Retrieval!"),
        franchise(10, 10, "2004-10-03", "The Pirate Round-Up Plan! Jonathon's Secret Winning Strategy!"),
        franchise(10, 11, "2004-10-10", "So Long, Marine Base! The Final Battle for Freedom"),
        franchise(16, 9, "2013-03-17", "A Collision! Law vs. Vice Admiral Smoker!"),
        franchise(16, 10, "2013-03-24", "First Meeting in Two Years! Luffy and Law"),
        franchise(16, 11, "2013-03-31", "Worst of the World - The Scary Scientist Caesar"),
        franchise(16, 12, "2013-04-14", "Chopper Enraged  Master's Tyrannical Experiments"),
        franchise(16, 13, "2013-04-21", "Gang Obliteration! Legendary Hitmen Attack!"),
        franchise(16, 14, "2013-04-28", "Save Nami! Luffy's Snow Mountain Battle"),
    )

    private fun match(episode: Int): String? =
        matchFranchiseEpisode(entry.single { it.episode == episode }, entry, franchise)?.id

    @Test
    fun episode_150_is_tvdb_season_9_episode_7() {
        // Not TMDB's S6E7, whose S06E07 release name is a Little Garden (dinosaur) episode.
        assertEquals("tt0388629:9:7", match(150))
        assertEquals("tt0388629:9:6", match(149))
        assertEquals("tt0388629:9:8", match(151))
    }

    @Test
    fun double_episode_days_pair_in_order() {
        assertEquals("tt0388629:10:9", match(204))
        assertEquals("tt0388629:10:10", match(205))
    }

    @Test
    fun episodes_tvdb_keeps_out_of_its_seasons_are_not_matched_and_do_not_shift_the_rest() {
        // 590 is the Toriko crossover, a special on TVDB; counting episodes would put 591 on 16:13.
        assertNull(match(590))
        assertEquals("tt0388629:16:11", match(589))
        assertEquals("tt0388629:16:12", match(591))
        assertEquals("tt0388629:16:13", match(592))
    }

    @Test
    fun an_episode_with_no_counterpart_that_day_is_not_guessed() {
        // TVDB dates 203's counterpart a week earlier; a near-identical title is not enough.
        assertNull(match(203))
        assertNull(
            matchFranchiseEpisode(
                entry.first().copy(released = null),
                entry,
                franchise,
            ),
        )
    }

    @Test
    fun epoch_day_reads_dates_and_date_times() {
        assertEquals(0L, "1970-01-01".epochDay())
        assertEquals(12155L, "2003-04-13T11:00:00.000Z".epochDay())
        assertEquals(1L, "2003-04-14".epochDay()!! - "2003-04-13".epochDay()!!)
        assertEquals(1L, "2004-03-01".epochDay()!! - "2004-02-29".epochDay()!!)
        assertNull("not a date".epochDay())
        assertNull(null.epochDay())
    }

    @Test
    fun title_similarity_tolerates_punctuation_and_case() {
        assertEquals(1.0, titleSimilarity("Bellamy vs. the Saruyama!", "bellamy VS the saruyama"))
        assertTrue(titleSimilarity("Luffy", "Zoro") < 0.2)
        assertEquals(0.0, titleSimilarity("", "Zoro"))
    }
}
