package pl.almara.inwentaryzacja

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Generuje dokument RW jako PDF (A4) i udostępnia go do druku lub wysyłki.
 * Kolumny odpowiadają papierowemu drukowi: Lp, Kod (data dostawy), Nazwa,
 * Ilość wydana, j.m. Pola nieużywane (cena, wartość, konta) są pominięte.
 */
object RwPdf {

    private const val PAGE_W = 595   // A4 @ 72 dpi (punkty)
    private const val PAGE_H = 842
    private const val MARGIN = 36f
    private const val ROW_H = 20f

    // Krawędzie kolumn
    private const val X_LP = MARGIN
    private const val X_CODE = 70f
    private const val X_NAME = 170f
    private const val X_QTY_END = 500f   // ilość wyrównana do prawej
    private const val X_UNIT = 505f
    private const val X_RIGHT = PAGE_W - MARGIN

    private val title = Paint().apply { textSize = 16f; isFakeBoldText = true }
    private val head = Paint().apply { textSize = 11f; isFakeBoldText = true }
    private val text = Paint().apply { textSize = 11f }
    private val small = Paint().apply { textSize = 9f }
    private val line = Paint().apply { strokeWidth = 0.7f }

    fun fileName(doc: RwDocument): String {
        val id = doc.number.ifEmpty { doc.id.take(8) }
        return "RW_${id.replace('/', '_')}.pdf"
    }

    fun build(context: Context, doc: RwDocument): File {
        val pdf = PdfDocument()
        var pageNo = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
        var y = drawHeader(page.canvas, doc)

        for ((i, item) in doc.items.withIndex()) {
            if (y > PAGE_H - MARGIN - 90f) {
                pdf.finishPage(page)
                pageNo++
                page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
                y = drawTableHead(page.canvas, drawHeaderTitleOnly(page.canvas, doc))
            }
            y = drawRow(page.canvas, i + 1, item, y)
        }

        drawSignatures(page.canvas, doc)
        pdf.finishPage(page)

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName(doc))
        FileOutputStream(file).use { pdf.writeTo(it) }
        pdf.close()
        return file
    }

    private fun drawHeader(c: Canvas, doc: RwDocument): Float {
        val top = drawHeaderTitleOnly(c, doc)
        return drawTableHead(c, top)
    }

    /** Rysuje firmę, tytuł, numer, datę i osoby; zwraca Y pod nagłówkiem. */
    private fun drawHeaderTitleOnly(c: Canvas, doc: RwDocument): Float {
        c.drawText("Almara", MARGIN, MARGIN + 12f, title)
        c.drawText("Rozchód wewnętrzny (RW)", MARGIN, MARGIN + 34f, head)
        c.drawText("Nr: ${doc.number.ifEmpty { "—" }}", X_RIGHT - 160f, MARGIN + 12f, text)
        c.drawText("Data wystawienia: ${doc.createdAt.take(10)}", X_RIGHT - 160f, MARGIN + 28f, text)
        c.drawText("Pobrał: ${doc.requester.ifEmpty { "—" }}", MARGIN, MARGIN + 54f, text)
        c.drawText("Kierownik zmiany: ${doc.manager.ifEmpty { "—" }}", MARGIN, MARGIN + 70f, text)
        return MARGIN + 88f
    }

    private fun drawTableHead(c: Canvas, top: Float): Float {
        c.drawText("Lp", X_LP, top, head)
        c.drawText("Kod (data)", X_CODE, top, head)
        c.drawText("Nazwa materiału", X_NAME, top, head)
        c.drawText("Ilość", X_QTY_END - head.measureText("Ilość"), top, head)
        c.drawText("j.m.", X_UNIT, top, head)
        c.drawLine(MARGIN, top + 5f, X_RIGHT, top + 5f, line)
        return top + ROW_H
    }

    private fun drawRow(c: Canvas, lp: Int, item: ScanItem, y: Float): Float {
        c.drawText(lp.toString(), X_LP, y, text)
        c.drawText(fit(item.labelDate.take(10), text, X_NAME - X_CODE - 6f), X_CODE, y, text)
        c.drawText(fit(item.product, text, X_QTY_END - 40f - X_NAME - 6f), X_NAME, y, text)
        val qty = Format.number(item.quantity)
        c.drawText(qty, X_QTY_END - text.measureText(qty), y, text)
        c.drawText(item.unit, X_UNIT, y, text)
        return y + ROW_H
    }

    private fun drawSignatures(c: Canvas, doc: RwDocument) {
        val y = PAGE_H - MARGIN - 40f
        val cols = listOf(
            "Pobrał" to doc.requester,
            "Kierownik" to doc.manager,
            "Podpis pobierającego" to "",
            "Podpis kierownika" to ""
        )
        val slot = (X_RIGHT - MARGIN) / cols.size
        cols.forEachIndexed { i, (label, name) ->
            val x = MARGIN + i * slot
            c.drawLine(x, y, x + slot - 12f, y, line)
            if (name.isNotEmpty()) c.drawText(fit(name, small, slot - 12f), x, y - 4f, small)
            c.drawText(fit(label, small, slot - 12f), x, y + 14f, small)
        }
    }

    /** Skraca tekst do szerokości kolumny (dodaje „…"). */
    private fun fit(s: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 0 && paint.measureText(s.substring(0, end) + "…") > maxWidth) end--
        return s.substring(0, end) + "…"
    }

    fun share(context: Context, doc: RwDocument) {
        val file = build(context, doc)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.rw_subject, doc.number))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.rw_send_pdf)))
    }

    fun print(activity: Activity, doc: RwDocument) {
        val file = build(activity, doc)
        val pm = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(
                old: PrintAttributes?,
                new: PrintAttributes?,
                signal: CancellationSignal?,
                callback: LayoutResultCallback,
                extras: Bundle?
            ) {
                if (signal?.isCanceled == true) {
                    callback.onLayoutCancelled()
                    return
                }
                callback.onLayoutFinished(
                    PrintDocumentInfo.Builder(file.name)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .build(),
                    true
                )
            }

            override fun onWrite(
                pages: Array<out PageRange>?,
                destination: ParcelFileDescriptor,
                signal: CancellationSignal?,
                callback: WriteResultCallback
            ) {
                file.inputStream().use { input ->
                    FileOutputStream(destination.fileDescriptor).use { output -> input.copyTo(output) }
                }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            }
        }
        pm.print(file.name, adapter, null)
    }
}
