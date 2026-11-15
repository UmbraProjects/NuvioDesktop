package com.spydermahn.nuvioremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.io.IOException
import java.util.concurrent.Executors

/**
 * The lock-screen and notification controls for what the PC is playing.
 *
 * The remote screen drops its connection when the app goes to the background; this service keeps one of its own to the
 * last PC while something plays there, and shows it as an ordinary media notification: play/pause, previous and next
 * (music), back and forward 10 s, a seek bar, and the phone's volume keys set the PC's volume, all from the lock screen.
 *
 * It is started by the remote screen (Android only lets an app in the foreground start one) and stops itself once
 * nothing has played for a while, the PC has been out of reach for a while, or the notification's close is pressed.
 */
class NowPlayingService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "nuvio-remote-media") }
    private lateinit var store: PcStore
    private lateinit var session: MediaSession
    private lateinit var notifications: NotificationManager

    // Touched on the main thread only.
    private var nowPlaying: NowPlaying? = null
    private var cover: Bitmap? = null
    private var coverUrl: String? = null
    private var volume: VolumeProvider? = null
    private var lastActiveAtMs = SystemClock.elapsedRealtime()
    private var lastReachedAtMs = SystemClock.elapsedRealtime()
    private var foregroundStarted = false
    private var stopped = false

    // Touched on [io] only.
    private var remote: RemoteSession? = null
    private var pc: SavedPc? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = PcStore(this)
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Now playing on the PC", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Controls for what Nuvio is playing, on the lock screen"
                setShowBadge(false)
            },
        )
        session = MediaSession(this, "NuvioRemote").apply {
            setCallback(callback)
            setSessionActivity(openAppIntent())
            isActive = true
        }
        goForeground()
        onIo { connect() }
        main.postDelayed(watchdog, WATCHDOG_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> send { it.key(RemoteKey.PlayPause) }
            ACTION_NEXT -> send { it.key(RemoteKey.Next) }
            ACTION_PREVIOUS -> send { it.key(RemoteKey.Previous) }
            ACTION_BACK -> send { it.key(RemoteKey.SeekBack) }
            ACTION_FORWARD -> send { it.key(RemoteKey.SeekForward) }
            ACTION_CLOSE -> shutDown()
            // A start from the remote screen: the PC may have changed, so connect afresh.
            ACTION_REFRESH -> onIo {
                if (store.last()?.address != pc?.address) {
                    closeRemote()
                    connect()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        // Nothing is watching the TV for the lock screen any more: put the normal picture back.
        PosterWallpaper.restore(this)
        onIo { closeRemote() }
        io.shutdown()
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    // --- The connection (on io) ---

    private fun connect() {
        if (stopped) return
        val target = store.last()
        if (target == null) {
            main.post { shutDown() }
            return
        }
        pc = target
        try {
            val opened = RemoteSession.open(target.host, target.port)
            when (opened.hello(target.token, phoneName(this))) {
                is HelloResult.Welcome -> Unit
                // Not paired any more, or a different Nuvio: the remote screen sorts that out.
                else -> {
                    opened.close()
                    main.post { shutDown() }
                    return
                }
            }
            remote = opened
            opened.listen(
                clock = SystemClock::elapsedRealtime,
                onLine = { line -> main.post { onLine(line) } },
                onClosed = { onIo { if (remote === opened) retryLater() } },
            )
            opened.subscribe()
            main.post { lastReachedAtMs = SystemClock.elapsedRealtime() }
        } catch (_: IOException) {
            retryLater()
        }
    }

    /** [io] once the service is gone refuses work; a late reply or a closing socket must not crash the app. */
    private fun onIo(work: () -> Unit) {
        try {
            io.execute(work)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    private fun retryLater() {
        closeRemote()
        main.post { showOffline() }
        if (!stopped) main.postDelayed({ if (!stopped) onIo { connect() } }, RETRY_MS)
    }

    private fun closeRemote() {
        remote?.close()
        remote = null
    }

    private fun send(action: (RemoteSession) -> Unit) {
        onIo {
            val current = remote ?: return@onIo
            try {
                action(current)
            } catch (_: IOException) {
                retryLater()
            }
        }
    }

    /** Pings, and drops a connection the PC has stopped answering; runs every few seconds. */
    private val watchdog: Runnable = object : Runnable {
        override fun run() {
            if (stopped) return
            val now = SystemClock.elapsedRealtime()
            if (nowPlaying != null) lastActiveAtMs = now
            if (now - lastActiveAtMs > IDLE_STOP_MS || now - lastReachedAtMs > OFFLINE_STOP_MS) {
                shutDown()
                return
            }
            onIo {
                val current = remote ?: return@onIo
                if (current.silentFor(SystemClock.elapsedRealtime()) > SILENT_MS || !current.ping()) retryLater()
            }
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    // --- What the PC says (on main) ---

    private fun onLine(line: ServerLine) {
        if (stopped) return
        lastReachedAtMs = SystemClock.elapsedRealtime()
        when (line) {
            is ServerLine.State -> show(line.nowPlaying)
            ServerLine.Idle -> show(null)
            else -> Unit
        }
    }

    private fun show(playing: NowPlaying?) {
        nowPlaying = playing
        updateWallpaper(playing)
        if (playing == null) {
            session.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0L, 0f).build())
            session.setMetadata(null)
            updateNotification()
            return
        }
        lastActiveAtMs = SystemClock.elapsedRealtime()
        if (playing.imageUrl != coverUrl) {
            coverUrl = playing.imageUrl
            cover = playing.imageUrl?.let(Covers::cached)
            val url = playing.imageUrl
            if (url != null && cover == null) {
                Thread({
                    val bitmap = Covers.load(url)
                    main.post {
                        if (!stopped && coverUrl == url && bitmap != null) {
                            cover = bitmap
                            publish()
                        }
                    }
                }, "nuvio-remote-cover").apply { isDaemon = true }.start()
            }
        }
        publish()
    }

    private fun publish() {
        val playing = nowPlaying ?: return
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, playing.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, playing.subtitle ?: pc?.name ?: "Nuvio")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, if (playing.hasTimeline) playing.durationMs else -1L)
                .apply { cover?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build(),
        )
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_REWIND or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_STOP
        if (playing.hasTimeline) actions = actions or PlaybackState.ACTION_SEEK_TO
        if (playing.canSkip) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (playing.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    playing.positionAt(SystemClock.elapsedRealtime()),
                    if (playing.isPlaying) 1f else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .build(),
        )
        updateVolume(playing)
        updateNotification()
    }

    /**
     * Poster wallpaper (Remote › More › Lock screen): the lock screen shows what plays, and the normal picture goes
     * back a little after playback stops (not straight away, so the gap between episodes or albums does not flicker).
     */
    private fun updateWallpaper(playing: NowPlaying?) {
        if (playing == null) {
            // Idle may be said more than once: the first one starts the wait.
            if (!wallpaperBackPending && store.wallpaperUrl != null) {
                wallpaperBackPending = true
                main.postDelayed(putWallpaperBack, WALLPAPER_BACK_MS)
            }
            return
        }
        if (wallpaperBackPending) {
            wallpaperBackPending = false
            main.removeCallbacks(putWallpaperBack)
        }
        val url = playing.imageUrl ?: return
        if (store.posterWallpaper && store.wallpaperUrl != url) PosterWallpaper.show(this, url)
    }

    private var wallpaperBackPending = false
    private val putWallpaperBack = Runnable {
        wallpaperBackPending = false
        PosterWallpaper.restore(this)
    }

    /** The phone's volume keys set the PC's volume while this session is the one in charge (the lock screen, say). */
    private fun updateVolume(playing: NowPlaying) {
        val level = playing.volume ?: return
        val existing = volume
        if (existing != null && existing.maxVolume == playing.volumeMax) {
            existing.currentVolume = level.coerceIn(0, playing.volumeMax)
            return
        }
        val provider = object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, playing.volumeMax, level.coerceIn(0, playing.volumeMax)) {
            override fun onSetVolumeTo(volume: Int) {
                val target = volume.coerceIn(0, maxVolume)
                currentVolume = target
                send { it.volume(target) }
            }

            override fun onAdjustVolume(direction: Int) {
                if (direction == 0) return
                val target = (currentVolume + direction * VOLUME_STEP).coerceIn(0, maxVolume)
                currentVolume = target
                send { it.volume(target) }
            }
        }
        volume = provider
        session.setPlaybackToRemote(provider)
    }

    private fun showOffline() {
        if (stopped) return
        nowPlaying = null
        session.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_NONE, 0L, 0f).build())
        updateNotification()
    }

    // --- The notification ---

    private fun goForeground() {
        if (foregroundStarted) return
        foregroundStarted = true
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        if (!stopped) notifications.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val playing = nowPlaying
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setContentIntent(openAppIntent())
            .setDeleteIntent(actionIntent(ACTION_CLOSE))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(playing?.isPlaying == true)
        if (playing == null) {
            return builder
                .setContentTitle(pc?.name ?: "Nuvio")
                .setContentText("Nothing playing")
                .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Close", ACTION_CLOSE))
                .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0))
                .build()
        }
        builder.setContentTitle(playing.title).setContentText(playing.subtitle ?: pc?.name).setLargeIcon(cover)
        val playPause = if (playing.isPlaying) {
            action(android.R.drawable.ic_media_pause, "Pause", ACTION_PLAY_PAUSE)
        } else {
            action(android.R.drawable.ic_media_play, "Play", ACTION_PLAY_PAUSE)
        }
        if (playing.canSkip) {
            builder.addAction(action(android.R.drawable.ic_media_previous, "Previous", ACTION_PREVIOUS))
            builder.addAction(playPause)
            builder.addAction(action(android.R.drawable.ic_media_next, "Next", ACTION_NEXT))
        } else {
            builder.addAction(action(android.R.drawable.ic_media_rew, "Back 10 seconds", ACTION_BACK))
            builder.addAction(playPause)
            builder.addAction(action(android.R.drawable.ic_media_ff, "Forward 10 seconds", ACTION_FORWARD))
        }
        builder.addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Close", ACTION_CLOSE))
        return builder
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun action(icon: Int, title: String, action: String) =
        Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), title, actionIntent(action)).build()

    private fun actionIntent(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, NowPlayingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun shutDown() {
        if (stopped) return
        stopped = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- What the lock screen, a headset or a watch asks for ---

    private val callback = object : MediaSession.Callback() {
        override fun onPlay() {
            if (nowPlaying?.isPlaying != true) send { it.key(RemoteKey.PlayPause) }
        }

        override fun onPause() {
            if (nowPlaying?.isPlaying == true) send { it.key(RemoteKey.PlayPause) }
        }

        override fun onSkipToNext() = send { it.key(RemoteKey.Next) }
        override fun onSkipToPrevious() = send { it.key(RemoteKey.Previous) }
        override fun onFastForward() = send { it.key(RemoteKey.SeekForward) }
        override fun onRewind() = send { it.key(RemoteKey.SeekBack) }
        override fun onStop() = shutDown()

        override fun onSeekTo(pos: Long) {
            nowPlaying = nowPlaying?.copy(positionMs = pos, receivedAtMs = SystemClock.elapsedRealtime())
            publish()
            send { it.seek(pos) }
        }
    }

    companion object {
        private const val CHANNEL = "now_playing"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_PLAY_PAUSE = "com.spydermahn.nuvioremote.PLAY_PAUSE"
        private const val ACTION_NEXT = "com.spydermahn.nuvioremote.NEXT"
        private const val ACTION_PREVIOUS = "com.spydermahn.nuvioremote.PREVIOUS"
        private const val ACTION_BACK = "com.spydermahn.nuvioremote.BACK"
        private const val ACTION_FORWARD = "com.spydermahn.nuvioremote.FORWARD"
        private const val ACTION_CLOSE = "com.spydermahn.nuvioremote.CLOSE"
        private const val ACTION_REFRESH = "com.spydermahn.nuvioremote.REFRESH"

        private const val WATCHDOG_MS = 5_000L
        private const val SILENT_MS = 12_000L
        private const val RETRY_MS = 5_000L
        private const val VOLUME_STEP = 5
        /** How long after playback stops the lock screen's normal picture goes back. */
        private const val WALLPAPER_BACK_MS = 20_000L
        /** Nothing has played for this long: gone. A gap between films or albums is shorter. */
        private const val IDLE_STOP_MS = 10 * 60_000L
        /** The PC has been unreachable (asleep, off) for this long: gone. */
        private const val OFFLINE_STOP_MS = 5 * 60_000L

        /** Starts the controls, or tells running ones to check they are on the right PC. Call from the foreground. */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, NowPlayingService::class.java).setAction(ACTION_REFRESH))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NowPlayingService::class.java))
        }
    }
}
