package pl.almara.inwentaryzacja

import android.content.Intent
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PzUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun catalogueEditUndoAndTypedNamesSaveCorrectIdsAndManualEntriesAreMarked() {
        val previous = CatalogStore.get(context)
        CatalogStore.replace(context, MaterialCatalog("2026-11-05T10:00:00Z", listOf(
            CatalogMaterial("pa6", "PA 6", "", "", emptyList()),
            CatalogMaterial("pa66", "PA 66", "", "", emptyList()),
            CatalogMaterial("duplicate1", "Duplicate", "A", "", emptyList()),
            CatalogMaterial("duplicate2", "Duplicate", "B", "", emptyList()))))
        try {
            for ((name, expectedId) in listOf("PA 6" to "pa6", "  pa   66 " to "pa66", "Custom material" to null, "Duplicate" to null)) {
                val doc = PzStore.create(context)
                try {
                    ActivityScenario.launch<PzEntryActivity>(Intent(context, PzEntryActivity::class.java)
                        .putExtra(PzDetailActivity.EXTRA_ID, doc.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
                        scenario.onActivity { activity ->
                            val material = activity.findViewById<AutoCompleteTextView>(R.id.materialInput)
                            material.onItemClickListener!!.onItemClick(null, null, 0, 0)
                            assertEquals("PA 6", material.text.toString())
                            material.setText("PA 6 edited", false)
                            material.setText(name, false)
                            activity.findViewById<EditText>(R.id.lotInput).setText("0001")
                            activity.findViewById<EditText>(R.id.quantityInput).setText("25")
                            activity.findViewById<MaterialButton>(R.id.conditionOk).performClick()
                        }
                        scenario.recreate()
                        scenario.onActivity { activity ->
                            val layout = activity.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.materialInputLayout)
                            assertEquals(expectedId == null, layout.helperText?.contains(context.getString(R.string.pz_material_manual)) == true)
                            activity.findViewById<View>(R.id.saveButton).performClick()
                        }
                    }
                    val item = PzStore.get(context, doc.id)!!.items.single()
                    assertEquals(name.trim(), item.materialName)
                    assertEquals(expectedId, item.materialId)
                    assertEquals(expectedId == null, PzUi.entryDetails(context, item).contains(context.getString(R.string.pz_material_manual)))
                } finally { PzStore.delete(context, doc.id) }
            }
        } finally {
            if (previous == null) JsonFiles.delete(File(context.filesDir, "material-catalog.json"))
            else CatalogStore.replace(context, previous)
        }
    }

    @Test fun finalizedRwHasNoDeleteOrEditingControlsWhileDraftStillDoes() {
        val draft = RwStore.create(context)
        val finalized = RwStore.create(context)
        RwStore.setPeople(context, finalized.id, "Jan", "Ewa")
        RwStore.putItem(context, finalized.id, QrParser.parse("Materiał | 25 kg | 2026-11-02 08:00", "now")!!)
        RwStore.finalize(context, finalized.id)
        try {
            for ((id, locked) in listOf(draft.id to false, finalized.id to true)) {
                ActivityScenario.launch<RwDetailActivity>(Intent(context, RwDetailActivity::class.java)
                    .putExtra(RwDetailActivity.EXTRA_RW_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
                    scenario.onActivity { activity ->
                        assertEquals(if (locked) View.GONE else View.VISIBLE, activity.findViewById<View>(R.id.deleteButton).visibility)
                        assertEquals(if (locked) View.GONE else View.VISIBLE, activity.findViewById<View>(R.id.resumeButton).visibility)
                        assertEquals(!locked, activity.findViewById<View>(R.id.requesterButton).isEnabled)
                        assertEquals(!locked, activity.findViewById<View>(R.id.managerButton).isEnabled)
                    }
                }
            }
            ActivityScenario.launch<RwListActivity>(Intent(context, RwListActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
                scenario.onActivity { activity ->
                    val list = activity.findViewById<ListView>(R.id.rwList)
                    val position = RwStore.list(context).indexOfFirst { it.id == finalized.id }
                    list.onItemLongClickListener!!.onItemLongClick(list, null, position, 0)
                }
                idle()
                assertTrue(instrumentation.uiAutomation.rootInActiveWindow
                    .findAccessibilityNodeInfosByText(context.getString(R.string.rw_delete_confirm, RwStore.get(context, finalized.id)!!.number)).isEmpty())
                assertNotNull(RwStore.get(context, finalized.id))
            }
        } finally {
            RwStore.delete(context, draft.id)
            JsonFiles.delete(File(context.filesDir, "rw/" + finalized.id + ".json"))
        }
    }

    private fun idle() { instrumentation.waitForIdleSync(); SystemClock.sleep(200); instrumentation.waitForIdleSync() }

    private fun screenshot(name: String) {
        val directory = context.getExternalFilesDir("verification")!!.apply { mkdirs() }
        FileOutputStream(File(directory, name + ".png")).use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun dialogText(text: String) {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || node.isEditable) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        val input = find(instrumentation.uiAutomation.rootInActiveWindow)
        assertNotNull(input)
        assertTrue(input!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
        idle()
    }

    private fun click(label: String) {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString().equals(label, true) || node.contentDescription?.toString().equals(label, true)) {
                var candidate: AccessibilityNodeInfo? = node
                while (candidate != null && !candidate.isClickable) candidate = candidate.parent
                if (candidate?.isEnabled == true) return candidate
            }
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        val node = find(instrumentation.uiAutomation.rootInActiveWindow)
            ?: instrumentation.uiAutomation.windows.firstNotNullOfOrNull { find(it.root) }
        assertNotNull("Clickable UI text: " + label, node)
        assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        idle()
    }

    private fun editEntry(documentId: String, unknown: Boolean, rotate: Boolean = false) {
        val intent = Intent(context, PzEntryActivity::class.java).putExtra(PzDetailActivity.EXTRA_ID, documentId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<PzEntryActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<AutoCompleteTextView>(R.id.materialInput).setText("Materiał PA 6", false)
                activity.findViewById<EditText>(R.id.quantityInput).setText(if (unknown) "25.5" else "100.5")
                activity.findViewById<CheckBox>(R.id.unknownLot).isChecked = unknown
                if (!unknown) activity.findViewById<EditText>(R.id.lotInput).setText("0001")
                activity.findViewById<MaterialButton>(if (unknown) R.id.conditionDamaged else R.id.conditionOk).performClick()
            }
            if (rotate) {
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals("Materiał PA 6", activity.findViewById<EditText>(R.id.materialInput).text.toString())
                    assertEquals("0001", activity.findViewById<EditText>(R.id.lotInput).text.toString())
                    assertEquals("100.5", activity.findViewById<EditText>(R.id.quantityInput).text.toString())
                    assertTrue(activity.findViewById<MaterialButton>(R.id.conditionOk).isChecked)
                }
            }
            if (!unknown) { idle(); screenshot("pz-entry") }
            scenario.onActivity { activity ->
                if (unknown) {
                    activity.findViewById<View>(R.id.saveButton).performClick()
                    assertNotNull(activity.findViewById<EditText>(R.id.unknownLotCommentInput).error)
                    activity.findViewById<EditText>(R.id.unknownLotCommentInput).setText("Nieczytelna etykieta partii")
                    activity.findViewById<View>(R.id.saveButton).performClick()
                    assertNotNull(activity.findViewById<EditText>(R.id.damageCommentInput).error)
                    activity.findViewById<EditText>(R.id.damageCommentInput).setText("Rozerwany worek")
                }
                activity.findViewById<View>(R.id.saveButton).performClick()
            }
            idle()
        }
    }

    @Test fun inventoryAndRwCamerasBindPreviewAndAnalysis() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.CAMERA)
        val session = SessionStore.createSession(context, "Camera test " + UUID.randomUUID())!!
        val rw = RwStore.create(context)
        try {
            val intents = listOf(Intent(context, ScanActivity::class.java).putExtra(ScanActivity.EXTRA_SESSION_ID, session.id),
                Intent(context, RwScanActivity::class.java).putExtra(RwScanActivity.EXTRA_RW_ID, rw.id))
            for (intent in intents) ActivityScenario.launch<androidx.appcompat.app.AppCompatActivity>(
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
                var opened = false
                val deadline = SystemClock.elapsedRealtime() + 15000
                while (!opened && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity {
                        opened = it.findViewById<androidx.camera.view.PreviewView>(R.id.previewView)
                            .previewStreamState.value == androidx.camera.view.PreviewView.StreamState.STREAMING
                    }
                    idle()
                }
                assertTrue(intent.component.toString(), opened)
            }
        } finally {
            SessionStore.deleteSession(context, session.id)
            RwStore.delete(context, rw.id)
        }
    }

    @Test fun labelCameraCapturesAndReturnsToTheEntryForm() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.CAMERA)
        ActivityScenario.launchActivityForResult<PzScanActivity>(Intent(context, PzScanActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            var ready = false
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (!ready && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { ready = it.findViewById<View>(R.id.captureButton).isEnabled }
                idle()
            }
            assertTrue("Camera is ready", ready)
            screenshot("pz-label-camera")
            scenario.onActivity { it.findViewById<View>(R.id.captureButton).performClick() }
            val result = scenario.result
            assertEquals(android.app.Activity.RESULT_OK, result.resultCode)
            val read = LabelJson.read(org.json.JSONObject(result.resultData!!.getStringExtra(PzScanActivity.EXTRA_LABEL)!!))
            assertTrue(read.capturedAt.isNotEmpty())
            val photo = requireNotNull(read.photo)
            try {
                val bytes = LabelPhotos.bytes(context, photo)
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                assertTrue(bitmap.width > 0 && bitmap.height > 0)
                bitmap.recycle()
                LabelVision().use { vision ->
                    val replay = com.google.android.gms.tasks.Tasks.await(vision.read(com.google.mlkit.vision.common.InputImage.fromFilePath(
                        context, android.net.Uri.fromFile(LabelPhotos.file(context, photo.id)))), 45, java.util.concurrent.TimeUnit.SECONDS)
                    assertEquals(read.text, replay.text)
                    assertEquals(read.codes, replay.codes)
                }
            } finally { LabelPhotos.delete(context, photo) }
        }
    }

    @Test fun cancelledAndReplacedEntryPhotosAreRemovedAcrossRotation() {
        fun photo(): LabelPhoto {
            val bitmap = Bitmap.createBitmap(30, 20, Bitmap.Config.ARGB_8888)
            val output = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
            bitmap.recycle()
            val bytes = output.toByteArray()
            return LabelPhoto(UUID.randomUUID().toString(), Digests.sha256(bytes)).also { LabelPhotos.restore(context, it, bytes) }
        }
        val doc = PzStore.create(context)
        val first = photo()
        val second = photo()
        try {
            ActivityScenario.launch<PzEntryActivity>(Intent(context, PzEntryActivity::class.java)
                .putExtra(PzDetailActivity.EXTRA_ID, doc.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
                scenario.onActivity { it.applyLabel(LabelRead(PzSummary.now(), emptyList(), "first", photo = first)) }
                scenario.recreate()
                assertTrue(LabelPhotos.file(context, first.id).exists())
                scenario.onActivity { it.applyLabel(LabelRead(PzSummary.now(), emptyList(), "second", photo = second)) }
                assertFalse(LabelPhotos.file(context, first.id).exists())
                assertTrue(LabelPhotos.file(context, second.id).exists())
            }
            idle()
            assertFalse(LabelPhotos.file(context, second.id).exists())
            assertTrue(PzStore.get(context, doc.id)!!.items.isEmpty())
        } finally { PzStore.delete(context, doc.id); LabelPhotos.delete(context, first); LabelPhotos.delete(context, second) }
    }

    @Test fun scannedSuggestionsAllowManualCorrectionsAndSurviveRescansAndRotation() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val doc = PzStore.create(context)
        val read = LabelRuleStore.get(context).apply(LabelRead(PzSummary.now(),
            listOf(LabelCode("PIB32 / SPXFPG005E / 20260721 / 175,000", "QR_CODE")), ""))
        try {
            ActivityScenario.launch<PzEntryActivity>(Intent(context, PzEntryActivity::class.java)
                .putExtra(PzDetailActivity.EXTRA_ID, doc.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
                scenario.onActivity { activity ->
                    activity.applyLabel(read)
                    assertEquals("Poliizobuten PIB32", activity.findViewById<EditText>(R.id.materialInput).text.toString())
                    assertEquals("SPXFPG005E", activity.findViewById<EditText>(R.id.lotInput).text.toString())
                    assertEquals("175", activity.findViewById<EditText>(R.id.quantityInput).text.toString())
                    assertFalse(activity.findViewById<MaterialButton>(R.id.conditionOk).isChecked)
                    activity.findViewById<AutoCompleteTextView>(R.id.materialInput).setText("Materiał ręczny", false)
                    activity.findViewById<EditText>(R.id.lotInput).setText("POPRAWIONA")
                    activity.findViewById<EditText>(R.id.quantityInput).setText("170")
                    activity.findViewById<AutoCompleteTextView>(R.id.unitInput).setText("szt.", false)
                    activity.applyLabel(read)
                }
                idle(); click(context.getString(R.string.cancel))
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals("Materiał ręczny", activity.findViewById<EditText>(R.id.materialInput).text.toString())
                    assertEquals("POPRAWIONA", activity.findViewById<EditText>(R.id.lotInput).text.toString())
                    assertEquals("170", activity.findViewById<EditText>(R.id.quantityInput).text.toString())
                    assertEquals("szt.", activity.findViewById<EditText>(R.id.unitInput).text.toString())
                    activity.findViewById<MaterialButton>(R.id.conditionOk).performClick()
                    activity.findViewById<View>(R.id.saveButton).performClick()
                }
                idle()
            }
            val entry = PzStore.get(context, doc.id)!!.items.single()
            assertEquals("Materiał ręczny", entry.materialName)
            assertEquals("POPRAWIONA", entry.lot)
            assertEquals("170", PzSummary.number(entry.quantity))
            assertEquals("szt.", entry.unit)
            assertEquals(read, entry.label)
        } finally { PzStore.delete(context, doc.id) }
    }

    @Test fun manualWorkflowRestoresFormsSelectsByPhysicalEntryAndLocksApproval() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val identityFile = File(context.noBackupFilesDir, "document-numbering.json")
        assumeTrue(!identityFile.exists() && Settings.serverUrls(context).isEmpty())
        assumeTrue(PzStore.list(context).isEmpty() && RwStore.list(context).isEmpty() && SessionStore.listSessions(context).isEmpty())
        val previousNames = Settings.receivers(context)
        val previousCatalog = CatalogStore.get(context)
        val doc = PzStore.create(context)
        try {
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
            instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
            Settings.setReceivers(context, listOf("PZ Test Receiver"))
            CatalogStore.replace(context, MaterialCatalog(PzSummary.now(), listOf(
                CatalogMaterial("ui-test", "PA 6", "", "", listOf("Dostawca z katalogu")))))
            editEntry(doc.id, unknown = false, rotate = true)
            editEntry(doc.id, unknown = true)
            val original = PzStore.get(context, doc.id)!!
            assertEquals(2, original.items.size)
            PzStore.putEntry(context, doc.id, original.items.last().copy(id = UUID.randomUUID().toString(),
                quantity = "20".toBigDecimal(), condition = PzCondition.OK, damageComment = ""))
            val intent = Intent(context, PzDetailActivity::class.java).putExtra(PzDetailActivity.EXTRA_ID, doc.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<PzDetailActivity>(intent).use { scenario ->
                scenario.onActivity { it.findViewById<AutoCompleteTextView>(R.id.supplierInput).showDropDown() }
                idle(); screenshot("pz-suppliers"); click("Dostawca z katalogu")
                assertEquals("Dostawca z katalogu", PzStore.get(context, doc.id)!!.supplier)
                scenario.onActivity { it.findViewById<AutoCompleteTextView>(R.id.supplierInput).showDropDown() }
                idle(); click(context.getString(R.string.pz_supplier_custom))
                dialogText("")
                click(context.getString(R.string.ok))
                assertEquals("Dostawca z katalogu", PzStore.get(context, doc.id)!!.supplier)
                dialogText("Dostawca testowy")
                assertTrue(instrumentation.uiAutomation.rootInActiveWindow
                    .findAccessibilityNodeInfosByText(context.getString(R.string.pz_supplier_required)).isEmpty())
                screenshot("pz-custom-supplier"); click(context.getString(R.string.ok))
                assertEquals("Dostawca testowy", PzStore.get(context, doc.id)!!.supplier)
                scenario.onActivity { it.findViewById<AutoCompleteTextView>(R.id.supplierInput).showDropDown() }
                idle(); click(context.getString(R.string.pz_supplier_custom))
                dialogText("Cancelled supplier"); click(context.getString(R.string.cancel))
                assertEquals("Dostawca testowy", PzStore.get(context, doc.id)!!.supplier)
                scenario.onActivity { activity ->
                    activity.findViewById<EditText>(R.id.invoiceInput).setText("0007/2026")
                    activity.findViewById<MaterialButton>(R.id.matchYes).performClick()
                    activity.findViewById<MaterialButton>(R.id.matchNo).performClick()
                    assertFalse(activity.findViewById<MaterialButton>(R.id.matchYes).isChecked)
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.deliveryCommentInputLayout).visibility)
                    activity.findViewById<EditText>(R.id.deliveryCommentInput).setText("Różnica względem dokumentu")
                    activity.findViewById<View>(R.id.receiverButton).performClick()
                }
                idle(); click("PZ Test Receiver")
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals("Dostawca testowy", activity.findViewById<EditText>(R.id.supplierInput).text.toString())
                    assertEquals("0007/2026", activity.findViewById<EditText>(R.id.invoiceInput).text.toString())
                    assertTrue(activity.findViewById<MaterialButton>(R.id.matchNo).isChecked)
                    assertTrue(activity.findViewById<TextView>(R.id.receiverButton).text.contains("PZ Test Receiver"))
                    assertEquals("3", activity.findViewById<TextView>(R.id.statCount).text.toString())
                    assertEquals("146 kg", activity.findViewById<TextView>(R.id.statWeight).text.toString())
                }
                idle(); screenshot("pz-draft")
                scenario.onActivity { activity ->
                    activity.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.groupSwitch).isChecked = true
                    val list = activity.findViewById<ListView>(R.id.itemsList)
                    assertEquals(3 + list.headerViewsCount, list.adapter.count)
                    activity.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.groupSwitch).isChecked = false
                    list.setSelection(list.headerViewsCount)
                }
                idle()
                val xy = IntArray(2)
                scenario.onActivity { activity ->
                    val list = activity.findViewById<ListView>(R.id.itemsList)
                    val row = list.getChildAt(list.headerViewsCount - list.firstVisiblePosition)
                    row.getLocationOnScreen(xy); xy[0] += row.width / 2; xy[1] += row.height / 2
                }
                val time = SystemClock.uptimeMillis()
                instrumentation.sendPointerSync(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, xy[0].toFloat(), xy[1].toFloat(), 0))
                SystemClock.sleep(800)
                instrumentation.sendPointerSync(MotionEvent.obtain(time, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, xy[0].toFloat(), xy[1].toFloat(), 0))
                idle(); click(context.getString(R.string.delete_selected)); click(context.getString(R.string.delete))
                val remaining = PzStore.get(context, doc.id)!!
                assertEquals(2, remaining.items.size)
                assertTrue(remaining.items.all { it.lot == null })
                PzStore.putEntry(context, doc.id, original.items.first().copy(id = UUID.randomUUID().toString()))
                scenario.recreate()
                scenario.onActivity { it.findViewById<View>(R.id.approveButton).performClick() }
                idle(); click(context.getString(R.string.pz_approve))
                val approved = PzStore.get(context, doc.id)!!
                assertTrue(approved.approved)
                assertTrue(approved.number.endsWith("/00/0001"))
                assertEquals(0, approved.phoneNumber)
                assertEquals(3, approved.items.size)
                scenario.onActivity { activity ->
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.addEntryButton).visibility)
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.draftHeader).visibility)
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.printButton).visibility)
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.emailButton).visibility)
                    activity.findViewById<ListView>(R.id.itemsList).setSelection(0)
                }
                idle()
                val artifacts = context.getExternalFilesDir("verification")!!.apply { mkdirs() }
                PzPdf.build(context, approved).copyTo(File(artifacts, "pz-sample.pdf"), overwrite = true)
                File(artifacts, "pz-sample.json").writeText(PzJson.document(approved).toString())
                FileOutputStream(File(artifacts, "pz-approved.png")).use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
                idle(); screenshot("pz-settings")
            }
        } finally {
            WorkManager.getInstance(context).cancelAllWork().result.get()
            JsonFiles.delete(File(context.filesDir, "pz/" + doc.id + ".json"))
            File(context.filesDir, "pz_pdf/" + doc.id).deleteRecursively()
            JsonFiles.delete(identityFile)
            if (previousCatalog == null) JsonFiles.delete(File(context.filesDir, "material-catalog.json"))
            else CatalogStore.replace(context, previousCatalog)
            Settings.setReceivers(context, previousNames)
        }
    }
}
