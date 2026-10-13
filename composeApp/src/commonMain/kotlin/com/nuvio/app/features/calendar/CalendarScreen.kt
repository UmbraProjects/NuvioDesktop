package com.nuvio.app.features.calendar

import com.nuvio.app.features.player.AppShortcutAction
import com.nuvio.app.features.player.appShortcutMatches

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.navigationKey
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.mdblist.MdbListCalendarRepository
import com.nuvio.app.features.simkl.SimklCalendarRepository
import com.nuvio.app.features.tracking.CalendarSource
import com.nuvio.app.features.tracking.CalendarSourceRepository
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.resolveCalendarSource
import com.nuvio.app.features.trakt.TraktCalendarEntry
import com.nuvio.app.features.trakt.TraktCalendarRepository
import com.nuvio.app.features.trakt.TraktPlatformClock
import com.nuvio.app.features.trakt.addMonth
import com.nuvio.app.features.trakt.calendarDaysInMonth
import com.nuvio.app.features.trakt.dayOfWeekSundayZero
import com.nuvio.app.features.trakt.epochMsToUtcDate
import com.nuvio.app.features.watched.WatchedRepository
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.calendar_connect_mdblist
import nuvio.composeapp.generated.resources.calendar_connect_trakt
import nuvio.composeapp.generated.resources.calendar_load_failed
import nuvio.composeapp.generated.resources.calendar_next_month
import nuvio.composeapp.generated.resources.calendar_previous_month
import nuvio.composeapp.generated.resources.calendar_title
import org.jetbrains.compose.resources.stringResource

private val MONTH_NAMES = arrayOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)
private val WEEKDAY_LABELS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
private val WEEKDAY_FULL = arrayOf(
    "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
)
private const val MONTH_GRID_MAX_WIDTH_DP = 1040
private const val CELL_ASPECT = 0.75f // 3:4, as the tracker site draws its days
private const val GRID_WEEKS = 6
private val CELL_GAP = 8.dp
private val PANEL_WIDTH = 380.dp
private val PANEL_GAP = 32.dp

/** The locale's first weekday (Monday in most places, Sunday in the US); 0 = Sunday. */
private val WEEK_START: Int = runCatching {
    (java.util.Calendar.getInstance().firstDayOfWeek - 1).coerceIn(0, 6)
}.getOrDefault(1)

/** One day cell of the six-week grid; days of the neighbouring months have a non-zero [monthDelta]. */
private data class GridDay(val year: Int, val month: Int, val day: Int, val monthDelta: Int) {
    val key: String get() = dateKey(year, month, day)
}

/** What the day panel shows per row: one entry, or a show's episodes dropped together. */
private data class CalendarRowGroup(val entries: List<TraktCalendarEntry>) {
    val first: TraktCalendarEntry get() = entries.first()
}

/**
 * The calendar, after the Nuvio Tracker site's: a fixed six-week grid of posters with the
 * focused day's line-up in a panel beside it.
 *
 * Keyboard (HTPC/remote): arrows move the focused day and the panel follows it; Shift+Left/Right
 * change month; Home jumps to today; Enter moves into the panel (Up/Down, Enter opens, Left or
 * Back returns to the grid).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CalendarScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onNavigateHome: (() -> Unit)? = null,
    onItemClick: ((TraktCalendarEntry) -> Unit)? = null,
) {
    val selectedCalendarSource by remember {
        CalendarSourceRepository.ensureLoaded()
        CalendarSourceRepository.uiState
    }.collectAsStateWithLifecycle()
    val connectedProviderIds by TrackingProviderRegistry.connectedProviderIds
        .collectAsStateWithLifecycle()
    val calendarSource = remember(selectedCalendarSource, connectedProviderIds) {
        resolveCalendarSource(selectedCalendarSource) { providerId ->
            providerId in connectedProviderIds
        }
    }
    val uiState by remember(calendarSource) {
        when (calendarSource) {
            CalendarSource.MDBLIST -> {
                MdbListCalendarRepository.ensureLoaded()
                MdbListCalendarRepository.uiState
            }
            CalendarSource.SIMKL -> {
                SimklCalendarRepository.ensureLoaded()
                SimklCalendarRepository.uiState
            }
            CalendarSource.TRAKT -> {
                TraktCalendarRepository.ensureLoaded()
                TraktCalendarRepository.uiState
            }
            CalendarSource.NUVIO -> {
                NuvioCalendarRepository.ensureLoaded()
                NuvioCalendarRepository.uiState
            }
        }
    }.collectAsStateWithLifecycle()
    val watchedState by WatchedRepository.uiState.collectAsStateWithLifecycle()

    val screenFocusRequester = remember { FocusRequester() }
    var calendarHasFocus by remember { mutableStateOf(false) }
    // While navigating away (to home or a detail page) the focus keeper must stand down, or it
    // steals focus back from the destination mid-transition and breaks its keyboard handling.
    var navigatingAway by remember { mutableStateOf(false) }
    // Reset navigatingAway whenever this screen is re-resumed (becomes the active destination).
    // With plain `remember`, the flag resets only when the composable leaves composition and
    // re-enters — but if the user navigates to a detail screen and immediately presses back, the
    // calendar may still be in composition throughout (mid-exit animation), so `remember` never
    // resets and the focus keeper stays silenced. LifecycleResumeEffect fires on every RESUMED
    // transition, covering both the normal (slow) and fast-back cases.
    LifecycleResumeEffect(Unit) {
        navigatingAway = false
        onPauseOrDispose {
            // Header/system Back does not pass through Calendar's key handler. Silence the focus
            // keeper as soon as this destination starts leaving so it cannot steal focus from Home.
            navigatingAway = true
        }
    }

    // UTC "today", consistent with how the repository buckets entries.
    val today = remember(uiState.hasLoaded) { epochMsToUtcDate(TraktPlatformClock.nowEpochMs()) }
    val todayKey = remember(today) { dateKey(today.year, today.month, today.day) }

    // Saveable so returning from a media-info screen restores the last viewed month/cell/row.
    var monthOffset by rememberSaveable { mutableIntStateOf(0) }
    var focusedDay by rememberSaveable { mutableIntStateOf(today.day) }
    var panelFocused by rememberSaveable { mutableStateOf(false) }
    var focusedRow by rememberSaveable { mutableIntStateOf(0) }

    val displayed = remember(today.year, today.month, monthOffset) {
        addMonth(today.year, today.month, monthOffset)
    }
    val displayYear = displayed.first
    val displayMonth = displayed.second
    val totalDays = calendarDaysInMonth(displayYear, displayMonth)
    val focusedKey = dateKey(displayYear, displayMonth, focusedDay.coerceIn(1, totalDays))

    // A show's episodes, and whether each is watched, as rows per day.
    val isWatched: (TraktCalendarEntry) -> Boolean = remember(watchedState.watchedKeys) {
        { entry ->
            if (entry.type == "movie") {
                WatchedRepository.isWatched(id = entry.contentId, type = "movie")
            } else {
                entry.seasonNumber != null && entry.episodeNumber != null &&
                    WatchedRepository.isWatched(
                        id = entry.contentId,
                        type = entry.type,
                        season = entry.seasonNumber,
                        episode = entry.episodeNumber,
                    )
            }
        }
    }
    val focusedGroups = remember(uiState.entriesByDate, focusedKey) {
        groupedRows(uiState.entriesByDate[focusedKey].orEmpty())
    }
    fun goToMonth(delta: Int, day: Int? = null) {
        val newOffset = monthOffset + delta
        val (y, m) = addMonth(today.year, today.month, newOffset)
        monthOffset = newOffset
        focusedDay = (day ?: if (newOffset == 0) today.day else 1).coerceIn(1, calendarDaysInMonth(y, m))
        panelFocused = false
        focusedRow = 0
    }

    // Arrows walk the grid as it's drawn: past the first or last day they carry on into the
    // neighbouring month, whose days are already showing at the edges.
    fun moveFocus(delta: Int) {
        val target = focusedDay + delta
        when {
            target < 1 -> {
                val (py, pm) = addMonth(displayYear, displayMonth, -1)
                goToMonth(-1, calendarDaysInMonth(py, pm) + target)
            }
            target > totalDays -> goToMonth(1, target - totalDays)
            else -> {
                focusedDay = target
                focusedRow = 0
            }
        }
    }

    fun open(group: CalendarRowGroup) {
        navigatingAway = true
        onItemClick?.invoke(group.first)
    }

    // Page months in on demand so the user can go arbitrarily far forward/back.
    LaunchedEffect(displayYear, displayMonth, calendarSource) {
        when (calendarSource) {
            CalendarSource.MDBLIST ->
                MdbListCalendarRepository.ensureMonthsAround(displayYear, displayMonth)
            CalendarSource.SIMKL ->
                SimklCalendarRepository.ensureMonthsAround(displayYear, displayMonth)
            CalendarSource.TRAKT ->
                TraktCalendarRepository.ensureMonthsAround(displayYear, displayMonth)
            CalendarSource.NUVIO ->
                NuvioCalendarRepository.ensureMonthsAround(displayYear, displayMonth)
        }
    }

    // Keyboard-focus keeper. On fast back-navigation the focus node may not be attached yet, or
    // focus gets stolen by the nav transition after a one-shot requestFocus — which left keyboard
    // nav dead until a mouse click. While the calendar doesn't hold focus, keep re-requesting
    // until it sticks (re-runs whenever calendarHasFocus flips).
    LaunchedEffect(calendarHasFocus, navigatingAway) {
        if (!calendarHasFocus && !navigatingAway) {
            repeat(25) {
                runCatching { screenFocusRequester.requestFocus() }
                delay(48)
                if (calendarHasFocus || navigatingAway) return@LaunchedEffect
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.nuvio.colors.background)
            .focusRequester(screenFocusRequester)
            .onFocusChanged { calendarHasFocus = it.hasFocus }
            .focusable()
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { _ ->
                try { screenFocusRequester.requestFocus() } catch (_: Exception) {}
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val navKey = event.navigationKey()
                when {
                    appShortcutMatches(AppShortcutAction.OpenCalendar, event) ||
                        appShortcutMatches(AppShortcutAction.GoHome, event) -> {
                        navigatingAway = true
                        onNavigateHome?.invoke(); true
                    }
                    event.isShiftPressed && navKey == Key.DirectionLeft -> { goToMonth(-1); true }
                    event.isShiftPressed && navKey == Key.DirectionRight -> { goToMonth(1); true }
                    event.key == Key.MoveHome -> { goToMonth(-monthOffset, today.day); true }

                    panelFocused -> when (navKey) {
                        Key.DirectionUp -> { focusedRow = (focusedRow - 1).coerceAtLeast(0); true }
                        Key.DirectionDown -> { focusedRow = (focusedRow + 1).coerceAtMost(focusedGroups.lastIndex); true }
                        Key.Enter, Key.NumPadEnter -> { focusedGroups.getOrNull(focusedRow)?.let(::open); true }
                        Key.DirectionLeft, Key.Escape, Key.Back, Key.Backspace -> { panelFocused = false; true }
                        else -> false
                    }

                    navKey == Key.DirectionLeft -> { moveFocus(-1); true }
                    navKey == Key.DirectionRight -> { moveFocus(1); true }
                    navKey == Key.DirectionUp -> { moveFocus(-7); true }
                    navKey == Key.DirectionDown -> { moveFocus(7); true }
                    navKey == Key.Enter || navKey == Key.NumPadEnter -> {
                        if (focusedGroups.isNotEmpty()) {
                            panelFocused = true
                            focusedRow = focusedRow.coerceIn(0, focusedGroups.lastIndex)
                        }
                        true
                    }
                    else -> false
                }
            }
            // Windows desktop app — no status-bar/notch inset; keep content high.
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
    ) {
        NuvioScreenHeader(
            title = stringResource(Res.string.calendar_title),
            includeStatusBarPadding = false,
            topPadding = 0.dp,
            onBack = onBack?.let { callback ->
                {
                    // Stop reclaiming focus immediately; lifecycle pause can arrive after the
                    // navigation transition has already brought the destination on screen.
                    navigatingAway = true
                    callback()
                }
            },
        )

        Spacer(modifier = Modifier.height(8.dp))

        // The grid sized to fit, the month controls centred over it, and the day panel right
        // beside it — the whole group centred in the window, as on the tracker site.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val navHeight = 44.dp + 16.dp
            val weekdayHeaderHeight = 24.dp + 4.dp
            val availWidth = (maxWidth - PANEL_WIDTH - PANEL_GAP).coerceIn(0.dp, MONTH_GRID_MAX_WIDTH_DP.dp)
            val availHeight = (maxHeight - navHeight - weekdayHeaderHeight).coerceAtLeast(0.dp)
            val cellWByWidth = (availWidth - CELL_GAP * 6) / 7
            val cellHByHeight = (availHeight - CELL_GAP * (GRID_WEEKS - 1)) / GRID_WEEKS
            val cellW = minOf(cellWByWidth, cellHByHeight * CELL_ASPECT).coerceAtLeast(8.dp)
            val cellH = cellW / CELL_ASPECT
            val gridWidth = cellW * 7 + CELL_GAP * 6
            val notConnected = !uiState.isAuthenticated && uiState.hasLoaded
            val firstLoad = uiState.isLoading && uiState.entriesByDate.isEmpty()
            val failed = uiState.errorMessage != null && uiState.entriesByDate.isEmpty()
            val showGrid = !notConnected && !firstLoad && !failed

            Row(
                modifier = Modifier.fillMaxHeight().align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.spacedBy(PANEL_GAP),
            ) {
                Column(modifier = Modifier.width(gridWidth).fillMaxHeight()) {
                    MonthNavHeader(
                        year = displayYear,
                        month = displayMonth,
                        isThisMonth = monthOffset == 0,
                        onPrev = { goToMonth(-1) },
                        onNext = { goToMonth(1) },
                        onToday = { goToMonth(-monthOffset, today.day) },
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    when {
                        notConnected -> CalendarMessage(
                            when (calendarSource) {
                                CalendarSource.MDBLIST -> stringResource(Res.string.calendar_connect_mdblist)
                                CalendarSource.SIMKL -> "Connect your SIMKL account to see your calendar."
                                CalendarSource.TRAKT -> stringResource(Res.string.calendar_connect_trakt)
                                // The local calendar needs no account, so never reports one missing.
                                CalendarSource.NUVIO -> ""
                            },
                        )
                        firstLoad -> Box(
                            modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                        failed -> CalendarMessage(stringResource(Res.string.calendar_load_failed))
                        else -> MonthCalendar(
                            year = displayYear,
                            month = displayMonth,
                            cellWidth = cellW,
                            cellHeight = cellH,
                            todayKey = todayKey,
                            focusedKey = focusedKey,
                            entriesByDate = uiState.entriesByDate,
                            isWatched = isWatched,
                            onDayClick = { day ->
                                if (day.monthDelta != 0) {
                                    goToMonth(day.monthDelta, day.day)
                                } else {
                                    focusedDay = day.day
                                    focusedRow = 0
                                    panelFocused = false
                                }
                            },
                        )
                    }
                }
                if (showGrid) {
                    DayPanel(
                        dayKey = focusedKey,
                        todayKey = todayKey,
                        groups = focusedGroups,
                        focusedRow = focusedRow.takeIf { panelFocused },
                        isWatched = isWatched,
                        onOpen = ::open,
                        // Its heading lines up with the month controls.
                        modifier = Modifier.width(PANEL_WIDTH).fillMaxHeight().padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** Previous / month and year / next, centred over the grid; Today at the grid's right edge when away from it. */
@Composable
private fun MonthNavHeader(
    year: Int,
    month: Int,
    isThisMonth: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth().height(44.dp)) {
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StepButton(
                icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                description = stringResource(Res.string.calendar_previous_month),
                onClick = onPrev,
            )
            Text(
                text = "${MONTH_NAMES[month - 1]} $year",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.width(220.dp),
            )
            StepButton(
                icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                description = stringResource(Res.string.calendar_next_month),
                onClick = onNext,
            )
        }
        if (!isThisMonth) {
            PillButton(text = "Today", onClick = onToday, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}

@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = description, tint = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CalendarMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 60.dp, start = 24.dp, end = 24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Always six weeks, leading and trailing days of the neighbouring months included, so the grid
 * (and everything around it) stays the same size from month to month. The caller sizes the cells
 * so all six weeks fit without scrolling, at the tracker's 3:4 day shape.
 */
@Composable
private fun MonthCalendar(
    year: Int,
    month: Int,
    cellWidth: Dp,
    cellHeight: Dp,
    todayKey: String,
    focusedKey: String,
    entriesByDate: Map<String, List<TraktCalendarEntry>>,
    isWatched: (TraktCalendarEntry) -> Boolean,
    onDayClick: (GridDay) -> Unit,
) {
    val days = remember(year, month) { sixWeeks(year, month) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(24.dp),
            horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
        ) {
            for (i in 0 until 7) {
                Text(
                    text = WEEKDAY_LABELS[(WEEK_START + i) % 7],
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(cellWidth),
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        for (week in 0 until GRID_WEEKS) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
            ) {
                for (dow in 0 until 7) {
                    val day = days[week * 7 + dow]
                    val key = day.key
                    val entries = entriesByDate[key].orEmpty()
                    DayCell(
                        day = day,
                        width = cellWidth,
                        height = cellHeight,
                        isToday = key == todayKey,
                        isPast = key < todayKey,
                        isFocused = key == focusedKey,
                        entries = entries,
                        allWatched = entries.isNotEmpty() && entries.all(isWatched),
                        onClick = { onDayClick(day) },
                    )
                }
            }
            if (week < GRID_WEEKS - 1) Spacer(modifier = Modifier.height(CELL_GAP))
        }
    }
}

private val PAST_POSTER_FILTER = ColorFilter.colorMatrix(
    ColorMatrix().apply {
        setToSaturation(0.75f)
        timesAssign(ColorMatrix(floatArrayOf(
            0.8f, 0f, 0f, 0f, 0f,
            0f, 0.8f, 0f, 0f, 0f,
            0f, 0f, 0.8f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )))
    },
)

@Composable
private fun DayCell(
    day: GridDay,
    width: Dp,
    height: Dp,
    isToday: Boolean,
    isPast: Boolean,
    isFocused: Boolean,
    entries: List<TraktCalendarEntry>,
    allWatched: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val first = entries.firstOrNull()
    val titles = entries.map { it.contentId }.distinct().size
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val outline = when {
        isFocused -> Modifier.border(3.dp, Color.White, shape)
        isToday -> Modifier.border(2.dp, MaterialTheme.nuvio.colors.accent, shape)
        hovered -> Modifier.border(2.dp, Color.White.copy(alpha = 0.35f), shape)
        else -> Modifier
    }
    val compact = width < 70.dp

    Box(
        modifier = Modifier
            .size(width, height)
            .alpha(if (day.monthDelta != 0) 0.4f else 1f)
            .clip(shape)
            .background(
                if (first == null) Color.White.copy(alpha = 0.035f)
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .then(outline)
            .hoverable(hover)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
    ) {
        if (first?.posterUrl != null) {
            NuvioAsyncImage(
                model = first.posterUrl,
                contentDescription = first.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                colorFilter = if (isPast) PAST_POSTER_FILTER else null,
            )
        }
        if (first != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.6f),
                            0.38f to Color.Transparent,
                        ),
                    ),
            )
        }

        Text(
            text = day.day.toString(),
            fontSize = if (compact) 12.sp else 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (first != null) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.TopStart).padding(start = if (compact) 5.dp else 9.dp, top = if (compact) 4.dp else 6.dp),
        )

        if (allWatched && !compact) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(0xEB16161A)),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.nuvio.colors.accent)
            }
        }

        if (titles > 1) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(if (compact) 3.dp else 6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.nuvio.colors.background.copy(alpha = 0.82f))
                    .padding(horizontal = if (compact) 5.dp else 7.dp, vertical = if (compact) 1.dp else 2.dp),
            ) {
                Text(
                    text = "+${titles - 1}",
                    fontSize = if (compact) 10.sp else 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The focused day's line-up, beside the grid. */
@Composable
private fun DayPanel(
    dayKey: String,
    todayKey: String,
    groups: List<CalendarRowGroup>,
    focusedRow: Int?,
    isWatched: (TraktCalendarEntry) -> Boolean,
    onOpen: (CalendarRowGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(dayKey) { listState.scrollToItem(0) }
    KeepRowInView(listState, focusedRow)
    Column(modifier = modifier) {
        DayHeading(dayKey, todayKey)
        if (groups.isEmpty()) {
            Text(
                text = "Nothing airing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(groups, key = { _, g -> g.rowKey() }) { index, group ->
                    EntryRow(
                        group = group,
                        isFocused = index == focusedRow,
                        isWatched = isWatched,
                        onClick = { onOpen(group) },
                    )
                }
            }
        }
    }
}

/** Keeps the keyboard-focused row in view, scrolling by the minimal amount. */
@Composable
private fun KeepRowInView(listState: LazyListState, index: Int?) {
    LaunchedEffect(index) {
        val target = index ?: return@LaunchedEffect
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo.firstOrNull { it.index == target }
        if (visible == null) {
            listState.animateScrollToItem(target.coerceAtLeast(0))
        } else {
            val above = visible.offset - info.viewportStartOffset
            val below = (visible.offset + visible.size) - info.viewportEndOffset
            when {
                above < 0 -> listState.animateScrollBy(above.toFloat())
                below > 0 -> listState.animateScrollBy(below.toFloat())
            }
        }
    }
}

@Composable
private fun DayHeading(dayKey: String, todayKey: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = dayHeading(dayKey, todayKey),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun EntryRow(
    group: CalendarRowGroup,
    isFocused: Boolean,
    isWatched: (TraktCalendarEntry) -> Boolean,
    onClick: () -> Unit,
) {
    val e = group.first
    val last = group.entries.last()
    val many = group.entries.size > 1
    val isMovie = e.type == "movie"
    val time = calendarAirTimeLabel(e.airAt).orEmpty()
    val what = when {
        isMovie -> "Film release"
        many -> "S${e.seasonNumber} · E${e.episodeNumber}–E${last.episodeNumber} · ${group.entries.size} episodes"
        e.seasonNumber != null && e.episodeNumber != null ->
            "S${e.seasonNumber} · E${e.episodeNumber}" + (e.episodeTitle?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        else -> null
    }
    val watched = group.entries.all(isWatched)
    val finale = group.entries.any { it.isFinale }
    val premiere = !isMovie && e.episodeNumber == 1
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val accent = MaterialTheme.nuvio.colors.accent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    isFocused -> accent.copy(alpha = 0.18f)
                    hovered -> Color.White.copy(alpha = 0.05f)
                    else -> Color.Transparent
                },
            )
            .then(if (isFocused) Modifier.border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(14.dp)) else Modifier)
            .hoverable(hover)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = time,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(48.dp),
        )
        Box(
            modifier = Modifier
                .size(width = 46.dp, height = 69.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (e.posterUrl != null) {
                NuvioAsyncImage(
                    model = e.posterUrl,
                    contentDescription = e.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = e.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            what?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (premiere || finale || watched) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (premiere) {
                        CalendarChip(
                            text = if (e.seasonNumber == 1) "Series premiere" else "Season premiere",
                            background = accent.copy(alpha = 0.28f),
                            content = Color.White,
                        )
                    }
                    if (finale) {
                        CalendarChip(text = "Finale", background = Color.White.copy(alpha = 0.1f), content = Color(0xFFD4D4D8))
                    }
                    if (watched) {
                        Text(
                            text = "✓ Watched",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = accent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarChip(text: String, background: Color, content: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text = text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = content, maxLines = 1)
    }
}

/** A show's episodes on one day (a whole season dropped at once) as one row, in airing order. */
private fun groupedRows(entries: List<TraktCalendarEntry>): List<CalendarRowGroup> {
    val out = mutableListOf<MutableList<TraktCalendarEntry>>()
    val at = mutableMapOf<String, MutableList<TraktCalendarEntry>>()
    entries
        // A source can list the same episode twice; one row each, and unique lazy keys.
        .distinctBy { it.rowKeyOf() }
        .sortedWith(compareBy<TraktCalendarEntry>({ it.airAt ?: "~" }, { it.title }, { it.seasonNumber ?: 0 }, { it.episodeNumber ?: 0 }))
        .forEach { e ->
            if (e.type == "movie" || e.seasonNumber == null) {
                out += mutableListOf(e)
                return@forEach
            }
            val k = "${e.contentId}|${e.seasonNumber}"
            at[k]?.add(e) ?: mutableListOf(e).also { at[k] = it; out += it }
        }
    return out.map { group -> CalendarRowGroup(group.sortedBy { it.episodeNumber ?: 0 }) }
}

private fun TraktCalendarEntry.rowKeyOf(): String = "$contentId|$type|$seasonNumber|$episodeNumber"

private fun CalendarRowGroup.rowKey(): String = first.rowKeyOf()

/** The days of the six-week grid around [year]/[month], starting on the locale's first weekday. */
private fun sixWeeks(year: Int, month: Int): List<GridDay> {
    val lead = (dayOfWeekSundayZero(year, month, 1) - WEEK_START + 7) % 7
    val (py, pm) = addMonth(year, month, -1)
    val (ny, nm) = addMonth(year, month, 1)
    val prevDays = calendarDaysInMonth(py, pm)
    val days = calendarDaysInMonth(year, month)
    return List(GRID_WEEKS * 7) { i ->
        val n = i - lead + 1
        when {
            n < 1 -> GridDay(py, pm, prevDays + n, -1)
            n > days -> GridDay(ny, nm, n - days, 1)
            else -> GridDay(year, month, n, 0)
        }
    }
}

/** "Today · Monday 12 October", "Tomorrow · …", "Yesterday · …", else the date. */
private fun dayHeading(key: String, todayKey: String): String {
    val year = key.substring(0, 4).toIntOrNull() ?: return key
    val month = key.substring(5, 7).toIntOrNull() ?: return key
    val day = key.substring(8, 10).toIntOrNull() ?: return key
    val date = "${WEEKDAY_FULL[dayOfWeekSundayZero(year, month, day)]} $day ${MONTH_NAMES[month - 1]}"
    val diff = runCatching {
        java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(todayKey), java.time.LocalDate.parse(key))
    }.getOrNull()
    return when (diff) {
        0L -> "Today · $date"
        1L -> "Tomorrow · $date"
        -1L -> "Yesterday · $date"
        else -> date
    }
}

/**
 * The local clock time of an airing ("21:00"), or null when the source gave no real time —
 * a bare date, or a midnight placeholder in the source's own offset.
 */
internal fun calendarAirTimeLabel(airAt: String?): String? {
    val raw = airAt?.trim()?.takeIf { 'T' in it } ?: return null
    if (raw.substringAfter('T').startsWith("00:00:00")) return null
    val instant = runCatching { java.time.OffsetDateTime.parse(raw).toInstant() }
        .recoverCatching { java.time.Instant.parse(raw) }
        .getOrNull() ?: return null
    return java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        .withZone(java.time.ZoneId.systemDefault())
        .format(instant)
}

private fun dateKey(year: Int, month: Int, day: Int): String {
    val mm = if (month < 10) "0$month" else "$month"
    val dd = if (day < 10) "0$day" else "$day"
    return "${year.toString().padStart(4, '0')}-$mm-$dd"
}
