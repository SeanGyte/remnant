package com.remnant.dreams.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Three screens ask whether the phone is already waking the user before Remnant does -- the
 * scheduler, the journal banner and the onboarding time step -- and they have to agree.
 * The awkward cases are the phone having no alarm at all, and getNextAlarmClock() handing
 * back Remnant's own alarm, which must never be mistaken for someone else's.
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
        // match is us looking at ourselves -- treating it as a companion would silence the
        // only alarm the user has.
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
