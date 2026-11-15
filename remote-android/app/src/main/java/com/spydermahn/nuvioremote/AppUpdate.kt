package com.spydermahn.nuvioremote

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A newer build of this app on the NuvioRemote release of the repo it was built from. */
data class RemoteUpdate(val build: Int, val apkUrl: String)

/**
 * The app's own updater. Every build from GitHub carries its run number as its version code, and the workflow puts the
 * same number in the release's title ("Nuvio Remote build 12 (windows-tv-adaptive 1a2b3c4)"). On start (and on return,
 * at most every few hours) the app reads that release and offers the APK when its number is higher. Android's own
 * installer does the install, and asks first; builds signed with one key update in place.
 *
 * Off in a build made on a PC (no repo to ask), and silent when the release has no build number (an older workflow).
 */
object AppUpdate {
    sealed interface State {
        data object Idle : State
        data class Available(val update: RemoteUpdate) : State
        data class Downloading(val update: RemoteUpdate, val percent: Int) : State
        data class Failed(val update: RemoteUpdate, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var lastCheckMs = 0L
    private var dismissedBuild = 0

    /** "build 12" in the release title, and the APK among its files. */
    fun parseRelease(json: String): RemoteUpdate? = runCatching {
        val release = JSONObject(json)
        val build = BUILD.find(release.optString("name"))?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val assets = release.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == APK_NAME }
            ?: return null
        val url = apk.optString("browser_download_url").takeIf { it.startsWith("https://") } ?: return null
        RemoteUpdate(build, url)
    }.getOrNull()

    /** Whether [update] is newer than this build and has not been put off. */
    fun isWanted(update: RemoteUpdate?, installedBuild: Int, dismissed: Int = 0): Boolean =
        update != null && update.build > installedBuild && update.build > dismissed

    suspend fun check(force: Boolean = false) {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        if (!force && lastCheckMs != 0L && now - lastCheckMs < CHECK_EVERY_MS) return
        if (_state.value is State.Downloading) return
        lastCheckMs = now
        val update = withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL("https://api.github.com/repos/$repo/releases/tags/$RELEASE_TAG").openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "NuvioRemote")
                try {
                    if (connection.responseCode != 200) null else parseRelease(connection.inputStream.bufferedReader().readText())
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
        }
        if (isWanted(update, BuildConfig.VERSION_CODE, dismissedBuild)) _state.value = State.Available(update!!)
    }

    fun later() {
        val shown = _state.value
        if (shown is State.Available) dismissedBuild = shown.update.build
        if (shown is State.Failed) dismissedBuild = shown.update.build
        _state.value = State.Idle
    }

    /** Downloads the APK into the app's cache and hands it to Android's installer. */
    suspend fun install(context: Context, update: RemoteUpdate) {
        // Android 8+ asks once whether this app may install apps; without it the installer would just refuse.
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            _state.value = State.Failed(update, "Allow installs from Nuvio Remote, then tap Update again.")
            return
        }
        _state.value = State.Downloading(update, 0)
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val target = File(dir, "NuvioRemote-${update.build}.apk")
                download(update.apkUrl, target) { percent -> _state.update { State.Downloading(update, percent) } }
                target
            }
        }
        file.onSuccess { apk ->
            _state.value = State.Available(update)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            _state.value = State.Failed(update, "The download did not finish. Check the Wi-Fi and try again.")
        }
    }

    private fun download(url: String, target: File, progress: (Int) -> Unit) {
        var next = url
        // GitHub answers the asset URL with a redirect to its file store; follow it by hand (HTTPS only).
        repeat(5) {
            val connection = URL(next).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "NuvioRemote")
            try {
                when (connection.responseCode) {
                    in 300..399 -> {
                        next = connection.getHeaderField("Location")?.takeIf { it.startsWith("https://") }
                            ?: error("redirect without an https location")
                    }
                    200 -> {
                        val total = connection.contentLengthLong
                        var done = 0L
                        var shown = -1
                        connection.inputStream.use { input ->
                            target.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    done += read
                                    val percent = if (total > 0) (done * 100 / total).toInt() else 0
                                    if (percent != shown) {
                                        shown = percent
                                        progress(percent)
                                    }
                                }
                            }
                        }
                        if (total > 0 && done != total) error("short download")
                        return
                    }
                    else -> error("HTTP ${connection.responseCode}")
                }
            } finally {
                connection.disconnect()
            }
        }
        error("too many redirects")
    }

    private val BUILD = Regex("""\bbuild (\d+)\b""")
    private const val RELEASE_TAG = "NuvioRemote"
    private const val APK_NAME = "NuvioRemote.apk"
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
}

/** A card over the app's screens while a newer build is waiting: Update or Later. Nothing shows otherwise. */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by AppUpdate.state.collectAsState()
    LifecycleResumeEffect(Unit) {
        scope.launch { AppUpdate.check() }
        onPauseOrDispose { }
    }
    val update = when (val s = state) {
        is AppUpdate.State.Available -> s.update
        is AppUpdate.State.Downloading -> s.update
        is AppUpdate.State.Failed -> s.update
        AppUpdate.State.Idle -> return
    }
    val g = LocalGlass.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .glass(RoundedCornerShape(18.dp), fill = g.tint, edge = g.tintEdge)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.SystemUpdate, contentDescription = null, tint = g.text, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text("Update available", color = g.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when (val s = state) {
                        is AppUpdate.State.Downloading -> "Downloading build ${update.build}… ${s.percent}%"
                        is AppUpdate.State.Failed -> s.message
                        else -> "Build ${update.build} (this is build ${BuildConfig.VERSION_CODE})"
                    },
                    color = g.textMuted,
                    fontSize = 12.sp,
                )
            }
        }
        if (state !is AppUpdate.State.Downloading) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                GlassTag("Later", onClick = AppUpdate::later)
                GlassTag("Update", accent = true, onClick = { scope.launch { AppUpdate.install(context, update) } })
            }
        }
    }
}
