package pl.almara.inwentaryzacja

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AtomicFile
import androidx.core.content.FileProvider
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import androidx.core.net.toUri
import androidx.core.util.tryWrite
import java.io.File

object PzPdf {
    fun fileName(doc: PzDocument): String = doc.number.replace('/', '_') + ".pdf"

    @Synchronized
    fun build(context: Context, doc: PzDocument): File {
        check(doc.approved)
        val file = File(context.filesDir, "pz_pdf/" + doc.id + "/" + fileName(doc))
        if (file.exists() || File(file.path + ".bak").exists()) {
            AtomicFile(file).openRead().close()
            return file
        }
        val writer = Pages(context, doc.number)
        try {
            drawForm(context, writer, doc)
            writer.finish()
            AtomicFile(file).tryWrite { writer.pdf.writeTo(it) }
        } finally { writer.pdf.close() }
        return file
    }

    @Synchronized
    fun restore(context: Context, doc: PzDocument, bytes: ByteArray) {
        val file = File(context.filesDir, "pz_pdf/" + doc.id + "/" + fileName(doc))
        AtomicFile(file).tryWrite { it.write(bytes) }
    }

    fun emailIntent(context: Context, doc: PzDocument): Intent {
        val file = build(context, doc)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            selector = Intent(Intent.ACTION_SENDTO, "mailto:".toUri())
            putExtra(Intent.EXTRA_SUBJECT, doc.number + " — " + doc.supplier)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("PDF", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun email(activity: Activity, doc: PzDocument) = activity.startActivity(Intent.createChooser(emailIntent(activity, doc), activity.getString(R.string.pz_email)))
    fun print(activity: Activity, doc: PzDocument) = PdfActions.print(activity, build(activity, doc))

    private data class Cell(val text: String, val alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL, val bold: Boolean = false)
    private data class Row(val cells: List<Cell>, val minHeight: Float = 0f, val repeatFirst: Boolean = false)

    private fun drawForm(context: Context, writer: Pages, doc: PzDocument) {
        fun text(id: Int) = context.getString(id)
        fun row(vararg values: String, height: Float = 0f) = Row(values.map { Cell(it) }, height)
        fun marked(checked: Boolean, label: String) = (if (checked) "☑ " else "☐ ") + label
        val groups = PzSummary.groups(doc.items)
        val materials = groups.groupBy { group ->
            val entry = group.entries.first()
            entry.materialId to if (entry.materialId == null) entry.materialName else null
        }.values.toList()
        val materialNames = materials.map { it.first().entries.first().materialName }
        writer.section(text(R.string.pz_form_details))
        val details = mutableListOf(
            row(text(R.string.pz_form_date), PzSummary.date(doc.createdAt)),
            row(text(R.string.pz_supplier), doc.supplier),
            row(text(R.string.pz_form_material), materialNames.mapIndexed { index, name ->
                if (materials.size > 1) "${index + 1}. $name" else name
            }.joinToString("\n")))
        if (doc.invoiceNumber.isNotEmpty()) details.add(row(text(R.string.pz_form_invoice), doc.invoiceNumber))
        writer.table(listOf(340, 207), listOf(text(R.string.pz_form_field), text(R.string.pz_form_value)),
            details.map { it.copy(repeatFirst = true) }, minHeight = 26f)

        writer.section(text(R.string.pz_form_items))
        val issues = mutableListOf<String>()
        if (doc.deliveryMatches == false) issues.add(context.getString(R.string.pz_form_delivery_issue, doc.deliveryComment))
        var position = 0
        val positions = doc.items.mapIndexed { index, entry -> entry.id to index + 1 }.toMap()
        for ((materialIndex, batches) in materials.withIndex()) {
            val rows = batches.map { group ->
                position++
                val first = group.entries.first()
                val damaged = group.damagedCount
                val ok = group.entries.size - damaged
                val condition = if (ok > 0 && damaged > 0)
                    marked(true, text(R.string.pz_form_ok) + ": " + ok) + "\n" + marked(true, text(R.string.pz_form_damaged) + ": " + damaged)
                else marked(ok > 0, text(R.string.pz_form_ok)) + " " + marked(damaged > 0, text(R.string.pz_form_damaged))
                val hasNotes = group.entries.any { it.lot == null || it.condition == PzCondition.DAMAGED }
                for (entry in group.entries) {
                    val notes = buildList {
                        if (entry.lot == null) add(context.getString(R.string.pz_form_unknown_lot, entry.unknownLotComment))
                        if (entry.condition == PzCondition.DAMAGED) add(context.getString(R.string.pz_form_damage, entry.damageComment))
                    }
                    for (note in notes) issues.add(context.getString(R.string.pz_form_entry_issue, position, positions.getValue(entry.id), note))
                }
                Row(listOf(Cell(position.toString(), Layout.Alignment.ALIGN_CENTER),
                    Cell(first.lot ?: context.getString(R.string.pz_unknown_lot_entry, positions.getValue(first.id))),
                    Cell(PzSummary.number(group.quantity), Layout.Alignment.ALIGN_OPPOSITE),
                    Cell(first.unit, Layout.Alignment.ALIGN_CENTER), Cell(group.entries.size.toString(), Layout.Alignment.ALIGN_CENTER),
                    Cell(condition), Cell(if (hasNotes) text(R.string.pz_form_notes_below) else "")), repeatFirst = true)
            }.toMutableList()
            val entries = batches.flatMap { it.entries }
            rows.add(Row(listOf(Cell(context.getString(R.string.pz_form_material_total, entries.size, PzSummary.totals(entries)), bold = true))))
            val caption = if (materials.size > 1) context.getString(R.string.pz_form_material_reference, materialIndex + 1, materialNames[materialIndex])
                else materialNames[materialIndex]
            writer.table(listOf(24, 82, 82, 32, 64, 115, 148), listOf(text(R.string.pz_form_position), text(R.string.pz_form_lot),
                text(R.string.pz_form_quantity), text(R.string.pz_form_unit), text(R.string.pz_form_packages), text(R.string.pz_form_condition),
                text(R.string.pz_form_notes)), rows, size = 10f, minHeight = 22f, caption = caption, showCaption = materials.size > 1,
                spaceBefore = if (materialIndex > 0) 12f else 0f)
        }
        writer.total(context.getString(R.string.pz_form_delivery_total, doc.items.size, PzSummary.totals(doc.items)))

        writer.section(text(R.string.pz_form_check))
        val matches = marked(doc.deliveryMatches == true, text(R.string.pz_form_yes)) + " " + marked(doc.deliveryMatches == false, text(R.string.pz_form_no))
        writer.table(listOf(280, 120, 147), listOf(text(R.string.pz_form_verification), text(R.string.pz_form_result), text(R.string.pz_form_notes)),
            listOf(row(text(R.string.pz_form_matches), matches, if (doc.deliveryMatches == false) text(R.string.pz_form_notes_below) else ""),
                row(text(R.string.pz_form_description) + "\n\n" + issues.joinToString("\n"), height = 60f)), minHeight = 26f)

        writer.section(text(R.string.pz_form_signatures))
        writer.table(listOf(105, 210, 60, 172), listOf(text(R.string.pz_form_role), text(R.string.pz_form_full_name),
            text(R.string.pz_form_date), text(R.string.pz_form_signature)),
            listOf(row(text(R.string.pz_form_received_by), doc.receiver, "", "", height = 40f)), minHeight = 26f)
    }

    private class Pages(private val context: Context, private val number: String) {
        val pdf = PdfDocument()
        private var pageNumber = 0
        private lateinit var page: PdfDocument.Page
        private var y = 0f
        private var section = ""
        private var sectionPending = false
        private var sectionDrawn = false
        private val border = Paint().apply { color = Color.BLACK; strokeWidth = 0.5f; style = Paint.Style.STROKE }
        private val fill = Paint().apply { color = Color.rgb(242, 242, 242) }
        private val bottom = 802f
        private val padding = 5f
        private val left = 24f
        private val width = 547
        private val titleTop = 32f

        init { nextPage() }

        private fun layout(cell: Cell, width: Int, size: Float): StaticLayout {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = size
                typeface = if (cell.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            return StaticLayout.Builder.obtain(cell.text, 0, cell.text.length, paint, width)
                .setAlignment(cell.alignment).setIncludePad(false).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build()
        }

        private fun nextPage() {
            if (pageNumber > 0) pdf.finishPage(page)
            pageNumber++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
            y = titleTop
            draw(layout(Cell(context.getString(R.string.pz_form_title), Layout.Alignment.ALIGN_CENTER, true), width, 16f), left, y)
            y += 24f
            draw(layout(Cell(number, Layout.Alignment.ALIGN_CENTER), width, 10f), left, y)
            y += 18f
            draw(layout(Cell(number), width - 100, 9f), left, 810f)
            draw(layout(Cell(context.getString(R.string.pz_form_page, pageNumber), Layout.Alignment.ALIGN_OPPOSITE), 100, 9f), left + width - 100, 810f)
            sectionPending = section.isNotEmpty()
        }

        fun section(title: String) {
            section = title
            sectionPending = true
            sectionDrawn = false
        }

        private fun sectionLayout() = layout(Cell(if (sectionDrawn) context.getString(R.string.pz_form_continued, section) else section,
            bold = true), width, 13f)

        private fun sectionHeight() = if (sectionPending) sectionLayout().height + 20f else 0f

        private fun drawSection() {
            if (!sectionPending) return
            y += 12f
            val heading = sectionLayout()
            draw(heading, left, y)
            y += heading.height + 8f
            sectionPending = false
            sectionDrawn = true
        }

        private fun draw(text: StaticLayout, x: Float, top: Float) {
            page.canvas.withTranslation(x, top) { text.draw(this) }
        }

        private fun prepare(height: Float) {
            if (y + sectionHeight() + height > bottom) nextPage()
            drawSection()
        }

        fun total(value: String) {
            val text = layout(Cell(value, bold = true), width, 11f)
            prepare(text.height + 18f)
            y += 12f
            draw(text, left, y)
            y += text.height + 6f
        }

        fun table(widths: List<Int>, headings: List<String>, rows: List<Row>, size: Float = 11.5f, minHeight: Float,
            caption: String? = null, showCaption: Boolean = false, spaceBefore: Float = 0f) {
            val header = headings.mapIndexed { index, value -> layout(Cell(value), widths[index] - 10, size) }
            val headerHeight = maxOf(minHeight, header.maxOf { it.height }.toFloat() + padding * 2)
            val captionText = caption?.let {
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; typeface = Typeface.DEFAULT_BOLD }
                layout(Cell(TextUtils.ellipsize(it, paint, width.toFloat(), TextUtils.TruncateAt.END).toString(), bold = true), width, 11f)
            }
            fun tableHeading(withCaption: Boolean) {
                drawSection()
                if (withCaption && captionText != null) {
                    draw(captionText, left, y)
                    y += captionText.height + 6f
                }
                cells(widths, header, IntArray(header.size), IntArray(header.size) { header[it].lineCount }, headerHeight, shaded = true)
            }
            val captionHeight = if (showCaption && captionText != null) captionText.height + 6f else 0f
            val repeatedHeight = titleTop + 42f + sectionLayout().height + 20f + headerHeight + (captionText?.let { it.height + 6f } ?: 0f)
            val firstRowHeight = rows.firstOrNull()?.let { row ->
                val columns = if (row.cells.size == 1) listOf(widths.sum()) else widths
                maxOf(minHeight, row.minHeight, row.cells.mapIndexed { index, cell -> layout(cell, columns[index] - 10, size).height }
                    .max().toFloat() + padding * 2)
            } ?: 0f
            val requiredRowHeight = if (firstRowHeight <= bottom - repeatedHeight) firstRowHeight else minHeight
            val moved = y + spaceBefore + sectionHeight() + headerHeight + captionHeight + requiredRowHeight > bottom
            if (moved) nextPage() else y += spaceBefore
            tableHeading(showCaption || moved)
            for (row in rows) {
                val columns = if (row.cells.size == 1) listOf(widths.sum()) else widths
                val text = row.cells.mapIndexed { index, cell -> layout(cell, columns[index] - 10, size) }
                drawRow(columns, row, text, maxOf(minHeight, row.minHeight), bottom - repeatedHeight) {
                    nextPage()
                    tableHeading(true)
                }
            }
        }

        private fun drawRow(widths: List<Int>, row: Row, text: List<StaticLayout>, minimum: Float, wholePageHeight: Float = bottom - 120f,
            newPage: () -> Unit) {
            val natural = maxOf(minimum, text.maxOf { it.height }.toFloat() + padding * 2)
            if (y + natural > bottom && natural <= wholePageHeight) newPage()
            var starts = IntArray(text.size)
            while (text.indices.any { starts[it] < text[it].lineCount }) {
                if (bottom - y < padding * 2 + text.maxOf { it.getLineBottom(0) }.toFloat()) newPage()
                val drawStarts = starts.copyOf()
                if (row.repeatFirst && starts[0] >= text[0].lineCount) drawStarts[0] = 0
                val ends = IntArray(text.size) { index ->
                    val cell = text[index]
                    val start = drawStarts[index]
                    var end = start
                    while (end < cell.lineCount && cell.getLineBottom(end) - cell.getLineTop(start) <= bottom - y - padding * 2) end++
                    end
                }
                val height = maxOf(minimum.coerceAtMost(bottom - y), text.indices.maxOf { index ->
                    if (ends[index] > drawStarts[index]) (text[index].getLineBottom(ends[index] - 1) - text[index].getLineTop(drawStarts[index])).toFloat() + padding * 2 else 0f
                })
                cells(widths, text, drawStarts, ends, height)
                starts = IntArray(text.size) { maxOf(starts[it], ends[it]) }
                if (text.indices.any { starts[it] < text[it].lineCount }) newPage()
            }
        }

        private fun cells(widths: List<Int>, text: List<StaticLayout>, starts: IntArray, ends: IntArray, height: Float, shaded: Boolean = false) {
            var x = left
            for (index in widths.indices) {
                val right = x + widths[index]
                if (shaded) page.canvas.drawRect(x, y, right, y + height, fill)
                page.canvas.drawRect(x, y, right, y + height, border)
                if (ends[index] > starts[index]) {
                    page.canvas.withClip(x + padding, y + padding, right - padding, y + height - padding) {
                        withTranslation(x + padding, y + padding - text[index].getLineTop(starts[index])) { text[index].draw(this) }
                    }
                }
                x = right
            }
            y += height
        }

        fun finish() = pdf.finishPage(page)
    }
}
