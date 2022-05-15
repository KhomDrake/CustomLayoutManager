package com.vinicius.automeasurerecyclerview

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import br.com.arch.toolkit.delegate.viewProvider
import kotlin.random.Random

class Adapter2 : RecyclerView.Adapter<ViewHolder2>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder2 {
        return ViewHolder2(
            LayoutInflater.from(parent.context).inflate(R.layout.item_layout_2, parent, false)
        )
    }

    override fun onBindViewHolder(holder: ViewHolder2, position: Int) {
        holder.bind()
    }

    override fun getItemCount() = 1
}

class ViewHolder2(itemView: View): RecyclerView.ViewHolder(itemView) {

    private val recyclerView: RecyclerView by viewProvider(R.id.recyclerview_2)

    fun bind() {
        recyclerView.layoutManager = LinearLayoutManager(itemView.context, LinearLayoutManager.HORIZONTAL, false)
        recyclerView.adapter = Adapter(recyclerView, generateRandomText())
        PagerSnapHelper().attachToRecyclerView(recyclerView)
    }

    private fun generateRandomText() : List<String> {
        val list = mutableListOf<String>()

        val sampleText = "skdljadlksadjsalk\n"

        for(i in 0 until 5) {
            val randomNumber = Random.nextInt(1, 10)
            var text = "Test "
            for (i in 0 until randomNumber) {
                text += sampleText
            }
            list.add(text)
        }

        return list
    }

}