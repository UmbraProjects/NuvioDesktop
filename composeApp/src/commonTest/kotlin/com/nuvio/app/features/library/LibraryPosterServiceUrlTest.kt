package com.nuvio.app.features.library

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Library rows showed a metadata addon's badge posters beside plain TMDB art (2026-11-13). */
class LibraryPosterServiceUrlTest {
    @Test
    fun addonPosterServicesAreRecognised() {
        assertTrue("https://posters.myaio.xyz/poster?tmdb_id=1813355&imdb_id=&stremio_id=tmdb:1813355&type=movie&shape=poster".isPosterServiceUrl())
        assertTrue("https://postersplus.cc/poster?tmdb_id=259265&imdb_id=tt32937780&type=series&shape=poster".isPosterServiceUrl())
        assertTrue("https://api.ratingposterdb.com/t0-free-rpdb/imdb/poster-default/tt0111161.jpg".isPosterServiceUrl())
    }

    @Test
    fun plainArtIsNot() {
        assertFalse("https://image.tmdb.org/t/p/w500/uDXPOk3fx1kP.jpg".isPosterServiceUrl())
        assertFalse("https://images.metahub.space/poster/medium/tt0111161/img".isPosterServiceUrl())
        assertFalse("https://artworks.thetvdb.com/banners/posters/81189-10.jpg".isPosterServiceUrl())
        assertFalse(null.isPosterServiceUrl())
    }
}
