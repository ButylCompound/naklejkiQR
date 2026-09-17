package pl.almara.inwentaryzacja

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PalletNameTest {

    @Test
    fun baseStripsParenNumber() {
        assertEquals("MATERIAL XYZ-123", PalletName.base("MATERIAL XYZ-123 (3)"))
        assertEquals("MATERIAL XYZ-123", PalletName.base("MATERIAL XYZ-123 (12)"))
        assertEquals("MAT", PalletName.base("MAT(7)"))
    }

    @Test
    fun baseStripsBareTrailingNumber() {
        // spacja przed numerem: "MATERIAL ABC 100 1" -> "MATERIAL ABC 100"
        assertEquals("MATERIAL ABC 100", PalletName.base("MATERIAL ABC 100 1"))
        assertEquals("MAT 12", PalletName.base("MAT 12 34"))
    }

    @Test
    fun baseStripsHyphenNumber() {
        // myślnik bez spacji: "MATERIAL XYZ123-1" -> "MATERIAL XYZ123"
        assertEquals("MATERIAL XYZ123", PalletName.base("MATERIAL XYZ123-1"))
        assertEquals("MATERIAL XYZ123", PalletName.base("MATERIAL XYZ123-12"))
        assertEquals("MATERIAL XYZ123", PalletName.base("MATERIAL XYZ123 - 2"))
    }

    @Test
    fun baseStripsOnlyLastNumberKeepingInternalCode() {
        // wewnętrzny myślnik/liczba w kodzie zostają — usuwamy tylko ostatnią paletę
        assertEquals("MATERIAL XYZ-123", PalletName.base("MATERIAL XYZ-123-1"))
        assertEquals("MATERIAL XYZ-123", PalletName.base("MATERIAL XYZ-123 3"))
        assertEquals("MATERIAL XYZ-123", PalletName.base("MATERIAL XYZ-123 (3)"))
    }

    @Test
    fun baseWithoutTrailingNumberUnchanged() {
        assertEquals("STAL NIERDZEWNA", PalletName.base("STAL NIERDZEWNA"))
        assertNull(PalletName.number("STAL NIERDZEWNA"))
    }

    @Test
    fun noNumberStickerExpandsFromOne() {
        // brak numeru na naklejce — baza bez zmian, podpowiedź liczby palet = 1,
        // rozbicie tworzy "MATERIAL XYZ (1)".. wg podanej liczby
        assertEquals("MATERIAL XYZ", PalletName.base("MATERIAL XYZ"))
        assertNull(PalletName.number("MATERIAL XYZ"))
        assertEquals("MATERIAL XYZ (1)", PalletName.numbered(PalletName.base("MATERIAL XYZ"), 1))
    }

    @Test
    fun numberReadsPalletIndex() {
        assertEquals(3, PalletName.number("MATERIAL XYZ-123 (3)"))
        assertEquals(1, PalletName.number("MATERIAL ABC 100 1"))
        assertEquals(1, PalletName.number("MATERIAL XYZ123-1"))
    }

    @Test
    fun numberedUsesGeneratorFormat() {
        assertEquals("MATERIAL XYZ-123 (1)", PalletName.numbered("MATERIAL XYZ-123", 1))
        assertEquals("MATERIAL XYZ123 (12)", PalletName.numbered("MATERIAL XYZ123", 12))
    }
}
