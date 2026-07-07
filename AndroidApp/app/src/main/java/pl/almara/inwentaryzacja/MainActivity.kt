package pl.almara.inwentaryzacja

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import pl.almara.inwentaryzacja.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var sessions: List<Session> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.newSessionButton.setOnClickListener { showNewSessionDialog() }

        binding.sessionsList.setOnItemClickListener { _, _, position, _ ->
            val session = sessions.getOrNull(position) ?: return@setOnItemClickListener
            startActivity(
                Intent(this, SessionDetailActivity::class.java)
                    .putExtra(SessionDetailActivity.EXTRA_SESSION_ID, session.id)
            )
        }
        binding.sessionsList.setOnItemLongClickListener { _, _, position, _ ->
            sessions.getOrNull(position)?.let { confirmDelete(it) }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        sessions = SessionStore.listSessions(this)
        binding.emptyText.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
        val rows = sessions.map { s ->
            val total = Format.weight(s.items.sumOf { it.weightKg })
            "${s.name}\n${s.createdAt}  •  Palet: ${s.items.size}  •  $total kg"
        }
        binding.sessionsList.adapter =
            ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
    }

    private fun showNewSessionDialog() {
        val defaultName = getString(
            R.string.default_session_name,
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        )
        val input = EditText(this).apply {
            setText(defaultName)
            hint = getString(R.string.session_name_hint)
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.new_session)
            .setView(input)
            .setPositiveButton(R.string.start) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { defaultName }
                val session = SessionStore.createSession(this, name)
                startActivity(
                    Intent(this, ScanActivity::class.java)
                        .putExtra(ScanActivity.EXTRA_SESSION_ID, session.id)
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(session: Session) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_session)
            .setMessage(getString(R.string.delete_confirm, session.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                SessionStore.deleteSession(this, session.id)
                refreshList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
