package com.nuvio.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every compiled app class must load. The JVM refuses a method whose parameters take more than 255
 * slots ("Too many arguments in method signature"), and only when the class is first loaded — at run
 * time, mid-session, as a crash. A wide data class gets there through its synthetic default
 * constructor (a bit mask per 32 fields, plus a marker), and a wide composable through the composer
 * and `$changed` / `$default` ints Compose adds. Loading each class here (without initialising it)
 * turns that crash into a failing test.
 */
class ClassFormatLimitTest {

    @Test
    fun everyAppClassLoads() {
        val loader = javaClass.classLoader
        val anchor = loader.getResource("com/nuvio/app/AppKt.class") ?: fail("app classes not on the test classpath")
        if (anchor.protocol != "file") return // packaged in a jar: nothing to walk
        val root = File(anchor.toURI()).parentFile.parentFile.parentFile.parentFile
        val broken = mutableListOf<String>()
        var loaded = 0
        root.walkTopDown()
            .filter { it.isFile && it.extension == "class" && it.name != "module-info.class" }
            .forEach { file ->
                val name = file.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.')
                try {
                    Class.forName(name, false, loader)
                    loaded++
                } catch (e: ClassFormatError) {
                    broken += "$name: ${e.message}"
                } catch (_: LinkageError) {
                    // A class whose dependency is missing from the test classpath; not this check's concern.
                }
            }
        assertTrue(loaded > 1000, "only $loaded classes found under $root")
        assertTrue(broken.isEmpty(), "classes the JVM refuses to load:\n" + broken.joinToString("\n"))
    }
}
