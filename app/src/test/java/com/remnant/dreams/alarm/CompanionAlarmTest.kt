package com.remnant.dreams.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Three screens ask whether the phone is already waking the user before Remnant does -- the
 * scheduler, the journal banner and the onboarding time step -- and they have to agree.
 * The awkward cases are the phone having no alarm at all, getNextAlarmClock() handing back
 * Remnant's own alarm, which must never be mistaken for someone else's, and the reverse:
 * another app's alarm set for the same minute as ours, which must never be mistaken for
 * our own -- that one cost a user two alarms ringing at 6am.
 */
class CompanionAlarmTest {

    private val ours = 1_700_000_000_000L
    private fun minutesBeforeOurs(minutes: Long) = ours - minutes * 60_000L

    // --- detection ---

    @Test
    fun `no alarm on the phone is not a companion`() {
        assertNull(CompanionAlarm.minutesBefore(null, ours))
        assertFalse(CompanionAlarm.isCompanion(null, ours))
    }

    @Test
    fun `an earlier alarm is a companion and reports the gap`() {
        assertEquals(62L, CompanionAlarm.minutesBefore(minutesBeforeOurs(62), ours))
        assertTrue(CompanionAlarm.isCompanion(minutesBeforeOurs(62), ours))
    }

    @Test
    fun `an alarm after ours is not a companion`() {
        assertNull(CompanionAlarm.minutesBefore(ours + 60_000L, ours))
        assertFalse(CompanionAlarm.isCompanion(ours + 60_000L, ours))
    }

    @Test
    fun `our own alarm coming back to us is not a companion`() {
        // getNextAlarmClock() reports Remnant's alarm alongside everyone else's, so an exact
        // match with no owner to check is us looking at ourselves -- treating it as a
        // companion would silence the only alarm the user has.
        assertNull(CompanionAlarm.minutesBefore(ours, ours))
        assertFalse(CompanionAlarm.isCompanion(ours, ours))
    }

    @Test
    fun `a near-identical time counts as the same alarm`() {
        val within = ours - (CompanionAlarm.SAME_ALARM_TOLERANCE_MS - 1)
        assertNull(CompanionAlarm.minutesBefore(within, ours))
    }

    @Test
    fun `a time outside the tolerance is a separate alarm`() {
        val outside = ours - (CompanionAlarm.SAME_ALARM_TOLERANCE_MS + 1)
        assertEquals(0L, CompanionAlarm.minutesBefore(outside, ours))
        assertTrue(CompanionAlarm.isCompanion(outside, ours))
    }

    // --- whose alarm is it? ---

    @Test
    fun `an alarm from our own package is ours`() {
        assertEquals(true, CompanionAlarm.ownsAlarm("com.remnant.dreams", "com.remnant.dreams"))
    }

    @Test
    fun `an alarm from another package is not ours`() {
        assertEquals(false, CompanionAlarm.ownsAlarm("com.sec.android.app.clockpackage", "com.remnant.dreams"))
    }

    @Test
    fun `an owner we could not read is left undecided`() {
        // Null is not "someone else" -- it means fall back to comparing times.
        assertNull(CompanionAlarm.ownsAlarm(null, "com.remnant.dreams"))
    }

    @Test
    fun `the debug build's own alarm is still ours`() {
        // The debug build carries an applicationIdSuffix, so the package to compare against
        // is the running one rather than a constant.
        assertEquals(
            true,
            CompanionAlarm.ownsAlarm("com.remnant.dreams.debug", "com.remnant.dreams.debug")
        )
    }

    // --- detection by owner rather than by clock ---

    @Test
    fun `another app's alarm at exactly our time is a companion`() {
        // The 6am bug: the phone's clock and Remnant were both set for 6:00, the matching
        // time read as our own alarm, Remnant rang anyway and the user got two alarms at
        // once. Knowing the owner is what settles it.
        assertEquals(0L, CompanionAlarm.minutesBefore(ours, ours, nextAlarmIsOurs = false))
        assertTrue(CompanionAlarm.isCompanion(ours, ours, nextAlarmIsOurs = false))
    }

    @Test
    fun `our own alarm at exactly our time is not a companion`() {
        assertNull(CompanionAlarm.minutesBefore(ours, ours, nextAlarmIsOurs = true))
        assertFalse(CompanionAlarm.isCompanion(ours, ours, nextAlarmIsOurs = true))
    }

    @Test
    fun `our own alarm earlier than the time being checked is still not a companion`() {
        // Re-picking a time during setup with our own alarm already on the phone: the owner
        // decides, however far apart the two times are.
        assertNull(CompanionAlarm.minutesBefore(minutesBeforeOurs(62), ours, nextAlarmIsOurs = true))
        assertFalse(CompanionAlarm.isCompanion(minutesBeforeOurs(62), ours, nextAlarmIsOurs = true))
    }

    @Test
    fun `another app's alarm after ours is still not a companion`() {
        // Owner or no owner, an alarm we get in front of leaves Remnant ringing first.
        assertNull(CompanionAlarm.minutesBefore(ours + 60_000L, ours, nextAlarmIsOurs = false))
        assertFalse(CompanionAlarm.isCompanion(ours + 60_000L, ours, nextAlarmIsOurs = false))
    }

    @Test
    fun `another app's alarm a moment after ours is not a companion`() {
        val justAfter = ours + (CompanionAlarm.SAME_ALARM_TOLERANCE_MS - 1)
        assertNull(CompanionAlarm.minutesBefore(justAfter, ours, nextAlarmIsOurs = false))
    }

    @Test
    fun `another app's alarm a moment before ours is a companion`() {
        // The tolerance is not applied once the owner is known, so a near-match no longer
        // swallows someone else's alarm.
        val justBefore = ours - (CompanionAlarm.SAME_ALARM_TOLERANCE_MS - 1)
        assertEquals(0L, CompanionAlarm.minutesBefore(justBefore, ours, nextAlarmIsOurs = false))
        assertTrue(CompanionAlarm.isCompanion(justBefore, ours, nextAlarmIsOurs = false))
    }

    @Test
    fun `an unreadable owner falls back to the time heuristic`() {
        // Some clock apps set no show intent to read an owner from. Behaviour there is the
        // old behaviour -- no better, but no worse either.
        assertNull(CompanionAlarm.minutesBefore(ours, ours, nextAlarmIsOurs = null))
        assertFalse(CompanionAlarm.isCompanion(ours, ours, nextAlarmIsOurs = null))
        assertEquals(62L, CompanionAlarm.minutesBefore(minutesBeforeOurs(62), ours, nextAlarmIsOurs = null))
    }

    @Test
    fun `an alarm at the same time from another app is not worth flagging at setup`() {
        // It is a companion, not a check-in that arrives long after the user got up, so the
        // setup hint has nothing to move.
        assertFalse(CompanionAlarm.isCheckInStale(ours, ours, nextAlarmIsOurs = false))
    }

    @Test
    fun `our own alarm never makes the setup hint fire`() {
        assertFalse(
            CompanionAlarm.isCheckInStale(minutesBeforeOurs(62), ours, nextAlarmIsOurs = true)
        )
    }

    // --- worth flagging during setup? ---

    @Test
    fun `an hour-long gap is stale enough to raise at setup`() {
        // The reported case: phone rings at 6:00, Remnant asks about the dream at 7:02.
        assertTrue(CompanionAlarm.isCheckInStale(minutesBeforeOurs(62), ours))
    }

    @Test
    fun `checking in just after the phone's alarm is not stale`() {
        assertFalse(
            CompanionAlarm.isCheckInStale(
                minutesBeforeOurs(CompanionAlarm.CHECK_IN_DELAY_MINUTES.toLong()),
                ours
            )
        )
    }

    @Test
    fun `the stale threshold is exclusive`() {
        assertFalse(CompanionAlarm.isCheckInStale(minutesBeforeOurs(15), ours))
        assertTrue(CompanionAlarm.isCheckInStale(minutesBeforeOurs(16), ours))
    }

    @Test
    fun `nothing to compare against is never stale`() {
        assertFalse(CompanionAlarm.isCheckInStale(null, ours))
    }

    // --- the offered replacement time ---

    @Test
    fun `the offered check-in lands just after the phone's alarm`() {
        val phoneAlarm = minutesBeforeOurs(62)
        val checkIn = CompanionAlarm.checkInTimeAfter(phoneAlarm)

        assertEquals(CompanionAlarm.CHECK_IN_DELAY_MINUTES * 60_000L, checkIn - phoneAlarm)
    }

    // --- the recommended wake time ---

    @Test
    fun `the recommendation is half an hour before the phone's alarm`() {
        val phoneAlarm = minutesBeforeOurs(62)
        val recommended = CompanionAlarm.recommendedWakeBefore(phoneAlarm)

        assertEquals(CompanionAlarm.RECOMMENDED_LEAD_MINUTES * 60_000L, phoneAlarm - recommended)
    }

    @Test
    fun `waking on the recommendation makes Remnant the first alarm, not a companion`() {
        // This is the behaviour the copy has to be honest about: at the recommended time
        // Remnant rings its own tone, because the phone's alarm is still ahead of it.
        val phoneAlarm = minutesBeforeOurs(62)
        val recommended = CompanionAlarm.recommendedWakeBefore(phoneAlarm)

        assertFalse(CompanionAlarm.isCompanion(phoneAlarm, recommended))
        assertFalse(CompanionAlarm.isCheckInStale(phoneAlarm, recommended))
    }

    @Test
    fun `the recommendation never collides with the phone's own alarm`() {
        // Matching the alarm exactly would read as our own alarm coming back to us and
        // leave the user with two things going off at once. Half an hour is clear of that.
        val phoneAlarm = minutesBeforeOurs(62)
        val recommended = CompanionAlarm.recommendedWakeBefore(phoneAlarm)

        assertTrue(phoneAlarm - recommended > CompanionAlarm.SAME_ALARM_TOLERANCE_MS)
    }

    @Test
    fun `the recommendation lands earlier than the quiet alternative`() {
        val phoneAlarm = minutesBeforeOurs(62)

        assertTrue(
            CompanionAlarm.recommendedWakeBefore(phoneAlarm) <
                CompanionAlarm.checkInTimeAfter(phoneAlarm)
        )
    }

    @Test
    fun `taking the offer stops the gap being worth flagging`() {
        // Accepting the suggestion has to settle the warning, or the hint nags forever.
        val phoneAlarm = minutesBeforeOurs(62)
        val newOurs = CompanionAlarm.checkInTimeAfter(phoneAlarm)

        assertTrue(CompanionAlarm.isCompanion(phoneAlarm, newOurs))
        assertFalse(CompanionAlarm.isCheckInStale(phoneAlarm, newOurs))
    }
}
