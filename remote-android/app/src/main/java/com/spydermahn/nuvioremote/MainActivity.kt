package com.spydermahn.nuvioremote

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap

class MainActivity : ComponentActivity() {
    private val viewModel: RemoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) openPairingLink(intent)
        // fork:live-scores - Android 13+ needs consent before any notification shows (scores, lock-screen controls).
        if (savedInstanceState == null && android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 47)
        }
        setContent {
            // The TV's theme, as soon as the PC has sent it (and the last one it sent until then).
            val theme by viewModel.state.collectAsStateWithLifecycle()
            val glass = remember(theme.theme) { GlassColors.of(theme.theme) }
            CompositionLocalProvider(LocalGlass provides glass) {
                MaterialTheme(
                    colorScheme = materialColors(glass),
                    typography = MaterialTheme.typography.withFont(NuvioSans),
                ) {
                    Surface(modifier = Modifier.fillMaxSize(), color = glass.ground) {
                        RemoteApp(viewModel)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForeground()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        openPairingLink(intent)
    }

    /** Opened from the QR code in Nuvio's Settings by the phone's camera: pair with what it holds. */
    private fun openPairingLink(intent: android.content.Intent?) {
        val link = PairingLink.parse(intent?.dataString) ?: return
        // Once only: a rotation or a return to the app must not pair again.
        intent?.data = null
        viewModel.pairWith(link)
    }

    override fun onStop() {
        viewModel.onBackground()
        super.onStop()
    }

    /** The phone's volume rocker drives the player's volume while the remote is showing. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val onRemote = viewModel.state.value.screen == Screen.Remote
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> RemoteKey.VolumeUp
            KeyEvent.KEYCODE_VOLUME_DOWN -> RemoteKey.VolumeDown
            else -> null
        }
        if (onRemote && key != null) {
            if (event.action == KeyEvent.ACTION_DOWN) viewModel.press(key)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

internal val Accent = Color(0xFF8AB4FF)
internal val Panel = Color(0xFF1B1D26)
internal val PanelRaised = Color(0xFF262935)
internal val Muted = Color(0xFF9AA0B4)

internal val RemoteColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF0B1730),
    background = Color(0xFF0E0F14),
    surface = Color(0xFF0E0F14),
    surfaceVariant = Panel,
    onSurface = Color(0xFFE8EAF2),
    onSurfaceVariant = Muted,
)

@Composable
private fun RemoteApp(viewModel: RemoteViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GlassGround {
    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        when (val screen = state.screen) {
            Screen.Pick -> PickScreen(state, viewModel)
            is Screen.Pair -> PairScreen(screen, state, viewModel)
            Screen.Remote -> ConnectedScreen(state, viewModel)
        }
    }
    }
    // Over everything, edge to edge: the bars are hidden while it shows.
    if (state.dark && state.screen == Screen.Remote) {
        DarkModeScreen(
            playing = state.nowPlaying,
            link = state.link,
            level = state.darkLevel,
            onKey = viewModel::press,
            onLevel = viewModel::setDarkLevel,
            onLeave = { viewModel.setDark(false) },
        )
    }
}

/** Every text style in Nuvio's typeface. */
private fun androidx.compose.material3.Typography.withFont(family: androidx.compose.ui.text.font.FontFamily) = copy(
    displayLarge = displayLarge.copy(fontFamily = family), displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family), headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family), headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family), titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family), bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family), bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family), labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

// ---------------------------------------------------------------- Picking a PC

@Composable
private fun PickScreen(state: UiState, viewModel: RemoteViewModel) {
    var address by rememberSaveable { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(24.dp))
            Text("Nuvio Remote", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "On the PC, turn on Settings › Keyboard shortcuts › Phone remote, then scan the code it shows. Your phone needs to be on the same Wi-Fi.",
                color = Muted,
                fontSize = 14.sp,
            )
            state.message?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = Color(0xFFFFB4A9), fontSize = 14.sp)
            }
            Spacer(Modifier.height(14.dp))
            ScanButton(viewModel, enabled = !state.busy)
        }

        if (state.saved.isNotEmpty()) {
            item { SectionLabel("Paired") }
            items(state.saved, key = { "saved-" + it.address }) { pc ->
                PcRow(
                    title = pc.name,
                    subtitle = pc.address,
                    enabled = !state.busy,
                    onClick = { viewModel.connectTo(pc) },
                    trailing = {
                        IconButton(onClick = { viewModel.forget(pc) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Forget ${pc.name}", tint = Muted)
                        }
                    },
                )
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("On this network", modifier = Modifier.weight(1f))
                if (state.scanning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = viewModel::scan) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Search again", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        if (state.found.isEmpty() && !state.scanning) {
            item {
                Text(
                    "No PCs found. Check Phone remote is on in Nuvio, or add the PC by its address below (it's shown on the same settings page).",
                    color = Muted,
                    fontSize = 14.sp,
                )
            }
        }
        items(state.found, key = { "found-${it.host}:${it.port}" }) { pc ->
            PcRow(
                title = pc.name,
                subtitle = "${pc.host}:${pc.port}",
                enabled = !state.busy,
                onClick = { viewModel.connectTo(pc) },
            )
        }

        item {
            SectionLabel("Debug mode")
            Text(
                "No PC to hand? The demo TV runs inside this app and answers like Nuvio: pairing (code ${DemoTv.PIN}), the remote, " +
                    "a pretend film and album, the library and the TV's colours. It keeps a list of what it was sent.",
                color = Muted,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = viewModel::tryDemo, enabled = !state.busy) {
                Text("Try the demo TV")
            }
        }

        item {
            SectionLabel("Add by address")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    placeholder = { Text("192.168.1.20") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { viewModel.connectToAddress(address) }),
                )
                Spacer(Modifier.size(10.dp))
                Button(onClick = { viewModel.connectToAddress(address) }, enabled = !state.busy && address.isNotBlank()) {
                    Text("Connect")
                }
            }
            if (state.busy) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text("Connecting…", color = Muted)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** Pairs by scanning the QR code in Nuvio's Settings › Phone remote: no code to type, no address to find. */
@Composable
private fun ScanButton(viewModel: RemoteViewModel, enabled: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Button(
        onClick = { QrScan.start(context, viewModel::pairWith, viewModel::showMessage) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
        Spacer(Modifier.size(10.dp))
        Text("Scan the TV's code")
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "Nuvio shows it under Settings › Keyboard shortcuts › Phone remote. The phone's camera can open it too.",
        color = Muted,
        fontSize = 13.sp,
    )
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(top = 18.dp, bottom = 4.dp),
        color = Muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )
}

@Composable
private fun PcRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Panel)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Tv, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Muted, fontSize = 13.sp)
        }
        trailing()
    }
}

// ---------------------------------------------------------------- Pairing

@Composable
private fun PairScreen(screen: Screen.Pair, state: UiState, viewModel: RemoteViewModel) {
    var pin by rememberSaveable(screen) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(screen) { focus.requestFocus() }
    BackHandler { viewModel.cancelPairing() }

    Column(
        modifier = Modifier.fillMaxSize().imePadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Tv, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text("Pair with ${screen.name}", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Enter the six-digit code shown in Nuvio under Settings › Keyboard shortcuts › Phone remote.",
            color = Muted,
            textAlign = TextAlign.Center,
        )
        if (screen.host == DemoTv.HOST && screen.port == DemoTv.PORT) {
            Spacer(Modifier.height(8.dp))
            Text("The demo TV's code is ${DemoTv.PIN}.", color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isDigit).take(6) },
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.Center, letterSpacing = 8.sp),
            modifier = Modifier.widthIn(max = 260.dp).focusRequester(focus),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (pin.length == 6) viewModel.pair(pin) }),
        )
        state.message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = Color(0xFFFFB4A9), textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(12.dp))
        val context = androidx.compose.ui.platform.LocalContext.current
        TextButton(onClick = { QrScan.start(context, viewModel::pairWith, viewModel::showMessage) }, enabled = !state.busy) {
            Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Scan the TV's code instead")
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = viewModel::cancelPairing) { Text("Cancel") }
            Spacer(Modifier.size(12.dp))
            Button(onClick = { viewModel.pair(pin) }, enabled = pin.length == 6 && !state.busy) {
                if (state.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Pair")
                }
            }
        }
    }
}
