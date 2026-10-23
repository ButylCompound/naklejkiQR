package pl.almara.inwentaryzacja

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialScanningBehaviorTest {
    private fun scan(quantity: String): ScanItem =
        QrParser.parse("Materiał (1) | $quantity | 2026-10-16 08:00 | QC", "2026-10-16 09:00:00")!!

    @Test
    fun sameMaterialAndDateWithDifferentUnitsKeepSeparatePalletKeysAndTotals() {
        val kg = PalletExpansion.expand(scan("10 kg"), 2)
        val pieces = PalletExpansion.expand(scan("10 szt."), 2)
        assertTrue(kg.map { it.raw }.toSet().intersect(pieces.map { it.raw }.toSet()).isEmpty())
        assertEquals("20 kg  •  20 szt.", Format.totals(kg + pieces))
    }

    @Test
    fun reportsKeepMixedUnitsInSeparateRowsAndTotals() {
        val items = (PalletExpansion.expand(scan("10 kg"), 2) +
            PalletExpansion.expand(scan("10 szt."), 3)).toMutableList()
        val session = Session("test", "Mixed units", "2026-10-16 09:00:00", Session.TYPE_RAW, items)
        val rows = CsvExporter.buildCsv(session).split("\r\n")
        assertEquals(listOf("kg", "kg", "szt.", "szt.", "szt."), rows.slice(1..5).map { it.split(';')[4] })
        assertTrue(rows.contains("Łączna ilość (kg);20"))
        assertTrue(rows.contains("Łączna ilość (szt.);30"))
    }

    @Test
    fun palletExpansionTreatsTheLabelQuantityAsQuantityPerPalletForBothUnits() {
        for (unit in listOf("kg", "szt.")) {
            val pallets = PalletExpansion.expand(scan("100 $unit"), 3)
            assertEquals(3, pallets.size)
            assertEquals("300 $unit", Format.totals(pallets))
        }
    }

    @Test
    fun unitSpellingVariantsAreNormalized() {
        val items = listOf("10 kg", "10 KG", "10 szt.", "10 szt").map { scan(it) }
        assertEquals(listOf("kg", "kg", "szt.", "szt."), items.map { it.unit })
        assertEquals("20 kg  •  20 szt.", Format.totals(items))
    }
    @Test
    fun fiveHundredKgDoesNotBlockTwentyPiecesWithTheSameNameAndDate() {
        val kg = scan("500kg")
        val pieces = scan("20 szt.")
        val first = PalletExpansion.expand(kg, 1)
        val more = PalletExpansion.expand(pieces, 1, first)
        assertEquals("500 kg  •  20 szt.", Format.totals(first + more))
        assertEquals(2, (first + more).map { it.key }.toSet().size)
        assertTrue(PalletExpansion.matching(pieces, first).isEmpty())
    }

    @Test
    fun rwIdentityUsesTheLabelQuantityRatherThanTheEditedIssuedQuantity() {
        val kg = scan("500 KG")
        val same = scan("500 kg")
        assertEquals(kg.sourceKey, same.copy(quantity = 25.0).sourceKey)
        assertTrue(kg.sourceKey != scan("20 szt.").sourceKey)
    }

}
