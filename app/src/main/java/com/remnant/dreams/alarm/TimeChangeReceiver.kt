package com.remnant.dreams.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The alarm is stored as an absolute epoch computed from the wall clock at schedule
 * time, so a timezone change or a clock correction leaves it firing at the wrong
 * local time. Recompute it whenever the system clock moves.
 */
class TimeChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_TIME_CHANGED ||
            intent?.action == Intent.ACTION_TIMEZONE_CHANGED
        ) {
            AlarmScheduler.schedule(context)
        }
    }
}
