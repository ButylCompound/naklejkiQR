package pl.almara.inwentaryzacja

import android.app.Activity
import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.File
import java.io.FileOutputStream

object PdfActions {
    fun print(activity: Activity, file: File) {
        val pages = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { it.pageCount }
        }
        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, signal: CancellationSignal?, callback: LayoutResultCallback, extras: Bundle?) {
                if (signal?.isCanceled == true) callback.onLayoutCancelled()
                else callback.onLayoutFinished(PrintDocumentInfo.Builder(file.name)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(pages).build(), true)
            }

            override fun onWrite(pages: Array<out PageRange>?, destination: ParcelFileDescriptor, signal: CancellationSignal?, callback: WriteResultCallback) {
                if (signal?.isCanceled == true) { callback.onWriteCancelled(); return }
                file.inputStream().use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
                if (signal?.isCanceled == true) callback.onWriteCancelled() else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            }
        }
        (activity.getSystemService(Context.PRINT_SERVICE) as PrintManager).print(file.name, adapter, null)
    }
}
