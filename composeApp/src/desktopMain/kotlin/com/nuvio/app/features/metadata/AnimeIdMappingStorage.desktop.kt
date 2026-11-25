package com.nuvio.app.features.metadata

import com.nuvio.app.core.build.PackagedResources
import java.io.InputStream

internal actual object AnimeIdMappingStorage {
    actual fun loadAnimeListText(): String? =
        PackagedResources.open("anime-list-mini.json")
            ?.bufferedReader()
            ?.use { it.readText() }

    actual fun openAnimeListStream(): InputStream? =
        PackagedResources.open("anime-list-mini.json")
}
