package pl.almara.inwentaryzacja

/** Jedna zeskanowana paleta. */
data class ScanItem(
    val product: String,
    val weightKg: Double,
    val labelDate: String,   // data z naklejki (kiedy wydrukowano)
    val scannedAt: String,   // kiedy zeskanowano telefonem
    val initials: String,    // inicjały operatora z naklejki (może być puste — starsze naklejki)
    val raw: String          // surowa zawartość kodu QR (klucz deduplikacji)
)

/** Sesja inwentaryzacji. */
data class Session(
    val id: String,
    val name: String,
    val createdAt: String,
    val items: MutableList<ScanItem>
)

object Format {
    /** "500.0" -> "500", "500.5" -> "500.5" */
    fun weight(w: Double): String =
        if (w == Math.floor(w) && !w.isInfinite()) w.toLong().toString() else w.toString()

    /** Wersja do CSV — polski Excel oczekuje przecinka dziesiętnego. */
    fun weightCsv(w: Double): String = weight(w).replace('.', ',')
}
