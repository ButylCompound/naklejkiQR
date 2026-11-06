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
class OcrConfidenceUiTest {
    @Test fun lowConfidenceFieldsPersistAndManualEditsClearOnlyTheEditedWarning() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val document = PzStore.create(context)
        val rules = LabelRules.parse(JSONObject(context.assets.open("label-rules.json").bufferedReader().use { it.readText() })
            .put("ocrWarningThreshold", 0.8))
        val text = "CHAMBROAD\nButyl Rubber\nDR-1953\nLOT NO.260502\n#UNET:1200KG"
        val words = Regex("\\S+").findAll(text).map { LabelConfidence(it.range.first, it.range.last + 1, 0.4) }.toList()
        val read = rules.apply(LabelRead(PzSummary.now(), emptyList(), text, ocrWords = words))
        val fields = mapOf("materialName" to R.id.materialInputLayout, "lot" to R.id.lotInputLayout,
            "quantity" to R.id.quantityInputLayout, "unit" to R.id.unitInputLayout)
        fun launch(entryId: String? = null) = ActivityScenario.launch<PzEntryActivity>(Intent(context, PzEntryActivity::class.java)
            .putExtra(PzDetailActivity.EXTRA_ID, document.id).putExtra(PzEntryActivity.EXTRA_ENTRY_ID, entryId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun check(activity: PzEntryActivity, warned: Set<String>) {
            for ((name, id) in fields) {
                val layout = activity.findViewById<TextInputLayout>(id)
                assertEquals(name, name in warned, layout.hint.toString().contains("⚠"))
                assertEquals(activity.getColor(if (name in warned) R.color.field_approximate else android.R.color.white), layout.boxBackgroundColor)
            }
        }
        try {
            launch().use { scenario ->
                scenario.onActivity { activity ->
                    activity.applyLabel(read)
                    check(activity, fields.keys)
                    assertTrue(activity.findViewById<TextInputLayout>(R.id.materialInputLayout).helperText.toString()
                        .contains(context.getString(R.string.label_approximate_low_confidence)))
                }
                instrumentation.waitForIdleSync()
                File(context.getExternalFilesDir("verification"), "pz-ocr-confidence.png").also { file ->
                    file.parentFile!!.mkdirs()
                    file.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    check(activity, fields.keys)
                    activity.findViewById<MaterialButton>(R.id.conditionOk).performClick()
                    activity.findViewById<View>(R.id.saveButton).performClick()
                }
            }
            val entry = PzStore.get(context, document.id)!!.items.single()
            assertEquals(fields.keys, entry.lowConfidenceFields)
            assertEquals(words, entry.label!!.ocrWords)
            launch(entry.id).use { scenario ->
                scenario.onActivity { activity ->
                    check(activity, fields.keys)
                    activity.findViewById<EditText>(R.id.lotInput).setText("LOT-MANUAL")
                    check(activity, fields.keys - "lot")
                    activity.findViewById<EditText>(R.id.quantityInput).setText("1195")
                    check(activity, setOf("materialName", "unit"))
                    activity.findViewById<AutoCompleteTextView>(R.id.unitInput).setText("szt.", false)
                    check(activity, setOf("materialName"))
                    activity.findViewById<AutoCompleteTextView>(R.id.materialInput).setText("Materiał ręczny", false)
                    check(activity, emptySet())
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    check(activity, emptySet())
                    activity.findViewById<View>(R.id.saveButton).performClick()
                }
            }
            val corrected = PzStore.get(context, document.id)!!.items.single()
            assertTrue(corrected.lowConfidenceFields.isEmpty())
            assertFalse(corrected.approximateMaterial)
            assertTrue(corrected.label!!.values.any { it.lowConfidence })
        } finally { PzStore.delete(context, document.id) }
    }
}
