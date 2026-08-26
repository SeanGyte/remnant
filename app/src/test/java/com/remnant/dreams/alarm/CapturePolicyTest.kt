package com.remnant.dreams.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that matters most here is the one that was missing: a capture nobody spoke into
 * must keep nothing. A silent room previously ran its full length and still wrote an entry,
 * an 800KB recording of nothing and a day's streak credit, because the decision hung off a
 * recogniser that was not hearing the microphone.
 */
class CapturePolicyTest {

    private val heard = true
    private val silent = false

    // --- is anyone talking? ---

    @Test
    fun `room tone is not speech`() {
        assertFalse(CapturePolicy.isSpeech(0))
        assertFalse(CapturePolicy.isSpeech(300))
    }

    @Test
    fun `a voice is speech`() {
        assertTrue(CapturePolicy.isSpeech(CapturePolicy.SPEECH_AMPLITUDE_THRESHOLD))
        assertTrue(CapturePolicy.isSpeech(20_000))
    }

    // --- nobody spoke ---

    @Test
    fun `a silent room is given the full initial wait`() {
        assertEquals(
            CapturePolicy.Decision.CONTINUE,
            CapturePolicy.decide(silent, elapsedMs = 14_000, sinceSpeechMs = 0)
        )
    }

    @Test
    fun `a silent room keeps nothing once the wait is up`() {
        // The defect this pins: no speech must mean no entry, no audio, no streak.
        assertEquals(
            CapturePolicy.Decision.DISCARD,
            CapturePolicy.decide(silent, elapsedMs = CapturePolicy.INITIAL_WAIT_MS, sinceSpeechMs = 0)
        )
    }

    @Test
    fun `a silent room still keeps nothing after a long run`() {
        assertEquals(
            CapturePolicy.Decision.DISCARD,
            CapturePolicy.decide(silent, elapsedMs = 600_000, sinceSpeechMs = 0)
        )
    }

    // --- somebody spoke ---

    @Test
    fun `talking keeps the capture open`() {
        assertEquals(
            CapturePolicy.Decision.CONTINUE,
            CapturePolicy.decide(heard, elapsedMs = 30_000, sinceSpeechMs = 1_000)
        )
    }

    @Test
    fun `a pause shorter than the timeout is not the end`() {
        assertEquals(
            CapturePolicy.Decision.CONTINUE,
            CapturePolicy.decide(heard, elapsedMs = 30_000, sinceSpeechMs = CapturePolicy.SILENCE_TIMEOUT_MS - 1)
        )
    }

    @Test
    fun `falling quiet after speaking saves the dream`() {
        assertEquals(
            CapturePolicy.Decision.SAVE,
            CapturePolicy.decide(heard, elapsedMs = 30_000, sinceSpeechMs = CapturePolicy.SILENCE_TIMEOUT_MS)
        )
    }

    @Test
    fun `the hard ceiling saves rather than discards once speech was heard`() {
        // A room that never falls quiet still has a dream in it -- keep what was said.
        assertEquals(
            CapturePolicy.Decision.SAVE,
            CapturePolicy.decide(heard, elapsedMs = CapturePolicy.MAX_CAPTURE_MS, sinceSpeechMs = 0)
        )
    }

    @Test
    fun `the initial wait is shorter than the silence timeout plus nothing`() {
        // Guards the constants against being edited into a state where a capture can be
        // discarded after the user has already started talking.
        assertTrue(CapturePolicy.INITIAL_WAIT_MS > CapturePolicy.SILENCE_TIMEOUT_MS)
        assertTrue(CapturePolicy.MAX_CAPTURE_MS > CapturePolicy.INITIAL_WAIT_MS)
    }
}
