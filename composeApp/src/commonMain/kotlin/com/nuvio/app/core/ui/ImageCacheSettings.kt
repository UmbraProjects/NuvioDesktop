package com.nuvio.app.core.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How much disk the artwork cache may use, and whether Home's artwork is downloaded ahead of time.
 *
 * Device-wide rather than per profile: there is one image cache on disk whoever is browsing, and a
 * profile switch should not resize it.
 *
 * The prewarm kinds are separate because they age differently. A logo or a backdrop is the same
 * file for as long as the title exists; a poster from a poster service changes whenever its
 * template does, and someone who edits that template regularly would only be warming images they
 * will never ask for again.
 */
data class ImageCacheSettings(
    val diskCacheGb: Int = DefaultDiskCacheGb,
    val prewarmEnabled: Boolean = false,
    val prewarmPosters: Boolean = true,
    val prewarmLogos: Boolean = true,
    val prewarmBackdrops: Boolean = true,
    /**
     * Also warm the titles inside each Home collection's folders. Off by default: unlike the rest
     * of the pass, which only downloads images for catalogs Home already loaded, this fetches every
     * folder's catalogs first.
     */
    val prewarmCollectionContents: Boolean = false,
    /**
     * How long background title metadata (row, hero-prewarm and Search/Library lookups) is kept on
     * disk between launches; 0 keeps it for the session only. See LightweightMetaDiskCache.
     */
    val metadataCacheHours: Int = DefaultMetadataCacheHours,
    /**
     * How old a Home catalog row kept from a previous launch may be and still be shown while that
     * launch refetches it; 0 keeps nothing between launches. See HomeCatalogDiskCache.
     */
    val homeCatalogCacheHours: Int = DefaultHomeCatalogCacheHours,
    /** How much RAM the app may hold on to; see [MemoryUse]. Device-wide, like the rest. */
    val memoryUse: MemoryUse = MemoryUse.Balanced,
) {
    val prewarmsAnything: Boolean
        get() = prewarmEnabled && (prewarmPosters || prewarmLogos || prewarmBackdrops)

    companion object {
        const val DefaultDiskCacheGb = 5
        val DiskCacheGbChoices = listOf(2, 5, 10, 20, 50)
        const val DefaultMetadataCacheHours = 24
        val MetadataCacheHoursChoices = listOf(0, 6, 24, 72, 168)
        const val DefaultHomeCatalogCacheHours = 24
        val HomeCatalogCacheHoursChoices = MetadataCacheHoursChoices
    }
}

/**
 * Memory use against smoothness, in the two places where the trade is real (2026-10-16 work):
 *
 *  - [idleGrowthMb] / [idleSlowGrowthMb]: how much the heap may grow before the native build's idle
 *    collector frees it while nobody is touching the app (IdleHeapCollector). Lower keeps memory
 *    down. It is deliberately NOT raised for Generous: an idle collection is invisible, and running
 *    fewer of them only lets a natural one land mid-scroll instead.
 *  - [largeArtworkEntries]: how many full-size hero backdrops stay decoded (~24-33 MB each), so
 *    moving back over cards finds them in memory instead of re-decoding (~110-140 ms each).
 *
 * Applies at once; nothing here needs a restart.
 */
enum class MemoryUse(
    val storageKey: String,
    val idleGrowthMb: Int,
    val idleSlowGrowthMb: Int,
    val largeArtworkEntries: Int,
) {
    Lean("lean", idleGrowthMb = 24, idleSlowGrowthMb = 8, largeArtworkEntries = 4),
    Balanced("balanced", idleGrowthMb = 48, idleSlowGrowthMb = 16, largeArtworkEntries = 6),
    Generous("generous", idleGrowthMb = 48, idleSlowGrowthMb = 16, largeArtworkEntries = 10),
    ;

    companion object {
        fun fromStorageKey(key: String?): MemoryUse? = entries.firstOrNull { it.storageKey == key }
    }
}

/** The outcome of the most recent prewarm pass, for the settings row to report. */
data class ImageCachePrewarmStatus(
    val running: Boolean = false,
    /** Work items finished: one image, or one focused-hero lookup and its images. */
    val checked: Int = 0,
    val downloaded: Int = 0,
    val alreadyCached: Int = 0,
    val failed: Int = 0,
    val total: Int = 0,
    /** On disk for what Home and the Library show now, by kind — however long ago it was fetched. */
    val postersReady: Int = 0,
    val logosReady: Int = 0,
    val backdropsReady: Int = 0,
    /** Whether hero backdrops are being analysed for automatic framing in this pass. */
    val framingActive: Boolean = false,
    /** Hero backdrops whose framing is ready. */
    val backdropsFramed: Int = 0,
)

internal expect object ImageCacheSettingsStorage {
    fun loadDiskCacheGb(): Int?
    fun saveDiskCacheGb(value: Int)
    fun loadPrewarmEnabled(): Boolean?
    fun savePrewarmEnabled(value: Boolean)
    fun loadPrewarmPosters(): Boolean?
    fun savePrewarmPosters(value: Boolean)
    fun loadPrewarmLogos(): Boolean?
    fun savePrewarmLogos(value: Boolean)
    fun loadPrewarmBackdrops(): Boolean?
    fun savePrewarmBackdrops(value: Boolean)
    fun loadPrewarmCollectionContents(): Boolean?
    fun savePrewarmCollectionContents(value: Boolean)
    fun loadMetadataCacheHours(): Int?
    fun saveMetadataCacheHours(value: Int)
    fun loadHomeCatalogCacheHours(): Int?
    fun saveHomeCatalogCacheHours(value: Int)
    fun loadMemoryUse(): String?
    fun saveMemoryUse(value: String)

    /** Bytes the artwork cache currently holds on disk, or null before it has been opened. */
    fun usedBytes(): Long?
}

object ImageCacheSettingsRepository {
    private val _settings = MutableStateFlow(ImageCacheSettings())
    val settings: StateFlow<ImageCacheSettings> = _settings.asStateFlow()

    private val _prewarmStatus = MutableStateFlow(ImageCachePrewarmStatus())
    val prewarmStatus: StateFlow<ImageCachePrewarmStatus> = _prewarmStatus.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        val defaults = ImageCacheSettings()
        _settings.value = ImageCacheSettings(
            diskCacheGb = ImageCacheSettingsStorage.loadDiskCacheGb()
                ?.takeIf { it in ImageCacheSettings.DiskCacheGbChoices }
                ?: defaults.diskCacheGb,
            prewarmEnabled = ImageCacheSettingsStorage.loadPrewarmEnabled() ?: defaults.prewarmEnabled,
            prewarmPosters = ImageCacheSettingsStorage.loadPrewarmPosters() ?: defaults.prewarmPosters,
            prewarmLogos = ImageCacheSettingsStorage.loadPrewarmLogos() ?: defaults.prewarmLogos,
            prewarmBackdrops = ImageCacheSettingsStorage.loadPrewarmBackdrops() ?: defaults.prewarmBackdrops,
            prewarmCollectionContents = ImageCacheSettingsStorage.loadPrewarmCollectionContents()
                ?: defaults.prewarmCollectionContents,
            metadataCacheHours = ImageCacheSettingsStorage.loadMetadataCacheHours()
                ?.takeIf { it in ImageCacheSettings.MetadataCacheHoursChoices }
                ?: defaults.metadataCacheHours,
            homeCatalogCacheHours = ImageCacheSettingsStorage.loadHomeCatalogCacheHours()
                ?.takeIf { it in ImageCacheSettings.HomeCatalogCacheHoursChoices }
                ?: defaults.homeCatalogCacheHours,
            memoryUse = MemoryUse.fromStorageKey(ImageCacheSettingsStorage.loadMemoryUse()) ?: defaults.memoryUse,
        )
    }

    fun setMemoryUse(value: MemoryUse) {
        ensureLoaded()
        if (_settings.value.memoryUse == value) return
        _settings.value = _settings.value.copy(memoryUse = value)
        ImageCacheSettingsStorage.saveMemoryUse(value.storageKey)
    }

    fun setDiskCacheGb(value: Int) {
        ensureLoaded()
        if (value !in ImageCacheSettings.DiskCacheGbChoices || _settings.value.diskCacheGb == value) return
        _settings.value = _settings.value.copy(diskCacheGb = value)
        ImageCacheSettingsStorage.saveDiskCacheGb(value)
    }

    fun setPrewarmEnabled(value: Boolean) {
        ensureLoaded()
        if (_settings.value.prewarmEnabled == value) return
        _settings.value = _settings.value.copy(prewarmEnabled = value)
        ImageCacheSettingsStorage.savePrewarmEnabled(value)
    }

    fun setPrewarmPosters(value: Boolean) {
        ensureLoaded()
        if (_settings.value.prewarmPosters == value) return
        _settings.value = _settings.value.copy(prewarmPosters = value)
        ImageCacheSettingsStorage.savePrewarmPosters(value)
    }

    fun setPrewarmLogos(value: Boolean) {
        ensureLoaded()
        if (_settings.value.prewarmLogos == value) return
        _settings.value = _settings.value.copy(prewarmLogos = value)
        ImageCacheSettingsStorage.savePrewarmLogos(value)
    }

    fun setPrewarmBackdrops(value: Boolean) {
        ensureLoaded()
        if (_settings.value.prewarmBackdrops == value) return
        _settings.value = _settings.value.copy(prewarmBackdrops = value)
        ImageCacheSettingsStorage.savePrewarmBackdrops(value)
    }

    fun setPrewarmCollectionContents(value: Boolean) {
        ensureLoaded()
        if (_settings.value.prewarmCollectionContents == value) return
        _settings.value = _settings.value.copy(prewarmCollectionContents = value)
        ImageCacheSettingsStorage.savePrewarmCollectionContents(value)
    }

    fun setMetadataCacheHours(value: Int) {
        ensureLoaded()
        if (value !in ImageCacheSettings.MetadataCacheHoursChoices || _settings.value.metadataCacheHours == value) return
        _settings.value = _settings.value.copy(metadataCacheHours = value)
        ImageCacheSettingsStorage.saveMetadataCacheHours(value)
    }

    fun setHomeCatalogCacheHours(value: Int) {
        ensureLoaded()
        if (value !in ImageCacheSettings.HomeCatalogCacheHoursChoices || _settings.value.homeCatalogCacheHours == value) return
        _settings.value = _settings.value.copy(homeCatalogCacheHours = value)
        ImageCacheSettingsStorage.saveHomeCatalogCacheHours(value)
    }

    internal fun publishPrewarmStatus(status: ImageCachePrewarmStatus) {
        _prewarmStatus.value = status
    }

    fun usedBytes(): Long? = ImageCacheSettingsStorage.usedBytes()
}
