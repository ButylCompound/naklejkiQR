package pl.almara.inwentaryzacja

import android.content.ActivityNotFoundException
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import pl.almara.inwentaryzacja.databinding.ItemSessionBinding

object PzUi {
    fun message(problem: PzProblem): Int = when (problem) {
        PzProblem.MATERIAL -> R.string.pz_material_required
        PzProblem.QUANTITY -> R.string.pz_quantity_required
        PzProblem.UNIT -> R.string.pz_unit_required
        PzProblem.LOT -> R.string.pz_lot_required
        PzProblem.UNKNOWN_LOT_COMMENT -> R.string.pz_unknown_comment_required
        PzProblem.DAMAGE_COMMENT -> R.string.pz_damage_comment_required
        PzProblem.SUPPLIER -> R.string.pz_supplier_required
        PzProblem.RECEIVER -> R.string.pz_receiver_required
        PzProblem.EMPTY -> R.string.pz_empty_required
        PzProblem.DELIVERY_MATCH -> R.string.pz_match_required
        PzProblem.DELIVERY_COMMENT -> R.string.pz_match_comment_required
    }

    fun error(activity: AppCompatActivity, error: Exception) {
        Log.e("PZ", "Operation failed", error)
        Toast.makeText(activity, when (error) {
            is PzValidationException -> message(error.problem)
            is ActivityNotFoundException -> R.string.pz_no_email_app
            else -> R.string.app_operation_failed
        }, Toast.LENGTH_LONG).show()
    }

    inline fun guard(activity: AppCompatActivity, block: () -> Unit) {
        try { block() } catch (error: Exception) { PzUi.error(activity, error) }
    }

    fun condition(context: Context, entry: PzEntry): String = context.getString(
        if (entry.condition == PzCondition.OK) R.string.pz_ok else R.string.pz_damaged)

    fun entryDetails(context: Context, entry: PzEntry): String = buildList {
        if (entry.materialId == null) add(context.getString(R.string.pz_material_manual))
        add(PzSummary.number(entry.quantity) + " " + entry.unit + "  •  " + context.getString(R.string.pz_lot) + ": " +
            (entry.lot ?: context.getString(R.string.pz_unknown_lot)))
        if (entry.lot == null) add(context.getString(R.string.pz_unknown_lot_comment) + ": " + entry.unknownLotComment)
        if (entry.condition == PzCondition.DAMAGED) add(context.getString(R.string.pz_damage_comment) + ": " + entry.damageComment)
        add(PzSummary.date(entry.scannedAt))
    }.joinToString("\n")
}

data class PzRow(val title: String, val detail: String, val key: String = "", val damaged: Boolean = false)

class PzRowsAdapter(context: Context, rows: List<PzRow>, private val selection: ItemSelection? = null) :
    ArrayAdapter<PzRow>(context, 0, rows) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val binding = if (convertView == null) ItemSessionBinding.inflate(LayoutInflater.from(context), parent, false)
            else ItemSessionBinding.bind(convertView)
        val row = getItem(position)!!
        binding.itemSelected.visibility = if (selection?.active == true && row.key.isNotEmpty()) View.VISIBLE else View.GONE
        binding.itemSelected.isChecked = selection?.contains(row.key) == true
        binding.sessionName.text = row.title
        binding.sessionMeta.text = row.detail
        binding.sessionName.setTextColor(ContextCompat.getColor(context, if (row.damaged) R.color.status_error else R.color.brand_blue))
        binding.root.setCardBackgroundColor(ContextCompat.getColor(context,
            if (selection?.contains(row.key) == true) R.color.brand_blue_container else android.R.color.white))
        return binding.root
    }
}
