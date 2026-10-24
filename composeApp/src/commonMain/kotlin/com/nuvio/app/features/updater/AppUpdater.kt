package com.nuvio.app.features.updater

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.build.AppVersionPolicy
import com.nuvio.app.core.build.PackagedBuild
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.core.ui.NuvioDialogSurface
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.platformOpenLogsDirectory
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.StartupOverlayCoordinator
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

private const val gitHubOwner = "UmbraProjects"
private const val gitHubRepo = "NuvioDesktop"
private const val gitHubApiBase = "https://api.github.com"
private const val releaseChannelBranch = "windows-tv-adaptive"

/** The rolling prerelease tag every nightly build is uploaded under. */
private const val nightlyReleaseTag = "Nightly"

data class AppUpdate(
    val channel: UpdateChannel,
    val tag: String,
    val title: String,
    val notes: String,
    val releaseUrl: String?,
    val assetName: String,
    val assetUrl: String,
    val assetSizeBytes: Long?,
    // Expected SHA-256 (lowercase hex) of the asset, from the GitHub API's `digest` field. Null
    // for assets uploaded before GitHub started publishing digests — verification is then skipped.
    val assetSha256: String? = null,
    // When this build was published, for display. GitHub's own timestamp, so nightlies uploaded
    // under one unchanging tag still have something to name them by.
    val publishedAt: String? = null,
) {
    /**
     * What identifies this exact build. The tag can't: every nightly reuses one tag, so the asset's
     * checksum (or, failing that, its upload time) is the only thing that changes between them.
     */
    val buildId: String? get() = assetSha256 ?: publishedAt

    /**
     * Key under which "ignore this version" is remembered. Nightlies fold the build into the key —
     * ignoring one nightly must not mute the tag forever.
     */
    fun ignoreKey(): String = when (channel) {
        UpdateChannel.Stable -> tag
        UpdateChannel.Nightly -> "$tag@${buildId.orEmpty()}"
    }
}

data class AppUpdaterUiState(
    val isChecking: Boolean = false,
    val update: AppUpdate? = null,
    val isUpdateAvailable: Boolean = false,
    /** The offered build is the other channel's, not a newer version of this one. */
    val isChannelSwitch: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float? = null,
    val downloadedApkPath: String? = null,
    val showDialog: Boolean = false,
    val showUnknownSourcesDialog: Boolean = false,
    val errorMessage: String? = null,
)

@Serializable
internal data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String? = null,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("target_commitish") val targetCommitish: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GitHubAssetDto> = emptyList(),
)

@Serializable
internal data class GitHubAssetDto(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long? = null,
    @SerialName("content_type") val contentType: String? = null,
    // GitHub publishes this as e.g. "sha256:1a2b…"; absent on older assets.
    val digest: String? = null,
    // Moves every time the asset is re-uploaded, which is how a rolling nightly tag gets a date.
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    /** The lowercase SHA-256 hex from [digest], or null when GitHub didn't provide one. */
    fun sha256Hex(): String? {
        val value = digest?.trim()?.lowercase() ?: return null
        if (!value.startsWith("sha256:")) return null
        return value.removePrefix("sha256:").takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    }
}

private val appUpdaterJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private class NoChannelReleaseException : IllegalStateException(
    runBlocking { getString(Res.string.updates_no_channel_release) },
)

private object VersionUtils {
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.trim().removePrefix("v").removePrefix("V")
    }

    fun parseVersionParts(raw: String?): List<Int>? {
        val normalized = normalize(raw)
        if (normalized.isBlank()) return null

        val parts = normalized.split('.', '-', '_')
            .filter { it.isNotBlank() }
            .mapNotNull { token -> token.takeWhile { it.isDigit() }.toIntOrNull() }

        return parts.takeIf { it.isNotEmpty() }
    }

    fun isRemoteNewer(remote: String?, local: String?): Boolean {
        val remoteParts = parseVersionParts(remote)
        val localParts = parseVersionParts(local)

        if (remoteParts == null || localParts == null) {
            val remoteValue = normalize(remote)
            val localValue = normalize(local)
            return remoteValue.isNotBlank() && localValue.isNotBlank() && remoteValue != localValue
        }

        val maxSize = maxOf(remoteParts.size, localParts.size)
        for (index in 0 until maxSize) {
            val remoteValue = remoteParts.getOrElse(index) { 0 }
            val localValue = localParts.getOrElse(index) { 0 }
            if (remoteValue != localValue) return remoteValue > localValue
        }
        return false
    }
}

private object AppUpdaterRepository {
    suspend fun getLatestChannelUpdate(channel: UpdateChannel): Result<AppUpdate> = runCatching {
        val release = fetchChannelRelease(channel) ?: throw NoChannelReleaseException()

        val tag = release.tagName?.takeIf { it.isNotBlank() }
            ?: release.name?.takeIf { it.isNotBlank() }
            ?: error(getString(Res.string.updates_release_missing_title))

        val asset = selectBestPortableUpdateAsset(release.assets)
            ?: error(getString(Res.string.updates_update_asset_missing))

        AppUpdate(
            channel = channel,
            tag = tag,
            title = release.name?.takeIf { it.isNotBlank() } ?: tag,
            notes = release.body.orEmpty(),
            releaseUrl = release.htmlUrl,
            assetName = asset.name,
            assetUrl = asset.browserDownloadUrl,
            assetSizeBytes = asset.size,
            assetSha256 = asset.sha256Hex(),
            // The asset's own upload time, not the release's: a nightly's tag and release date stay
            // put while the ZIP behind them is replaced.
            publishedAt = asset.updatedAt ?: release.publishedAt,
        )
    }

    /**
     * Fetches the one release the channel points at.
     *
     * Deliberately not a scan of `/releases`: that list, served unauthenticated, has been observed
     * omitting a published release for days at a time (v1.13 was absent from it while
     * `/releases/latest` and `/releases/tags/v1.13` both returned it), which silently strands
     * everyone on an older build with no error to notice. The per-channel endpoints each name
     * exactly one release, so there is nothing for the listing to lose. The list is still consulted
     * as a fallback for the case where the direct endpoint is the one that fails.
     */
    private suspend fun fetchChannelRelease(channel: UpdateChannel): GitHubReleaseDto? {
        val releasesBase = "$gitHubApiBase/repos/$gitHubOwner/$gitHubRepo/releases"
        val directUrl = when (channel) {
            UpdateChannel.Stable -> "$releasesBase/latest"
            UpdateChannel.Nightly -> "$releasesBase/tags/$nightlyReleaseTag"
        }

        fetchRelease(directUrl)?.takeIf { it.matchesChannel(channel) }?.let { return it }

        return fetchReleaseList("$releasesBase?per_page=20").firstOrNull { it.matchesChannel(channel) }
    }

    /** A single release, or null when GitHub has none under that name (a 404 is not an error). */
    private suspend fun fetchRelease(url: String): GitHubReleaseDto? {
        val response = requestGitHub(url)
        if (response.status !in 200..299) return null
        return runCatching { appUpdaterJson.decodeFromString<GitHubReleaseDto>(response.body) }.getOrNull()
    }

    private suspend fun fetchReleaseList(url: String): List<GitHubReleaseDto> {
        val response = requestGitHub(url)
        if (response.status !in 200..299) {
            error(getString(Res.string.updates_github_api_error, response.status))
        }
        return appUpdaterJson.decodeFromString<List<GitHubReleaseDto>>(response.body)
    }

    private suspend fun requestGitHub(url: String) = httpRequestRaw(
        method = "GET",
        url = url,
        headers = mapOf(
            "Accept" to "application/vnd.github+json",
            "User-Agent" to "NuvioMobile",
        ),
        body = "",
    )
}

/**
 * Whether this release is the one the given channel distributes.
 *
 * Nightlies are matched on their tag as well as the prerelease flag so that un-ticking "set as a
 * pre-release" on the nightly — an easy thing to do by accident when re-cutting it — moves it into
 * neither channel's blind spot: it stays nightly, and stable still refuses it.
 */
internal fun GitHubReleaseDto.matchesChannel(channel: UpdateChannel): Boolean {
    if (draft || !matchesReleaseBranch()) return false
    val isNightly = tagName?.trim().equals(nightlyReleaseTag, ignoreCase = true)
    return when (channel) {
        UpdateChannel.Stable -> !prerelease && !isNightly
        UpdateChannel.Nightly -> prerelease || isNightly
    }
}

/** Guards against a release cut from some other branch being handed out as an update. */
private fun GitHubReleaseDto.matchesReleaseBranch(): Boolean {
    if (targetCommitish?.trim()?.equals(releaseChannelBranch, ignoreCase = true) == true) {
        return true
    }

    return listOf(tagName, name)
        .filterNotNull()
        .any { value -> value.contains(releaseChannelBranch, ignoreCase = true) }
}

/**
 * Which builds are worth offering, kept pure so both channels' rules stay testable.
 *
 * The two channels cannot share one rule. Stable releases carry version names that increase, so a
 * version comparison answers it. Nightlies do not: every nightly reports the version of the release
 * it was cut from and ships under one unchanging tag, so a version comparison either never fires or
 * — because "Nightly" parses to no version at all — fires forever.
 */
internal object UpdateAvailability {
    fun isOffered(
        update: AppUpdate,
        localVersion: String,
        installedNightlyId: String?,
        runningBuild: PackagedBuild? = null,
    ): Boolean =
        when (update.channel) {
            UpdateChannel.Nightly ->
                !isSelfBuiltNightlyAtLeastAsNew(update, installedNightlyId, runningBuild) &&
                    (update.buildId == null || update.buildId != installedNightlyId)
            // Returning to stable is never a version upgrade — the nightly is ahead of, or level
            // with, whatever stable is offering — so the switch itself is what makes it worth
            // installing. Without this, a nightly user could never get back.
            UpdateChannel.Stable ->
                installedNightlyId != null ||
                    VersionUtils.isRemoteNewer(update.tag, localVersion)
        }

    /** True when the offer moves the user between channels rather than forward within one. */
    fun isChannelSwitch(
        update: AppUpdate,
        installedNightlyId: String?,
        runningBuild: PackagedBuild? = null,
    ): Boolean =
        when (update.channel) {
            // A self-built nightly carries no marker, but it is not off-channel either: it was
            // packaged for this one, so an offer moving it forward is an ordinary nightly update.
            UpdateChannel.Nightly ->
                installedNightlyId == null && runningBuild?.isNightly != true
            UpdateChannel.Stable -> installedNightlyId != null
        }

    /**
     * Whether the running image is a nightly built here that is already level with the offered one.
     *
     * Only a build the updater installed leaves a marker naming its asset; anything else — a local
     * `createReleaseDistributable` copied over the install — leaves none, and the launch-time
     * reconciliation drops any marker it inherited (see [decideNightlyMarker]). With no marker the
     * asset-identity comparison can only ever answer "different", so a self-built nightly was
     * re-offered the published one on every launch however far ahead of it that build was.
     *
     * The build stamp is the only thing that can rank the two, since neither the rolling tag nor
     * the version name moves between nightlies: it records which channel the image was packaged
     * for and when it was packaged. Applied only to the unmarked case — an install the updater
     * performed has its own settled rules, and nothing here should be able to talk it out of an
     * update.
     */
    private fun isSelfBuiltNightlyAtLeastAsNew(
        update: AppUpdate,
        installedNightlyId: String?,
        runningBuild: PackagedBuild?,
    ): Boolean {
        if (installedNightlyId != null) return false
        if (runningBuild?.isNightly != true) return false
        val builtAt = utcInstantOrNull(runningBuild.buildTime) ?: return false
        val publishedAt = utcInstantOrNull(update.publishedAt) ?: return false
        return builtAt >= publishedAt
    }
}

private val utcInstantPattern = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.\d+)?Z$""")

/**
 * The second-precision prefix of a UTC ISO-8601 timestamp, or null when it isn't one.
 *
 * Both sides are minted in UTC — the packaging task stamps `ISO_INSTANT` at `ZoneOffset.UTC`, and
 * GitHub publishes `Z` timestamps — so once the shape is verified the strings sort chronologically
 * on their own. Anything else (an offset, a truncated field, a hand-edited stamp) returns null and
 * the caller keeps the old behaviour rather than inventing an ordering.
 */
internal fun utcInstantOrNull(value: String?): String? =
    value?.trim()?.let { utcInstantPattern.matchEntire(it)?.groupValues?.get(1) }

internal fun selectBestPortableUpdateAsset(assets: List<GitHubAssetDto>): GitHubAssetDto? {
    val updateAssets = assets.filter { asset ->
        asset.name.endsWith(".zip", ignoreCase = true) ||
            asset.contentType.equals("application/zip", ignoreCase = true) ||
            asset.contentType.equals("application/x-zip-compressed", ignoreCase = true)
    }
    if (updateAssets.isEmpty()) return null
    if (updateAssets.size == 1) return updateAssets.first()

    for (fragment in AppUpdaterPlatform.getSupportedAbis()) {
        updateAssets.firstOrNull { asset ->
            asset.name.contains(fragment, ignoreCase = true)
        }?.let { return it }
    }

    return updateAssets.firstOrNull { asset ->
        val name = asset.name.lowercase()
        name.contains("universal") || name.contains("all")
    } ?: updateAssets.first()
}

class AppUpdaterController internal constructor(
    private val scope: CoroutineScope,
) {
    private val _uiState = MutableStateFlow(AppUpdaterUiState())
    val uiState: StateFlow<AppUpdaterUiState> = _uiState.asStateFlow()

    private var autoCheckStarted = false

    fun ensureAutoCheckStarted() {
        if (autoCheckStarted || !AppFeaturePolicy.inAppUpdaterEnabled || !AppUpdaterPlatform.isSupported) {
            return
        }
        autoCheckStarted = true
        checkForUpdates(force = false, showNoUpdateFeedback = false)
    }

    fun checkForUpdates(force: Boolean, showNoUpdateFeedback: Boolean) {
        if (!AppFeaturePolicy.inAppUpdaterEnabled || !AppUpdaterPlatform.isSupported) {
            if (showNoUpdateFeedback) {
                scope.launch {
                    NuvioToastController.show(getString(Res.string.updates_not_available))
                }
            }
            return
        }

        scope.launch {
            _uiState.update { state ->
                state.copy(
                    isChecking = true,
                    errorMessage = null,
                    showUnknownSourcesDialog = false,
                )
            }

            val ignoredTag = AppUpdaterPlatform.getIgnoredTag()
            val installedNightlyId = AppUpdaterPlatform.getInstalledNightlyBuild()?.id
            val runningBuild = AppVersionPolicy.packagedBuild
            val result = AppUpdaterRepository.getLatestChannelUpdate(AppUpdaterPlatform.getUpdateChannel())

            result.onSuccess { update ->
                val remoteNewer = UpdateAvailability.isOffered(
                    update = update,
                    localVersion = AppVersionConfig.DESKTOP_VERSION_NAME,
                    installedNightlyId = installedNightlyId,
                    runningBuild = runningBuild,
                )
                val ignored = ignoredTag != null && ignoredTag == update.ignoreKey()
                val shouldShowDialog = force || (remoteNewer && !ignored)

                _uiState.update { state ->
                    state.copy(
                        isChecking = false,
                        update = update.takeIf { remoteNewer },
                        isUpdateAvailable = remoteNewer,
                        isChannelSwitch = remoteNewer &&
                            UpdateAvailability.isChannelSwitch(update, installedNightlyId, runningBuild),
                        isDownloading = false,
                        downloadProgress = null,
                        downloadedApkPath = state.downloadedApkPath.takeIf { remoteNewer },
                        showDialog = shouldShowDialog,
                        showUnknownSourcesDialog = false,
                        errorMessage = null,
                    )
                }

                if (showNoUpdateFeedback && !remoteNewer) {
                    NuvioToastController.show(getString(Res.string.updates_latest_version))
                }
            }.onFailure { error ->
                _uiState.update { state ->
                    state.copy(
                        isChecking = false,
                        isDownloading = false,
                        downloadProgress = null,
                        downloadedApkPath = null,
                        update = null,
                        isUpdateAvailable = false,
                        isChannelSwitch = false,
                        showDialog = force && error !is NoChannelReleaseException,
                        showUnknownSourcesDialog = false,
                        errorMessage = if (force && error !is NoChannelReleaseException) {
                            error.message ?: getString(Res.string.updates_check_failed)
                        } else {
                            null
                        },
                    )
                }

                if (showNoUpdateFeedback || error is NoChannelReleaseException) {
                    NuvioToastController.show(error.message ?: getString(Res.string.updates_check_failed))
                }
            }
        }
    }

    fun showLatestChangelog() {
        if (!AppFeaturePolicy.inAppUpdaterEnabled || !AppUpdaterPlatform.isSupported) {
            scope.launch {
                NuvioToastController.show(getString(Res.string.updates_not_available))
            }
            return
        }

        scope.launch {
            _uiState.update { state ->
                state.copy(
                    isChecking = true,
                    errorMessage = null,
                    showUnknownSourcesDialog = false,
                )
            }

            val installedNightlyId = AppUpdaterPlatform.getInstalledNightlyBuild()?.id
            val runningBuild = AppVersionPolicy.packagedBuild
            AppUpdaterRepository.getLatestChannelUpdate(AppUpdaterPlatform.getUpdateChannel()).onSuccess { update ->
                val remoteNewer = UpdateAvailability.isOffered(
                    update = update,
                    localVersion = AppVersionConfig.DESKTOP_VERSION_NAME,
                    installedNightlyId = installedNightlyId,
                    runningBuild = runningBuild,
                )
                _uiState.update { state ->
                    state.copy(
                        isChecking = false,
                        update = update,
                        isUpdateAvailable = remoteNewer,
                        isChannelSwitch = remoteNewer &&
                            UpdateAvailability.isChannelSwitch(update, installedNightlyId, runningBuild),
                        isDownloading = false,
                        downloadProgress = null,
                        downloadedApkPath = state.downloadedApkPath.takeIf { remoteNewer },
                        showDialog = true,
                        showUnknownSourcesDialog = false,
                        errorMessage = null,
                    )
                }
            }.onFailure { error ->
                _uiState.update { state ->
                    state.copy(
                        isChecking = false,
                        isDownloading = false,
                        downloadProgress = null,
                        downloadedApkPath = null,
                        update = null,
                        isUpdateAvailable = false,
                        isChannelSwitch = false,
                        showDialog = true,
                        showUnknownSourcesDialog = false,
                        errorMessage = error.message ?: getString(Res.string.updates_check_failed),
                    )
                }
            }
        }
    }

    fun dismissDialog() {
        _uiState.update { state ->
            state.copy(
                showDialog = false,
                showUnknownSourcesDialog = false,
                errorMessage = null,
            )
        }
    }

    fun ignoreThisVersion() {
        val update = _uiState.value.update ?: return
        AppUpdaterPlatform.setIgnoredTag(update.ignoreKey())
        dismissDialog()
    }

    fun downloadUpdate() {
        val update = _uiState.value.update ?: return

        scope.launch {
            _uiState.update { state ->
                state.copy(
                    isDownloading = true,
                    downloadProgress = 0f,
                    errorMessage = null,
                )
            }

            AppUpdaterPlatform.downloadApk(
                assetUrl = update.assetUrl,
                assetName = update.assetName,
                expectedSha256 = update.assetSha256,
            ) { downloadedBytes, totalBytes ->
                val progress = if (totalBytes != null && totalBytes > 0L) {
                    (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                } else {
                    null
                }
                _uiState.update { state -> state.copy(downloadProgress = progress) }
            }.onSuccess { path ->
                _uiState.update { state ->
                    state.copy(
                        isDownloading = false,
                        downloadProgress = 1f,
                        downloadedApkPath = path,
                        errorMessage = null,
                    )
                }
                installDownloadedUpdate()
            }.onFailure { error ->
                _uiState.update { state ->
                    state.copy(
                        isDownloading = false,
                        downloadProgress = null,
                        downloadedApkPath = null,
                        errorMessage = error.message ?: getString(Res.string.updates_download_failed),
                        showDialog = true,
                    )
                }
            }
        }
    }

    fun installDownloadedUpdate() {
        val apkPath = _uiState.value.downloadedApkPath ?: return
        if (!AppUpdaterPlatform.canRequestPackageInstalls()) {
            _uiState.update { state -> state.copy(showUnknownSourcesDialog = true, showDialog = true) }
            return
        }

        val update = _uiState.value.update
        AppUpdaterPlatform.installDownloadedApk(apkPath).onSuccess { outcome ->
            if (update != null) {
                when (outcome) {
                    UpdateInstallOutcome.RESTARTING -> recordInstalledBuild(update)
                    // Nothing is installed yet — the verified archive is merely sitting in Explorer
                    // waiting to be extracted, and we never learn whether it was. Recording it as
                    // installed would strand the user with no further prompts. Stable can simply be
                    // asked again next launch, because its version comparison self-corrects either
                    // way; a nightly cannot, so mark that one ignored: the auto-check stops nagging
                    // about a build already in hand, while a manual check still offers it.
                    UpdateInstallOutcome.REVEALED -> if (update.channel == UpdateChannel.Nightly) {
                        AppUpdaterPlatform.setIgnoredTag(update.ignoreKey())
                    }
                }
            }
            _uiState.update { state -> state.copy(showUnknownSourcesDialog = false) }
            if (outcome == UpdateInstallOutcome.RESTARTING) {
                scope.launch {
                    NuvioToastController.show(getString(Res.string.updates_restarting))
                }
            }
        }.onFailure { error ->
            scope.launch {
                val fallbackMessage = error.message ?: getString(Res.string.updates_install_failed)
                _uiState.update { state ->
                    state.copy(
                        errorMessage = fallbackMessage,
                        showDialog = true,
                    )
                }
            }
        }
    }

    fun resumeInstallation() {
        if (AppUpdaterPlatform.canRequestPackageInstalls()) {
            installDownloadedUpdate()
        } else {
            AppUpdaterPlatform.openUnknownSourcesSettings()
        }
    }

    /**
     * Remembers which build is about to be swapped in, so the next check knows what is running.
     *
     * Written before the app exits (the helper gives us a beat, and the store persists
     * synchronously). Installing a stable build clears the marker, which is what lets a returning
     * nightly user be treated as a stable user again.
     */
    private fun recordInstalledBuild(update: AppUpdate) {
        val nightly = update.buildId
            ?.takeIf { update.channel == UpdateChannel.Nightly }
            ?.let { InstalledNightlyBuild(id = it, publishedAt = update.publishedAt) }
        AppUpdaterPlatform.setInstalledNightlyBuild(nightly)
        AppUpdaterPlatform.setIgnoredTag(null)
    }

    companion object {
        // The app builds exactly one controller, and Settings — which is a long way down the tree
        // from it — needs to re-check the moment the channel changes. Handing the instance down
        // through every settings signature to deliver one call is worse than naming it here.
        private var active: AppUpdaterController? = null

        /** Re-checks against the current channel, e.g. after the user switches to nightly. */
        fun recheckActiveController() {
            active?.checkForUpdates(force = false, showNoUpdateFeedback = false)
        }
    }

    init {
        active = this
    }
}

@Composable
fun rememberAppUpdaterController(): AppUpdaterController {
    val scope = rememberCoroutineScope()
    return remember(scope) { AppUpdaterController(scope) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUpdaterHost(
    controller: AppUpdaterController,
    modifier: Modifier = Modifier,
) {
    if (!AppFeaturePolicy.inAppUpdaterEnabled || !AppUpdaterPlatform.isSupported) {
        return
    }

    val state by controller.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(controller) {
        controller.ensureAutoCheckStarted()
    }

    val visibleOverlay by StartupOverlayCoordinator.visibleOverlay.collectAsStateWithLifecycle()
    LaunchedEffect(state.showDialog) {
        StartupOverlayCoordinator.setWantsToShow(StartupOverlayCoordinator.Overlay.Updater, state.showDialog)
    }

    if (!state.showDialog) return
    // Held back while the setup wizard or the key prompt owns the screen — a fresh install of a
    // stale download is exactly when this would otherwise land on top of the wizard.
    if (visibleOverlay != StartupOverlayCoordinator.Overlay.Updater) return

    val tokens = MaterialTheme.nuvio
    val showPrimaryAction =
        state.showUnknownSourcesDialog || state.isDownloading || state.downloadedApkPath != null || state.isUpdateAvailable

    // Deliberately Dialog rather than BasicAlertDialog, for the same reason NuvioModalDialog is:
    // BasicAlertDialog wraps its content in a Box carrying Material's own sizeIn(maxWidth =
    // DialogMaxWidth = 560.dp). That cap is baked into the composable and is NOT lifted by
    // usePlatformDefaultWidth = false, so no width modifier on the content can escape it - the
    // dialog just grows taller. Going straight to Dialog leaves the width to us.
    Dialog(
        onDismissRequest = {
            if (!state.isDownloading) {
                controller.dismissDialog()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        NuvioDialogSurface(
            modifier = modifier
                // Padding first so it still shrinks on a small window, then the cap, then fill -
                // resolving to min(760dp, available) rather than the whole screen.
                .padding(horizontal = 24.dp, vertical = 24.dp)
                .widthIn(max = 760.dp)
                .fillMaxWidth(),
        ) {
            // Laid out like NuvioModalDialog — header, hairline, body, hairline, action strip —
            // rather than through it, because the action strip here also carries the project and
            // logs links on its left, which the shared shell has no slot for.
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = when {
                            state.showUnknownSourcesDialog -> stringResource(Res.string.updates_title_allow_installs)
                            // A channel switch is not "Update available" — the release title alone
                            // would read as a new version when it can just as well be an older one.
                            state.isChannelSwitch -> stringResource(Res.string.updates_title_switch_build)
                            state.isUpdateAvailable -> state.update?.title ?: stringResource(Res.string.updates_title_available)
                            else -> stringResource(Res.string.updates_title_status)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = tokens.colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when {
                            state.showUnknownSourcesDialog -> stringResource(Res.string.updates_message_allow_installs)
                            state.isDownloading -> stringResource(Res.string.updates_message_downloading)
                            state.downloadedApkPath != null &&
                                state.update?.assetName?.endsWith(".zip", ignoreCase = true) == true ->
                                stringResource(Res.string.updates_message_portable_ready)
                            state.isChannelSwitch -> stringResource(
                                Res.string.updates_message_switch_build,
                                updateChannelLabel(state.update?.channel ?: UpdateChannel.Stable),
                            )
                            state.isUpdateAvailable -> stringResource(Res.string.updates_message_ready)
                            // The box below says "No updates found"; the subtitle says what that
                            // means for the user rather than repeating it.
                            else -> stringResource(Res.string.updates_latest_version)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                    )
                }
                // The running build, where the old sidebar footer had it: version and code, then
                // the channel and packaging time underneath, right-aligned against the title.
                InstalledBuildSummary()
                }

                UpdaterHairline()

                // Takes only the height left after the header and action strip, and scrolls within
                // it: unbounded, a short window pushed the action strip - and the Update button -
                // off the bottom of the dialog.
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    state.errorMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.danger,
                        )
                    }

                    UpdateTargetCard(
                        update = state.update?.takeIf { state.isUpdateAvailable },
                        isChecking = state.isChecking,
                    )

                    state.update?.let { update ->
                        if (state.isDownloading || state.downloadProgress != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LinearProgressIndicator(
                                    progress = { (state.downloadProgress ?: 0f).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    text = if (state.downloadProgress != null) {
                                        stringResource(
                                            Res.string.updates_downloading_progress,
                                            ((state.downloadProgress ?: 0f) * 100).toInt().coerceIn(0, 100),
                                        )
                                    } else {
                                        stringResource(Res.string.updates_preparing_download)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = tokens.colors.textMuted,
                                )
                            }
                        }

                        if (update.notes.isNotBlank()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = stringResource(Res.string.updates_release_notes),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = tokens.colors.textMuted,
                                    fontWeight = FontWeight.Bold,
                                )
                                ReleaseNotesMarkdown(
                                    markdown = update.notes,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        // Grows with the notes instead of always reserving 180dp,
                                        // but stays bounded so the dialog cannot outgrow a short
                                        // window; longer notes still scroll inside the box.
                                        .heightIn(min = 120.dp, max = 260.dp)
                                        .clip(RoundedCornerShape(NuvioTokens.Radius.sm))
                                        .background(tokens.colors.surfaceCard)
                                        .border(
                                            tokens.borders.hairline,
                                            tokens.colors.borderSubtle,
                                            RoundedCornerShape(NuvioTokens.Radius.sm),
                                        )
                                        .padding(14.dp)
                                        .verticalScroll(rememberScrollState()),
                                )
                            }
                        }
                    }
                }

                UpdaterHairline()

                // The action strip doubles as the app's footer: the project link and the logs
                // folder used to sit under the settings sidebar, and this dialog — reached from
                // the same top bar — is where the build being run is already the subject.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Weighted rather than followed by a weighted Spacer: a Row measures weighted
                    // children last, so on a narrow window the links shrink and the buttons keep
                    // their width instead of the last one (Update) being squeezed to nothing.
                    UpdaterFooterLinks(modifier = Modifier.weight(1f).padding(start = 6.dp))
                    if (state.isUpdateAvailable && !state.isDownloading && !state.showUnknownSourcesDialog) {
                        TextButton(onClick = controller::ignoreThisVersion) {
                            Text(stringResource(Res.string.action_ignore))
                        }
                    }
                    TextButton(
                        onClick = controller::dismissDialog,
                        enabled = !state.isDownloading,
                    ) {
                        Text(
                            if (state.isDownloading) {
                                stringResource(Res.string.updates_message_downloading)
                            } else if (showPrimaryAction) {
                                stringResource(Res.string.action_later)
                            } else {
                                stringResource(Res.string.action_close)
                            },
                        )
                    }
                    if (showPrimaryAction) {
                        Button(
                            onClick = {
                                when {
                                    state.showUnknownSourcesDialog -> controller.resumeInstallation()
                                    state.downloadedApkPath != null -> controller.installDownloadedUpdate()
                                    else -> controller.downloadUpdate()
                                }
                            },
                            enabled = if (state.showUnknownSourcesDialog || state.downloadedApkPath != null) {
                                true
                            } else {
                                !state.isChecking && !state.isDownloading && state.isUpdateAvailable
                            },
                        ) {
                            Text(
                                when {
                                    state.showUnknownSourcesDialog -> stringResource(Res.string.action_continue)
                                    state.downloadedApkPath != null &&
                                        state.update?.assetName?.endsWith(".zip", ignoreCase = true) == true ->
                                        stringResource(Res.string.updates_open_download)
                                    state.downloadedApkPath != null -> stringResource(Res.string.action_install)
                                    state.isDownloading -> stringResource(Res.string.updates_message_downloading)
                                    state.isChannelSwitch -> stringResource(Res.string.action_install)
                                    else -> stringResource(Res.string.action_update)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The build on offer — version for a stable release, publish time for a nightly (every nightly
 * shares one tag and one version name, so the time is its name) — and the download size. With
 * nothing on offer the box says so, and the running build is up in the header for comparison.
 */
@Composable
private fun UpdateTargetCard(
    update: AppUpdate?,
    isChecking: Boolean,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NuvioTokens.Radius.sm))
            .background(tokens.colors.surfaceCard)
            .border(tokens.borders.hairline, tokens.colors.borderSubtle, RoundedCornerShape(NuvioTokens.Radius.sm))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (isChecking) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        if (update == null) {
            Text(
                text = stringResource(Res.string.updates_message_no_updates),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
            )
            return@Row
        }
        val published = utcInstantOrNull(update.publishedAt)?.let(::shortUtcLabel)
        val size = update.assetSizeBytes?.let(::formatFileSize)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = when (update.channel) {
                    UpdateChannel.Stable -> stringResource(
                        Res.string.updates_target_version,
                        update.tag.removePrefix("v").removePrefix("V"),
                    )
                    UpdateChannel.Nightly -> listOfNotNull(updateChannelLabel(update.channel), published)
                        .joinToString(" \u2022 ")
                },
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = listOfNotNull(
                // Stable already leads with the version, so the date is the second line there;
                // a nightly has led with it.
                published
                    ?.takeIf { update.channel == UpdateChannel.Stable }
                    ?.let { stringResource(Res.string.updates_target_published, it) },
                size,
            ).joinToString(" \u2022 ")
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
        }
    }
}

/** The running version and build, right-aligned in the dialog header. */
@Composable
private fun InstalledBuildSummary() {
    val tokens = MaterialTheme.nuvio
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = stringResource(
                Res.string.updates_target_version,
                "${AppVersionConfig.DESKTOP_VERSION_NAME} (${AppVersionConfig.DESKTOP_VERSION_CODE})",
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        installedBuildDescription()?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

/**
 * Which build of the version is running: the channel and, when the image is stamped, when it was
 * packaged. Null for a `gradlew run` launch, which has no image to describe.
 *
 * Every nightly reports the version of the release it was cut from, so the version name on its own
 * cannot tell a nightly from the stable build of the same number — which is exactly the question a
 * bug report needs answered. The marker above only knows about nightlies that arrived through the
 * updater; the packaging stamp also covers ones installed by hand.
 */
@Composable
private fun installedBuildDescription(): String? {
    val nightlyBuild = remember { AppUpdaterPlatform.getInstalledNightlyBuild() }
    val packagedBuild = remember { AppVersionPolicy.packagedBuild }
    val channel = when {
        nightlyBuild != null || packagedBuild?.isNightly == true -> updateChannelLabel(UpdateChannel.Nightly)
        packagedBuild != null -> updateChannelLabel(UpdateChannel.Stable)
        else -> return null
    }
    val stamp = packagedBuild?.buildTime?.let { utcInstantOrNull(it) }?.let(::shortUtcLabel)
        ?: packagedBuild?.label
        ?: nightlyBuild?.label
    return listOfNotNull(channel, stamp?.let { "$it UTC" }).joinToString(" \u2022 ")
}

/** "2026-09-16 02:10" out of an ISO instant, or the raw value when it is shorter than that. */
private fun shortUtcLabel(instant: String): String =
    instant.take(16).takeIf { it.length == 16 }?.replace('T', ' ') ?: instant

/**
 * The repository link and the logs folder, in the footer style the settings sidebar used to carry.
 * Labelled "GitHub" rather than by the app's name: in the sidebar the name identified the
 * application, but tucked into a dialog it only ever opens the repo, so it says that.
 */
@Composable
private fun UpdaterFooterLinks(modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Muted rather than accent-coloured: these are footnotes, and a bright accent made them
        // the loudest thing on the panel. The underline still marks them as links.
        Text(
            text = stringResource(Res.string.updates_github_link),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
            fontWeight = FontWeight.Medium,
            textDecoration = TextDecoration.Underline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { runCatching { uriHandler.openUri(NuvioHtpcRepoUrl) } },
        )
        Text(
            text = stringResource(Res.string.compose_about_open_logs_folder),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
            fontWeight = FontWeight.Medium,
            textDecoration = TextDecoration.Underline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { platformOpenLogsDirectory() },
        )
    }
}

@Composable
private fun UpdaterHairline() {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.06f)),
    )
}

private const val NuvioHtpcRepoUrl = "https://github.com/UmbraProjects/NuvioDesktop"

/** Display name for a channel, shared by the update dialog and the settings selector. */
@Composable
internal fun updateChannelLabel(channel: UpdateChannel): String = when (channel) {
    UpdateChannel.Stable -> stringResource(Res.string.settings_updates_channel_stable)
    UpdateChannel.Nightly -> stringResource(Res.string.settings_updates_channel_nightly)
}

private fun formatFileSize(sizeBytes: Long): String {
    if (sizeBytes <= 0L) return "0 ${localizedByteUnit("B")}"
    val units = listOf("B", "KB", "MB", "GB")
    var value = sizeBytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    val roundedValue = if (value >= 10 || unitIndex == 0) {
        value.toInt().toString()
    } else {
        ((value * 10).toInt() / 10.0).toString()
    }
    return "$roundedValue ${localizedByteUnit(units[unitIndex])}"
}
