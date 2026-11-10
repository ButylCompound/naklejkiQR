package pl.almara.inwentaryzacja

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PzEmailShareTest {
    @Test fun attachmentHandlerCanBeSelectedAndReadThePdfFromAnotherApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        val document = PzDocument(UUID.randomUUID().toString(), PzSummary.now(), supplier = "Dostawca", receiver = "Jan",
            deliveryMatches = true, items = listOf(PzEntry(UUID.randomUUID().toString(), null, "PA 6", "0001", "",
                "25".toBigDecimal(), "kg", PzCondition.OK, "", PzSummary.now())),
            number = "PZ/2026/03/0001", phoneNumber = 3, approvedAt = PzSummary.now())
        val received = CountDownLatch(1)
        var result: Intent? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { result = intent; received.countDown() }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(PdfShareReceiverActivity.RESULT_ACTION), ContextCompat.RECEIVER_EXPORTED)
        try {
            val share = PzPdf.emailIntent(context, document)
            val component = ComponentName(testPackage, PdfShareReceiverActivity::class.java.name)
            fun matches(intent: Intent) = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo.packageName == component.packageName && it.activityInfo.name == component.className }
            assertTrue("The PDF attachment handler is offered", matches(share))
            val restricted = Intent(share).apply { selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")) }
            assertFalse("The old mailto selector hides a valid PDF handler", matches(restricted))
            context.startActivity(Intent(share).setComponent(component).putExtra("replyPackage", context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue("The receiving app reports its attachment", received.await(20, TimeUnit.SECONDS))
            val response = requireNotNull(result)
            assertFalse(response.getStringExtra("error"), response.hasExtra("error"))
            assertEquals("%PDF-", response.getStringExtra("signature"))
            assertEquals(document.number + " — " + document.supplier, response.getStringExtra("subject"))
            assertTrue(response.getBooleanExtra("clipMatches", false))
            assertNotEquals(Process.myUid(), response.getIntExtra("receiverUid", -1))
        } finally {
            context.unregisterReceiver(receiver)
            File(context.filesDir, "pz_pdf/" + document.id).deleteRecursively()
        }
    }
}
