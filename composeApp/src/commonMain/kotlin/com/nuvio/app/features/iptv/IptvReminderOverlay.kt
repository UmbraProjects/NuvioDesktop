package com.nuvio.app.features.iptv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.delay

/*
 * fork:live-tv-reminder-banner - the reminder card as a banner at the top centre, where no Live TV page keeps its
 * buttons (the score pop-ups keep the bottom right). Solid, so nothing shows through it, with the programme's picture
 * on the left (its art, else the two sides of a fixture, else the channel's logo) and a bar that fills as the start
 * comes near. A switching reminder's banner takes no input; a plain reminder's banner asks Switch now, At the start
 * or Not now, in a focused popup in the same place. Over a playing video the HUD says it instead.
 */

private val BannerWidth = 620.dp
private val BannerShape = RoundedCornerShape(14.dp)
private val BannerTop = 20.dp

@Composable
fun IptvReminderOverlay(modifier: Modifier = Modifier) {
    val shown by IptvReminderEngine.showing.collectAsStateWithLifecycle()
    val asking = shown?.takeIf { it.options.isNotEmpty() }
    if (asking != null) {
        IptvReminderQuestion(asking)
        return
    }
    // Keep the last card while it slides away.
    var last by remember { mutableStateOf<IptvReminderEngine.Card?>(null) }
    LaunchedEffect(shown) { if (shown != null) last = shown }
    val card = shown ?: last
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = shown != null,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = BannerTop),
        ) {
            if (card != null) ReminderBanner(card, height = 96, buttons = null)
        }
    }
}

/** The plain reminder: the banner with three equal buttons; left and right to move, OK to pick, Back for Not now. */
@Composable
private fun IptvReminderQuestion(card: IptvReminderEngine.Card) {
    var at by remember(card.id) { mutableIntStateOf(card.focused.coerceIn(0, card.options.lastIndex)) }
    val focus = remember(card.id) { FocusRequester() }
    LaunchedEffect(card.id) { runCatching { focus.requestFocus() } }
    Popup(
        alignment = Alignment.TopCenter,
        offset = with(LocalDensity.current) { IntOffset(0, BannerTop.roundToPx()) },
        properties = PopupProperties(focusable = true),
        onDismissRequest = { IptvReminderEngine.answer(-1) },
    ) {
        com.nuvio.app.core.ui.KeyRouteLayer("reminder card") // the focus router leaves the open card alone
        Box(
            Modifier
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> at = (at - 1).coerceAtLeast(0)
                        Key.DirectionRight -> at = (at + 1).coerceAtMost(card.options.lastIndex)
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.Spacebar -> IptvReminderEngine.answer(at)
                        Key.Escape, Key.Back, Key.Backspace -> IptvReminderEngine.answer(-1)
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
        ) {
            ReminderBanner(card, height = 128, buttons = { BannerButtons(card.options, at) { IptvReminderEngine.answer(it) } })
        }
    }
}

/** The programme a reminder is for, as the art code wants it: its channel and its guide entry (title and times if not found). */
private fun programmeOf(reminder: IptvReminder): IptvWallProgramme? {
    val playlist = IptvRepository.settings.value.playlists.firstOrNull { it.id == reminder.playlistId } ?: return null
    val channel = IptvRepository.contentState(playlist.id).value.content?.byKey?.get(reminder.channelKey) ?: return null
    val entry = runCatching { IptvEpgRepository.guideEntries(playlist, channel).firstOrNull { it.startMs == reminder.startMs } }.getOrNull()
        ?: IptvEpgEntry(reminder.title, reminder.startMs, reminder.endMs)
    return IptvWallProgramme(IptvMergedChannel(listOf(channel)), entry)
}

/** "1:48", "starting now", "on now". */
private fun countdown(startMs: Long, now: Long): String {
    val left = startMs - now
    return when {
        left <= 0 -> if (left > -60_000L) "starting now" else "on now"
        left < 60 * 60_000L -> "%d:%02d".format(left / 60_000L, (left / 1000L) % 60)
        else -> "in ${left / 3_600_000L} h ${(left / 60_000L) % 60} min"
    }
}

@Composable
private fun ReminderBanner(card: IptvReminderEngine.Card, height: Int, buttons: (@Composable () -> Unit)?) {
    val tokens = MaterialTheme.nuvio
    val reminder = card.reminder
    val programme = remember(card.id) { reminder?.let(::programmeOf) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(card.id) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    // How far from the card going up to the start: full at the start.
    val progress = if (reminder == null || reminder.startMs <= card.shownAt) 1f else
        ((now - card.shownAt).toFloat() / (reminder.startMs - card.shownAt)).coerceIn(0f, 1f)
    Row(
        modifier = Modifier
            .width(BannerWidth)
            .height(height.dp)
            .shadow(24.dp, BannerShape, clip = false)
            .clip(BannerShape)
            .background(tokens.colors.surfacePopover)
            .border(1.dp, Color.White.copy(alpha = 0.12f), BannerShape),
    ) {
        BannerArt(programme, Modifier.width((height * 1.5f).dp).fillMaxHeight())
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    card.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (reminder != null) {
                    Text(
                        countdown(reminder.startMs, now),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.colors.accent,
                        maxLines = 1,
                    )
                }
            }
            Text(
                reminder?.channelName ?: card.line,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ProgressLine(progress, height = 3.dp)
            buttons?.invoke()
        }
    }
}

/** The programme's picture: its art, else a fixture's two sides, else the channel's logo on a tint of its name. */
@Composable
private fun BannerArt(programme: IptvWallProgramme?, modifier: Modifier) {
    val art = rememberProgrammeArt(programme)
    val sport = rememberSportArt(rememberSportInfo(programme?.entry?.title, programme?.entry?.kind))
    val name = programme?.channel?.primary?.name.orEmpty()
    val hue = ((name.hashCode() % 360) + 360) % 360
    val logo = IptvLogos.good(programme?.channel?.primary?.logo)
    Box(
        modifier = modifier.background(Brush.radialGradient(listOf(Color.hsv(hue.toFloat(), 0.5f, 0.36f), Color.hsv(hue.toFloat(), 0.35f, 0.14f)))),
        contentAlignment = Alignment.Center,
    ) {
        when {
            art?.picture != null -> NuvioAsyncImage(model = art.picture, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            sport != null -> IptvMatchup(sport, crest = 34.dp)
            logo != null -> NuvioAsyncImage(model = logo, contentDescription = null, modifier = Modifier.fillMaxSize().padding(14.dp), contentScale = ContentScale.Fit)
        }
    }
}

@Composable
private fun BannerButtons(options: List<String>, at: Int, onPick: (Int) -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(Modifier.fillMaxWidth().height(34.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == at
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) tokens.colors.textPrimary.copy(alpha = 0.92f) else tokens.colors.textPrimary.copy(alpha = 0.08f))
                    .clickable { onPick(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (on) tokens.colors.background else tokens.colors.textPrimary,
                    maxLines = 1,
                )
            }
        }
    }
}
