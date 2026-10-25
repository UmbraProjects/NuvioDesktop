package com.nuvio.app.core.build

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Static reflection check (native-image plan step 4).
 *
 * Reflection works on the JVM whether or not it is registered, and on the native build it fails at
 * run time, often silently. So every by-name reflective lookup in the desktop sources must either
 * resolve to a registered type and member, or sit in [allowed] with the reason it needs none. Example:
 * the skiko `FrameWatcher` lookup in `IdleHeapCollector.kt` needs its `skiko/` entry, and this test
 * fails if someone removes that entry or adds a lookup without one.
 *
 * Checked: `Class.forName("x.Y")`, optionally followed by `.getMethod("m")` and the like;
 * `Y::class.java.getMethod("m")` (`Y` resolved through the file's imports); and `v.getField("f")`
 * where `val v = Class.forName("x.Y")` in the same file. Registered means an entry in
 * `composeApp/native-image` (agent output and hand topics) or in the generated metadata, when a native
 * build has produced it.
 */
class NativeImageReflectionCheckTest {
    private class Allowed(val file: String, val snippet: String, val reason: String)

    private val allowed = listOf(
        Allowed(
            "core/build/AppClassArchive.kt", "Class.forName(\"jdk.internal.misc.CDS\")",
            "JVM class-archive training only; guarded by -XX:+AutoCreateSharedArchive, which the native build never has.",
        ),
        Allowed(
            "core/build/StartupClassPreloader.kt", "Class.forName(name, false, loader).declaredConstructors",
            "JVM startup preload of a class list; skipped on the native build (\"native image; preload skipped\").",
        ),
        Allowed(
            "core/build/StartupClassPreloader.kt", "Class.forName(names[i], false, loader)",
            "Same JVM-only preload as above.",
        ),
        Allowed(
            "features/player/desktop/MacosAwtViewResolver.kt", "current.getDeclaredMethod(name)",
            "Two copies: the macOS resolver never runs (Windows-only app); the Windows one calls getHWnd on " +
                "the AWT peer, which the agent output registers and playback exercises on every start.",
        ),
    )

    private val lookup = Regex("""\b(Class\.forName|getDeclaredField|getDeclaredMethod|getMethod|getField|getDeclaredConstructor)\s*\(""")
    private val memberCall = """\s*\.\s*(getMethod|getDeclaredMethod|getField|getDeclaredField)\s*\(\s*"((?:[^"\\]|\\.)*)""""
    private val forNameLiteral = Regex("""Class\.forName\(\s*"([^"]+)"\s*\)(?:$memberCall)?""")
    private val classLiteralMember = Regex("""(\w+)::class\.java$memberCall""")
    private val variableMember = Regex("""(\w+)$memberCall""")
    private val forNameVariable = Regex("""val\s+(\w+)\s*=\s*Class\.forName\(\s*"([^"]+)"\s*\)""")

    private val projectDir: File = listOf(File("."), File("composeApp"))
        .firstOrNull { File(it, "native-image").isDirectory }
        ?: fail("composeApp/native-image not found from ${File(".").absolutePath}")

    private class Registration(val methods: Boolean, val fields: Boolean, val methodNames: Set<String>, val fieldNames: Set<String>)

    private val json = Json { ignoreUnknownKeys = true }

    private fun registrations(): Map<String, List<Registration>> {
        val root = File(projectDir, "native-image")
        val entries = mutableListOf<JsonObject>()
        (json.parseToJsonElement(File(root, "agent/reflect-config.json").readText()) as JsonArray)
            .mapTo(entries) { it as JsonObject }
        val topicFiles = root.listFiles { file -> file.isDirectory && file.name != "agent" }.orEmpty()
            .map { File(it, "reachability-metadata.json") } +
            File(projectDir, "build/native-image/generated/reachability-metadata.json")
        topicFiles.filter { it.isFile }.forEach { file ->
            ((json.parseToJsonElement(file.readText()) as JsonObject)["reflection"] as? JsonArray).orEmpty()
                .mapTo(entries) { it as JsonObject }
        }
        fun JsonObject.flag(name: String) = (this[name] as? JsonPrimitive)?.booleanOrNull == true
        fun JsonElement?.names() = (this as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.content }.toSet()
        return entries.mapNotNull { entry ->
            val type = (entry["type"] as? JsonPrimitive)?.content ?: (entry["name"] as? JsonPrimitive)?.content
                ?: return@mapNotNull null
            type to Registration(
                methods = entry.flag("allDeclaredMethods") || entry.flag("allPublicMethods"),
                fields = entry.flag("allDeclaredFields") || entry.flag("allPublicFields"),
                methodNames = entry["methods"].names(),
                fieldNames = entry["fields"].names(),
            )
        }.groupBy({ it.first }, { it.second })
    }

    private fun resolveSimpleName(simple: String, source: String): String {
        Regex("""^import\s+([\w.]+)\.$simple\s*$""", RegexOption.MULTILINE).find(source)?.let { return "${it.groupValues[1]}.$simple" }
        val pkg = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE).find(source)?.groupValues?.get(1)
        return if (pkg != null) "$pkg.$simple" else simple
    }

    @Test
    fun `every by-name reflective lookup is registered or allowed with a reason`() {
        val registered = registrations()
        assertTrue(registered.isNotEmpty(), "no reflection metadata found under ${projectDir.absolutePath}")
        val sourceRoots = listOf("src/desktopMain/kotlin", "src/fullCommonMain/kotlin").map { File(projectDir, it) }
        val problems = mutableListOf<String>()
        val usedAllowances = mutableSetOf<Allowed>()

        fun check(where: String, type: String, kind: String?, rawMember: String?) {
            val entries = registered[type]
            if (entries == null) {
                problems += "$where: $type has no native-image reflection entry"
                return
            }
            if (kind == null || rawMember == null) return
            val member = rawMember.replace("\\$", "$")
            val ok = if (kind.endsWith("Field")) {
                entries.any { it.fields || member in it.fieldNames }
            } else {
                entries.any { it.methods || member in it.methodNames }
            }
            if (!ok) problems += "$where: $type is registered, but not its ${if (kind.endsWith("Field")) "field" else "method"} '$member'"
        }

        sourceRoots.filter { it.isDirectory }.forEach { sourceRoot ->
            sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val source = file.readText()
                val relative = file.relativeTo(sourceRoot).invariantSeparatorsPath.substringAfter("com/nuvio/app/")
                val variables = forNameVariable.findAll(source).associate { it.groupValues[1] to it.groupValues[2] }
                val lines = source.lines()
                var offset = 0
                lines.forEachIndexed { index, line ->
                    val trimmed = line.trim()
                    val lineStart = offset
                    offset += line.length + 1
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
                    lookup.findAll(line).forEach lookupLoop@{ match ->
                        val where = "$relative:${index + 1}"
                        val allowance = allowed.firstOrNull { relative.endsWith(it.file) && it.snippet in line }
                        if (allowance != null) {
                            usedAllowances += allowance
                            return@lookupLoop
                        }
                        val rest = source.substring(lineStart + match.range.first)
                        forNameLiteral.matchAt(rest, 0)?.let { m ->
                            check(where, m.groupValues[1], m.groupValues[2].ifEmpty { null }, m.groupValues[3].ifEmpty { null })
                            return@lookupLoop
                        }
                        // Member lookups: find the receiver just before the call on this line.
                        val head = line.substring(0, match.range.first + match.value.length - 1).trimEnd()
                        val call = line.substring(match.range.first)
                        val receiverLine = line.substring(0, match.range.first) + call
                        classLiteralMember.findAll(receiverLine).firstOrNull { it.groups[2]!!.range.first == match.range.first }?.let { m ->
                            check(where, resolveSimpleName(m.groupValues[1], source), m.groupValues[2], m.groupValues[3])
                            return@lookupLoop
                        }
                        variableMember.findAll(receiverLine).firstOrNull {
                            it.groups[2]!!.range.first == match.range.first && it.groupValues[1] in variables
                        }?.let { m ->
                            check(where, variables.getValue(m.groupValues[1]), m.groupValues[2], m.groupValues[3])
                            return@lookupLoop
                        }
                        // A member lookup chained onto a checked Class.forName("…") on the same line.
                        if (Regex("""Class\.forName\(\s*"[^"]+"\s*\)\s*\.\s*$""").containsMatchIn(head.removeSuffix(match.groupValues[1]))) {
                            return@lookupLoop
                        }
                        problems += "$where: reflective lookup the check cannot resolve — use literal names, or add it to " +
                            "`allowed` with a reason:\n    $trimmed"
                    }
                }
            }
        }
        (allowed - usedAllowances).forEach { problems += "stale allowance (no longer matches any line): ${it.file}: ${it.snippet}" }
        assertTrue(problems.isEmpty(), "Native-image reflection check:\n" + problems.joinToString("\n"))
    }
}
