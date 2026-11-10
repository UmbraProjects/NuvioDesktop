package com.nuvio.app.core.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.desktop.NativeAudioDuck
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal actual object DiagnosticsSettingsStorage {
    // A machine store (see MACHINE_STORE_NAMES): it describes this PC, not the person.
    private val store = DesktopStorage.store("nuvio_diagnostics")

    actual fun loadMemoryOverlayEnabled(): Boolean = store.getBoolean("memory_overlay") ?: false

    actual fun saveMemoryOverlayEnabled(value: Boolean) {
        store.putBoolean("memory_overlay", value)
    }
}

private const val SampleIntervalMs = 1_000L

@Composable
internal actual fun MemoryDiagnosticsOverlay(modifier: Modifier) {
    val enabled by DiagnosticsSettings.memoryOverlayEnabled.collectAsState()
    var rows by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(enabled) {
        if (!enabled) {
            rows = emptyList()
            withContext(Dispatchers.Default) { pushToPlayers(null) }
            return@LaunchedEffect
        }
        while (true) {
            val next = withContext(Dispatchers.Default) {
                MemoryBreakdownSampler.overlayRows(MemoryBreakdownSampler.sample()).also(::pushToPlayers)
            }
            rows = next
            delay(SampleIntervalMs)
        }
    }
    if (!enabled || rows.isEmpty()) return
    Column(
        modifier = modifier
            .background(Color(0xE6101014), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("Memory", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        rows.forEach { (label, value) ->
            Row {
                Text(label, color = Color(0xFFB8B8C0), fontSize = 12.sp, modifier = Modifier.width(170.dp))
                Spacer(Modifier.width(8.dp))
                Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * The same rows into every live player's HUD (`window.nuvioSetMemoryOverlay` in controls.js), or
 * null to clear them. A hero trailer's HUD ignores it. The bridge queues the call until a HUD that
 * is still loading is ready, and replays it into one that reloads.
 */
private fun pushToPlayers(rows: List<Pair<String, String>>?) {
    val handles = runCatching { NativeAudioDuck.liveHandles() }.getOrDefault(emptyList())
    if (handles.isEmpty()) return
    val payload = rows?.joinToString(prefix = "[", postfix = "]") { (label, value) ->
        "[${jsString(label)},${jsString(value)}]"
    } ?: "null"
    val script = "window.nuvioSetMemoryOverlay&&window.nuvioSetMemoryOverlay($payload)"
    handles.forEach { handle -> runCatching { NativePlayerBridge.runJavaScript(handle, script) } }
}

private fun jsString(value: String): String = buildString {
    append('"')
    value.forEach { c ->
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '<' -> append("\\u003c")
            else -> append(c)
        }
    }
    append('"')
}
