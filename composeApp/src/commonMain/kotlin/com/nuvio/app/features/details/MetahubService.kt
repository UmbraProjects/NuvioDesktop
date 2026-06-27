package com.nuvio.app.features.details

object MetahubService {
    suspend fun getValidLogoUrl(imdbId: String): String? {
        return "https://images.metahub.space/logo/medium/$imdbId/img"
    }

    suspend fun getValidBackgroundUrl(imdbId: String): String? {
        return "https://images.metahub.space/background/medium/$imdbId/img"
    }
}
