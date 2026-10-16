package com.piner.pinertv.catalog

import android.util.Log
import com.piner.pinertv.nas.FileItem
import com.piner.pinertv.nas.SynologyRepository

/**
 * Infiera temporadas/episodios desde carpetas Synology.
 *
 * Criterio (documentado también en docs/series_details_criterio.md):
 * 1) Subcarpetas Temporada N / Season N / S01 → una temporada por carpeta.
 * 2) Si no hay carpetas de temporada: agrupa vídeos por SxxExx / Cap. / NxNN
 *    parseados con [NameParser.parseEpisodeFile]; sin número → temporada 1.
 * 3) Vídeos sueltos en la raíz de la serie se mezclan en su temporada inferida.
 */
class SeriesStructureLoader(private val repo: SynologyRepository) {

    suspend fun load(seriesFolderPath: String): List<SeasonBucket> {
        val root = seriesFolderPath.trimEnd('/')
        if (root.isBlank()) return emptyList()
        val files = repo.listFiles(root).orEmpty()
        if (files.isEmpty()) return emptyList()

        val seasonDirs = files.filter { it.isdir && seasonNumberFromFolder(it.name) != null }
        val rawEpisodes = mutableListOf<EpisodeEntry>()

        if (seasonDirs.isNotEmpty()) {
            for (dir in seasonDirs) {
                val seasonNum = seasonNumberFromFolder(dir.name) ?: continue
                val videos = collectVideos(dir.path, depth = 0)
                videos.forEach { video ->
                    rawEpisodes += toEpisode(video, dir.name, fallbackSeason = seasonNum)
                }
            }
            // Vídeos sueltos en raíz de la serie (si los hay)
            files.filter { !it.isdir && VideoExtensions.isVideo(it.name) }.forEach { video ->
                rawEpisodes += toEpisode(video, root.substringAfterLast('/'), fallbackSeason = 1)
            }
        } else {
            val videos = collectVideos(root, depth = 0)
            videos.forEach { video ->
                val parentName = video.path.substringBeforeLast('/').substringAfterLast('/')
                rawEpisodes += toEpisode(video, parentName, fallbackSeason = 1)
            }
        }

        val grouped = rawEpisodes
            .distinctBy { it.filePath }
            .groupBy { it.season }
            .toSortedMap()

        return grouped.map { (season, eps) ->
            val sorted = eps.sortedWith(
                compareBy<EpisodeEntry> { it.episode ?: Int.MAX_VALUE }
                    .thenBy { it.title.lowercase() }
            )
            SeasonBucket(
                season = season,
                label = "Temporada $season",
                episodes = sorted.map { it.copy(seasonLabel = "Temporada $season") }
            )
        }.also {
            Log.i(TAG, "Serie $root → ${it.size} temporadas, ${rawEpisodes.size} episodios")
        }
    }

    /**
     * Resuelve la carpeta raíz de la serie a partir del [MediaItem].
     * Si falta folderPath (catálogo antiguo), sube desde el vídeo:
     * carpeta temporada → padre; si no, padre del archivo.
     */
    fun resolveSeriesRoot(item: MediaItem): String {
        item.folderPath?.takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }
        if (item.mediaType != MediaType.SERIES) return item.filePath.substringBeforeLast('/')
        val path = item.filePath.trimEnd('/')
        val parent = path.substringBeforeLast('/')
        val parentName = parent.substringAfterLast('/')
        return if (seasonNumberFromFolder(parentName) != null) {
            parent.substringBeforeLast('/')
        } else {
            parent
        }
    }

    private suspend fun collectVideos(path: String, depth: Int): List<FileItem> {
        if (depth > 3) return emptyList()
        val files = repo.listFiles(path).orEmpty()
        val out = mutableListOf<FileItem>()
        files.filter { !it.isdir && VideoExtensions.isVideo(it.name) }.forEach { out += it }
        for (dir in files.filter { it.isdir }) {
            // Evitar reentrar en otras temporadas al recolectar desde raíz
            if (depth == 0 && seasonNumberFromFolder(dir.name) != null) continue
            out += collectVideos(dir.path, depth + 1)
        }
        return out
    }

    private fun toEpisode(video: FileItem, parentFolder: String, fallbackSeason: Int): EpisodeEntry {
        val parsed = NameParser.parseEpisodeFile(video.name, parentFolder)
        val season = parsed.season ?: fallbackSeason
        val ep = parsed.episode
        val title = parsed.name.ifBlank {
            video.name.substringBeforeLast('.').ifBlank { video.name }
        }
        return EpisodeEntry(
            season = season,
            episode = ep,
            title = title,
            filePath = video.path,
            seasonLabel = "Temporada $season"
        )
    }

    companion object {
        private const val TAG = "PinerTV-Series"

        fun seasonNumberFromFolder(name: String): Int? {
            Regex("(?i)^(?:Temporada|Season)\\s*(\\d{1,2})$").find(name.trim())?.let {
                return it.groupValues[1].toIntOrNull()
            }
            Regex("(?i)^S(\\d{1,2})$").find(name.trim())?.let {
                return it.groupValues[1].toIntOrNull()
            }
            // Carpeta solo numérica 1..40 (común en algunas colecciones)
            Regex("^(\\d{1,2})$").find(name.trim())?.let {
                val n = it.groupValues[1].toIntOrNull()
                if (n != null && n in 1..40) return n
            }
            return null
        }
    }
}
