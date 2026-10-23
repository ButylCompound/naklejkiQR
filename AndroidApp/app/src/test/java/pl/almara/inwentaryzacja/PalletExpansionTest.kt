package pl.almara.inwentaryzacja

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PalletExpansionTest {
    private fun sticker(product: String = "MAT (1)", quantity: String = "100 kg", date: String = "2026-10-16 08:00") =
        QrParser.parse("$product | $quantity | $date | QC | aB3dE_fG", "2026-10-16 09:00:00")!!

    @Test
    fun fiveThenThreeAddsEightPallets() {
        val first = PalletExpansion.expand(sticker(), 5)
        val more = PalletExpansion.expand(sticker("MAT (7)"), 3, first)
        assertEquals((1..8).map { "MAT ($it)" }, (first + more).map { it.product })
        assertEquals("800 kg", Format.totals(first + more))
        assertEquals(5, PalletExpansion.matching(sticker(), first).size)
    }

    @Test
    fun addingContinuesAfterTheLargestRemainingNumberFollowingDeletion() {
        val first = PalletExpansion.expand(sticker(), 5).filterNot { it.product == "MAT (2)" }
        val more = PalletExpansion.expand(sticker(), 3, first)
        assertEquals(listOf("MAT (6)", "MAT (7)", "MAT (8)"), more.map { it.product })
        assertEquals(7, (first + more).map { it.key }.toSet().size)
    }

    @Test
    fun addedPalletsUseTheCurrentAlleyAndIdWithoutChangingExistingEntries() {
        val first = PalletExpansion.expand(sticker().copy(alley = "1", labelId = "00000001"), 5)
        val more = PalletExpansion.expand(sticker().copy(alley = "2", labelId = "00000002"), 3, first)
        assertTrue(first.all { it.alley == "1" && it.labelId == "00000001" })
        assertTrue(more.all { it.alley == "2" && it.labelId == "00000002" })
        assertEquals(listOf("MAT (6)", "MAT (7)", "MAT (8)"), more.map { it.product })
    }

    @Test
    fun differentPackingQuantitiesUnitsDatesAndInitialsHaveSeparateCounts() {
        val source = sticker()
        val first = PalletExpansion.expand(source, 5)
        for (other in listOf(
            sticker(quantity = "200 kg"),
            sticker(quantity = "100 szt."),
            sticker(date = "2026-10-17 08:00"),
            source.copy(initials = "AB"),
            sticker(product = "OTHER (1)")
        )) {
            assertTrue(PalletExpansion.matching(other, first).isEmpty())
            assertEquals(PalletName.numbered(PalletName.base(other.product), 1),
                PalletExpansion.expand(other, 1, first).single().product)
        }
    }

    @Test
    fun oldLabelsNewIdsAndUnitAliasesBelongToTheSameDelivery() {
        val old = QrParser.parse("MAT (3) | 100 kg | 2026-10-16 08:00 | QC", "now")!!
        val first = PalletExpansion.expand(old, 5)
        val recent = sticker(quantity = "100 KG").copy(labelId = "00000002")
        assertEquals(5, PalletExpansion.matching(recent, first).size)
        val more = PalletExpansion.expand(recent, 3, first)
        assertEquals("MAT (6)", more.first().product)
        assertTrue(more.all { it.labelId == "00000002" && it.unit == "kg" })
    }

    @Test
    fun idsDoNotChangePalletKeys() {
        val source = sticker()
        val withId = PalletExpansion.expand(source, 2)
        val withoutId = PalletExpansion.expand(source.copy(labelId = ""), 2)
        assertEquals(withoutId.map { it.key }, withId.map { it.key })
    }
}
