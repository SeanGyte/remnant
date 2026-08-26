package com.remnant.dreams.alarm

/**
 * Collects what the recogniser hands back over a capture.
 *
 * A recogniser reports each utterance twice over: a stream of partial results that each
 * restate the whole utterance so far, then one final result that supersedes them. Only the
 * final result is authoritative, but it arrives only once the speaker pauses long enough
 * for the utterance to close. Someone who tells their dream straight through never pauses,
 * so the words sit in the partials until the capture is stopped -- and if the partials are
 * thrown away at that point, a full minute of speech reaches the database as nothing at all.
 *
 * Holding the latest partial is what makes those words survive. Pure and synchronous on
 * purpose: every call arrives on the main thread, and the salvage rules are the part worth
 * testing without a recogniser attached.
 */
class TranscriptAccumulator {

    private val committed = StringBuilder()
    private var pending: String = ""

    /** Everything heard so far: the finished utterances plus any partial still in flight. */
    val text: String
        get() = join(committed.toString(), pending)

    /** True while nothing usable has been heard at all. */
    val isEmpty: Boolean
        get() = text.isEmpty()

    /**
     * A partial hypothesis for the utterance in progress. Each one restates that utterance
     * rather than continuing it, so it replaces the previous partial instead of appending.
     */
    fun onPartial(partial: String?) {
        val trimmed = partial?.trim().orEmpty()
        if (trimmed.isNotEmpty()) pending = trimmed
    }

    /**
     * The recogniser's final text for an utterance, which supersedes the partials that led
     * up to it. An empty final means the recogniser closed the utterance with nothing to
     * show for it -- the partials are then the best record there is, so they are kept
     * rather than cleared.
     */
    fun onFinal(final: String?) {
        val trimmed = final?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            salvagePending()
            return
        }
        pending = ""
        commit(trimmed)
    }

    /**
     * Commits the partial still in flight, for when its utterance will never be finalised:
     * the capture was stopped mid-sentence, or the recogniser gave up. Safe to call twice.
     */
    fun salvagePending() {
        val salvaged = pending
        pending = ""
        if (salvaged.isNotEmpty()) commit(salvaged)
    }

    private fun commit(value: String) {
        if (committed.isNotEmpty()) committed.append(" ")
        committed.append(value)
    }

    private fun join(first: String, second: String): String = when {
        first.isEmpty() -> second
        second.isEmpty() -> first
        else -> "$first $second"
    }
}
