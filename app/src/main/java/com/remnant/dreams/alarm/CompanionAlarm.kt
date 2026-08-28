package com.remnant.dreams.alarm

import java.util.Locale
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
     * Clock and alarm apps, by package name, that a next-alarm entry can be trusted to mean
     * somebody is being woken up.
     *
     * Needed because "the phone's next alarm" is not the same thing as "the user's wake
     * alarm". getNextAlarmClock() reports the soonest setAlarmClock() alarm from any app,
     * and plenty of non-clock apps set them: Samsung's Modes and Routines quietly adds one
     * at the end of Sleep mode that never appears in the Clock app, and Find My Mobile,
     * Reminder and Calendar have all been reported doing the same. Treating a phone-finder
     * ping as the user's alarm silences Remnant for a wake-up that is never coming.
     *
     * The list is a shortcut, not the whole answer -- [isWakeAlarmPackage] falls back to
     * reading the name. What it is really for is the clock apps the name cannot catch:
     * Sony's alarm lives in com.sonyericsson.organizer, and the two big third-party alarm
     * apps are named after sleeping rather than after clocks. Without those three spelled
     * out here, their users would lose companion mode entirely.
     *
     * Package names verified against vendor listings and the debloat registries that track
     * them (Universal Android Debloater NG, UIBloatwareRegistry) rather than recalled.
     */
    val CLOCK_PACKAGES = setOf(
        // AOSP, and the OEMs that ship it unrenamed (Xiaomi/HyperOS, older Huawei/EMUI)
        "com.android.deskclock",
        "com.android.alarmclock",
        // Google Clock -- also the stock clock on Motorola and Nothing
        "com.google.android.deskclock",
        "com.sec.android.app.clockpackage", // Samsung
        "com.huawei.deskclock", // Huawei
        "com.hihonor.deskclock", // Honor
        "com.coloros.alarmclock", // OPPO / realme (ColorOS)
        "com.oplus.alarmclock", // OPPO, newer builds
        "com.oneplus.deskclock", // OnePlus (OxygenOS)
        "com.android.BBKClock", // vivo / iQOO
        "com.motorola.cn.deskclock", // Motorola, China ROM
        "com.sonyericsson.organizer", // Sony Xperia -- no "clock" or "alarm" in the name
        "com.lge.clock", // LG
        "com.asus.deskclock", // Asus / ZenFone
        "com.transsion.deskclock", // Tecno, Infinix, itel
        "com.zui.deskclock", // Lenovo (ZUI)
        "zte.com.cn.alarmclock", // ZTE
        "cn.nubia.deskclock.preset", // nubia
        "com.htc.android.worldclock", // HTC
        "com.urbandroid.sleep", // Sleep as Android -- named for sleep, not for clocks
        "droom.sleepIfUCan" // Alarmy -- likewise
    )

    /**
     * Packages that read like a clock app by name but are not one, so the [CLOCK_NAME_HINTS]
     * fallback must not let them through.
     *
     * com.qualcomm.qti.poweroffalarm is the one that matters: it is firmware plumbing that
     * lets an alarm start a powered-off phone, it ships on most Qualcomm devices, and it has
     * "alarm" sitting in the middle of its name. com.samsung.android.app.clockpack is a pack
     * of clock faces.
     */
    private val NOT_CLOCK_PACKAGES = setOf(
        "com.qualcomm.qti.poweroffalarm",
        "com.samsung.android.app.clockpack"
    )

    /**
     * What an unknown OEM's clock app almost always has in its package name. Matched
     * case-insensitively, and as a substring so that "deskclock" counts.
     */
    private val CLOCK_NAME_HINTS = listOf("clock", "alarm")

    /**
     * Whether an alarm set by [owningPackage] is the kind of alarm a person wakes up to,
     * and so the kind Remnant should stay quiet for.
     *
     * The allow-list decides first, then the name. The name check exists because the list
     * cannot keep up with every OEM: a clock app nobody here has heard of still ought to put
     * Remnant into companion mode, and getting that wrong means a second alarm going off at
     * someone. An unrecognised package with neither word in it is left alone -- Remnant
     * stays standalone and rings, which is the safe way to be wrong.
     */
    fun isWakeAlarmPackage(owningPackage: String): Boolean {
        if (owningPackage in NOT_CLOCK_PACKAGES) return false
        if (owningPackage in CLOCK_PACKAGES) return true
        val lower = owningPackage.lowercase(Locale.ROOT)
        return CLOCK_NAME_HINTS.any { hint -> hint in lower }
    }

    /**
     * Whether an alarm owned by [owningPackage] is worth putting through the companion
     * decision at all.
     *
     * Three things get through, and the two that are not clock apps are here on purpose:
     *
     *  - Our own alarm, so that [ownsAlarm] can rule it out on identity. Dropping it here
     *    instead would look the same but lose the reason.
     *  - An alarm with no readable owner, so the time-comparison fallback still stands.
     *  - Anything [isWakeAlarmPackage] recognises as a clock.
     *
     * What does not get through is a foreign alarm from an app that has no business waking
     * anybody -- and since getNextAlarmClock() only ever reports one alarm, that leaves
     * nothing to ride along with, which is the correct answer: ring.
     */
    fun isCompanionableAlarm(owningPackage: String?, ourPackage: String): Boolean =
        owningPackage == null ||
            owningPackage == ourPackage ||
            isWakeAlarmPackage(owningPackage)

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
