package com.remnant.dreams.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A recogniser restates the utterance in progress with every partial result and only
 * finalises it once the speaker pauses. Someone describing a dream without pausing can
 * therefore reach the end of a capture with every word still sitting in a partial, so the
 * rule that decides what survives is worth pinning: partials replace each other, finals
 * commit, and nothing in flight is thrown away when the capture stops.
 */
class TranscriptAccumulatorTest {

    @Test
    fun `a fresh accumulator is empty`() {
        val acc = TranscriptAccumulator()

        assertTrue(acc.isEmpty)
        assertEquals("", acc.text)
    }

    // --- partials ---

    @Test
    fun `a partial is readable before it is finalised`() {
        val acc = TranscriptAccumulator()
        acc.onPartial("I was flying over")

        assertEquals("I was flying over", acc.text)
        assertFalse(acc.isEmpty)
    }

    @Test
    fun `each partial restates the utterance rather than extending it`() {
        val acc = TranscriptAccumulator()
        acc.onPartial("I was")
        acc.onPartial("I was flying")
        acc.onPartial("I was flying over water")

        assertEquals("I was flying over water", acc.text)
    }

    @Test
    fun `blank partials are ignored rather than wiping what is there`() {
        val acc = TranscriptAccumulator()
        acc.onPartial("I was flying")
        acc.onPartial("")
        acc.onPartial("   ")

        assertEquals("I was flying", acc.text)
    }

    // --- finals ---

    @Test
    fun `a final supersedes the partials that led to it`() {
        val acc = TranscriptAccumulator()
        acc.onPartial("I was fly")
        acc.onFinal("I was flying over water")

        assertEquals("I was flying over water", acc.text)
    }

    @Test
    fun `successive finals are joined into one transcript`() {
        val acc = TranscriptAccumulator()
        acc.onFinal("I was flying over water.")
        acc.onFinal("Then the water turned to glass.")

        assertEquals("I was flying over water. Then the water turned to glass.", acc.text)
    }

    @Test
    fun `an empty final keeps the partial instead of discarding it`() {
        // The recogniser closing an utterance with nothing to show for it must not cost us
        // the words it had already offered.
        val acc = TranscriptAccumulator()
        acc.onPartial("I was flying over water")
        acc.onFinal(null)

        assertEquals("I was flying over water", acc.text)
    }

    // --- salvage: the case that was losing whole dreams ---

    @Test
    fun `stopping mid-sentence keeps the words already heard`() {
        // The capture is stopped while the user is still talking, so this utterance will
        // never be finalised. Previously everything here was thrown away.
        val acc = TranscriptAccumulator()
        acc.onFinal("I was flying over water.")
        acc.onPartial("Then the water turned to")
        acc.salvagePending()

        assertEquals("I was flying over water. Then the water turned to", acc.text)
    }

    @Test
    fun `salvaging twice does not duplicate the words`() {
        val acc = TranscriptAccumulator()
        acc.onPartial("Then the water turned to")
        acc.salvagePending()
        acc.salvagePending()

        assertEquals("Then the water turned to", acc.text)
    }

    @Test
    fun `salvaging nothing leaves the transcript alone`() {
        val acc = TranscriptAccumulator()
        acc.onFinal("I was flying over water.")
        acc.salvagePending()

        assertEquals("I was flying over water.", acc.text)
    }

    @Test
    fun `a salvaged partial can be followed by more speech`() {
        val acc = TranscriptAccumulator()
        acc.onPartial("Then the water")
        acc.salvagePending()
        acc.onFinal("turned to glass.")

        assertEquals("Then the water turned to glass.", acc.text)
    }

    @Test
    fun `a capture that heard only silence stays empty`() {
        val acc = TranscriptAccumulator()
        acc.onPartial(null)
        acc.onFinal("")
        acc.salvagePending()

        assertTrue(acc.isEmpty)
    }
}
