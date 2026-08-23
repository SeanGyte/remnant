package com.remnant.dreams.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plain-text journal export (Pro feature). Pure formatting logic, unit-testable.
 * PDF export is on the roadmap; text covers the "my data is mine" promise at launch.
 */
object ExportFormatter {

    private const val DIVIDER = "----------------------------------------"

    /**
     * Formats the full journal as plain text, newest entry first (the order the
     * journal displays them in).
     */
    fun format(dreams: List<DreamEntry>, exportedAt: Date = Date()): String {
        val headerFormat = SimpleDateFormat("d MMMM yyyy, h:mm a", Locale.getDefault())
        val entryFormat = SimpleDateFormat("EEEE, d MMMM yyyy 'at' h:mm a", Locale.getDefault())

        val sb = StringBuilder()
        sb.appendLine("Remnant -- Dream Journal Export")
        sb.appendLine("Exported: ${headerFormat.format(exportedAt)}")
        sb.appendLine("${dreams.size} dream${if (dreams.size != 1) "s" else ""}")
        sb.appendLine()

        for (dream in dreams) {
            sb.appendLine(DIVIDER)
            sb.appendLine()
            sb.append(entryFormat.format(Date(dream.timestamp)))
            sb.appendLine("  (${formatDuration(dream.durationSeconds)})")
            if (dream.isFragment) {
                sb.appendLine("[fragment -- capture was incomplete]")
            }
            sb.appendLine()
            sb.appendLine(dream.transcription.ifEmpty { "(no transcript)" })
            sb.appendLine()
        }

        return sb.toString()
    }

    fun formatDuration(totalSeconds: Int): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }
}
