package com.example.offlinedocumentcomposer

import com.example.offlinedocumentcomposer.presentation.tools.parsePageRanges
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfPageRangeTest {

    @Test
    fun testEmptyAndBlankInput() {
        assertEquals(emptyList<Int>(), parsePageRanges("", 10))
        assertEquals(emptyList<Int>(), parsePageRanges("   ", 10))
        assertEquals(emptyList<Int>(), parsePageRanges("1, 2", 0))
    }

    @Test
    fun testSinglePages() {
        val result = parsePageRanges("1, 3, 5", 10)
        assertEquals(listOf(0, 2, 4), result)
    }

    @Test
    fun testRanges() {
        val result = parsePageRanges("1-3, 5-7", 10)
        assertEquals(listOf(0, 1, 2, 4, 5, 6), result)
    }

    @Test
    fun testReversedRange() {
        val result = parsePageRanges("5-2", 10)
        assertEquals(listOf(1, 2, 3, 4), result)
    }

    @Test
    fun testDeduplicationAndSorting() {
        val result = parsePageRanges("5, 2, 2, 1, 5, 3", 10)
        assertEquals(listOf(0, 1, 2, 4), result)
    }

    @Test
    fun testDelimitersAndSpaces() {
        val result = parsePageRanges("1; 2 3, 4", 10)
        assertEquals(listOf(0, 1, 2, 3), result)
    }

    @Test
    fun testOutOfBoundsClamping() {
        // Range 8-15 clamped to max 10
        val result = parsePageRanges("8-15", 10)
        assertEquals(listOf(7, 8, 9), result)
    }
}
