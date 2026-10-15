package ru.hlebushek.RuTTS.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import ru.hlebushek.RuTTS.R
import ru.hlebushek.RuTTS.config.ConfigRepository
import ru.hlebushek.RuTTS.config.DictionaryEntry
import ru.hlebushek.RuTTS.config.DictionaryKind
import ru.hlebushek.RuTTS.ui.SystemBars

class DictionaryEditorActivity : AppCompatActivity(R.layout.activity_dictionary_editor) {
    private lateinit var repository: ConfigRepository
    private lateinit var dictionaryKind: DictionaryKind
    private lateinit var adapter: DictionaryAdapter
    private lateinit var emptyView: View
    private val entries = mutableListOf<DictionaryEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemBars.applyInsets(this)

        repository = ConfigRepository(this)
        dictionaryKind = intent.getStringExtra(EXTRA_KIND)
            ?.let(DictionaryKind::valueOf)
            ?: DictionaryKind.CHARACTERS
        entries += repository.loadDictionary(dictionaryKind)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.navigationContentDescription = getString(R.string.navigation_back)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.title = if (dictionaryKind == DictionaryKind.CHARACTERS) {
            getString(R.string.settings_characters)
        } else {
            getString(R.string.settings_single_characters)
        }

        emptyView = findViewById(R.id.emptyView)
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        adapter = DictionaryAdapter(entries) { position ->
            showEditDialog(position)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.addButton)
            .setOnClickListener {
                showEditDialog(null)
            }

        updateEmptyState()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_dictionary_editor, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_restore_defaults -> {
                confirmRestoreDefaults()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun confirmRestoreDefaults() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.dictionary_restore_defaults_message)
            .setNegativeButton(R.string.dictionary_cancel, null)
            .setPositiveButton(R.string.settings_restore_defaults) { _, _ ->
                val oldSize = entries.size
                repository.restoreDictionaryDefaults(dictionaryKind)
                entries.clear()
                entries += repository.loadDictionary(dictionaryKind)
                notifyEntriesReloaded(oldSize, entries.size)
                updateEmptyState()
            }
            .show()
    }

    private fun showEditDialog(index: Int?) {
        val existing = index?.let(entries::get)
        val dialogView = layoutInflater.inflate(R.layout.dialog_dictionary_entry, null)
        val keyInput = dialogView.findViewById<TextInputEditText>(R.id.keyInput)
        val valueInput = dialogView.findViewById<TextInputEditText>(R.id.valueInput)
        val keyLayout = dialogView.findViewById<TextInputLayout>(R.id.keyLayout)
        val valueLayout = dialogView.findViewById<TextInputLayout>(R.id.valueLayout)

        keyInput.setText(existing?.key.orEmpty())
        valueInput.setText(existing?.value.orEmpty())

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.dictionary_add_title else R.string.dictionary_edit_title)
            .setView(dialogView)
            .setNegativeButton(R.string.dictionary_cancel, null)
            .setPositiveButton(R.string.dictionary_save, null)
            .apply {
                if (existing != null) {
                    setNeutralButton(R.string.dictionary_delete, null)
                }
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                keyLayout.error = null
                valueLayout.error = null
                val key = keyInput.text?.toString()?.trim().orEmpty()
                val value = valueInput.text?.toString()?.trim().orEmpty()
                when {
                    key.isBlank() || key.contains('=') || key.contains('\n') || key.contains('\r') -> {
                        keyLayout.error = getString(R.string.dictionary_invalid_key)
                    }
                    value.isBlank() -> {
                        valueLayout.error = getString(R.string.dictionary_invalid_value)
                    }
                    else -> {
                        if (upsertEntry(index, DictionaryEntry(key, value), keyLayout)) {
                            dialog.dismiss()
                        }
                    }
                }
            }

            if (existing != null) {
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    val removedIndex = index
                    entries.removeAt(index)
                    repository.saveDictionary(dictionaryKind, entries)
                    adapter.notifyItemRemoved(removedIndex)
                    updateEmptyState()
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
    }

    private fun upsertEntry(index: Int?, entry: DictionaryEntry, keyLayout: TextInputLayout): Boolean {
        val duplicateIndex = entries.indexOfFirstIndexed { currentIndex, existing ->
            existing.key.equals(entry.key, ignoreCase = true) && currentIndex != index
        }
        if (duplicateIndex >= 0) {
            keyLayout.error = getString(R.string.dictionary_duplicate_key)
            return false
        }

        if (index == null) {
            entries += entry
            adapter.notifyItemInserted(entries.lastIndex)
        } else {
            entries[index] = entry
            adapter.notifyItemChanged(index)
        }
        repository.saveDictionary(dictionaryKind, entries)
        updateEmptyState()
        return true
    }

    private fun updateEmptyState() {
        emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun notifyEntriesReloaded(oldSize: Int, newSize: Int) {
        val sharedCount = minOf(oldSize, newSize)
        if (sharedCount > 0) {
            adapter.notifyItemRangeChanged(0, sharedCount)
        }
        when {
            oldSize > newSize -> adapter.notifyItemRangeRemoved(newSize, oldSize - newSize)
            newSize > oldSize -> adapter.notifyItemRangeInserted(oldSize, newSize - oldSize)
        }
    }

    companion object {
        private const val EXTRA_KIND = "extra_kind"

        fun createIntent(context: Context, kind: DictionaryKind): Intent {
            return Intent(context, DictionaryEditorActivity::class.java)
                .putExtra(EXTRA_KIND, kind.name)
        }
    }
}

private inline fun <T> List<T>.indexOfFirstIndexed(predicate: (Int, T) -> Boolean): Int {
    forEachIndexed { index, item ->
        if (predicate(index, item)) {
            return index
        }
    }
    return -1
}
