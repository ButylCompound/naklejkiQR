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

    @Test fun lotsKeepProductScansAndPalletGroupsSeparateWhileIdsDoNot() {
        val first = QrParser.parse("Materiał (1) | 500 kg | 2026-11-02 08:00 | QC | aB3dE_fG | 0001", "now")!!
        val second = first.copy(lot = "0002")
        assertTrue(first.key != second.key)
        assertTrue(first.sourceKey != second.sourceKey)
        assertEquals(first.key, first.copy(labelId = "00000001").key)
        val existing = PalletExpansion.expand(first, 2) + PalletExpansion.expand(second, 3)
        assertEquals(2, PalletExpansion.matching(first, existing).size)
        assertEquals(3, PalletExpansion.matching(second, existing).size)
        val more = PalletExpansion.expand(first.copy(product = "Materiał (20)"), 1, existing).single()
        assertEquals("Materiał (3)", more.product)
        assertEquals("0001", more.lot)
        assertEquals(first.sourceKey, first.copy(quantity = 10.0).sourceKey)
        assertTrue(first.sourceKey != second.copy(quantity = 10.0).sourceKey)
        assertEquals(5, existing.map { it.sourceKey }.toSet().size)
    }
}
