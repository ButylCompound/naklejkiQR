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
        const val LIST_CUSTOM_ALLEYS = "custom_alleys"
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
            when (which) {
                LIST_MANAGERS -> R.string.settings_managers
                LIST_CUSTOM_ALLEYS -> R.string.settings_custom_alleys
                else -> R.string.settings_requesters
            }
        )
        binding.nameInput.setHint(
            if (which == LIST_CUSTOM_ALLEYS) R.string.alley_name_hint else R.string.people_name_hint
        )
        binding.emptyState.setText(
            if (which == LIST_CUSTOM_ALLEYS) R.string.alley_empty else R.string.people_empty
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
        if (which == LIST_CUSTOM_ALLEYS && !Settings.isValidCustomAlleyName(name)) {
            Toast.makeText(this, R.string.alley_numeric_invalid, Toast.LENGTH_SHORT).show()
            return
        }
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
            LIST_CUSTOM_ALLEYS -> Settings.setCustomAlleys(this, names)
            else -> Settings.setRequesters(this, names)
        }
        adapter.notifyDataSetChanged()
        refreshEmpty()
    }

    private fun load(): List<String> = when (which) {
        LIST_MANAGERS -> Settings.managers(this)
        LIST_CUSTOM_ALLEYS -> Settings.customAlleys(this)
        else -> Settings.requesters(this)
    }

    private fun refreshEmpty() {
        binding.emptyState.visibility = if (names.isEmpty()) View.VISIBLE else View.GONE
    }
}
