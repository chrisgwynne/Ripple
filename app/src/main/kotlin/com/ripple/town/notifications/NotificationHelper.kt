package com.ripple.town.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ripple.town.MainActivity
import com.ripple.town.R

/**
 * Standard, minimal notification plumbing: one channel, a permission check, and a
 * plain single-notification builder. Deliberately does not attempt notification
 * grouping/summary-style bundling beyond what [FollowedResidentNotifier] already
 * caps per check — see that file for the "no storm" bound.
 *
 * Tapping a notification opens [MainActivity] as a plain app-open (launchMode
 * "singleTask", already declared in the manifest) rather than deep-linking to the
 * specific event/resident — see [FollowedResidentNotifier] for why that's scoped
 * down rather than attempted here.
 */
object NotificationHelper {

    const val CHANNEL_ID = "followed_resident_updates"
    private const val CHANNEL_NAME = "Followed resident updates"
    private const val CHANNEL_DESCRIPTION =
        "Notable life events for residents you follow or have favourited."

    /** Idempotent — safe to call on every app start. No-op below API 26 (channels didn't exist). */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = CHANNEL_DESCRIPTION
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * True only if the app is actually allowed to post notifications right now —
     * both the user's in-app opt-in (checked by the caller before invoking this)
     * and, on API 33+, the runtime [Manifest.permission.POST_NOTIFICATIONS] grant.
     * Below API 33 the permission is implicitly granted at install time.
     */
    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Posts one notification for a notable event. [notificationId] should be
     * derived from the event id so repeat delivery (e.g. a retried Worker run)
     * naturally de-dupes/replaces rather than stacking duplicates.
     */
    fun notify(context: Context, notificationId: Int, title: String, text: String) {
        if (!canPostNotifications(context)) return
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        // canPostNotifications() above already covers the API 33+ runtime-permission
        // check; this try/catch is a last-line defensive guard against a race where
        // the permission is revoked between the check and this call (e.g. the user
        // flips it off in system settings mid-Worker-run).
        runCatching { NotificationManagerCompat.from(context).notify(notificationId, notification) }
    }
}
