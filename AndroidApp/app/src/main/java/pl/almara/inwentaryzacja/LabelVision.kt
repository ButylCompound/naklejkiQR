package pl.almara.inwentaryzacja

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import java.io.IOException

class LabelVision : Closeable {
    private val barcodes = BarcodeScanning.getClient(BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build())
    private val text = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun read(image: InputImage): Task<LabelRead> {
        val codes = barcodes.process(image)
        val words = text.process(image)
        return Tasks.whenAllComplete(codes, words).continueWith {
            if (!codes.isSuccessful && !words.isSuccessful) throw IOException("Label recognition failed", words.exception ?: codes.exception)
            val recognized = if (words.isSuccessful) lines(words.result) else LabelTextResult("", emptyList(), emptyList())
            LabelRead(PzSummary.now(), if (codes.isSuccessful) codes.result.mapNotNull { code ->
                val raw = code.rawBytes?.toString(Charsets.UTF_8) ?: code.rawValue
                raw?.takeIf { it.isNotBlank() }?.let { LabelCode(it, format(code.format)) }
            }.distinct() else emptyList(), recognized.text, ocrWords = recognized.words, ocrLines = recognized.lines)
        }
    }

    private fun lines(text: Text): LabelTextResult {
        return LabelTextLayout.layout(text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val corners = line.cornerPoints?.map { LabelTextPoint(it.x.toDouble(), it.y.toDouble()) }
                ?: line.boundingBox?.let { box -> listOf(LabelTextPoint(box.left.toDouble(), box.top.toDouble()),
                    LabelTextPoint(box.right.toDouble(), box.top.toDouble()), LabelTextPoint(box.right.toDouble(), box.bottom.toDouble()),
                    LabelTextPoint(box.left.toDouble(), box.bottom.toDouble())) } ?: return@mapNotNull null
            LabelTextLine(line.text, line.angle.toDouble(), corners, line.confidence.toDouble(),
                line.elements.map { LabelTextElement(it.text, it.confidence.toDouble()) })
        })
    }

    override fun close() { barcodes.close(); text.close() }

    private fun format(format: Int): String = when (format) {
        Barcode.FORMAT_QR_CODE -> "QR_CODE"
        Barcode.FORMAT_CODE_128 -> "CODE_128"
        Barcode.FORMAT_CODE_39 -> "CODE_39"
        Barcode.FORMAT_CODE_93 -> "CODE_93"
        Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_AZTEC -> "AZTEC"
        Barcode.FORMAT_EAN_13 -> "EAN_13"
        Barcode.FORMAT_EAN_8 -> "EAN_8"
        Barcode.FORMAT_UPC_A -> "UPC_A"
        Barcode.FORMAT_UPC_E -> "UPC_E"
        Barcode.FORMAT_ITF -> "ITF"
        Barcode.FORMAT_CODABAR -> "CODABAR"
        else -> "UNKNOWN"
    }
}
