package pl.almara.inwentaryzacja

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import pl.almara.inwentaryzacja.databinding.ActivityRwListBinding

/** Lista dokumentów RW z możliwością utworzenia nowego. */
class RwListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRwListBinding
    private var docs: List<RwDocument> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRwListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.newRwButton.setOnClickListener {
            val doc = RwStore.create(this)
            openScan(doc.id)
        }
        binding.rwList.setOnItemClickListener { _, _, position, _ ->
            docs.getOrNull(position)?.let { openDetail(it.id) }
        }
        binding.rwList.setOnItemLongClickListener { _, _, position, _ ->
            docs.getOrNull(position)?.let { confirmDelete(it) }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        docs = RwStore.list(this)
        binding.emptyState.visibility = if (docs.isEmpty()) View.VISIBLE else View.GONE
        binding.rwList.adapter = RwAdapter(this, docs)
    }

    private fun openScan(id: String) {
        startActivity(
            Intent(this, RwScanActivity::class.java).putExtra(RwScanActivity.EXTRA_RW_ID, id)
        )
    }

    private fun openDetail(id: String) {
        startActivity(
            Intent(this, RwDetailActivity::class.java).putExtra(RwDetailActivity.EXTRA_RW_ID, id)
        )
    }

    private fun confirmDelete(doc: RwDocument) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rw_delete)
            .setMessage(getString(R.string.rw_delete_confirm, doc.number.ifEmpty { getString(R.string.rw_draft) }))
            .setPositiveButton(R.string.delete) { _, _ ->
                RwStore.delete(this, doc.id)
                onResume()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
