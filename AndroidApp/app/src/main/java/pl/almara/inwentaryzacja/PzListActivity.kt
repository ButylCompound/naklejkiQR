package pl.almara.inwentaryzacja

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import pl.almara.inwentaryzacja.databinding.ActivityPzListBinding

class PzListActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPzListBinding
    private var documents: List<PzDocument> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPzListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.newPzButton.setOnClickListener { PzUi.guard(this) { open(PzStore.create(this).id) } }
        binding.pzList.setOnItemClickListener { _, _, position, _ -> open(documents[position].id) }
        binding.pzList.setOnItemLongClickListener { _, _, position, _ ->
            val doc = documents[position]
            if (doc.approved) Toast.makeText(this, R.string.pz_locked_delete, Toast.LENGTH_SHORT).show()
            else MaterialAlertDialogBuilder(this).setTitle(R.string.pz_delete).setMessage(R.string.pz_delete_confirm)
                .setPositiveButton(R.string.delete) { _, _ -> PzUi.guard(this) { PzStore.delete(this, doc.id); refresh() } }
                .setNegativeButton(R.string.cancel, null).show()
            true
        }
    }

    override fun onResume() { super.onResume(); PzUi.guard(this) { refresh() } }

    private fun refresh() {
        documents = PzStore.list(this)
        binding.emptyState.visibility = if (documents.isEmpty()) View.VISIBLE else View.GONE
        binding.pzList.adapter = PzRowsAdapter(this, documents.map { doc ->
            val state = if (doc.approved) getString(if (PzBackupStore.isBackedUp(this, doc.id)) R.string.pz_backup_complete else R.string.pz_backup_pending) else getString(R.string.pz_draft)
            PzRow(doc.number.ifEmpty { getString(R.string.pz_draft) } + "  •  " + doc.supplier,
                PzSummary.date(doc.createdAt) + "\n" + getString(R.string.pz_overall_total, doc.items.size, PzSummary.totals(doc.items)) + "\n" + state)
        })
    }

    private fun open(id: String) = startActivity(Intent(this, PzDetailActivity::class.java).putExtra(PzDetailActivity.EXTRA_ID, id))
}
