package com.nuvio.app.features.home.components

import kotlin.test.Test
import kotlin.test.assertEquals

class CollectionCardImageLayersTest {

    @Test
    fun `unfocused card with a distinct cover shows only the cover`() {
        assertEquals(
            CollectionCardImageLayers(
                composeCover = true,
                composeFirstFrame = false,
                composeAnimation = false,
            ),
            collectionCardImageLayers(hasDistinctCover = true, playAnimation = false),
        )
    }

    @Test
    fun `focused card with a distinct cover keeps cover underneath while playing focus artwork`() {
        assertEquals(
            CollectionCardImageLayers(
                composeCover = true,
                composeFirstFrame = true,
                composeAnimation = true,
            ),
            collectionCardImageLayers(hasDistinctCover = true, playAnimation = true),
        )
    }

    @Test
    fun `unfocused card without a separate cover falls back to the animation first frame`() {
        assertEquals(
            CollectionCardImageLayers(
                composeCover = false,
                composeFirstFrame = true,
                composeAnimation = false,
            ),
            collectionCardImageLayers(hasDistinctCover = false, playAnimation = false),
        )
    }

    @Test
    fun `focused card without a separate cover plays animation over its first frame`() {
        assertEquals(
            CollectionCardImageLayers(
                composeCover = false,
                composeFirstFrame = true,
                composeAnimation = true,
            ),
            collectionCardImageLayers(hasDistinctCover = false, playAnimation = true),
        )
    }
}
