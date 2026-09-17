package pl.almara.inwentaryzacja

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun trimsFloatingPointNoise() {
        assertEquals("12345.7", Format.number(12345.699999999995))
        assertEquals("0.3", Format.number(0.1 + 0.2))
    }

    @Test
    fun keepsWholeAndDecimalValues() {
        assertEquals("500", Format.number(500.0))
        assertEquals("123.5", Format.number(123.5))
        assertEquals("12.75", Format.number(12.75))
    }
}
