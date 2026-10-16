package com.piner.pinertv.ui.widget

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.NestedScrollView

/**
 * Viewport de filas sin scroll reactivo: ←/→ no desplaza en vertical.
 * El anclaje a media altura lo fija el padding superior del contenido.
 */
class FocusStableNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {

    /** Si true, ignora cualquier scroll (foco, fling, programático). */
    var suppressFocusScroll: Boolean = true

    override fun requestChildRectangleOnScreen(
        child: View,
        rectangle: Rect,
        immediate: Boolean
    ): Boolean {
        if (suppressFocusScroll) return false
        return super.requestChildRectangleOnScreen(child, rectangle, immediate)
    }

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        if (suppressFocusScroll) return 0
        return super.computeScrollDeltaToGetChildRectOnScreen(rect)
    }

    override fun scrollTo(x: Int, y: Int) {
        if (suppressFocusScroll) {
            // Solo permitir reset a (0,0); cualquier otro scroll se ignora
            if (x == 0 && y == 0) {
                super.scrollTo(0, 0)
            } else if (scrollX != 0 || scrollY != 0) {
                super.scrollTo(0, 0)
            }
            return
        }
        super.scrollTo(x, y)
    }

    override fun fling(velocityY: Int) {
        if (suppressFocusScroll) return
        super.fling(velocityY)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (suppressFocusScroll) return false
        return super.onTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (suppressFocusScroll) return false
        return super.onInterceptTouchEvent(ev)
    }

    /** Bloquea el auto-scroll que NestedScrollView hace al recibir foco en un descendiente. */
    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        if (!suppressFocusScroll) {
            return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
        }
        val index = if (direction and FOCUS_FORWARD != 0) 0 else childCount - 1
        val step = if (direction and FOCUS_FORWARD != 0) 1 else -1
        var i = index
        while (i >= 0 && i < childCount) {
            val child = getChildAt(i)
            if (child.focusable != View.NOT_FOCUSABLE) {
                if (child.requestFocus(direction, previouslyFocusedRect)) return true
            } else if (child is ViewGroup) {
                val finder = FocusFinder.getInstance()
                val next = finder.findNextFocus(child, null, direction)
                if (next != null && next.requestFocus(direction, previouslyFocusedRect)) return true
            }
            i += step
        }
        return false
    }
}
