package com.nuvio.app.core.build

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The native-image build inputs in `composeApp/native-image/` (see its README).
 *
 * native-image rejects a metadata file with a BOM, with an error that does not say so ("Error parsing
 * JNI configuration"), and Windows PowerShell 5.1 writes one by default. A type registered in two
 * hand-written topics means one of the two reasons is wrong, and an undocumented topic loses its
 * reason the first time someone wonders whether it can go.
 */
class NativeImageMetadataTest {
    private val root: File = listOf(File("native-image"), File("composeApp/native-image"))
        .firstOrNull { it.isDirectory }
        ?: fail("composeApp/native-image not found from ${File(".").absolutePath}")

    private val json = Json { ignoreUnknownKeys = true }

    private fun metadataFiles(): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()

    @Test
    fun `every metadata file is BOM-free JSON`() {
        val files = metadataFiles()
        assertTrue(files.isNotEmpty(), "no metadata files under $root")
        files.forEach { file ->
            val bytes = file.readBytes()
            assertTrue(
                !(bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()),
                "${file.relativeTo(root)} starts with a BOM; native-image will refuse it",
            )
            runCatching { json.parseToJsonElement(bytes.decodeToString()) }
                .onFailure { fail("${file.relativeTo(root)} is not valid JSON: ${it.message}") }
        }
    }

    @Test
    fun `every hand-written topic has its metadata and a README saying why`() {
        val topics = root.listFiles { file -> file.isDirectory && file.name != "agent" }.orEmpty()
        assertTrue(topics.isNotEmpty(), "no topic directories under $root")
        topics.forEach { topic ->
            assertTrue(File(topic, "reachability-metadata.json").isFile, "${topic.name}/ has no reachability-metadata.json")
            assertTrue(File(topic, "README.md").isFile, "${topic.name}/ has no README.md explaining its entries")
        }
    }

    @Test
    fun `no type is registered by two hand-written topics`() {
        val owners = mutableMapOf<String, String>()
        val clashes = mutableListOf<String>()
        root.listFiles { file -> file.isDirectory && file.name != "agent" }.orEmpty().forEach { topic ->
            val doc = json.parseToJsonElement(File(topic, "reachability-metadata.json").readText()) as JsonObject
            (doc["reflection"] as? JsonArray).orEmpty().forEach { entry ->
                val type = (entry as JsonObject)["type"]?.jsonPrimitive?.content ?: return@forEach
                owners.put(type, topic.name)?.let { previous -> clashes += "$type in $previous/ and ${topic.name}/" }
            }
        }
        assertTrue(clashes.isEmpty(), "registered twice:\n" + clashes.joinToString("\n"))
    }
}
