package com.nuvio.app.features.p2p

import com.nuvio.app.core.build.AppFeaturePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nuvio.composeapp.generated.resources.*
import com.nuvio.app.core.i18n.localizedString

data class P2pSettingsUiState(
    val p2pEnabled: Boolean = false,
    val enableUpload: Boolean = true,
    val hideTorrentStats: Boolean = true,
    val torrentProfile: P2pTorrentProfile = P2pTorrentProfile.BALANCED,
    val cacheSize: P2pCacheSize = P2pCacheSize.GB_2,
    val engineBackend: P2pEngineBackend = P2pEngineBackend.NUVIO_ENGINE,
    /**
     * Nuvio Engine only: torrent pieces live in memory and nothing is written to the drive. The
     * engine holds the playback buffer preset's window plus a little behind it, so the preset
     * sets the memory it uses; the disk cache size does not apply.
     */
    val ramOnlyMode: Boolean = false,
)

/** Mirrors Stremio's Soft / Default / Fast swarm profiles; the engine owns the actual numbers. */
enum class P2pTorrentProfile {
    SOFT,
    BALANCED,
    FAST,
}

enum class P2pCacheSize(val bytes: Long) {
    NONE(0L),
    GB_2(2L * 1024L * 1024L * 1024L),
    GB_5(5L * 1024L * 1024L * 1024L),
    GB_10(10L * 1024L * 1024L * 1024L),
}

/**
 * Which native torrent engine backs [P2pStreamingEngine] on desktop. Nuvio Engine is the
 * in-process libtorrent engine upstream moved mobile onto; TorrServer is the external Go daemon
 * this fork shipped first. Both stay in the build so a user can switch without a reinstall — that
 * is the restore path if the Nuvio Engine port misbehaves. Unknown on platforms with one engine.
 */
enum class P2pEngineBackend {
    NUVIO_ENGINE,
    TORRSERVER,
}

data class P2pCacheUiState(
    val usedBytes: Long = 0L,
    val protectedBytes: Long = 0L,
    val isClearing: Boolean = false,
    val hasMeasurement: Boolean = false,
)

data class P2pCacheClearResult(
    val reclaimedBytes: Long,
    val remainingBytes: Long,
    val protectedBytes: Long,
)

object P2pSettingsRepository {
    private val _uiState = MutableStateFlow(P2pSettingsUiState())
    val uiState: StateFlow<P2pSettingsUiState> = _uiState.asStateFlow()

    val isVisible: Boolean
        get() = AppFeaturePolicy.p2pEnabled

    private var hasLoaded = false
    private var p2pEnabled = false
    private var enableUpload = true
    private var hideTorrentStats = true
    private var torrentProfile = P2pTorrentProfile.BALANCED
    private var cacheSize = P2pCacheSize.GB_2
    private var engineBackend = P2pEngineBackend.NUVIO_ENGINE
    private var ramOnlyMode = false

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun clearLocalState() {
        hasLoaded = false
        p2pEnabled = false
        enableUpload = true
        hideTorrentStats = true
        torrentProfile = P2pTorrentProfile.BALANCED
        cacheSize = P2pCacheSize.GB_2
        engineBackend = P2pEngineBackend.NUVIO_ENGINE
        ramOnlyMode = false
        publish()
    }

    fun setP2pEnabled(enabled: Boolean) {
        ensureLoaded()
        if (p2pEnabled == enabled) return
        p2pEnabled = enabled
        P2pSettingsStorage.saveP2pEnabled(enabled)
        publish()
    }

    fun setEnableUpload(enabled: Boolean) {
        ensureLoaded()
        if (enableUpload == enabled) return
        enableUpload = enabled
        P2pSettingsStorage.saveEnableUpload(enabled)
        publish()
    }

    fun setHideTorrentStats(enabled: Boolean) {
        ensureLoaded()
        if (hideTorrentStats == enabled) return
        hideTorrentStats = enabled
        P2pSettingsStorage.saveHideTorrentStats(enabled)
        publish()
    }

    fun setTorrentProfile(profile: P2pTorrentProfile) {
        ensureLoaded()
        if (torrentProfile == profile) return
        torrentProfile = profile
        P2pSettingsStorage.saveTorrentProfile(profile.name)
        publish()
    }

    fun setCacheSize(size: P2pCacheSize) {
        ensureLoaded()
        if (cacheSize == size) return
        cacheSize = size
        P2pSettingsStorage.saveCacheSize(size.name)
        publish()
    }

    fun setEngineBackend(backend: P2pEngineBackend) {
        ensureLoaded()
        if (engineBackend == backend) return
        engineBackend = backend
        P2pSettingsStorage.saveEngineBackend(backend.name)
        publish()
    }

    fun setRamOnlyMode(enabled: Boolean) {
        ensureLoaded()
        if (ramOnlyMode == enabled) return
        ramOnlyMode = enabled
        P2pSettingsStorage.saveRamOnlyMode(enabled)
        publish()
    }

    private fun loadFromDisk() {
        hasLoaded = true
        p2pEnabled = P2pSettingsStorage.loadP2pEnabled() ?: false
        enableUpload = P2pSettingsStorage.loadEnableUpload() ?: true
        hideTorrentStats = P2pSettingsStorage.loadHideTorrentStats() ?: true
        torrentProfile = P2pSettingsStorage.loadTorrentProfile()
            ?.let { stored -> P2pTorrentProfile.entries.firstOrNull { it.name == stored } }
            ?: P2pTorrentProfile.BALANCED
        cacheSize = P2pSettingsStorage.loadCacheSize()
            ?.let { stored -> P2pCacheSize.entries.firstOrNull { it.name == stored } }
            ?: P2pCacheSize.GB_2
        engineBackend = P2pSettingsStorage.loadEngineBackend()
            ?.let { stored -> P2pEngineBackend.entries.firstOrNull { it.name == stored } }
            ?: P2pEngineBackend.NUVIO_ENGINE
        ramOnlyMode = P2pSettingsStorage.loadRamOnlyMode() ?: false
        publish()
    }

    private fun publish() {
        _uiState.value = P2pSettingsUiState(
            p2pEnabled = p2pEnabled,
            enableUpload = enableUpload,
            hideTorrentStats = hideTorrentStats,
            torrentProfile = torrentProfile,
            cacheSize = cacheSize,
            engineBackend = engineBackend,
            ramOnlyMode = ramOnlyMode,
        )
    }
}

internal expect object P2pSettingsStorage {
    fun loadP2pEnabled(): Boolean?
    fun saveP2pEnabled(enabled: Boolean)
    fun loadEnableUpload(): Boolean?
    fun saveEnableUpload(enabled: Boolean)
    fun loadHideTorrentStats(): Boolean?
    fun saveHideTorrentStats(enabled: Boolean)
    fun loadTorrentProfile(): String?
    fun saveTorrentProfile(profile: String)
    fun loadCacheSize(): String?
    fun saveCacheSize(size: String)
    fun loadEngineBackend(): String?
    fun saveEngineBackend(backend: String)
    fun loadRamOnlyMode(): Boolean?
    fun saveRamOnlyMode(enabled: Boolean)
}

data class P2pStreamRequest(
    val infoHash: String,
    val fileIdx: Int?,
    val filename: String? = null,
    val trackers: List<String> = emptyList(),
)

internal fun canonicalP2pInfoHash(infoHash: String): String {
    val canonical = infoHash.trim().lowercase()
    require(
        (canonical.length == 40 || canonical.length == 64) &&
            canonical.all { it in '0'..'9' || it in 'a'..'f' },
    ) {
        localizedString(Res.string.p2p_torrent_info_hash_must_be_40)
    }
    return canonical
}

internal fun buildP2pMagnetUri(infoHash: String, trackers: List<String>): String {
    val canonicalHash = canonicalP2pInfoHash(infoHash)
    val topic = if (canonicalHash.length == 40) {
        "urn:btih:$canonicalHash"
    } else {
        "urn:btmh:1220$canonicalHash"
    }
    val trackerParameters = trackers.filter(String::isNotBlank).distinct().joinToString("") { tracker ->
        "&tr=${tracker.encodeP2pQueryValue()}"
    }
    return "magnet:?xt=$topic$trackerParameters"
}

private fun String.encodeP2pQueryValue(): String = buildString {
    for (byte in this@encodeP2pQueryValue.encodeToByteArray()) {
        val value = byte.toInt() and 0xff
        if ((value in 'a'.code..'z'.code) ||
            (value in 'A'.code..'Z'.code) ||
            (value in '0'.code..'9'.code) ||
            value == '-'.code || value == '.'.code || value == '_'.code || value == '~'.code
        ) {
            append(value.toChar())
        } else {
            append('%')
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }
}

private const val HEX_DIGITS = "0123456789ABCDEF"

sealed class P2pStreamingState {
    data object Idle : P2pStreamingState()

    /**
     * [phase] is a backend-defined token ("starting_engine", "add_magnet", "prepare_stream",
     * "attach_route"); the player overlay maps it to a label and falls back to "starting engine".
     */
    data class Connecting(
        val phase: String = "starting_engine",
        val downloadSpeed: Long = 0L,
        val uploadSpeed: Long = 0L,
        val peers: Int = 0,
        val seeds: Int = 0,
    ) : P2pStreamingState()

    data class Streaming(
        val localUrl: String,
        val downloadSpeed: Long,
        val uploadSpeed: Long,
        val peers: Int,
        val seeds: Int,
        val bufferProgress: Float,
        val totalProgress: Float,
        val downloadedBytes: Long = 0L,
        val verifiedBytes: Long = 0L,
        val deliveredBytes: Long = 0L,
    ) : P2pStreamingState()

    data class Error(val message: String) : P2pStreamingState()
}

class P2pStreamingException(message: String) : Exception(message)

expect object P2pStreamingEngine {
    val state: StateFlow<P2pStreamingState>
    val cacheState: StateFlow<P2pCacheUiState>
    /** Whether [P2pEngineBackend.NUVIO_ENGINE] can actually be used on this install. */
    val nuvioEngineAvailable: Boolean
    suspend fun startStream(request: P2pStreamRequest): String
    suspend fun clearCache(): P2pCacheClearResult
    fun stopStream()
    fun shutdown()
}

internal fun formatP2pSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1_048_576 -> "${(bytesPerSec / 1_048_576.0).formatOneDecimal()} MB/s"
        bytesPerSec >= 1_024 -> "${(bytesPerSec / 1_024.0).formatNoDecimal()} KB/s"
        else -> "$bytesPerSec B/s"
    }
}

internal fun formatP2pMegabytes(bytes: Long): String =
    "${(bytes / 1_048_576.0).formatOneDecimal()} MB"

private fun Double.formatOneDecimal(): String {
    val rounded = kotlin.math.round(this * 10.0) / 10.0
    val whole = rounded.toLong()
    val fraction = ((rounded - whole) * 10.0).toInt()
    return "$whole.$fraction"
}

private fun Double.formatNoDecimal(): String =
    kotlin.math.round(this).toInt().toString()
