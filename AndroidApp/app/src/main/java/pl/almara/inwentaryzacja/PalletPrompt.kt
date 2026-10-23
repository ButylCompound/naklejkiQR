package pl.almara.inwentaryzacja

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object PalletPrompt {
    fun show(
        context: Context,
        item: ScanItem,
        existingCount: Int,
        onOk: (Int) -> Unit,
        onCancel: () -> Unit = {}
    ) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(3))
            setText("1")
            setSelectAllOnFocus(true)
        }
        val preview = TextView(context)
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
            addView(preview)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(PalletName.base(item.product))
            .setMessage(context.getString(
                R.string.pallet_count_prompt, item.labelDate, Format.quantity(item), existingCount
            ))
            .setView(container)
            .setPositiveButton(R.string.add_pallets, null)
            .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .create()

        dialog.setOnShowListener {
            val add = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            fun updatePreview() {
                val count = input.text.toString().toIntOrNull() ?: 0
                add.isEnabled = count > 0
                add.text = context.getString(R.string.add_pallet_count, count)
                preview.text = if (count > 0) context.getString(
                    R.string.pallet_count_preview,
                    existingCount + count,
                    Format.quantity(item.copy(quantity = (existingCount + count) * item.quantity))
                ) else ""
            }
            input.doAfterTextChanged { updatePreview() }
            updatePreview()
            add.setOnClickListener {
                val count = input.text.toString().toInt()
                dialog.dismiss()
                onOk(count)
            }
        }
        dialog.show()
    }
}
