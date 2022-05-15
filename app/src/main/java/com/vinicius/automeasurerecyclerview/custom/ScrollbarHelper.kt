package com.vinicius.automeasurerecyclerview.custom

import android.view.View
import androidx.recyclerview.widget.RecyclerView

object ScrollbarHelper {
    /**
     * @param startChild View closest to start of the list. (top or left)
     * @param endChild   View closest to end of the list (bottom or right)
     */
    fun computeScrollOffset(
        state: RecyclerView.State, orientation: OrientationHelper,
        startChild: View?, endChild: View?, lm: RecyclerView.LayoutManager,
        smoothScrollbarEnabled: Boolean, reverseLayout: Boolean
    ): Int {
        if (lm.childCount == 0 || state.itemCount == 0 || startChild == null || endChild == null) {
            return 0
        }
        val minPosition = Math.min(
            lm.getPosition(startChild),
            lm.getPosition(endChild)
        )
        val maxPosition = Math.max(
            lm.getPosition(startChild),
            lm.getPosition(endChild)
        )
        val itemsBefore =
            if (reverseLayout) Math.max(0, state.itemCount - maxPosition - 1) else Math.max(
                0,
                minPosition
            )
        if (!smoothScrollbarEnabled) {
            return itemsBefore
        }
        val laidOutArea = Math.abs(
            orientation.getDecoratedEnd(endChild)
                    - orientation.getDecoratedStart(startChild)
        )
        val itemRange = Math.abs(
            (lm.getPosition(startChild)
                    - lm.getPosition(endChild))
        ) + 1
        val avgSizePerRow = laidOutArea.toFloat() / itemRange
        return Math.round(
            itemsBefore * avgSizePerRow + ((orientation.startAfterPadding
                    - orientation.getDecoratedStart(startChild)))
        )
    }

    /**
     * @param startChild View closest to start of the list. (top or left)
     * @param endChild   View closest to end of the list (bottom or right)
     */
    fun computeScrollExtent(
        state: RecyclerView.State, orientation: OrientationHelper,
        startChild: View?, endChild: View?, lm: RecyclerView.LayoutManager,
        smoothScrollbarEnabled: Boolean
    ): Int {
        if ((lm.childCount == 0) || (state.itemCount == 0) || (startChild == null
                    ) || (endChild == null)
        ) {
            return 0
        }
        if (!smoothScrollbarEnabled) {
            return Math.abs(lm.getPosition(startChild) - lm.getPosition(endChild)) + 1
        }
        val extend = (orientation.getDecoratedEnd(endChild)
                - orientation.getDecoratedStart(startChild))
        return Math.min(orientation.totalSpace, extend)
    }

    /**
     * @param startChild View closest to start of the list. (top or left)
     * @param endChild   View closest to end of the list (bottom or right)
     */
    fun computeScrollRange(
        state: RecyclerView.State, orientation: OrientationHelper,
        startChild: View?, endChild: View?, lm: RecyclerView.LayoutManager,
        smoothScrollbarEnabled: Boolean
    ): Int {
        if ((lm.childCount == 0) || (state.itemCount == 0) || (startChild == null
                    ) || (endChild == null)
        ) {
            return 0
        }
        if (!smoothScrollbarEnabled) {
            return state.itemCount
        }
        // smooth scrollbar enabled. try to estimate better.
        val laidOutArea = (orientation.getDecoratedEnd(endChild)
                - orientation.getDecoratedStart(startChild))
        val laidOutRange = (Math.abs(
            lm.getPosition(startChild)
                    - lm.getPosition(endChild)
        )
                + 1)
        // estimate a size for full list.
        return (laidOutArea.toFloat() / laidOutRange * state.itemCount).toInt()
    }
}