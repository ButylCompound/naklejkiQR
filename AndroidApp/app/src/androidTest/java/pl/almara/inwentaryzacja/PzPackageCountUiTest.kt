package pl.almara.inwentaryzacja

import android.content.Intent
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PzPackageCountUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun launch(document: String, entry: String? = null) = ActivityScenario.launch<PzEntryActivity>(
        Intent(context, PzEntryActivity::class.java).putExtra(PzDetailActivity.EXTRA_ID, document)
            .putExtra(PzEntryActivity.EXTRA_ENTRY_ID, entry).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun fill(activity: PzEntryActivity) {
        activity.findViewById<AutoCompleteTextView>(R.id.materialInput).setText("Materiał ręczny", false)
        activity.findViewById<EditText>(R.id.lotInput).setText("LOT001")
        activity.findViewById<EditText>(R.id.quantityInput).setText("25")
        activity.findViewById<MaterialButton>(R.id.conditionOk).performClick()
    }

    @Test fun packageCountSurvivesRotationAndEditingChangesOnlyOnePhysicalEntry() {
        val doc = PzStore.create(context)
        val read = LabelRead(PzSummary.now(), emptyList(), "NET 25 KG", values = listOf(
            LabelValue("lot", "LOT001"), LabelValue("quantity", "25", "kg")))
        try {
            launch(doc.id).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals("1", activity.findViewById<EditText>(R.id.packageCountInput).text.toString())
                    activity.applyLabel(read)
                    fill(activity)
                    activity.findViewById<EditText>(R.id.packageCountInput).setText("4")
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals("4", activity.findViewById<EditText>(R.id.packageCountInput).text.toString())
                    activity.findViewById<View>(R.id.saveButton).performClick()
                }
            }
            val saved = PzStore.get(context, doc.id)!!
            assertEquals(4, saved.items.size)
            assertEquals(4, saved.items.map { it.id }.toSet().size)
            assertTrue(saved.items.all { it.label == read })
            assertEquals("100 kg", PzSummary.totals(saved.items))
            val edited = saved.items[1]
            launch(doc.id, edited.id).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.packageCountInputLayout).visibility)
                    activity.findViewById<EditText>(R.id.quantityInput).setText("30")
                    activity.findViewById<View>(R.id.saveButton).performClick()
                }
            }
            val updated = PzStore.get(context, doc.id)!!
            assertEquals(4, updated.items.size)
            assertEquals(saved.items.filter { it.id != edited.id }, updated.items.filter { it.id != edited.id })
            assertEquals("105 kg", PzSummary.totals(updated.items))
        } finally { PzStore.delete(context, doc.id) }
    }

    @Test fun invalidCountsKeepTheFormOpenAndPiecesUsePerPackageAmounts() {
        val doc = PzStore.create(context)
        try {
            launch(doc.id).use { scenario ->
                scenario.onActivity { activity ->
                    fill(activity)
                    val count = activity.findViewById<EditText>(R.id.packageCountInput)
                    val layout = activity.findViewById<TextInputLayout>(R.id.packageCountInputLayout)
                    for (value in listOf("", "0")) {
                        count.setText(value)
                        activity.findViewById<View>(R.id.saveButton).performClick()
                        assertEquals(context.getString(R.string.pz_package_count_required), layout.error.toString())
                        assertTrue(PzStore.get(context, doc.id)!!.items.isEmpty())
                    }
                    count.setText("2")
                    assertNull(layout.error)
                    activity.findViewById<EditText>(R.id.quantityInput).setText("20")
                    activity.findViewById<AutoCompleteTextView>(R.id.unitInput).setText("szt.", false)
                    activity.findViewById<View>(R.id.saveButton).performClick()
                }
            }
            assertEquals("40 szt.", PzSummary.totals(PzStore.get(context, doc.id)!!.items))
        } finally { PzStore.delete(context, doc.id) }
    }
}
