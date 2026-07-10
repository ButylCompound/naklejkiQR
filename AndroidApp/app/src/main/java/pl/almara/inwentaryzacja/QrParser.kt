package pl.almara.inwentaryzacja

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/**
 * Parser zawartości kodu QR z naklejki.
 * Wymagany format (ten sam co w GeneratorNaklejek / Inwentaryzacja/skaner.py):
 *   "Nazwa produktu | 500kg | 2026-07-07 10:00:00 | XX"
 * Wszystkie cztery segmenty są obowiązkowe, a data musi być poprawną
 * datą w formacie yyyy-MM-dd HH:mm:ss — inaczej kod jest odrzucany.
 */
object QrParser {

    private val LABEL_DATE_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)

    fun parse(raw: String, scannedAt: String): ScanItem? {
        val parts = raw.split("|").map { it.trim() }
        if (parts.size != 4) return null

        val (product, weightPart, labelDate, initials) = parts
        if (product.isEmpty() || initials.isEmpty()) return null

        val weight = weightPart
            .replace("kg", "", ignoreCase = true)
            .trim()
            .replace(',', '.')
            .toDoubleOrNull() ?: return null

        if (!isValidLabelDate(labelDate)) return null

        return ScanItem(
            product = product,
            weightKg = weight,
            labelDate = labelDate,
            scannedAt = scannedAt,
            initials = initials,
            raw = raw
        )
    }

    private fun isValidLabelDate(value: String): Boolean =
        try {
            LocalDateTime.parse(value, LABEL_DATE_FORMAT)
            true
        } catch (e: DateTimeParseException) {
            false
        }
}
