package com.remnant.dreams.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Selecting a Cloud voice is the consent to use Google's text-to-speech service, so
 * `selectedOrNull` is the one gate every caller goes through before making a request.
 * Null means the phone's own voice and no network call at all.
 */
class VoiceOptionTest {

    @Test
    fun `no voice selected means no cloud voice`() {
        // The stored id starts empty, which is what SharedPreferences hands back before
        // the user has ever opened the picker. Nothing may be sent in that state.
        assertNull(VoiceOption.selectedOrNull(""))
    }

    @Test
    fun `selecting a voice returns that exact voice`() {
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
        // A voice retired in a later release must not quietly become some other Cloud
        // voice the user never chose -- it drops to the device voice instead.
        assertNull(VoiceOption.selectedOrNull("en-AU-Retired-Voice"))
    }

    @Test
    fun `there is no default cloud voice`() {
        // The original bug: the picker had a DEFAULT, so an untouched install looked like
        // a Cloud selection and the user's first name went to Google at onboarding.
        // No unselected state may ever resolve to a Cloud voice.
        for (id in listOf("", " ", "default", "none")) {
            assertNull(VoiceOption.selectedOrNull(id))
        }
    }
}
