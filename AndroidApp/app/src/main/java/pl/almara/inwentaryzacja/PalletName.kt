package pl.almara.inwentaryzacja

/**
 * Obsługa numeru palety w nazwie surowca. Naklejki z generatora mają postać
 * "NAZWA (n)" (patrz GeneratorNaklejek/label_utils.py); ręcznie opisane mogą mieć
 * numer na końcu po spacji ("NAZWA 1") lub po myślniku ("NAZWA XYZ123-1"). Numer
 * to zawsze ostatni człon; przy rozbiciu na palety zapisujemy go w formacie
 * generatora "NAZWA (i)". Ewentualne myślniki/liczby wewnątrz nazwy zostają.
 */
object PalletName {

    private val PAREN = Regex("""^(.*?)\s*\((\d+)\)$""")
    private val SUFFIX = Regex("""^(.*?)\s*[-\s]\s*(\d+)$""")

    /** Nazwa bazowa bez numeru palety ("MAT (3)" -> "MAT", "MAT XYZ123-1" -> "MAT XYZ123"). */
    fun base(product: String): String {
        val trimmed = product.trim()
        (PAREN.matchEntire(trimmed) ?: SUFFIX.matchEntire(trimmed))
            ?.let { return it.groupValues[1].trim() }
        return trimmed
    }

    /** Numer palety z naklejki, jeśli obecny — do podpowiedzi liczby palet. */
    fun number(product: String): Int? {
        val trimmed = product.trim()
        return (PAREN.matchEntire(trimmed) ?: SUFFIX.matchEntire(trimmed))
            ?.groupValues?.get(2)?.toIntOrNull()
    }

    /** Nazwa palety w formacie generatora: "NAZWA (i)". */
    fun numbered(base: String, index: Int): String = "$base ($index)"
}
