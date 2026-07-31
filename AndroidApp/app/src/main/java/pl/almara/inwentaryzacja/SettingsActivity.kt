package pl.almara.inwentaryzacja

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import pl.almara.inwentaryzacja.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.rowAlleys.setOnClickListener { editAlleys() }
        binding.rowRequesters.setOnClickListener { editPeople(PeopleEditActivity.LIST_REQUESTERS) }
        binding.rowManagers.setOnClickListener { editPeople(PeopleEditActivity.LIST_MANAGERS) }
        binding.resetButton.setOnClickListener { confirmReset() }
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_reset)
            .setMessage(R.string.settings_reset_confirm)
            .setPositiveButton(R.string.settings_reset_yes) { _, _ ->
                Settings.resetDefaults(this)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun editPeople(which: String) {
        startActivity(
            Intent(this, PeopleEditActivity::class.java)
                .putExtra(PeopleEditActivity.EXTRA_LIST, which)
        )
    }

    private fun refresh() {
        binding.alleysValue.text = Settings.alleyCount(this).toString()
        binding.requestersValue.text = peopleSummary(Settings.requesters(this))
        binding.managersValue.text = peopleSummary(Settings.managers(this))
    }

    private fun peopleSummary(people: List<String>): String =
        if (people.isEmpty()) getString(R.string.settings_people_empty)
        else getString(R.string.settings_people_count, people.size) + ": " + people.joinToString(", ")

    private fun editAlleys() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Settings.alleyCount(this@SettingsActivity).toString())
            setSelectAllOnFocus(true)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_alley_count)
            .setView(pad(input))
            .setPositiveButton(R.string.ok) { _, _ ->
                val value = input.text.toString().toIntOrNull()
                if (value == null || value < 1) {
                    Toast.makeText(this, R.string.settings_alley_count_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    Settings.setAlleyCount(this, value)
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pad(view: EditText): FrameLayout {
        val p = (20 * resources.displayMetrics.density).toInt()
        return FrameLayout(this).apply {
            setPadding(p, p / 2, p, 0)
            addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }
}
