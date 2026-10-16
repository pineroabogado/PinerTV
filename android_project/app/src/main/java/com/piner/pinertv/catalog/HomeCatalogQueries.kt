package com.piner.pinertv.catalog

import com.piner.pinertv.data.CatalogCache
import java.util.Calendar

/**
 * Filas de Inicio alineadas con Emby Fire TV.
 *
 * Emby `latestmoviereleases` → i18n [RecentlyReleasedMovies] =
 * «Películas Estrenadas Recientemente»: SortBy ProductionYear,PremiereDate
 * + MinPremiereDate = ahora − 1 año (no DateCreated / mtime).
 */
object HomeCatalogQueries {

    /** Límite típico de fila home Emby (20–50). */
    const val RECENTLY_RELEASED_LIMIT = 36

    /**
     * Películas por fecha de estreno (año TMDB/archivo), no catálogo completo ni A–Z.
     * Criterio Emby: estrenos del último año; si hay pocas, rellena con top por año.
     */
    fun recentlyReleasedMovies(excludeIds: Set<String> = emptySet()): List<MediaItem> {
        val yearNow = Calendar.getInstance().get(Calendar.YEAR)
        val minYear = yearNow - 1
        val pool = CatalogCache.movies.filter { it.id !in excludeIds }
        val byPremiere = compareByDescending<MediaItem> { it.year?.toIntOrNull() ?: 0 }
            .thenByDescending { it.mtimeSec }
            .thenBy { it.title.lowercase() }

        val lastYear = pool
            .filter { (it.year?.toIntOrNull() ?: 0) >= minYear }
            .sortedWith(byPremiere)

        if (lastYear.size >= 12) {
            return lastYear.take(RECENTLY_RELEASED_LIMIT)
        }
        // Bibliotecas antiguas / sin metadatos: top N por año (sigue sin ser dump A–Z)
        return pool.sortedWith(byPremiere).take(RECENTLY_RELEASED_LIMIT)
    }
}
