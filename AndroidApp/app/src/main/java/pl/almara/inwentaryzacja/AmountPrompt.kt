package pl.almara.inwentaryzacja

import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Dialog do wpisania ilości pobranego materiału (ilość wydana). */
object AmountPrompt {

    fun show(
        context: Context,
        product: String,
        unit: String,
        prefill: Double,
        onOk: (Double) -> Unit,
        onCancel: () -> Unit = {}
    ) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(Format.number(prefill))
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
            .setTitle(product)
            .setMessage(context.getString(R.string.rw_amount_prompt, unit))
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val value = input.text.toString().replace(',', '.').toDoubleOrNull()
                if (value != null && value > 0) onOk(value) else onCancel()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }
}
