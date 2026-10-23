package pl.almara.inwentaryzacja

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/**
 * Parser zawartości kodu QR z naklejki.
 * Format: "Nazwa produktu | 500 kg | 2026-07-07 10:00:00 | XX | aB3dE_fG".
 * Starsze naklejki mają 3 lub 4 pola. ID służy tylko do wyświetlania;
 * pole raw zachowuje dotychczasowy klucz deduplikacji bez ID.
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
        if (parts.size !in 3..5) return null

        val product = parts[0]
        val quantityPart = parts[1]
        val labelDateRaw = parts[2]
        val initials = parts.getOrElse(3) { "" }
        val labelId = parts.getOrElse(4) { "" }
        if (parts.size == 5 && !Regex("[A-Za-z0-9_-]{8}").matches(labelId)) return null
        if (product.isEmpty()) return null

        // "20 szt." / "500 kg" / "500kg" -> liczba + jednostka (brak jednostki = "kg")
        val match = QUANTITY.matchEntire(quantityPart) ?: return null
        val quantity = match.groupValues[1].replace(',', '.').toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0 } ?: return null
        val unit = Units.normalize(match.groupValues[2])

        val labelDate = normalizeLabelDate(labelDateRaw) ?: return null

        return ScanItem(
            product = product,
            quantity = quantity,
            unit = unit,
            labelDate = labelDate,
            scannedAt = scannedAt,
            initials = initials,
            raw = if (parts.size == 5) {
                val withoutId = raw.substringBeforeLast('|').trimEnd()
                if (initials.isEmpty()) withoutId.substringBeforeLast('|').trimEnd() else withoutId
            } else raw,
            labelId = labelId
        )
    }

    private val QUANTITY = Regex(
        """([+]?(?:\d+(?:[.,]\d*)?|[.,]\d+)(?:[eE][+-]?\d+)?)\s*(kg|szt\.?)?""",
        RegexOption.IGNORE_CASE
    )

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
