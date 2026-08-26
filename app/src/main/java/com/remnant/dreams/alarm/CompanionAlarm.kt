package com.remnant.dreams.alarm

import kotlin.math.abs

/**
 * Decides whether the phone is already being woken by something else before Remnant's own
 * alarm -- the "companion" case, where Remnant stays quiet and just checks in afterwards
 * instead of ringing a second alarm at the user.
 *
 * Deliberately pure: the AlarmManager lookup stays at the call sites so the decision itself
 * is unit-testable. Three screens ask this same question (the scheduler, the journal banner
 * and the onboarding time step) and they must all answer it identically.
 */
object CompanionAlarm {

    /**
     * How close another alarm has to be to ours before we treat the two as the same alarm.
     * getNextAlarmClock() reports Remnant's own alarm alongside everyone else's, so a match
     * this tight is almost always us looking at ourselves.
     */
    const val SAME_ALARM_TOLERANCE_MS = 1000L

    /**
     * How long after the phone's own alarm Remnant offers to check in. Long enough that the
     * user has silenced their alarm and surfaced, short enough that the dream hasn't gone.
     */
    const val CHECK_IN_DELAY_MINUTES = 5

    /**
     * How far behind the phone's alarm Remnant's check-in has to fall before the gap is
     * worth raising during setup. Inside this the user is still surfacing and the dream is
     * still there; well outside it -- an alarm at 6:00 and a check-in at 7:02 -- the
     * question arrives long after they got up, which is the complaint this guards.
     */
    const val STALE_CHECK_IN_MINUTES = 15L

    private const val MS_PER_MINUTE = 60_000L

    /**
     * Minutes that the phone's next alarm lands before [ourAlarmMs], or null when there is
     * no earlier alarm to ride along with: no alarm at all, our own alarm looking back at
     * us, or one that fires after ours.
     */
    fun minutesBefore(nextAlarmMs: Long?, ourAlarmMs: Long): Long? {
        if (nextAlarmMs == null) return null
        if (abs(nextAlarmMs - ourAlarmMs) < SAME_ALARM_TOLERANCE_MS) return null
        if (nextAlarmMs >= ourAlarmMs) return null
        return (ourAlarmMs - nextAlarmMs) / MS_PER_MINUTE
    }

    /** Whether [nextAlarmMs] means Remnant should run as a companion rather than ring. */
    fun isCompanion(nextAlarmMs: Long?, ourAlarmMs: Long): Boolean =
        minutesBefore(nextAlarmMs, ourAlarmMs) != null

    /**
     * Whether Remnant's check-in trails the phone's alarm by long enough that the user
     * should be told before they finish setting up, rather than reading about it in the
     * journal banner afterwards.
     */
    fun isCheckInStale(nextAlarmMs: Long?, ourAlarmMs: Long): Boolean {
        val gap = minutesBefore(nextAlarmMs, ourAlarmMs) ?: return false
        return gap > STALE_CHECK_IN_MINUTES
    }

    /**
     * The moment Remnant would check in if it followed the phone's alarm at [nextAlarmMs]
     * rather than the time the user picked.
     */
    fun checkInTimeAfter(nextAlarmMs: Long): Long =
        nextAlarmMs + CHECK_IN_DELAY_MINUTES * MS_PER_MINUTE
}
