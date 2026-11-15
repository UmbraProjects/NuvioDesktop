package com.nuvio.app.features.livescores

/*
 * fork:live-scores - a pop-up when a followed match has a goal, a try, a red card, a missed penalty or VAR, kicks
 * off, reaches half-time or finishes. Off by default (Settings > Live TV > Live scores). Everything in this package
 * is pure Kotlin except LiveScoreEngine (polling), LiveScoreOverlay (Compose) and the desktop actuals.
 */

enum class LiveSport(val label: String) {
    Football("Football"),
    Rugby("Rugby union"),
    RugbyLeague("Rugby league"),
    Mma("UFC"),
    Boxing("Boxing"),
}

/** Where a match's state comes from. ESPN is the main feed; the others fill gaps. */
enum class LiveFeed { Espn, SportsDb, ApiFootball }

/** A team (or fighter) as a favourite: [id] is the feed's own id, [name] what Settings shows. */
data class LiveTeam(
    val sport: LiveSport,
    val id: String,
    val name: String,
    val shortName: String = name,
)

enum class MatchPhase { Before, Live, HalfTime, Finished, Postponed }

/** One thing that happened in a match, as the feed reports it. [key] is stable across polls. */
data class MatchIncident(
    val key: String,
    val kind: IncidentKind,
    /** The team it belongs to, by the feed's team id; null when the feed does not say. */
    val teamId: String?,
    val clock: String?,
    val player: String?,
    /** For scores: what the feed calls it ("Penalty - Scored", "try", "drop goal"). */
    val detail: String?,
)

enum class IncidentKind {
    Goal,
    OwnGoal,
    PenaltyGoal,
    Try,
    Conversion,
    KickedPoints,
    RedCard,
    YellowCard,
    PenaltyMissed,
    Var,
    Other,
}

data class LiveMatch(
    val feed: LiveFeed,
    val id: String,
    val sport: LiveSport,
    /** The competition's catalogue key when known (see LiveScoreCatalog), else the feed's own league id. */
    val competitionKey: String?,
    val competitionName: String?,
    val home: LiveTeam,
    val away: LiveTeam,
    val homeScore: Int?,
    val awayScore: Int?,
    val phase: MatchPhase,
    /** The feed's own status name (STATUS_HALFTIME...), kept so a change can be told apart. */
    val statusName: String,
    val clock: String?,
    val startEpochMs: Long?,
    val incidents: List<MatchIncident> = emptyList(),
    /** Fight nights: the headline, and the result line once there is one. */
    val headline: String? = null,
    val result: String? = null,
)

/** What the overlay shows. [favourite] decides the big flash and the chime. */
enum class ScoreEventKind(val label: String, val settingLabel: String) {
    Goal("GOAL", "Goals, tries and points"),
    Try("TRY", "Goals, tries and points"),
    Points("POINTS", "Goals, tries and points"),
    RedCard("RED CARD", "Red cards"),
    SinBin("YELLOW CARD", "Red cards"),
    PenaltyMissed("PENALTY MISSED", "Penalties and VAR"),
    Var("VAR", "Penalties and VAR"),
    KickOff("KICK-OFF", "Kick-off, half-time and full-time"),
    HalfTime("HALF-TIME", "Kick-off, half-time and full-time"),
    FullTime("FULL-TIME", "Kick-off, half-time and full-time"),
    FightStart("FIGHT NIGHT", "Kick-off, half-time and full-time"),
    FightResult("RESULT", "Kick-off, half-time and full-time"),
}

/** The four groups the user can switch on and off (all on by default). */
enum class ScoreEventGroup(val label: String) {
    Scores("Goals, tries and points"),
    Cards("Red cards"),
    PenaltiesVar("Penalties and VAR"),
    StartBreakEnd("Kick-off, half-time and full-time"),
}

val ScoreEventKind.group: ScoreEventGroup
    get() = when (this) {
        ScoreEventKind.Goal, ScoreEventKind.Try, ScoreEventKind.Points -> ScoreEventGroup.Scores
        ScoreEventKind.RedCard, ScoreEventKind.SinBin -> ScoreEventGroup.Cards
        ScoreEventKind.PenaltyMissed, ScoreEventKind.Var -> ScoreEventGroup.PenaltiesVar
        ScoreEventKind.KickOff, ScoreEventKind.HalfTime, ScoreEventKind.FullTime,
        ScoreEventKind.FightStart, ScoreEventKind.FightResult -> ScoreEventGroup.StartBreakEnd
    }

data class ScoreEvent(
    val kind: ScoreEventKind,
    val match: LiveMatch,
    /** Which side it belongs to: true home, false away, null neither (kick-off, full-time). */
    val forHome: Boolean?,
    /** The line under the score: "Bruno Fernandes · pen", "Own goal", "Goal ruled out". */
    val detail: String?,
    val clock: String?,
    val favourite: Boolean,
    /** Stable id: the same event is never shown twice. */
    val key: String,
)
