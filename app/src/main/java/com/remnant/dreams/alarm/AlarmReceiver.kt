package com.remnant.dreams.alarm

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.remnant.dreams.R
import com.remnant.dreams.RemnantApp

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
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

        val notification = NotificationCompat.Builder(context, RemnantApp.CHANNEL_ALARM)
            .setContentTitle("Remnant")
            .setContentText("Time to capture your dream")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(ALARM_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notification permission not granted -- try direct activity launch as fallback
            context.startActivity(alarmIntent)
        }
    }

    companion object {
        const val ALARM_NOTIFICATION_ID = 1001
    }
}
