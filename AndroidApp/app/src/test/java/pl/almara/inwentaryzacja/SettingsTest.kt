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
    fun documentNumberIncludesPhoneAndIndependentModulePrefix() {
        assertEquals("RW/2026/03/0001", DocumentNumbering.format("RW", 2026, 3, 1))
        assertEquals("RW/2026/03/0007", DocumentNumbering.format("RW", 2026, 3, 7))
        assertEquals("PZ/2026/03/0099", DocumentNumbering.format("PZ", 2026, 3, 99))
    }

    @Test
    fun documentNumberGrowsBeyondDisplayPadding() {
        assertEquals("RW/2026/103/0100", DocumentNumbering.format("RW", 2026, 103, 100))
        assertEquals("PZ/2027/03/12345", DocumentNumbering.format("PZ", 2027, 3, 12345))
    }
}
