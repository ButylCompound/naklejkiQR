package pl.almara.inwentaryzacja

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.EditText
import android.widget.ListView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PzTextInputTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun idle() { instrumentation.waitForIdleSync(); SystemClock.sleep(200) }

    private fun tapField(scenario: ActivityScenario<PzDetailActivity>, field: Int) {
        instrumentation.setInTouchMode(true)
        var windowFocused = false
        val focusDeadline = SystemClock.elapsedRealtime() + 10000
        while (!windowFocused && SystemClock.elapsedRealtime() < focusDeadline) {
            idle()
            scenario.onActivity { windowFocused = it.hasWindowFocus() }
        }
        assertTrue("PZ window has focus before tapping", windowFocused)
        scenario.onActivity { activity ->
            WindowInsetsControllerCompat(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
            activity.findViewById<ListView>(R.id.itemsList).setSelection(0)
        }
        SystemClock.sleep(500)
        idle()
        scenario.onActivity { activity ->
            val input = activity.findViewById<EditText>(field)
            assertTrue("Form header is attached", input.isAttachedToWindow)
            input.requestRectangleOnScreen(Rect(0, 0, input.width, input.height), true)
        }
        idle()
        val bounds = Rect()
        scenario.onActivity { activity ->
            val input = activity.findViewById<EditText>(field)
            assertTrue("Input is visible", input.getGlobalVisibleRect(bounds))
        }
        val time = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0))
        val deadline = SystemClock.elapsedRealtime() + 10000
        var keyboardVisible = false
        while (!keyboardVisible && SystemClock.elapsedRealtime() < deadline) {
            idle()
            scenario.onActivity { activity ->
                keyboardVisible = ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
        }
        assertTrue("Software keyboard is visible", keyboardVisible)
        SystemClock.sleep(700)
        scenario.onActivity { activity ->
            val input = activity.findViewById<EditText>(field)
            assertTrue("Input retains focus after the keyboard opens: " + activity.currentFocus, input.hasFocus())
            assertTrue("Focused field stays visible", input.getGlobalVisibleRect(Rect()))
        }
    }

    private fun checkTyping(withEntries: Boolean) {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val doc = PzStore.create(context)
        try {
            if (withEntries) repeat(20) { index ->
                PzStore.putEntry(context, doc.id, PzEntry(UUID.randomUUID().toString(), null, "Materiał " + index,
                    "LOT-" + index, "", "25".toBigDecimal(), "kg", PzCondition.OK, "", PzSummary.now()))
            }
            ActivityScenario.launch<PzDetailActivity>(Intent(context, PzDetailActivity::class.java)
                .putExtra(PzDetailActivity.EXTRA_ID, doc.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
                idle()
                scenario.onActivity { it.findViewById<MaterialButton>(R.id.matchNo).performClick() }
                idle()
                tapField(scenario, R.id.invoiceInput)
                instrumentation.sendStringSync("FV/2026/001")
                SystemClock.sleep(500)
                scenario.onActivity { activity ->
                    assertEquals("FV/2026/001", activity.findViewById<EditText>(R.id.invoiceInput).text.toString())
                    assertTrue(activity.findViewById<EditText>(R.id.invoiceInput).hasFocus())
                }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                idle()
                tapField(scenario, R.id.deliveryCommentInput)
                instrumentation.sendStringSync("Missing package")
                repeat(5) {
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
                    instrumentation.sendStringSync("Another delivery issue")
                }
                SystemClock.sleep(500)
                val comment = "Missing package" + "\nAnother delivery issue".repeat(5)
                scenario.onActivity { activity ->
                    assertEquals(comment, activity.findViewById<EditText>(R.id.deliveryCommentInput).text.toString())
                    assertTrue(activity.findViewById<EditText>(R.id.deliveryCommentInput).hasFocus())
                }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                idle()
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals("FV/2026/001", activity.findViewById<EditText>(R.id.invoiceInput).text.toString())
                    assertEquals(comment, activity.findViewById<EditText>(R.id.deliveryCommentInput).text.toString())
                }
                assertEquals("FV/2026/001", PzStore.get(context, doc.id)!!.invoiceNumber)
                assertEquals(comment, PzStore.get(context, doc.id)!!.deliveryComment)
            }
        } finally { PzStore.delete(context, doc.id) }
    }

    @Test fun invoiceAndDeliveryCommentKeepFocusAndSaveInAnEmptyPz() = checkTyping(false)
    @Test fun invoiceAndDeliveryCommentKeepFocusAndSaveWithPalletRows() = checkTyping(true)
}

