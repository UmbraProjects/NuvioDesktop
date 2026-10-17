package com.nuvio.app.core.ui

import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.collection.CollectionFolder
import com.nuvio.app.features.collection.FolderViewMode
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The prewarm must fetch the one image each surface shows with the current settings — not every
 * image an item carries, which would warm backdrops and logos nobody is shown.
 */
class ArtworkPrewarmTargetsTest {

    private val all = ImageCacheSettings(prewarmEnabled = true)
    private val portrait = PrewarmDisplay()
    private val landscape = PrewarmDisplay(cardStyle = PosterCardStyleUiState(catalogLandscapeModeEnabled = true))

    @Test
    fun `nothing is chosen while prewarm is off`() {
        val targets = prewarmTargets(listOf(item("a")), emptyList(), ImageCacheSettings())
        assertTrue(targets.isEmpty())
    }

    @Test
    fun `portrait cards warm the poster only, the hero its backdrop and logo`() {
        val targets = prewarmTargets(
            heroItems = listOf(item("hero")),
            sections = listOf(section("row1", item("a")), section("row2", item("b"))),
            settings = all,
            display = portrait,
        )
        assertEquals(
            listOf("https://b/hero", "https://l/hero", "https://p/a", "https://p/b"),
            targets.map { it.url },
        )
    }

    @Test
    fun `the hero warms what it settled on, not the catalog copy`() {
        // Home publishes the hero after enrichment replaced the catalog backdrop with TMDB's.
        val settled = item("a").copy(banner = "https://image.tmdb.org/t/p/original/a.jpg")
        val targets = prewarmTargets(listOf(settled), emptyList(), all.copy(prewarmLogos = false))
        assertEquals(listOf("https://image.tmdb.org/t/p/original/a.jpg"), targets.map { it.url })
    }

    @Test
    fun `a hero without a backdrop shows, and warms, its poster`() {
        val targets = prewarmTargets(listOf(item("a").copy(banner = null)), emptyList(), all)
        assertEquals(
            listOf(PrewarmTarget("https://p/a", PrewarmArtworkKind.Poster), PrewarmTarget("https://l/a", PrewarmArtworkKind.Logo)),
            targets,
        )
    }

    @Test
    fun `landscape cards warm the backdrop and the logo drawn over it`() {
        val targets = prewarmTargets(emptyList(), listOf(section("row", item("a"))), all, landscape)
        assertEquals(
            listOf(PrewarmTarget("https://b/a", PrewarmArtworkKind.Backdrop), PrewarmTarget("https://l/a", PrewarmArtworkKind.Logo)),
            targets,
        )
    }

    @Test
    fun `a landscape poster replaces the backdrop and carries its own title`() {
        val item = item("a").copy(landscapePoster = "https://lp/a")
        val targets = prewarmTargets(emptyList(), listOf(section("row", item)), all, landscape)
        assertEquals(listOf(PrewarmTarget("https://lp/a", PrewarmArtworkKind.Poster)), targets)
    }

    @Test
    fun `text titles mean no logo on landscape cards`() {
        val textTitles = PrewarmDisplay(
            cardStyle = PosterCardStyleUiState(catalogLandscapeModeEnabled = true, landscapeTextTitlesEnabled = true),
        )
        val targets = prewarmTargets(emptyList(), listOf(section("row", item("a"))), all, textTitles)
        assertEquals(listOf("https://b/a"), targets.map { it.url })
    }

    @Test
    fun `continue watching warms only the image its style leads with`() {
        val cw = cwItem()
        fun shown(style: ContinueWatchingSectionStyle, thumbnails: Boolean) = prewarmTargets(
            emptyList(), emptyList(), all,
            PrewarmDisplay(continueWatchingStyle = style, useEpisodeThumbnails = thumbnails),
            continueWatching = listOf(cw),
        ).single()

        assertEquals(PrewarmTarget("https://still/cw", PrewarmArtworkKind.Backdrop), shown(ContinueWatchingSectionStyle.Card, true))
        assertEquals(PrewarmTarget("https://b/cw", PrewarmArtworkKind.Backdrop), shown(ContinueWatchingSectionStyle.Card, false))
        assertEquals(PrewarmTarget("https://p/cw", PrewarmArtworkKind.Poster), shown(ContinueWatchingSectionStyle.Poster, true))
    }

    @Test
    fun `a disabled kind is skipped rather than replaced by a fallback`() {
        val targets = prewarmTargets(
            emptyList(), emptyList(), all.copy(prewarmBackdrops = false),
            PrewarmDisplay(continueWatchingStyle = ContinueWatchingSectionStyle.Card),
            continueWatching = listOf(cwItem()),
        )
        assertTrue(targets.isEmpty(), "the card shows the still, so its poster would never be asked for")
    }

    @Test
    fun `order is hero, continue watching, rows, library, and each url once`() {
        val targets = prewarmTargets(
            heroItems = listOf(item("hero")),
            sections = listOf(section("row", item("a"))),
            settings = all.copy(prewarmLogos = false),
            display = portrait.copy(continueWatchingStyle = ContinueWatchingSectionStyle.Poster),
            continueWatching = listOf(cwItem()),
            library = listOf(item("lib"), item("a")),
        )
        assertEquals(
            listOf("https://b/hero", "https://p/cw", "https://p/a", "https://p/lib"),
            targets.map { it.url },
        )
    }

    @Test
    fun `rows are capped, the total is capped, and non-http art is skipped`() {
        val long = section("long", *Array(10) { item("i$it") })
        assertEquals(4, prewarmTargets(emptyList(), listOf(long), all, perRowLimit = 4).size)
        assertEquals(5, prewarmTargets(emptyList(), listOf(long), all, maxTargets = 5).size)

        val local = item("x").copy(poster = "file:///C:/art.png", banner = "  ", logo = null)
        assertTrue(prewarmTargets(listOf(local), listOf(section("row", local)), all).isEmpty())
    }

    @Test
    fun `focused heroes cover home rows when the hero follows focus, then the library`() {
        val heroes = prewarmFocusedHeroes(
            focusRows = listOf(listOf(item("a"), item("b")), listOf(item("a"), item("c"))),
            library = listOf(item("lib"), item("a")),
            settings = all,
        )
        assertEquals(
            listOf("a" to true, "b" to true, "c" to true, "lib" to false, "a" to false),
            heroes.map { it.item.id to it.normalHomeMode },
            "each title once per surface; the library hero resolves with its own rules",
        )
    }

    @Test
    fun `no focused heroes when neither backdrops nor logos are wanted`() {
        val heroes = prewarmFocusedHeroes(
            listOf(listOf(item("a"))), listOf(item("lib")),
            all.copy(prewarmBackdrops = false, prewarmLogos = false),
        )
        assertTrue(heroes.isEmpty())
    }

    @Test
    fun `focused hero rows are capped like card rows`() {
        val row = List(10) { item("i$it") }
        assertEquals(4, prewarmFocusedHeroes(listOf(row), emptyList(), all, perRowLimit = 4).size)
    }

    @Test
    fun `hero artwork is the backdrop, else the poster, plus the logo`() {
        assertEquals(
            listOf(PrewarmTarget("https://b/a", PrewarmArtworkKind.Backdrop), PrewarmTarget("https://l/a", PrewarmArtworkKind.Logo)),
            heroArtwork(item("a")),
        )
        assertEquals(
            listOf(PrewarmTarget("https://p/a", PrewarmArtworkKind.Poster)),
            heroArtwork(item("a").copy(banner = " ", logo = null)),
        )
    }

    @Test
    fun `collection cards warm after the rows and before the library, the cover beside a gif`() {
        val collection = collection(
            "c",
            CollectionFolder(id = "f1", title = "f1", coverImageUrl = "https://cover/f1"),
            CollectionFolder(id = "f2", title = "f2", coverImageUrl = "https://cover/f2", focusGifUrl = "https://gif/f2"),
        )
        val targets = prewarmTargets(
            heroItems = emptyList(),
            sections = listOf(section("row", item("a"))),
            settings = all,
            library = listOf(item("lib")),
            collections = listOf(collection),
        )
        assertEquals(
            listOf("https://p/a", "https://cover/f1", "https://gif/f2", "https://cover/f2", "https://p/lib"),
            targets.map { it.url },
        )
        assertTrue(targets.all { it.kind == PrewarmArtworkKind.Poster })
    }

    @Test
    fun `folders are only queued when collection contents are on`() {
        val collections = listOf(collection("c", CollectionFolder(id = "f", title = "f")))
        assertTrue(prewarmCollectionFolders(collections, all).isEmpty())
        val folders = prewarmCollectionFolders(collections, all.copy(prewarmCollectionContents = true))
        assertEquals(listOf("c" to "f"), folders.map { it.collectionId to it.folder.id })
        assertTrue(
            prewarmCollectionFolders(collections, ImageCacheSettings(prewarmCollectionContents = true)).isEmpty(),
            "nothing while prewarm itself is off",
        )
    }

    @Test
    fun `a folder grid warms posters even in landscape mode, rows warm home cards`() {
        val items = listOf(item("a"), item("a"))
        assertEquals(
            listOf(PrewarmTarget("https://p/a", PrewarmArtworkKind.Poster)),
            prewarmFolderTargets(items, FolderViewMode.TABBED_GRID, landscape, all),
        )
        assertEquals(
            listOf(PrewarmTarget("https://b/a", PrewarmArtworkKind.Backdrop), PrewarmTarget("https://l/a", PrewarmArtworkKind.Logo)),
            prewarmFolderTargets(items, FolderViewMode.ROWS, landscape, all),
        )
        assertEquals(
            listOf("https://l/a"),
            prewarmFolderTargets(items, FolderViewMode.ROWS, landscape, all.copy(prewarmBackdrops = false)).map { it.url },
        )
    }

    @Test
    fun `folders are capped across collections in home order`() {
        val on = all.copy(prewarmCollectionContents = true)
        val collections = (1..3).map { c ->
            collection("c$c", *(1..4).map { f -> CollectionFolder(id = "c$c-f$f", title = "f$f") }.toTypedArray())
        }
        val folders = prewarmCollectionFolders(collections, on, perCollectionLimit = 3, totalLimit = 5)
        assertEquals(
            listOf("c1-f1", "c1-f2", "c1-f3", "c2-f1", "c2-f2"),
            folders.map { it.folder.id },
        )
    }

    private fun collection(id: String, vararg folders: CollectionFolder) =
        Collection(id = id, title = id, folders = folders.toList())

    private fun cwItem() = ContinueWatchingItem(
        parentMetaId = "cw", parentMetaType = "series", videoId = "cw:1:2", title = "cw", subtitle = "",
        imageUrl = "https://still/cw", resumePositionMs = 10, durationMs = 100, progressFraction = 0.1f,
        seasonNumber = 1, episodeNumber = 2,
        poster = "https://p/cw", background = "https://b/cw",
        episodeThumbnail = "https://still/cw", logo = "https://l/cw",
    )

    private fun item(id: String) = MetaPreview(
        id = id,
        type = "movie",
        name = id,
        poster = "https://p/$id",
        banner = "https://b/$id",
        logo = "https://l/$id",
    )

    private fun section(key: String, vararg items: MetaPreview) = HomeCatalogSection(
        key = key,
        title = key,
        subtitle = "",
        addonName = "test",
        items = items.toList(),
    )
}
