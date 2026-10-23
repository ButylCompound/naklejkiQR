package pl.almara.inwentaryzacja

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvExporterTest {
    @Test
    fun reportIncludesIdsAndLeavesLegacyIdsBlank() {
        val base = "Materiał; Łódź | 12,5 kg | 2026-10-16 08:00"
        val scannedAt = "2026-10-16 09:00:00"
        val recent = QrParser.parse("$base | QC | 00000001", scannedAt)!!
        val legacy = QrParser.parse(base, scannedAt)!!
        val session = Session("session", "Test", scannedAt, items = mutableListOf(recent, legacy))
        val lines = CsvExporter.buildCsv(session).removePrefix("\uFEFF").split("\r\n")
        assertEquals("Lp;Alejka;Produkt;Ilość;Jednostka;Inicjały;Data naklejki;Data skanowania;ID", lines[0])
        assertEquals("1;1;\"Materiał; Łódź\";12,5;kg;QC;2026-10-16 08:00:00;$scannedAt;00000001", lines[1])
        assertEquals("2;1;\"Materiał; Łódź\";12,5;kg;;2026-10-16 08:00:00;$scannedAt;", lines[2])
        assertEquals("Liczba palet;2", lines[4])
        assertEquals("Łączna ilość (kg);25", lines[5])
    }
}
