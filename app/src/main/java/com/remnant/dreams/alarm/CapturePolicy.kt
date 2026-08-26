package com.remnant.dreams.alarm

/**
 * Decides when a capture should end, and whether what it heard is worth keeping.
 *
 * This used to be the recogniser's job by accident: the capture ran until the recogniser
 * said it had stopped hearing things. That put the decision behind a component that turned
 * out not to be receiving the microphone at all, so a silent room and a spoken dream were
 * indistinguishable -- both ran the full length and both saved an entry. The energy coming
 * off the recorder is the one signal that is always true, so the decision hangs off that
 * instead, and lives here where it can be tested without a microphone.
 */
object CapturePolicy {

    /**
     * Amplitude, on MediaRecorder's 0..32767 scale, above which we call it speech rather
     * than room tone. A quiet bedroom sits in the low hundreds; a voice at arm's length
     * runs into the thousands. Set well clear of the noise floor so a running fan cannot
     * hold a capture open all night.
     */
    const val SPEECH_AMPLITUDE_THRESHOLD = 1500

    /** How long to wait for the user to say anything at all before giving up on them. */
    const val INITIAL_WAIT_MS = 15_000L

    /** How much quiet ends a capture once they have started talking. */
    const val SILENCE_TIMEOUT_MS = 8_000L

    /** Hard ceiling, so a capture cannot run all day if the room never falls quiet. */
    const val MAX_CAPTURE_MS = 600_000L

    /** What the capture loop should do next. */
    enum class Decision {
        /** Keep listening. */
        CONTINUE,

        /** Stop and write the entry -- the user said something. */
        SAVE,

        /** Stop and keep nothing. Nobody spoke, so there is no dream and no entry. */
        DISCARD
    }

    /** True when [amplitude] is loud enough to count as somebody speaking. */
    fun isSpeech(amplitude: Int): Boolean = amplitude >= SPEECH_AMPLITUDE_THRESHOLD

    /**
     * @param heardSpeech whether any speech-level audio has been seen this capture
     * @param elapsedMs how long the capture has been running
     * @param sinceSpeechMs how long since the last speech-level audio; ignored when
     *   [heardSpeech] is false
     */
    fun decide(heardSpeech: Boolean, elapsedMs: Long, sinceSpeechMs: Long): Decision {
        if (!heardSpeech) {
            // Nothing has been said. Past the initial wait there is nothing to wait for,
            // and saving here is what produced entries full of silence.
            return if (elapsedMs >= INITIAL_WAIT_MS) Decision.DISCARD else Decision.CONTINUE
        }

        if (sinceSpeechMs >= SILENCE_TIMEOUT_MS) return Decision.SAVE
        if (elapsedMs >= MAX_CAPTURE_MS) return Decision.SAVE

        return Decision.CONTINUE
    }
}
