package com.nuvio.app.features.music.home

import java.time.LocalDateTime

/** fork:music-home - the weekday and hour on this computer's clock, for the greeting. */
internal actual fun localWeekdayAndHour(): Pair<Int, Int> =
    LocalDateTime.now().let { it.dayOfWeek.value to it.hour }
