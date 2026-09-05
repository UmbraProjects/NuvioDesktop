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
        root.listFiles()?.filter { it.isDirectory && !it.isUnmatchedFolder() }?.forEach { movieDir ->
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
        val showDirs = root.listFiles()?.filter { it.isDirectory && !it.isUnmatchedFolder() }.orEmpty()
        val items = mutableListOf<LocalMediaItem>()

        for (showDir in showDirs) {
            val episodes = collectVideoFiles(showDir)
            if (episodes.isEmpty()) continue
            items += buildSeriesItem(folder, showDir, episodes)
        }

        // Loose episode files at the root → treat the root folder itself as one show.
        val looseEpisodes = root.listFiles()?.filter { it.isFile && it.isVideo() }.orEmpty()
        if (looseEpisodes.isNotEmpty()) {
            items += buildSeriesItem(folder, root, looseEpisodes)
        }

        return items.qualifyCollidingYears().dedupeByKey()
    }

    private fun buildMovieItem(folder: LocalFolder, nameForTitle: String, file: File): LocalMediaItem {
        val parsed = FilenameParser.parseTitle(nameForTitle)
        val title = parsed.title.ifBlank { file.nameWithoutExtension }
        return LocalMediaItem(
            key = "${folder.id}:${FilenameParser.normalizeKey(title, parsed.year)}",
            folderId = folder.id,
            type = LocalFolderType.MOVIES,
            isAnime = folder.isAnime,
            title = title,
            year = parsed.year,
            files = listOf(LocalMediaFile(path = file.absolutePath)),
        )
    }

    private fun buildSeriesItem(folder: LocalFolder, showDirectory: File, episodeFiles: List<File>): LocalMediaItem {
        val parsed = FilenameParser.parseTitle(showDirectory.name)
        val title = parsed.title.ifBlank { showDirectory.name }
        val files = episodeFiles
            .map { file ->
                // A title folder can contain "S17" without being a season directory. Only nested
                // directories may provide fallback season context; direct children stay absolute.
                val seasonFolderName = file.parentFile
                    ?.takeUnless { parent ->
                        parent.absolutePath.equals(showDirectory.absolutePath, ignoreCase = true)
                    }
                    ?.name
                val episode = FilenameParser.parseEpisode(file.name, seasonFolderName, isAnime = folder.isAnime)
                LocalMediaFile(path = file.absolutePath, season = episode.season, episode = episode.episode)
            }
            .sortedWith(compareBy({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE }, { it.fileName }))
        return LocalMediaItem(
            key = "${folder.id}:${FilenameParser.normalizeKey(title, null)}",
            folderId = folder.id,
            type = LocalFolderType.SERIES,
            isAnime = folder.isAnime,
            title = title,
            year = parsed.year,
            files = files,
        )
    }

    private fun collectVideoFiles(dir: File): List<File> {
        val result = mutableListOf<File>()
        dir.walkTopDown()
            .maxDepth(6)
            .onEnter { !it.isUnmatchedFolder() }
            .filter { it.isFile && it.isVideo() }
            .forEach { result += it }
        return result
    }

    /**
     * Compatibility with the older Kitsu repair flow, which parked rejected files here. The current
     * flow is internal-only, but existing folders must stay ignored or their files would reappear.
     */
    private fun File.isUnmatchedFolder(): Boolean = name.equals(LOCAL_UNMATCHED_FOLDER, ignoreCase = true)

    private fun File.isVideo(): Boolean = extension.lowercase() in VIDEO_EXTENSIONS

    /**
     * Series keys deliberately omit the year so that `Show` and `Show (2019)` describe one show
     * rather than two. A sequel whose only distinguishing mark is punctuation a filesystem forbids
     * breaks that assumption: `Kaguya-sama: Love is War` and `Kaguya-sama: Love is War?` can only be
     * told apart in a folder name by their year, and merging them puts both runs' files on one item
     * pointing at one Kitsu entry. So when the same normalized title appears under *different*
     * years, the year is promoted into the key.
     *
     * The earliest year keeps the bare key — along with any year-less folder, which could belong to
     * either — so adding a sequel folder later never re-keys the show that was already there and
     * drops its manual match, catalog assignment or episode renumbering. The cost of that choice is
     * that a pre-seeded download override (LibraryFileNaming.expectedItemKey, which cannot know
     * about a collision it hasn't scanned) misses the later entry; that item simply auto-matches
     * instead.
     */
    private fun List<LocalMediaItem>.qualifyCollidingYears(): List<LocalMediaItem> {
        val yearsByKey = HashMap<String, MutableSet<Int>>()
        for (item in this) {
            val year = item.year ?: continue
            yearsByKey.getOrPut(item.key) { mutableSetOf() } += year
        }
        if (yearsByKey.values.none { it.size > 1 }) return this
        return map { item ->
            val years = yearsByKey[item.key] ?: return@map item
            val year = item.year
            if (years.size < 2 || year == null || year == years.min()) item
            else item.copy(key = "${item.key}-$year")
        }
    }

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
