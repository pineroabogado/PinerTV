package com.piner.pinertv.ui.widget

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import com.piner.pinertv.R

/** Aplica clip redondeado (mismo radio que selector_card_focus = 6dp). */
object CardRoundClip {
    private const val RADIUS_DP = 6f

    fun apply(view: View?) {
        if (view == null) return
        val radius = RADIUS_DP * view.resources.displayMetrics.density
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radius)
            }
        }
        view.clipToOutline = true
    }
}
