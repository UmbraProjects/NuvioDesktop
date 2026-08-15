package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.JFileChooser
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

internal fun credentialFreeSettingsProperties(source: Properties): Properties = Properties().apply {
    source.stringPropertyNames().sorted().forEach { key ->
        if (isSettingsCredentialKey(key)) return@forEach
        val value = source.getProperty(key)
        val sanitizedValue = runCatching {
            backupJson.parseToJsonElement(value).withoutCredentials().toString()
        }.getOrElse { if (isSensitiveSettingsValue(value)) "" else value }
        setProperty(key, sanitizedValue)
    }
}

private fun settingsPropertiesForBackup(file: Path, includeCredentials: Boolean): Properties? {
    if (!includeCredentials && file.name in CredentialStoreNames) return null
    val source = Properties()
    Files.newInputStream(file).use(source::load)
    return if (includeCredentials) source else credentialFreeSettingsProperties(source)
}

internal actual object DesktopSettingsBackup {
    actual fun create(includeCredentials: Boolean): DesktopSettingsBackupResult {
        val chooser = JFileChooser().apply {
            dialogTitle = "Back up Nuvio settings"
            fileFilter = FileNameExtensionFilter("Nuvio settings backup (*.zip)", "zip")
            selectedFile = DesktopStorage.rootDir.resolve(
                "nuvio-settings-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.zip",
            ).toFile()
        }
        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) {
            return DesktopSettingsBackupResult.Cancelled
        }
        val chosen = chooser.selectedFile.toPath()
        val destination = if (chosen.extension.equals("zip", ignoreCase = true)) {
            chosen
        } else {
            chosen.resolveSibling("${chosen.fileName}.zip")
        }.toAbsolutePath().normalize()

        return runCatching {
            Files.createDirectories(destination.parent)
            val temporary = destination.resolveSibling("${destination.fileName}.tmp")
            try {
                ZipOutputStream(Files.newOutputStream(temporary)).use { zip ->
                    val files = Files.list(DesktopStorage.rootDir).use { paths ->
                        paths.filter { path -> path.isRegularFile() && path.extension == "properties" }
                            .sorted()
                            .toList()
                    }
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
}
