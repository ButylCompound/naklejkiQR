package pl.almara.inwentaryzacja

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FuzzyMaterialUiTest {
    @Test fun warningSurvivesRotationAndSaveAndClearsOnManualCorrection() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val previousCatalog = CatalogStore.get(context)
        CatalogStore.replace(context, MaterialCatalog(PzSummary.now(), listOf(
            CatalogMaterial("butyl-test", "BUTYL IIR-1953", "", "", emptyList()))))
        val document = PzStore.create(context)
        val rules = LabelRules.parse(JSONObject(context.assets.open("label-rules.json").bufferedReader().use { it.readText() }))
        val read = rules.apply(LabelRead(PzSummary.now(), emptyList(), "CHAMBROAD\nButyl Rubber\nDR-1953\nLOT NO.260502\n#UNET:1200KG"))
        fun launch(entryId: String? = null) = ActivityScenario.launch<PzEntryActivity>(Intent(context, PzEntryActivity::class.java)
            .putExtra(PzDetailActivity.EXTRA_ID, document.id).putExtra(PzEntryActivity.EXTRA_ENTRY_ID, entryId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun check(activity: PzEntryActivity, approximate: Boolean) {
            val layout = activity.findViewById<TextInputLayout>(R.id.materialInputLayout)
            assertEquals(approximate, layout.hint.toString().contains("⚠"))
            assertEquals(activity.getColor(if (approximate) R.color.field_approximate else android.R.color.white), layout.boxBackgroundColor)
        }
        try {
            launch().use { scenario ->
                scenario.onActivity { activity ->
                    activity.applyLabel(read)
                    assertEquals("BUTYL IIR-1953", activity.findViewById<EditText>(R.id.materialInput).text.toString())
                    check(activity, true)
                }
                instrumentation.waitForIdleSync()
                File(context.getExternalFilesDir("verification"), "pz-fuzzy-material.png").also { file ->
                    file.parentFile!!.mkdirs()
                    file.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    check(activity, true)
                    activity.findViewById<MaterialButton>(R.id.conditionOk).performClick()
                    activity.findViewById<View>(R.id.saveButton).performClick()
                }
            }
            val entry = PzStore.get(context, document.id)!!.items.single()
            assertTrue(entry.approximateMaterial)
            launch(entry.id).use { scenario ->
                scenario.onActivity { activity ->
                    check(activity, true)
                    activity.findViewById<AutoCompleteTextView>(R.id.materialInput).setText("Materiał ręczny", false)
                    check(activity, false)
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    check(activity, false)
                    activity.findViewById<View>(R.id.saveButton).performClick()
                }
            }
            assertFalse(PzStore.get(context, document.id)!!.items.single().approximateMaterial)
        } finally {
            PzStore.delete(context, document.id)
            if (previousCatalog == null) JsonFiles.delete(File(context.filesDir, "material-catalog.json"))
            else CatalogStore.replace(context, previousCatalog)
        }
    }
}
