package com.piner.pinertv.data

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import androidx.media3.ui.CaptionStyleCompat

class SubtitleStylePrefs(context: Context) {

    private val prefs = context.getSharedPreferences("pinertv_subs_style", Context.MODE_PRIVATE)

    var sizePercent: Int
        get() = prefs.getInt(KEY_SIZE, 100).coerceIn(70, 160)
        set(v) = prefs.edit().putInt(KEY_SIZE, v.coerceIn(70, 160)).apply()

    /** white | yellow | cyan */
    var textColorKey: String
        get() = prefs.getString(KEY_FG, "white") ?: "white"
        set(v) = prefs.edit().putString(KEY_FG, v).apply()

    /** none | semi | solid */
    var backgroundKey: String
        get() = prefs.getString(KEY_BG, "semi") ?: "semi"
        set(v) = prefs.edit().putString(KEY_BG, v).apply()

    fun textColor(): Int = when (textColorKey) {
        "yellow" -> Color.YELLOW
        "cyan" -> Color.CYAN
        else -> Color.WHITE
    }

    fun backgroundColor(): Int = when (backgroundKey) {
        "solid" -> Color.BLACK
        "none" -> Color.TRANSPARENT
        else -> Color.argb(0x99, 0, 0, 0)
    }

    fun toCaptionStyle(): CaptionStyleCompat =
        CaptionStyleCompat(
            textColor(),
            backgroundColor(),
            Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            Color.BLACK,
            Typeface.DEFAULT_BOLD
        )

    fun fractionalTextSize(): Float = 0.053f * (sizePercent / 100f)

    companion object {
        private const val KEY_SIZE = "size_pct"
        private const val KEY_FG = "fg"
        private const val KEY_BG = "bg"
    }
}
