package com.remnant.dreams.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.remnant.dreams.data.PrefsManager
import java.util.Calendar

object AlarmScheduler {

    private const val TAG = "AlarmScheduler"
    private const val REQUEST_CODE = 1001

    /**
     * Guard band for "already passed today". AlarmReceiver reschedules at the exact
     * moment the alarm fires, so today's slot is equal to (or a hair before) now --
     * without the tolerance we would re-arm for right now and fire in a loop.
     */
    private const val PAST_TOLERANCE_MS = 1000L

    fun schedule(context: Context) {
        val prefs = PrefsManager(context)
        if (!prefs.alarmEnabled) return

        val alarmManager = context.getSystemService(AlarmManager::class.java)

        // Check permission on Android 12+
        if (Build.VERSION.SDK_INT >= 31 && !alarmManager.canScheduleExactAlarms()) {
            Log.w(TAG, "Cannot schedule exact alarms -- permission not granted")
            // DEFERRED: no re-arm UX yet. If the user revokes and later restores the
            // exact-alarm permission, nothing prompts them and the alarm stays dead
            // while the UI still says it's set. Needs a settings prompt / re-arm flow.
            return
        }

        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nowMs = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, prefs.alarmHour)
            set(Calendar.MINUTE, prefs.alarmMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= nowMs + PAST_TOLERANCE_MS) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val ourAlarmTime = calendar.timeInMillis

        // Check if another app has an alarm scheduled within 15 min before ours.
        // getNextAlarmClock() returns the system's next alarm from ANY app.
        // If that alarm is from a different package and fires within our window, enable companion mode.
        val companion = detectCompanionAlarm(context, alarmManager, ourAlarmTime)
        prefs.companionMode = companion
        Log.d(TAG, "Companion mode: $companion")

        try {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(ourAlarmTime, pendingIntent),
                pendingIntent
            )
            Log.d(TAG, "Alarm scheduled for ${calendar.time}")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException scheduling alarm: ${e.message}")
        }
    }

    /**
     * Check if another app's alarm is scheduled before ours.
     * If ANY alarm fires before ours, the user is already being woken up by something else.
     * Returns true if a companion alarm is detected.
     */
    private fun detectCompanionAlarm(
        context: Context,
        alarmManager: AlarmManager,
        ourAlarmTimeMs: Long
    ): Boolean {
        try {
            val nextAlarmTime = alarmManager.nextAlarmClock?.triggerTime
            val minutesBefore = CompanionAlarm.minutesBefore(nextAlarmTime, ourAlarmTimeMs)

            if (minutesBefore != null) {
                Log.d(TAG, "Companion alarm detected: ${minutesBefore}m before ours")
                return true
            }

            Log.d(TAG, "No earlier alarm from anything else -- no companion")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check for companion alarm: ${e.message}")
        }
        return false
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        Log.d(TAG, "Alarm cancelled")
    }

    fun rescheduleForTomorrow(context: Context) {
        schedule(context)
    }
}
