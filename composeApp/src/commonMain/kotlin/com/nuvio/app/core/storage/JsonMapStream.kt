package com.nuvio.app.core.storage

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Writes [entries] as one JSON object, `{"key": dto, ...}`, straight to [output], converting each
 * entry with [toDto] only as it is written.
 *
 * For whole-map caches rewritten on every change. Encoding the map to a String first made each
 * write a DTO copy of every entry plus the full text (the MDBList ratings + hero cast store was a
 * 15M-char buffer in a 2026-10-19 heap dump); this keeps one entry in flight. The output is the same
 * JSON `encodeToString(map of dto)` produces, so [decodeJsonMap] and older files read it alike.
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun <E, D> Json.encodeJsonMap(
    output: OutputStream,
    entries: Map<String, E>,
    dtoSerializer: KSerializer<D>,
    toDto: (E) -> D,
) {
    output.write('{'.code)
    var first = true
    for ((key, entry) in entries) {
        if (!first) output.write(','.code)
        first = false
        encodeToStream(String.serializer(), key, output)
        output.write(':'.code)
        encodeToStream(dtoSerializer, toDto(entry), output)
    }
    output.write('}'.code)
    output.flush()
}

/** Reads what [encodeJsonMap] wrote, without first reading the file into a String. */
@OptIn(ExperimentalSerializationApi::class)
internal fun <D> Json.decodeJsonMap(input: InputStream, dtoSerializer: KSerializer<D>): Map<String, D> =
    decodeFromStream(MapSerializer(String.serializer(), dtoSerializer), input)
