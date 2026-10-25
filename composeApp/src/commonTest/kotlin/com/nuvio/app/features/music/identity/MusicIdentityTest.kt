package com.nuvio.app.features.music.identity

import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicArtistRef
import com.nuvio.app.features.music.MusicTrack
import com.nuvio.app.features.music.library.ArtistArtwork
import com.nuvio.app.features.music.library.LibraryArtist
import com.nuvio.app.features.music.library.LibraryRelease
import com.nuvio.app.features.music.library.MusicLibraryState
import com.nuvio.app.features.music.library.ReleaseArtwork
import com.nuvio.app.features.music.library.ReleaseKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicIdentityTest {
    private fun release(key: String, cover: String?, date: String = "2020", yt: String? = null) = LibraryRelease(
        key = key, title = key, kind = ReleaseKind.Album, year = date.take(4), releaseDate = date,
        ytBrowseId = yt, art = ReleaseArtwork(covers = listOfNotNull(cover)),
    )

    private fun artist(
        art: ArtistArtwork = ArtistArtwork(),
        releases: List<LibraryRelease> = emptyList(),
        id: String = "UC1",
        name: String = "Hozier",
        mood: String? = null,
        palette: ArtworkPalette? = null,
    ) = LibraryArtist(id = id, name = name, art = art, releases = releases, mood = mood, palette = palette)

    @Test
    fun heroFollowsTheFallbackChain() {
        val covers = listOf(release("old", "c-old", "2014"), release("new", "c-new", "2024"))
        val all = ArtistArtwork(backgrounds = listOf("bg1", "bg2"), banner = "ban", wideThumb = "wide", thumb = "t")
        assertEquals(HeroImage("bg1", HeroSource.ArtistBackground), artist(all, covers).identity().hero)
        assertEquals(HeroImage("ban", HeroSource.Banner), artist(all.copy(backgrounds = emptyList()), covers).identity().hero)
        assertEquals(HeroImage("wide", HeroSource.WideThumb), artist(all.copy(backgrounds = emptyList(), banner = null), covers).identity().hero)
        // Last rung: the newest cover, to be blurred.
        assertEquals(HeroImage("c-new", HeroSource.BlurredCover), artist(ArtistArtwork(thumb = "t"), covers).identity().hero)
        assertNull(artist().identity().hero)
    }

    @Test
    fun logoAndCutoutPreferTheBetterVariant() {
        val id = artist(ArtistArtwork(logo = "l", logoHd = "lhd", clearArt = "ca", cutout = "co")).identity()
        assertEquals("lhd", id.logoUrl)
        assertEquals("ca", id.cutoutUrl)
        assertNull(artist().identity().logoUrl)
    }

    @Test
    fun anArtistWithNoArtNeverAsksForAPalette() {
        val id = artist().identity()
        assertTrue(id.paletteSources.isEmpty())
        assertFalse(id.needsPalette)
        assertNull(id.palette)
    }

    @Test
    fun paletteIsDerivedOnceThenOnlyWhenTheArtChanges() {
        val art = ArtistArtwork(backgrounds = listOf("bg1"), thumb = "t")
        val fresh = artist(art).identity()
        assertEquals(listOf("bg1", "t"), fresh.paletteSources)
        assertTrue(fresh.needsPalette)

        val cached = ArtworkPaletteMath.derive(0xE8913A, forUrl = "bg1")
        assertFalse(artist(art, palette = cached).identity().needsPalette)

        // New background: the cached palette is for other art, so it is derived again (and still usable meanwhile).
        val changed = artist(art.copy(backgrounds = listOf("bg9")), palette = cached).identity()
        assertTrue(changed.needsPalette)
        assertEquals(cached, changed.palette)
    }

    @Test
    fun aReleaseBorrowsTheArtistsPaletteAndMotionUntilItHasItsOwn() {
        val p = ArtworkPaletteMath.derive(0xE8913A)
        val a = artist(palette = p, mood = "Angry")
        val r = release("r", "c").identity(a)
        assertEquals(p, r.palette)
        assertEquals(MotionProfile.Driven, r.motion)
        assertEquals("c", r.coverUrl)
        assertNull(release("r", null).identity().palette)
    }

    @Test
    fun motionComesFromMoodThenStyleAndGenre() {
        assertEquals(MotionProfile.Calm, motionProfile("Melancholy", null))
        assertEquals(MotionProfile.Driven, motionProfile("Aggressive", "Rock/Pop"))
        assertEquals(MotionProfile.Driven, motionProfile(null, "Heavy Metal"))
        assertEquals(MotionProfile.Calm, motionProfile(null, null, listOf("Folk")))
        assertEquals(MotionProfile.Steady, motionProfile(null, null))
        assertEquals(MotionProfile.Steady, motionProfile("Happy", "Pop"))
        // A mood that says both is a tie, and form breaks it.
        assertEquals(MotionProfile.Driven, motionProfile("Calm but intense", "Punk"))
        // Mood beats style.
        assertEquals(MotionProfile.Calm, motionProfile("Peaceful", "Metal"))
    }

    @Test
    fun theArtistWhoIsPlayingIsFoundByChannelIdThenByName() {
        val hozier = artist(id = "UC1", name = "Hozier")
        val metallica = artist(id = "UC2", name = "Metallica")
        val library = MusicLibraryState(artists = listOf(hozier, metallica))
        fun track(vararg refs: MusicArtistRef) = MusicTrack("v", "t", refs.toList(), 100, 1, true)

        assertEquals("UC2", playingArtist(track(MusicArtistRef("Whoever", "UC2")), null, library)?.id)
        assertEquals("UC1", playingArtist(track(MusicArtistRef("hozier")), null, library)?.id)
        val album = MusicAlbum("MPREb", "A", listOf(MusicArtistRef("Metallica", null)), null, null, null, emptyList(), null)
        assertEquals("UC2", playingArtist(null, album, library)?.id)
        assertNull(playingArtist(track(MusicArtistRef("Someone Else", "UC9")), null, library))
        assertNull(playingArtist(null, null, library))
    }

    @Test
    fun theViewedArtistIsTheLibraryArtistOrTheOwnerOfTheAlbum() {
        val owner = artist(id = "UC1", releases = listOf(release("r", null, yt = "MPREb_1")))
        val library = MusicLibraryState(artists = listOf(owner))
        assertEquals("UC1", viewedArtist(library, "UC1", null)?.id)
        assertEquals("UC1", viewedArtist(library, null, "MPREb_1")?.id)
        assertNull(viewedArtist(library, "UC404", null))
        assertNull(viewedArtist(library, null, null))
    }

    @Test
    fun aPageWearsTheViewedArtistAndEverywhereElseThePlayingOne() {
        val viewed = LibraryArtist(id = "UCv", name = "Viewed")
        val playing = LibraryArtist(id = "UCp", name = "Playing")
        assertEquals("UCv", accentArtist(playing, viewed, viewedFirst = true)?.id)
        assertEquals("UCp", accentArtist(playing, viewed, viewedFirst = false)?.id)
        assertEquals("UCp", accentArtist(playing, null, viewedFirst = true)?.id)
        assertEquals("UCv", accentArtist(null, viewed, viewedFirst = false)?.id)
        assertNull(accentArtist(null, null, viewedFirst = true))
    }
}
