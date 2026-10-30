package com.nuvio.app.features.preroll

/**
 * A palette from genres alone, for when the model sent no theme (or an unusable one) and for packs
 * cached before themes existed. Deliberately conservative: a genre-true palette is never wrong,
 * just less specific than the model's.
 */
internal fun fallbackTheme(genres: List<String>): TriviaTheme {
    val g = genres.map { it.lowercase() }
    fun has(vararg words: String) = g.any { genre -> words.any { genre.contains(it) } }
    return when {
        has("horror") -> TriviaTheme("#B3202A", "#2B1B3D", TriviaAtmosphere.Fog)
        has("sci", "science") -> TriviaTheme("#1FB5D6", "#7A5CFF", TriviaAtmosphere.Stars)
        has("war", "western") -> TriviaTheme("#C58B3A", "#5B6B3A", TriviaAtmosphere.Dust)
        has("crime", "thriller", "mystery") -> TriviaTheme("#D1A23A", "#1F6F8B", TriviaAtmosphere.Rain)
        has("action", "adventure") -> TriviaTheme("#E8742A", "#F2C14E", TriviaAtmosphere.Embers)
        has("animation", "family") -> TriviaTheme("#3FA9F5", "#FFB300", TriviaAtmosphere.Bokeh)
        has("romance") -> TriviaTheme("#E0607E", "#F5C26B", TriviaAtmosphere.Bokeh)
        has("fantasy") -> TriviaTheme("#8E6CEF", "#F2C14E", TriviaAtmosphere.Sparks)
        has("music", "musical") -> TriviaTheme("#E040A0", "#4DD0E1", TriviaAtmosphere.Neon)
        has("comedy") -> TriviaTheme("#F5A623", "#E8553A", TriviaAtmosphere.Bokeh)
        has("documentary", "history") -> TriviaTheme("#C9A66B", "#4F6D7A", TriviaAtmosphere.Dust)
        else -> TriviaTheme("#D4A94A", "#3C5A8A", TriviaAtmosphere.Dust)
    }
}

/** Fills whichever colours the model left out from [genres], keeping what it did send. */
internal fun TriviaPack.withFallbackTheme(genres: List<String>): TriviaPack {
    val base = fallbackTheme(genres)
    val own = theme
    val merged = if (own == null) base else own.copy(
        primary = own.primary ?: base.primary,
        secondary = own.secondary ?: base.secondary,
    )
    return copy(theme = merged)
}

/** `#RRGGBB` to 0xFFRRGGBB, or null. */
internal fun hexToArgb(hex: String?): Long? {
    val value = normalizeHexColour(hex)?.drop(1)?.toLongOrNull(16) ?: return null
    return 0xFF000000L or value
}
