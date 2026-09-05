package com.nuvio.app.features.watchprogress

expect object CurrentDateProvider {
    fun todayIsoDate(): String

    /** The calendar date [epochMs] falls on **in the viewer's timezone**, as `yyyy-MM-dd`. */
    fun localIsoDateAt(epochMs: Long): String

    /**
     * The first instant of [isoDate] in the viewer's timezone, or null if the date is not a real
     * calendar date (`isoCalendarDateOrNull` accepts day 31 in February, so this can be reached).
     */
    fun startOfLocalDayEpochMs(isoDate: String): Long?
}
