package pl.almara.inwentaryzacja

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import pl.almara.inwentaryzacja.databinding.ActivityPzDetailBinding
import pl.almara.inwentaryzacja.databinding.HeaderPzDocumentBinding
import pl.almara.inwentaryzacja.databinding.DialogTextInputBinding

class PzDetailActivity : AppCompatActivity() {
    companion object { const val EXTRA_ID = "pz_id" }
    private lateinit var binding: ActivityPzDetailBinding
    private lateinit var header: HeaderPzDocumentBinding
    private lateinit var selection: ItemSelection
    private lateinit var id: String
    private var doc: PzDocument? = null
    private var loading = false
    private var groups: List<PzGroup> = emptyList()
    private var rowsAdapter: PzRowsAdapter? = null
    private val handler = Handler(Looper.getMainLooper())
    private val save = Runnable { PzUi.guard(this) { saveHeader() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        binding = ActivityPzDetailBinding.inflate(layoutInflater)
        header = HeaderPzDocumentBinding.inflate(layoutInflater, binding.itemsList, false)
        setContentView(binding.root)
        binding.itemsList.addHeaderView(header.root, null, true)
        binding.itemsList.itemsCanFocus = true
        selection = ItemSelection(this, savedInstanceState?.getStringArrayList("selected_items").orEmpty(),
            onChanged = ::updateSelection, onDelete = { keys -> PzUi.guard(this) { PzStore.deleteEntries(this, id, keys); refresh() } })
        binding.toolbar.setNavigationOnClickListener { finish() }
        header.groupSwitch.isChecked = savedInstanceState?.getBoolean("grouped") ?: false
        header.groupSwitch.setOnCheckedChangeListener { _, _ -> showRows() }
        header.supplierInput.setOnItemClickListener { parent, _, position, _ ->
            if (position == parent.adapter.count - 1) {
                header.supplierInput.setText(doc?.supplier.orEmpty(), false)
                editSupplier()
            } else PzUi.guard(this) { saveHeader() }
        }
        header.invoiceInput.doAfterTextChanged { scheduleSave() }
        header.deliveryCommentInput.doAfterTextChanged { scheduleSave() }
        header.matchGroup.addOnButtonCheckedListener { _, _, _ ->
            header.deliveryCommentInputLayout.visibility = if (header.matchNo.isChecked) View.VISIBLE else View.GONE
            scheduleSave()
        }
        header.receiverButton.setOnClickListener { chooseReceiver() }
        binding.addEntryButton.setOnClickListener { PzUi.guard(this) { saveHeader(); openEntry(null) } }
        binding.approveButton.setOnClickListener { PzUi.guard(this) { confirmApproval() } }
        binding.printButton.setOnClickListener { PzUi.guard(this) { doc?.let { PzPdf.print(this, it) } } }
        binding.emailButton.setOnClickListener { PzUi.guard(this) { doc?.let { PzPdf.email(this, it) } } }
        binding.itemsList.setOnItemClickListener { _, _, position, _ ->
            val index = position - binding.itemsList.headerViewsCount
            val current = doc
            if (index >= 0 && current != null) {
                if (header.groupSwitch.isChecked) {
                    val entry = groups[index].entries.first()
                    header.groupSwitch.isChecked = false
                    binding.itemsList.setSelection(binding.itemsList.headerViewsCount + current.items.indexOf(entry))
                } else if (selection.active) selection.toggle(current.items[index].id)
                else if (!current.approved) PzUi.guard(this) { saveHeader(); openEntry(current.items[index].id) }
            }
        }
        binding.itemsList.setOnItemLongClickListener { _, _, position, _ ->
            val current = doc
            val index = position - binding.itemsList.headerViewsCount
            if (current != null && !current.approved && !header.groupSwitch.isChecked && index >= 0) {
                selection.toggle(current.items[index].id)
                true
            } else false
        }
    }

    override fun onResume() { super.onResume(); if (::id.isInitialized) PzUi.guard(this) { refresh() } }

    private fun scheduleSave() {
        if (!loading && doc?.approved == false) { handler.removeCallbacks(save); handler.postDelayed(save, 350) }
    }

    private fun saveHeader() {
        handler.removeCallbacks(save)
        if (doc?.approved != false || loading) return
        val matches = when (header.matchGroup.checkedButtonId) { R.id.matchYes -> true; R.id.matchNo -> false; else -> null }
        doc = PzStore.edit(this, id) { it.copy(supplier = header.supplierInput.text.toString().trim(),
            invoiceNumber = header.invoiceInput.text.toString().trim(), deliveryMatches = matches,
            deliveryComment = if (matches == false) header.deliveryCommentInput.text.toString().trim() else "") }
    }

    private fun refresh() {
        val current = PzStore.get(this, id) ?: run { finish(); return }
        loading = true
        doc = current
        binding.toolbar.title = current.number.ifEmpty { getString(R.string.pz_draft) }
        binding.toolbar.subtitle = if (current.approved) getString(R.string.pz_locked) else null
        header.scanDate.text = getString(R.string.pz_label_value, getString(R.string.pz_scan_date), PzSummary.date(current.createdAt))
        supplierChoices()
        header.supplierInput.setText(current.supplier, false)
        header.invoiceInput.setText(current.invoiceNumber)
        header.receiverButton.text = getString(R.string.pz_label_value, getString(R.string.pz_receiver), current.receiver.ifEmpty { getString(R.string.rw_choose) })
        when (current.deliveryMatches) {
            true -> header.matchGroup.check(R.id.matchYes)
            false -> header.matchGroup.check(R.id.matchNo)
            null -> header.matchGroup.clearChecked()
        }
        header.deliveryCommentInput.setText(current.deliveryComment)
        header.deliveryCommentInputLayout.visibility = if (current.deliveryMatches == false) View.VISIBLE else View.GONE
        header.draftHeader.visibility = if (current.approved) View.GONE else View.VISIBLE
        header.approvedHeader.visibility = if (current.approved) View.VISIBLE else View.GONE
        header.approvedHeader.text = buildList {
            add(getString(R.string.pz_supplier) + ": " + current.supplier)
            add(getString(R.string.pz_receiver) + ": " + current.receiver)
            if (current.invoiceNumber.isNotEmpty()) add(getString(R.string.pz_invoice) + ": " + current.invoiceNumber)
            add(getString(R.string.pz_delivery_matches) + ": " + getString(if (current.deliveryMatches == true) R.string.pz_yes else R.string.pz_no))
            if (current.deliveryMatches == false) add(current.deliveryComment)
        }.joinToString("\n")
        binding.totals.countLabel.setText(R.string.pz_packages)
        binding.totals.statCount.text = current.items.size.toString()
        binding.totals.statWeight.text = PzSummary.totals(current.items)
        header.backupValue.visibility = if (current.approved) View.VISIBLE else View.GONE
        val backupError = PzBackupStore.error(this, id)
        header.backupValue.text = when {
            PzBackupStore.isBackedUp(this, id) -> getString(R.string.pz_backup_complete)
            backupError != null -> getString(R.string.pz_backup_error, backupError)
            else -> getString(R.string.pz_backup_pending)
        }
        binding.addEntryButton.visibility = if (current.approved) View.GONE else View.VISIBLE
        binding.approveButton.visibility = if (current.approved) View.GONE else View.VISIBLE
        binding.approvedActions.visibility = if (current.approved) View.VISIBLE else View.GONE
        loading = false
        if (current.approved) selection.clear() else selection.retainKeys(current.items.map { it.id }.toSet())
        showRows()
    }

    private fun showRows() {
        val current = doc ?: return
        groups = PzSummary.groups(current.items)
        val rows = if (header.groupSwitch.isChecked) groups.map { group ->
            val first = group.entries.first()
            val lot = first.lot ?: getString(R.string.pz_unknown_lot_entry, current.items.indexOf(first) + 1)
            PzRow(first.materialName + "\n" + getString(R.string.pz_lot) + ": " + lot,
                getString(R.string.pz_group_total, group.entries.size, PzSummary.number(group.quantity) + " " + first.unit, group.damagedCount) +
                    if (first.materialId == null) "\n" + getString(R.string.pz_material_manual) else "",
                damaged = group.damagedCount > 0)
        } else current.items.mapIndexed { index, entry ->
            PzRow((index + 1).toString() + ". " + entry.materialName + " — " + PzUi.condition(this, entry),
                PzUi.entryDetails(this, entry), entry.id, entry.condition == PzCondition.DAMAGED)
        }
        rowsAdapter = PzRowsAdapter(this, rows, selection)
        binding.itemsList.adapter = rowsAdapter
    }

    private fun updateSelection() {
        rowsAdapter?.notifyDataSetChanged()
        binding.toolbar.visibility = if (selection.active) View.GONE else View.VISIBLE
        header.groupSwitch.isEnabled = !selection.active
        header.supplierInput.isEnabled = !selection.active
        header.invoiceInput.isEnabled = !selection.active
        header.receiverButton.isEnabled = !selection.active
        header.matchYes.isEnabled = !selection.active
        header.matchNo.isEnabled = !selection.active
        header.deliveryCommentInput.isEnabled = !selection.active
        binding.addEntryButton.isEnabled = !selection.active
        binding.approveButton.isEnabled = !selection.active
    }

    private fun chooseReceiver() {
        val receivers = Settings.receivers(this)
        if (receivers.isEmpty()) {
            MaterialAlertDialogBuilder(this).setMessage(R.string.pz_receiver_list_empty)
                .setPositiveButton(R.string.pz_edit_receivers) { _, _ -> startActivity(Intent(this, PeopleEditActivity::class.java)
                    .putExtra(PeopleEditActivity.EXTRA_LIST, PeopleEditActivity.LIST_RECEIVERS)) }
                .setNegativeButton(R.string.cancel, null).show()
        } else MaterialAlertDialogBuilder(this).setTitle(R.string.pz_receiver).setItems(receivers.toTypedArray()) { _, index ->
            PzUi.guard(this) {
                saveHeader()
                doc = PzStore.edit(this, id) { it.copy(receiver = receivers[index]) }
                header.receiverButton.text = getString(R.string.pz_label_value, getString(R.string.pz_receiver), receivers[index])
            }
        }.show()
    }

    private fun supplierChoices() {
        val choices = (CatalogStore.get(this)?.suppliers.orEmpty() + listOfNotNull(doc?.supplier?.takeIf { it.isNotEmpty() }))
            .distinct().sorted() + getString(R.string.pz_supplier_custom)
        header.supplierInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, choices))
    }

    private fun editSupplier() {
        val input = DialogTextInputBinding.inflate(layoutInflater)
        input.inputLayout.setHint(R.string.pz_supplier)
        input.input.setText(doc?.supplier.orEmpty())
        input.input.doAfterTextChanged { input.inputLayout.error = null }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.pz_supplier_custom).setView(input.root)
            .setPositiveButton(R.string.ok, null).setNegativeButton(R.string.cancel, null).create()
        dialog.setOnShowListener {
            input.input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val value = input.input.text.toString().trim()
                if (value.isEmpty()) input.inputLayout.error = getString(R.string.pz_supplier_required)
                else PzUi.guard(this) {
                    header.supplierInput.setText(value, false)
                    saveHeader()
                    supplierChoices()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun confirmApproval() {
        saveHeader()
        val current = doc ?: return
        current.problem()?.let { throw PzValidationException(it) }
        MaterialAlertDialogBuilder(this).setTitle(R.string.pz_approve).setMessage(R.string.pz_approval_confirm)
            .setPositiveButton(R.string.pz_approve) { _, _ -> PzUi.guard(this) {
                saveHeader()
                PzStore.approve(this, id)
                SyncScheduler.request(this, replace = true)
                refresh()
            } }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun openEntry(entryId: String?) = startActivity(Intent(this, PzEntryActivity::class.java)
        .putExtra(EXTRA_ID, id).putExtra(PzEntryActivity.EXTRA_ENTRY_ID, entryId)
        .putExtra(PzEntryActivity.EXTRA_SCAN, entryId == null))

    override fun onPause() {
        if (::binding.isInitialized) PzUi.guard(this) { saveHeader() }
        handler.removeCallbacks(save)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        PzUi.guard(this) { saveHeader() }
        outState.putStringArrayList("selected_items", selection.saved())
        outState.putBoolean("grouped", header.groupSwitch.isChecked)
        super.onSaveInstanceState(outState)
    }
}
