package com.remnant.dreams.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fallback alarm rings without a notification, so nothing puts a screen in front of
 * the user -- opening the app has to do it. These are the two boundaries that decide it.
 */
class AlarmRingingStateTest {

    /** 12 June 2026, 07:00:00 UTC -- an arbitrary fixed instant standing in for the alarm. */
    private val firedAt = 1_781_247_600_000L

    private val minute = 60 * 1000L

    // --- shouldRouteToCapture ---

    @Test
    fun `no outstanding alarm never routes to capture`() {
        // 0 is the resting value: no fallback alarm has fired, so opening the app is just
        // opening the app.
        assertFalse(AlarmRingingState.shouldRouteToCapture(0L, firedAt))
    }

    @Test
    fun `opening the app the moment the alarm fires routes to capture`() {
        assertTrue(AlarmRingingState.shouldRouteToCapture(firedAt, firedAt))
    }

    @Test
    fun `opening the app while it is still ringing routes to capture`() {
        assertTrue(AlarmRingingState.shouldRouteToCapture(firedAt, firedAt + 2 * minute))
    }

    @Test
    fun `opening the app after the ringing stopped still routes to capture`() {
        // The whole point of the window: someone who silenced the phone and got up anyway
        // has still just woken up, and their dream is still there to capture.
        val afterRingOut = firedAt + AlarmRingingState.RING_DURATION_MS + minute
        assertTrue(AlarmRingingState.shouldRouteToCapture(firedAt, afterRingOut))
    }

    @Test
    fun `the last millisecond of the capture window still routes`() {
        val edge = firedAt + AlarmRingingState.CAPTURE_WINDOW_MS - 1
        assertTrue(AlarmRingingState.shouldRouteToCapture(firedAt, edge))
    }

    @Test
    fun `the end of the capture window stops routing`() {
        val edge = firedAt + AlarmRingingState.CAPTURE_WINDOW_MS
        assertFalse(AlarmRingingState.shouldRouteToCapture(firedAt, edge))
    }

    @Test
    fun `opening the app hours later does not drag the user into capture`() {
        assertFalse(AlarmRingingState.shouldRouteToCapture(firedAt, firedAt + 6 * 60 * minute))
    }

    @Test
    fun `yesterday's unanswered alarm does not fire today`() {
        // The flag is only cleared by the alarm screen opening, so a fallback alarm nobody
        // answered stays set. It must go stale on its own, not ambush the next morning.
        assertFalse(AlarmRingingState.shouldRouteToCapture(firedAt, firedAt + 24 * 60 * minute))
    }

    @Test
    fun `a clock correction backwards does not leave the alarm ringing into the future`() {
        // NTP or a timezone fix can move the clock behind the recorded instant. Treat that
        // as answered rather than routing to capture for the next half hour of wall clock.
        assertFalse(AlarmRingingState.shouldRouteToCapture(firedAt, firedAt - minute))
    }

    // --- ringingTimeLeftMs ---

    @Test
    fun `a fresh alarm has the full ring duration left`() {
        assertEquals(
            AlarmRingingState.RING_DURATION_MS,
            AlarmRingingState.ringingTimeLeftMs(firedAt, firedAt)
        )
    }

    @Test
    fun `time left counts down as the alarm rings`() {
        assertEquals(
            AlarmRingingState.RING_DURATION_MS - 2 * minute,
            AlarmRingingState.ringingTimeLeftMs(firedAt, firedAt + 2 * minute)
        )
    }

    @Test
    fun `time left never goes negative once the alarm has rung out`() {
        val afterRingOut = firedAt + AlarmRingingState.RING_DURATION_MS + minute
        assertEquals(0L, AlarmRingingState.ringingTimeLeftMs(firedAt, afterRingOut))
    }

    @Test
    fun `no outstanding alarm has no ringing left, so the service stops itself`() {
        // The service asks this on every start command; 0 is what makes a stale or
        // duplicate start shut itself down instead of ringing at the wrong hour.
        assertEquals(0L, AlarmRingingState.ringingTimeLeftMs(0L, firedAt))
    }

    @Test
    fun `the capture window outlasts the ringing`() {
        // If this ever inverts, the app would stop offering capture while it is still
        // making noise at the user.
        assertTrue(AlarmRingingState.CAPTURE_WINDOW_MS > AlarmRingingState.RING_DURATION_MS)
    }
}
