package com.piner.pinertv.data

import android.content.Context

/** Orden y visibilidad de filas del Inicio. */
class HomeLayoutPrefs(context: Context) {

    enum class RowId { CONTINUE, MOVIES, SERIES }

    private val prefs = context.getSharedPreferences("pinertv_home_layout", Context.MODE_PRIVATE)

    fun order(): List<RowId> {
        val raw = prefs.getString(KEY_ORDER, DEFAULT_ORDER) ?: DEFAULT_ORDER
        return raw.split(',')
            .mapNotNull { runCatching { RowId.valueOf(it.trim()) }.getOrNull() }
            .ifEmpty { RowId.entries.toList() }
            .distinct()
            .let { list ->
                val missing = RowId.entries.filter { it !in list }
                list + missing
            }
    }

    fun setOrder(rows: List<RowId>) {
        prefs.edit().putString(KEY_ORDER, rows.joinToString(",") { it.name }).apply()
    }

    fun isVisible(row: RowId): Boolean =
        prefs.getBoolean(KEY_VIS + row.name, true)

    fun setVisible(row: RowId, visible: Boolean) {
        prefs.edit().putBoolean(KEY_VIS + row.name, visible).apply()
    }

    fun moveUp(row: RowId) {
        val list = order().toMutableList()
        val i = list.indexOf(row)
        if (i > 0) {
            list.removeAt(i)
            list.add(i - 1, row)
            setOrder(list)
        }
    }

    fun moveDown(row: RowId) {
        val list = order().toMutableList()
        val i = list.indexOf(row)
        if (i in 0 until list.lastIndex) {
            list.removeAt(i)
            list.add(i + 1, row)
            setOrder(list)
        }
    }

    companion object {
        private const val KEY_ORDER = "order"
        private const val KEY_VIS = "vis_"
        private const val DEFAULT_ORDER = "CONTINUE,MOVIES,SERIES"
    }
}
