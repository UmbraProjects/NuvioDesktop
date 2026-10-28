package com.nuvio.engine.internal

/**
 * JNI surface of nuvio_engine.dll. The JNI symbols are named for this exact class, so the package
 * and object name must stay `com.nuvio.engine.internal.NativeBridge` whatever the DLL is called.
 *
 * The DLL is loaded from an explicit path chosen by [NuvioEngineLibrary], not `loadLibrary`: the
 * packaged app ships it beside Nuvio.exe next to player_bridge.dll, and `gradlew run` reads it out
 * of build/native/windows, and neither directory is on java.library.path.
 */
internal object NativeBridge {
    init {
        NuvioEngineLibrary.load()
    }

    external fun nativeCreate(
        dataDirectory: String,
        cacheDirectory: String,
        memoryCacheCapacityBytes: Long,
        diskCacheCapacityBytes: Long,
        torrentProfile: Int,
        listenPort: Int,
        uploadMode: Int,
        uploadLimitBytesPerSecond: Long,
        streamInactivityTimeoutMilliseconds: Int,
        warmTorrentTimeoutMilliseconds: Int,
        tlsCaBundlePath: String,
        ramOnlyMode: Boolean,
    ): LongArray

    external fun nativeDestroy(handle: Long)
    external fun nativeAddMagnet(handle: Long, magnetUri: String): LongArray
    external fun nativeAddTorrentData(handle: Long, torrentData: ByteArray): LongArray
    external fun nativePollEvent(handle: Long): NativeEventPayload?
    external fun nativeGetFiles(handle: Long, torrentId: String): NativeFilesPayload
    external fun nativePrepareStream(
        handle: Long,
        torrentId: String,
        fileIndex: Int,
        filenameHint: String?,
    ): LongArray
    external fun nativeStopStream(handle: Long, streamId: String): LongArray
    external fun nativeRemoveTorrent(handle: Long, torrentId: String): LongArray
    external fun nativeGetStats(handle: Long): LongArray
    external fun nativeGetStreamStats(handle: Long, streamId: String): LongArray
    external fun nativeReclaimDiskCache(handle: Long, targetBytes: Long): LongArray
    external fun nativeStatusMessage(status: Int): String
    external fun nativeEngineVersion(): String
    external fun nativeBackendVersion(): String
}

internal class NativeEventPayload(
    @JvmField val type: Int,
    @JvmField val sequence: Long,
    @JvmField val requestId: Long,
    @JvmField val droppedEvents: Long,
    @JvmField val torrentId: String,
    @JvmField val message: String,
    @JvmField val fileIndex: Int,
    @JvmField val fileSize: Long,
    @JvmField val streamId: String,
    @JvmField val streamUrl: String,
)

internal class NativeFilePayload(
    @JvmField val index: Int,
    @JvmField val offset: Long,
    @JvmField val size: Long,
    @JvmField val pathTruncated: Boolean,
    @JvmField val path: String,
)

internal class NativeFilesPayload(
    @JvmField val status: Int,
    @JvmField val files: Array<NativeFilePayload>,
)
