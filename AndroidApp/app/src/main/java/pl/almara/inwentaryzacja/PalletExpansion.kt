package pl.almara.inwentaryzacja

/**
 * Dodawanie palet z tej samej dostawy z zachowaniem numerów zapisanych pozycji.
 */
object PalletExpansion {

    fun matching(item: ScanItem, existing: List<ScanItem>): List<ScanItem> =
        existing.filter { key(PalletName.base(it.product), it) == key(PalletName.base(item.product), item) }

    fun expand(item: ScanItem, count: Int, existing: List<ScanItem> = emptyList()): List<ScanItem> {
        val base = PalletName.base(item.product)
        val lastNumber = matching(item, existing).maxOfOrNull { PalletName.number(it.product) ?: 0 } ?: 0
        return (1..count).map { offset ->
            val name = PalletName.numbered(base, lastNumber + offset)
            item.copy(product = name, unit = Units.normalize(item.unit), raw = key(name, item.copy(lot = "")))
        }
    }

    fun key(name: String, item: ScanItem): String =
        item.copy(product = name).key
}
