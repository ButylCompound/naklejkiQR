package pl.almara.inwentaryzacja

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeviceIdentityTest {
    @Test fun exportAppScopedIdentityForReinstallCheck() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("pl.almara.inwentaryzacja.debug", context.packageName)
        assertTrue(context.packageManager.getApplicationLabel(context.applicationInfo).toString().startsWith("(debug)"))
        val key = DocumentNumbering.deviceKey(context)
        assertTrue(Regex("[a-f0-9]{64}").matches(key))
        val releaseContext = object : ContextWrapper(context) {
            override fun getPackageName() = "pl.almara.inwentaryzacja"
        }
        assertNotEquals(key, DocumentNumbering.deviceKey(releaseContext))
        File(context.filesDir, "identity-verification.json").writeText(JSONObject()
            .put("deviceKey", key).put("installationId", DocumentNumbering.identity(context).installationId).toString())
    }
}
