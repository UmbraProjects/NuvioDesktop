package com.spydermahn.nuvioremote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.URL

enum class PadMode { Touchpad, DPad }

/** How bright the phone is in dark mode (the window's own brightness, 0 to 1). */
enum class DarkLevel(val label: String, val brightness: Float) {
    Low("Low", 0.05f),
    Medium("Medium", 0.15f),
    High("High", 0.35f),
    ;

    fun next(): DarkLevel = entries[(ordinal + 1) % entries.size]
}

data class SavedPc(val name: String, val host: String, val port: Int, val token: String) {
    val address get() = "$host:$port"
}

/**
 * The paired PCs and which one was used last, shared by the remote screen, the lock-screen controls and the share
 * menu. Stored as "host|port|token|name" per PC.
 */
class PcStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("nuvio_remote", Context.MODE_PRIVATE)

    init {
        // The demo TV lives in the app: once it has been paired, it is started wherever the paired PCs are read (the
        // remote, the lock-screen controls, Send to Nuvio), so it is there to answer like a real PC.
        if (saved().any(DemoTv::isDemo)) DemoTv.start()
    }

    fun saved(): List<SavedPc> = prefs.getStringSet(KEY_SAVED, emptySet()).orEmpty().mapNotNull { entry ->
        val parts = entry.split('|', limit = 4)
        if (parts.size < 4) return@mapNotNull null
        SavedPc(RemoteProtocol.decode(parts[3]), parts[0], parts[1].toIntOrNull() ?: return@mapNotNull null, parts[2])
    }.sortedBy { it.name.lowercase() }

    fun saveAll(list: List<SavedPc>) {
        prefs.edit().putStringSet(KEY_SAVED, list.map { "${it.host}|${it.port}|${it.token}|${RemoteProtocol.encode(it.name)}" }.toSet()).apply()
    }

    var lastAddress: String?
        get() = prefs.getString(KEY_LAST, null)
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY_LAST) else putString(KEY_LAST, value) }.apply()
        }

    /** The theme a PC last sent, so the app opens in that TV's colours before it has connected. */
    fun theme(address: String): RemoteTheme? = RemoteTheme.decode(prefs.getString("$KEY_THEME$address", null))

    fun saveTheme(address: String, theme: RemoteTheme) {
        prefs.edit().putString("$KEY_THEME$address", theme.encode()).apply()
    }

    /** Touchpad (the default) or the D-pad: whichever was used last. */
    var padMode: PadMode
        get() = runCatching { PadMode.valueOf(prefs.getString(KEY_PAD, null) ?: "") }.getOrDefault(PadMode.Touchpad)
        set(value) {
            prefs.edit().putString(KEY_PAD, value.name).apply()
        }

    /** A PC's network cards and broadcast addresses (from its WAKE line), to wake it later. */
    fun wake(address: String): ServerLine.Wake? {
        val saved = prefs.getString("$KEY_WAKE$address", null) ?: return null
        val macs = saved.substringBefore('|').split(',').filter { it.isNotBlank() }
        if (macs.isEmpty()) return null
        return ServerLine.Wake(macs, saved.substringAfter('|', "").split(',').filter { it.isNotBlank() })
    }

    fun saveWake(address: String, wake: ServerLine.Wake) {
        if (wake.macs.isEmpty()) return
        prefs.edit().putString("$KEY_WAKE$address", wake.macs.joinToString(",") + "|" + wake.broadcasts.joinToString(",")).apply()
    }

    /** Dark mode's brightness, Medium until changed. */
    var darkLevel: DarkLevel
        get() = runCatching { DarkLevel.valueOf(prefs.getString(KEY_DARK, null) ?: "") }.getOrDefault(DarkLevel.Medium)
        set(value) {
            prefs.edit().putString(KEY_DARK, value.name).apply()
        }

    /** Poster wallpaper: the lock screen shows what the TV plays (off until turned on). */
    var posterWallpaper: Boolean
        get() = prefs.getBoolean(KEY_POSTER_WALLPAPER, false)
        set(value) {
            prefs.edit().putBoolean(KEY_POSTER_WALLPAPER, value).apply()
        }

    /** The art now on the lock screen from the TV, or null when the user's own picture is there. */
    var wallpaperUrl: String?
        get() = prefs.getString(KEY_WALLPAPER_URL, null)
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY_WALLPAPER_URL) else putString(KEY_WALLPAPER_URL, value) }.commit()
        }

    /** The PC the remote was last connected to, if it is still paired. */
    fun last(): SavedPc? = lastAddress?.let { address -> saved().firstOrNull { it.address == address } }

    private companion object {
        const val KEY_SAVED = "saved_pcs"
        const val KEY_LAST = "last_pc"
        const val KEY_THEME = "theme_"
        const val KEY_PAD = "pad_mode"
        const val KEY_DARK = "dark_level"
        const val KEY_WAKE = "wake_"
        const val KEY_POSTER_WALLPAPER = "poster_wallpaper"
        const val KEY_WALLPAPER_URL = "wallpaper_url"
    }
}

fun phoneName(context: Context): String =
    Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
        ?: Build.MODEL ?: "Android phone"

/**
 * Cover art for the now-playing card and the lock screen: fetched on the caller's thread, scaled down, and the last few
 * kept so a pause or a seek never fetches again.
 */
object Covers {
    private const val MAX_SIDE = 512
    private val kept = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 4
    }

    @Synchronized
    fun cached(url: String): Bitmap? = kept[url]

    /** Blocking; call off the main thread. Null when there is no picture to be had. */
    fun load(url: String): Bitmap? {
        cached(url)?.let { return it }
        val bitmap = fetch(url, MAX_SIDE) ?: return null
        synchronized(this) { kept[url] = bitmap }
        return bitmap
    }

    /** Blocking and not kept: [url]'s picture with its shorter side brought down to about [maxSide]. */
    fun fetch(url: String, maxSide: Int): Bitmap? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val bytes = runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 6_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = true
            try {
                if (connection.responseCode !in 200..299) null else connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }.getOrNull() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide && bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
