package com.remnant.dreams.data

/**
 * Fallback transcript text, shared between the capture service that writes it and the
 * audio-cleanup worker that rewrites it once the recording has been deleted.
 *
 * Both sides must use these constants rather than their own copy of the string --
 * hand-typed copies previously differed by one dash character, so the rewrite never
 * matched and entries kept telling the user to play a recording that was gone.
 */
object TranscriptPlaceholders {

    /** Written when a capture produced audio but no usable transcript. */
    const val NO_TRANSCRIPT_WITH_AUDIO = "Couldn't catch the words -- tap to play the recording."

    /** Replaces [NO_TRANSCRIPT_WITH_AUDIO] once the recording itself has been deleted. */
    const val NO_TRANSCRIPT_AUDIO_DELETED = "Dream captured but transcript unavailable."
}
