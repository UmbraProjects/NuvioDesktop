package com.nuvio.app.features.settings

import com.nuvio.app.relaunchAfterExit
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.MACHINE_STORE_NAMES
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

private val CredentialStoreNames = setOf(
    "nuvio_auth.properties",
    "nuvio_trakt_auth.properties",
    "nuvio_simkl_auth.properties",
    "nuvio_profile_pin_cache.properties",
)

private val SensitiveKeyFragments = listOf(
    "access_token",
    "refreshtoken",
    "refresh_token",
    "api_key",
    "apikey",
    "authorization",
    "credential",
    "client_secret",
    "password",
    "passphrase",
    "private_key",
    "profile_pin",
    "secret",
    "session",
    "token",
)

private val SensitiveValueMarkers = listOf(
    "access_token=",
    "api_key=",
    "apikey=",
    "authorization=",
    "token=",
    "realdebrid=",
    "alldebrid=",
    "premiumize=",
    "debridlink=",
    "offcloud=",
    "putio=",
    "torbox=",
)

private val backupJson = Json { prettyPrint = false }

internal fun isSettingsCredentialKey(key: String): Boolean {
    val normalized = key.lowercase().replace('-', '_').replace('.', '_')
    return SensitiveKeyFragments.any(normalized::contains)
}

private fun containsEmbeddedCredential(value: String): Boolean {
    val normalized = value.lowercase()
    return SensitiveValueMarkers.any(normalized::contains)
}

private fun looksLikeHttpUrl(value: String): Boolean {
    val normalized = value.trimStart().lowercase()
    return normalized.startsWith("http://") || normalized.startsWith("https://")
}

/**
 * Whether an http(s) URL is safe to put in a credential-free export.
 *
 * Allowlisted rather than denylisted, which is the whole point: [SensitiveValueMarkers] can only
 * name the credential shapes someone thought of, and a Stremio addon's configuration is arbitrary
 * text chosen by the addon author. `https://host/manifest.json` is the unconfigured form and carries
 * nothing; every other shape — userinfo, a query string, or any path segment ahead of the manifest —
 * is configuration, and configuration is where the debrid keys, opaque session tokens and account
 * ids live. Unparseable input is treated as configured, since a URL that cannot be inspected cannot
 * be cleared.
 *
 * The cost of being wrong in this direction is a restored profile that needs its addons re-added.
 * The cost of being wrong in the other direction is a debrid key in a file the user shares.
 */
internal fun isCredentialFreeUrl(value: String): Boolean {
    val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
    if (uri.userInfo != null) return false
    if (uri.rawQuery != null || uri.rawFragment != null) return false
    val path = uri.rawPath.orEmpty().trim('/')
    return path.isEmpty() || path.equals("manifest.json", ignoreCase = true)
}

/**
 * True when this string must not survive a credential-free export.
 *
 * URLs go through [isCredentialFreeUrl]; everything else falls back to the marker denylist, which
 * still catches credentials embedded in non-URL values.
 */
private fun isSensitiveSettingsValue(value: String): Boolean =
    if (looksLikeHttpUrl(value)) !isCredentialFreeUrl(value) else containsEmbeddedCredential(value)

private fun JsonElement.withoutCredentials(): JsonElement = when (this) {
    // Keys are filtered on their own merits: `addon_enabled_states_<profile>` is a map *keyed by
    // manifest URL*, so a configured URL hides in the key rather than the value and would otherwise
    // be exported untouched however carefully the values are cleaned.
    is JsonObject -> JsonObject(
        entries
            .filterNot { (key, _) -> isSettingsCredentialKey(key) || isSensitiveSettingsValue(key) }
            .associate { (key, value) -> key to value.withoutCredentials() },
    )
    is JsonArray -> JsonArray(
        mapNotNull { element ->
            element.withoutCredentials().takeUnless { sanitized -> sanitized === JsonNull }
        },
    )
    is JsonPrimitive -> if (isString && isSensitiveSettingsValue(content)) JsonNull else this
    else -> this
}

private fun credentialFreeSettingsValue(value: String): String = runCatching {
    backupJson.parseToJsonElement(value).withoutCredentials().toString()
}.getOrElse { if (isSensitiveSettingsValue(value)) "" else value }

internal fun credentialFreeSettingsProperties(source: Properties): Properties = Properties().apply {
    source.stringPropertyNames().sorted().forEach { key ->
        if (isSettingsCredentialKey(key)) return@forEach
        setProperty(key, credentialFreeSettingsValue(source.getProperty(key)))
    }
}

/**
 * Lays a credential-free backup of one store over what this machine already has, without signing
 * anything out.
 *
 * Keys the export always drops (tokens, API keys, PINs) are kept from [current]. A value the export
 * would have *cleaned* — an addon list minus its configured URLs — keeps the local value when that
 * cleans to exactly what the backup holds, i.e. the setting has not changed since the backup and the
 * only difference is the credentials that were stripped. Everything else comes from [backup].
 */
internal fun mergeCredentialFreeStore(current: Properties, backup: Properties): Properties = Properties().apply {
    backup.stringPropertyNames().forEach { key ->
        val backupValue = backup.getProperty(key)
        val currentValue = current.getProperty(key)
        val unchanged = currentValue != null && credentialFreeSettingsValue(currentValue) == backupValue
        setProperty(key, if (unchanged) currentValue else backupValue)
    }
    current.stringPropertyNames()
        .filter { key -> key !in backup.stringPropertyNames() && isSettingsCredentialKey(key) }
        .forEach { key -> setProperty(key, current.getProperty(key)) }
}

private const val PreferencesEntryPrefix = "preferences/"
private const val BackupInfoEntry = "backup-info.txt"
private const val CredentialsIncludedLine = "Credentials included:"
private val StoreFileName = Regex("[A-Za-z0-9_.-]+\\.properties")
private const val MaxStoreEntryBytes = 64 * 1024 * 1024

internal class SettingsBackupContents(
    val stores: Map<String, Properties>,
    val credentialsIncluded: Boolean,
)

/**
 * Reads a backup written by [DesktopSettingsBackup.create]. Only `preferences/<store>.properties`
 * entries are taken, by bare file name, so nothing in the archive can name a path outside the data
 * directory.
 */
internal fun readSettingsBackup(input: InputStream): SettingsBackupContents {
    val stores = sortedMapOf<String, Properties>()
    var credentialsIncluded: Boolean? = null
    ZipInputStream(input).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            val bytes = zip.readNBytes(MaxStoreEntryBytes + 1)
            require(bytes.size <= MaxStoreEntryBytes) { "${entry.name} is too large for a settings store." }
            when {
                entry.name == BackupInfoEntry -> credentialsIncluded = bytes.decodeToString().lineSequence()
                    .firstOrNull { it.startsWith(CredentialsIncludedLine) }
                    ?.substringAfter(CredentialsIncludedLine)?.trim()?.toBooleanStrictOrNull()
                entry.name.startsWith(PreferencesEntryPrefix) -> {
                    val name = entry.name.removePrefix(PreferencesEntryPrefix)
                    if (!StoreFileName.matches(name) || name.startsWith(".")) continue
                    stores[name] = Properties().apply { load(ByteArrayInputStream(bytes)) }
                }
            }
        }
    }
    require(stores.isNotEmpty()) { "That file is not a Nuvio settings backup." }
    // An unlabelled backup is merged rather than replaced: keeping local sign-ins is harmless if
    // the backup did carry its own, and losing them is not.
    return SettingsBackupContents(stores, credentialsIncluded ?: false)
}

private const val PendingImportName = "pending-settings-import.zip"
private const val FailedImportName = "pending-settings-import.failed.zip"

/**
 * Applies a backup staged by [DesktopSettingsBackup.import]. Called from `main()` once this copy
 * owns the data directory and before any store is opened: nearly every repository caches its
 * settings in memory from first access, so a restore written under a running app would be shadowed
 * for the rest of the session and then overwritten by its next flush.
 *
 * Stores the backup does not contain are left alone. A backup that cannot be applied is set aside
 * rather than retried on every launch.
 */
internal fun applyPendingSettingsImport(rootDir: Path) {
    val pending = rootDir.resolve(PendingImportName)
    if (!Files.isRegularFile(pending)) return
    runCatching {
        val contents = Files.newInputStream(pending).use(::readSettingsBackup)
        contents.stores.forEach { (name, backup) ->
            // Into the signed-in account's folder, or the data directory for a machine store.
            val target = DesktopStorage.storeFile(name.removeSuffix(".properties"))
            val restored = if (contents.credentialsIncluded) {
                backup
            } else {
                val current = Properties()
                if (Files.isRegularFile(target)) Files.newInputStream(target).use(current::load)
                mergeCredentialFreeStore(current, backup)
            }
            writeRestoredStore(target, restored)
        }
        Files.delete(pending)
        System.out.println(
            "Info: (DesktopStartup) imported ${contents.stores.size} settings stores " +
                "(credentials included=${contents.credentialsIncluded})",
        )
    }.onFailure { error ->
        System.err.println("Error: (DesktopStartup) settings import failed: ${error.message}")
        error.printStackTrace(System.err)
        runCatching { Files.move(pending, rootDir.resolve(FailedImportName), StandardCopyOption.REPLACE_EXISTING) }
            .onFailure { runCatching { Files.deleteIfExists(pending) } }
    }
}

/** Same swap as the store's own write, so the pre-import file is left behind as its `.bak`. */
private fun writeRestoredStore(target: Path, properties: Properties) {
    val temporary = target.resolveSibling("${target.fileName}.tmp")
    val backup = target.resolveSibling("${target.fileName}.bak")
    try {
        Files.newOutputStream(temporary).use { output -> properties.store(output, "Nuvio desktop preferences") }
        runCatching {
            Files.deleteIfExists(backup)
            if (Files.exists(target)) Files.createLink(backup, target)
        }
        runCatching { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE) }
            .recoverCatching { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
            .getOrThrow()
    } catch (error: Throwable) {
        runCatching { Files.deleteIfExists(temporary) }
        throw error
    }
}

private fun settingsPropertiesForBackup(file: Path, includeCredentials: Boolean): Properties? {
    if (!includeCredentials && file.name in CredentialStoreNames) return null
    val source = Properties()
    Files.newInputStream(file).use(source::load)
    return if (includeCredentials) source else credentialFreeSettingsProperties(source)
}

// Swing's default look-and-feel has no UI classes in the packaged runtime, so a chooser or option
// pane built before one is installed throws "no ComponentUI class". The other pickers do the same.
private fun installSystemLookAndFeel() {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
}

internal actual object DesktopSettingsBackup {
    actual fun create(includeCredentials: Boolean): DesktopSettingsBackupResult {
        installSystemLookAndFeel()
        val chosen = runCatching {
            val chooser = JFileChooser().apply {
                dialogTitle = "Back up Nuvio settings"
                fileFilter = FileNameExtensionFilter("Nuvio settings backup (*.zip)", "zip")
                selectedFile = DesktopStorage.rootDir.resolve(
                    "nuvio-settings-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.zip",
                ).toFile()
            }
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
        }.getOrElse { error ->
            return DesktopSettingsBackupResult.Failed("Could not open the file picker: ${error.message ?: error::class.simpleName}")
        } ?: return DesktopSettingsBackupResult.Cancelled
        val destination = if (chosen.extension.equals("zip", ignoreCase = true)) {
            chosen
        } else {
            chosen.resolveSibling("${chosen.fileName}.zip")
        }.toAbsolutePath().normalize()

        return runCatching {
            // The backup reads the files off disk; writes are otherwise coalesced for a second or two.
            DesktopStorage.flushAll()
            Files.createDirectories(destination.parent)
            val temporary = destination.resolveSibling("${destination.fileName}.tmp")
            try {
                ZipOutputStream(Files.newOutputStream(temporary)).use { zip ->
                    // This PC's shared stores plus the signed-in account's own; other accounts'
                    // folders are theirs to back up.
                    fun storesIn(directory: Path, keep: (String) -> Boolean) = Files.list(directory).use { paths ->
                        paths.filter { path ->
                            path.isRegularFile() && path.extension == "properties" &&
                                keep(path.fileName.toString().removeSuffix(".properties"))
                        }.toList()
                    }
                    val files = (
                        storesIn(DesktopStorage.rootDir) { it in MACHINE_STORE_NAMES } +
                            storesIn(DesktopStorage.accountDir) { true }
                        ).sortedBy { it.fileName.toString() }
                    files.forEach { file ->
                        val properties = settingsPropertiesForBackup(file, includeCredentials)
                            ?: return@forEach
                        zip.putNextEntry(ZipEntry("preferences/${file.fileName}"))
                        properties.store(zip, "Nuvio settings backup")
                        zip.closeEntry()
                    }
                    zip.putNextEntry(ZipEntry("backup-info.txt"))
                    zip.write(
                        buildString {
                            appendLine("Nuvio settings backup")
                            appendLine("Created: ${LocalDateTime.now()}")
                            appendLine("Credentials included: $includeCredentials")
                            appendLine("Contains desktop *.properties preference stores.")
                        }.encodeToByteArray(),
                    )
                    zip.closeEntry()
                }
                runCatching {
                    Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                }.recoverCatching {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
                }.getOrThrow()
            } catch (error: Throwable) {
                Files.deleteIfExists(temporary)
                throw error
            }
            DesktopSettingsBackupResult.Saved(destination.toString())
        }.getOrElse { error ->
            DesktopSettingsBackupResult.Failed(error.message ?: "Unable to create settings backup")
        }
    }

    actual fun import(): DesktopSettingsImportResult {
        installSystemLookAndFeel()
        val selected = runCatching {
            val chooser = JFileChooser(DesktopStorage.rootDir.toFile()).apply {
                dialogTitle = "Import Nuvio settings"
                fileFilter = FileNameExtensionFilter("Nuvio settings backup (*.zip)", "zip")
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        }.getOrElse { error ->
            return DesktopSettingsImportResult.Failed("Could not open the file picker: ${error.message ?: error::class.simpleName}")
        } ?: return DesktopSettingsImportResult.Cancelled
        val source = selected.toPath().toAbsolutePath().normalize()
        return runCatching {
            // Read in full now, so a bad file is reported here rather than discovered at next launch.
            val contents = Files.newInputStream(source).use(::readSettingsBackup)
            val signIns = if (contents.credentialsIncluded) {
                "This backup includes sign-ins and keys, which replace the ones on this PC."
            } else {
                "This backup has no sign-ins or keys, so the ones on this PC are kept."
            }
            val confirmed = JOptionPane.showConfirmDialog(
                null,
                "Import ${contents.stores.size} settings files from ${source.fileName}?\n\n" +
                    "$signIns\nSettings the backup does not contain are left as they are.\n\n" +
                    "Nuvio will restart to apply them.",
                "Import Nuvio settings",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE,
            )
            if (confirmed != JOptionPane.OK_OPTION) return DesktopSettingsImportResult.Cancelled
            Files.copy(source, DesktopStorage.rootDir.resolve(PendingImportName), StandardCopyOption.REPLACE_EXISTING)
            DesktopSettingsImportResult.Staged(restarting = relaunchAfterExit("relaunch-after-import.bat"))
        }.getOrElse { error ->
            DesktopSettingsImportResult.Failed(
                (error as? IllegalArgumentException)?.message
                    ?: "Could not read that backup: ${error.message ?: error::class.simpleName}",
            )
        }
    }
}
