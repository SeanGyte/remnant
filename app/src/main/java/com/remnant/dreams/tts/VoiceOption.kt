package com.remnant.dreams.tts

import kotlin.random.Random

data class VoiceOption(
    val id: String,
    val friendlyName: String,
    val description: String,
    val languageCode: String,
    val gender: String
) {
    companion object {
        val ALL = listOf(
            VoiceOption("en-AU-Chirp3-HD-Charon", "Jack", "The Early Riser", "en-AU", "Male"),
            VoiceOption("en-AU-Chirp3-HD-Enceladus", "Lachie", "The Surf Report", "en-AU", "Male"),
            VoiceOption("en-AU-News-F", "Ruby", "The Morning Show", "en-AU", "Female"),
            VoiceOption("en-GB-News-K", "James", "The Newsreader", "en-GB", "Male"),
            VoiceOption("en-GB-News-J", "Oliver", "The Gentleman", "en-GB", "Male"),
            VoiceOption("en-GB-News-L", "Hugh", "The Anchor", "en-GB", "Male"),
            VoiceOption("en-GB-News-G", "Charlotte", "The Presenter", "en-GB", "Female"),
            VoiceOption("en-GB-News-I", "Sophie", "The Correspondent", "en-GB", "Female"),
            VoiceOption("en-US-Chirp3-HD-Aoede", "Harper", "The Go-Getter", "en-US", "Female"),
            VoiceOption("en-US-Chirp3-HD-Kore", "Quinn", "The Upbeat", "en-US", "Female"),
        )

        /** Stored in place of a voice id when the user has chosen the phone's own voice. */
        const val DEVICE_VOICE_ID = "device"

        private const val ID_HARPER = "en-US-Chirp3-HD-Aoede"
        private const val ID_QUINN = "en-US-Chirp3-HD-Kore"

        /**
         * The voices a fresh install can be assigned. Which of the two a given install
         * gets is a coin flip, so two people on the same build can end up with different
         * companions.
         */
        val ASSIGNABLE_DEFAULTS = ALL.filter { it.id == ID_HARPER || it.id == ID_QUINN }

        /**
         * The Cloud voice currently in use, or null for the phone's own voice.
         *
         * Null is the only state in which Remnant makes no text-to-speech request:
         * [DEVICE_VOICE_ID], an id this build no longer offers, and an install that
         * somehow never got assigned all land here, which is the safe way to be wrong.
         */
        fun selectedOrNull(voiceId: String): VoiceOption? =
            if (voiceId.isEmpty()) null else ALL.find { it.id == voiceId }

        /** The coin flip. Seedable so the outcomes can be tested. */
        fun assignDefault(random: Random = Random.Default): VoiceOption =
            ASSIGNABLE_DEFAULTS[random.nextInt(ASSIGNABLE_DEFAULTS.size)]

        /**
         * The voice id a fresh install should be given, or null if this install already
         * has one. Assignment happens once, while the stored id is empty, so it never
         * re-rolls -- and a user who switched to the phone's own voice is never quietly
         * handed a Cloud voice back on the next launch.
         */
        fun assignIfUnset(storedVoiceId: String, random: Random = Random.Default): String? =
            if (storedVoiceId.isEmpty()) assignDefault(random).id else null
    }
}
