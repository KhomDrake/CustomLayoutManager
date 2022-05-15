package com.vinicius.automeasurerecyclerview.custom

import android.view.View
import androidx.annotation.IntDef
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy

internal class ViewBoundsCheck(val mCallback: Callback) {
    var mBoundFlags: BoundFlags

    /**
     * The set of flags that can be passed for checking the view boundary conditions.
     * CVS in the flag name indicates the child view, and PV indicates the parent view.\
     * The following S, E indicate a view's start and end points, respectively.
     * GT and LT indicate a strictly greater and less than relationship.
     * Greater than or equal (or less than or equal) can be specified by setting both GT and EQ (or
     * LT and EQ) flags.
     * For instance, setting both [.FLAG_CVS_GT_PVS] and [.FLAG_CVS_EQ_PVS] indicate the
     * child view's start should be greater than or equal to its parent start.
     */
    @IntDef(
        flag = true,
        value = [FLAG_CVS_GT_PVS, FLAG_CVS_EQ_PVS, FLAG_CVS_LT_PVS, FLAG_CVS_GT_PVE, FLAG_CVS_EQ_PVE, FLAG_CVS_LT_PVE, FLAG_CVE_GT_PVS, FLAG_CVE_EQ_PVS, FLAG_CVE_LT_PVS, FLAG_CVE_GT_PVE, FLAG_CVE_EQ_PVE, FLAG_CVE_LT_PVE]
    )
    @Retention(
        RetentionPolicy.SOURCE
    )
    annotation class ViewBounds
    internal class BoundFlags {
        var mBoundFlags = 0
        var mRvStart = 0
        var mRvEnd = 0
        var mChildStart = 0
        var mChildEnd = 0
        fun setBounds(rvStart: Int, rvEnd: Int, childStart: Int, childEnd: Int) {
            mRvStart = rvStart
            mRvEnd = rvEnd
            mChildStart = childStart
            mChildEnd = childEnd
        }

        fun addFlags(@ViewBounds flags: Int) {
            mBoundFlags = mBoundFlags or flags
        }

        fun resetFlags() {
            mBoundFlags = 0
        }

        fun compare(x: Int, y: Int): Int {
            if (x > y) {
                return GT
            }
            return if (x == y) {
                EQ
            } else LT
        }

        fun boundsMatch(): Boolean {
            if (mBoundFlags and (MASK shl CVS_PVS_POS) != 0) {
                if (mBoundFlags and (compare(mChildStart, mRvStart) shl CVS_PVS_POS) == 0) {
                    return false
                }
            }
            if (mBoundFlags and (MASK shl CVS_PVE_POS) != 0) {
                if (mBoundFlags and (compare(mChildStart, mRvEnd) shl CVS_PVE_POS) == 0) {
                    return false
                }
            }
            if (mBoundFlags and (MASK shl CVE_PVS_POS) != 0) {
                if (mBoundFlags and (compare(mChildEnd, mRvStart) shl CVE_PVS_POS) == 0) {
                    return false
                }
            }
            if (mBoundFlags and (MASK shl CVE_PVE_POS) != 0) {
                if (mBoundFlags and (compare(mChildEnd, mRvEnd) shl CVE_PVE_POS) == 0) {
                    return false
                }
            }
            return true
        }
    }

    /**
     * Returns the first view starting from fromIndex to toIndex in views whose bounds lie within
     * its parent bounds based on the provided preferredBoundFlags. If no match is found based on
     * the preferred flags, and a nonzero acceptableBoundFlags is specified, the last view whose
     * bounds lie within its parent view based on the acceptableBoundFlags is returned. If no such
     * view is found based on either of these two flags, null is returned.
     * @param fromIndex The view position index to start the search from.
     * @param toIndex The view position index to end the search at.
     * @param preferredBoundFlags The flags indicating the preferred match. Once a match is found
     * based on this flag, that view is returned instantly.
     * @param acceptableBoundFlags The flags indicating the acceptable match if no preferred match
     * is found. If so, and if acceptableBoundFlags is non-zero, the
     * last matching acceptable view is returned. Otherwise, null is
     * returned.
     * @return The first view that satisfies acceptableBoundFlags or the last view satisfying
     * acceptableBoundFlags boundary conditions.
     */
    fun findOneViewWithinBoundFlags(
        fromIndex: Int, toIndex: Int,
        @ViewBounds preferredBoundFlags: Int,
        @ViewBounds acceptableBoundFlags: Int
    ): View? {
        val start = mCallback.parentStart
        val end = mCallback.parentEnd
        val next = if (toIndex > fromIndex) 1 else -1
        var acceptableMatch: View? = null
        var i = fromIndex
        while (i != toIndex) {
            val child = mCallback.getChildAt(i)
            val childStart = mCallback.getChildStart(child)
            val childEnd = mCallback.getChildEnd(child)
            mBoundFlags.setBounds(start, end, childStart, childEnd)
            if (preferredBoundFlags != 0) {
                mBoundFlags.resetFlags()
                mBoundFlags.addFlags(preferredBoundFlags)
                if (mBoundFlags.boundsMatch()) {
                    // found a perfect match
                    return child
                }
            }
            if (acceptableBoundFlags != 0) {
                mBoundFlags.resetFlags()
                mBoundFlags.addFlags(acceptableBoundFlags)
                if (mBoundFlags.boundsMatch()) {
                    acceptableMatch = child
                }
            }
            i += next
        }
        return acceptableMatch
    }

    /**
     * Returns whether the specified view lies within the boundary condition of its parent view.
     * @param child The child view to be checked.
     * @param boundsFlags The flag against which the child view and parent view are matched.
     * @return True if the view meets the boundsFlag, false otherwise.
     */
    fun isViewWithinBoundFlags(child: View?, @ViewBounds boundsFlags: Int): Boolean {
        mBoundFlags.setBounds(
            mCallback.parentStart, mCallback.parentEnd,
            mCallback.getChildStart(child), mCallback.getChildEnd(child)
        )
        if (boundsFlags != 0) {
            mBoundFlags.resetFlags()
            mBoundFlags.addFlags(boundsFlags)
            return mBoundFlags.boundsMatch()
        }
        return false
    }

    /**
     * Callback provided by the user of this class in order to retrieve information about child and
     * parent boundaries.
     */
    internal interface Callback {
        fun getChildAt(index: Int): View
        val parentStart: Int
        val parentEnd: Int

        fun getChildStart(view: View?): Int
        fun getChildEnd(view: View?): Int
    }

    companion object {
        const val GT = 1 shl 0
        const val EQ = 1 shl 1
        const val LT = 1 shl 2
        const val CVS_PVS_POS = 0

        /**
         * The child view's start should be strictly greater than parent view's start.
         */
        const val FLAG_CVS_GT_PVS = GT shl CVS_PVS_POS

        /**
         * The child view's start can be equal to its parent view's start. This flag follows with GT
         * or LT indicating greater (less) than or equal relation.
         */
        const val FLAG_CVS_EQ_PVS = EQ shl CVS_PVS_POS

        /**
         * The child view's start should be strictly less than parent view's start.
         */
        const val FLAG_CVS_LT_PVS = LT shl CVS_PVS_POS
        const val CVS_PVE_POS = 4

        /**
         * The child view's start should be strictly greater than parent view's end.
         */
        const val FLAG_CVS_GT_PVE = GT shl CVS_PVE_POS

        /**
         * The child view's start can be equal to its parent view's end. This flag follows with GT
         * or LT indicating greater (less) than or equal relation.
         */
        const val FLAG_CVS_EQ_PVE = EQ shl CVS_PVE_POS

        /**
         * The child view's start should be strictly less than parent view's end.
         */
        const val FLAG_CVS_LT_PVE = LT shl CVS_PVE_POS
        const val CVE_PVS_POS = 8

        /**
         * The child view's end should be strictly greater than parent view's start.
         */
        const val FLAG_CVE_GT_PVS = GT shl CVE_PVS_POS

        /**
         * The child view's end can be equal to its parent view's start. This flag follows with GT
         * or LT indicating greater (less) than or equal relation.
         */
        const val FLAG_CVE_EQ_PVS = EQ shl CVE_PVS_POS

        /**
         * The child view's end should be strictly less than parent view's start.
         */
        const val FLAG_CVE_LT_PVS = LT shl CVE_PVS_POS
        const val CVE_PVE_POS = 12

        /**
         * The child view's end should be strictly greater than parent view's end.
         */
        const val FLAG_CVE_GT_PVE = GT shl CVE_PVE_POS

        /**
         * The child view's end can be equal to its parent view's end. This flag follows with GT
         * or LT indicating greater (less) than or equal relation.
         */
        const val FLAG_CVE_EQ_PVE = EQ shl CVE_PVE_POS

        /**
         * The child view's end should be strictly less than parent view's end.
         */
        const val FLAG_CVE_LT_PVE = LT shl CVE_PVE_POS
        const val MASK = GT or EQ or LT
    }

    init {
        mBoundFlags = BoundFlags()
    }
}