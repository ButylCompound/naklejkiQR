package pl.almara.inwentaryzacja

import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LabelStressTest {
    @Test fun recordRecognitionOnAlteredPhotographs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.filesDir, "label-stress")
        val manifest = File(folder, "manifest.json")
        assumeTrue(manifest.isFile)
        val cases = JSONObject(manifest.readText()).getJSONArray("cases")
        val rules = LabelRules.parse(JSONObject(context.assets.open("label-rules.json").bufferedReader().use { it.readText() }))
        val records = JSONArray()
        val output = File(folder, "reads.json")
        LabelVision().use { vision ->
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "Stress ${index + 1}/${cases.length()}: ${case.getString("file")}\n")
                })
                val image = InputImage.fromFilePath(context, Uri.fromFile(File(folder, case.getString("file"))))
                val read = Tasks.await(vision.read(image), 45, TimeUnit.SECONDS)
                records.put(JSONObject().put("file", case.getString("file")).put("read", LabelJson.read(rules.apply(read))))
                output.writeText(records.toString(2))
            }
        }
        assertEquals(cases.length(), records.length())
    }
}
