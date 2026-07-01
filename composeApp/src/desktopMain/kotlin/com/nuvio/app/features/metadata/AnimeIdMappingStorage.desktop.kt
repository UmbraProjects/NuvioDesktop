package com.nuvio.app.features.metadata

internal actual object AnimeIdMappingStorage {
    actual fun loadAnimeListText(): String? =
        Thread.currentThread()
            .contextClassLoader
            ?.getResourceAsStream("anime-list-mini.json")
            ?.bufferedReader()
            ?.use { it.readText() }
}
