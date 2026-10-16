package com.piner.pinertv.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.piner.pinertv.catalog.MediaItem
import com.piner.pinertv.catalog.MediaType

class ContinueWatchingStore(context: Context) {

    private val appCtx = context.applicationContext

    data class Entry(
        val id: String,
        val title: String,
        val year: String?,
        val filePath: String,
        val mediaType: MediaType,
        val posterUrl: String?,
        val backdropUrl: String? = null,
        val positionMs: Long,
        val durationMs: Long,
        val updatedAt: Long = System.currentTimeMillis()
    )

    private val prefs = context.getSharedPreferences("pinertv_continue", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun save(item: MediaItem, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0L) return
        val ratio = positionMs.toDouble() / durationMs.toDouble()
        val map = loadMap().toMutableMap()
        if (ratio < 0.03 || ratio > 0.95) {
            map.remove(item.id)
        } else {
            map[item.id] = Entry(
                id = item.id,
                title = item.title,
                year = item.year,
                filePath = item.filePath,
                mediaType = item.mediaType,
                posterUrl = item.posterUrl,
                backdropUrl = item.backdropUrl,
                positionMs = positionMs,
                durationMs = durationMs
            )
        }
        prefs.edit().putString(KEY, gson.toJson(map.values.toList())).apply()
        // Espejo en WatchState (visto / % para cards)
        WatchStateStore(appCtx).save(item.id, positionMs, durationMs)
        if (item.filePath != item.id) {
            WatchStateStore(appCtx).save(item.filePath, positionMs, durationMs)
        }
    }

    fun getPosition(id: String): Long =
        loadMap()[id]?.positionMs
            ?: loadMap().values.firstOrNull { it.filePath == id }?.positionMs
            ?: 0L

    /** Posición exacta guardada (ms), buscando por id o filePath. */
    fun getPositionFor(item: MediaItem): Long {
        val byId = getPosition(item.id)
        if (byId > 0L) return byId
        if (item.filePath != item.id) return getPosition(item.filePath)
        return 0L
    }

    fun progressPercent(id: String): Int {
        val e = loadMap()[id]
            ?: loadMap().values.firstOrNull { it.filePath == id }
            ?: return 0
        if (e.durationMs <= 0L) return 0
        return ((e.positionMs * 100L) / e.durationMs).toInt().coerceIn(0, 100)
    }

    fun progressPercentFor(item: MediaItem): Int {
        val a = progressPercent(item.id)
        if (a in 1..99) return a
        return if (item.filePath != item.id) progressPercent(item.filePath) else 0
    }

    fun list(): List<MediaItem> {
        return loadMap().values
            .sortedByDescending { it.updatedAt }
            .take(20)
            .map {
                MediaItem(
                    id = it.id,
                    title = it.title,
                    year = it.year,
                    filePath = it.filePath,
                    mediaType = it.mediaType,
                    posterUrl = it.posterUrl,
                    backdropUrl = it.backdropUrl
                )
            }
    }

    private fun loadMap(): Map<String, Entry> {
        val json = prefs.getString(KEY, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<List<Entry>>() {}.type
            val list: List<Entry> = gson.fromJson(json, type)
            list.associateBy { it.id }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    companion object {
        private const val KEY = "entries"
    }
}
