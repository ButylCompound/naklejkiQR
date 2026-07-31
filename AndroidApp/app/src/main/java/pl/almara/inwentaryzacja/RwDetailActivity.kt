package pl.almara.inwentaryzacja

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import pl.almara.inwentaryzacja.databinding.ActivityRwDetailBinding

/** Szczegóły RW: wybór osób, edycja pozycji, zatwierdzenie i wydruk / wysyłka PDF. */
class RwDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RW_ID = "rw_id"
    }

    private lateinit var binding: ActivityRwDetailBinding
    private lateinit var rwId: String
    private lateinit var doc: RwDocument

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRwDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra(EXTRA_RW_ID)
        if (id == null) {
            finish()
            return
        }
        rwId = id

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.requesterButton.setOnClickListener {
            pickPerson(Settings.requesters(this), doc.requester) { chosen ->
                RwStore.setPeople(this, rwId, chosen, doc.manager)
                refresh()
            }
        }
        binding.managerButton.setOnClickListener {
            pickPerson(Settings.managers(this), doc.manager) { chosen ->
                RwStore.setPeople(this, rwId, doc.requester, chosen)
                refresh()
            }
        }
        binding.itemsList.setOnItemClickListener { _, _, position, _ ->
            if (!locked()) editAmount(doc.items[position])
        }
        binding.itemsList.setOnItemLongClickListener { _, _, position, _ ->
            if (!locked()) confirmDeleteItem(doc.items[position])
            true
        }
        binding.resumeButton.setOnClickListener {
            startActivity(
                Intent(this, RwScanActivity::class.java)
                    .putExtra(RwScanActivity.EXTRA_RW_ID, rwId)
                    .putExtra(RwScanActivity.EXTRA_RETURN_ONLY, true)
            )
        }
        binding.printButton.setOnClickListener { finalizeThen { RwPdf.print(this, doc) } }
        binding.sendButton.setOnClickListener { finalizeThen { RwPdf.share(this, doc) } }
        binding.deleteButton.setOnClickListener { confirmDeleteDoc() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val d = RwStore.get(this, rwId)
        if (d == null) {
            finish()
            return
        }
        doc = d
        val locked = d.number.isNotEmpty()
        binding.toolbar.title = d.number.ifEmpty { getString(R.string.rw_draft) }
        binding.toolbar.subtitle = if (locked) getString(R.string.rw_locked) else null
        binding.requesterButton.text =
            getString(R.string.rw_requester_label, d.requester.ifEmpty { getString(R.string.rw_choose) })
        binding.managerButton.text =
            getString(R.string.rw_manager_label, d.manager.ifEmpty { getString(R.string.rw_choose) })
        binding.requesterButton.isEnabled = !locked
        binding.managerButton.isEnabled = !locked
        binding.resumeButton.visibility = if (locked) View.GONE else View.VISIBLE
        binding.itemsList.adapter = RwItemAdapter(this, d.items)
    }

    private fun locked(): Boolean = doc.number.isNotEmpty()

    private fun pickPerson(people: List<String>, current: String, onPick: (String) -> Unit) {
        if (people.isEmpty()) {
            Toast.makeText(this, R.string.rw_no_people, Toast.LENGTH_LONG).show()
            return
        }
        val names = people.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rw_choose_person)
            .setSingleChoiceItems(names, names.indexOf(current)) { dialog, which ->
                onPick(names[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun editAmount(item: ScanItem) {
        AmountPrompt.show(
            context = this,
            product = item.product,
            unit = item.unit,
            prefill = item.quantity,
            onOk = { amount ->
                RwStore.setItemQuantity(this, rwId, item.raw, amount)
                refresh()
            }
        )
    }

    private fun confirmDeleteItem(item: ScanItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_item)
            .setMessage(getString(R.string.delete_item_confirm, item.product))
            .setPositiveButton(R.string.delete) { _, _ ->
                RwStore.deleteItem(this, rwId, item.raw)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Zatwierdza dokument (nadaje numer) i wykonuje akcję, o ile dane są komplet­ne. */
    private fun finalizeThen(action: () -> Unit) {
        if (doc.items.isEmpty()) {
            Toast.makeText(this, R.string.rw_no_items, Toast.LENGTH_SHORT).show()
            return
        }
        if (doc.requester.isEmpty() || doc.manager.isEmpty()) {
            Toast.makeText(this, R.string.rw_pick_people, Toast.LENGTH_SHORT).show()
            return
        }
        if (locked()) {
            action()  // już zatwierdzony — ponowny wydruk / wysyłka bez pytania
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rw_confirm_title)
            .setMessage(R.string.rw_confirm_message)
            .setPositiveButton(R.string.rw_confirm_yes) { _, _ ->
                RwStore.finalize(this, rwId)
                refresh()
                action()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteDoc() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rw_delete)
            .setMessage(getString(R.string.rw_delete_confirm, doc.number.ifEmpty { getString(R.string.rw_draft) }))
            .setPositiveButton(R.string.delete) { _, _ ->
                RwStore.delete(this, rwId)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
