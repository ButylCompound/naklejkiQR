package pl.almara.inwentaryzacja

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import pl.almara.inwentaryzacja.databinding.ActivityPeopleEditBinding

/** Edycja listy osób (zamawiający lub kierownicy zmiany): dodawanie i usuwanie. */
class PeopleEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LIST = "list"
        const val LIST_REQUESTERS = "requesters"
        const val LIST_MANAGERS = "managers"
    }

    private lateinit var binding: ActivityPeopleEditBinding
    private lateinit var which: String
    private val names = mutableListOf<String>()
    private lateinit var adapter: PersonAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPeopleEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        which = intent.getStringExtra(EXTRA_LIST) ?: run { finish(); return }
        binding.toolbar.title = getString(
            if (which == LIST_MANAGERS) R.string.settings_managers else R.string.settings_requesters
        )
        binding.toolbar.setNavigationOnClickListener { finish() }

        names.addAll(load())
        adapter = PersonAdapter(this, names) { position ->
            names.removeAt(position)
            persist()
        }
        binding.peopleList.adapter = adapter
        refreshEmpty()

        binding.addButton.setOnClickListener { add() }
    }

    private fun add() {
        val name = binding.nameInput.text.toString().trim()
        if (name.isEmpty()) return
        if (names.any { it.equals(name, ignoreCase = true) }) {
            Toast.makeText(this, R.string.people_duplicate, Toast.LENGTH_SHORT).show()
            return
        }
        names.add(name)
        persist()
        binding.nameInput.text.clear()
    }

    private fun persist() {
        when (which) {
            LIST_MANAGERS -> Settings.setManagers(this, names)
            else -> Settings.setRequesters(this, names)
        }
        adapter.notifyDataSetChanged()
        refreshEmpty()
    }

    private fun load(): List<String> =
        if (which == LIST_MANAGERS) Settings.managers(this) else Settings.requesters(this)

    private fun refreshEmpty() {
        binding.emptyState.visibility = if (names.isEmpty()) View.VISIBLE else View.GONE
    }
}
