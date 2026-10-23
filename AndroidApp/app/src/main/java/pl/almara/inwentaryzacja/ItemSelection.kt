package pl.almara.inwentaryzacja

import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ItemSelection(
    private val activity: AppCompatActivity,
    restored: List<String> = emptyList(),
    private val onChanged: () -> Unit,
    private val onDelete: (Set<String>) -> Unit
) {
    private val selected = restored.toMutableSet()
    private var mode: ActionMode? = null
    val active: Boolean get() = selected.isNotEmpty()
    fun contains(item: ScanItem): Boolean = item.raw in selected
    fun saved(): ArrayList<String> = ArrayList(selected)

    fun toggle(item: ScanItem) {
        if (!selected.add(item.raw)) selected.remove(item.raw)
        update()
    }

    fun retain(items: List<ScanItem>) {
        selected.retainAll(items.map { it.raw }.toSet())
        update()
    }

    fun clear() {
        selected.clear()
        if (mode != null) mode?.finish() else onChanged()
    }

    private fun update() {
        if (!active) {
            clear()
            return
        }
        if (mode == null) mode = activity.startSupportActionMode(object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(R.string.delete_selected)
                    .setIcon(R.drawable.ic_delete)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val keys = selected.toSet()
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.delete_selected)
                    .setMessage(activity.getString(R.string.delete_selected_confirm, keys.size))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        clear()
                        onDelete(keys)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                this@ItemSelection.mode = null
                selected.clear()
                onChanged()
            }
        })
        mode?.title = activity.getString(R.string.selected_count, selected.size)
        onChanged()
    }
}
