package com.remnant.dreams.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class ExportFormatterTest {

    private val dream = DreamEntry(
        id = 1,
        timestamp = 1755900000000L,
        transcription = "I was walking through a city made of glass.",
        durationSeconds = 134,
        isFragment = false
    )

    @Test
    fun `header includes app name and dream count`() {
        val text = ExportFormatter.format(listOf(dream, dream.copy(id = 2)), Date(0))
        assertTrue(text.startsWith("Remnant -- Dream Journal Export"))
        assertTrue(text.contains("2 dreams"))
    }

    @Test
    fun `single dream count is singular`() {
        val text = ExportFormatter.format(listOf(dream), Date(0))
        assertTrue(text.contains("1 dream\n"))
        assertFalse(text.contains("1 dreams"))
    }

    @Test
    fun `transcription text is included`() {
        val text = ExportFormatter.format(listOf(dream), Date(0))
        assertTrue(text.contains("I was walking through a city made of glass."))
    }

    @Test
    fun `duration is formatted with minutes and seconds`() {
        val text = ExportFormatter.format(listOf(dream), Date(0))
        assertTrue(text.contains("(2m 14s)"))
    }

    @Test
    fun `fragment entries carry the fragment marker`() {
        val text = ExportFormatter.format(listOf(dream.copy(isFragment = true)), Date(0))
        assertTrue(text.contains("[fragment -- capture was incomplete]"))
    }

    @Test
    fun `non-fragment entries have no fragment marker`() {
        val text = ExportFormatter.format(listOf(dream), Date(0))
        assertFalse(text.contains("[fragment"))
    }

    @Test
    fun `empty transcription gets placeholder`() {
        val text = ExportFormatter.format(listOf(dream.copy(transcription = "")), Date(0))
        assertTrue(text.contains("(no transcript)"))
    }

    @Test
    fun `empty journal still produces a valid header`() {
        val text = ExportFormatter.format(emptyList(), Date(0))
        assertTrue(text.contains("0 dreams"))
    }

    @Test
    fun `formatDuration handles sub-minute durations`() {
        assertEquals("45s", ExportFormatter.formatDuration(45))
        assertEquals("0s", ExportFormatter.formatDuration(0))
        assertEquals("1m 0s", ExportFormatter.formatDuration(60))
        assertEquals("10m 5s", ExportFormatter.formatDuration(605))
    }
}
