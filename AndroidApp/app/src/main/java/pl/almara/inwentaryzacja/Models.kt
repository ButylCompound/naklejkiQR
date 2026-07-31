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
    val alley: Int = 1       // alejka ustawiona ręcznie podczas skanowania
)

/** Sesja inwentaryzacji. */
data class Session(
    val id: String,
    val name: String,
    val createdAt: String,
    val items: MutableList<ScanItem>
)

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
    /** "500.0" -> "500", "500.5" -> "500.5" */
    fun number(n: Double): String =
        if (n == Math.floor(n) && !n.isInfinite()) n.toLong().toString() else n.toString()

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
