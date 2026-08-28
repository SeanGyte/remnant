package com.remnant.dreams.alarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exact-alarm permission is only revocable on a narrow band of Android versions, and
 * getting the band wrong is invisible until an alarm does not ring. Below API 31 there is no
 * permission at all, so a false reading there would put a warning on the journal of a phone
 * with nothing wrong with it; from API 31 up, missing the revoked case is the bug this was
 * written for -- an alarm the UI claimed was set and that never went off.
 */
class ExactAlarmPolicyTest {

    private val apiLegacy = 29 // the app's floor -- no exact-alarm permission exists
    private val apiRevocable = 31 // granted on install, user can take it away
    private val apiAutoGranted = 33 // USE_EXACT_ALARM, granted and not revocable

    // --- is there a permission to lose? ---

    @Test
    fun `the permission does not exist below API 31`() {
        assertFalse(ExactAlarmPolicy.isPermissionRevocable(apiLegacy))
        assertFalse(ExactAlarmPolicy.isPermissionRevocable(30))
    }

    @Test
    fun `the permission exists from API 31 on`() {
        assertTrue(ExactAlarmPolicy.isPermissionRevocable(apiRevocable))
        assertTrue(ExactAlarmPolicy.isPermissionRevocable(32))
        assertTrue(ExactAlarmPolicy.isPermissionRevocable(apiAutoGranted))
    }

    // --- can the alarm be set? ---

    @Test
    fun `old versions can always schedule`() {
        // canScheduleExactAlarms() does not exist to be called here, so whatever is passed
        // for it must not change the answer.
        assertTrue(ExactAlarmPolicy.canSchedule(apiLegacy, permissionGranted = true))
        assertTrue(ExactAlarmPolicy.canSchedule(apiLegacy, permissionGranted = false))
    }

    @Test
    fun `a granted permission can schedule`() {
        assertTrue(ExactAlarmPolicy.canSchedule(apiRevocable, permissionGranted = true))
        assertTrue(ExactAlarmPolicy.canSchedule(apiAutoGranted, permissionGranted = true))
    }

    @Test
    fun `a revoked permission cannot schedule`() {
        assertFalse(ExactAlarmPolicy.canSchedule(apiRevocable, permissionGranted = false))
        assertFalse(ExactAlarmPolicy.canSchedule(32, permissionGranted = false))
    }

    // --- does the journal have to say something? ---

    @Test
    fun `a revoked permission with the alarm on is worth warning about`() {
        // The reported hole: schedule() returned quietly and the banner kept reporting a
        // time. This is the case that has to reach the user.
        assertTrue(
            ExactAlarmPolicy.shouldWarn(
                alarmEnabled = true,
                sdkInt = apiRevocable,
                permissionGranted = false
            )
        )
    }

    @Test
    fun `nothing is warned about while the alarm is off`() {
        // The banner already says the alarm is off. Nothing is being silently dropped.
        assertFalse(
            ExactAlarmPolicy.shouldWarn(
                alarmEnabled = false,
                sdkInt = apiRevocable,
                permissionGranted = false
            )
        )
    }

    @Test
    fun `an old phone is never warned about`() {
        assertFalse(
            ExactAlarmPolicy.shouldWarn(
                alarmEnabled = true,
                sdkInt = apiLegacy,
                permissionGranted = false
            )
        )
    }

    @Test
    fun `a working alarm is not warned about`() {
        assertFalse(
            ExactAlarmPolicy.shouldWarn(
                alarmEnabled = true,
                sdkInt = apiAutoGranted,
                permissionGranted = true
            )
        )
    }

    // --- is a permission change worth re-arming for? ---

    @Test
    fun `getting the permission back re-arms the alarm`() {
        assertTrue(
            ExactAlarmPolicy.shouldRearm(
                alarmEnabled = true,
                sdkInt = apiRevocable,
                permissionGranted = true
            )
        )
    }

    @Test
    fun `losing the permission does not re-arm`() {
        // The broadcast fires on both edges. Re-arming on the losing one would only fail.
        assertFalse(
            ExactAlarmPolicy.shouldRearm(
                alarmEnabled = true,
                sdkInt = apiRevocable,
                permissionGranted = false
            )
        )
    }

    @Test
    fun `a permission change with the alarm off re-arms nothing`() {
        assertFalse(
            ExactAlarmPolicy.shouldRearm(
                alarmEnabled = false,
                sdkInt = apiRevocable,
                permissionGranted = true
            )
        )
    }

    @Test
    fun `warning and re-arming are never both true`() {
        // They are the two halves of the same question: the banner explains the alarm that
        // could not be set, the receiver puts back the one that can be. Both firing would
        // mean warning about an alarm we had just armed.
        for (sdk in listOf(apiLegacy, 30, apiRevocable, 32, apiAutoGranted)) {
            for (granted in listOf(true, false)) {
                assertFalse(
                    "sdk=$sdk granted=$granted",
                    ExactAlarmPolicy.shouldWarn(true, sdk, granted) &&
                        ExactAlarmPolicy.shouldRearm(true, sdk, granted)
                )
            }
        }
    }
}
