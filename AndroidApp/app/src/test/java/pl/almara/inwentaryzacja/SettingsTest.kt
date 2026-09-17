package pl.almara.inwentaryzacja

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {

    @Test
    fun customAlleyNameRejectsPureNumbers() {
        assertFalse(Settings.isValidCustomAlleyName("5"))
        assertFalse(Settings.isValidCustomAlleyName("01"))
        assertFalse(Settings.isValidCustomAlleyName("007"))
        assertFalse(Settings.isValidCustomAlleyName(" 12 "))
        assertFalse(Settings.isValidCustomAlleyName(""))
        assertFalse(Settings.isValidCustomAlleyName("   "))
    }

    @Test
    fun customAlleyNameAcceptsNonNumeric() {
        assertTrue(Settings.isValidCustomAlleyName("o1"))
        assertTrue(Settings.isValidCustomAlleyName("A5"))
        assertTrue(Settings.isValidCustomAlleyName("rampa"))
        assertTrue(Settings.isValidCustomAlleyName("12b"))
    }

    @Test
    fun rwNumberPadsToTwoDigits() {
        assertEquals("RW/2026/01", Settings.formatRwNumber(2026, 1))
        assertEquals("RW/2026/07", Settings.formatRwNumber(2026, 7))
        assertEquals("RW/2026/99", Settings.formatRwNumber(2026, 99))
    }

    @Test
    fun rwNumberGrowsBeyondTwoDigits() {
        assertEquals("RW/2026/100", Settings.formatRwNumber(2026, 100))
        assertEquals("RW/2026/1234", Settings.formatRwNumber(2026, 1234))
    }
}
