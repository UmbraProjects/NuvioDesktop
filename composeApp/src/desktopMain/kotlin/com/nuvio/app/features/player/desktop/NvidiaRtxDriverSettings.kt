package com.nuvio.app.features.player.desktop

import co.touchlab.kermit.Logger
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val log = Logger.withTag("NvidiaRtxDriver")

/**
 * The NVIDIA App's own RTX Video switches. Null means not known (no NVIDIA driver, an older one
 * without the values, or the read failed), which the player treats as "not off".
 */
internal data class NvidiaRtxDriverSettings(
    val superResolutionEnabled: Boolean?,
    val trueHdrEnabled: Boolean?,
)

/**
 * d3d11vpp only asks the driver for VSR / True HDR; with the NVIDIA App switch off it still runs.
 * VSR then upscales 2x with the video processor's plain scaler instead of mpv's sharper one, and
 * True HDR tags SDR as PQ without converting it. So the player skips each filter the driver has
 * off, whatever the app setting says.
 *
 * The driver keeps the switches as undocumented per-adapter values under the display class key:
 * `_User_Global_VAL_SuperResolution` (0 off, otherwise the quality level, e.g. 5) and
 * `_User_Global_VAL_TrueHDR` (0 off, 1 on). Checked on a 5070 Ti, driver of October 2026, by
 * flipping each switch in the NVIDIA App.
 */
internal object NvidiaRtxDriverState {
    private const val DISPLAY_CLASS_KEY =
        "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Class\\{4d36e968-e325-11ce-bfc1-08002be10318}"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshing = AtomicBoolean(false)
    private val _settings = MutableStateFlow(NvidiaRtxDriverSettings(null, null))
    val settings: StateFlow<NvidiaRtxDriverSettings> = _settings.asStateFlow()

    /** Re-reads in the background (about 50 ms); called per playback attach. */
    fun refreshAsync() {
        if (!refreshing.compareAndSet(false, true)) return
        scope.launch {
            try {
                val next = read() ?: return@launch
                if (next != _settings.value) {
                    log.i { "NVIDIA App RTX Video: VSR=${next.superResolutionEnabled} TrueHDR=${next.trueHdrEnabled}" }
                    _settings.value = next
                }
            } finally {
                refreshing.set(false)
            }
        }
    }

    private fun read(): NvidiaRtxDriverSettings? = runCatching {
        val process = ProcessBuilder("reg.exe", "query", DISPLAY_CLASS_KEY, "/s", "/f", "_User_Global_VAL_", "/v")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        parseNvidiaRtxDriverSettings(output)
    }.onFailure { log.w(it) { "Could not read NVIDIA RTX Video settings" } }.getOrNull()
}

private val rtxDriverValueLine =
    Regex("""^\s*_User_Global_VAL_(SuperResolution|TrueHDR)\s+REG_DWORD\s+0x([0-9a-fA-F]+)\s*$""")

/** `reg query` output to settings. Several adapters: on if any has it on. */
internal fun parseNvidiaRtxDriverSettings(regQueryOutput: String): NvidiaRtxDriverSettings {
    val values = mutableMapOf<String, MutableList<Long>>()
    regQueryOutput.lineSequence().forEach { line ->
        val match = rtxDriverValueLine.matchEntire(line) ?: return@forEach
        val value = match.groupValues[2].toLongOrNull(16) ?: return@forEach
        values.getOrPut(match.groupValues[1]) { mutableListOf() }.add(value)
    }
    fun enabled(name: String): Boolean? = values[name]?.let { list -> list.any { it != 0L } }
    return NvidiaRtxDriverSettings(
        superResolutionEnabled = enabled("SuperResolution"),
        trueHdrEnabled = enabled("TrueHDR"),
    )
}
