package pl.almara.inwentaryzacja

import android.content.ContextWrapper
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PzPdfFormTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext

    private fun entry(lot: String?, quantity: String = "700", damaged: Boolean = false) = PzEntry(
        UUID.randomUUID().toString(), null, "Żywica węglowodorowa PRS A1100", lot,
        if (lot == null) "Etykieta partii nieczytelna. Paleta odłożona do weryfikacji." else "", quantity.toBigDecimal(), "kg",
        if (damaged) PzCondition.DAMAGED else PzCondition.OK,
        if (damaged) "Rozdarcie folii ochronnej na narożniku palety." else "", "2026-10-30T10:15:00+01:00")

    private fun document() = PzDocument(UUID.randomUUID().toString(), "2026-10-30T10:15:00+01:00",
        supplier = "STANDARD Sp. z o.o.", receiver = "Jan Kowalski", invoiceNumber = "FV/1042/2026", deliveryMatches = false,
        deliveryComment = "Dostawa obejmuje jedną paletę mniej niż podano w dokumentacji.",
        items = listOf(entry("2604075"), entry("2604075"), entry("2604020", damaged = true), entry(null)),
        number = "PZ/2026/03/0001", phoneNumber = 3, installationId = UUID.randomUUID().toString(),
        approvedAt = "2026-10-30T11:00:00+01:00")

    private fun checkPdf(document: PzDocument, name: String, check: (File, Int) -> Unit) {
        val root = File(base.cacheDir, "pz-form-tests/" + UUID.randomUUID()).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = root
        }
        try {
            val pdf = PzPdf.build(context, document)
            val count = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    for (index in 0 until renderer.pageCount) renderer.openPage(index).use { page ->
                        assertEquals(595, page.width)
                        assertEquals(842, page.height)
                    }
                    renderer.pageCount
                }
            }
            val artifacts = base.getExternalFilesDir("verification")!!.apply { mkdirs() }
            pdf.copyTo(File(artifacts, name + ".pdf"), overwrite = true)
            File(artifacts, name + ".json").writeText(PzJson.document(document).toString(2))
            check(pdf, count)
            assertArrayEquals(pdf.readBytes(), PzPdf.build(context, document).readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun filledReceivingFormFitsOneA4PageAndKeepsItsIssuedCopy() {
        checkPdf(document(), "pz-form-sample") { _, pages -> assertEquals(1, pages) }
    }

    @Test fun multipleMaterialsAndUnitsUseSeparateLotRowsAndContinueAcrossPages() {
        val preview = document().copy(items = listOf(entry("2604075"), entry("2604075"), entry("2604020"),
            entry("LOT-260502", quantity = "1200").copy(materialName = "Kauczuk butylowy IIR-1953")),
            deliveryMatches = true, deliveryComment = "")
        checkPdf(preview, "pz-form-materials") { _, pages -> assertEquals(1, pages) }
        val entries = (1..36).map { index ->
            if (index <= 24) entry("LOT-%04d".format(index))
            else entry("LOT-%04d".format(index - 24), quantity = "20").copy(materialName = "Elementy opakowaniowe — Łódź", unit = "szt.")
        } + entry("LOT-0001", damaged = true)
        checkPdf(document().copy(items = entries, number = "PZ/2026/03/0002", deliveryMatches = true, deliveryComment = ""),
            "pz-form-many-lots") { _, pages -> assertTrue(pages > 1) }
    }

    @Test fun longCommentsContinueWithoutLosingTheSignatureSection() {
        val doc = document()
        val long = doc.items[2].copy(damageComment = (1..600).joinToString(" ") { "oznaczenie-" + it })
        checkPdf(doc.copy(items = doc.items.map { if (it.id == long.id) long else it }, number = "PZ/2026/03/0003"),
            "pz-form-long-notes") { _, pages -> assertTrue(pages > 1) }
    }
}
