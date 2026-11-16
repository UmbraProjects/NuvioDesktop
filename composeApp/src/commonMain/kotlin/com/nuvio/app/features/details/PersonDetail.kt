package com.nuvio.app.features.details

import com.nuvio.app.features.home.MetaPreview

data class PersonDetail(
    val tmdbId: Int,
    val name: String,
    val biography: String?,
    val birthday: String?,
    val deathday: String?,
    val placeOfBirth: String?,
    val profilePhoto: String?,
    val knownFor: String?,
    val movieCredits: List<MetaPreview>,
    val tvCredits: List<MetaPreview>,
    /** Backdrop of the title they are best known for (TMDB has no backdrops of people). */
    val heroBackdrop: String? = null,
    /** That title's name, shown as a small caption so the backdrop is not mistaken for the person. */
    val heroBackdropTitle: String? = null,
)
