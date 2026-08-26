package com.remnant.dreams.alarm

/**
 * Whether an unanswered fallback alarm is still outstanding, worked out from timestamps
 * alone.
 *
 * The alarm normally arrives as a high-priority notification carrying a full-screen
 * intent. A user who refused POST_NOTIFICATIONS gets neither, so [AlarmRingtoneService]
 * makes the noise instead -- but a service cannot put a screen in front of anyone, so the
 * app has to notice the outstanding alarm when the user opens it and route them into the
 * capture flow itself. Those two boundaries live here, free of Android, so they can be
 * tested.
 */
object AlarmRingingState {

    /** How long the fallback keeps making noise before it gives up on its own. */
    const val RING_DURATION_MS = 5 * 60 * 1000L

    /**
     * How long after the alarm fired that opening the app still means "I have just woken
     * up". Longer than the ringing, so someone who silenced the phone and got up anyway
     * still lands in capture; short enough that opening the app at lunchtime does not.
     */
    const val CAPTURE_WINDOW_MS = 30 * 60 * 1000L

    /**
     * True while an unanswered fallback alarm should pull the user into the capture flow.
     * [ringingSince] is 0 when no fallback alarm is outstanding.
     */
    fun shouldRouteToCapture(ringingSince: Long, now: Long): Boolean =
        elapsedSince(ringingSince, now)?.let { it < CAPTURE_WINDOW_MS } ?: false

    /** Milliseconds of ringing left, or 0 once the fallback should fall silent. */
    fun ringingTimeLeftMs(ringingSince: Long, now: Long): Long {
        val elapsed = elapsedSince(ringingSince, now) ?: return 0L
        return (RING_DURATION_MS - elapsed).coerceAtLeast(0L)
    }

    /**
     * Time since the alarm fired, or null if no alarm is outstanding. A negative elapsed
     * means the clock moved backwards under us (timezone fix, NTP correction); the alarm
     * is treated as answered rather than left ringing into the future.
     */
    private fun elapsedSince(ringingSince: Long, now: Long): Long? {
        if (ringingSince <= 0L) return null
        val elapsed = now - ringingSince
        return if (elapsed < 0L) null else elapsed
    }
}
