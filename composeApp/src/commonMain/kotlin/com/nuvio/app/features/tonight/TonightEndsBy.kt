package com.nuvio.app.features.tonight

/*
 * fork:tonight - phase 4 of design-notes/tonight-picker.md: Ends By (a timeline from now to
 * bedtime) and The Pile (things added but never started). Pure, tested in features.tonight.
 */

// ---- Ends By -----------------------------------------------------------------------------------

const val TONIGHT_MAX_EPISODE_BLOCKS = 4

/**
 * The rows, best first: everything with a known length, those that end by bedtime ahead of those
 * that do not. Among the ones that fit, a title that fills the evening well (and suits the user's
 * taste) ranks higher than a short one that leaves an hour spare.
 */
fun tonightEndsByRows(pool: List<TonightTitle>, taste: TonightTaste, minutesUntilBed: Int): List<EndsByRowPlan> {
    val bed = minutesUntilBed.coerceAtLeast(1)
    return pool.distinctBy { it.key }.mapNotNull { t ->
        val minutes = t.minutesTonight() ?: return@mapNotNull null
        if (minutes <= 0) return@mapNotNull null
        when (t.kind) {
            TonightKind.Film -> EndsByRowPlan(t, listOf(minutes), episodesBeforeBed = 0, fits = minutes <= bed + 10)
            TonightKind.Show -> {
                val fitting = (bed / minutes).coerceIn(0, TONIGHT_MAX_EPISODE_BLOCKS)
                // The episodes that fit, plus one past bedtime (drawn faded) while there is room for
                // it; a show none of whose episodes fit still shows its one episode, past bedtime.
                val blocks = if (fitting < TONIGHT_MAX_EPISODE_BLOCKS) fitting + 1 else fitting
                EndsByRowPlan(t, List(blocks) { minutes }, episodesBeforeBed = fitting, fits = fitting >= 1)
            }
        }
    }.sortedWith(
        compareByDescending<EndsByRowPlan> { it.fits }
            .thenByDescending { row ->
                val used = row.minutesBeforeBed.toFloat() / bed
                val fill = if (row.fits) used.coerceAtMost(1f) else 0f
                fill * 0.5f + taste.score(row.title) * 0.3f + ((row.title.rating ?: 6.5) / 10.0).toFloat() * 0.2f
            },
    )
}

/**
 * [blockMinutes]: one bar for a film; for a show one block per episode, the first
 * [episodesBeforeBed] of them before bed and at most one more past it (drawn faded).
 */
data class EndsByRowPlan(
    val title: TonightTitle,
    val blockMinutes: List<Int>,
    val episodesBeforeBed: Int,
    val fits: Boolean,
) {
    val minutesBeforeBed: Int
        get() = if (title.kind == TonightKind.Show) blockMinutes.take(episodesBeforeBed).sum() else blockMinutes.sum()

    /** The label at the bar's end: "ends 22:52" or "3 eps · 22:40". */
    fun endLabel(nowMinuteOfDay: Int): String = when (title.kind) {
        TonightKind.Film -> "ends ${clockLabel(nowMinuteOfDay + blockMinutes.sum())}"
        TonightKind.Show -> if (episodesBeforeBed == 0) {
            "1 ep · ${clockLabel(nowMinuteOfDay + blockMinutes.first())}"
        } else {
            "$episodesBeforeBed ep${if (episodesBeforeBed == 1) "" else "s"} · ${clockLabel(nowMinuteOfDay + minutesBeforeBed)}"
        }
    }
}

/** The timeline's span: from the hour before now to a whole hour at least 30 minutes past bed (3 to 6 hours). */
fun tonightEndsByAxis(nowMinuteOfDay: Int, minutesUntilBed: Int): Pair<Int, Int> {
    val start = nowMinuteOfDay - nowMinuteOfDay % 60
    val wantedEnd = nowMinuteOfDay + minutesUntilBed.coerceAtLeast(60) + 30
    var hours = (wantedEnd - start + 59) / 60
    hours = hours.coerceIn(3, 6)
    return start to start + hours * 60
}

// ---- The Pile ----------------------------------------------------------------------------------

data class PileItem(val title: TonightTitle, val waitedDays: Int) {
    /** 0 for something added this week, rising to 1 at six months: how dusty its spine is drawn. */
    val dust: Float get() = ((waitedDays - 7).coerceAtLeast(0) / 173f).coerceIn(0f, 1f)
}

/** Library titles never started or watched, newest first. */
fun tonightPile(pool: List<TonightTitle>, nowEpochMs: Long): List<PileItem> =
    pool.filter { TonightOrigin.Library in it.origins && !it.started && !it.watched && it.addedAtEpochMs != null }
        .distinctBy { it.key }
        .sortedByDescending { it.addedAtEpochMs }
        .map { PileItem(it, (((nowEpochMs - (it.addedAtEpochMs ?: nowEpochMs)) / 86_400_000L).toInt()).coerceAtLeast(0)) }

fun pileAgeLabel(days: Int): String = when {
    days < 1 -> "added today"
    days < 2 -> "added yesterday"
    days < 7 -> "added $days days ago"
    days < 14 -> "added last week"
    days < 60 -> "added ${days / 7} weeks ago"
    days < 365 -> "added ${days / 30} months ago"
    days < 730 -> "added over a year ago"
    else -> "added ${days / 365} years ago"
}
