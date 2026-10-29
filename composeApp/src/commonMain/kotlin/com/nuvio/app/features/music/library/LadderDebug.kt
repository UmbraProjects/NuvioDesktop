package com.nuvio.app.features.music.library

/**
 * Item 5: the ladder debug view and log. [ladderDebugLines] turns a built ladder into plain text: what the candidate
 * pool looked like, and for every rung the purity, proximity, the score it won with, how much tag evidence sits behind
 * it and who it beat. The ladder screen shows it on F3 and writes it to the log under the tag "LadderDebug" every time a
 * ladder is built, so a bad ladder can be sent back as text and the weights tuned from it.
 *
 * A ladder read back from disk, or one whose rungs the person already marked, carries no build details; the text says so.
 */
internal fun ladderDebugLines(ladder: Ladder, title: String? = null): List<String> {
    val lines = ArrayList<String>()
    lines += "Ladder${title?.let { ": $it" }.orEmpty()} | target ${ladder.target} | ${if (ladder.coldStart) "cold start (nothing in the Library to compare)" else "compared with the Library"}"
    val pool = ladder.pool
    if (pool == null) {
        lines += "No build details: this ladder was kept from an earlier build."
    } else {
        lines += "Pool: ${pool.candidates} candidates, ${pool.owned} already saved, ${pool.offGenre} not of the genre, " +
            "${pool.scored} scored, ${pool.eligible} eligible (purity ${MIN_PURITY} or more, ${MIN_TAG_VOTES}+ tag votes while enough have them)"
        lines += "Purity of those: min ${pool.purityMin}, median ${pool.purityMedian}, max ${pool.purityMax} | " +
            "dial ${pool.dial}, first rung starts ${pool.start} of the way up"
    }
    lines += "Weights: purity prior $PURITY_PRIOR_VOTES votes, fit penalty $PURITY_FIT_PENALTY, playable bonus $PLAYABLE_BONUS, fans bonus up to $FANS_BONUS"
    if (ladder.rungs.isEmpty()) lines += "No rungs."
    for (rung in ladder.rungs) {
        val pick = rung.pick
        val thin = if (pick != null && pick.tagVotes <= THIN_TAG_VOTES) "  THIN TAGS" else ""
        lines += "${rung.step}. ${rung.candidate.name} | purity ${two(rung.purity)} | proximity ${two(rung.proximity)}" +
            (pick?.let { " | tag votes ${it.tagVotes}" }.orEmpty()) + thin
        if (pick != null) {
            lines += "   slot ${pick.slot} wanted adjusted purity ${pick.rungTarget} (this one ${two(rung.purity)}, plain share ${pick.rawPurity}), missed by ${pick.miss} of the range | fit ${pick.fit} = " +
                "proximity ${two(rung.proximity)} + flavour ${pick.flavour} + bonus ${pick.bonus} - $PURITY_FIT_PENALTY x miss ${pick.miss}"
            if (pick.runnersUp.isNotEmpty()) lines += "   beat: ${pick.runnersUp.joinToString(", ")}"
        } else {
            lines += "   no build details (a marked or kept rung)"
        }
        lines += "   near: ${rung.anchorName ?: "nothing in the Library"} | fans ${rung.candidate.fans ?: "unknown"} | " +
            "playable ${if (rung.candidate.playable) "yes" else "no"}"
    }
    return lines
}

/** A rung with this many genre votes or fewer stands on very little evidence: one vote for the genre is "100% pure". */
internal const val THIN_TAG_VOTES = 2

private fun two(x: Double): Double = (x * 100).let { kotlin.math.round(it) } / 100.0
