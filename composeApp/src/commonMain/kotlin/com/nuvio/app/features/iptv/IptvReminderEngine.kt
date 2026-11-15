package com.nuvio.app.features.iptv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/*
 * fork:live-tv-departure - fires the reminders the viewer set ("Tune in at"). A plain reminder shows a card two
 * minutes before the start (on the Compose overlay, and on the player's HUD pill over a playing video); a switching
 * reminder puts the channel on at the start. Reminders live in IptvSettings, so a restart keeps them.
 */
object IptvReminderEngine {
    /** A card on the screen. [id] changes with every card so the same text can show twice. */
    data class Card(
        val title: String,
        val line: String,
        val id: Long,
        val options: List<String> = emptyList(),
        val focused: Int = 0,
        /** fork:live-tv-reminder-banner - the reminder behind the card: its picture and the countdown to its start. */
        val reminder: IptvReminder? = null,
        /** When the card went up, for the countdown bar. */
        val shownAt: Long = 0L,
    )

    /**
     * fork:live-tv-reminder-banner - screens that should not be interrupted (the triage questions) hold the card back;
     * one that comes up meanwhile waits and shows, with its full time, once the last hold is let go.
     */
    private var holds = 0
    private var held: Card? = null

    @Synchronized
    fun hold(): () -> Unit {
        holds++
        var released = false
        return {
            synchronized(this) {
                if (!released) {
                    released = true
                    holds--
                    if (holds == 0) held?.let { held = null; show(it) }
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var hide: Job? = null
    private var counter = 0L

    private val _showing = MutableStateFlow<Card?>(null)
    val showing: StateFlow<Card?> = _showing.asStateFlow()

    /** Set by the desktop app: draws a pill on the player's HUD (title, line, milliseconds). */
    @Volatile
    var playerPill: (String, String, Long) -> Unit = { _, _, _ -> }

    private const val CARD_MS = 20_000L

    /**
     * fork:live-tv-reminder-card - set by the desktop app: draws the card with its answers on a playing video's HUD
     * (title, line, options, focused, milliseconds before it closes by itself). False when no video is playing.
     */
    @Volatile
    var playerChoice: ((String, String, List<String>, Int, Long) -> Boolean)? = null

    /** The plain reminder whose card is up, waiting for an answer. */
    private var pending: IptvReminder? = null
    private const val TICK_MS = 15_000L

    /** Starts the clock. Safe to call more than once. */
    fun start() {
        if (job != null) return
        job = scope.launch {
            while (true) {
                runCatching { tick(System.currentTimeMillis()) }
                delay(TICK_MS)
            }
        }
    }

    internal fun tick(now: Long) {
        IptvRepository.ensureLoaded()
        if (IptvRepository.settings.value.reminders.isEmpty()) return
        IptvRepository.takeDueReminders(now).forEach { fire(it, now) }
    }

    private fun fire(reminder: IptvReminder, now: Long) {
        if (reminder.autoSwitch && switchTo(reminder)) {
            // fork:live-tv-brakes - reached through a reminder: chosen, so its end gets the card.
            IptvBrakeEngine.chose(reminder.title, reminder.endMs)
            show(Card("Switching to ${reminder.title}", "${reminder.channelName} · starting now", 0L, reminder = reminder))
        } else if (reminder.autoSwitch) {
            show(Card(reminder.title, IptvReminderRules.cardLine(reminder, now), 0L, reminder = reminder))
        } else {
            // fork:live-tv-reminder-card - a card that asks: switch now, at the start, or not now.
            val line = IptvReminderRules.cardLine(reminder, now)
            val options = IptvReminderRules.CARD_OPTIONS
            pending = reminder
            IptvBrakeEngine.dropCard()
            val inPlayer = runCatching { playerChoice?.invoke(reminder.title, line, options, IptvReminderRules.CARD_FOCUS, CARD_MS) == true }.getOrDefault(false)
            // Over a playing video the HUD asks; the overlay would sit under the video and still take the keys.
            if (!inPlayer) show(Card(reminder.title, line, 0L, options, IptvReminderRules.CARD_FOCUS, reminder)) else _showing.value = null
        }
    }

    /** The answer to the reminder card (from the overlay or the HUD); false when no reminder card was waiting. */
    fun answer(index: Int, now: Long = System.currentTimeMillis()): Boolean {
        val reminder = pending ?: return false
        pending = null
        _showing.value = null
        when (IptvReminderRules.answerOf(IptvReminderRules.CARD_OPTIONS, index)) {
            IptvReminderAnswer.Now -> if (switchTo(reminder)) IptvBrakeEngine.chose(reminder.title, reminder.endMs)
            IptvReminderAnswer.AtStart -> if (reminder.startMs > now) {
                IptvRepository.addReminder(IptvReminderRules.atStart(reminder))
            } else if (switchTo(reminder)) {
                IptvBrakeEngine.chose(reminder.title, reminder.endMs)
            }
            IptvReminderAnswer.Dismiss -> Unit
        }
        return true
    }

    /** Another card took the HUD's place: this one no longer waits for an answer. */
    fun dropCard() {
        pending = null
    }

    private fun switchTo(reminder: IptvReminder): Boolean {
        val playlist = IptvRepository.settings.value.playlists.firstOrNull { it.id == reminder.playlistId } ?: return false
        val channel = IptvRepository.contentState(playlist.id).value.content?.byKey?.get(reminder.channelKey) ?: return false
        return IptvPlaybackRequests.play(playlist, channel)
    }

    @Synchronized
    private fun show(card: Card) {
        if (holds > 0) {
            held = card
            return
        }
        val shown = card.copy(id = ++counter, shownAt = System.currentTimeMillis())
        _showing.value = shown
        runCatching { playerPill(shown.title, shown.line, CARD_MS) }
        hide?.cancel()
        hide = scope.launch {
            delay(CARD_MS)
            if (_showing.value?.id == shown.id) {
                _showing.value = null
                if (shown.options.isNotEmpty()) pending = null
            }
        }
    }
}
