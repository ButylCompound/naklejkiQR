package pl.almara.inwentaryzacja

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {

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
