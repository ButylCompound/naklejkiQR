package pl.almara.inwentaryzacja

/** Jedna zeskanowana paleta. */
data class ScanItem(
    val product: String,
    val quantity: Double,
    val unit: String,        // jednostka z naklejki ("kg" lub "szt.")
    val labelDate: String,   // data z naklejki (kiedy wydrukowano)
    val scannedAt: String,   // kiedy zeskanowano telefonem
    val initials: String,    // inicjały operatora z naklejki
    val raw: String,         // surowa zawartość kodu QR (klucz deduplikacji)
    val alley: String = "1"  // alejka ustawiona ręcznie podczas skanowania (numer lub nazwa)
)

/** Sesja inwentaryzacji. */
data class Session(
    val id: String,
    val name: String,
    val createdAt: String,
    val type: String = TYPE_PRODUCT,   // "product" lub "raw" (surowce)
    val items: MutableList<ScanItem>
) {
    companion object {
        const val TYPE_PRODUCT = "product"
        const val TYPE_RAW = "raw"
    }
}

/** Dokument RW — wewnętrzne pobranie materiałów. */
data class RwDocument(
    val id: String,
    val number: String,        // "" dopóki nie zatwierdzono
    val createdAt: String,
    val requester: String,     // zamawiający (kto stworzył zapotrzebowanie)
    val manager: String,       // kierownik zmiany
    val items: MutableList<ScanItem>  // ilość = ilość wydana; labelDate = kod (data dostawy)
)

object Format {
    /**
     * "500.0" -> "500", "500.5" -> "500.5". Zaokrągla do 3 miejsc, żeby suma
     * kilku wag nie pokazywała szumu zmiennoprzecinkowego (np. 12345.6999999).
     */
    fun number(n: Double): String {
        val rounded = Math.round(n * 1000.0) / 1000.0
        return if (rounded == Math.floor(rounded) && !rounded.isInfinite())
            rounded.toLong().toString()
        else
            rounded.toString()
    }

    /** Wersja do CSV — polski Excel oczekuje przecinka dziesiętnego. */
    fun numberCsv(n: Double): String = number(n).replace('.', ',')

    /** Ilość z jednostką: "500 kg", "20 szt." */
    fun quantity(item: ScanItem): String = "${number(item.quantity)} ${item.unit}"

    /** Sumy w rozbiciu na jednostki, np. "1500 kg  •  40 szt." (pusto → "0"). */
    fun totals(items: List<ScanItem>): String =
        items.groupBy { it.unit }
            .map { (unit, group) -> "${number(group.sumOf { it.quantity })} $unit" }
            .joinToString("  •  ")
            .ifEmpty { "0" }
}
