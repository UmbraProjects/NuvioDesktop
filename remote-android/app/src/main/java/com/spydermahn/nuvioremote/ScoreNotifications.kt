package com.spydermahn.nuvioremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * fork:live-scores - a favourite team's goal, card or whistle from the PC (`SCORE` line) as a phone notification.
 * The PC only sends them while Settings > Live TV > Live scores > Notify the phone remote is on, and only while this
 * app is connected to it. One notification per match, replaced by the next event, so a busy match does not stack.
 */
object ScoreNotifications {
    private const val CHANNEL = "live_scores"

    fun show(context: Context, score: ServerLine.Score) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Live scores", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Your favourite teams' goals, cards and whistles from Nuvio on the PC"
            },
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setContentTitle(score.title)
            .setContentText(score.text)
            .setStyle(Notification.BigTextStyle().bigText(score.text))
            .setCategory(Notification.CATEGORY_EVENT)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(notificationId(score.title), notification)
    }

    /** One id per match: the score line without the event word and the numbers ("Man United – Liverpool"). */
    internal fun notificationId(title: String): Int {
        val match = title.substringAfter(" · ").replace(Regex("[0-9]"), "").replace(Regex("\\s+"), " ").trim()
        return 4_000 + (match.hashCode() and 0x0fff)
    }
}
