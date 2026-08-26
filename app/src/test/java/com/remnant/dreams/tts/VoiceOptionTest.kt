package com.remnant.dreams.tts

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Cloud voice is assigned on first run, so text-to-speech is on by default and the
 * privacy policy says so. Two things have to hold: the assignment happens once and never
 * re-rolls, and choosing the phone's own voice really does mean no request is ever made.
 * `selectedOrNull` is the single gate every caller goes through before calling Google.
 */
class VoiceOptionTest {

    // --- first-run assignment ---

    @Test
    fun `the assignable voices are Harper and Quinn`() {
        // Named here rather than filtered by id, so renaming or reordering the catalogue
        // cannot quietly change who new users are given.
        assertEquals(2, VoiceOption.ASSIGNABLE_DEFAULTS.size)
        assertEquals(
            listOf("Harper", "Quinn"),
            VoiceOption.ASSIGNABLE_DEFAULTS.map { it.friendlyName }
        )
        assertEquals(
            listOf("en-US-Chirp3-HD-Aoede", "en-US-Chirp3-HD-Kore"),
            VoiceOption.ASSIGNABLE_DEFAULTS.map { it.id }
        )
    }

    @Test
    fun `an assigned voice is always one of the two`() {
        repeat(200) {
            assertTrue(VoiceOption.assignDefault() in VoiceOption.ASSIGNABLE_DEFAULTS)
        }
    }

    @Test
    fun `both voices actually get handed out`() {
        // A coin flip that only ever lands one way is the bug this catches. Seeded so the
        // test cannot flake.
        val assigned = (0 until 200)
            .map { VoiceOption.assignDefault(Random(it)).friendlyName }
            .toSet()
        assertEquals(setOf("Harper", "Quinn"), assigned)
    }

    @Test
    fun `a fresh install gets a voice`() {
        val assigned = VoiceOption.assignIfUnset("")
        assertNotNull(assigned)
        assertTrue(VoiceOption.ASSIGNABLE_DEFAULTS.any { it.id == assigned })
    }

    @Test
    fun `an install that already has a voice is never reassigned`() {
        // This is what makes the assignment survive restarts: every launch asks, and every
        // launch after the first is told to leave it alone.
        for (stored in VoiceOption.ALL.map { it.id }) {
            assertNull(VoiceOption.assignIfUnset(stored))
        }
    }

    @Test
    fun `choosing the phone's own voice is never overwritten on the next launch`() {
        // The worst possible regression: a user opts out of Cloud voices and the next cold
        // start silently hands them one, sending their name to Google after all.
        assertNull(VoiceOption.assignIfUnset(VoiceOption.DEVICE_VOICE_ID))
    }

    // --- what is actually in use ---

    @Test
    fun `the phone's own voice resolves to no cloud voice, so nothing is sent`() {
        assertNull(VoiceOption.selectedOrNull(VoiceOption.DEVICE_VOICE_ID))
    }

    @Test
    fun `a selected voice resolves to that exact voice`() {
        val ruby = VoiceOption.selectedOrNull("en-AU-News-F")
        assertNotNull(ruby)
        assertEquals("en-AU-News-F", ruby?.id)
        assertEquals("Ruby", ruby?.friendlyName)
    }

    @Test
    fun `every offered voice resolves from its own id`() {
        for (voice in VoiceOption.ALL) {
            assertEquals(voice, VoiceOption.selectedOrNull(voice.id))
        }
    }

    @Test
    fun `an id this build no longer offers falls back to the phone's own voice`() {
        // A voice retired in a later release must not quietly become some other Cloud voice
        // the user never chose -- it drops to the device voice, which sends nothing.
        assertNull(VoiceOption.selectedOrNull("en-AU-Retired-Voice"))
    }

    @Test
    fun `an unassigned install sends nothing until it has been assigned`() {
        // Between install and the first Application.onCreate the stored id is empty. The
        // safe reading of "no voice" is the device voice, not an arbitrary Cloud one.
        assertNull(VoiceOption.selectedOrNull(""))
    }

    @Test
    fun `the device voice id cannot collide with a real voice`() {
        assertTrue(VoiceOption.ALL.none { it.id == VoiceOption.DEVICE_VOICE_ID })
    }
}
