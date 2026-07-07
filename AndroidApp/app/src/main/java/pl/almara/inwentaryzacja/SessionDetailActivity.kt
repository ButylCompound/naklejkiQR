package pl.almara.inwentaryzacja

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import pl.almara.inwentaryzacja.databinding.ActivitySessionDetailBinding

class SessionDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }

    private lateinit var binding: ActivitySessionDetailBinding
    private lateinit var sessionId: String
    private var session: Session? = null

    /** "Zapisz CSV" — użytkownik wybiera lokalizację (np. folder OneDrive/SharePoint). */
    private val saveCsvLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            val s = session ?: return@registerForActivityResult
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.openOutputStream(uri)?.use {
                    it.write(CsvExporter.buildCsv(s).toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this, R.string.csv_saved, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this, getString(R.string.csv_save_error, e.message), Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra(EXTRA_SESSION_ID)
        if (id == null) {
            finish()
            return
        }
        sessionId = id

        binding.scanButton.setOnClickListener {
            startActivity(
                Intent(this, ScanActivity::class.java)
                    .putExtra(ScanActivity.EXTRA_SESSION_ID, sessionId)
            )
        }
        binding.shareCsvButton.setOnClickListener {
            session?.let { CsvExporter.share(this, it) }
        }
        binding.saveCsvButton.setOnClickListener {
            session?.let { saveCsvLauncher.launch(CsvExporter.fileName(it)) }
        }
        binding.deleteButton.setOnClickListener { confirmDelete() }
    }

    override fun onResume() {
        super.onResume()
        val s = SessionStore.getSession(this, sessionId)
        if (s == null) {
            finish()
            return
        }
        session = s
        title = s.name

        val total = Format.weight(s.items.sumOf { it.weightKg })
        binding.summaryText.text = getString(R.string.summary_format, s.items.size, total)

        val rows = s.items.mapIndexed { i, item ->
            getString(
                R.string.item_format,
                i + 1, item.product, Format.weight(item.weightKg),
                item.labelDate.ifEmpty { "—" }, item.scannedAt
            )
        }
        binding.itemsList.adapter =
            ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
    }

    private fun confirmDelete() {
        val s = session ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_session)
            .setMessage(getString(R.string.delete_confirm, s.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                SessionStore.deleteSession(this, sessionId)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
