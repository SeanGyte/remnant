package com.remnant.dreams.alarm

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.remnant.dreams.R
import com.remnant.dreams.RemnantApp
import com.remnant.dreams.data.PrefsManager

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        // Re-arm first, before anything that can fail. setAlarmClock is one-shot, so
        // if the chain breaks here every future morning silently goes missing.
        AlarmScheduler.rescheduleForTomorrow(context)

        // Post a high-priority notification with full-screen intent.
        // On a locked device, this launches AlarmActivity directly.
        // On an unlocked device, the notification appears (and user taps to open).
        // This is the reliable pattern for alarm apps on Android 10+.

        val alarmIntent = Intent(context, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, 0, alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (!notificationsAllowed(context)) {
            // No notification means no full-screen intent either -- it rides on one. Ring
            // through a foreground service instead, and let JournalActivity pick the user
            // up when they open the app.
            Log.w(TAG, "Notifications not permitted -- ringing the fallback alarm")
            PrefsManager(context).alarmRingingSince = System.currentTimeMillis()
            AlarmRingtoneService.start(context)
            return
        }

        val notification = NotificationCompat.Builder(context, RemnantApp.CHANNEL_ALARM)
            .setContentTitle("Remnant")
            .setContentText("Time to capture your dream")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            // Android suppresses the full-screen intent to a heads-up notification when
            // the device is in use -- without this, tapping it would do nothing.
            .setContentIntent(fullScreenPendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(ALARM_NOTIFICATION_ID, notification)
    }

    private fun notificationsAllowed(context: Context): Boolean {
        // POST_NOTIFICATIONS only exists as a runtime permission from API 33; below that
        // areNotificationsEnabled() is the only meaningful signal.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    companion object {
        private const val TAG = "AlarmReceiver"
        const val ALARM_NOTIFICATION_ID = 1001
    }
}
