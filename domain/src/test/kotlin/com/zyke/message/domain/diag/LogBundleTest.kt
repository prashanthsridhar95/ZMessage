package com.zyke.message.domain.diag

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class LogBundleTest {
    private val header = listOf("App 1.0 (1)", "Phone: Test X")

    @Test
    fun headerComesFirstThenSectionsInOrder() {
        val text = LogBundle.build(header, listOf(LogSection("old", "a\n"), LogSection("new", "b\n")))
        assertTrue(text.startsWith("Zyke Message log\nApp 1.0 (1)\nPhone: Test X\n\n"))
        assertTrue(text.indexOf("===== old =====") < text.indexOf("===== new ====="))
        assertTrue(text.contains("===== old =====\na\n"))
    }

    @Test
    fun emptySectionsAreLeftOut() {
        val sections = listOf(LogSection("empty", "  \n"), LogSection("real", "x"))
        val text = LogBundle.build(header, sections)
        assertFalse("empty" in text)
        assertTrue(LogBundle.hasContent(sections))
        assertFalse(LogBundle.hasContent(listOf(LogSection("e", ""))))
    }

    @Test
    fun overTheLimitTheNewestLinesAreKept() {
        val lines = (1..1000).joinToString("\n") { "line-$it" }
        val text = LogBundle.build(header, listOf(LogSection("log", lines)), maxChars = 2000)
        assertTrue(text.length <= 2000)
        assertTrue(text.startsWith("Zyke Message log"))
        assertTrue(LogBundle.TRIMMED_NOTE in text)
        assertTrue("line-1000" in text)
        assertFalse("line-1\n" in text)
        // The cut falls on a line boundary.
        val firstKept = text.substringAfter(LogBundle.TRIMMED_NOTE).lines().first()
        assertTrue(firstKept, firstKept.startsWith("line-"))
    }

    @Test
    fun underTheLimitNothingIsCut() {
        val text = LogBundle.build(header, listOf(LogSection("log", "one\ntwo")), maxChars = 10_000)
        assertFalse(LogBundle.TRIMMED_NOTE in text)
        assertEquals(1, Regex("one\\n").findAll(text).count())
    }
}
