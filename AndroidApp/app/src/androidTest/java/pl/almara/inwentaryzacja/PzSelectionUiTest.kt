package pl.almara.inwentaryzacja

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
import android.widget.ListView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.card.MaterialCardView
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PzSelectionUiTest {
    @Test fun physicalEntriesShowCheckedStateAndRefreshAfterEachToggleAndRotation() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val document = PzStore.create(context)
        repeat(3) {
            PzStore.putEntry(context, document.id, PzEntry(UUID.randomUUID().toString(), null, "BUTYL IIR-1953",
                "260502", "", "1200".toBigDecimal(), "kg", PzCondition.OK, "", PzSummary.now()))
        }
        fun idle() { instrumentation.waitForIdleSync(); SystemClock.sleep(150); instrumentation.waitForIdleSync() }
        fun row(activity: PzDetailActivity, index: Int): View {
            val list = activity.findViewById<ListView>(R.id.itemsList)
            return list.getChildAt(list.headerViewsCount + index - list.firstVisiblePosition)
        }
        fun check(activity: PzDetailActivity, index: Int, active: Boolean, selected: Boolean) {
            val view = row(activity, index)
            val checkbox = view.findViewById<CheckBox>(R.id.itemSelected)
            assertNotNull("Selection checkbox exists", checkbox)
            assertEquals(if (active) View.VISIBLE else View.GONE, checkbox.visibility)
            assertEquals(selected, checkbox.isChecked)
            assertEquals(activity.getColor(if (selected) R.color.brand_blue_container else android.R.color.white),
                (view as MaterialCardView).cardBackgroundColor.defaultColor)
        }
        fun tap(activity: PzDetailActivity, index: Int) {
            val list = activity.findViewById<ListView>(R.id.itemsList)
            val position = list.headerViewsCount + index
            list.performItemClick(row(activity, index), position, list.adapter.getItemId(position))
        }
        try {
            ActivityScenario.launch<PzDetailActivity>(Intent(context, PzDetailActivity::class.java)
                .putExtra(PzDetailActivity.EXTRA_ID, document.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<ListView>(R.id.itemsList).let { it.setSelection(it.headerViewsCount) }
                }
                idle()
                val xy = IntArray(2)
                scenario.onActivity { activity ->
                    check(activity, 0, false, false)
                    val view = row(activity, 0)
                    view.getLocationOnScreen(xy)
                    xy[0] += view.width / 2
                    xy[1] += view.height / 2
                }
                val time = SystemClock.uptimeMillis()
                instrumentation.sendPointerSync(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, xy[0].toFloat(), xy[1].toFloat(), 0))
                SystemClock.sleep(800)
                instrumentation.sendPointerSync(MotionEvent.obtain(time, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, xy[0].toFloat(), xy[1].toFloat(), 0))
                idle()
                scenario.onActivity { activity ->
                    check(activity, 0, true, true)
                    check(activity, 1, true, false)
                    tap(activity, 1)
                }
                idle()
                scenario.onActivity { activity ->
                    check(activity, 0, true, true)
                    check(activity, 1, true, true)
                }
                File(context.getExternalFilesDir("verification"), "pz-selection.png").also { file ->
                    file.parentFile!!.mkdirs()
                    file.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                scenario.onActivity { tap(it, 0) }
                idle()
                scenario.onActivity { activity ->
                    check(activity, 0, true, false)
                    check(activity, 1, true, true)
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    activity.findViewById<ListView>(R.id.itemsList).let { it.setSelection(it.headerViewsCount) }
                }
                idle()
                scenario.onActivity { activity ->
                    check(activity, 0, true, false)
                    check(activity, 1, true, true)
                    tap(activity, 1)
                }
                idle()
                scenario.onActivity { activity ->
                    check(activity, 0, false, false)
                    check(activity, 1, false, false)
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.toolbar).visibility)
                }
            }
            assertEquals(3, PzStore.get(context, document.id)!!.items.size)
        } finally { PzStore.delete(context, document.id) }
    }
}
