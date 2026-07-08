package com.nuvio.app.features.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.nuvio.app.core.ui.navigationKey
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.trakt.TraktCalendarEntry
import com.nuvio.app.features.simkl.SimklAuthRepository
import com.nuvio.app.features.simkl.SimklCalendarRepository
import com.nuvio.app.features.simkl.SimklSettingsRepository
import com.nuvio.app.features.trakt.TraktCalendarRepository
import com.nuvio.app.features.trakt.TraktPlatformClock
import com.nuvio.app.features.trakt.addMonth
import com.nuvio.app.features.trakt.calendarDaysInMonth
import com.nuvio.app.features.trakt.dayOfWeekSundayZero
import com.nuvio.app.features.trakt.epochMsToUtcDate
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
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
private const val MONTH_GRID_MAX_WIDTH_DP = 760
private const val CELL_ASPECT = 0.68f // poster-ish width/height
private val CELL_GAP = 4.dp

/** A tapped day, shown in the day-detail (selection) panel. */
private data class CalendarDaySelection(
    val dateKey: String,
    val entries: List<TraktCalendarEntry>,
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CalendarScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onNavigateHome: (() -> Unit)? = null,
    onItemClick: ((TraktCalendarEntry) -> Unit)? = null,
) {
    val useSimkl = remember {
        SimklAuthRepository.isAuthenticated.value && SimklSettingsRepository.isSimklCalendarSource()
    }
    val uiState by remember(useSimkl) {
        if (useSimkl) {
            SimklCalendarRepository.ensureLoaded()
            SimklCalendarRepository.uiState
        } else {
            TraktCalendarRepository.ensureLoaded()
            TraktCalendarRepository.uiState
        }
    }.collectAsStateWithLifecycle()

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
        onPauseOrDispose { }
    }

    // UTC "today", consistent with how the repository buckets entries.
    val today = remember(uiState.hasLoaded) { epochMsToUtcDate(TraktPlatformClock.nowEpochMs()) }
    val todayKey = remember(today) { dateKey(today.year, today.month, today.day) }

    // Saveable so returning from a media-info screen restores the last viewed month/cell.
    var monthOffset by rememberSaveable { mutableIntStateOf(0) }
    var focusedDay by rememberSaveable { mutableIntStateOf(today.day) }

    val displayed = remember(today.year, today.month, monthOffset) {
        addMonth(today.year, today.month, monthOffset)
    }
    val displayYear = displayed.first
    val displayMonth = displayed.second
    val totalDays = calendarDaysInMonth(displayYear, displayMonth)

    var selectedDay by remember { mutableStateOf<CalendarDaySelection?>(null) }

    fun goToMonth(delta: Int) {
        val newOffset = monthOffset + delta
        val (y, m) = addMonth(today.year, today.month, newOffset)
        monthOffset = newOffset
        focusedDay = (if (newOffset == 0) today.day else 1).coerceIn(1, calendarDaysInMonth(y, m))
    }

    fun openDay(key: String, entries: List<TraktCalendarEntry>) {
        if (entries.isNotEmpty()) selectedDay = CalendarDaySelection(dateKey = key, entries = entries)
    }

    // Page months in on demand so the user can go arbitrarily far forward/back.
    LaunchedEffect(displayYear, displayMonth, useSimkl) {
        if (useSimkl) SimklCalendarRepository.ensureMonthsAround(displayYear, displayMonth)
        else TraktCalendarRepository.ensureMonthsAround(displayYear, displayMonth)
    }

    // Keyboard-focus keeper. On fast back-navigation the focus node may not be attached yet, or
    // focus gets stolen by the nav transition after a one-shot requestFocus — which left keyboard
    // nav dead until a mouse click. While no sheet is open and the calendar doesn't hold focus,
    // keep re-requesting until it sticks (re-runs whenever calendarHasFocus flips).
    LaunchedEffect(selectedDay, calendarHasFocus, navigatingAway) {
        if (selectedDay == null && !calendarHasFocus && !navigatingAway) {
            repeat(25) {
                runCatching { screenFocusRequester.requestFocus() }
                delay(48)
                if (calendarHasFocus || selectedDay != null || navigatingAway) return@LaunchedEffect
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
                if (selectedDay != null) return@onPreviewKeyEvent false
                val navKey = event.navigationKey()
                when {
                    event.key == Key.C || event.key == Key.H -> {
                        navigatingAway = true
                        onNavigateHome?.invoke(); true
                    }
                    event.isShiftPressed && navKey == Key.DirectionLeft -> { goToMonth(-1); true }
                    event.isShiftPressed && navKey == Key.DirectionRight -> { goToMonth(1); true }
                    navKey == Key.DirectionLeft -> {
                        focusedDay = (focusedDay - 1).coerceAtLeast(1); true
                    }
                    navKey == Key.DirectionRight -> {
                        focusedDay = (focusedDay + 1).coerceAtMost(totalDays); true
                    }
                    navKey == Key.DirectionUp -> {
                        focusedDay = (focusedDay - 7).coerceAtLeast(1); true
                    }
                    navKey == Key.DirectionDown -> {
                        focusedDay = (focusedDay + 7).coerceAtMost(totalDays); true
                    }
                    event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                        val key = dateKey(displayYear, displayMonth, focusedDay)
                        openDay(key, uiState.entriesByDate[key].orEmpty())
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
            onBack = onBack,
        )

        Spacer(modifier = Modifier.height(8.dp))

        MonthNavHeader(
            year = displayYear,
            month = displayMonth,
            onPrev = { goToMonth(-1) },
            onNext = { goToMonth(1) },
        )

        Spacer(modifier = Modifier.height(8.dp))

        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            when {
                !uiState.isAuthenticated && uiState.hasLoaded -> {
                    CalendarMessage(
                        if (useSimkl) "Connect your SIMKL account to see your calendar."
                        else stringResource(Res.string.calendar_connect_trakt)
                    )
                }

                uiState.isLoading && uiState.entriesByDate.isEmpty() -> {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 80.dp),
                    )
                }

                uiState.errorMessage != null && uiState.entriesByDate.isEmpty() -> {
                    CalendarMessage(stringResource(Res.string.calendar_load_failed))
                }

                else -> {
                    MonthCalendar(
                        year = displayYear,
                        month = displayMonth,
                        todayKey = todayKey,
                        focusedDay = focusedDay,
                        entriesByDate = uiState.entriesByDate,
                        onDaySelected = ::openDay,
                    )
                }
            }
        }
    }

    selectedDay?.let { selection ->
        CalendarDayDialog(
            selection = selection,
            onDismiss = { selectedDay = null },
            onItemClick = { entry ->
                navigatingAway = true
                onItemClick?.invoke(entry)
            },
        )
    }
}

@Composable
private fun MonthNavHeader(
    year: Int,
    month: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Row(
            modifier = Modifier.widthIn(max = MONTH_GRID_MAX_WIDTH_DP.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrev) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    contentDescription = stringResource(Res.string.calendar_previous_month),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = "${MONTH_NAMES[month - 1]} $year",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNext) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = stringResource(Res.string.calendar_next_month),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
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
 * Lays the whole month out to fit the available area (no scrolling). Cell size is derived
 * from both the available width and height so the last week never gets cut off, while keeping
 * a portrait poster shape.
 */
@Composable
private fun MonthCalendar(
    year: Int,
    month: Int,
    todayKey: String,
    focusedDay: Int,
    entriesByDate: Map<String, List<TraktCalendarEntry>>,
    onDaySelected: (String, List<TraktCalendarEntry>) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        val weekdayHeaderHeight = 22.dp
        val leadingBlanks = dayOfWeekSundayZero(year, month, 1)
        val totalDays = calendarDaysInMonth(year, month)
        val weeks = (leadingBlanks + totalDays + 6) / 7

        val availWidth = maxWidth.coerceAtMost(MONTH_GRID_MAX_WIDTH_DP.dp)
        val availHeight = (maxHeight - weekdayHeaderHeight - 8.dp).coerceAtLeast(0.dp)
        val cellWByWidth = (availWidth - CELL_GAP * 6) / 7
        val cellHByHeight = (availHeight - CELL_GAP * (weeks - 1)) / weeks
        val cellW = minOf(cellWByWidth, cellHByHeight * CELL_ASPECT).coerceAtLeast(8.dp)
        val cellH = cellW / CELL_ASPECT
        val gridWidth = cellW * 7 + CELL_GAP * 6

        Column(modifier = Modifier.width(gridWidth)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(weekdayHeaderHeight),
                horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
            ) {
                WEEKDAY_LABELS.forEach { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(cellW),
                    )
                }
            }

            for (week in 0 until weeks) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
                ) {
                    for (dow in 0 until 7) {
                        val cellIndex = week * 7 + dow
                        val dayNumber = cellIndex - leadingBlanks + 1
                        Box(modifier = Modifier.width(cellW)) {
                            if (dayNumber in 1..totalDays) {
                                val key = dateKey(year, month, dayNumber)
                                val entries = entriesByDate[key].orEmpty()
                                DayCell(
                                    dayNumber = dayNumber,
                                    cellHeight = cellH,
                                    isToday = key == todayKey,
                                    isFocused = dayNumber == focusedDay,
                                    entries = entries,
                                    onClick = if (entries.isNotEmpty()) {
                                        { onDaySelected(key, entries) }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                }
                if (week < weeks - 1) Spacer(modifier = Modifier.height(CELL_GAP))
            }
        }
    }
}

@Composable
private fun DayCell(
    dayNumber: Int,
    cellHeight: Dp,
    isToday: Boolean,
    isFocused: Boolean,
    entries: List<TraktCalendarEntry>,
    onClick: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(8.dp)
    val primary = MaterialTheme.colorScheme.primary
    val firstEntry = entries.firstOrNull()

    // Distinguish "another episode of the same show" from "another show".
    val displayedShowId = firstEntry?.contentId
    val sameShowEpisodeCount = if (displayedShowId == null) {
        0
    } else {
        entries.count { it.contentId == displayedShowId }
    }
    val otherShowCount = entries.map { it.contentId }.distinct().size - 1

    val borderModifier = when {
        isFocused -> Modifier.border(3.dp, Color.White, shape)
        isToday -> Modifier.border(2.dp, primary, shape)
        else -> Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(cellHeight)
            .clip(shape)
            .background(
                if (firstEntry == null) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .then(borderModifier)
            .then(
                if (onClick != null) Modifier.clickable { onClick() } else Modifier,
            ),
    ) {
        if (firstEntry?.posterUrl != null) {
            NuvioAsyncImage(
                model = firstEntry.posterUrl,
                contentDescription = firstEntry.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            // Light scrim behind the day number so it stays readable over the poster.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.55f),
                            0.35f to Color.Transparent,
                        ),
                    ),
            )
        }

        // Day number, top-left.
        Text(
            text = dayNumber.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (firstEntry != null) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
        )

        // Badges, top-right. Blue = other shows; white (below it) = extra episodes of this show.
        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(3.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (otherShowCount > 0) {
                CalendarBadge(
                    text = "+$otherShowCount",
                    background = primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
            if (sameShowEpisodeCount > 1) {
                // White (vs blue) is what tells you these are extra episodes of the same show.
                CalendarBadge(
                    text = "+${sameShowEpisodeCount - 1}",
                    background = Color.White,
                    contentColor = Color.Black,
                )
            }
        }
    }
}

@Composable
private fun CalendarBadge(
    text: String,
    background: Color,
    contentColor: Color,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = contentColor,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CalendarDayDialog(
    selection: CalendarDaySelection,
    onDismiss: () -> Unit,
    onItemClick: (TraktCalendarEntry) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var focusedRow by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }
    // Keep the keyboard-focused row in view, scrolling by the minimal amount (reveal from the
    // top or bottom edge) rather than always jumping it to the top on every keypress.
    LaunchedEffect(focusedRow) {
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo.firstOrNull { it.index == focusedRow }
        if (visible == null) {
            listState.animateScrollToItem(focusedRow.coerceAtLeast(0))
        } else {
            val above = visible.offset - info.viewportStartOffset
            val below = (visible.offset + visible.size) - info.viewportEndOffset
            when {
                above < 0 -> listState.animateScrollBy(above.toFloat())
                below > 0 -> listState.animateScrollBy(below.toFloat())
            }
        }
    }

    fun confirm(index: Int) {
        selection.entries.getOrNull(index)?.let { onItemClick(it) }
        onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth(0.92f)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> {
                            focusedRow = (focusedRow - 1).coerceAtLeast(0); true
                        }
                        Key.DirectionDown -> {
                            focusedRow = (focusedRow + 1).coerceAtMost(selection.entries.lastIndex); true
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            confirm(focusedRow); true
                        }
                        Key.Escape, Key.Back, Key.Backspace -> {
                            onDismiss(); true
                        }
                        else -> false
                    }
                },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.nuvio.colors.surfaceSheet,
        ) {
            Column {
                Text(
                    text = formatDayHeader(selection.dateKey),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                ) {
                    itemsIndexed(selection.entries) { index, entry ->
                        if (index > 0) NuvioBottomSheetDivider()
                        CalendarDayRow(
                            entry = entry,
                            isFocused = index == focusedRow,
                            onClick = { confirm(index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDayRow(
    entry: TraktCalendarEntry,
    isFocused: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 72.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (entry.posterUrl != null) {
                NuvioAsyncImage(
                    model = entry.posterUrl,
                    contentDescription = entry.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            entrySubtitle(entry)?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun entrySubtitle(entry: TraktCalendarEntry): String? {
    val season = entry.seasonNumber ?: return null
    val episode = entry.episodeNumber ?: return null
    val code = "S${season}E$episode"
    return entry.episodeTitle?.takeIf { it.isNotBlank() }?.let { "$code · $it" } ?: code
}

private fun formatDayHeader(dateKey: String): String {
    val year = dateKey.substring(0, 4).toIntOrNull() ?: return dateKey
    val month = dateKey.substring(5, 7).toIntOrNull() ?: return dateKey
    val day = dateKey.substring(8, 10).toIntOrNull() ?: return dateKey
    val dow = dayOfWeekSundayZero(year, month, day)
    return "${WEEKDAY_FULL[dow]}, ${MONTH_NAMES[month - 1]} $day"
}

private fun dateKey(year: Int, month: Int, day: Int): String {
    val mm = if (month < 10) "0$month" else "$month"
    val dd = if (day < 10) "0$day" else "$day"
    return "${year.toString().padStart(4, '0')}-$mm-$dd"
}
