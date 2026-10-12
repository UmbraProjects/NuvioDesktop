package com.nuvio.app.core.ui

import coil3.disk.DiskCache
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

/**
 * The artwork disk cache, split so animated collection art cannot evict posters.
 *
 * One LRU served both, and they are not alike: a poster is 50–300 KB, a collection GIF several
 * megabytes. On 2026-10-05 the 5 GB cache was full, with 1.3 GB of it in files over 2 MB — so each
 * long browse of animated collections pushed hundreds of posters out, and those came back as
 * fresh ~2.5 s poster-service renders.
 *
 * Animated images (GIF, animated WebP) go to [animated], which has its own share of the budget;
 * everything else stays in [stills]. The split is decided by the bytes, at the moment an entry is
 * committed, so no caller has to say what it is loading. A key lives in one partition at a time:
 * reads look in [animated] first because it is far smaller, and an entry that moves is removed
 * from the partition it left.
 *
 * GIFs already in the stills cache from before the split stay there until they are evicted or
 * re-downloaded; nothing migrates them, because the journal only knows hashed keys and Coil's API
 * only accepts the originals.
 */
internal class PartitionedImageDiskCache(
    private val stills: DiskCache,
    private val animated: DiskCache,
) : DiskCache {

    override val size: Long get() = stills.size + animated.size
    override val maxSize: Long get() = stills.maxSize + animated.maxSize
    override val directory: Path get() = stills.directory
    override val fileSystem: FileSystem get() = stills.fileSystem

    override fun openSnapshot(key: String): DiskCache.Snapshot? {
        animated.openSnapshot(key)?.let { return it }
        val snapshot = stills.openSnapshot(key) ?: return null
        // An empty body is never a real image: before RoutingEditor learned to leave a 304's body
        // alone, its sniff truncated revalidated entries to zero bytes. Treated as a miss so those
        // entries heal on the next load instead of failing to decode forever.
        if (fileSystem.metadataOrNull(snapshot.data)?.size == 0L) {
            snapshot.close()
            stills.remove(key)
            return null
        }
        return RoutingSnapshot(key, snapshot)
    }

    override fun openEditor(key: String): DiskCache.Editor? {
        animated.openSnapshot(key)?.let { existing ->
            existing.close()
            return animated.openEditor(key)
        }
        return stills.openEditor(key)?.let { RoutingEditor(key, it) }
    }

    override fun remove(key: String): Boolean {
        val fromAnimated = animated.remove(key)
        val fromStills = stills.remove(key)
        return fromAnimated || fromStills
    }

    override fun clear() {
        stills.clear()
        animated.clear()
    }

    override fun shutdown() {
        stills.shutdown()
        animated.shutdown()
    }

    /** A stills entry, so that rewriting it (`NetworkFetcher` replacing a stale copy) still routes. */
    private inner class RoutingSnapshot(
        private val key: String,
        private val delegate: DiskCache.Snapshot,
    ) : DiskCache.Snapshot by delegate {
        override fun closeAndOpenEditor(): DiskCache.Editor? =
            delegate.closeAndOpenEditor()?.let { RoutingEditor(key, it) }
    }

    /**
     * Writes into the stills cache; on commit, an animated body is copied into [animated] instead
     * and the stills edit abandoned. A metadata-only edit (a 304 refreshing headers) commits where
     * it is without looking at the body: merely asking the editor for [data] marks the body written
     * and creates it empty, so a sniff there would commit a zero-byte image over the cached one.
     */
    private inner class RoutingEditor(
        private val key: String,
        private val delegate: DiskCache.Editor,
    ) : DiskCache.Editor by delegate {

        @Volatile
        private var bodyWritten = false

        override val data: Path
            get() = delegate.data.also { bodyWritten = true }

        override fun commit() {
            commitAndOpenSnapshot()?.close()
        }

        override fun commitAndOpenSnapshot(): DiskCache.Snapshot? {
            if (!bodyWritten || !fileSystem.isAnimatedImage(delegate.data)) {
                return delegate.commitAndOpenSnapshot()?.let { RoutingSnapshot(key, it) }
            }
            val target = animated.openEditor(key)
                ?: return delegate.commitAndOpenSnapshot()?.let { RoutingSnapshot(key, it) }
            return try {
                fileSystem.copy(delegate.metadata, target.metadata)
                fileSystem.copy(delegate.data, target.data)
                delegate.abort()
                stills.remove(key)
                target.commitAndOpenSnapshot()
            } catch (error: Exception) {
                runCatching { target.abort() }
                runCatching { delegate.abort() }
                throw error
            }
        }
    }
}

/** GIF, or a WebP whose extended header has the animation flag set. */
internal fun FileSystem.isAnimatedImage(path: Path): Boolean {
    val header = runCatching {
        if (!exists(path)) return false
        source(path).buffer().use { source ->
            val bytes = ByteArray(AnimatedSniffBytes)
            var read = 0
            while (read < bytes.size) {
                val count = source.read(bytes, read, bytes.size - read)
                if (count <= 0) break
                read += count
            }
            bytes.copyOf(read)
        }
    }.getOrNull() ?: return false
    return header.isGif() || header.isAnimatedWebP()
}

private fun ByteArray.isGif(): Boolean =
    size >= 6 && this[0] == 'G'.code.toByte() && this[1] == 'I'.code.toByte() &&
        this[2] == 'F'.code.toByte() && this[3] == '8'.code.toByte()

/** `RIFF....WEBPVP8X` followed by a flags byte whose bit 1 (0x02) is ANIMATION. */
private fun ByteArray.isAnimatedWebP(): Boolean =
    size >= 21 && ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" && ascii(12, 4) == "VP8X" &&
        (this[20].toInt() and 0x02) != 0

private fun ByteArray.ascii(offset: Int, length: Int): String =
    String(this, offset, length, Charsets.US_ASCII)

private const val AnimatedSniffBytes = 32

/** The animated partition's share of the configured total. */
internal const val AnimatedImageCacheFraction = 0.25
