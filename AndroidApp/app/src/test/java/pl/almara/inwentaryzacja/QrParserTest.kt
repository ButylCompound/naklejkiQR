package pl.almara.inwentaryzacja

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Testy parsera skupione na znakach specjalnych w nazwie produktu.
 * Nazwa nie jest w żaden sposób sanityzowana — parser dzieli kod po "|",
 * przycina spacje i przekazuje nazwę dalej bez zmian.
 */
class QrParserTest {

    private val scannedAt = "2026-07-27 09:00:00"

    /** Nazwa musi przejść przez parser bez zmian (poza przycięciem spacji). */
    private fun assertProduct(name: String) {
        val raw = "$name | 500kg | 2026-01-01 01:01:01 | XY"
        val item = QrParser.parse(raw, scannedAt)
        assertEquals("nazwa '$name' powinna przejść bez zmian", name, item?.product)
    }

    @Test
    fun polishDiacritics() {
        assertProduct("Śruba ocynkowana ⌀8")
        assertProduct("Kątownik żółty")
        assertProduct("Zaślepka gwintowana ćwierćcalowa")
        assertProduct("ĄĆĘŁŃÓŚŹŻ ąćęłńóśźż")
    }

    @Test
    fun punctuationAndSymbols() {
        assertProduct("Blacha 2mm (ocynk)")
        assertProduct("Rura 50% x 100")
        assertProduct("Klej \"Super Mocny\"")
        assertProduct("Profil #40 & łącznik")
        assertProduct("Farba - biała / matowa")
        assertProduct("Stal 1.4301, gat. A2")
        assertProduct("Element +/- 0,5mm")
        assertProduct("Kabel 3×2,5")
    }

    @Test
    fun unicodeBeyondLatin() {
        assertProduct("Uszczelka °C odporna")
        assertProduct("Znak €uro test")
        assertProduct("Emoji 📦 karton")
    }

    @Test
    fun interiorAndTrimmedWhitespace() {
        // spacje wewnątrz nazwy zostają, brzegowe są przycinane
        assertProduct("Mat 1")
        assertProduct("Śruba   M8   dużo   spacji")
        val item = QrParser.parse("   Blacha   | 500kg | 2026-01-01 01:01:01 | XY", scannedAt)
        assertEquals("Blacha", item?.product)
    }

    @Test
    fun pipeInNameBreaksParsing() {
        // "|" to separator segmentów — nazwa z pipe rozbija kod na 5 części i jest odrzucana
        val raw = "Profil | 45 | 500kg | 2026-01-01 01:01:01 | XY"
        assertNull(QrParser.parse(raw, scannedAt))
    }

    @Test
    fun specialCharsWithoutInitials() {
        // naklejki na surowce bez inicjałów — nazwa ze znakami specjalnymi, 3 segmenty
        val raw = "Kątownik ⌀8 \"typ A\" | 1234 kg | 2026-01-01 01:01:01"
        val item = QrParser.parse(raw, scannedAt)
        assertEquals("Kątownik ⌀8 \"typ A\"", item?.product)
        assertEquals("", item?.initials)
        assertEquals(1234.0, item?.quantity)
        assertEquals("kg", item?.unit)
    }

    @Test
    fun emptyProductRejected() {
        assertNull(QrParser.parse(" | 500 kg | 2026-01-01 01:01:01 | XY", scannedAt))
    }

    @Test
    fun unitsParsedFromQuantity() {
        // "20 szt." -> 20 + "szt.", "500 kg" -> 500 + "kg"
        val pieces = QrParser.parse("ALBU C1E | 20 szt. | 2026-07-28 13:01:53 | QC", scannedAt)
        assertEquals(20.0, pieces?.quantity)
        assertEquals("szt.", pieces?.unit)

        val kg = QrParser.parse("Blacha | 500 kg | 2026-07-28 13:01:53 | QC", scannedAt)
        assertEquals(500.0, kg?.quantity)
        assertEquals("kg", kg?.unit)
    }

    @Test
    fun legacyNoUnitDefaultsToKg() {
        // starsze naklejki bez spacji i bez jednostki — traktowane jak kilogramy
        val item = QrParser.parse("Mat 1 | 500kg | 2026-01-01 01:01:01 | XY", scannedAt)
        assertEquals(500.0, item?.quantity)
        assertEquals("kg", item?.unit)

        val bare = QrParser.parse("Mat 2 | 42 | 2026-01-01 01:01:01 | XY", scannedAt)
        assertEquals(42.0, bare?.quantity)
        assertEquals("kg", bare?.unit)
    }

    @Test
    fun decimalQuantityWithComma() {
        val item = QrParser.parse("Farba | 12,5 kg | 2026-01-01 01:01:01 | XY", scannedAt)
        assertEquals(12.5, item?.quantity)
        assertEquals("kg", item?.unit)
    }

    @Test
    fun nonNumericQuantityRejected() {
        assertNull(QrParser.parse("Mat 1 | szt. | 2026-01-01 01:01:01 | XY", scannedAt))
    }
}
