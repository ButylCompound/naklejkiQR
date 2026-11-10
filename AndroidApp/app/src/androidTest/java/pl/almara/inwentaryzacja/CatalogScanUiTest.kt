package pl.almara.inwentaryzacja

import android.content.Intent
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CatalogScanUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val material = CatalogMaterial("pib32-id", "PIB32", "", "", emptyList())

    private fun restore(catalog: MaterialCatalog?) {
        if (catalog == null) JsonFiles.delete(File(context.filesDir, "material-catalog.json"))
        else CatalogStore.replace(context, catalog)
    }

    private fun launch(id: String) = ActivityScenario.launch<PzEntryActivity>(Intent(context, PzEntryActivity::class.java)
        .putExtra(PzDetailActivity.EXTRA_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    @Test fun barcodeAndOcrNamesSaveTheCatalogueNameAndIdWhileKeepingOriginalRead() {
        val previous = CatalogStore.get(context)
        CatalogStore.replace(context, MaterialCatalog(PzSummary.now(), listOf(material)))
        val rules = LabelRuleStore.get(context)
        val reads = listOf(
            rules.apply(LabelRead(PzSummary.now(), listOf(LabelCode("PIB32 / SPXFPG005E / 20260721 / 175,000", "QR_CODE")), "")),
            rules.apply(LabelRead(PzSummary.now(), emptyList(), "POLIIZOBUTEN PIB32\nNumer serii: LOT001\nMasa netto: 175 kg")))
        try {
            for (read in reads) {
                val doc = PzStore.create(context)
                try {
                    launch(doc.id).use { scenario ->
                        scenario.onActivity { activity ->
                            activity.applyLabel(read)
                            assertEquals("PIB32", activity.findViewById<EditText>(R.id.materialInput).text.toString())
                            activity.findViewById<EditText>(R.id.lotInput).setText("LOT001")
                        }
                        scenario.recreate()
                        scenario.onActivity { activity ->
                            assertEquals("PIB32", activity.findViewById<EditText>(R.id.materialInput).text.toString())
                            activity.findViewById<MaterialButton>(R.id.conditionOk).performClick()
                            activity.findViewById<View>(R.id.saveButton).performClick()
                        }
                    }
                    val entry = PzStore.get(context, doc.id)!!.items.single()
                    assertEquals("PIB32", entry.materialName)
                    assertEquals(material.id, entry.materialId)
                    assertEquals(read, entry.label)
                } finally { PzStore.delete(context, doc.id) }
            }
        } finally { restore(previous) }
    }

    @Test fun missingUnmatchedAndAmbiguousCataloguesLeaveNameEmptyAndAllowManualEntry() {
        val previous = CatalogStore.get(context)
        val read = LabelRead(PzSummary.now(), emptyList(), "POLIIZOBUTEN PIB32", values = listOf(
            LabelValue("materialName", "POLIIZOBUTEN PIB32"), LabelValue("lot", "LOT001"), LabelValue("quantity", "175", "kg")))
        val catalogues = listOf(null,
            MaterialCatalog(PzSummary.now(), listOf(material.copy(name = "PIB33"))),
            MaterialCatalog(PzSummary.now(), listOf(material, material.copy(id = "duplicate"))))
        try {
            for (catalog in catalogues) {
                restore(catalog)
                val doc = PzStore.create(context)
                try {
                    launch(doc.id).use { scenario ->
                        scenario.onActivity { activity ->
                            activity.applyLabel(read)
                            assertEquals("", activity.findViewById<EditText>(R.id.materialInput).text.toString())
                            assertEquals("LOT001", activity.findViewById<EditText>(R.id.lotInput).text.toString())
                            assertEquals("175", activity.findViewById<EditText>(R.id.quantityInput).text.toString())
                            activity.findViewById<AutoCompleteTextView>(R.id.materialInput).setText("Materiał ręczny", false)
                            activity.applyLabel(read)
                            assertEquals("Materiał ręczny", activity.findViewById<EditText>(R.id.materialInput).text.toString())
                            activity.findViewById<MaterialButton>(R.id.conditionOk).performClick()
                            activity.findViewById<View>(R.id.saveButton).performClick()
                        }
                    }
                    val entry = PzStore.get(context, doc.id)!!.items.single()
                    assertEquals("Materiał ręczny", entry.materialName)
                    assertNull(entry.materialId)
                } finally { PzStore.delete(context, doc.id) }
            }
        } finally { restore(previous) }
    }
}
