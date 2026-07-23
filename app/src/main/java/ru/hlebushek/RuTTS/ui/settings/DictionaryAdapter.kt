package ru.hlebushek.RuTTS.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ru.hlebushek.RuTTS.R
import ru.hlebushek.RuTTS.config.DictionaryEntry

class DictionaryAdapter(
    private val entries: List<DictionaryEntry>,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<DictionaryAdapter.DictionaryViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DictionaryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_dictionary_entry, parent, false)
        return DictionaryViewHolder(view, onClick)
    }

    override fun onBindViewHolder(holder: DictionaryViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount(): Int = entries.size

    class DictionaryViewHolder(
        itemView: View,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val keyText: TextView = itemView.findViewById(R.id.keyText)
        private val valueText: TextView = itemView.findViewById(R.id.valueText)

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onClick(position)
                }
            }
        }

        fun bind(entry: DictionaryEntry) {
            keyText.text = entry.key
            valueText.text = entry.value
        }
    }
}

