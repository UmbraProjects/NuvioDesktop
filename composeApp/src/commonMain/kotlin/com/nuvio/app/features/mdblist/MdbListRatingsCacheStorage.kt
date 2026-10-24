package com.nuvio.app.features.mdblist

import java.io.InputStream
import java.io.OutputStream

/**
 * The MDBList ratings cache and the hero cast cache, each its own file streamed in and out (see
 * encodeJsonMap). They shared one properties store until 2026-10-19, rewritten whole (6.5-13 MB)
 * whenever either changed.
 */
internal expect object MdbListRatingsCacheStorage {
    /** Null when there is no ratings cache yet. */
    fun <T> readRatings(decode: (InputStream) -> T): T?
    fun writeRatings(encode: (OutputStream) -> Unit)
    fun clearRatings()

    /** Null when there is no cast cache yet. */
    fun <T> readCast(decode: (InputStream) -> T): T?
    fun writeCast(encode: (OutputStream) -> Unit)
}
