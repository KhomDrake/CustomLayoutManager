package com.vinicius.automeasurerecyclerview

import android.content.Context
import android.util.Log
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager

class CustomLayoutManager(
    private val Swidth: Int,
    context: Context
) : LinearLayoutManager(context, HORIZONTAL, false) {

    override fun getWidthMode(): Int {
        return View.MeasureSpec.UNSPECIFIED
    }

    override fun getWidth(): Int {
        return super.getWidth()
    }

}
