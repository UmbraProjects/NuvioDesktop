package com.nuvio.app.core.resources

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Compose Multiplatform resources do not unescape Android's backslash-apostrophe: it reaches the
 * screen as written ("This server\'s films"). Apostrophes go in as they are.
 */
class StringEscapeTest {
    private val escapedApostrophe = "\\'"

    @Test
    fun noStringUsesAnAndroidEscapedApostrophe() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .flatMap {
                sequenceOf(File(it, "composeApp/src/commonMain/composeResources"), File(it, "src/commonMain/composeResources"))
            }
            .first { it.isDirectory }
        val offenders = root.listFiles { file -> file.isDirectory && file.name.startsWith("values") }.orEmpty()
            .flatMap { dir -> dir.listFiles { file -> file.extension == "xml" }.orEmpty().toList() }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    "${file.parentFile.name}/${file.name}:${index + 1}".takeIf { escapedApostrophe in line }
                }
            }
        assertTrue(offenders.isEmpty(), "Use a plain apostrophe in Compose resources: $offenders")
    }
}
