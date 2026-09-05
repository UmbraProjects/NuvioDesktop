package com.nuvio.app.features.home.components

import com.nuvio.app.features.collection.CollectionFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which artwork a collection card loads, given whether the surface animates.
 *
 * The bug this pins: the animation flag used to control only whether a still was drawn *underneath*
 * the animation, so Search, Library and Discover — none of which animate — still downloaded and
 * fully decoded multi-megabyte GIFs in order to display them as stills.
 */
class CollectionFolderCardImageUrlTest {

    private val gif = "https://example/folder.gif"
    private val cover = "https://example/folder.jpg"

    private fun folder(
        coverImageUrl: String? = cover,
        focusGifUrl: String? = gif,
        mobileFocusGifEnabled: Boolean = true,
    ) = CollectionFolder(
        id = "folder",
        title = "Folder",
        coverImageUrl = coverImageUrl,
        focusGifUrl = focusGifUrl,
        mobileFocusGifEnabled = mobileFocusGifEnabled,
    )

    @Test
    fun `an animating surface loads the gif`() {
        assertEquals(gif, collectionFolderCardImageUrl(folder(), animateGifs = true))
    }

    @Test
    fun `a non-animating surface loads the cover instead of the gif`() {
        assertEquals(cover, collectionFolderCardImageUrl(folder(), animateGifs = false))
    }

    @Test
    fun `a folder with no cover still shows its gif on a non-animating surface`() {
        // Falls back rather than showing a blank card; the card then asks the decoder for a still.
        assertEquals(
            gif,
            collectionFolderCardImageUrl(folder(coverImageUrl = null), animateGifs = false),
        )
    }

    @Test
    fun `a folder with its gif switched off never loads the gif`() {
        assertEquals(
            cover,
            collectionFolderCardImageUrl(folder(mobileFocusGifEnabled = false), animateGifs = true),
        )
        assertNull(
            collectionFolderCardImageUrl(
                folder(coverImageUrl = null, mobileFocusGifEnabled = false),
                animateGifs = true,
            ),
        )
    }

    @Test
    fun `a folder with no gif uses its cover on either surface`() {
        val noGif = folder(focusGifUrl = null)
        assertEquals(cover, collectionFolderCardImageUrl(noGif, animateGifs = true))
        assertEquals(cover, collectionFolderCardImageUrl(noGif, animateGifs = false))
    }

    @Test
    fun `a folder with no artwork at all resolves to nothing`() {
        assertNull(
            collectionFolderCardImageUrl(
                folder(coverImageUrl = null, focusGifUrl = null),
                animateGifs = true,
            ),
        )
    }
}
