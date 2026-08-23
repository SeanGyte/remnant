package com.remnant.dreams

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.remnant.dreams.billing.BillingManager
import com.remnant.dreams.data.DreamDatabase
import com.remnant.dreams.notification.WeeklyRecapWorker
import com.remnant.dreams.worker.AudioCleanupWorker
import com.remnant.dreams.worker.AudioCompressionWorker
import java.util.Calendar
import java.util.concurrent.TimeUnit

class RemnantApp : Application() {

    val database: DreamDatabase by lazy { DreamDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        scheduleWeeklyRecap()
        scheduleDailyAudioMaintenance()
        restoreProEntitlement()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALARM,
                "Dream Capture",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Active dream capture alarm"
                setSound(null, null)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RECAP,
                "Weekly Recap",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Your weekly dream summary"
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CAPTURE_STATUS,
                "Capture Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Recording status while capturing dreams"
            }
        )
    }

    private fun scheduleWeeklyRecap() {
        val now = Calendar.getInstance()
        val sunday = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
            set(Calendar.HOUR_OF_DAY, 19)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            if (before(now)) add(Calendar.WEEK_OF_YEAR, 1)
        }

        val initialDelay = sunday.timeInMillis - now.timeInMillis

        val workRequest = PeriodicWorkRequestBuilder<WeeklyRecapWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "weekly_recap",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    private fun scheduleDailyAudioMaintenance() {
        val wm = WorkManager.getInstance(this)

        // Audio compression: re-encode old recordings to save space
        val compressionWork = PeriodicWorkRequestBuilder<AudioCompressionWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(1, TimeUnit.HOURS) // Don't run immediately on first install
            .build()

        wm.enqueueUniquePeriodicWork(
            "audio_compression",
            ExistingPeriodicWorkPolicy.KEEP,
            compressionWork
        )

        // Audio cleanup: delete recordings past retention period
        val cleanupWork = PeriodicWorkRequestBuilder<AudioCleanupWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(2, TimeUnit.HOURS) // Run after compression has had a chance
            .build()

        wm.enqueueUniquePeriodicWork(
            "audio_cleanup",
            ExistingPeriodicWorkPolicy.KEEP,
            cleanupWork
        )
    }

    /**
     * Connect to Google Play Billing and restore the Pro entitlement (queryPurchases).
     * Offline-first: if Play is unreachable the locally cached entitlement stands.
     */
    private fun restoreProEntitlement() {
        BillingManager.getInstance(this).startConnection()
    }

    companion object {
        const val CHANNEL_ALARM = "alarm_channel"
        const val CHANNEL_RECAP = "recap_channel"
        const val CHANNEL_CAPTURE_STATUS = "capture_status_channel"
    }
}
