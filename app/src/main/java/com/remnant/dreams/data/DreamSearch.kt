package com.remnant.dreams.data

/**
 * In-memory search across dream entries (Pro feature). The journal already holds the
 * full entry list in memory, and everything stays on-device, so filtering here is
 * simpler and faster than a database round-trip.
 */
object DreamSearch {

    /**
     * Case-insensitive substring match against the transcription.
     * A blank query returns the full list unchanged.
     */
    fun filter(dreams: List<DreamEntry>, query: String): List<DreamEntry> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return dreams
        return dreams.filter { it.transcription.contains(trimmed, ignoreCase = true) }
    }
}
