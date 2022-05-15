package com.vinicius.automeasurerecyclerview.custom

import android.graphics.PointF
import android.os.Parcel
import android.os.Parcelable
import android.os.Parcelable.Creator
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Recycler
import com.vinicius.automeasurerecyclerview.callPrivateFunc
import kotlin.math.abs

class HorizontalLayoutManager(
    orientation: Int = HORIZONTAL,
    private val isInfinite: Boolean = true
) : RecyclerView.LayoutManager(),
    ItemTouchHelper.ViewDropHandler,
    RecyclerView.SmoothScroller.ScrollVectorProvider {

    private var orientation: Int = HORIZONTAL

    private var lastStackFromEnd = false


    /**
     * Defines if layout should be calculated from end to start.
     *
     * @see .mShouldReverseLayout
     */
    private var reverseLayout = false

    /**
     * This keeps the final value for how LayoutManager should start laying out views.
     * It is calculated by checking [.getReverseLayout] and View's layout direction.
     * [.onLayoutChildren] is run.
     */
    private var shouldReverseLayout = false

    /**
     * Works the same way as [android.widget.AbsListView.setStackFromBottom] and
     * it supports both orientations.
     * see [android.widget.AbsListView.setStackFromBottom]
     */
    private var stackFromEnd = false

    /**
     * Works the same way as [android.widget.AbsListView.setSmoothScrollbarEnabled].
     * see [android.widget.AbsListView.setSmoothScrollbarEnabled]
     */
    private var smoothScrollbarEnabled = true

    /**
     * When LayoutManager needs to scroll to a position, it sets this variable and requests a
     * layout which will check this variable and re-layout accordingly.
     */
    private var pendingScrollPosition = RecyclerView.NO_POSITION

    /**
     * Used to keep the offset value when [.scrollToPositionWithOffset] is
     * called.
     */
    private var pendingScrollPositionOffset = INVALID_OFFSET

    private var recycleChildrenOnDetach = false
    
    private var pendingSavedState: SavedState? = null

    private var orientationHelper: OrientationHelper? = null

    private var layoutState: LayoutState? = null

    private val anchorInfo = AnchorInfo()

    /**
     * Stashed to avoid allocation, currently only used in #fill()
     */
    private val layoutChunkResult = LayoutChunkResult()

    /**
     * Number of items to prefetch when first coming on screen with new data.
     */
    private var initialPrefetchItemCount = 2

    // Reusable int array to be passed to method calls that mutate it in order to "return" two ints.
    // This should only be used used transiently and should not be used to retain any state over
    // time.
    private val reusableIntPair = IntArray(2)

    init {
        this.orientation = orientation
        setOrientation(this.orientation)
    }

    override fun isAutoMeasureEnabled(): Boolean {
        return true
    }

    override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams {
        return RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }


    /**
     * Returns whether LayoutManager will recycle its children when it is detached from
     * RecyclerView.
     *
     * @return true if LayoutManager will recycle its children when it is detached from
     * RecyclerView.
     */
    fun getRecycleChildrenOnDetach(): Boolean {
        return recycleChildrenOnDetach
    }

    /**
     * Set whether LayoutManager will recycle its children when it is detached from
     * RecyclerView.
     *
     *
     * If you are using a [RecyclerView.RecycledViewPool], it might be a good idea to set
     * this flag to `true` so that views will be available to other RecyclerViews
     * immediately.
     *
     *
     * Note that, setting this flag will result in a performance drop if RecyclerView
     * is restored.
     *
     * @param recycleChildrenOnDetach Whether children should be recycled in detach or not.
     */
    fun setRecycleChildrenOnDetach(recycleChildrenOnDetach: Boolean) {
        this.recycleChildrenOnDetach = recycleChildrenOnDetach
    }

    override fun onDetachedFromWindow(view: RecyclerView?, recycler: Recycler) {
        super.onDetachedFromWindow(view, recycler)
        if (recycleChildrenOnDetach) {
            removeAndRecycleAllViews(recycler)
            recycler.clear()
        }
    }

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        if (childCount > 0) {
            event.fromIndex = 0
            event.toIndex = childCount - 1
        }
    }

    override fun onSaveInstanceState(): Parcelable {
        pendingSavedState?.let {
            return SavedState(it)
        }
        val state = SavedState()
        if (childCount > 0) {
            ensureLayoutState()
            val didLayoutFromEnd = lastStackFromEnd xor shouldReverseLayout
            state.mAnchorLayoutFromEnd = didLayoutFromEnd
            if (didLayoutFromEnd) {
                getChildClosestToEnd()?.let {
                    state.mAnchorOffset = (orientationHelper!!.endAfterPadding
                            - orientationHelper!!.getDecoratedEnd(it))
                    state.mAnchorPosition = getPosition(it)
                }
                
            } else {
                getChildClosestToStart()?.let {
                    state.mAnchorPosition = getPosition(it)
                    state.mAnchorOffset = (orientationHelper!!.getDecoratedStart(it)
                            - orientationHelper!!.startAfterPadding)
                }
                
            }
        } else {
            state.invalidateAnchor()
        }
        return state
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is SavedState) {
            pendingSavedState = state
            requestLayout()
        }
    }

    /**
     * @return true if [.getOrientation] is [.HORIZONTAL]
     */
    override fun canScrollHorizontally(): Boolean {
        return orientation == HORIZONTAL
    }

    /**
     * @return true if [.getOrientation] is [.VERTICAL]
     */
    override fun canScrollVertically(): Boolean {
        return orientation == VERTICAL
    }

    /**
     * Compatibility support for [android.widget.AbsListView.setStackFromBottom]
     */
    fun setStackFromEnd(stackFromEnd: Boolean) {
        assertNotInLayoutOrScroll(null)
        if (this.stackFromEnd == stackFromEnd) {
            return
        }
        this.stackFromEnd = stackFromEnd
        requestLayout()
    }

    fun getStackFromEnd(): Boolean {
        return stackFromEnd
    }

    /**
     * Sets the orientation of the layout. [LinearLayoutManager]
     * will do its best to keep scroll position.
     *
     * @param orientation [.HORIZONTAL] or [.VERTICAL]
     */
    private fun setOrientation(@RecyclerView.Orientation orientation: Int) {
        if (orientation != HORIZONTAL && orientation != VERTICAL) {
            throw IllegalArgumentException("invalid orientation:$orientation")
        }
        assertNotInLayoutOrScroll(null)
        if (this.orientation != orientation || orientationHelper == null) {
            orientationHelper =
                OrientationHelper.createOrientationHelper(
                    this,
                    orientation
                )
            anchorInfo.mOrientationHelper = orientationHelper
            this.orientation = orientation
            requestLayout()
        }
    }

    /**
     * Calculates the view layout order. (e.g. from end to start or start to end)
     * RTL layout support is applied automatically. So if layout is RTL and
     * [.getReverseLayout] is `true`, elements will be laid out starting from left.
     */
    private fun resolveShouldLayoutReverse() {
        // A == B is the same result, but we rather keep it readable
        shouldReverseLayout = if (orientation == VERTICAL || !isLayoutRTL()) {
            reverseLayout
        } else {
            !reverseLayout
        }
    }

    /**
     * Returns if views are laid out from the opposite direction of the layout.
     *
     * @return If layout is reversed or not.
     * @see .setReverseLayout
     */
    fun getReverseLayout(): Boolean {
        return reverseLayout
    }

    /**
     * Used to reverse item traversal and layout order.
     * This behaves similar to the layout change for RTL views. When set to true, first item is
     * laid out at the end of the UI, second item is laid out before it etc.
     *
     * For horizontal layouts, it depends on the layout direction.
     * When set to true, If [RecyclerView] is LTR, than it will
     * layout from RTL, if [RecyclerView]} is RTL, it will layout
     * from LTR.
     *
     * If you are looking for the exact same behavior of
     * [android.widget.AbsListView.setStackFromBottom], use
     * [.setStackFromEnd]
     */
    fun setReverseLayout(reverseLayout: Boolean) {
        assertNotInLayoutOrScroll(null)
        if (reverseLayout == this.reverseLayout) {
            return
        }
        this.reverseLayout = reverseLayout
        requestLayout()
    }

    /**
     * {@inheritDoc}
     */
    override fun findViewByPosition(position: Int): View? {
        val childCount = childCount
        if (childCount == 0) {
            return null
        }
        val firstChild = getPosition(getChildAt(0)!!)
        val viewPosition = position - firstChild
        if (viewPosition in 0 until childCount) {
            val child = getChildAt(viewPosition)
            if (getPosition(child!!) == position) {
                return child // in pre-layout, this may not match
            }
        }
        // fallback to traversal. This might be necessary in pre-layout.
        return super.findViewByPosition(position)
    }

    /**
     *
     * Returns the amount of extra space that should be laid out by LayoutManager.
     *
     *
     * By default, [LinearLayoutManager] lays out 1 extra page
     * of items while smooth scrolling and 0 otherwise. You can override this method to implement
     * your custom layout pre-cache logic.
     *
     *
     * **Note:**Laying out invisible elements generally comes with significant
     * performance cost. It's typically only desirable in places like smooth scrolling to an unknown
     * location, where 1) the extra content helps LinearLayoutManager know in advance when its
     * target is approaching, so it can decelerate early and smoothly and 2) while motion is
     * continuous.
     *
     *
     * Extending the extra layout space is especially expensive if done while the user may change
     * scrolling direction. Changing direction will cause the extra layout space to swap to the
     * opposite side of the viewport, incurring many rebinds/recycles, unless the cache is large
     * enough to handle it.
     *
     * @return The extra space that should be laid out (in pixels).
     */
    @Deprecated("Use {@link #calculateExtraLayoutSpace(RecyclerView.State, int[])} instead.")
    protected fun getExtraLayoutSpace(state: RecyclerView.State): Int {
        return if (state.hasTargetScrollPosition()) {
            orientationHelper!!.totalSpace
        } else {
            0
        }
    }

    /**
     *
     * Calculates the amount of extra space (in pixels) that should be laid out by [ ] and stores the result in `extraLayoutSpace`. `extraLayoutSpace[0]` should be used for the extra space at the top/left, and `extraLayoutSpace[1]` should be used for the extra space at the bottom/right (depending on the
     * orientation). Thus, the side where it is applied is unaffected by [ ][.getLayoutDirection] (LTR vs RTL), [.getStackFromEnd] and [ ][.getReverseLayout]. Negative values are ignored.
     *
     *
     * By default, `LinearLayoutManager` lays out 1 extra page of items while smooth
     * scrolling, in the direction of the scroll, and no extra space is laid out in all other
     * situations. You can override this method to implement your own custom pre-cache logic. Use
     * [RecyclerView.State.hasTargetScrollPosition] to find out if a smooth scroll to a
     * position is in progress, and [RecyclerView.State.getTargetScrollPosition] to find out
     * which item it is scrolling to.
     *
     *
     * **Note:**Laying out extra items generally comes with significant performance
     * cost. It's typically only desirable in places like smooth scrolling to an unknown location,
     * where 1) the extra content helps LinearLayoutManager know in advance when its target is
     * approaching, so it can decelerate early and smoothly and 2) while motion is continuous.
     *
     *
     * Extending the extra layout space is especially expensive if done while the user may change
     * scrolling direction. In the default implementation, changing direction will cause the extra
     * layout space to swap to the opposite side of the viewport, incurring many rebinds/recycles,
     * unless the cache is large enough to handle it.
     */
    protected fun calculateExtraLayoutSpace(
        state: RecyclerView.State,
        extraLayoutSpace: IntArray
    ) {
        var extraLayoutSpaceStart = 0
        var extraLayoutSpaceEnd = 0

        // If calculateExtraLayoutSpace is not overridden, call the
        // deprecated getExtraLayoutSpace for backwards compatibility
        val extraScrollSpace = getExtraLayoutSpace(state)
        if (layoutState!!.mLayoutDirection == LayoutState.LAYOUT_START) {
            extraLayoutSpaceStart = extraScrollSpace
        } else {
            extraLayoutSpaceEnd = extraScrollSpace
        }
        extraLayoutSpace[0] = extraLayoutSpaceStart
        extraLayoutSpace[1] = extraLayoutSpaceEnd
    }

    override fun smoothScrollToPosition(
        recyclerView: RecyclerView, state: RecyclerView.State?,
        position: Int
    ) {
        val linearSmoothScroller = LinearSmoothScroller(recyclerView.context)
        linearSmoothScroller.targetPosition = position
        startSmoothScroll(linearSmoothScroller)
    }

    override fun computeScrollVectorForPosition(targetPosition: Int): PointF? {
        if (childCount == 0) {
            return null
        }
        val firstChildPos = getPosition(getChildAt(0)!!)
        val direction = if (targetPosition < firstChildPos != shouldReverseLayout) -1 else 1
        return if (orientation == HORIZONTAL) {
            PointF(direction.toFloat(), 0f)
        } else {
            PointF(0f, direction.toFloat())
        }
    }

    /**
     * {@inheritDoc}
     */
    override fun onLayoutChildren(recycler: Recycler, state: RecyclerView.State) {
        // layout algorithm:
        // 1) by checking children and other variables, find an anchor coordinate and an anchor
        //  item position.
        // 2) fill towards start, stacking from bottom
        // 3) fill towards end, stacking from top
        // 4) scroll to fulfill requirements like stack from bottom.
        // create layout state
        if (pendingSavedState != null || pendingScrollPosition != RecyclerView.NO_POSITION) {
            if (state.itemCount == 0) {
                removeAndRecycleAllViews(recycler)
                return
            }
        }
        
        pendingSavedState?.let {
            if(it.hasValidAnchor()) {
                pendingScrollPosition = it.mAnchorPosition
            }
        }
        ensureLayoutState()
        layoutState?.mRecycle = false
        // resolve layout direction
        resolveShouldLayoutReverse()
        val focused = focusedChild
        if (!anchorInfo.mValid || pendingScrollPosition != RecyclerView.NO_POSITION || pendingSavedState != null) {
            anchorInfo.reset()
            anchorInfo.mLayoutFromEnd = shouldReverseLayout xor stackFromEnd
            // calculate anchor position and coordinate
            updateAnchorInfoForLayout(recycler, state, anchorInfo)
            anchorInfo.mValid = true
        } else if (focused != null && orientationHelper?.run {
                getDecoratedStart(focused) >= endAfterPadding || getDecoratedEnd(focused) <= startAfterPadding
            } == true
        ) {
            // This case relates to when the anchor child is the focused view and due to layout
            // shrinking the focused view fell outside the viewport, e.g. when soft keyboard shows
            // up after tapping an EditText which shrinks RV causing the focused view (The tapped
            // EditText which is the anchor child) to get kicked out of the screen. Will update the
            // anchor coordinate in order to make sure that the focused view is laid out. Otherwise,
            // the available space in layoutState will be calculated as negative preventing the
            // focused view from being laid out in fill.
            // Note that we won't update the anchor position between layout passes (refer to
            // TestResizingRelayoutWithAutoMeasure), which happens if we were to call
            // updateAnchorInfoForLayout for an anchor that's not the focused view (e.g. a reference
            // child which can change between layout passes).
            anchorInfo.assignFromViewAndKeepVisibleRect(focused, getPosition(focused))
        }

        // LLM may decide to layout items for "extra" pixels to account for scrolling target,
        // caching or predictive animations.
        layoutState?.let { layoutState ->
            layoutState.mLayoutDirection =
                if (layoutState.mLastScrollDelta >= 0) LayoutState.LAYOUT_END else LayoutState.LAYOUT_START
            
            reusableIntPair[0] = 0
            reusableIntPair[1] = 0
            calculateExtraLayoutSpace(state, reusableIntPair)
            var extraForStart: Int = (Math.max(0, reusableIntPair[0])
                    + orientationHelper!!.startAfterPadding)
            var extraForEnd: Int = (Math.max(0, reusableIntPair[1])
                    + orientationHelper!!.endPadding)
            if (state.isPreLayout && pendingScrollPosition != RecyclerView.NO_POSITION && pendingScrollPositionOffset != INVALID_OFFSET) {
                // if the child is visible and we are going to move it around, we should layout
                // extra items in the opposite direction to make sure new items animate nicely
                // instead of just fading in
                val existing = findViewByPosition(pendingScrollPosition)
                if (existing != null) {
                    val current: Int
                    val upcomingOffset: Int
                    if (shouldReverseLayout) {
                        current = (orientationHelper!!.endAfterPadding
                                - orientationHelper!!.getDecoratedEnd(existing))
                        upcomingOffset = current - pendingScrollPositionOffset
                    } else {
                        current = (orientationHelper!!.getDecoratedStart(existing)
                                - orientationHelper!!.startAfterPadding)
                        upcomingOffset = pendingScrollPositionOffset - current
                    }
                    if (upcomingOffset > 0) {
                        extraForStart += upcomingOffset
                    } else {
                        extraForEnd -= upcomingOffset
                    }
                }
            }
            var startOffset: Int
            var endOffset: Int
            val firstLayoutDirection: Int = if (anchorInfo.mLayoutFromEnd) {
                if (shouldReverseLayout) LayoutState.ITEM_DIRECTION_TAIL
                else LayoutState.ITEM_DIRECTION_HEAD
            } else {
                if (shouldReverseLayout) LayoutState.ITEM_DIRECTION_HEAD
                else LayoutState.ITEM_DIRECTION_TAIL
            }
            onAnchorReady(recycler, state, anchorInfo, firstLayoutDirection)
            detachAndScrapAttachedViews(recycler)
            layoutState.mInfinite = resolveIsInfinite()
            layoutState.mIsPreLayout = state.isPreLayout
            // noRecycleSpace not needed: recycling doesn't happen in below's fill
            // invocations because mScrollingOffset is set to SCROLLING_OFFSET_NaN
            layoutState.mNoRecycleSpace = 0
            if (anchorInfo.mLayoutFromEnd) {
                // fill towards start
                updateLayoutStateToFillStart(anchorInfo)
                layoutState.mExtraFillSpace = extraForStart
                fill(recycler, layoutState, state, false)
                startOffset = layoutState.mOffset
                val firstElement: Int = layoutState.mCurrentPosition
                if (layoutState.mAvailable > 0) {
                    extraForEnd += layoutState.mAvailable
                }
                // fill towards end
                updateLayoutStateToFillEnd(anchorInfo)
                layoutState.mExtraFillSpace = extraForEnd
                layoutState.mCurrentPosition += layoutState.mItemDirection
                fill(recycler, layoutState, state, false)
                endOffset = layoutState.mOffset
                if (layoutState.mAvailable > 0) {
                    // end could not consume all. add more items towards start
                    extraForStart = layoutState.mAvailable
                    updateLayoutStateToFillStart(firstElement, startOffset)
                    layoutState.mExtraFillSpace = extraForStart
                    fill(recycler, layoutState, state, false)
                    startOffset = layoutState.mOffset
                }
            } else {
                // fill towards end
                updateLayoutStateToFillEnd(anchorInfo)
                layoutState.mExtraFillSpace = extraForEnd
                fill(recycler, layoutState, state, false)
                endOffset = layoutState.mOffset
                val lastElement: Int = layoutState.mCurrentPosition
                if (layoutState.mAvailable > 0) {
                    extraForStart += layoutState.mAvailable
                }
                // fill towards start
                updateLayoutStateToFillStart(anchorInfo)
                layoutState.mExtraFillSpace = extraForStart
                layoutState.mCurrentPosition += layoutState.mItemDirection
                fill(recycler, layoutState, state, false)
                startOffset = layoutState.mOffset
                if (layoutState.mAvailable > 0) {
                    extraForEnd = layoutState.mAvailable
                    // start could not consume all it should. add more items towards end
                    updateLayoutStateToFillEnd(lastElement, endOffset)
                    layoutState.mExtraFillSpace = extraForEnd
                    fill(recycler, layoutState, state, false)
                    endOffset = layoutState.mOffset
                }
            }

            // changes may cause gaps on the UI, try to fix them.
            // TODO we can probably avoid this if neither stackFromEnd/reverseLayout/RTL values have
            // changed
            if (childCount > 0) {
                // because layout from end may be changed by scroll to position
                // we re-calculate it.
                // find which side we should check for gaps.
                if (shouldReverseLayout xor stackFromEnd) {
                    var fixOffset = fixLayoutEndGap(endOffset, recycler, state, true)
                    startOffset += fixOffset
                    endOffset += fixOffset
                    fixOffset = fixLayoutStartGap(startOffset, recycler, state, false)
                    startOffset += fixOffset
                    endOffset += fixOffset
                } else {
                    var fixOffset = fixLayoutStartGap(startOffset, recycler, state, true)
                    startOffset += fixOffset
                    endOffset += fixOffset
                    fixOffset = fixLayoutEndGap(endOffset, recycler, state, false)
                    startOffset += fixOffset
                    endOffset += fixOffset
                }
            }
            layoutForPredictiveAnimations(recycler, state, startOffset, endOffset)
            if (!state.isPreLayout) {
                orientationHelper!!.onLayoutComplete()
            } else {
                anchorInfo.reset()
            }
            lastStackFromEnd = stackFromEnd
        }
    }

    override fun onLayoutCompleted(state: RecyclerView.State?) {
        super.onLayoutCompleted(state)
        pendingSavedState = null // we don't need this anymore
        pendingScrollPosition = RecyclerView.NO_POSITION
        pendingScrollPositionOffset = INVALID_OFFSET
        anchorInfo.reset()
    }

    /**
     * Method called when Anchor position is decided. Extending class can setup accordingly or
     * even update anchor info if necessary.
     * @param recycler The recycler for the layout
     * @param state The layout state
     * @param anchorInfo The mutable POJO that keeps the position and offset.
     * @param firstLayoutItemDirection The direction of the first layout filling in terms of adapter
     * indices.
     */
    fun onAnchorReady(
        recycler: Recycler?, state: RecyclerView.State?,
        anchorInfo: AnchorInfo?, firstLayoutItemDirection: Int
    ) = Unit

    /**
     * If necessary, layouts new items for predictive animations
     */
    private fun layoutForPredictiveAnimations(
        recycler: Recycler,
        state: RecyclerView.State, startOffset: Int,
        endOffset: Int
    ) {
        // If there are scrap children that we did not layout, we need to find where they did go
        // and layout them accordingly so that animations can work as expected.
        // This case may happen if new views are added or an existing view expands and pushes
        // another view out of bounds.
        if (!state.willRunPredictiveAnimations() || childCount == 0 || state.isPreLayout
            || !supportsPredictiveItemAnimations()
        ) {
            return
        }
        // to make the logic simpler, we calculate the size of children and call fill.
        var scrapExtraStart = 0
        var scrapExtraEnd = 0
        val scrapList = recycler.scrapList
        val scrapSize = scrapList.size
        val firstChildPos = getPosition(getChildAt(0)!!)
        for (i in 0 until scrapSize) {
            val scrap = scrapList[i]
            if (scrap.callPrivateFunc("isRemoved") as? Boolean == true) {
                continue
            }
            val position = scrap.layoutPosition
            val direction =
                if (position < firstChildPos != shouldReverseLayout) LayoutState.LAYOUT_START else LayoutState.LAYOUT_END
            if (direction == LayoutState.LAYOUT_START) {
                scrapExtraStart += orientationHelper!!.getDecoratedMeasurement(scrap.itemView)
            } else {
                scrapExtraEnd += orientationHelper!!.getDecoratedMeasurement(scrap.itemView)
            }
        }
        layoutState?.let { layoutState ->
            this.layoutState?.mScrapList = scrapList
            if (scrapExtraStart > 0) {
                val anchor = getChildClosestToStart()
                updateLayoutStateToFillStart(getPosition(anchor!!), startOffset)
                layoutState.mExtraFillSpace = scrapExtraStart
                layoutState.mAvailable = 0
                layoutState.assignPositionFromScrapList()
                fill(recycler, layoutState, state, false)
            }
            if (scrapExtraEnd > 0) {
                val anchor = getChildClosestToEnd()
                updateLayoutStateToFillEnd(getPosition(anchor!!), endOffset)
                layoutState.mExtraFillSpace = scrapExtraEnd
                layoutState.mAvailable = 0
                layoutState.assignPositionFromScrapList()
                fill(recycler, layoutState, state, false)
            }
        }
        
        layoutState?.mScrapList = null
    }

    private fun updateAnchorInfoForLayout(
        recycler: Recycler, state: RecyclerView.State,
        anchorInfo: AnchorInfo
    ) {
        if (updateAnchorFromPendingData(state, anchorInfo)) {
            return
        }
        if (updateAnchorFromChildren(recycler, state, anchorInfo)) {
            return
        }
        anchorInfo.assignCoordinateFromPadding()
        anchorInfo.mPosition = if (stackFromEnd) state.itemCount - 1 else 0
    }

    /**
     * Finds an anchor child from existing Views. Most of the time, this is the view closest to
     * start or end that has a valid position (e.g. not removed).
     *
     *
     * If a child has focus, it is given priority.
     */
    private fun updateAnchorFromChildren(
        recycler: Recycler,
        state: RecyclerView.State, anchorInfo: AnchorInfo
    ): Boolean {
        if (childCount == 0) {
            return false
        }
        val focused = focusedChild
        if (focused != null && anchorInfo.isViewValidAsAnchor(focused, state)) {
            anchorInfo.assignFromViewAndKeepVisibleRect(focused, getPosition(focused))
            return true
        }
        if (lastStackFromEnd != stackFromEnd) {
            return false
        }
        val referenceChild = if (anchorInfo.mLayoutFromEnd) findReferenceChildClosestToEnd(
            recycler,
            state
        ) else findReferenceChildClosestToStart(recycler, state)
        if (referenceChild != null) {
            anchorInfo.assignFromView(referenceChild, getPosition(referenceChild))
            // If all visible views are removed in 1 pass, reference child might be out of bounds.
            // If that is the case, offset it back to 0 so that we use these pre-layout children.
            if (!state.isPreLayout && supportsPredictiveItemAnimations()) {
                // validate this child is at least partially visible. if not, offset it to start
                val notVisible = orientationHelper?.run {
                    getDecoratedStart(referenceChild) >= endAfterPadding
                            || getDecoratedEnd(referenceChild) < startAfterPadding
                } ?: false
                    
                if (notVisible) {
                    anchorInfo.mCoordinate =
                        if (anchorInfo.mLayoutFromEnd) orientationHelper!!.endAfterPadding else orientationHelper!!.startAfterPadding
                }
            }
            return true
        }
        return false
    }

    /**
     * If there is a pending scroll position or saved states, updates the anchor info from that
     * data and returns true
     */
    private fun updateAnchorFromPendingData(
        state: RecyclerView.State,
        anchorInfo: AnchorInfo
    ): Boolean {
        if (state.isPreLayout || pendingScrollPosition == RecyclerView.NO_POSITION) {
            return false
        }
        // validate scroll position
        if (pendingScrollPosition < 0 || pendingScrollPosition >= state.itemCount) {
            pendingScrollPosition = RecyclerView.NO_POSITION
            pendingScrollPositionOffset = INVALID_OFFSET
            return false
        }

        // if child is visible, try to make it a reference child and ensure it is fully visible.
        // if child is not visible, align it depending on its virtual position.
        anchorInfo.mPosition = pendingScrollPosition
        pendingSavedState?.let { pendingSavedState ->
            if (pendingSavedState.hasValidAnchor()) {
                // Anchor offset depends on how that child was laid out. Here, we update it
                // according to our current view bounds
                anchorInfo.mLayoutFromEnd = pendingSavedState.mAnchorLayoutFromEnd
                if (anchorInfo.mLayoutFromEnd) {
                    anchorInfo.mCoordinate = (orientationHelper!!.endAfterPadding
                            - pendingSavedState.mAnchorOffset)
                } else {
                    anchorInfo.mCoordinate = (orientationHelper!!.startAfterPadding
                            + pendingSavedState.mAnchorOffset)
                }
                return true
            }
        }
        
        if (pendingScrollPositionOffset == INVALID_OFFSET) {
            val child = findViewByPosition(pendingScrollPosition)
            if (child != null) {
                val childSize: Int = orientationHelper!!.getDecoratedMeasurement(child)
                if (childSize > orientationHelper!!.totalSpace) {
                    // item does not fit. fix depending on layout direction
                    anchorInfo.assignCoordinateFromPadding()
                    return true
                }
                val startGap: Int = (orientationHelper!!.getDecoratedStart(child)
                        - orientationHelper!!.startAfterPadding)
                if (startGap < 0) {
                    anchorInfo.mCoordinate = orientationHelper!!.startAfterPadding
                    anchorInfo.mLayoutFromEnd = false
                    return true
                }
                val endGap: Int = (orientationHelper!!.endAfterPadding
                        - orientationHelper!!.getDecoratedEnd(child))
                if (endGap < 0) {
                    anchorInfo.mCoordinate = orientationHelper!!.endAfterPadding
                    anchorInfo.mLayoutFromEnd = true
                    return true
                }
                anchorInfo.mCoordinate =
                    if (anchorInfo.mLayoutFromEnd) 
                        orientationHelper!!.getDecoratedEnd(child) +
                                orientationHelper!!.totalSpaceChange
                    else orientationHelper!!.getDecoratedStart(child)
            } else { // item is not visible.
                if (childCount > 0) {
                    // get position of any child, does not matter
                    val pos = getPosition(getChildAt(0)!!)
                    anchorInfo.mLayoutFromEnd = (pendingScrollPosition < pos
                            == shouldReverseLayout)
                }
                anchorInfo.assignCoordinateFromPadding()
            }
            return true
        }
        // override layout from end values for consistency
        anchorInfo.mLayoutFromEnd = shouldReverseLayout
        // if this changes, we should update prepareForDrop as well
        if (shouldReverseLayout) {
            anchorInfo.mCoordinate = (orientationHelper!!.endAfterPadding
                    - pendingScrollPositionOffset)
        } else {
            anchorInfo.mCoordinate = (orientationHelper!!.startAfterPadding
                    + pendingScrollPositionOffset)
        }
        return true
    }

    /**
     * @return The final offset amount for children
     */
    private fun fixLayoutEndGap(
        endOffSet: Int, recycler: Recycler,
        state: RecyclerView.State, canOffsetChildren: Boolean
    ): Int {
        var endOffset = endOffSet
        var gap: Int = orientationHelper!!.endAfterPadding - endOffset
        var fixOffset = 0
        if (gap > 0) {
            fixOffset = -scrollBy(-gap, recycler, state)
        } else {
            return 0 // nothing to fix
        }
        // move offset according to scroll amount
        endOffset += fixOffset
        if (canOffsetChildren) {
            // re-calculate gap, see if we could fix it
            gap = orientationHelper!!.endAfterPadding - endOffset
            if (gap > 0) {
                orientationHelper!!.offsetChildren(gap)
                return gap + fixOffset
            }
        }
        return fixOffset
    }

    /**
     * @return The final offset amount for children
     */
    private fun fixLayoutStartGap(
        startOffSet: Int, recycler: Recycler,
        state: RecyclerView.State, canOffsetChildren: Boolean
    ): Int {
        var startOffset = startOffSet
        var gap: Int = startOffset - orientationHelper!!.startAfterPadding
        var fixOffset = 0
        if (gap > 0) {
            // check if we should fix this gap.
            fixOffset = -scrollBy(gap, recycler, state)
        } else {
            return 0 // nothing to fix
        }
        startOffset += fixOffset
        if (canOffsetChildren) {
            // re-calculate gap, see if we could fix it
            gap = startOffset - orientationHelper!!.startAfterPadding
            if (gap > 0) {
                orientationHelper!!.offsetChildren(-gap)
                return fixOffset - gap
            }
        }
        return fixOffset
    }

    private fun updateLayoutStateToFillEnd(anchorInfo: AnchorInfo) {
        updateLayoutStateToFillEnd(anchorInfo.mPosition, anchorInfo.mCoordinate)
    }

    private fun updateLayoutStateToFillEnd(itemPosition: Int, offset: Int) {
        layoutState?.mAvailable = orientationHelper!!.endAfterPadding - offset
        layoutState?.mItemDirection =
            if (shouldReverseLayout) LayoutState.ITEM_DIRECTION_HEAD else LayoutState.ITEM_DIRECTION_TAIL
        layoutState?.mCurrentPosition = itemPosition
        layoutState?.mLayoutDirection = LayoutState.LAYOUT_END
        layoutState?.mOffset = offset
        layoutState?.mScrollingOffset = LayoutState.SCROLLING_OFFSET_NaN
    }

    private fun updateLayoutStateToFillStart(anchorInfo: AnchorInfo) {
        updateLayoutStateToFillStart(anchorInfo.mPosition, anchorInfo.mCoordinate)
    }

    private fun updateLayoutStateToFillStart(itemPosition: Int, offset: Int) {
        layoutState?.mAvailable = offset - orientationHelper!!.startAfterPadding
        layoutState?.mCurrentPosition = itemPosition
        layoutState?.mItemDirection =
            if (shouldReverseLayout) LayoutState.ITEM_DIRECTION_TAIL else LayoutState.ITEM_DIRECTION_HEAD
        layoutState?.mLayoutDirection = LayoutState.LAYOUT_START
        layoutState?.mOffset = offset
        layoutState?.mScrollingOffset = LayoutState.SCROLLING_OFFSET_NaN
    }

    protected fun isLayoutRTL(): Boolean {
        return layoutDirection == ViewCompat.LAYOUT_DIRECTION_RTL
    }

    fun ensureLayoutState() {
        if (layoutState == null) {
            layoutState = createLayoutState()
        }
    }

    /**
     * Test overrides this to plug some tracking and verification.
     *
     * @return A new LayoutState
     */
    fun createLayoutState(): LayoutState {
        return LayoutState()
    }

    /**
     *
     * Scroll the RecyclerView to make the position visible.
     *
     *
     * RecyclerView will scroll the minimum amount that is necessary to make the
     * target position visible. If you are looking for a similar behavior to
     * [android.widget.ListView.setSelection] or
     * [android.widget.ListView.setSelectionFromTop], use
     * [.scrollToPositionWithOffset].
     *
     *
     * Note that scroll position change will not be reflected until the next layout call.
     *
     * @param position Scroll to this adapter position
     * @see .scrollToPositionWithOffset
     */
    override fun scrollToPosition(position: Int) {
        pendingScrollPosition = position
        pendingScrollPositionOffset = INVALID_OFFSET
        pendingSavedState?.invalidateAnchor()
        requestLayout()
    }

    /**
     * Scroll to the specified adapter position with the given offset from resolved layout
     * start. Resolved layout start depends on [.getReverseLayout],
     * [ViewCompat.getLayoutDirection] and [.getStackFromEnd].
     *
     *
     * For example, if layout is [.VERTICAL] and [.getStackFromEnd] is true, calling
     * `scrollToPositionWithOffset(10, 20)` will layout such that
     * `item[10]`'s bottom is 20 pixels above the RecyclerView's bottom.
     *
     *
     * Note that scroll position change will not be reflected until the next layout call.
     *
     *
     * If you are just trying to make a position visible, use [.scrollToPosition].
     *
     * @param position Index (starting at 0) of the reference item.
     * @param offset   The distance (in pixels) between the start edge of the item view and
     * start edge of the RecyclerView.
     * @see .setReverseLayout
     * @see .scrollToPosition
     */
    fun scrollToPositionWithOffset(position: Int, offset: Int) {
        pendingScrollPosition = position
        pendingScrollPositionOffset = offset
        pendingSavedState?.invalidateAnchor()
        requestLayout()
    }


    /**
     * {@inheritDoc}
     */
    override fun scrollHorizontallyBy(
        dx: Int, recycler: Recycler?,
        state: RecyclerView.State
    ): Int {
        return if (orientation == VERTICAL) {
            0
        } else scrollBy(dx, recycler, state)
    }

    /**
     * {@inheritDoc}
     */
    override fun scrollVerticallyBy(
        dy: Int, recycler: Recycler?,
        state: RecyclerView.State
    ): Int {
        return if (orientation == HORIZONTAL) {
            0
        } else scrollBy(dy, recycler, state)
    }

    override fun computeHorizontalScrollOffset(state: RecyclerView.State): Int {
        return computeScrollOffset(state)
    }

    override fun computeVerticalScrollOffset(state: RecyclerView.State): Int {
        return computeScrollOffset(state)
    }

    override fun computeHorizontalScrollExtent(state: RecyclerView.State): Int {
        return computeScrollExtent(state)
    }

    override fun computeVerticalScrollExtent(state: RecyclerView.State): Int {
        return computeScrollExtent(state)
    }

    override fun computeHorizontalScrollRange(state: RecyclerView.State): Int {
        return computeScrollRange(state)
    }

    override fun computeVerticalScrollRange(state: RecyclerView.State): Int {
        return computeScrollRange(state)
    }

    private fun computeScrollOffset(state: RecyclerView.State): Int {
        if (childCount == 0) {
            return 0
        }
        ensureLayoutState()
        return ScrollbarHelper.computeScrollOffset(
            state, orientationHelper!!,
            findFirstVisibleChildClosestToStart(!smoothScrollbarEnabled, true),
            findFirstVisibleChildClosestToEnd(!smoothScrollbarEnabled, true),
            this, smoothScrollbarEnabled, shouldReverseLayout
        )
    }

    private fun computeScrollExtent(state: RecyclerView.State): Int {
        if (childCount == 0) {
            return 0
        }
        ensureLayoutState()
        return ScrollbarHelper.computeScrollExtent(
            state, orientationHelper!!,
            findFirstVisibleChildClosestToStart(!smoothScrollbarEnabled, true),
            findFirstVisibleChildClosestToEnd(!smoothScrollbarEnabled, true),
            this, smoothScrollbarEnabled
        )
    }

    private fun computeScrollRange(state: RecyclerView.State): Int {
        if (childCount == 0) {
            return 0
        }
        ensureLayoutState()
        return ScrollbarHelper.computeScrollRange(
            state, orientationHelper!!,
            findFirstVisibleChildClosestToStart(!smoothScrollbarEnabled, true),
            findFirstVisibleChildClosestToEnd(!smoothScrollbarEnabled, true),
            this, smoothScrollbarEnabled
        )
    }

    /**
     * When smooth scrollbar is enabled, the position and size of the scrollbar thumb is computed
     * based on the number of visible pixels in the visible items. This however assumes that all
     * list items have similar or equal widths or heights (depending on list orientation).
     * If you use a list in which items have different dimensions, the scrollbar will change
     * appearance as the user scrolls through the list. To avoid this issue,  you need to disable
     * this property.
     *
     * When smooth scrollbar is disabled, the position and size of the scrollbar thumb is based
     * solely on the number of items in the adapter and the position of the visible items inside
     * the adapter. This provides a stable scrollbar as the user navigates through a list of items
     * with varying widths / heights.
     *
     * @param enabled Whether or not to enable smooth scrollbar.
     *
     * @see .setSmoothScrollbarEnabled
     */
    fun setSmoothScrollbarEnabled(enabled: Boolean) {
        smoothScrollbarEnabled = enabled
    }

    /**
     * Returns the current state of the smooth scrollbar feature. It is enabled by default.
     *
     * @return True if smooth scrollbar is enabled, false otherwise.
     *
     * @see .setSmoothScrollbarEnabled
     */
    fun isSmoothScrollbarEnabled(): Boolean {
        return smoothScrollbarEnabled
    }

    private fun updateLayoutState(
        layoutDirection: Int, requiredSpace: Int,
        canUseExistingSpace: Boolean, state: RecyclerView.State
    ) {
        // If parent provides a hint, don't measure unlimited.
        layoutState?.mInfinite = resolveIsInfinite()
        layoutState?.mLayoutDirection = layoutDirection
        reusableIntPair[0] = 0
        reusableIntPair[1] = 0
        calculateExtraLayoutSpace(state, reusableIntPair)
        val extraForStart = Math.max(0, reusableIntPair[0])
        val extraForEnd = Math.max(0, reusableIntPair[1])
        val layoutToEnd = layoutDirection == LayoutState.LAYOUT_END
        layoutState?.mExtraFillSpace = if (layoutToEnd) extraForEnd else extraForStart
        layoutState?.mNoRecycleSpace = if (layoutToEnd) extraForStart else extraForEnd
        val scrollingOffset: Int
        if (layoutToEnd) {
            layoutState!!.mExtraFillSpace += orientationHelper!!.endPadding
            // get the first child in the direction we are going
            val child = getChildClosestToEnd()
            // the direction in which we are traversing children
            layoutState?.mItemDirection =
                if (shouldReverseLayout) LayoutState.ITEM_DIRECTION_HEAD else LayoutState.ITEM_DIRECTION_TAIL
            layoutState?.mCurrentPosition = getPosition(child!!) + layoutState!!.mItemDirection
            layoutState?.mOffset = orientationHelper!!.getDecoratedEnd(child)
            // calculate how much we can scroll without adding new children (independent of layout)
            scrollingOffset = (orientationHelper!!.getDecoratedEnd(child)
                    - orientationHelper!!.endAfterPadding)
        } else {
            val child = getChildClosestToStart()
            layoutState!!.mExtraFillSpace += orientationHelper!!.startAfterPadding
            layoutState?.mItemDirection =
                if (shouldReverseLayout) LayoutState.ITEM_DIRECTION_TAIL else LayoutState.ITEM_DIRECTION_HEAD
            layoutState?.mCurrentPosition = getPosition(child!!) + layoutState!!.mItemDirection
            layoutState?.mOffset = orientationHelper!!.getDecoratedStart(child)
            scrollingOffset = (-orientationHelper!!.getDecoratedStart(child)
                    + orientationHelper!!.startAfterPadding)
        }
        layoutState?.mAvailable = requiredSpace
        if (canUseExistingSpace) {
            layoutState!!.mAvailable -= scrollingOffset
        }
        layoutState?.mScrollingOffset = scrollingOffset
    }

    private fun resolveIsInfinite(): Boolean {
        return isInfinite || orientationHelper?.mode == View.MeasureSpec.UNSPECIFIED
                && orientationHelper?.end == 0;
    }

    private fun collectPrefetchPositionsForLayoutState(
        state: RecyclerView.State, layoutState: LayoutState,
        layoutPrefetchRegistry: LayoutPrefetchRegistry
    ) {
        val pos = layoutState.mCurrentPosition
        if (pos >= 0 && pos < state.itemCount) {
            layoutPrefetchRegistry.addPosition(pos, Math.max(0, layoutState.mScrollingOffset))
        }
    }

    override fun collectInitialPrefetchPositions(
        adapterItemCount: Int,
        layoutPrefetchRegistry: LayoutPrefetchRegistry
    ) {
        val fromEnd: Boolean
        val anchorPos: Int
        pendingSavedState?.let { pendingSavedState ->
            if (this.pendingSavedState != null && pendingSavedState.hasValidAnchor()) {
                // use restored state, since it hasn't been resolved yet
                fromEnd = pendingSavedState.mAnchorLayoutFromEnd
                anchorPos = pendingSavedState.mAnchorPosition
            } else {
                resolveShouldLayoutReverse()
                fromEnd = shouldReverseLayout
                anchorPos = if (pendingScrollPosition == RecyclerView.NO_POSITION) {
                    if (fromEnd) adapterItemCount - 1 else 0
                } else {
                    pendingScrollPosition
                }
            }
            val direction =
                if (fromEnd) LayoutState.ITEM_DIRECTION_HEAD else LayoutState.ITEM_DIRECTION_TAIL
            var targetPos = anchorPos
            for (i in 0 until initialPrefetchItemCount) {
                if (targetPos >= 0 && targetPos < adapterItemCount) {
                    layoutPrefetchRegistry.addPosition(targetPos, 0)
                } else {
                    break // no more to prefetch
                }
                targetPos += direction
            }
        }
    }

    /**
     * Sets the number of items to prefetch in
     * [.collectInitialPrefetchPositions], which defines
     * how many inner items should be prefetched when this LayoutManager's RecyclerView
     * is nested inside another RecyclerView.
     *
     *
     * Set this value to the number of items this inner LayoutManager will display when it is
     * first scrolled into the viewport. RecyclerView will attempt to prefetch that number of items
     * so they are ready, avoiding jank as the inner RecyclerView is scrolled into the viewport.
     *
     *
     * For example, take a vertically scrolling RecyclerView with horizontally scrolling inner
     * RecyclerViews. The rows always have 4 items visible in them (or 5 if not aligned). Passing
     * `4` to this method for each inner RecyclerView's LinearLayoutManager will enable
     * RecyclerView's prefetching feature to do create/bind work for 4 views within a row early,
     * before it is scrolled on screen, instead of just the default 2.
     *
     *
     * Calling this method does nothing unless the LayoutManager is in a RecyclerView
     * nested in another RecyclerView.
     *
     *
     * **Note:** Setting this value to be larger than the number of
     * views that will be visible in this view can incur unnecessary bind work, and an increase to
     * the number of Views created and in active use.
     *
     * @param itemCount Number of items to prefetch
     *
     * @see .isItemPrefetchEnabled
     * @see .getInitialPrefetchItemCount
     * @see .collectInitialPrefetchPositions
     */
    fun setInitialPrefetchItemCount(itemCount: Int) {
        initialPrefetchItemCount = itemCount
    }

    /**
     * Gets the number of items to prefetch in
     * [.collectInitialPrefetchPositions], which defines
     * how many inner items should be prefetched when this LayoutManager's RecyclerView
     * is nested inside another RecyclerView.
     *
     * @see .isItemPrefetchEnabled
     * @see .setInitialPrefetchItemCount
     * @see .collectInitialPrefetchPositions
     * @return number of items to prefetch.
     */
    fun getInitialPrefetchItemCount(): Int {
        return initialPrefetchItemCount
    }

    override fun collectAdjacentPrefetchPositions(
        dx: Int, dy: Int, state: RecyclerView.State,
        layoutPrefetchRegistry: LayoutPrefetchRegistry
    ) {
        val delta = if (orientation == HORIZONTAL) dx else dy
        if (childCount == 0 || delta == 0) {
            // can't support this scroll, so don't bother prefetching
            return
        }
        ensureLayoutState()
        val layoutDirection =
            if (delta > 0) LayoutState.LAYOUT_END else LayoutState.LAYOUT_START
        val absDelta = abs(delta)
        updateLayoutState(layoutDirection, absDelta, true, state)
        collectPrefetchPositionsForLayoutState(state, layoutState!!, layoutPrefetchRegistry)
    }

    private fun scrollBy(delta: Int, recycler: Recycler?, state: RecyclerView.State): Int {
        if (childCount == 0 || delta == 0) {
            return 0
        }
        ensureLayoutState()
        layoutState!!.mRecycle = true
        val layoutDirection =
            if (delta > 0) LayoutState.LAYOUT_END else LayoutState.LAYOUT_START
        val absDelta = Math.abs(delta)
        updateLayoutState(layoutDirection, absDelta, true, state)
        val consumed: Int = (layoutState!!.mScrollingOffset
                + fill(recycler, layoutState!!, state, false))
        if (consumed < 0) {
            return 0
        }
        val scrolled = if (absDelta > consumed) layoutDirection * consumed else delta
        orientationHelper!!.offsetChildren(-scrolled)
        layoutState!!.mLastScrollDelta = scrolled
        return scrolled
    }

    override fun assertNotInLayoutOrScroll(message: String?) {
        if (pendingSavedState == null) {
            super.assertNotInLayoutOrScroll(message)
        }
    }

    /**
     * Recycles children between given indices.
     *
     * @param startIndex inclusive
     * @param endIndex   exclusive
     */
    private fun recycleChildren(recycler: Recycler?, startIndex: Int, endIndex: Int) {
        if (startIndex == endIndex) {
            return
        }
        if (endIndex > startIndex) {
            for (i in endIndex - 1 downTo startIndex) {
                removeAndRecycleViewAt(i, recycler!!)
            }
        } else {
            for (i in startIndex downTo endIndex + 1) {
                removeAndRecycleViewAt(i, recycler!!)
            }
        }
    }

    /**
     * Recycles views that went out of bounds after scrolling towards the end of the layout.
     *
     *
     * Checks both layout position and visible position to guarantee that the view is not visible.
     *
     * @param recycler Recycler instance of [RecyclerView]
     * @param scrollingOffset This can be used to add additional padding to the visible area. This
     * is used to detect children that will go out of bounds after scrolling,
     * without actually moving them.
     * @param noRecycleSpace Extra space that should be excluded from recycling. This is the space
     * from `extraLayoutSpace[0]`, calculated in [                       ][.calculateExtraLayoutSpace].
     */
    private fun recycleViewsFromStart(
        recycler: Recycler?, scrollingOffset: Int,
        noRecycleSpace: Int
    ) {
        if (scrollingOffset < 0) {
            return
        }
        // ignore padding, ViewGroup may not clip children.
        val limit = scrollingOffset - noRecycleSpace
        val childCount = childCount
        if (shouldReverseLayout) {
            for (i in childCount - 1 downTo 0) {
                getChildAt(i)?.let { child ->
                    if (orientationHelper!!.getDecoratedEnd(child) > limit
                        || orientationHelper!!.getTransformedEndWithDecoration(child) > limit
                    ) {
                        // stop here
                        recycleChildren(recycler, childCount - 1, i)
                        return
                    }
                }
            }
        } else {
            for (i in 0 until childCount) {
                getChildAt(i)?.let { child ->
                    if (orientationHelper!!.getDecoratedEnd(child) > limit
                        || orientationHelper!!.getTransformedEndWithDecoration(child) > limit
                    ) {
                        // stop here
                        recycleChildren(recycler, 0, i)
                        return
                    }
                }
            }
        }
    }


    /**
     * Recycles views that went out of bounds after scrolling towards the start of the layout.
     *
     *
     * Checks both layout position and visible position to guarantee that the view is not visible.
     *
     * @param recycler Recycler instance of [RecyclerView]
     * @param scrollingOffset This can be used to add additional padding to the visible area. This
     * is used to detect children that will go out of bounds after scrolling,
     * without actually moving them.
     * @param noRecycleSpace Extra space that should be excluded from recycling. This is the space
     * from `extraLayoutSpace[1]`, calculated in [                       ][.calculateExtraLayoutSpace].
     */
    private fun recycleViewsFromEnd(
        recycler: Recycler?, scrollingOffset: Int,
        noRecycleSpace: Int
    ) {
        val childCount = childCount
        if (scrollingOffset < 0) {
            return
        }
        val limit: Int = orientationHelper!!.end - scrollingOffset + noRecycleSpace
        if (shouldReverseLayout) {
            for (i in 0 until childCount) {
                getChildAt(i)?.let { child ->
                    if (orientationHelper!!.getDecoratedStart(child) < limit
                        || orientationHelper!!.getTransformedStartWithDecoration(child) < limit
                    ) {
                        // stop here
                        recycleChildren(recycler, 0, i)
                        return
                    }
                }

            }
        } else {
            for (i in childCount - 1 downTo 0) {
                getChildAt(i)?.let { child ->
                    if (orientationHelper!!.getDecoratedStart(child) < limit
                        || orientationHelper!!.getTransformedStartWithDecoration(child) < limit
                    ) {
                        // stop here
                        recycleChildren(recycler, childCount - 1, i)
                        return
                    }
                }
            }
        }
    }

    /**
     * Helper method to call appropriate recycle method depending on current layout direction
     *
     * @param recycler    Current recycler that is attached to RecyclerView
     * @param layoutState Current layout state. Right now, this object does not change but
     * we may consider moving it out of this view so passing around as a
     * parameter for now, rather than accessing [.mLayoutState]
     * @see .recycleViewsFromStart
     * @see .recycleViewsFromEnd
     * @see LayoutState.mLayoutDirection
     */
    private fun recycleByLayoutState(
        recycler: Recycler?,
        layoutState: LayoutState
    ) {
        if (!layoutState.mRecycle || layoutState.mInfinite) {
            return
        }
        val scrollingOffset = layoutState.mScrollingOffset
        val noRecycleSpace = layoutState.mNoRecycleSpace
        if (layoutState.mLayoutDirection == LayoutState.LAYOUT_START) {
            recycleViewsFromEnd(recycler, scrollingOffset, noRecycleSpace)
        } else {
            recycleViewsFromStart(recycler, scrollingOffset, noRecycleSpace)
        }
    }

    /**
     * The magic functions :). Fills the given layout, defined by the layoutState. This is fairly
     * independent from the rest of the [LinearLayoutManager]
     * and with little change, can be made publicly available as a helper class.
     *
     * @param recycler        Current recycler that is attached to RecyclerView
     * @param layoutState     Configuration on how we should fill out the available space.
     * @param state           Context passed by the RecyclerView to control scroll steps.
     * @param stopOnFocusable If true, filling stops in the first focusable new child
     * @return Number of pixels that it added. Useful for scroll functions.
     */
    fun fill(
        recycler: Recycler?, layoutState: LayoutState,
        state: RecyclerView.State, stopOnFocusable: Boolean
    ): Int {
        // max offset we should set is mFastScroll + available
        val start = layoutState.mAvailable
        if (layoutState.mScrollingOffset != LayoutState.SCROLLING_OFFSET_NaN) {
            // TODO ugly bug fix. should not happen
            if (layoutState.mAvailable < 0) {
                layoutState.mScrollingOffset += layoutState.mAvailable
            }
            recycleByLayoutState(recycler, layoutState)
        }
        var remainingSpace = layoutState.mAvailable + layoutState.mExtraFillSpace
        val layoutChunkResult: LayoutChunkResult = layoutChunkResult
        while ((layoutState.mInfinite || remainingSpace > 0) && layoutState.hasMore(state)) {
            layoutChunkResult.resetInternal()
            layoutChunk(recycler, state, layoutState, layoutChunkResult)
            if (layoutChunkResult.mFinished) {
                break
            }
            layoutState.mOffset += layoutChunkResult.mConsumed * layoutState.mLayoutDirection
            /**
             * Consume the available space if:
             * * layoutChunk did not request to be ignored
             * * OR we are laying out scrap children
             * * OR we are not doing pre-layout
             */
            if (!layoutChunkResult.mIgnoreConsumed || layoutState.mScrapList != null || !state.isPreLayout
            ) {
                layoutState.mAvailable -= layoutChunkResult.mConsumed
                // we keep a separate remaining space because mAvailable is important for recycling
                remainingSpace -= layoutChunkResult.mConsumed
            }
            if (layoutState.mScrollingOffset != LayoutState.SCROLLING_OFFSET_NaN) {
                layoutState.mScrollingOffset += layoutChunkResult.mConsumed
                if (layoutState.mAvailable < 0) {
                    layoutState.mScrollingOffset += layoutState.mAvailable
                }
                recycleByLayoutState(recycler, layoutState)
            }
            if (stopOnFocusable && layoutChunkResult.mFocusable) {
                break
            }
        }
        return start - layoutState.mAvailable
    }

    private fun layoutChunk(
        recycler: Recycler?, state: RecyclerView.State?,
        layoutState: LayoutState, result: LayoutChunkResult
    ) {
        val view = layoutState.next(recycler!!)
        if (view == null) {
            // if we are laying out views in scrap, this may return null which means there is
            // no more items to layout.
            result.mFinished = true
            return
        }
        val params = view.layoutParams as RecyclerView.LayoutParams
        if (layoutState.mScrapList == null) {
            if (shouldReverseLayout == (layoutState.mLayoutDirection
                        == LayoutState.LAYOUT_START)
            ) {
                addView(view)
            } else {
                addView(view, 0)
            }
        } else {
            if (shouldReverseLayout == (layoutState.mLayoutDirection
                        == LayoutState.LAYOUT_START)
            ) {
                addDisappearingView(view)
            } else {
                addDisappearingView(view, 0)
            }
        }
        measureChildWithMargins(view, 0, 0)
        result.mConsumed = orientationHelper!!.getDecoratedMeasurement(view)
        val left: Int
        val top: Int
        val right: Int
        val bottom: Int
        if (orientation == VERTICAL) {
            if (isLayoutRTL()) {
                right = width - paddingRight
                left = right - orientationHelper!!.getDecoratedMeasurementInOther(view)
            } else {
                left = paddingLeft
                right = left + orientationHelper!!.getDecoratedMeasurementInOther(view)
            }
            if (layoutState.mLayoutDirection == LayoutState.LAYOUT_START) {
                bottom = layoutState.mOffset
                top = layoutState.mOffset - result.mConsumed
            } else {
                top = layoutState.mOffset
                bottom = layoutState.mOffset + result.mConsumed
            }
        } else {
            top = paddingTop
            bottom = top + orientationHelper!!.getDecoratedMeasurementInOther(view)
            if (layoutState.mLayoutDirection == LayoutState.LAYOUT_START) {
                right = layoutState.mOffset
                left = layoutState.mOffset - result.mConsumed
            } else {
                left = layoutState.mOffset
                right = layoutState.mOffset + result.mConsumed
            }
        }
        // We calculate everything with View's bounding box (which includes decor and margins)
        // To calculate correct layout position, we subtract margins.
        layoutDecoratedWithMargins(view, left, top, right, bottom)
        // Consume the available space if the view is not removed OR changed
        if (params.isItemRemoved || params.isItemChanged) {
            result.mIgnoreConsumed = true
        }
        result.mFocusable = view.hasFocusable()
    }

    /**
     * Converts a focusDirection to orientation.
     *
     * @param focusDirection One of [View.FOCUS_UP], [View.FOCUS_DOWN],
     * [View.FOCUS_LEFT], [View.FOCUS_RIGHT],
     * [View.FOCUS_BACKWARD], [View.FOCUS_FORWARD]
     * or 0 for not applicable
     * @return [LayoutState.LAYOUT_START] or [LayoutState.LAYOUT_END] if focus direction
     * is applicable to current state, [LayoutState.INVALID_LAYOUT] otherwise.
     */
    fun convertFocusDirectionToLayoutDirection(focusDirection: Int): Int {
        when (focusDirection) {
            View.FOCUS_BACKWARD -> if (orientation == VERTICAL) {
                return LayoutState.LAYOUT_START
            } else return if (isLayoutRTL()) {
                LayoutState.LAYOUT_END
            } else {
                LayoutState.LAYOUT_START
            }
            View.FOCUS_FORWARD -> return when {
                orientation == VERTICAL -> {
                    LayoutState.LAYOUT_END
                }
                isLayoutRTL() -> {
                    LayoutState.LAYOUT_START
                }
                else -> {
                    LayoutState.LAYOUT_END
                }
            }
            View.FOCUS_UP -> return if (orientation == VERTICAL) LayoutState.LAYOUT_START
                else LayoutState.INVALID_LAYOUT
            View.FOCUS_DOWN -> return if (orientation == VERTICAL) LayoutState.LAYOUT_END
                else LayoutState.INVALID_LAYOUT
            View.FOCUS_LEFT -> return if (orientation == HORIZONTAL) LayoutState.LAYOUT_START
                else LayoutState.INVALID_LAYOUT
            View.FOCUS_RIGHT -> return if (orientation == HORIZONTAL) LayoutState.LAYOUT_END
                else LayoutState.INVALID_LAYOUT
            else -> {
                return LayoutState.INVALID_LAYOUT
            }
        }
    }

    /**
     * Convenience method to find the child closes to start. Caller should check it has enough
     * children.
     *
     * @return The child closes to start of the layout from user's perspective.
     */
    private fun getChildClosestToStart(): View? {
        return getChildAt(if (shouldReverseLayout) childCount - 1 else 0)
    }

    /**
     * Convenience method to find the child closes to end. Caller should check it has enough
     * children.
     *
     * @return The child closes to end of the layout from user's perspective.
     */
    private fun getChildClosestToEnd(): View? {
        return getChildAt(if (shouldReverseLayout) 0 else childCount - 1)
    }

    /**
     * Convenience method to find the visible child closes to start. Caller should check if it has
     * enough children.
     *
     * @param completelyVisible Whether child should be completely visible or not
     * @return The first visible child closest to start of the layout from user's perspective.
     */
    private fun findFirstVisibleChildClosestToStart(
        completelyVisible: Boolean,
        acceptPartiallyVisible: Boolean
    ): View? {
        return if (shouldReverseLayout) {
            findOneVisibleChild(
                childCount - 1, -1, completelyVisible,
                acceptPartiallyVisible
            )
        } else {
            findOneVisibleChild(
                0, childCount, completelyVisible,
                acceptPartiallyVisible
            )
        }
    }

    /**
     * Convenience method to find the visible child closes to end. Caller should check if it has
     * enough children.
     *
     * @param completelyVisible Whether child should be completely visible or not
     * @return The first visible child closest to end of the layout from user's perspective.
     */
    private fun findFirstVisibleChildClosestToEnd(
        completelyVisible: Boolean,
        acceptPartiallyVisible: Boolean
    ): View? {
        return if (shouldReverseLayout) {
            findOneVisibleChild(
                0, childCount, completelyVisible,
                acceptPartiallyVisible
            )
        } else {
            findOneVisibleChild(
                childCount - 1, -1, completelyVisible,
                acceptPartiallyVisible
            )
        }
    }


    /**
     * Among the children that are suitable to be considered as an anchor child, returns the one
     * closest to the end of the layout.
     *
     *
     * Due to ambiguous adapter updates or children being removed, some children's positions may be
     * invalid. This method is a best effort to find a position within adapter bounds if possible.
     *
     *
     * It also prioritizes children that are within the visible bounds.
     * @return A View that can be used an an anchor View.
     */
    private fun findReferenceChildClosestToEnd(
        recycler: Recycler,
        state: RecyclerView.State
    ): View? {
        return if (shouldReverseLayout) findFirstReferenceChild(
            recycler,
            state
        ) else findLastReferenceChild(recycler, state)
    }

    /**
     * Among the children that are suitable to be considered as an anchor child, returns the one
     * closest to the start of the layout.
     *
     *
     * Due to ambiguous adapter updates or children being removed, some children's positions may be
     * invalid. This method is a best effort to find a position within adapter bounds if possible.
     *
     *
     * It also prioritizes children that are within the visible bounds.
     *
     * @return A View that can be used an an anchor View.
     */
    private fun findReferenceChildClosestToStart(
        recycler: Recycler,
        state: RecyclerView.State
    ): View? {
        return if (shouldReverseLayout) findLastReferenceChild(
            recycler,
            state
        ) else findFirstReferenceChild(recycler, state)
    }

    private fun findFirstReferenceChild(recycler: Recycler, state: RecyclerView.State): View? {
        return findReferenceChild(recycler, state, 0, childCount, state.itemCount)
    }

    private fun findLastReferenceChild(recycler: Recycler, state: RecyclerView.State): View? {
        return findReferenceChild(recycler, state, childCount - 1, -1, state.itemCount)
    }

    // overridden by GridLayoutManager
    private fun findReferenceChild(
        recycler: Recycler?, state: RecyclerView.State?,
        start: Int, end: Int, itemCount: Int
    ): View? {
        ensureLayoutState()
        var invalidMatch: View? = null
        var outOfBoundsMatch: View? = null
        val boundsStart: Int = orientationHelper!!.startAfterPadding
        val boundsEnd: Int = orientationHelper!!.endAfterPadding
        val diff = if (end > start) 1 else -1
        var i = start
        while (i != end) {
            val view = getChildAt(i)
            val position = getPosition(view!!)
            if (position >= 0 && position < itemCount) {
                if ((view.layoutParams as RecyclerView.LayoutParams).isItemRemoved) {
                    if (invalidMatch == null) {
                        invalidMatch = view // removed item, least preferred
                    }
                } else if (orientationHelper!!.getDecoratedStart(view) >= boundsEnd
                    || orientationHelper!!.getDecoratedEnd(view) < boundsStart
                ) {
                    if (outOfBoundsMatch == null) {
                        outOfBoundsMatch = view // item is not visible, less preferred
                    }
                } else {
                    return view
                }
            }
            i += diff
        }
        return outOfBoundsMatch ?: invalidMatch
    }

    // returns the out-of-bound child view closest to RV's end bounds. An out-of-bound child is
    // defined as a child that's either partially or fully invisible (outside RV's padding area).
    private fun findPartiallyOrCompletelyInvisibleChildClosestToEnd(): View? {
        return if (shouldReverseLayout) findFirstPartiallyOrCompletelyInvisibleChild() else findLastPartiallyOrCompletelyInvisibleChild()
    }

    // returns the out-of-bound child view closest to RV's starting bounds. An out-of-bound child is
    // defined as a child that's either partially or fully invisible (outside RV's padding area).
    private fun findPartiallyOrCompletelyInvisibleChildClosestToStart(): View? {
        return if (shouldReverseLayout) findLastPartiallyOrCompletelyInvisibleChild() else findFirstPartiallyOrCompletelyInvisibleChild()
    }

    private fun findFirstPartiallyOrCompletelyInvisibleChild(): View? {
        return findOnePartiallyOrCompletelyInvisibleChild(0, childCount)
    }

    private fun findLastPartiallyOrCompletelyInvisibleChild(): View? {
        return findOnePartiallyOrCompletelyInvisibleChild(childCount - 1, -1)
    }

    /**
     * Returns the adapter position of the first visible view. This position does not include
     * adapter changes that were dispatched after the last layout pass.
     *
     *
     * Note that, this value is not affected by layout orientation or item order traversal.
     * ([.setReverseLayout]). Views are sorted by their positions in the adapter,
     * not in the layout.
     *
     *
     * If RecyclerView has item decorators, they will be considered in calculations as well.
     *
     *
     * LayoutManager may pre-cache some views that are not necessarily visible. Those views
     * are ignored in this method.
     *
     * @return The adapter position of the first visible item or [RecyclerView.NO_POSITION] if
     * there aren't any visible items.
     * @see .findFirstCompletelyVisibleItemPosition
     * @see .findLastVisibleItemPosition
     */
    fun findFirstVisibleItemPosition(): Int {
        val child = findOneVisibleChild(0, childCount,
            completelyVisible = false,
            acceptPartiallyVisible = true
        )
        return child?.let { getPosition(it) } ?: RecyclerView.NO_POSITION
    }

    /**
     * Returns the adapter position of the first fully visible view. This position does not include
     * adapter changes that were dispatched after the last layout pass.
     *
     *
     * Note that bounds check is only performed in the current orientation. That means, if
     * LayoutManager is horizontal, it will only check the view's left and right edges.
     *
     * @return The adapter position of the first fully visible item or
     * [RecyclerView.NO_POSITION] if there aren't any visible items.
     * @see .findFirstVisibleItemPosition
     * @see .findLastCompletelyVisibleItemPosition
     */
    fun findFirstCompletelyVisibleItemPosition(): Int {
        val child = findOneVisibleChild(0, childCount,
            completelyVisible = true,
            acceptPartiallyVisible = false
        )
        return child?.let { getPosition(it) } ?: RecyclerView.NO_POSITION
    }

    /**
     * Returns the adapter position of the last visible view. This position does not include
     * adapter changes that were dispatched after the last layout pass.
     *
     *
     * Note that, this value is not affected by layout orientation or item order traversal.
     * ([.setReverseLayout]). Views are sorted by their positions in the adapter,
     * not in the layout.
     *
     *
     * If RecyclerView has item decorators, they will be considered in calculations as well.
     *
     *
     * LayoutManager may pre-cache some views that are not necessarily visible. Those views
     * are ignored in this method.
     *
     * @return The adapter position of the last visible view or [RecyclerView.NO_POSITION] if
     * there aren't any visible items.
     * @see .findLastCompletelyVisibleItemPosition
     * @see .findFirstVisibleItemPosition
     */
    fun findLastVisibleItemPosition(): Int {
        val child = findOneVisibleChild(childCount - 1, -1,
            completelyVisible = false,
            acceptPartiallyVisible = true
        )
        return child?.let { getPosition(it) } ?: RecyclerView.NO_POSITION
    }

    /**
     * Returns the adapter position of the last fully visible view. This position does not include
     * adapter changes that were dispatched after the last layout pass.
     *
     *
     * Note that bounds check is only performed in the current orientation. That means, if
     * LayoutManager is horizontal, it will only check the view's left and right edges.
     *
     * @return The adapter position of the last fully visible view or
     * [RecyclerView.NO_POSITION] if there aren't any visible items.
     * @see .findLastVisibleItemPosition
     * @see .findFirstCompletelyVisibleItemPosition
     */
    fun findLastCompletelyVisibleItemPosition(): Int {
        val child = findOneVisibleChild(childCount - 1, -1,
            completelyVisible = true,
            acceptPartiallyVisible = false
        )
        return child?.let { getPosition(it) } ?: RecyclerView.NO_POSITION
    }

    // Returns the first child that is visible in the provided index range, i.e. either partially or
    // fully visible depending on the arguments provided. Completely invisible children are not
    // acceptable by this method, but could be returned
    // using #findOnePartiallyOrCompletelyInvisibleChild
    private fun findOneVisibleChild(
        fromIndex: Int, toIndex: Int, completelyVisible: Boolean,
        acceptPartiallyVisible: Boolean
    ): View? {
        ensureLayoutState()
        return null
    }

    private fun findOnePartiallyOrCompletelyInvisibleChild(fromIndex: Int, toIndex: Int): View? {
        ensureLayoutState()
        return null
    }

    /**
     * Used for debugging.
     * Logs the internal representation of children to default logger.
     */
    private fun logChildren() {
    }

    /**
     * Used for debugging.
     * Validates that child views are laid out in correct order. This is important because rest of
     * the algorithm relies on this constraint.
     *
     * In default layout, child 0 should be closest to screen position 0 and last child should be
     * closest to position WIDTH or HEIGHT.
     * In reverse layout, last child should be closes to screen position 0 and first child should
     * be closest to position WIDTH  or HEIGHT
     */
    fun validateChildOrder() {
        if (childCount < 1) {
            return
        }
        val lastPos = getPosition(getChildAt(0)!!)
        val lastScreenLoc: Int = orientationHelper!!.getDecoratedStart(getChildAt(0)!!)
        if (shouldReverseLayout) {
            for (i in 1 until childCount) {
                val child = getChildAt(i)
                val pos = getPosition(child!!)
                val screenLoc: Int = orientationHelper!!.getDecoratedStart(child)
                if (pos < lastPos) {
                    logChildren()
                    throw RuntimeException(
                        "detected invalid position. loc invalid? "
                                + (screenLoc < lastScreenLoc)
                    )
                }
                if (screenLoc > lastScreenLoc) {
                    logChildren()
                    throw RuntimeException("detected invalid location")
                }
            }
        } else {
            for (i in 1 until childCount) {
                val child = getChildAt(i)
                val pos = getPosition((child)!!)
                val screenLoc: Int = orientationHelper!!.getDecoratedStart(child)
                if (pos < lastPos) {
                    logChildren()
                    throw RuntimeException(
                        ("detected invalid position. loc invalid? "
                                + (screenLoc < lastScreenLoc))
                    )
                }
                if (screenLoc < lastScreenLoc) {
                    logChildren()
                    throw RuntimeException("detected invalid location")
                }
            }
        }
    }

    override fun supportsPredictiveItemAnimations(): Boolean {
        return pendingSavedState == null && lastStackFromEnd == stackFromEnd
    }

    /**
     * {@inheritDoc}
     */
    // This method is only intended to be called (and should only ever be called) by
    // ItemTouchHelper.
    override fun prepareForDrop(view: View, target: View, x: Int, y: Int) {
        assertNotInLayoutOrScroll("Cannot drop a view during a scroll or layout calculation")
        ensureLayoutState()
        resolveShouldLayoutReverse()
        val myPos = getPosition(view)
        val targetPos = getPosition(target)
        val dropDirection =
            if (myPos < targetPos) LayoutState.ITEM_DIRECTION_TAIL else LayoutState.ITEM_DIRECTION_HEAD
        if (shouldReverseLayout) {
            if (dropDirection == LayoutState.ITEM_DIRECTION_TAIL) {
                scrollToPositionWithOffset(
                    targetPos, (
                            orientationHelper!!.endAfterPadding
                                    - (orientationHelper!!.getDecoratedStart(target)
                                    + orientationHelper!!.getDecoratedMeasurement(view)))
                )
            } else {
                scrollToPositionWithOffset(
                    targetPos, (
                            orientationHelper!!.endAfterPadding
                                    - orientationHelper!!.getDecoratedEnd(target))
                )
            }
        } else {
            if (dropDirection == LayoutState.ITEM_DIRECTION_HEAD) {
                scrollToPositionWithOffset(targetPos, orientationHelper!!.getDecoratedStart(target))
            } else {
                scrollToPositionWithOffset(
                    targetPos, (
                            orientationHelper!!.getDecoratedEnd(target)
                                    - orientationHelper!!.getDecoratedMeasurement(view))
                )
            }
        }
    }
    
    companion object {
        const val HORIZONTAL = RecyclerView.HORIZONTAL
        const val VERTICAL = RecyclerView.VERTICAL
        const val INVALID_OFFSET = Integer.MIN_VALUE
        const val MAX_SCROLL_FACTOR = 1 / 3f
    }

    class SavedState(
        var mAnchorPosition: Int = 0,
        var mAnchorOffset: Int = 0,
        var mAnchorLayoutFromEnd: Boolean = false
    ) : Parcelable {

        constructor(parcel: Parcel) : this(
            mAnchorPosition = parcel.readInt(),
            mAnchorOffset = parcel.readInt(),
            mAnchorLayoutFromEnd = parcel.readInt() == 1
        )

        constructor(other: SavedState) : this(
            mAnchorPosition = other.mAnchorPosition,
            mAnchorOffset = other.mAnchorOffset,
            mAnchorLayoutFromEnd = other.mAnchorLayoutFromEnd
        )

        fun hasValidAnchor(): Boolean {
            return mAnchorPosition >= 0
        }

        fun invalidateAnchor() {
            mAnchorPosition = RecyclerView.NO_POSITION
        }

        override fun describeContents(): Int {
            return 0
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeInt(mAnchorPosition)
            dest.writeInt(mAnchorOffset)
            dest.writeInt(if (mAnchorLayoutFromEnd) 1 else 0)
        }

        @JvmField
        val CREATOR: Creator<SavedState?> =
            object : Creator<SavedState?> {
                override fun createFromParcel(sd: Parcel): SavedState {
                    return SavedState(sd)
                }

                override fun newArray(size: Int): Array<SavedState?> {
                    return arrayOfNulls(size)
                }
            }
    }

    class LayoutState {
        
        /**
         * We may not want to recycle children in some cases (e.g. layout)
         */
        var mRecycle = true

        /**
         * Pixel offset where layout should start
         */
        var mOffset = 0

        /**
         * Number of pixels that we should fill, in the layout direction.
         */
        var mAvailable = 0

        /**
         * Current position on the adapter to get the next item.
         */
        var mCurrentPosition = 0

        /**
         * Defines the direction in which the data adapter is traversed.
         * Should be [.ITEM_DIRECTION_HEAD] or [.ITEM_DIRECTION_TAIL]
         */
        var mItemDirection = 0

        /**
         * Defines the direction in which the layout is filled.
         * Should be [.LAYOUT_START] or [.LAYOUT_END]
         */
        var mLayoutDirection = 0

        /**
         * Used when LayoutState is constructed in a scrolling state.
         * It should be set the amount of scrolling we can make without creating a new view.
         * Settings this is required for efficient view recycling.
         */
        var mScrollingOffset = 0

        /**
         * Used if you want to pre-layout items that are not yet visible.
         * The difference with [.mAvailable] is that, when recycling, distance laid out for
         * [.mExtraFillSpace] is not considered to avoid recycling visible children.
         */
        var mExtraFillSpace = 0

        /**
         * Contains the [.calculateExtraLayoutSpace]  extra layout
         * space} that should be excluded for recycling when cleaning up the tail of the list during
         * a smooth scroll.
         */
        var mNoRecycleSpace = 0

        /**
         * Equal to [RecyclerView.State.isPreLayout]. When consuming scrap, if this value
         * is set to true, we skip removed views since they should not be laid out in post layout
         * step.
         */
        var mIsPreLayout = false

        /**
         * The most recent [.scrollBy]
         * amount.
         */
        var mLastScrollDelta = 0

        /**
         * When LLM needs to layout particular views, it sets this list in which case, LayoutState
         * will only return views from this list and return null if it cannot find an item.
         */
        var mScrapList: List<RecyclerView.ViewHolder>? = null

        /**
         * Used when there is no limit in how many views can be laid out.
         */
        var mInfinite = false

        /**
         * @return true if there are more items in the data adapter
         */
        fun hasMore(state: RecyclerView.State): Boolean {
            return mCurrentPosition >= 0 && mCurrentPosition < state.itemCount
        }

        /**
         * Gets the view for the next element that we should layout.
         * Also updates current item index to the next item, based on [.mItemDirection]
         *
         * @return The next element that we should layout.
         */
        fun next(recycler: Recycler): View? {
            if (mScrapList != null) {
                return nextViewFromScrapList()
            }
            val view = recycler.getViewForPosition(mCurrentPosition)
            mCurrentPosition += mItemDirection
            return view
        }

        /**
         * Returns the next item from the scrap list.
         *
         *
         * Upon finding a valid VH, sets current item position to VH.itemPosition + mItemDirection
         *
         * @return View if an item in the current position or direction exists if not null.
         */
        private fun nextViewFromScrapList(): View? {
            mScrapList?.let { list ->
                val size = list.size
                for (i in 0 until size) {
                    val view = list[i].itemView
                    val lp = view.layoutParams as RecyclerView.LayoutParams
                    if (lp.isItemRemoved) {
                        continue
                    }
                    if (mCurrentPosition == lp.viewLayoutPosition) {
                        assignPositionFromScrapList(view)
                        return view
                    }
                }
            }

            return null
        }

        fun assignPositionFromScrapList() {
            assignPositionFromScrapList(null)
        }

        fun assignPositionFromScrapList(ignore: View?) {
            val closest = nextViewInLimitedList(ignore)
            mCurrentPosition = if (closest == null) {
                RecyclerView.NO_POSITION
            } else {
                (closest.layoutParams as RecyclerView.LayoutParams)
                    .viewLayoutPosition
            }
        }

        fun nextViewInLimitedList(ignore: View?): View? {
            val list = mScrapList ?: return null
            val size = list.size
            var closest: View? = null
            var closestDistance = Int.MAX_VALUE
            check(!(mIsPreLayout)) { "Scrap list cannot be used in pre layout" }
            for (i in 0 until size) {
                val view = list[i].itemView
                val lp = view.layoutParams as RecyclerView.LayoutParams
                if (view === ignore || lp.isItemRemoved) {
                    continue
                }
                val distance = ((lp.viewLayoutPosition - mCurrentPosition)
                        * mItemDirection)
                if (distance < 0) {
                    continue  // item is not in current direction
                }
                if (distance < closestDistance) {
                    closest = view
                    closestDistance = distance
                    if (distance == 0) {
                        break
                    }
                }
            }
            return closest
        }
        
        companion object {
            const val LAYOUT_START = -1

            const val LAYOUT_END = 1

            const val INVALID_LAYOUT = Int.MIN_VALUE

            const val ITEM_DIRECTION_HEAD = -1

            const val ITEM_DIRECTION_TAIL = 1

            const val SCROLLING_OFFSET_NaN = Int.MIN_VALUE
        }
    }
    
    class AnchorInfo {
        
        init {
            reset()
        }
        
        var mOrientationHelper: OrientationHelper? = null
        var mPosition = 0
        var mCoordinate = 0
        var mLayoutFromEnd = false
        var mValid = false

        fun assignCoordinateFromPadding() {
            mOrientationHelper?.let {
                mCoordinate =
                    if (mLayoutFromEnd) 
                        it.endAfterPadding 
                    else 
                        it.startAfterPadding
            }
            
        }

        fun reset() {
            mPosition = RecyclerView.NO_POSITION
            mCoordinate = INVALID_OFFSET
            mLayoutFromEnd = false
            mValid = false
        }

        override fun toString(): String {
            return ("AnchorInfo{"
                    + "mPosition=" + mPosition
                    + ", mCoordinate=" + mCoordinate
                    + ", mLayoutFromEnd=" + mLayoutFromEnd
                    + ", mValid=" + mValid
                    + '}')
        }

        fun isViewValidAsAnchor(child: View, state: RecyclerView.State): Boolean {
            val lp = child.layoutParams as RecyclerView.LayoutParams
            return !lp.isItemRemoved && lp.viewLayoutPosition >= 0 && lp.viewLayoutPosition < state.itemCount
        }

        fun assignFromViewAndKeepVisibleRect(child: View?, position: Int) {
            mOrientationHelper?.let { orientationHelper ->
                val spaceChange: Int = orientationHelper.totalSpaceChange
                if (spaceChange >= 0) {
                    assignFromView(child, position)
                    return
                }
                mPosition = position
                if (mLayoutFromEnd) {
                    val prevLayoutEnd: Int = orientationHelper.endAfterPadding - spaceChange
                    val childEnd = orientationHelper.getDecoratedEnd(child!!)
                    val previousEndMargin = prevLayoutEnd - childEnd
                    mCoordinate = orientationHelper.endAfterPadding - previousEndMargin
                    // ensure we did not push child's top out of bounds because of this
                    if (previousEndMargin > 0) { // we have room to shift bottom if necessary
                        val childSize = orientationHelper.getDecoratedMeasurement(child)
                        val estimatedChildStart = mCoordinate - childSize
                        val layoutStart: Int = orientationHelper.startAfterPadding
                        val previousStartMargin = (orientationHelper.getDecoratedStart(child)
                                - layoutStart)
                        val startReference = layoutStart + Math.min(previousStartMargin, 0)
                        val startMargin = estimatedChildStart - startReference
                        if (startMargin < 0) {
                            // offset to make top visible but not too much
                            mCoordinate += Math.min(previousEndMargin, -startMargin)
                        }
                    }
                } else {
                    val childStart = orientationHelper.getDecoratedStart(child!!)
                    val startMargin: Int = childStart - orientationHelper.startAfterPadding
                    mCoordinate = childStart
                    if (startMargin > 0) { // we have room to fix end as well
                        val estimatedEnd = (childStart
                                + orientationHelper.getDecoratedMeasurement(child))
                        val previousLayoutEnd: Int = (orientationHelper.endAfterPadding
                                - spaceChange)
                        val previousEndMargin = (previousLayoutEnd
                                - orientationHelper.getDecoratedEnd(child))
                        val endReference: Int = (orientationHelper.endAfterPadding
                                - Math.min(0, previousEndMargin))
                        val endMargin = endReference - estimatedEnd
                        if (endMargin < 0) {
                            mCoordinate -= Math.min(startMargin, -endMargin)
                        }
                    }
                }
            }
            
            
        }

        fun assignFromView(child: View?, position: Int) {
            mCoordinate = if (mLayoutFromEnd) {
                (mOrientationHelper!!.getDecoratedEnd(child!!)
                        + mOrientationHelper!!.totalSpaceChange)
            } else {
                mOrientationHelper!!.getDecoratedStart(child!!)
            }
            mPosition = position
        }
        
    }
    
    class LayoutChunkResult {
        
        var mConsumed = 0
        var mFinished = false
        var mIgnoreConsumed = false
        var mFocusable = false

        fun resetInternal() {
            mConsumed = 0
            mFinished = false
            mIgnoreConsumed = false
            mFocusable = false
        }
    }
}

