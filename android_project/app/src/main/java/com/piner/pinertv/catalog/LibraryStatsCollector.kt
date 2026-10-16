package com.piner.pinertv.catalog

import com.piner.pinertv.data.CatalogCache
import com.piner.pinertv.nas.SynologyRepository

/**
 * Conteos de biblioteca sin póster ni metadatos TMDB.
 * Un pase por serie: episodios + tamaño; películas usan sizeBytes del índice.
 */
class LibraryStatsCollector(private val repo: SynologyRepository) {

    data class Report(
        val movies: Int,
        val series: Int,
        val episodes: Int,
        val totalSizeBytes: Long,
        val sizeKnown: Boolean
    )

    suspend fun collect(): Report {
        val movies = CatalogCache.movies
        val series = CatalogCache.series
        var episodeCount = 0
        var size = movies.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        var anySize = movies.any { it.sizeBytes > 0L }

        val loader = SeriesStructureLoader(repo)
        for (s in series) {
            val root = loader.resolveSeriesRoot(s)
            val walk = countVideosUnder(root)
            episodeCount += walk.count
            size += walk.bytes
            if (walk.bytes > 0L) anySize = true
        }

        return Report(
            movies = movies.size,
            series = series.size,
            episodes = episodeCount,
            totalSizeBytes = size,
            sizeKnown = anySize && size > 0L
        )
    }

    private data class Walk(val count: Int, val bytes: Long)

    private suspend fun countVideosUnder(root: String, depth: Int = 0): Walk {
        if (depth > 4 || root.isBlank()) return Walk(0, 0L)
        val files = repo.listFiles(root).orEmpty()
        var count = 0
        var bytes = 0L
        for (f in files) {
            when {
                !f.isdir && VideoExtensions.isVideo(f.name) && !CatalogIndexer.isJunkVideo(f) -> {
                    count++
                    bytes += f.additional?.size ?: 0L
                }
                f.isdir && !CatalogIndexer.isJunkFolder(f.name) && !CatalogIndexer.isJunkPath(f.path) -> {
                    val child = countVideosUnder(f.path, depth + 1)
                    count += child.count
                    bytes += child.bytes
                }
            }
        }
        return Walk(count, bytes)
    }
}
