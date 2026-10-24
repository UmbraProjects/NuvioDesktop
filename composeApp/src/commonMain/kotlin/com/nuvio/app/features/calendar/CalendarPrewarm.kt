package com.nuvio.app.features.calendar

import co.touchlab.kermit.Logger
import com.nuvio.app.features.mdblist.MdbListCalendarRepository
import com.nuvio.app.features.simkl.SimklCalendarRepository
import com.nuvio.app.features.tracking.CalendarSource
import com.nuvio.app.features.tracking.CalendarSourceRepository
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.resolveCalendarSource
import com.nuvio.app.features.trakt.TraktCalendarRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Warms the active calendar source's repository in the background so the Calendar screen opens
 * onto data already in memory instead of a spinner.
 *
 * Every calendar repository caches for the life of the process but nothing on disk, and the first
 * open of a session pays the full fetch — for SIMKL that is one request per followed show plus one
 * per watchlisted movie, a few seconds even on a fast connection. Scheduling that fetch once the
 * startup warm has finished and the app has settled moves the wait to a moment nobody is looking.
 *
 * Runs once per schedule call; the repositories themselves dedupe, so a screen opened before the
 * prewarm fires simply joins the load already in flight.
 */
object CalendarPrewarm {
    private val log = Logger.withTag("CalendarPrewarm")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pending: Job? = null

    /** Delay after the deferred startup warm before touching the calendar provider. */
    private const val IDLE_DELAY_MS = 10_000L

    /** Schedules a prewarm after [IDLE_DELAY_MS], replacing any earlier one still waiting. */
    fun scheduleAsync() {
        pending?.cancel()
        pending = scope.launch {
            delay(IDLE_DELAY_MS)
            prewarm()
        }
    }

    private fun prewarm() {
        CalendarSourceRepository.ensureLoaded()
        val connected = TrackingProviderRegistry.connectedProviderIds.value
        val source = resolveCalendarSource(CalendarSourceRepository.selectedSource()) { providerId ->
            providerId in connected
        }
        if (source.providerId !in connected) {
            log.d { "skipped: calendar source $source is not connected" }
            return
        }
        log.i { "prewarming $source calendar" }
        when (source) {
            CalendarSource.MDBLIST -> MdbListCalendarRepository.ensureLoaded()
            CalendarSource.SIMKL -> SimklCalendarRepository.ensureLoaded()
            CalendarSource.TRAKT -> TraktCalendarRepository.ensureLoaded()
        }
    }
}
