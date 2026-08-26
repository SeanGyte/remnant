package com.remnant.dreams.worker

import com.remnant.dreams.data.TranscriptPlaceholders
import java.util.Calendar
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioCleanupWorkerTest {

    private val originalTimeZone: TimeZone = TimeZone.getDefault()

    /** 12 June 2026, 02:30:00 UTC -- an arbitrary fixed instant, well clear of any boundary. */
    private val now = 1_781_231_400_000L

    private val oneDay = 24L * 60 * 60 * 1000

    /** Mirrors DreamDao's `timestamp < :before` queries, which keep an entry sitting on the cutoff. */
    private fun hasExpired(timestamp: Long, cutoff: Long) = timestamp < cutoff

    @Before
    fun pinTimeZone() {
        // Day arithmetic is calendar-based, so the assertions below only hold exactly in a
        // zone without daylight saving. The DST behaviour gets its own test.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    // --- retentionCutoffMillis ---

    @Test
    fun `zero retention leaves the cutoff at now and is the caller's problem`() {
        // 0 means "keep forever", which the worker decides before it ever asks for a cutoff.
        assertEquals(now, retentionCutoffMillis(now, 0))
    }

    @Test
    fun `seven day cutoff is seven days before now`() {
        assertEquals(now - 7 * oneDay, retentionCutoffMillis(now, 7))
    }

    @Test
    fun `thirty day cutoff is thirty days before now`() {
        assertEquals(now - 30 * oneDay, retentionCutoffMillis(now, 30))
    }

    @Test
    fun `ninety day cutoff is ninety days before now`() {
        assertEquals(now - 90 * oneDay, retentionCutoffMillis(now, 90))
    }

    @Test
    fun `one year cutoff is three hundred and sixty five days before now`() {
        assertEquals(now - 365 * oneDay, retentionCutoffMillis(now, 365))
    }

    @Test
    fun `longer retention produces an earlier cutoff`() {
        assertTrue(retentionCutoffMillis(now, 365) < retentionCutoffMillis(now, 90))
        assertTrue(retentionCutoffMillis(now, 90) < retentionCutoffMillis(now, 30))
        assertTrue(retentionCutoffMillis(now, 30) < retentionCutoffMillis(now, 7))
    }

    // --- boundary behaviour ---

    @Test
    fun `entry landing exactly on the cutoff is kept`() {
        val cutoff = retentionCutoffMillis(now, 30)
        assertFalse(hasExpired(cutoff, cutoff))
    }

    @Test
    fun `entry one millisecond older than the cutoff expires`() {
        val cutoff = retentionCutoffMillis(now, 30)
        assertTrue(hasExpired(cutoff - 1, cutoff))
    }

    @Test
    fun `entry one millisecond newer than the cutoff is kept`() {
        val cutoff = retentionCutoffMillis(now, 30)
        assertFalse(hasExpired(cutoff + 1, cutoff))
    }

    @Test
    fun `entry recorded at now is kept under the shortest retention`() {
        assertFalse(hasExpired(now, retentionCutoffMillis(now, 7)))
    }

    @Test
    fun `cutoff keeps the local time of day across a daylight saving change`() {
        // Sydney gains an hour on 4 October 2026, so a day back is 23 hours of elapsed
        // time, not 24. Counting calendar days keeps retention honest to the user's clock.
        TimeZone.setDefault(TimeZone.getTimeZone("Australia/Sydney"))

        val start = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.OCTOBER, 4, 9, 30, 0)
        }
        val cutoff = Calendar.getInstance().apply {
            timeInMillis = retentionCutoffMillis(start.timeInMillis, 1)
        }

        assertEquals(3, cutoff.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, cutoff.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cutoff.get(Calendar.MINUTE))
    }

    // --- transcript placeholders (finding 9: the two sides must match byte for byte) ---

    @Test
    fun `capture service placeholder is byte identical to the shared constant`() {
        // Hard-coded on purpose: if anyone writes their own copy of this string again,
        // with an em dash or any other drift, this test is what catches it.
        assertEquals(
            "Couldn't catch the words -- tap to play the recording.",
            TranscriptPlaceholders.NO_TRANSCRIPT_WITH_AUDIO
        )
    }

    @Test
    fun `placeholder uses an ASCII double hyphen and stays pure ASCII`() {
        // The original bug was an em dash on one side and a double hyphen on the other,
        // so anything outside ASCII in this string is the drift coming back.
        assertTrue(TranscriptPlaceholders.NO_TRANSCRIPT_WITH_AUDIO.contains(" -- "))
        assertTrue(TranscriptPlaceholders.NO_TRANSCRIPT_WITH_AUDIO.all { it.code < 128 })
    }

    @Test
    fun `cleaned up placeholder no longer mentions the recording`() {
        assertEquals(
            "Dream captured but transcript unavailable.",
            TranscriptPlaceholders.NO_TRANSCRIPT_AUDIO_DELETED
        )
        assertFalse(TranscriptPlaceholders.NO_TRANSCRIPT_AUDIO_DELETED.contains("recording"))
    }
}
