package com.vinicius.automeasurerecyclerview

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.doOnPreDraw
import androidx.recyclerview.widget.RecyclerView
import br.com.arch.toolkit.delegate.viewProvider
import java.util.*

class Adapter(private val recyclerView: RecyclerView, private val list: List<String>) : RecyclerView.Adapter<ViewHolder>() {
    private val pool = LinkedList<ViewHolder>()
    private var ini = true

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return createYourViewHolder(parent)
    }

    private fun createYourViewHolder(parent: ViewGroup) = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_layout, parent, false).apply {
            doOnPreDraw {
                Log.i("Vini", "View Created: H${it.height}/W${it.width}")
            }
        }
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(list[position])
    }

    override fun getItemViewType(position: Int): Int {
        return 12
    }

    override fun getItemCount() = list.size
}

class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {

    private val name: AppCompatTextView by viewProvider(R.id.name)

    fun bind(name: String) {
        this.name.text = name
    }

}