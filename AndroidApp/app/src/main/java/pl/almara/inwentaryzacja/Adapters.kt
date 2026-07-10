package pl.almara.inwentaryzacja

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import pl.almara.inwentaryzacja.databinding.ItemScanItemBinding
import pl.almara.inwentaryzacja.databinding.ItemSessionBinding

/** Wiersz listy sesji (karta z nazwą i podsumowaniem). */
class SessionAdapter(context: Context, private val sessions: List<Session>) :
    ArrayAdapter<Session>(context, 0, sessions) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val binding = if (convertView != null) {
            ItemSessionBinding.bind(convertView)
        } else {
            ItemSessionBinding.inflate(LayoutInflater.from(context), parent, false)
        }
        val s = sessions[position]
        binding.sessionName.text = s.name
        binding.sessionMeta.text = context.getString(
            R.string.session_meta_format,
            s.createdAt, s.items.size, Format.weight(s.items.sumOf { it.weightKg })
        )
        return binding.root
    }
}

/** Wiersz listy zeskanowanych palet (karta z produktem, wagą i datami). */
class ScanItemAdapter(context: Context, private val items: List<ScanItem>) :
    ArrayAdapter<ScanItem>(context, 0, items) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val binding = if (convertView != null) {
            ItemScanItemBinding.bind(convertView)
        } else {
            ItemScanItemBinding.inflate(LayoutInflater.from(context), parent, false)
        }
        val item = items[position]
        binding.itemProduct.text = "${position + 1}. ${item.product}"
        binding.itemWeight.text =
            context.getString(R.string.item_weight_format, Format.weight(item.weightKg))
        binding.itemDates.text = "${item.initials}  •  " + context.getString(
            R.string.item_dates_format, item.labelDate, item.scannedAt
        )
        return binding.root
    }
}
