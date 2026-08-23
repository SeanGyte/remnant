package com.remnant.dreams.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamSearchTest {

    private val dreams = listOf(
        DreamEntry(id = 1, transcription = "I was flying over the ocean"),
        DreamEntry(id = 2, transcription = "Chased through a FOREST by something"),
        DreamEntry(id = 3, transcription = "Back at school, exam I hadn't studied for"),
        DreamEntry(id = 4, transcription = "")
    )

    @Test
    fun `blank query returns everything unchanged`() {
        assertEquals(dreams, DreamSearch.filter(dreams, ""))
        assertEquals(dreams, DreamSearch.filter(dreams, "   "))
    }

    @Test
    fun `match is case insensitive`() {
        val result = DreamSearch.filter(dreams, "forest")
        assertEquals(listOf(2L), result.map { it.id })
    }

    @Test
    fun `query is trimmed before matching`() {
        val result = DreamSearch.filter(dreams, "  ocean  ")
        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test
    fun `substring matches mid-word`() {
        val result = DreamSearch.filter(dreams, "studi")
        assertEquals(listOf(3L), result.map { it.id })
    }

    @Test
    fun `no match returns empty list`() {
        assertTrue(DreamSearch.filter(dreams, "unicorn").isEmpty())
    }

    @Test
    fun `empty journal returns empty list`() {
        assertTrue(DreamSearch.filter(emptyList(), "anything").isEmpty())
    }

    @Test
    fun `original order is preserved`() {
        val result = DreamSearch.filter(dreams, "a")
        assertEquals(listOf(1L, 2L, 3L), result.map { it.id })
    }
}
