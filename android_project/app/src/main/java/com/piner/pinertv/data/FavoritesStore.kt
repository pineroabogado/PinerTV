package com.piner.pinertv.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.piner.pinertv.catalog.MediaItem

class FavoritesStore(context: Context) {

    private val prefs = context.getSharedPreferences("pinertv_favorites", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun isFavorite(id: String): Boolean = load().any { it.id == id }

    fun toggle(item: MediaItem): Boolean {
        val list = load().toMutableList()
        val exists = list.any { it.id == item.id }
        if (exists) {
            list.removeAll { it.id == item.id }
        } else {
            list.add(0, item)
        }
        prefs.edit().putString(KEY, gson.toJson(list.take(100))).apply()
        return !exists
    }

    fun list(): List<MediaItem> = load()

    private fun load(): List<MediaItem> {
        val json = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<MediaItem>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val KEY = "items"
    }
}
