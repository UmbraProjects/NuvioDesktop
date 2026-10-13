package com.nuvio.app.features.tmdb

private val TMDB_SIZED_IMAGE = Regex("""^(https?://image\.tmdb\.org/t/p/)w\d+/""")

/**
 * The same TMDB image at full size, for artwork drawn across the whole screen.
 *
 * TMDB serves one file at every size, and the size is only a path segment, so any sized URL can
 * be re-pointed at `original`. Addons rarely ask for it: Nuvio's default catalog addon
 * (catalog.nuvio.tv) supplies backdrops as `w1280`, and the Home hero keeps an item's own backdrop
 * whenever its text is complete. On a 4K TV that put a 1280x720 picture under the hero, three
 * times upscaled, where an account using another metadata addon got `original` (2026-10-13).
 *
 * Anything else — another host, `original` already, a poster size the caller did not mean to
 * upgrade — passes through unchanged.
 */
internal fun fullSizeTmdbBackdrop(url: String?): String? =
    url?.let { TMDB_SIZED_IMAGE.replaceFirst(it, "$1original/") }
