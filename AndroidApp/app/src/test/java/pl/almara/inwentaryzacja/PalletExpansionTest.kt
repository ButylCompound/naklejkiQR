package pl.almara.inwentaryzacja

import org.junit.Assert.assertEquals
import org.junit.Test

class PalletExpansionTest {

    private fun sticker(
        product: String,
        qty: Double = 500.0,
        date: String = "2026-01-01 01:01:01",
        scannedAt: String = "2026-09-02 10:00:00"
    ) = ScanItem(product, qty, "kg", date, scannedAt, "", "qr:$product", "1")

    /** Odwzorowanie deduplikacji SessionStore.addItem (po polu raw). Zwraca liczbę dodanych. */
    private fun addAll(session: MutableList<ScanItem>, entries: List<ScanItem>): Int {
        var added = 0
        for (e in entries) if (session.none { it.raw == e.raw }) {
            session.add(e)
            added++
        }
        return added
    }

    @Test
    fun expandNumbersEntriesAndReplicatesScanData() {
        val entries = PalletExpansion.expand(sticker("MATERIAL XYZ-123 (3)", qty = 250.0), 3)
        assertEquals(
            listOf("MATERIAL XYZ-123 (1)", "MATERIAL XYZ-123 (2)", "MATERIAL XYZ-123 (3)"),
            entries.map { it.product }
        )
        // waga, jednostka i data takie same dla wszystkich palet z tego skanu
        assertEquals(listOf(250.0, 250.0, 250.0), entries.map { it.quantity })
        assertEquals(setOf("2026-01-01 01:01:01"), entries.map { it.labelDate }.toSet())
    }

    @Test
    fun rescanningSameStickerAddsNothing() {
        val session = mutableListOf<ScanItem>()
        val s = sticker("MATERIAL XYZ-123 (3)")
        assertEquals(12, addAll(session, PalletExpansion.expand(s, 12)))
        // ten sam skan ponownie (inny czas skanu nie ma znaczenia)
        val again = sticker("MATERIAL XYZ-123 (3)", scannedAt = "2026-09-02 11:30:00")
        assertEquals(0, addAll(session, PalletExpansion.expand(again, 12)))
        assertEquals(12, session.size)
    }

    @Test
    fun rescanningDifferentStickerFromSameSeriesAddsNothing() {
        val session = mutableListOf<ScanItem>()
        assertEquals(12, addAll(session, PalletExpansion.expand(sticker("MATERIAL XYZ-123 (3)"), 12)))
        // inny numer na naklejce, ta sama seria (baza + waga + data) -> te same klucze
        assertEquals(0, addAll(session, PalletExpansion.expand(sticker("MATERIAL XYZ-123 (7)"), 12)))
        assertEquals(12, session.size)
    }

    @Test
    fun rescanningWithSameCountAddsNothing() {
        val session = mutableListOf<ScanItem>()
        addAll(session, PalletExpansion.expand(sticker("MAT (5)"), 12))
        assertEquals(0, addAll(session, PalletExpansion.expand(sticker("MAT (5)"), 12)))
        assertEquals(12, session.size)
    }

    @Test
    fun rescanningWithFewerPalletsRemovesNothing() {
        val session = mutableListOf<ScanItem>()
        addAll(session, PalletExpansion.expand(sticker("MAT (5)"), 12))
        // mniejsza liczba nie usuwa nadmiarowych wpisów (6..12 zostają)
        assertEquals(0, addAll(session, PalletExpansion.expand(sticker("MAT (5)"), 5)))
        assertEquals(12, session.size)
    }

    @Test
    fun rescanningWithMorePalletsAddsOnlyNewOnes() {
        val session = mutableListOf<ScanItem>()
        addAll(session, PalletExpansion.expand(sticker("MAT (5)"), 12))
        assertEquals(3, addAll(session, PalletExpansion.expand(sticker("MAT (5)"), 15)))
        assertEquals(15, session.size)
        assertEquals((1..15).map { "MAT ($it)" }.toSet(), session.map { it.product }.toSet())
    }

    @Test
    fun differentWeightIsTreatedAsSeparateSeries() {
        val session = mutableListOf<ScanItem>()
        addAll(session, PalletExpansion.expand(sticker("MAT (1)", qty = 500.0), 3))
        // ta sama nazwa, inna waga -> inne klucze -> osobne wpisy
        assertEquals(3, addAll(session, PalletExpansion.expand(sticker("MAT (1)", qty = 600.0), 3)))
        assertEquals(6, session.size)
    }
}
