package com.nuvio.app.features.debrid

import com.nuvio.app.features.streams.StreamClientResolve

internal class TorboxFileSelector {
    fun selectFile(
        files: List<TorboxTorrentFileDto>,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?,
    ): TorboxTorrentFileDto? {
        val playable = files.filter { it.isPlayableVideo() }
        if (playable.isEmpty()) return null

        resolve.fileIdx?.let { fileIdx ->
            playable.firstOrNull { it.id == fileIdx }?.let { return it }
            files.getOrNull(fileIdx)?.takeIf { it.isPlayableVideo() }?.let { return it }
        }

        val names = listOfNotNull(resolve.filename, resolve.title, resolve.torrentName)
            .map { it.normalizedName() }
            .filter { it.isNotBlank() }
        if (names.isNotEmpty()) {
            playable.firstOrNull { file ->
                val fileName = file.displayName().normalizedName()
                names.any { name -> fileName.contains(name) || name.contains(fileName) }
            }?.let { return it }
        }

        val episodePatterns = buildEpisodePatterns(
            season = season ?: resolve.season,
            episode = episode ?: resolve.episode,
        )
        if (episodePatterns.isNotEmpty()) {
            playable.firstOrNull { file ->
                val fileName = file.displayName().lowercase()
                episodePatterns.any { pattern -> fileName.contains(pattern) }
            }?.let { return it }
        }

        return playable.maxByOrNull { it.size ?: 0L }
    }

    private fun TorboxTorrentFileDto.isPlayableVideo(): Boolean {
        val mime = mimeType.orEmpty().lowercase()
        if (mime.startsWith("video/")) return true
        return displayName().lowercase().hasVideoExtension()
    }
}

internal class RealDebridFileSelector {
    fun selectFile(
        files: List<RealDebridTorrentFileDto>,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?,
    ): RealDebridTorrentFileDto? {
        val playable = files.filter { it.isPlayableVideo() }
        if (playable.isEmpty()) return null

        resolve.fileIdx?.let { fileIdx ->
            playable.firstOrNull { it.id == fileIdx }?.let { return it }
            files.getOrNull(fileIdx)?.takeIf { it.isPlayableVideo() }?.let { return it }
        }

        val names = listOfNotNull(resolve.filename, resolve.title, resolve.torrentName)
            .map { it.normalizedName() }
            .filter { it.isNotBlank() }
        if (names.isNotEmpty()) {
            playable.firstOrNull { file ->
                val fileName = file.displayName().normalizedName()
                names.any { name -> fileName.contains(name) || name.contains(fileName) }
            }?.let { return it }
        }

        val episodePatterns = buildEpisodePatterns(
            season = season ?: resolve.season,
            episode = episode ?: resolve.episode,
        )
        if (episodePatterns.isNotEmpty()) {
            playable.firstOrNull { file ->
                val fileName = file.displayName().lowercase()
                episodePatterns.any { pattern -> fileName.contains(pattern) }
            }?.let { return it }
        }

        return playable.maxByOrNull { it.bytes ?: 0L }
    }

    private fun RealDebridTorrentFileDto.isPlayableVideo(): Boolean =
        displayName().lowercase().hasVideoExtension()
}

private fun String.normalizedName(): String =
    substringAfterLast('/')
        .substringBeforeLast('.')
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

private fun buildEpisodePatterns(season: Int?, episode: Int?): List<String> {
    if (season == null || episode == null) return emptyList()
    val seasonTwo = season.toString().padStart(2, '0')
    val episodeTwo = episode.toString().padStart(2, '0')
    return listOf(
        "s${seasonTwo}e$episodeTwo",
        "${season}x$episodeTwo",
        "${season}x$episode",
    )
}

private fun String.hasVideoExtension(): Boolean =
    videoExtensions.any { endsWith(it) }

private val videoExtensions = setOf(
    ".mp4",
    ".mkv",
    ".webm",
    ".avi",
    ".mov",
    ".m4v",
    ".ts",
    ".m2ts",
    ".wmv",
    ".flv",
)
