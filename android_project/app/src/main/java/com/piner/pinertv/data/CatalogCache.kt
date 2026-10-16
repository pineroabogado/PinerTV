package com.piner.pinertv.data

import com.piner.pinertv.catalog.MediaItem

object CatalogCache {
    @Volatile var movies: List<MediaItem> = emptyList()
    @Volatile var series: List<MediaItem> = emptyList()
    @Volatile var sid: String? = null
    @Volatile var baseUrl: String? = null

    /** Actualiza un ítem enriquecido (p.ej. póster TMDB a demanda). */
    @Synchronized
    fun upsert(item: MediaItem) {
        fun List<MediaItem>.replace(): List<MediaItem> {
            val i = indexOfFirst { it.id == item.id }
            if (i < 0) return this
            return toMutableList().also { it[i] = item }
        }
        movies = movies.replace()
        series = series.replace()
    }
}
