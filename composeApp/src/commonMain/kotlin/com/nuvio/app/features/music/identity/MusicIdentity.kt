package com.nuvio.app.features.music.identity

import com.nuvio.app.features.music.library.LibraryArtist
import com.nuvio.app.features.music.library.LibraryRelease

/**
 * One resolved object per artist and per release that every music screen reads, so a tile, a page,
 * the mini player and now playing cannot disagree about which picture, logo or colour an artist has.
 * Built from the stored [LibraryArtist] / [LibraryRelease]; holds URLs and a palette, never bitmaps.
 */

/** Which rung of the hero fallback chain produced [HeroImage]. */
enum class HeroSource {
    ArtistBackground,
    Banner,
    WideThumb,

    /** A release cover: the UI should blur it and fade it, it is not a wide picture. */
    BlurredCover,
}

data class HeroImage(val url: String, val source: HeroSource)

/**
 * How lively a room should feel, from the artist's mood and style. Stage and Rooms read it in later
 * phases (slow drift for [Calm], a tighter pulse for [Driven]); [Steady] is the default.
 */
enum class MotionProfile { Calm, Steady, Driven }

data class ArtistIdentity(
    val artistId: String,
    val name: String,
    /** Wide picture for a room: artist background, banner, wide thumb, blurred cover; null means draw the palette gradient. */
    val hero: HeroImage?,
    /** Every background, best first, for the cycling backdrop. Empty when there are none. */
    val backgrounds: List<String>,
    /** Title logo; null means set the name in the display font. */
    val logoUrl: String?,
    /** Cut-out or clear art for the portrait; null means a circular thumb. */
    val cutoutUrl: String?,
    /** Avatar; null means initials on the palette colour. */
    val thumbUrl: String?,
    /** Null until it has been derived (or when no art could yield one): callers then keep the app accent. */
    val palette: ArtworkPalette?,
    val motion: MotionProfile,
    /** Images to sample for [palette], best first. The first is the cache key ([ArtworkPalette.forUrl]). */
    val paletteSources: List<String>,
) {
    /** True when [palette] is missing or was derived for art the artist no longer has. */
    val needsPalette: Boolean
        get() = paletteSources.isNotEmpty() && palette?.forUrl != paletteSources.first()

}

data class ReleaseIdentity(
    val releaseKey: String,
    val title: String,
    val coverUrl: String?,
    /** Printed disc art for the album object and the spinner. */
    val cdArtUrl: String?,
    val spineUrl: String?,
    val case3dUrl: String?,
    val backUrl: String?,
    /** The release's own palette, else the artist's. Null means the app accent. */
    val palette: ArtworkPalette?,
    val motion: MotionProfile,
)

/** Builds the identity for [this] artist. Cheap and pure: call it wherever it is needed. */
fun LibraryArtist.identity(): ArtistIdentity {
    val newestCover = releases
        .filter { it.art.cover != null }
        .maxByOrNull { it.releaseDate ?: it.year ?: "" }
        ?.art?.cover
    val hero = art.backgrounds.firstOrNull()?.let { HeroImage(it, HeroSource.ArtistBackground) }
        ?: art.banner?.let { HeroImage(it, HeroSource.Banner) }
        ?: art.wideThumb?.let { HeroImage(it, HeroSource.WideThumb) }
        ?: newestCover?.let { HeroImage(it, HeroSource.BlurredCover) }
    // Backgrounds are often dark and grainy; a photo carries the artist's colour better, so
    // it is tried second, then a cover. The first entry is the cache key.
    val sources = listOfNotNull(art.backgrounds.firstOrNull() ?: art.wideThumb, art.thumb, newestCover).distinct()
    return ArtistIdentity(
        artistId = id,
        name = name,
        hero = hero,
        backgrounds = art.backgrounds,
        logoUrl = art.bestLogo,
        cutoutUrl = art.bestClearArt,
        thumbUrl = art.thumb,
        palette = palette,
        motion = motionProfile(mood, style, genres),
        paletteSources = sources,
    )
}

/** Builds the identity for release [this]; [artist] supplies the palette and motion when the release has none. */
fun LibraryRelease.identity(artist: LibraryArtist? = null): ReleaseIdentity = ReleaseIdentity(
    releaseKey = key,
    title = title,
    coverUrl = art.cover,
    cdArtUrl = art.cdArt,
    spineUrl = art.spine,
    case3dUrl = art.case3d,
    backUrl = art.back,
    palette = palette ?: artist?.palette,
    motion = motionProfile(mood ?: artist?.mood, style ?: artist?.style, genres.ifEmpty { artist?.genres.orEmpty() }),
)

private val calmWords = listOf(
    "melanchol", "sad", "somber", "sombre", "mellow", "peaceful", "relax", "calm", "dreamy", "gentle",
    "tender", "wistful", "romantic", "sentimental", "quiet", "soothing", "atmospheric", "ethereal",
)
private val drivenWords = listOf(
    "aggressive", "angry", "intense", "energetic", "fierce", "rebellious", "furious", "raw", "heavy",
    "defiant", "frantic", "explosive", "wild", "powerful",
)
private val driven = listOf("metal", "punk", "hardcore", "industrial", "thrash", "grindcore", "drill")
private val calm = listOf("ambient", "folk", "classical", "lo-fi", "lofi", "new age", "acoustic", "singer-songwriter", "drone")

/**
 * Mood decides first (it describes feel, where style and genre describe form); when mood is absent
 * or says nothing either way, style and genres are checked, driven before calm so a "folk punk"
 * act is lively. No signal at all gives [MotionProfile.Steady].
 */
fun motionProfile(mood: String?, style: String?, genres: List<String> = emptyList()): MotionProfile {
    val m = mood?.lowercase().orEmpty()
    val drivenMood = drivenWords.any { it in m }
    val calmMood = calmWords.any { it in m }
    if (drivenMood && !calmMood) return MotionProfile.Driven
    if (calmMood && !drivenMood) return MotionProfile.Calm
    val form = (listOfNotNull(style) + genres).joinToString(" ").lowercase()
    return when {
        driven.any { it in form } -> MotionProfile.Driven
        calm.any { it in form } -> MotionProfile.Calm
        else -> MotionProfile.Steady
    }
}
