package com.nuvio.app.features.qualicache

data class QualiCacheSettings(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val accessKey: String = "",
    /**
     * The 4K badge. There is no toggle for the source on its own: the source picks which 4K badge
     * is drawn rather than earning one of its own, so it has nothing to switch off.
     */
    val showResolution: Boolean = true,
    val showDynamicRange: Boolean = true,
    val showAudio: Boolean = true,
) {
    val hasBaseUrl: Boolean
        get() = baseUrl.isNotBlank()

    /** Enabled *and* pointed at a server — the only state in which a lookup may be issued. */
    val isUsable: Boolean
        get() = enabled && hasBaseUrl && enabledCategories().isNotEmpty()

    fun isCategoryEnabled(category: QualityBadgeCategory): Boolean =
        when (category) {
            QualityBadgeCategory.Resolution -> showResolution
            QualityBadgeCategory.DynamicRange -> showDynamicRange
            QualityBadgeCategory.Audio -> showAudio
        }

    fun enabledCategories(): List<QualityBadgeCategory> =
        QualityBadgeCategory.entries.filter(::isCategoryEnabled)
}
