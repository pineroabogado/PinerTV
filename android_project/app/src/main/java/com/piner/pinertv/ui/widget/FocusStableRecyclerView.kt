package com.piner.pinertv.ui.widget

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * Bloquea el auto-scroll vertical del RecyclerView al mover el foco ←/→
 * dentro de la misma fila. El scroll vertical lo hace la Activity al cambiar de fila.
 */
class FocusStableRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    /** Si true, ignora requestChildRectangleOnScreen (sin saltos verticales en horizontal). */
    var suppressFocusScroll: Boolean = true

    override fun requestChildRectangleOnScreen(
        child: View,
        rect: Rect,
        immediate: Boolean
    ): Boolean {
        if (suppressFocusScroll) return false
        return super.requestChildRectangleOnScreen(child, rect, immediate)
    }
}
