package com.remnant.dreams.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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

        // Without the exact-alarm permission there is no alarm to set. Nothing is faked in
        // its place: the journal banner reads the same permission back and says so, and
        // ExactAlarmPermissionReceiver comes back through here the moment it is handed over.
        if (!ExactAlarmPolicy.canSchedule(Build.VERSION.SDK_INT, exactAlarmsPermitted(context))) {
            Log.w(TAG, "Cannot schedule exact alarms -- permission not granted")
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

        // Check if another app has an alarm scheduled at or before ours.
        // getNextAlarmClock() returns the system's next alarm from ANY app, our own included,
        // so who owns it decides this -- not how close it lands to our time.
        val companion = detectCompanionAlarm(context, ourAlarmTime)
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
     * Whether the system currently lets us set an exact alarm.
     *
     * Always true below [ExactAlarmPolicy.FIRST_REVOCABLE_SDK], where there is no permission
     * to withhold -- and where canScheduleExactAlarms() does not exist to be called.
     */
    fun exactAlarmsPermitted(context: Context): Boolean {
        if (!ExactAlarmPolicy.isPermissionRevocable(Build.VERSION.SDK_INT)) return true
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return true
        return alarmManager.canScheduleExactAlarms()
    }

    /**
     * Whether the user has an alarm switched on that is not actually set, because the
     * exact-alarm permission has been taken away. The journal banner asks this so it can say
     * so plainly instead of reporting an alarm that will never ring.
     */
    fun isBlockedByPermission(context: Context): Boolean = ExactAlarmPolicy.shouldWarn(
        alarmEnabled = PrefsManager(context).alarmEnabled,
        sdkInt = Build.VERSION.SDK_INT,
        permissionGranted = exactAlarmsPermitted(context)
    )

    /**
     * The system screen where the exact-alarm permission is handed back, aimed at Remnant's
     * own entry rather than the whole list.
     */
    fun exactAlarmSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    /**
     * The phone's next alarm as the companion logic needs to see it: when it fires, and
     * whether it is Remnant's own alarm looking back at us.
     *
     * [isOurs] is null when the owning app could not be established -- see [nextAlarm].
     */
    data class NextAlarm(val triggerTimeMs: Long, val isOurs: Boolean?)

    /**
     * The next alarm clock set on the phone, whoever set it, or null when nothing is set.
     *
     * The owner comes from the package that created the alarm's show intent, which is the
     * only dependable way to tell our own alarm from the phone's. Comparing trigger times
     * cannot do it: a phone alarm set for the same minute as ours reads as our own, Remnant
     * drops out of companion mode, and both alarms ring over each other.
     *
     * Both reads can come back empty and neither is a fault worth reacting to:
     *
     *  - getShowIntent() carries no nullability annotation either way, so the platform makes
     *    no promise here. An alarm can legitimately be registered with a null show intent
     *    and the framework stores and returns it as such.
     *  - getCreatorPackage() is documented nullable, and devices have been reported handing
     *    back a sound trigger time with no readable creator (LG's clock, and Samsung's on
     *    at least some builds).
     *
     * So a trigger time is never discarded because the attribution failed: the owner stays
     * null and the companion logic falls back to the old time comparison, which is what
     * shipped before this and no worse than it.
     *
     * The package name is only ever compared, never resolved to a label, icon or launch
     * intent -- those need a <queries> declaration from API 30 on, and this does not.
     *
     * Worth knowing: getNextAlarmClock() reports one alarm, the soonest setAlarmClock()
     * alarm from any app in this profile. Samsung's Find My Mobile, Modes and Routines,
     * Reminder and Calendar all schedule alarm clocks of their own, so the alarm we ride
     * along with is not always the one the user thinks of as their alarm. That was already
     * true of the old behaviour and is not something identity fixes.
     */
    fun nextAlarm(context: Context): NextAlarm? {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return null
        val info = try {
            alarmManager.nextAlarmClock
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read the phone's next alarm: ${e.message}")
            null
        } ?: return null

        val owningPackage = try {
            info.showIntent?.creatorPackage
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read the next alarm's owner: ${e.message}")
            null
        }
        return NextAlarm(info.triggerTime, CompanionAlarm.ownsAlarm(owningPackage, context.packageName))
    }

    /**
     * Check if another app's alarm is scheduled at or before ours.
     * If an alarm belonging to anything else fires by the time ours would, the user is
     * already being woken up by something else. Returns true if a companion alarm is
     * detected.
     */
    private fun detectCompanionAlarm(context: Context, ourAlarmTimeMs: Long): Boolean {
        try {
            val next = nextAlarm(context)
            val minutesBefore =
                CompanionAlarm.minutesBefore(next?.triggerTimeMs, ourAlarmTimeMs, next?.isOurs)

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
