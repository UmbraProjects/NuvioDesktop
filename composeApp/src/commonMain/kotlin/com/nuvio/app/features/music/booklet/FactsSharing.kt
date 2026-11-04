package com.nuvio.app.features.music.booklet
// FORK UI (Spyder): Nick's album page facts, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** Picks a text file to read or a place to save one (a Windows file dialog). Null when the person cancels. */
internal expect object FactsFilePicker {
    /** The chosen file's name and text. */
    suspend fun openText(): Pair<String, String>?

    /** Saves [contents]; returns the file's name, or null when cancelled. */
    suspend fun saveText(suggestedName: String, contents: String): String?
}

/** What choosing a file found, held until the person applies or cancels. */
internal data class ImportPreview(val fileName: String, val plan: ImportPlan)

/*
 * fork:facts-share - export and import of facts as a JSON file (the rules are in FactsShare.kt). Nothing leaves this
 * computer by itself: the person saves a file and gives it to someone, and imports one someone gave them.
 */
internal object FactsSharing {
    private val log = Logger.withTag("FactsSharing")

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    private val _preview = MutableStateFlow<ImportPreview?>(null)
    val preview: StateFlow<ImportPreview?> = _preview.asStateFlow()

    /** The last thing that happened, as a line for the settings page; null when there is nothing to say. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _imports = MutableStateFlow(loadImports())
    val imports: StateFlow<List<ImportRecord>> = _imports.asStateFlow()

    private fun loadImports(): List<ImportRecord> = FillStorage.keys().filter { it.startsWith("imp1|") }
        .mapNotNull { decodeImportRecord(FillStorage.get(it)) }.sortedByDescending { it.atMs }

    private fun haveFact(key: String) = decodeBookletEntry(BookletCache.get(key))?.booklet != null
    private fun havePin(key: String) = PinStorage.get(key) != null

    /** Writes the AI's facts and the person's pins to a file the person names. */
    suspend fun export() {
        val entries = withContext(Dispatchers.Default) {
            val facts = BookletCache.keptKeys().sorted().mapNotNull { key ->
                val value = BookletCache.get(key)
                if (value != null && checkShareEntry(key, value)) ShareEntry(key, value) else null
            }
            val pins = PinStorage.keys().filter { it.startsWith("pin1|") }.sorted().mapNotNull { key ->
                val value = PinStorage.get(key)
                if (value != null && checkShareEntry(key, value)) ShareEntry(key, value) else null
            }
            facts + pins
        }
        if (entries.isEmpty()) { _message.value = "Nothing to export yet"; return }
        val text = withContext(Dispatchers.Default) { encodeShare(entries, now()) }
        val day = Clock.System.now().toString().take(10)
        val saved = runCatching { FactsFilePicker.saveText("nuvio-music-facts-$day.json", text) }.getOrNull()
        _message.value = saved?.let { "Saved ${entries.size} entries to $it" }
    }

    /** Asks for a file and shows what applying it would do; nothing is written until [apply]. */
    suspend fun chooseImport() {
        val (name, text) = runCatching { FactsFilePicker.openText() }.getOrNull() ?: return
        val parsed = withContext(Dispatchers.Default) { parseShare(text) }
        if (parsed == null) { _message.value = "$name is not a Nuvio music facts file"; return }
        val plan = withContext(Dispatchers.Default) { planImport(parsed, ::haveFact, ::havePin) }
        if (plan.isEmpty) {
            _message.value = "Nothing new in $name (${plan.alreadyHave} already here" + (if (plan.rejected > 0) ", ${plan.rejected} skipped" else "") + ")"
            return
        }
        _preview.value = ImportPreview(name, plan)
    }

    fun cancel() { _preview.value = null }

    /** Writes the previewed entries that are still gaps, and records the import so it can be undone. */
    suspend fun apply() {
        val shown = _preview.value ?: return
        _preview.value = null
        val id = now().toString(36)
        val written = withContext(Dispatchers.Default) {
            val keys = ArrayList<String>()
            for (entry in shown.plan.add) {
                if (shareKindOf(entry.key) == ShareKind.Pin) {
                    if (havePin(entry.key)) continue
                    val pin = decodePin(entry.value) ?: continue
                    PinStorage.put(entry.key, encodePin(pin.copy(origin = importOrigin(id))))
                } else {
                    if (haveFact(entry.key)) continue
                    BookletCache.put(entry.key, entry.value)
                }
                keys += entry.key
            }
            keys
        }
        if (written.isEmpty()) { _message.value = "Nothing was added"; return }
        val record = ImportRecord(id, now(), shown.fileName, written)
        FillStorage.put("imp1|$id", encodeImportRecord(record))
        _imports.update { listOf(record) + it }
        _message.value = "Added ${describeCounts(written.groupingBy { shareKindOf(it)!! }.eachCount())} from ${shown.fileName}"
        log.i { "import $id: ${written.size} entries from ${shown.fileName}" }
    }

    /** Removes what the import [id] added (a pin the person has edited since is theirs and stays). */
    suspend fun undo(id: String) {
        val record = _imports.value.firstOrNull { it.id == id } ?: return
        val removed = withContext(Dispatchers.Default) {
            val keys = keysToUndo(record) { key ->
                if (shareKindOf(key) == ShareKind.Pin) PinStorage.get(key) else BookletCache.get(key)
            }
            keys.forEach { key -> if (shareKindOf(key) == ShareKind.Pin) PinStorage.put(key, null) else BookletCache.put(key, null) }
            keys.size
        }
        FillStorage.put("imp1|$id", null)
        _imports.update { list -> list.filter { it.id != id } }
        _message.value = "Removed $removed entries added from ${record.fileName}"
    }

    fun clearMessage() { _message.value = null }
}
