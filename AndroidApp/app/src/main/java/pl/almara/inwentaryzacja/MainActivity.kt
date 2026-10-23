package pl.almara.inwentaryzacja

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import pl.almara.inwentaryzacja.databinding.ActivityMainBinding
import pl.almara.inwentaryzacja.databinding.DialogNewSessionBinding
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

        binding.toolbar.setNavigationOnClickListener { finish() }
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
        binding.emptyState.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
        binding.sessionsList.adapter = SessionAdapter(this, sessions)
    }

    private fun showNewSessionDialog() {
        val defaultName = getString(
            R.string.default_session_name,
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        )
        val form = DialogNewSessionBinding.inflate(layoutInflater).apply {
            sessionNameInput.setText(defaultName)
            sessionNameInput.setSelectAllOnFocus(true)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_session)
            .setView(form.root)
            .setPositiveButton(R.string.start, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        form.sessionNameInput.doAfterTextChanged { form.sessionNameInput.error = null }
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val name = form.sessionNameInput.text.toString().trim().ifEmpty { defaultName }
                val type = if (form.sessionTypeGroup.checkedRadioButtonId == R.id.typeRaw)
                    Session.TYPE_RAW else Session.TYPE_PRODUCT
                val session = SessionStore.createSession(this, name, type)
                if (session == null) {
                    form.sessionNameInput.error = getString(R.string.session_name_exists)
                    return@setOnClickListener
                }
                dialog.dismiss()
                startActivity(
                    Intent(this, ScanActivity::class.java)
                        .putExtra(ScanActivity.EXTRA_SESSION_ID, session.id)
                )
            }
        }
        dialog.show()
    }

    private fun confirmDelete(session: Session) {
        MaterialAlertDialogBuilder(this)
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
