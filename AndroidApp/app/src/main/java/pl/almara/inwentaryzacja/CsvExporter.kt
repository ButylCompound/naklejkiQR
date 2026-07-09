package pl.almara.inwentaryzacja

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Eksport sesji do pliku CSV zgodnego z polskim Excelem
 * (separator ';', przecinek dziesiętny, BOM UTF-8).
 */
object CsvExporter {

    fun fileName(session: Session): String {
        val safe = session.name
            .replace(Regex("[^\\p{L}\\p{N} _-]"), "")
            .trim()
            .replace(Regex("\\s+"), "_")
        return "inwentaryzacja_${safe.ifEmpty { "sesja" }}.csv"
    }

    fun buildCsv(session: Session): String {
        val sb = StringBuilder()
        sb.append('\uFEFF') // BOM — dzięki temu Excel poprawnie odczyta polskie znaki
        sb.append("Lp;Produkt;Waga (kg);Inicjały;Data naklejki;Data skanowania\r\n")
        session.items.forEachIndexed { i, item ->
            sb.append(i + 1).append(';')
                .append(esc(item.product)).append(';')
                .append(Format.weightCsv(item.weightKg)).append(';')
                .append(esc(item.initials)).append(';')
                .append(esc(item.labelDate)).append(';')
                .append(esc(item.scannedAt)).append("\r\n")
        }
        sb.append("\r\n")
        sb.append("Liczba palet;").append(session.items.size).append("\r\n")
        sb.append("Łączna waga (kg);")
            .append(Format.weightCsv(session.items.sumOf { it.weightKg })).append("\r\n")
        sb.append("Sesja;").append(esc(session.name)).append("\r\n")
        sb.append("Utworzono;").append(esc(session.createdAt)).append("\r\n")
        return sb.toString()
    }

    private fun esc(v: String): String =
        if (v.contains(';') || v.contains('"') || v.contains('\n'))
            "\"" + v.replace("\"", "\"\"") + "\""
        else v

    /**
     * Otwiera systemowe okno udostępniania — operator może wysłać CSV
     * e-mailem (Outlook/Gmail) lub wgrać na SharePoint (aplikacja OneDrive/Teams).
     */
    fun share(context: Context, session: Session) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName(session))
        file.writeText(buildCsv(session), Charsets.UTF_8)

        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.csv_subject, session.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.csv_share_title))
        )
    }
}
