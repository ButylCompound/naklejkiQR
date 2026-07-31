package pl.almara.inwentaryzacja

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/**
 * Parser zawartości kodu QR z naklejki.
 * Format (ten sam co w GeneratorNaklejek / Inwentaryzacja/skaner.py):
 *   "Nazwa produktu | 500 kg | 2026-07-07 10:00:00 | XX"
 * Jednostka ("kg" lub "szt.") jest częścią segmentu ilości; brak jednostki
 *   (starsze naklejki "500kg") traktujemy jako "kg".
 * Inicjały operatora są opcjonalne — naklejki na surowce mogą ich nie mieć
 *   ("Mat 1 | 1234 kg | 2026-01-01 01:01:01"); wtedy inicjały są puste.
 * Data akceptowana jest z sekundami (yyyy-MM-dd HH:mm:ss) lub bez nich
 * (yyyy-MM-dd HH:mm — starsze naklejki); zapisywana jest znormalizowana, z ":00".
 */
object QrParser {

    private val WITH_SECONDS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
    private val WITHOUT_SECONDS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)

    fun parse(raw: String, scannedAt: String): ScanItem? {
        val parts = raw.split("|").map { it.trim() }
        if (parts.size != 3 && parts.size != 4) return null

        val product = parts[0]
        val quantityPart = parts[1]
        val labelDateRaw = parts[2]
        val initials = parts.getOrElse(3) { "" }
        if (product.isEmpty()) return null

        // "20 szt." / "500 kg" / "500kg" -> liczba + jednostka (brak jednostki = "kg")
        val match = QUANTITY.find(quantityPart) ?: return null
        val quantity = match.groupValues[1].replace(',', '.').toDouble()
        val unit = match.groupValues[2].trim().ifEmpty { "kg" }

        val labelDate = normalizeLabelDate(labelDateRaw) ?: return null

        return ScanItem(
            product = product,
            quantity = quantity,
            unit = unit,
            labelDate = labelDate,
            scannedAt = scannedAt,
            initials = initials,
            raw = raw
        )
    }

    private val QUANTITY = Regex("""^(\d+(?:[.,]\d+)?)\s*(.*)$""")

    private fun normalizeLabelDate(value: String): String? {
        val parsed = parseStrict(value, WITH_SECONDS) ?: parseStrict(value, WITHOUT_SECONDS)
        return parsed?.format(WITH_SECONDS)
    }

    private fun parseStrict(value: String, format: DateTimeFormatter): LocalDateTime? =
        try {
            LocalDateTime.parse(value, format)
        } catch (e: DateTimeParseException) {
            null
        }
}
