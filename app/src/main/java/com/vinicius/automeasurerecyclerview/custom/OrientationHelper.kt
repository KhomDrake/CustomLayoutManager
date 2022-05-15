package com.vinicius.automeasurerecyclerview.custom

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

abstract class OrientationHelper private constructor(
    /**
     * Returns the [LayoutManager][RecyclerView.LayoutManager] that
     * is associated with this OrientationHelper.
     */
    val layoutManager: RecyclerView.LayoutManager
) {
    private var mLastTotalSpace = INVALID_SIZE
    val mTmpRect = Rect()

    /**
     * Call this method after onLayout method is complete if state is NOT pre-layout.
     * This method records information like layout bounds that might be useful in the next layout
     * calculations.
     */
    fun onLayoutComplete() {
        mLastTotalSpace = totalSpace
    }

    /**
     * Returns the layout space change between the previous layout pass and current layout pass.
     *
     *
     * Make sure you call [.onLayoutComplete] at the end of your LayoutManager's
     * [RecyclerView.LayoutManager.onLayoutChildren] method.
     *
     * @return The difference between the current total space and previous layout's total space.
     * @see .onLayoutComplete
     */
    val totalSpaceChange: Int
        get() = if (INVALID_SIZE == mLastTotalSpace) 0 else totalSpace - mLastTotalSpace

    /**
     * Returns the start of the view including its decoration and margin.
     *
     *
     * For example, for the horizontal helper, if a View's left is at pixel 20, has 2px left
     * decoration and 3px left margin, returned value will be 15px.
     *
     * @param view The view element to check
     * @return The first pixel of the element
     * @see .getDecoratedEnd
     */
    abstract fun getDecoratedStart(view: View): Int

    /**
     * Returns the end of the view including its decoration and margin.
     *
     *
     * For example, for the horizontal helper, if a View's right is at pixel 200, has 2px right
     * decoration and 3px right margin, returned value will be 205.
     *
     * @param view The view element to check
     * @return The last pixel of the element
     * @see .getDecoratedStart
     */
    abstract fun getDecoratedEnd(view: View): Int

    /**
     * Returns the end of the View after its matrix transformations are applied to its layout
     * position.
     *
     *
     * This method is useful when trying to detect the visible edge of a View.
     *
     *
     * It includes the decorations but does not include the margins.
     *
     * @param view The view whose transformed end will be returned
     * @return The end of the View after its decor insets and transformation matrix is applied to
     * its position
     *
     * @see RecyclerView.LayoutManager.getTransformedBoundingBox
     */
    abstract fun getTransformedEndWithDecoration(view: View): Int

    /**
     * Returns the start of the View after its matrix transformations are applied to its layout
     * position.
     *
     *
     * This method is useful when trying to detect the visible edge of a View.
     *
     *
     * It includes the decorations but does not include the margins.
     *
     * @param view The view whose transformed start will be returned
     * @return The start of the View after its decor insets and transformation matrix is applied to
     * its position
     *
     * @see RecyclerView.LayoutManager.getTransformedBoundingBox
     */
    abstract fun getTransformedStartWithDecoration(view: View): Int

    /**
     * Returns the space occupied by this View in the current orientation including decorations and
     * margins.
     *
     * @param view The view element to check
     * @return Total space occupied by this view
     * @see .getDecoratedMeasurementInOther
     */
    abstract fun getDecoratedMeasurement(view: View): Int

    /**
     * Returns the space occupied by this View in the perpendicular orientation including
     * decorations and margins.
     *
     * @param view The view element to check
     * @return Total space occupied by this view in the perpendicular orientation to current one
     * @see .getDecoratedMeasurement
     */
    abstract fun getDecoratedMeasurementInOther(view: View): Int

    /**
     * Returns the start position of the layout after the start padding is added.
     *
     * @return The very first pixel we can draw.
     */
    abstract val startAfterPadding: Int

    /**
     * Returns the end position of the layout after the end padding is removed.
     *
     * @return The end boundary for this layout.
     */
    abstract val endAfterPadding: Int

    /**
     * Returns the end position of the layout without taking padding into account.
     *
     * @return The end boundary for this layout without considering padding.
     */
    abstract val end: Int

    /**
     * Offsets all children's positions by the given amount.
     *
     * @param amount Value to add to each child's layout parameters
     */
    abstract fun offsetChildren(amount: Int)

    /**
     * Returns the total space to layout. This number is the difference between
     * [.getEndAfterPadding] and [.getStartAfterPadding].
     *
     * @return Total space to layout children
     */
    abstract val totalSpace: Int

    /**
     * Offsets the child in this orientation.
     *
     * @param view   View to offset
     * @param offset offset amount
     */
    abstract fun offsetChild(view: View, offset: Int)

    /**
     * Returns the padding at the end of the layout. For horizontal helper, this is the right
     * padding and for vertical helper, this is the bottom padding. This method does not check
     * whether the layout is RTL or not.
     *
     * @return The padding at the end of the layout.
     */
    abstract val endPadding: Int

    /**
     * Returns the MeasureSpec mode for the current orientation from the LayoutManager.
     *
     * @return The current measure spec mode.
     *
     * @see View.MeasureSpec
     *
     * @see RecyclerView.LayoutManager.getWidthMode
     * @see RecyclerView.LayoutManager.getHeightMode
     */
    abstract val mode: Int

    /**
     * Returns the MeasureSpec mode for the perpendicular orientation from the LayoutManager.
     *
     * @return The current measure spec mode.
     *
     * @see View.MeasureSpec
     *
     * @see RecyclerView.LayoutManager.getWidthMode
     * @see RecyclerView.LayoutManager.getHeightMode
     */
    abstract val modeInOther: Int

    companion object {
        private const val INVALID_SIZE = Int.MIN_VALUE
        const val HORIZONTAL = RecyclerView.HORIZONTAL
        const val VERTICAL = RecyclerView.VERTICAL

        /**
         * Creates an OrientationHelper for the given LayoutManager and orientation.
         *
         * @param layoutManager LayoutManager to attach to
         * @param orientation   Desired orientation. Should be [.HORIZONTAL] or [.VERTICAL]
         * @return A new OrientationHelper
         */
        fun createOrientationHelper(
            layoutManager: RecyclerView.LayoutManager, @RecyclerView.Orientation orientation: Int
        ): OrientationHelper {
            when (orientation) {
                HORIZONTAL -> return createHorizontalHelper(layoutManager)
                VERTICAL -> return createVerticalHelper(layoutManager)
            }
            throw IllegalArgumentException("invalid orientation")
        }

        /**
         * Creates a horizontal OrientationHelper for the given LayoutManager.
         *
         * @param layoutManager The LayoutManager to attach to.
         * @return A new OrientationHelper
         */
        fun createHorizontalHelper(
            layoutManager: RecyclerView.LayoutManager
        ): OrientationHelper {
            return object : OrientationHelper(layoutManager) {
                override val endAfterPadding: Int
                    get() = layoutManager.width - layoutManager.paddingRight
                override val end: Int
                    get() = layoutManager.width

                override fun offsetChildren(amount: Int) {
                    layoutManager.offsetChildrenHorizontal(amount)
                }

                override val startAfterPadding: Int
                    get() = layoutManager.paddingLeft

                override fun getDecoratedMeasurement(view: View): Int {
                    val params = view.layoutParams as RecyclerView.LayoutParams
                    return (layoutManager.getDecoratedMeasuredWidth(view) + params.leftMargin
                            + params.rightMargin)
                }

                override fun getDecoratedMeasurementInOther(view: View): Int {
                    val params = view.layoutParams as RecyclerView.LayoutParams
                    return (layoutManager.getDecoratedMeasuredHeight(view) + params.topMargin
                            + params.bottomMargin)
                }

                override fun getDecoratedEnd(view: View): Int {
                    val params = view.layoutParams as RecyclerView.LayoutParams
                    return layoutManager.getDecoratedRight(view) + params.rightMargin
                }

                override fun getDecoratedStart(view: View): Int {
                    val params = view.layoutParams as RecyclerView.LayoutParams
                    return layoutManager.getDecoratedLeft(view) - params.leftMargin
                }

                override fun getTransformedEndWithDecoration(view: View): Int {
                    layoutManager.getTransformedBoundingBox(view, true, mTmpRect)
                    return mTmpRect.right
                }

                override fun getTransformedStartWithDecoration(view: View): Int {
                    layoutManager.getTransformedBoundingBox(view, true, mTmpRect)
                    return mTmpRect.left
                }

                override val totalSpace: Int
                    get() = (layoutManager.width - layoutManager.paddingLeft
                            - layoutManager.paddingRight)

                override fun offsetChild(view: View, offset: Int) {
                    view.offsetLeftAndRight(offset)
                }

                override val endPadding: Int
                    get() = layoutManager.paddingRight
                override val mode: Int
                    get() = layoutManager.widthMode
                override val modeInOther: Int
                    get() = layoutManager.heightMode
            }
        }

        /**
         * Creates a vertical OrientationHelper for the given LayoutManager.
         *
         * @param layoutManager The LayoutManager to attach to.
         * @return A new OrientationHelper
         */
        fun createVerticalHelper(layoutManager: RecyclerView.LayoutManager): OrientationHelper {
            return object : OrientationHelper(layoutManager) {
                override val endAfterPadding: Int
                    get() = layoutManager.height - layoutManager.paddingBottom
                override val end: Int
                    get() = layoutManager.height

                override fun offsetChildren(amount: Int) {
                    layoutManager.offsetChildrenVertical(amount)
                }

                override val startAfterPadding: Int
                    get() = layoutManager.paddingTop

                override fun getDecoratedMeasurement(view: View): Int {
                    val params = view.layoutParams as RecyclerView.LayoutParams
                    return (layoutManager.getDecoratedMeasuredHeight(view) + params.topMargin
                            + params.bottomMargin)
                }

                override fun getDecoratedMeasurementInOther(view: View): Int {
                    val params = view.layoutParams as RecyclerView.LayoutParams
                    return (layoutManager.getDecoratedMeasuredWidth(view) + params.leftMargin
                            + params.rightMargin)
                }

                override fun getDecoratedEnd(view: View): Int {
                    val params = view.layoutParams as RecyclerView.LayoutParams
                    return layoutManager.getDecoratedBottom(view) + params.bottomMargin
                }

                override fun getDecoratedStart(view: View): Int {
                    val params = view.layoutParams as RecyclerView.LayoutParams
                    return layoutManager.getDecoratedTop(view) - params.topMargin
                }

                override fun getTransformedEndWithDecoration(view: View): Int {
                    layoutManager.getTransformedBoundingBox(view, true, mTmpRect)
                    return mTmpRect.bottom
                }

                override fun getTransformedStartWithDecoration(view: View): Int {
                    layoutManager.getTransformedBoundingBox(view, true, mTmpRect)
                    return mTmpRect.top
                }

                override val totalSpace: Int
                    get() = (layoutManager.height - layoutManager.paddingTop
                            - layoutManager.paddingBottom)

                override fun offsetChild(view: View, offset: Int) {
                    view.offsetTopAndBottom(offset)
                }

                override val endPadding: Int
                    get() = layoutManager.paddingBottom
                override val mode: Int
                    get() = layoutManager.heightMode
                override val modeInOther: Int
                    get() = layoutManager.widthMode
            }
        }
    }
}
