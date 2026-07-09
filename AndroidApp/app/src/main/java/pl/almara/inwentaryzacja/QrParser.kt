package pl.almara.inwentaryzacja

/**
 * Parser zawartości kodu QR z naklejki.
 * Oczekiwany format (ten sam co w GeneratorNaklejek / Inwentaryzacja/skaner.py):
 *   "Nazwa produktu | 500kg | 2026-07-07 10:00:00 | XX"
 * (inicjały operatora na końcu są opcjonalne — starsze naklejki ich nie mają)
 */
object QrParser {

    fun parse(raw: String, scannedAt: String): ScanItem? {
        val parts = raw.split("|").map { it.trim() }
        if (parts.size < 2) return null

        val product = parts[0]
        if (product.isEmpty()) return null

        val weight = parts[1]
            .replace("kg", "", ignoreCase = true)
            .trim()
            .replace(',', '.')
            .toDoubleOrNull() ?: return null

        val labelDate = if (parts.size >= 3) parts[2] else ""
        val initials = if (parts.size >= 4) parts[3] else ""

        return ScanItem(
            product = product,
            weightKg = weight,
            labelDate = labelDate,
            scannedAt = scannedAt,
            initials = initials,
            raw = raw
        )
    }
}
