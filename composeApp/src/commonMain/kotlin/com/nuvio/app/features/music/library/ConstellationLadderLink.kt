package com.nuvio.app.features.music.library

/**
 * Library views, phase C5: the Constellation's link to the ladder, as pure data (no Compose, so it is unit-tested).
 *
 *  - The found-via ring and line: an artist saved from a ladder carries how it was found ([LibraryArtist.foundVia]).
 *    Its star gets a dashed ring, and when the label names an artist who is also in the Library ("Out from Sting",
 *    "Where next from Sting") a dashed line runs from it to that artist's star.
 *  - The star menu (right-click, or M or the menu key on the selected star): Play, Play cluster (C6, see
 *    ConstellationPlay.kt), Ladder from here, Climb into, Ladder into the star's genre group, Open artist room. The same
 *    actions are on the side panel.
 *  - The cluster "Ladder into": with a genre chip chosen, a pill leads a ladder into that genre.
 */

private const val LADDER_PREFIX = "ladder: "
private const val WHERE_NEXT_PREFIX = "Where next from "
private const val OUT_FROM_PREFIX = "Out from "

/** True when the artist was saved from a ladder: the ladder screen's "ladder: ..." label or the artist page's "Where next from ...". */
internal fun foundViaLadder(foundVia: String?): Boolean =
    foundVia != null && (foundVia.startsWith(LADDER_PREFIX.trim()) || foundVia.startsWith(WHERE_NEXT_PREFIX))

/** How the artist was found, without the stored "ladder: " prefix: "Into shoegaze", "Out from Sting", "Where next from Sting". */
internal fun foundViaText(foundVia: String?): String? =
    foundVia?.removePrefix(LADDER_PREFIX.trim())?.trim()?.takeIf { it.isNotEmpty() }

/** The artist a ladder led out from, when the label names one ("Out from Sting", "Where next from Sting"); null for "Into ..." labels. */
internal fun foundViaSource(foundVia: String?): String? {
    val text = foundViaText(foundVia) ?: return null
    val name = when {
        text.startsWith(WHERE_NEXT_PREFIX) -> text.removePrefix(WHERE_NEXT_PREFIX)
        text.startsWith(OUT_FROM_PREFIX) -> text.removePrefix(OUT_FROM_PREFIX)
        else -> return null
    }
    return name.trim().takeIf { it.isNotEmpty() }
}

/** A line on the map from an artist found through a ladder to the Library artist the ladder led out from. */
internal data class FoundViaLink(val foundId: String, val sourceId: String)

/**
 * The found-via lines: one for every ladder-found artist whose source artist is also in [artists]. An "Out from" label
 * can name a record rather than an artist; it only links when an artist of that name is saved, so a record title that
 * is not an artist's name draws nothing. An artist is never linked to itself.
 */
internal fun foundViaLinks(artists: List<LibraryArtist>): List<FoundViaLink> =
    artists.mapNotNull { found ->
        if (!foundViaLadder(found.foundVia)) return@mapNotNull null
        val source = foundViaSource(found.foundVia) ?: return@mapNotNull null
        val from = artists.firstOrNull { it.id != found.id && sameArtistName(it.name, source) } ?: return@mapNotNull null
        FoundViaLink(found.id, from.id)
    }

/** The stars that get the found-via ring. */
internal fun ladderFoundIds(artists: List<LibraryArtist>): Set<String> =
    artists.filter { foundViaLadder(it.foundVia) }.map { it.id }.toSet()

/** A cluster can lead a ladder when its label is a genre (a group named after a mood cannot). */
internal fun canLadderInto(label: String?): Boolean = label != null && canonicalGenre(label) != null

/** The ladder into a genre group, by the genre as the cluster's label spells it. */
internal fun clusterLadderRequest(label: String): LadderRequest = LadderRequest.Genre(label)

internal enum class StarAction { Play, PlayCluster, LadderFrom, ClimbInto, LadderIntoCluster, OpenRoom }

/**
 * The star menu's actions, in order. Play cluster needs a group ([clusterLabel] not null); "Ladder into the genre" is
 * left out when the group is not a genre.
 */
internal fun starActions(clusterLabel: String?): List<StarAction> = buildList {
    add(StarAction.Play)
    if (clusterLabel != null) add(StarAction.PlayCluster)
    add(StarAction.LadderFrom)
    add(StarAction.ClimbInto)
    if (canLadderInto(clusterLabel)) add(StarAction.LadderIntoCluster)
    add(StarAction.OpenRoom)
}

internal fun starActionLabel(action: StarAction, artist: String, clusterLabel: String?): String = when (action) {
    StarAction.Play -> "Play"
    StarAction.PlayCluster -> "Play all ${clusterLabel.orEmpty()}"
    StarAction.LadderFrom -> "Ladder from here"
    StarAction.ClimbInto -> "Climb into $artist"
    StarAction.LadderIntoCluster -> "Ladder into ${clusterLabel.orEmpty()}"
    StarAction.OpenRoom -> "Open artist room"
}

/** The ladder an action opens, or null for the ones that are not a ladder (the play actions, Open artist room, or a cluster that is not a genre). */
internal fun starActionRequest(action: StarAction, artist: String, clusterLabel: String?): LadderRequest? = when (action) {
    StarAction.LadderFrom -> LadderRequest.Artist(artist, LadderMode.OutFrom)
    StarAction.ClimbInto -> LadderRequest.Artist(artist, LadderMode.Into)
    StarAction.LadderIntoCluster -> clusterLabel?.takeIf { canLadderInto(it) }?.let(::clusterLadderRequest)
    StarAction.Play, StarAction.PlayCluster, StarAction.OpenRoom -> null
}
