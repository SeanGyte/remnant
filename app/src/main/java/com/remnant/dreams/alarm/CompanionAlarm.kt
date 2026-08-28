package com.remnant.dreams.alarm

import kotlin.math.abs

/**
 * Decides whether the phone is already being woken by something else before Remnant's own
 * alarm -- the "companion" case, where Remnant stays quiet and just checks in afterwards
 * instead of ringing a second alarm at the user.
 *
 * Deliberately pure: the AlarmManager lookup stays at the call sites so the decision itself
 * is unit-testable. Three screens ask this same question (the scheduler, the journal banner
 * and the onboarding time step) and they must all answer it identically. The caller reads
 * the next alarm's owner as well as its time -- see AlarmScheduler.nextAlarm -- because who
 * set the alarm, not when it lands, is what tells our alarm from the phone's.
 */
object CompanionAlarm {

    /**
     * How close another alarm has to be to ours before we treat the two as the same alarm.
     *
     * Only used when the alarm's owner cannot be established. It is a poor stand-in for
     * knowing who set the alarm: a phone alarm set for the same minute as ours reads as our
     * own, Remnant drops out of companion mode, and the user gets two alarms going off at
     * once. Prefer the owner -- see [ownsAlarm].
     */
    const val SAME_ALARM_TOLERANCE_MS = 1000L

    /**
     * How long after the phone's own alarm Remnant offers to check in. Long enough that the
     * user has silenced their alarm and surfaced, short enough that the dream hasn't gone.
     */
    const val CHECK_IN_DELAY_MINUTES = 5

    /**
     * How far ahead of the phone's own alarm Remnant recommends waking the user.
     *
     * This is the recommended setup rather than the quiet one. A dream goes within a minute
     * or two of getting up, so an ask that comes after the household alarm arrives in the
     * middle of the rush and catches nothing. Half an hour ahead lands while the dream is
     * still there and still leaves the usual alarm to run as it always has -- but it does
     * mean Remnant rings a tone of its own, because at that point it is the first alarm of
     * the morning rather than a companion to one. The copy offering it has to say so.
     */
    const val RECOMMENDED_LEAD_MINUTES = 30L

    /**
     * How far behind the phone's alarm Remnant's check-in has to fall before the gap is
     * worth raising during setup. Inside this the user is still surfacing and the dream is
     * still there; well outside it -- an alarm at 6:00 and a check-in at 7:02 -- the
     * question arrives long after they got up, which is the complaint this guards.
     */
    const val STALE_CHECK_IN_MINUTES = 15L

    private const val MS_PER_MINUTE = 60_000L

    /**
     * Whether the alarm owned by [owningPackage] is Remnant's own, or null when the owner
     * could not be read and the caller has to fall back to comparing times.
     *
     * The owner is the package behind the alarm's show intent. It is the only thing that
     * actually separates our alarm from the phone's: the times can be identical.
     */
    fun ownsAlarm(owningPackage: String?, ourPackage: String): Boolean? =
        if (owningPackage == null) null else owningPackage == ourPackage

    /**
     * Minutes that the phone's next alarm lands before [ourAlarmMs], or null when there is
     * no earlier alarm to ride along with: no alarm at all, our own alarm looking back at
     * us, or one that fires after ours.
     *
     * [nextAlarmIsOurs] comes from [ownsAlarm]. When it is known, it settles the question of
     * whose alarm this is on its own -- an alarm belonging to another app counts as one to
     * ride along with even when it is set for the same second as ours, which is the case
     * that used to leave two alarms ringing over each other. Null means the owner could not
     * be read, and only then does the time tolerance stand in for it.
     */
    fun minutesBefore(nextAlarmMs: Long?, ourAlarmMs: Long, nextAlarmIsOurs: Boolean? = null): Long? {
        if (nextAlarmMs == null) return null
        if (nextAlarmIsOurs == true) return null
        if (nextAlarmIsOurs == null && abs(nextAlarmMs - ourAlarmMs) < SAME_ALARM_TOLERANCE_MS) return null
        if (nextAlarmMs > ourAlarmMs) return null
        return (ourAlarmMs - nextAlarmMs) / MS_PER_MINUTE
    }

    /** Whether [nextAlarmMs] means Remnant should run as a companion rather than ring. */
    fun isCompanion(nextAlarmMs: Long?, ourAlarmMs: Long, nextAlarmIsOurs: Boolean? = null): Boolean =
        minutesBefore(nextAlarmMs, ourAlarmMs, nextAlarmIsOurs) != null

    /**
     * Whether Remnant's check-in trails the phone's alarm by long enough that the user
     * should be told before they finish setting up, rather than reading about it in the
     * journal banner afterwards.
     */
    fun isCheckInStale(nextAlarmMs: Long?, ourAlarmMs: Long, nextAlarmIsOurs: Boolean? = null): Boolean {
        val gap = minutesBefore(nextAlarmMs, ourAlarmMs, nextAlarmIsOurs) ?: return false
        return gap > STALE_CHECK_IN_MINUTES
    }

    /**
     * The moment Remnant would check in if it followed the phone's alarm at [nextAlarmMs]
     * rather than the time the user picked. The quiet option: the phone still does the
     * waking and Remnant asks just afterwards without a tone of its own.
     */
    fun checkInTimeAfter(nextAlarmMs: Long): Long =
        nextAlarmMs + CHECK_IN_DELAY_MINUTES * MS_PER_MINUTE

    /**
     * The recommended time to be woken, [RECOMMENDED_LEAD_MINUTES] ahead of the phone's own
     * alarm at [nextAlarmMs]. Remnant is the first alarm of the morning at this point, so it
     * rings rather than checking in quietly -- see [RECOMMENDED_LEAD_MINUTES].
     */
    fun recommendedWakeBefore(nextAlarmMs: Long): Long =
        nextAlarmMs - RECOMMENDED_LEAD_MINUTES * MS_PER_MINUTE
}
