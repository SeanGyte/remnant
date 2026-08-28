package com.remnant.dreams.alarm

/**
 * Decides what to do about the exact-alarm permission: whether an alarm can be set at all,
 * whether the user has to be told it is not set, and whether a permission change is worth
 * re-arming for.
 *
 * Pure, because the alternative is three Android-only call sites quietly disagreeing about
 * which API levels the permission even exists on. The scheduler, the journal banner and the
 * permission-change receiver all read the answer from here.
 *
 * The API-level story, which is the part that is easy to get wrong:
 *
 *  - API 29-30 (the app's floor is 29): there is no exact-alarm permission. Exact alarms are
 *    always allowed, so there is nothing to warn about and nothing to ask for.
 *  - API 31-32: SCHEDULE_EXACT_ALARM is granted on install but the user can take it away in
 *    Settings, and taking it away is silent. This is the case that all of this exists for.
 *  - API 33+: the app also holds USE_EXACT_ALARM, which is auto-granted and cannot be
 *    revoked, so canScheduleExactAlarms() answers true and every decision below falls out
 *    the harmless way. The checks are kept rather than version-gated off, because the answer
 *    comes from the system rather than from our reading of the version.
 */
object ExactAlarmPolicy {

    /** First API level with an exact-alarm permission that the user can withhold. */
    const val FIRST_REVOCABLE_SDK = 31

    /**
     * Whether [sdkInt] is a version where the exact-alarm permission exists at all. Below
     * this there is no permission to hold, ask for, or lose.
     */
    fun isPermissionRevocable(sdkInt: Int): Boolean = sdkInt >= FIRST_REVOCABLE_SDK

    /**
     * Whether an exact alarm can actually be set. [permissionGranted] is
     * AlarmManager.canScheduleExactAlarms(), which is only meaningful -- and only callable --
     * from [FIRST_REVOCABLE_SDK] on, so it is ignored below that.
     */
    fun canSchedule(sdkInt: Int, permissionGranted: Boolean): Boolean =
        !isPermissionRevocable(sdkInt) || permissionGranted

    /**
     * Whether the journal has to say the alarm is not set.
     *
     * Only when the user thinks they have one. With the alarm switched off there is nothing
     * being silently dropped and nothing to warn about -- the banner already says it is off.
     */
    fun shouldWarn(alarmEnabled: Boolean, sdkInt: Int, permissionGranted: Boolean): Boolean =
        alarmEnabled && !canSchedule(sdkInt, permissionGranted)

    /**
     * Whether a permission-state-changed broadcast should put the alarm back.
     *
     * The broadcast fires on both edges, so it arrives when the permission is taken away as
     * well as when it is handed back. Re-arming on the losing edge would just fail, so the
     * granted state is checked rather than assumed.
     */
    fun shouldRearm(alarmEnabled: Boolean, sdkInt: Int, permissionGranted: Boolean): Boolean =
        alarmEnabled && canSchedule(sdkInt, permissionGranted)
}
