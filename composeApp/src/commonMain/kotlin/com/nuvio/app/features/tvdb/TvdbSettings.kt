package com.nuvio.app.features.tvdb

data class TvdbSettings(
    val apiKey: String = "",
) {
    val hasApiKey: Boolean get() = apiKey.isNotBlank()
}
