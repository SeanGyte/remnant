package com.remnant.dreams.notification

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.remnant.dreams.R
import com.remnant.dreams.RemnantApp
import com.remnant.dreams.data.DreamDatabase
import com.remnant.dreams.data.PrefsManager
import com.remnant.dreams.ui.JournalActivity
import java.util.Calendar

class WeeklyRecapWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = DreamDatabase.getInstance(applicationContext)
        val prefs = PrefsManager(applicationContext)

        // Get dreams from the last 7 days
        val weekAgo = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -7)
        }.timeInMillis

        val weekCount = db.dreamDao().getCountSince(weekAgo)
        val totalCount = db.dreamDao().getTotalCount()
        val longestDream = db.dreamDao().getLongestDreamSince(weekAgo)

        if (weekCount == 0 && totalCount == 0) {
            return Result.success()
        }

        val message = buildMessage(weekCount, totalCount, longestDream?.durationSeconds ?: 0, prefs.currentStreak)
        showNotification(message)

        return Result.success()
    }

    private fun buildMessage(weekCount: Int, totalCount: Int, longestSeconds: Int, streak: Int): String {
        val parts = mutableListOf<String>()

        if (weekCount > 0) {
            parts.add("$weekCount dream${if (weekCount != 1) "s" else ""} captured this week.")
            if (longestSeconds > 60) {
                val minutes = longestSeconds / 60
                parts.add("Your longest was ${minutes}m ${longestSeconds % 60}s.")
            }
        } else {
            parts.add("No dreams captured this week. Tomorrow's a new day.")
        }

        parts.add("$totalCount total dream${if (totalCount != 1) "s" else ""} captured.")

        if (streak > 2) {
            parts.add("$streak day streak!")
        }

        return parts.joinToString(" ")
    }

    private fun showNotification(message: String) {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val intent = Intent(applicationContext, JournalActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, RemnantApp.CHANNEL_RECAP)
            .setContentTitle("Your Week in Dreams")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(3001, notification)
    }
}
