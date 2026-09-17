package pl.almara.inwentaryzacja

/**
 * Rozbicie skanu surowca na oddzielne palety (1)..(n) w formacie generatora.
 * Klucz deduplikacji palety odtwarza zawartość QR pojedynczej palety, więc nie
 * zależy od numeru na zeskanowanej naklejce — ponowne zeskanowanie tej samej
 * serii (baza, waga, jednostka, data, inicjały) daje te same klucze.
 */
object PalletExpansion {

    fun expand(item: ScanItem, count: Int): List<ScanItem> {
        val base = PalletName.base(item.product)
        return (1..count).map { i ->
            val name = PalletName.numbered(base, i)
            item.copy(product = name, raw = key(name, item))
        }
    }

    fun key(name: String, item: ScanItem): String =
        "$name | ${Format.number(item.quantity)} ${item.unit} | ${item.labelDate} | ${item.initials}"
}
