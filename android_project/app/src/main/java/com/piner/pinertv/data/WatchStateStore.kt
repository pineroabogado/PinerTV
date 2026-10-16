package com.piner.pinertv.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Visto + progreso por id/filePath (complementa ContinueWatchingStore).
 * ratio ≥ 0.95 → marcado como visto.
 */
class WatchStateStore(context: Context) {

    data class State(
        val id: String,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val watched: Boolean = false,
        val updatedAt: Long = System.currentTimeMillis()
    )

    private val prefs = context.getSharedPreferences("pinertv_watch", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun save(id: String, positionMs: Long, durationMs: Long) {
        if (id.isBlank() || durationMs <= 0L) return
        val ratio = positionMs.toDouble() / durationMs.toDouble()
        val map = loadMap().toMutableMap()
        when {
            ratio >= 0.95 -> map[id] = State(id, durationMs, durationMs, watched = true)
            ratio < 0.03 -> {
                // reinicio temprano: no borrar «visto»
                val prev = map[id]
                if (prev?.watched != true) map.remove(id)
            }
            else -> map[id] = State(id, positionMs, durationMs, watched = false)
        }
        prefs.edit().putString(KEY, gson.toJson(map.values.toList())).apply()
    }

    fun markWatched(id: String) {
        val map = loadMap().toMutableMap()
        val prev = map[id]
        map[id] = State(
            id = id,
            positionMs = prev?.durationMs ?: 0L,
            durationMs = prev?.durationMs ?: 0L,
            watched = true
        )
        prefs.edit().putString(KEY, gson.toJson(map.values.toList())).apply()
    }

    fun isWatched(id: String): Boolean = loadMap()[id]?.watched == true

    fun progressPercent(id: String): Int {
        val e = loadMap()[id] ?: return 0
        if (e.watched) return 100
        if (e.durationMs <= 0L) return 0
        return ((e.positionMs * 100L) / e.durationMs).toInt().coerceIn(0, 100)
    }

    fun getPosition(id: String): Long = loadMap()[id]?.positionMs ?: 0L

    private fun loadMap(): Map<String, State> {
        val json = prefs.getString(KEY, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<List<State>>() {}.type
            val list: List<State> = gson.fromJson(json, type)
            list.associateBy { it.id }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    companion object {
        private const val KEY = "states"
    }
}
