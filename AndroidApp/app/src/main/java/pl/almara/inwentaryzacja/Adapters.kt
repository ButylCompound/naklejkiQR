package pl.almara.inwentaryzacja

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseExpandableListAdapter
import pl.almara.inwentaryzacja.databinding.ItemAlleyGroupBinding
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
        val typeLabel = context.getString(
            if (s.type == Session.TYPE_RAW) R.string.session_type_raw else R.string.session_type_products
        )
        binding.sessionMeta.text = "$typeLabel  •  " + context.getString(
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

/**
 * Widok pozycji pogrupowany wg alejki (rozwijane listy). Alejki numeryczne
 * najpierw (rosnąco), potem niestandardowe (alfabetycznie); pozycje w grupie
 * zachowują kolejność skanowania.
 */
class AlleyGroupAdapter(private val context: Context, items: List<ScanItem>) :
    BaseExpandableListAdapter() {

    private val byAlley = items.groupBy { it.alley }
    private val groups = byAlley.keys.sortedWith(
        compareBy({ it.toIntOrNull() == null }, { it.toIntOrNull() ?: 0 }, { it })
    )

    private fun childrenOf(group: Int): List<ScanItem> = byAlley.getValue(groups[group])

    override fun getGroupCount() = groups.size
    override fun getChildrenCount(group: Int) = childrenOf(group).size
    override fun getGroup(group: Int) = groups[group]
    override fun getChild(group: Int, child: Int) = childrenOf(group)[child]
    override fun getGroupId(group: Int) = group.toLong()
    override fun getChildId(group: Int, child: Int) = child.toLong()
    override fun hasStableIds() = false
    override fun isChildSelectable(group: Int, child: Int) = false

    override fun getGroupView(group: Int, isExpanded: Boolean, convertView: View?, parent: ViewGroup): View {
        val binding = if (convertView != null) ItemAlleyGroupBinding.bind(convertView)
        else ItemAlleyGroupBinding.inflate(LayoutInflater.from(context), parent, false)
        val children = childrenOf(group)
        binding.groupIndicator.text = if (isExpanded) "▾" else "▸"
        binding.groupTitle.text = context.getString(
            R.string.alley_group_format,
            context.getString(R.string.alley_label, groups[group]),
            children.size,
            Format.totals(children)
        )
        return binding.root
    }

    override fun getChildView(group: Int, child: Int, isLast: Boolean, convertView: View?, parent: ViewGroup): View {
        val binding = if (convertView != null) ItemScanItemBinding.bind(convertView)
        else ItemScanItemBinding.inflate(LayoutInflater.from(context), parent, false)
        val item = childrenOf(group)[child]
        binding.itemProduct.text = "${child + 1}. ${item.product}"
        binding.itemWeight.text = Format.quantity(item)
        val who = if (item.initials.isBlank()) "" else "${item.initials}  •  "
        binding.itemDates.text =
            who + context.getString(R.string.item_dates_format, item.labelDate, item.scannedAt)
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
