package com.vinicius.automeasurerecyclerview

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.recyclerview.widget.PagerSnapHelper
import br.com.arch.toolkit.delegate.viewProvider
import com.vinicius.automeasurerecyclerview.custom.CustomLinearLayoutManager
import kotlin.random.Random

class MainActivity : AppCompatActivity(R.layout.activity_main) {

    private val recyclerView: MyRecyclerView by viewProvider(R.id.recyclerview)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val randomText = generateRandomText()
//        recyclerView.layoutManager = CustomLayoutManager(
//            width,
//            this
//        )
        recyclerView.layoutManager = CustomLinearLayoutManager()
        recyclerView.adapter = Adapter(recyclerView, randomText)
        PagerSnapHelper().attachToRecyclerView(recyclerView)
    }

    private fun generateRandomText() : List<String> {
        val list = mutableListOf<String>()

        val sampleText = "skdljadlksadjsalk\n"

        for(i in 0 until 8) {
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