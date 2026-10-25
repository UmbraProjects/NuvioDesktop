package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.identity.ArtworkPalette
import kotlinx.serialization.Serializable

/** How a release is filed in the library. The order is the order of the sections on an artist page. */
@Serializable
enum class ReleaseKind(val label: String, val pluralLabel: String) {
    Album("Album", "Albums"),
    EP("EP", "EPs"),
    Single("Single", "Singles"),
    Compilation("Compilation", "Compilations"),
    Live("Live", "Live"),
    Other("Other", "Other releases"),
}

/** Artwork for an artist. Every field is a plain URL; a source that has none leaves it null. */
@Serializable
data class ArtistArtwork(
    /** Portrait or square photo (list tiles, circular avatar). */
    val thumb: String? = null,
    /** Transparent title logo. */
    val logo: String? = null,
    /** The high-definition transparent logo; preferred over [logo] when present. */
    val logoHd: String? = null,
    /** Transparent clear art: the artist cut out on nothing, to lay over a backdrop. */
    val clearArt: String? = null,
    /** A transparent cut-out of the artist (TheAudioDB "cutout"). */
    val cutout: String? = null,
    val banner: String? = null,
    val wideThumb: String? = null,
    /** Wide backgrounds, best first. */
    val backgrounds: List<String> = emptyList(),
) {
    val bestLogo: String? get() = logoHd ?: logo
    val bestClearArt: String? get() = clearArt ?: cutout
    val bestBackground: String? get() = backgrounds.firstOrNull() ?: wideThumb
}

/** Artwork for a release. */
@Serializable
data class ReleaseArtwork(
    /** Front cover, best first. The UI shows the first and may fall through on a load error. */
    val covers: List<String> = emptyList(),
    /** The printed disc: a round transparent PNG (Fanart.tv "cdart", TheAudioDB "CDart"). */
    val cdArt: String? = null,
    val back: String? = null,
    val spine: String? = null,
    val case3d: String? = null,
) {
    val cover: String? get() = covers.firstOrNull()
}

@Serializable
data class ArtistLink(val label: String, val url: String)

@Serializable
data class LibraryRelease(
    /** Stable key inside one artist: the MusicBrainz release-group id, else a title-and-kind key. */
    val key: String,
    val title: String,
    val kind: ReleaseKind,
    /** "Live", "Remix", "Soundtrack" and the like, from MusicBrainz secondary types. */
    val secondaryTypes: List<String> = emptyList(),
    val year: String? = null,
    /** Full date when a source has it: `2001-03-12`, `2001-03` or `2001`. */
    val releaseDate: String? = null,
    /** YouTube Music album browse id (`MPREb_...`): the key that opens and plays the album. Null when YT Music lists no such release. */
    val ytBrowseId: String? = null,
    val musicBrainzId: String? = null,
    val deezerId: Long? = null,
    val audioDbId: String? = null,
    val trackCount: Int? = null,
    val durationSeconds: Int? = null,
    val label: String? = null,
    val genres: List<String> = emptyList(),
    val style: String? = null,
    val mood: String? = null,
    val description: String? = null,
    val upc: String? = null,
    val explicit: Boolean? = null,
    val art: ReleaseArtwork = ReleaseArtwork(),
    /** Derived from the cover and cached here so it is never recomputed on scroll. Null until first needed. */
    val palette: ArtworkPalette? = null,
) {
    val isPlayable: Boolean get() = ytBrowseId != null
}

@Serializable
data class LibraryArtist(
    /** The YouTube Music channel id (`UC...`) the artist was added from. */
    val id: String,
    val name: String,
    val sortName: String? = null,
    val musicBrainzId: String? = null,
    val deezerId: Long? = null,
    val audioDbId: String? = null,
    /** "Group", "Person", "Orchestra"... as MusicBrainz says. */
    val type: String? = null,
    val country: String? = null,
    val area: String? = null,
    /** Year the group formed or the person was born. */
    val beginYear: String? = null,
    val endYear: String? = null,
    val ended: Boolean = false,
    val disambiguation: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val style: String? = null,
    val mood: String? = null,
    val biography: String? = null,
    /** Where [biography] came from, for the credit line ("TheAudioDB", "Wikipedia"). */
    val biographySource: String? = null,
    val website: String? = null,
    val links: List<ArtistLink> = emptyList(),
    val fans: Long? = null,
    /** MusicBrainz community rating, 0..5. */
    val rating: Double? = null,
    val art: ArtistArtwork = ArtistArtwork(),
    val releases: List<LibraryRelease> = emptyList(),
    /** Which sources answered, for the credit line and for deciding what a refresh may skip. */
    val sources: List<String> = emptyList(),
    val addedAtEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    /** Derived from the artist's art and cached here so it is never recomputed on scroll. Null until first needed. */
    val palette: ArtworkPalette? = null,
    /**
     * True when the other sources found an artist by this name whose releases are not this channel's
     * (a namesake). Their facts and artwork are then left out, so the page shows only what YouTube Music lists.
     */
    val channelMismatch: Boolean = false,
    /** The YouTube Music channel MusicBrainz lists for the namesake it found, when this channel is not it. */
    val suggestedChannelId: String? = null,
) {
    fun releasesOf(kind: ReleaseKind): List<LibraryRelease> = releases.filter { it.kind == kind }

    /** The sections an artist page shows: only kinds that have releases, in [ReleaseKind] order. */
    val sections: List<Pair<ReleaseKind, List<LibraryRelease>>>
        get() = ReleaseKind.entries.mapNotNull { kind -> releasesOf(kind).takeIf { it.isNotEmpty() }?.let { kind to it } }
}

@Serializable
internal data class StoredMusicLibrary(
    val artists: List<LibraryArtist> = emptyList(),
)
