package com.remnant.dreams.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.remnant.dreams.data.PrefsManager

/**
 * Puts the alarm back when the exact-alarm permission is handed back.
 *
 * Losing the permission leaves the alarm unset -- AlarmScheduler.schedule() has nothing it
 * can do with it. Getting it back used to change nothing on its own: the user had to open
 * Remnant before anything re-armed, and until they did, an alarm they had just re-permitted
 * still would not ring. The system tells us the moment it changes, so we act on it.
 *
 * The broadcast is API 31+ and is only sent to the app whose permission moved. It fires on
 * both edges, so whether the permission is now held is checked rather than assumed.
 */
class ExactAlarmPermissionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            return
        }

        val permitted = AlarmScheduler.exactAlarmsPermitted(context)
        val rearm = ExactAlarmPolicy.shouldRearm(
            alarmEnabled = PrefsManager(context).alarmEnabled,
            sdkInt = Build.VERSION.SDK_INT,
            permissionGranted = permitted
        )

        Log.d(TAG, "Exact-alarm permission changed -- granted=$permitted, re-arming=$rearm")
        if (rearm) AlarmScheduler.schedule(context)
    }

    private companion object {
        const val TAG = "ExactAlarmPermission"
    }
}
