package pl.almara.inwentaryzacja

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Dialog liczby palet danego surowca (rozbijanej na oddzielne wpisy). */
object PalletPrompt {

    fun show(
        context: Context,
        base: String,
        prefill: Int,
        onOk: (Int) -> Unit,
        onCancel: () -> Unit = {}
    ) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            // maks. 2 cyfry -> 99 palet
            filters = arrayOf(InputFilter.LengthFilter(2))
            setText(prefill.coerceIn(1, 99).toString())
            setSelectAllOnFocus(true)
        }
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        val container = FrameLayout(context).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(base)
            .setMessage(R.string.pallet_count_prompt)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val count = input.text.toString().toIntOrNull()
                if (count != null && count > 0) onOk(count) else onCancel()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }
}
