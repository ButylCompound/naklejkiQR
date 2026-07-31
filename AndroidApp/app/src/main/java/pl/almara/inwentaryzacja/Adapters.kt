package pl.almara.inwentaryzacja

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import pl.almara.inwentaryzacja.databinding.ItemPersonBinding
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
            s.createdAt, s.items.size, Format.totals(s.items)
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
        binding.itemWeight.text = Format.quantity(item)
        val who = if (item.initials.isBlank()) "" else "${item.initials}  •  "
        binding.itemDates.text = context.getString(R.string.alley_label, item.alley) +
            "  •  $who" + context.getString(R.string.item_dates_format, item.labelDate, item.scannedAt)
        return binding.root
    }
}

/** Wiersz listy dokumentów RW. */
class RwAdapter(context: Context, private val docs: List<RwDocument>) :
    ArrayAdapter<RwDocument>(context, 0, docs) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val binding = if (convertView != null) {
            ItemSessionBinding.bind(convertView)
        } else {
            ItemSessionBinding.inflate(LayoutInflater.from(context), parent, false)
        }
        val doc = docs[position]
        binding.sessionName.text =
            doc.number.ifEmpty { context.getString(R.string.rw_draft) }
        binding.sessionMeta.text = context.getString(
            R.string.rw_meta_format, doc.createdAt, doc.items.size, doc.requester.ifEmpty { "—" }
        )
        return binding.root
    }
}

/** Wiersz listy osób z przyciskiem usuwania. */
class PersonAdapter(
    context: Context,
    private val names: List<String>,
    private val onDelete: (Int) -> Unit
) : ArrayAdapter<String>(context, 0, names) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val binding = if (convertView != null) {
            ItemPersonBinding.bind(convertView)
        } else {
            ItemPersonBinding.inflate(LayoutInflater.from(context), parent, false)
        }
        binding.personName.text = names[position]
        binding.personDelete.setOnClickListener { onDelete(position) }
        return binding.root
    }
}

/** Wiersz pozycji w dokumencie RW (kod = data dostawy). */
class RwItemAdapter(context: Context, private val items: List<ScanItem>) :
    ArrayAdapter<ScanItem>(context, 0, items) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val binding = if (convertView != null) {
            ItemScanItemBinding.bind(convertView)
        } else {
            ItemScanItemBinding.inflate(LayoutInflater.from(context), parent, false)
        }
        val item = items[position]
        binding.itemProduct.text = "${position + 1}. ${item.product}"
        binding.itemWeight.text = Format.quantity(item)
        binding.itemDates.text = context.getString(R.string.rw_item_code, item.labelDate)
        return binding.root
    }
}
