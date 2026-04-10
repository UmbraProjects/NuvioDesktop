package com.nuvio.app.features.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle

internal object PlayerPictureInPictureManager {
    private data class SessionState(
        val isActive: Boolean = false,
        val isPlaying: Boolean = false,
        val playerSize: IntSize = IntSize.Zero,
    )

    private var sessionState = SessionState()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wasInPictureInPictureMode = false
    private var pendingPictureInPictureExitCheck: Runnable? = null
    private var pausePlaybackCallback: (() -> Unit)? = null

    fun updateSession(
        activity: Activity,
        isActive: Boolean,
        isPlaying: Boolean,
        playerSize: IntSize,
    ) {
        sessionState = SessionState(
            isActive = isActive,
            isPlaying = isPlaying,
            playerSize = playerSize,
        )
        applyPictureInPictureParams(activity)
    }

    fun clearSession(activity: Activity) {
        sessionState = SessionState()
        wasInPictureInPictureMode = false
        clearPendingPictureInPictureExitCheck()
        applyPictureInPictureParams(activity)
    }

    fun registerPausePlaybackCallback(callback: (() -> Unit)?) {
        pausePlaybackCallback = callback
        if (callback == null) {
            clearPendingPictureInPictureExitCheck()
        }
    }

    fun onUserLeaveHint(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return false
        }
        return enterIfEligible(activity)
    }

    fun onPictureInPictureModeChanged(
        activity: ComponentActivity,
        isInPictureInPictureMode: Boolean,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val wasInPictureInPicture = wasInPictureInPictureMode
        wasInPictureInPictureMode = isInPictureInPictureMode
        clearPendingPictureInPictureExitCheck()

        if (!wasInPictureInPicture || isInPictureInPictureMode) return

        val exitCheck = Runnable {
            val returnedToForeground = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (!returnedToForeground || activity.isFinishing || activity.isDestroyed) {
                pausePlaybackCallback?.invoke()
            }
        }
        pendingPictureInPictureExitCheck = exitCheck
        mainHandler.postDelayed(exitCheck, 250L)
    }

    private fun applyPictureInPictureParams(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        activity.setPictureInPictureParams(buildParams())
    }

    private fun enterIfEligible(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!sessionState.isActive || !sessionState.isPlaying) return false
        if (activity.isFinishing) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode) return false
        return activity.enterPictureInPictureMode(buildParams())
    }

    private fun buildParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        buildAspectRatio(sessionState.playerSize)?.let(builder::setAspectRatio)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(sessionState.isActive && sessionState.isPlaying)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun buildAspectRatio(playerSize: IntSize): Rational? {
        if (playerSize.width <= 0 || playerSize.height <= 0) return null

        val width = playerSize.width.coerceAtLeast(1)
        val height = playerSize.height.coerceAtLeast(1)
        val ratio = width.toDouble() / height.toDouble()

        return when {
            ratio > MaxPictureInPictureAspectRatio -> Rational(239, 100)
            ratio < MinPictureInPictureAspectRatio -> Rational(100, 239)
            else -> Rational(width, height)
        }
    }

    private fun clearPendingPictureInPictureExitCheck() {
        pendingPictureInPictureExitCheck?.let(mainHandler::removeCallbacks)
        pendingPictureInPictureExitCheck = null
    }
}

private const val MaxPictureInPictureAspectRatio = 2.39
private const val MinPictureInPictureAspectRatio = 1.0 / MaxPictureInPictureAspectRatio