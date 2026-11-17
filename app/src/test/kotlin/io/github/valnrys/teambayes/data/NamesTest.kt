package io.github.valnrys.teambayes.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NamesTest {

    @Test
    fun `trims and collapses inner whitespace`() {
        assertEquals("Sam Smith", normaliseName("  Sam   Smith\t"))
        assertEquals("Sam Smith", normaliseName("Sam\u00a0Smith"))
    }

    @Test
    fun `composed and decomposed accents are the same name`() {
        assertEquals(normaliseName("Ren\u00e9"), normaliseName("Rene\u0301"))
        assertEquals("Ren\u00e9", normaliseName("Rene\u0301"))
    }

    @Test
    fun `case is kept`() {
        assertEquals("sAM", normaliseName("sAM"))
    }

    @Test
    fun `blank stays blank`() {
        assertEquals("", normaliseName(" \t "))
    }
}
