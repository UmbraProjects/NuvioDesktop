package com.spydermahn.nuvioremote

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.util.concurrent.Executors

/**
 * Poster wallpaper: while the TV plays, the phone's **lock screen** shows the poster (films and TV) or the album cover
 * (music), whole and centred over a blurred copy of itself. When playback stops the user's normal lock-screen picture
 * goes back; with none chosen the lock screen goes back to matching the home screen. The home screen is never touched.
 *
 * Android does not let an app read the current wallpaper without a broad storage permission, so it cannot simply
 * "undo": the user picks their normal picture once (Remote › More › Lock screen) and a copy is kept in the app.
 *
 * Every change runs in order on one background thread; which picture is on the lock screen is kept in [PcStore] so
 * the remote screen and the lock-screen service agree on it.
 */
object PosterWallpaper {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nuvio-remote-wallpaper").apply { isDaemon = true }
    }

    private fun normalFile(context: Context) = File(context.applicationContext.filesDir, "lock_normal.jpg")

    fun hasNormal(context: Context): Boolean = normalFile(context).exists()

    /** Puts [url]'s picture on the lock screen, unless it is already there or the switch is off. */
    fun show(context: Context, url: String) {
        val app = context.applicationContext
        // Now-playing lines come every second or so: one fetch per picture is enough.
        synchronized(this) {
            if (asking == url) return
            asking = url
        }
        worker.execute {
            try {
                showNow(app, url)
            } finally {
                synchronized(this) { if (asking == url) asking = null }
            }
        }
    }

    private var asking: String? = null

    private fun showNow(app: Context, url: String) {
        val store = PcStore(app)
        if (!store.posterWallpaper || store.wallpaperUrl == url || failed == url) return
        val poster = WallpaperRules.sharperUrls(url).firstNotNullOfOrNull(::fetch)
        if (poster == null) {
            failed = url // not asked again for this picture; the next one is tried as usual
            return
        }
        val (width, height) = screenSize(app)
        val picture = compose(poster, width, height)
        val done = runCatching {
            WallpaperManager.getInstance(app).setBitmap(picture, null, true, WallpaperManager.FLAG_LOCK)
        }.isSuccess
        if (done) store.wallpaperUrl = url else failed = url
    }

    /** Touched on [worker] only. */
    private var failed: String? = null

    /** Puts the normal picture back if a poster is showing (also after the switch is turned off). */
    fun restore(context: Context) {
        val app = context.applicationContext
        worker.execute {
            val store = PcStore(app)
            if (store.wallpaperUrl == null) return@execute
            putNormalBack(app)
            store.wallpaperUrl = null
        }
    }

    /** Keeps a screen-sized copy of the picture the user chose as their normal lock screen. */
    fun chooseNormal(context: Context, uri: Uri, onDone: (Boolean) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val (width, height) = screenSize(app)
            val ok = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val sample = WallpaperRules.sampleSize(bounds.outWidth, bounds.outHeight, width, height)
                val bitmap = app.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: error("not a picture")
                normalFile(app).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                // Showing nothing from the TV right now: put the new choice up straight away.
                if (PcStore(app).wallpaperUrl == null) putNormalBack(app)
            }.isSuccess
            onDone(ok)
        }
    }

    fun forgetNormal(context: Context) {
        val app = context.applicationContext
        worker.execute { normalFile(app).delete() }
    }

    private fun putNormalBack(app: Context) {
        val manager = WallpaperManager.getInstance(app)
        val normal = normalFile(app)
        runCatching {
            if (normal.exists()) {
                normal.inputStream().use { manager.setStream(it, null, true, WallpaperManager.FLAG_LOCK) }
            } else {
                // No picture of their own: the lock screen goes back to sharing the home screen's wallpaper.
                manager.clear(WallpaperManager.FLAG_LOCK)
            }
        }
    }

    private fun fetch(url: String): Bitmap? = runCatching { Covers.fetch(url, maxSide = 1600) }.getOrNull()

    /** The poster whole and centred on a blurred, darkened copy of itself: nothing is cropped. */
    private fun compose(poster: Bitmap, width: Int, height: Int): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val smooth = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        // The blur: the poster shrunk to a few dozen pixels, grown a little, then stretched (evenly) to cover the
        // screen; the smoothing between so few pixels smears it. The edges that overflow the screen are cut off.
        val cover = WallpaperRules.cover(poster.width, poster.height, width, height)
        val tiny = Bitmap.createScaledBitmap(poster, maxOf(1, poster.width / 32), maxOf(1, poster.height / 32), true)
        val soft = Bitmap.createScaledBitmap(tiny, tiny.width * 4, tiny.height * 4, true)
        canvas.drawBitmap(soft, null, RectF(cover.left, cover.top, cover.right, cover.bottom), smooth)
        canvas.drawColor(Color.argb(110, 0, 0, 0))

        val fit = WallpaperRules.fit(poster.width, poster.height, width, height)
        val box = RectF(fit.left, fit.top, fit.right, fit.bottom)
        val corner = width * 0.03f
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(box, corner, corner, Path.Direction.CW) })
        canvas.drawBitmap(poster, Rect(0, 0, poster.width, poster.height), box, smooth)
        canvas.restore()
        return out
    }

    @Suppress("DEPRECATION")
    private fun screenSize(app: Context): Pair<Int, Int> {
        val windows = app.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windows.maximumWindowMetrics.bounds
            return minOf(bounds.width(), bounds.height()) to maxOf(bounds.width(), bounds.height())
        }
        val metrics = DisplayMetrics()
        windows.defaultDisplay.getRealMetrics(metrics)
        return minOf(metrics.widthPixels, metrics.heightPixels) to maxOf(metrics.widthPixels, metrics.heightPixels)
    }
}

/** A rectangle in plain floats, so the layout rules can be tested off the phone. */
data class WallpaperBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

/** The sums behind [PosterWallpaper], kept free of Android so they can be unit tested. */
object WallpaperRules {
    /** How much of the screen's width and height the poster may take; the rest shows the blur. */
    const val MAX_WIDTH = 0.82f
    const val MAX_HEIGHT = 0.62f

    /** The poster scaled to fit inside the allowed area, centred on the screen both ways (symmetric margins). */
    fun fit(imageWidth: Int, imageHeight: Int, screenWidth: Int, screenHeight: Int): WallpaperBox {
        val scale = minOf(screenWidth * MAX_WIDTH / imageWidth, screenHeight * MAX_HEIGHT / imageHeight)
        val w = imageWidth * scale
        val h = imageHeight * scale
        val left = (screenWidth - w) / 2f
        val top = (screenHeight - h) / 2f
        return WallpaperBox(left, top, left + w, top + h)
    }

    /** The image scaled to cover the whole screen, centred (for the blurred backdrop). */
    fun cover(imageWidth: Int, imageHeight: Int, screenWidth: Int, screenHeight: Int): WallpaperBox {
        val scale = maxOf(screenWidth.toFloat() / imageWidth, screenHeight.toFloat() / imageHeight)
        val w = imageWidth * scale
        val h = imageHeight * scale
        val left = (screenWidth - w) / 2f
        val top = (screenHeight - h) / 2f
        return WallpaperBox(left, top, left + w, top + h)
    }

    /** Power-of-two shrink that still leaves the picture at least screen-sized. */
    fun sampleSize(imageWidth: Int, imageHeight: Int, screenWidth: Int, screenHeight: Int): Int {
        if (imageWidth <= 0 || imageHeight <= 0) return 1
        var sample = 1
        while (imageWidth / (sample * 2) >= screenWidth && imageHeight / (sample * 2) >= screenHeight) sample *= 2
        return sample
    }

    private val tmdbSize = Regex("""(image\.tmdb\.org/t/p/)(w\d+|original)/""")
    private val googleSize = Regex("""=w\d+-h\d+[^/]*$""")

    /**
     * The URLs to try, sharpest first. The now-playing art is sized for a small card; TMDB posters and YouTube Music
     * covers can be asked for larger, which matters on a phone-sized screen. The original URL is always last.
     */
    fun sharperUrls(url: String): List<String> {
        val sharper = when {
            tmdbSize.containsMatchIn(url) -> tmdbSize.replace(url) { "${it.groupValues[1]}w780/" }
            googleSize.containsMatchIn(url) -> googleSize.replace(url, "=w1200-h1200-l90-rj")
            else -> url
        }
        return listOf(sharper, url).distinct()
    }
}
