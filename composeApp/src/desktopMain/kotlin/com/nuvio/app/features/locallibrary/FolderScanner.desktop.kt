package com.nuvio.app.features.locallibrary

import com.nuvio.app.features.player.VIDEO_EXTENSIONS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal actual object FolderScanner {

    actual suspend fun scan(folder: LocalFolder): LocalScanResult = withContext(Dispatchers.IO) {
        val root = File(folder.path)
        if (!root.exists() || !root.isDirectory) {
            return@withContext LocalScanResult(errorMessage = "Folder not found: ${folder.path}")
        }
        val items = runCatching {
            when (folder.type) {
                LocalFolderType.MOVIES -> scanMovies(folder, root)
                LocalFolderType.SERIES -> scanSeries(folder, root)
            }
        }.getOrElse { error ->
            return@withContext LocalScanResult(errorMessage = error.message ?: "Failed to scan ${folder.path}")
        }
        LocalScanResult(items = items.sortedBy { it.title.lowercase() })
    }

    private fun scanMovies(folder: LocalFolder, root: File): List<LocalMediaItem> {
        val items = mutableListOf<LocalMediaItem>()

        // Plex layout: one subfolder per movie.
        root.listFiles()?.filter { it.isDirectory }?.forEach { movieDir ->
            val videos = collectVideoFiles(movieDir)
            val primary = videos.maxByOrNull { it.length() } ?: return@forEach
            items += buildMovieItem(folder, nameForTitle = movieDir.name, file = primary)
        }

        // Loose video files sitting directly in the folder.
        root.listFiles()?.filter { it.isFile && it.isVideo() }?.forEach { file ->
            items += buildMovieItem(folder, nameForTitle = file.name, file = file)
        }

        return items.dedupeByKey()
    }

    private fun scanSeries(folder: LocalFolder, root: File): List<LocalMediaItem> {
        val showDirs = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        val items = mutableListOf<LocalMediaItem>()

        for (showDir in showDirs) {
            val episodes = collectVideoFiles(showDir)
            if (episodes.isEmpty()) continue
            items += buildSeriesItem(folder, showDir.name, episodes)
        }

        // Loose episode files at the root → treat the root folder itself as one show.
        val looseEpisodes = root.listFiles()?.filter { it.isFile && it.isVideo() }.orEmpty()
        if (looseEpisodes.isNotEmpty()) {
            items += buildSeriesItem(folder, root.name, looseEpisodes)
        }

        return items.dedupeByKey()
    }

    private fun buildMovieItem(folder: LocalFolder, nameForTitle: String, file: File): LocalMediaItem {
        val parsed = FilenameParser.parseTitle(nameForTitle)
        val title = parsed.title.ifBlank { file.nameWithoutExtension }
        return LocalMediaItem(
            key = "${folder.id}:${FilenameParser.normalizeKey(title, parsed.year)}",
            folderId = folder.id,
            type = LocalFolderType.MOVIES,
            title = title,
            year = parsed.year,
            files = listOf(LocalMediaFile(path = file.absolutePath)),
        )
    }

    private fun buildSeriesItem(folder: LocalFolder, showFolderName: String, episodeFiles: List<File>): LocalMediaItem {
        val parsed = FilenameParser.parseTitle(showFolderName)
        val title = parsed.title.ifBlank { showFolderName }
        val files = episodeFiles
            .map { file ->
                val seasonFolderName = file.parentFile?.name
                val episode = FilenameParser.parseEpisode(file.name, seasonFolderName)
                LocalMediaFile(path = file.absolutePath, season = episode.season, episode = episode.episode)
            }
            .sortedWith(compareBy({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE }, { it.fileName }))
        return LocalMediaItem(
            key = "${folder.id}:${FilenameParser.normalizeKey(title, null)}",
            folderId = folder.id,
            type = LocalFolderType.SERIES,
            title = title,
            year = parsed.year,
            files = files,
        )
    }

    private fun collectVideoFiles(dir: File): List<File> {
        val result = mutableListOf<File>()
        dir.walkTopDown()
            .maxDepth(6)
            .filter { it.isFile && it.isVideo() }
            .forEach { result += it }
        return result
    }

    private fun File.isVideo(): Boolean = extension.lowercase() in VIDEO_EXTENSIONS

    private fun List<LocalMediaItem>.dedupeByKey(): List<LocalMediaItem> {
        val byKey = LinkedHashMap<String, LocalMediaItem>()
        for (item in this) {
            val existing = byKey[item.key]
            byKey[item.key] = if (existing == null) {
                item
            } else {
                existing.copy(files = (existing.files + item.files).distinctBy { it.path })
            }
        }
        return byKey.values.toList()
    }
}
