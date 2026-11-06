package pl.almara.inwentaryzacja

import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PzLabelVisionTest {
    @Test fun suppliedPhotographsDecodeWithTheBundledOfflineModels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val samples = File(context.filesDir, "sample-labels").listFiles()
            ?.filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png") }?.sortedBy { it.name }.orEmpty()
        assumeTrue(samples.isNotEmpty())
        val rules = LabelRuleStore.get(context)
        val records = JSONArray()
        LabelVision().use { vision ->
            for (file in samples) {
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "Photo: " + file.name + "\n") })
                assertTrue(file.path, file.isFile && file.length() > 0)
                val read = Tasks.await(vision.read(InputImage.fromFilePath(context, Uri.fromFile(file))), 45, TimeUnit.SECONDS)
                val parsed = rules.apply(read)
                records.put(JSONObject().put("file", file.name).put("read", LabelJson.read(parsed)))
                File(context.filesDir, "label-recognition.json").writeText(records.toString(2))
                val missingLot = file.name in setOf("20261027_105836.jpg", "20261027_105843.jpg",
                    "20261027_105725.jpg", "20261027_105738.jpg")
                assertTrue(file.name, parsed.values.any { it.field == "materialName" })
                assertEquals(file.name, !missingLot, parsed.values.any { it.field == "lot" })
                assertEquals(file.name, !file.name.contains("105725") && !file.name.contains("105738"),
                    parsed.values.any { it.field == "quantity" })
            }
        }
        File(context.filesDir, "label-recognition.json").writeText(records.toString(2))
        assertEquals(21, records.length())
        val braskem = (0 until records.length()).map { records.getJSONObject(it) }
            .first { it.getString("file") == "20261027_105831.jpg" }
        val read = LabelJson.read(braskem.getJSONObject("read"))
        assertTrue(read.values.any { it.field == "quantity" && it.value == "175" && it.unit == "kg" })
        assertTrue(read.values.any { it.field == "lot" && it.value == "SPXFPG005E" })
    }
}
