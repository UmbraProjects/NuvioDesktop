package com.nuvio.app.features.music.library

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

private val log = Logger.withTag("MusicBrainzOffline")

internal actual fun musicBrainzOfflineCopy(): MusicBrainzOfflineCopy = DesktopMusicBrainzOffline

/**
 * fork:music - the offline copy on this PC, under `<data dir>/musicbrainz/`. Each build lives in its own
 * `copy-<dump>` folder and `current.txt` names the one in use: Windows will not delete or rename a file that is
 * memory-mapped, so a replaced copy is only deleted on the next start, once nothing has it open.
 * The `.tar.xz` downloads sit in `downloads/` until the build that reads them is done, so a cut connection resumes.
 */
internal object DesktopMusicBrainzOffline : MusicBrainzOfflineCopy {
    private const val BASE = "https://data.metabrainz.org/pub/musicbrainz/data/json-dumps"
    private const val USER_AGENT = "NuvioDesktop/1.0 (https://github.com/UmbraProjects/NuvioDesktop)"
    private val DUMPS = listOf("artist", "release-group")

    /** Room the built copy takes, roughly, on top of what is still to download. */
    private const val BUILD_SPACE_BYTES = 2_500_000_000L

    private val root: Path by lazy { DesktopStorage.rootDir.resolve("musicbrainz") }
    private val pointer: Path get() = root.resolve("current.txt")

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // One low-priority thread: the build reads ~37 GB of JSON and should never take the UI's cores first.
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "musicbrainz-offline").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private var job: Job? = null
    private var checkedForUpdate = false

    @Volatile
    private var reader: MusicBrainzIndexReader? = null

    private val _state = MutableStateFlow<MusicBrainzOfflineState>(MusicBrainzOfflineState.Absent)
    override val state: StateFlow<MusicBrainzOfflineState> = _state.asStateFlow()

    override val index: MusicBrainzOfflineIndex? get() = reader

    init {
        runCatching { openInstalled() }.onFailure { log.w { "Opening the offline copy failed: ${it.message}" } }
    }

    /** Opens the copy `current.txt` names, and deletes every other copy and unfinished build left from before. */
    private fun openInstalled() {
        if (!root.isDirectory()) return
        val name = pointer.takeIf { it.exists() }?.let { Files.readString(it).trim() }
        val dir = name?.let { root.resolve(it) }
        root.listDirectoryEntries().filter { it.isDirectory() && it.name != "downloads" && it.name != name }.forEach(::deleteTree)
        if (dir == null || !dir.resolve("meta.json").exists()) return
        val opened = MusicBrainzIndexReader(dir)
        reader = opened
        _state.value = ready(opened)
    }

    private fun ready(r: MusicBrainzIndexReader, newer: String? = null) =
        MusicBrainzOfflineState.Ready(r.dump, treeSize(r.dir), r.artistCount, r.groupCount, newer)

    override fun download() {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                install()
            } catch (cancelled: CancellationException) {
                _state.value = reader?.let { ready(it) } ?: MusicBrainzOfflineState.Absent
                throw cancelled
            } catch (error: Throwable) {
                log.e(error) { "Building the MusicBrainz offline copy failed" }
                _state.value = MusicBrainzOfflineState.Failed(error.message ?: error::class.simpleName.orEmpty())
            }
        }
    }

    private suspend fun install() {
        Files.createDirectories(root)
        val dump = text("$BASE/LATEST").trim()
        require(dump.matches(Regex("""\d{8}-\d{6}"""))) { "MusicBrainz's dump list gave an unexpected answer" }
        val sums = parseSums(text("$BASE/$dump/SHA256SUMS"))
        val downloads = root.resolve("downloads").also { Files.createDirectories(it) }
        // A download of an older dump can't be resumed into this one.
        downloads.listDirectoryEntries().filter { !it.name.startsWith("$dump-") }.forEach { Files.deleteIfExists(it) }

        val files = DUMPS.map { downloads.resolve("$dump-$it.tar.xz") }
        val sizes = DUMPS.map { contentLength("$BASE/$dump/$it.tar.xz") }
        val total = sizes.sum()
        val have = files.sumOf { if (it.exists()) it.fileSize() else 0L }
        val free = Files.getFileStore(root).usableSpace
        val needed = (total - have) + BUILD_SPACE_BYTES
        require(free >= needed) { "Needs about ${gb(needed)} free; the drive has ${gb(free)}" }

        var before = 0L
        for ((x, name) in DUMPS.withIndex()) {
            fetchResumable("$BASE/$dump/$name.tar.xz", files[x], sizes[x]) { done ->
                _state.value = MusicBrainzOfflineState.Downloading(before + done, total)
            }
            before += sizes[x]
        }
        for ((x, name) in DUMPS.withIndex()) {
            val expected = sums["$name.tar.xz"] ?: continue
            if (sha256(files[x]) != expected) {
                Files.deleteIfExists(files[x])
                error("The downloaded $name dump was damaged; try again")
            }
        }

        val target = root.resolve("copy-$dump")
        val building = root.resolve("building-$dump")
        deleteTree(building)
        Files.createDirectories(building)
        _state.value = MusicBrainzOfflineState.Building(0f)
        var readBefore = 0L
        var lastShown = -1
        MusicBrainzIndexBuilder(building).use { builder ->
            for ((x, name) in DUMPS.withIndex()) {
                val counting = CountingStream(BufferedInputStream(Files.newInputStream(files[x]), 1 shl 20))
                counting.use {
                    val tar = XZInputStream(counting)
                    val entry = openTarEntry(tar, "mbdump/$name") ?: error("The $name dump has no mbdump/$name file")
                    var lines = 0
                    val check = currentCoroutineContext()
                    forEachJsonLine(BufferedInputStream(entry, 1 shl 20)) { line, length ->
                        if (name == "artist") builder.addArtistLine(line, length) else builder.addGroupLine(line, length)
                        if (++lines and 0xFFF == 0) {
                            check.ensureActive()
                            val permille = ((readBefore + counting.count) * 1000 / total).toInt()
                            if (permille != lastShown) {
                                lastShown = permille
                                _state.value = MusicBrainzOfflineState.Building(permille / 1000f)
                            }
                        }
                    }
                }
                readBefore += sizes[x]
            }
            builder.finish(dump)
            log.i { "MusicBrainz offline copy $dump built: ${builder.artists} artists, ${builder.groups} release groups" }
        }
        deleteTree(target)
        Files.move(building, target)
        val opened = MusicBrainzIndexReader(target)
        Files.writeString(pointer, target.name)
        val old = reader
        reader = opened
        old?.close() // its folder goes on the next start
        files.forEach { Files.deleteIfExists(it) }
        checkedForUpdate = true
        _state.value = ready(opened)
    }

    override fun cancel() {
        job?.cancel()
    }

    override fun delete() {
        job?.cancel()
        val old = reader
        reader = null
        old?.close()
        runCatching { Files.deleteIfExists(pointer) }
        runCatching { root.resolve("downloads").takeIf { it.exists() }?.let(::deleteTree) }
        // Mapped files can't go until the process lets them go; whatever is left is deleted on the next start.
        old?.dir?.let { runCatching { deleteTree(it) } }
        _state.value = MusicBrainzOfflineState.Absent
    }

    override fun checkForUpdate() {
        val current = _state.value as? MusicBrainzOfflineState.Ready ?: return
        if (checkedForUpdate || job?.isActive == true) return
        checkedForUpdate = true
        scope.launch {
            val latest = runCatching { text("$BASE/LATEST").trim() }.getOrNull() ?: return@launch
            if (latest > current.dump) {
                val now = _state.value
                if (now is MusicBrainzOfflineState.Ready && now.dump == current.dump) _state.value = now.copy(newer = latest)
            }
        }
    }

    // region transfers

    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", USER_AGENT)

    private fun text(url: String): String = http.newCall(request(url).build()).execute().use { response ->
        if (!response.isSuccessful) error("MusicBrainz's dump server answered ${response.code}")
        response.body?.string().orEmpty()
    }

    private fun contentLength(url: String): Long = http.newCall(request(url).head().build()).execute().use { response ->
        if (!response.isSuccessful) error("MusicBrainz's dump server answered ${response.code}")
        response.header("Content-Length")?.toLongOrNull() ?: error("MusicBrainz's dump server gave no size")
    }

    /** Downloads [url] into [target], carrying on from what is already there. */
    private suspend fun fetchResumable(url: String, target: Path, size: Long, onProgress: (Long) -> Unit) {
        var have = if (target.exists()) target.fileSize() else 0L
        if (have > size) {
            Files.delete(target)
            have = 0
        }
        if (have == size) return onProgress(size)
        val builder = request(url)
        if (have > 0) builder.header("Range", "bytes=$have-")
        http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) error("MusicBrainz's dump server answered ${response.code}")
            // A server that ignores the range sends the whole file: start over.
            val append = have > 0 && response.code == 206
            if (!append) have = 0
            val body = response.body ?: error("MusicBrainz's dump server sent nothing")
            val options = if (append) arrayOf(StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            else arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            Files.newOutputStream(target, *options).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(1 shl 20)
                    var shown = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        if (have - shown >= 4L shl 20) {
                            shown = have
                            onProgress(have)
                        }
                    }
                }
            }
        }
        if (have != size) error("The download stopped early; try again to carry on")
        onProgress(have)
    }

    private fun parseSums(text: String): Map<String, String> = text.lines().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 2) null else parts[1].removePrefix("*") to parts[0].lowercase()
    }.toMap()

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // endregion

    private class CountingStream(inner: InputStream) : FilterInputStream(inner) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }

    private fun gb(bytes: Long): String = "%.1f GB".format(bytes / 1_000_000_000.0)

    private fun treeSize(dir: Path): Long =
        runCatching { Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() } }.getOrDefault(0L)

    private fun deleteTree(dir: Path) {
        if (!dir.exists()) return
        runCatching {
            Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } } }
        }
    }
}
