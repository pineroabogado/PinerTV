package com.piner.pinertv.catalog

import android.util.Log
import com.piner.pinertv.nas.FileItem
import com.piner.pinertv.nas.SynologyRepository

class CatalogIndexer(private val repo: SynologyRepository) {

    data class Catalog(
        val movies: List<MediaItem>,
        val series: List<MediaItem>
    )

    suspend fun index(
        shareNames: List<String> = listOf("piner", "PINER2"),
        folderNames: List<String> = listOf("PELÍCULAS", "SERIES", "PELICULAS")
    ): Catalog {
        val shares = repo.listShares().orEmpty()
        val movies = mutableListOf<MediaItem>()
        val series = mutableListOf<MediaItem>()

        for (shareName in shareNames) {
            val share = shares.firstOrNull { it.name.equals(shareName, ignoreCase = true) } ?: continue
            val rootFiles = repo.listFiles(share.path).orEmpty()
            for (folder in rootFiles.filter { it.isdir }) {
                val matched = folderNames.any { it.equals(folder.name, ignoreCase = true) }
                if (!matched) continue
                val isSeries = folder.name.contains("SERIE", ignoreCase = true)
                val children = repo.listFiles(folder.path).orEmpty()
                if (isSeries) {
                    series.addAll(indexSeriesFolders(children))
                } else {
                    movies.addAll(indexMovieEntries(children))
                }
            }
        }

        val cleanMovies = movies.filterNot { isJunkMedia(it) }.distinctBy { it.filePath }
        Log.i(TAG, "Indexado: ${cleanMovies.size} películas (raw=${movies.size}), ${series.size} series")
        val bondish = cleanMovies.filter {
            it.title.contains("bond", ignoreCase = true) ||
                it.title.contains("007", ignoreCase = true) ||
                it.filePath.contains("bond", ignoreCase = true)
        }
        if (bondish.isNotEmpty()) {
            Log.i(TAG, "Bond/007 en catálogo (${bondish.size}): ${bondish.take(8).joinToString { it.title }}")
        } else {
            Log.w(TAG, "Ningún título Bond/007 en catálogo tras indexar")
        }
        return Catalog(
            movies = cleanMovies.sortedBy { it.title.lowercase() },
            series = series.sortedBy { it.title.lowercase() }
        )
    }

    /**
     * Película = carpeta con vídeo, o vídeo suelto.
     * Sagas (p.ej. James Bond): si hay subcarpetas, se expanden SIEMPRE
     * (aunque exista un vídeo suelto en la raíz de la saga); si no, se indexaba
     * solo «James Bond» y se perdían Casino Royale, Skyfall, etc.
     *
     * Excluye BDMV/STREAM (00000.m2ts…), samples, extras, VIDEO_TS, etc.
     */
    private suspend fun indexMovieEntries(entries: List<FileItem>, depth: Int = 0): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        for (entry in entries) {
            if (entry.isdir) {
                if (isJunkFolder(entry.name) || isJunkPath(entry.path)) continue
                val files = repo.listFiles(entry.path).orEmpty()
                val videos = files
                    .filter { !it.isdir && VideoExtensions.isVideo(it.name) }
                    .filterNot { isJunkVideo(it) }
                val subdirs = files.filter { it.isdir && !isJunkFolder(it.name) && !isJunkPath(it.path) }
                when {
                    // Disco Blu-ray: BDMV → 1 película (nombre de carpeta + .m2ts más grande en STREAM)
                    files.any { it.isdir && it.name.equals("BDMV", ignoreCase = true) } -> {
                        pickBlurayFeature(entry.path)?.let { hit ->
                            result.add(
                                toMedia(entry.name, hit.path, MediaType.MOVIE, hit.mtimeSec, hit.size)
                            )
                        }
                    }
                    subdirs.isNotEmpty() && depth < 4 -> {
                        result.addAll(indexMovieEntries(subdirs, depth + 1))
                        // Vídeos sueltos en raíz de saga: solo si no son basura
                        videos.forEach { f ->
                            if (!isJunkVideo(f)) {
                                result.add(
                                    toMedia(
                                        f.name,
                                        f.path,
                                        MediaType.MOVIE,
                                        f.mtimeOr(entry),
                                        f.additional?.size ?: 0L
                                    )
                                )
                            }
                        }
                    }
                    videos.size == 1 -> {
                        val v = videos[0]
                        result.add(
                            toMedia(
                                entry.name,
                                v.path,
                                MediaType.MOVIE,
                                v.mtimeOr(entry),
                                v.additional?.size ?: 0L
                            )
                        )
                    }
                    videos.size > 1 -> {
                        // Varios vídeos: elegir el principal (mayor tamaño), no indexar todos
                        val feature = videos.maxByOrNull { it.additional?.size ?: 0L } ?: videos.first()
                        result.add(
                            toMedia(
                                entry.name,
                                feature.path,
                                MediaType.MOVIE,
                                feature.mtimeOr(entry),
                                feature.additional?.size ?: 0L
                            )
                        )
                    }
                }
            } else if (VideoExtensions.isVideo(entry.name) && !isJunkVideo(entry)) {
                result.add(
                    toMedia(
                        entry.name,
                        entry.path,
                        MediaType.MOVIE,
                        entry.mtimeSec(),
                        entry.additional?.size ?: 0L
                    )
                )
            }
        }
        return result
    }

    private suspend fun indexSeriesFolders(entries: List<FileItem>): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        for (entry in entries.filter { it.isdir && !isJunkFolder(it.name) }) {
            val first = findFirstVideo(entry.path)
            if (first != null) {
                val mtime = first.mtimeSec.takeIf { it > 0 } ?: entry.mtimeSec()
                result.add(
                    toMedia(entry.name, first.path, MediaType.SERIES, mtime, first.size).copy(
                        id = entry.path,
                        folderPath = entry.path
                    )
                )
            }
        }
        return result
    }

    private data class VideoHit(val path: String, val mtimeSec: Long, val size: Long = 0L)

    /**
     * Blu-ray: el feature es el .m2ts más grande bajo BDMV/STREAM.
     * Sí usamos segmentos numéricos aquí (son el contenido real), pero solo UNO.
     */
    private suspend fun pickBlurayFeature(movieFolder: String): VideoHit? {
        val streamPath = "$movieFolder/BDMV/STREAM".replace("//", "/")
        val streamFiles = repo.listFiles(streamPath).orEmpty()
        val m2ts = streamFiles.filter {
            !it.isdir && VideoExtensions.isVideo(it.name)
        }.map { VideoHit(it.path, it.mtimeSec(), it.additional?.size ?: 0L) }
        if (m2ts.isNotEmpty()) {
            return m2ts.maxByOrNull { it.size } ?: m2ts.first()
        }
        // Fallback: buscar cualquier vídeo no-basura bajo la carpeta
        return findLargestVideo(movieFolder, allowNumericStreams = true)
    }

    private suspend fun findLargestVideo(
        root: String,
        depth: Int = 0,
        allowNumericStreams: Boolean = false
    ): VideoHit? {
        if (depth > 5) return null
        val files = repo.listFiles(root).orEmpty()
        val videos = files
            .filter { !it.isdir && VideoExtensions.isVideo(it.name) }
            .filter { allowNumericStreams || !isJunkVideo(it) }
            .map { VideoHit(it.path, it.mtimeSec(), it.additional?.size ?: 0L) }
        var best = videos.maxByOrNull { it.size }
        for (dir in files.filter { it.isdir }) {
            val n = dir.name.lowercase()
            if (!allowNumericStreams && isJunkFolder(dir.name)) continue
            if (allowNumericStreams && n in setOf("certificate", "clipinf", "playlist", "jackets")) continue
            val child = findLargestVideo(dir.path, depth + 1, allowNumericStreams) ?: continue
            if (best == null || child.size > best.size) best = child
        }
        return best
    }

    private suspend fun findFirstVideo(path: String, depth: Int = 0): VideoHit? {
        if (depth > 3) return null
        val files = repo.listFiles(path).orEmpty()
        files.firstOrNull {
            !it.isdir && VideoExtensions.isVideo(it.name) && !isJunkVideo(it)
        }?.let {
            return VideoHit(it.path, it.mtimeSec(), it.additional?.size ?: 0L)
        }
        for (dir in files.filter { it.isdir && !isJunkFolder(it.name) }) {
            findFirstVideo(dir.path, depth + 1)?.let { return it }
        }
        return null
    }

    private fun FileItem.mtimeSec(): Long = additional?.time?.mtime ?: 0L

    private fun FileItem.mtimeOr(folder: FileItem): Long =
        mtimeSec().takeIf { it > 0 } ?: folder.mtimeSec()

    private fun toMedia(
        rawName: String,
        filePath: String,
        type: MediaType,
        mtimeSec: Long = 0L,
        sizeBytes: Long = 0L
    ): MediaItem {
        val parsed = NameParser.cleanName(rawName)
        return MediaItem(
            id = filePath,
            title = parsed.name.ifBlank { rawName },
            year = parsed.year,
            filePath = filePath,
            mediaType = type,
            mtimeSec = mtimeSec,
            sizeBytes = sizeBytes,
            alternativeTitle = parsed.alternativeTitle
        )
    }

    companion object {
        private const val TAG = "PinerTV-Catalog"

        private val JUNK_FOLDERS = setOf(
            "bdmv", "certificate", "clipinf", "playlist", "stream",
            "audio_ts", "video_ts", "jackets", "auxdata",
            "extras", "extra", "bonus", "bonuses", "featurettes", "featurette",
            "samples", "sample", "trailer", "trailers", "subs", "subtitles",
            "behind the scenes", "deleted scenes", "interviews", "shorts",
            "other", "misc"
        )

        fun isJunkFolder(name: String): Boolean {
            val n = name.trim().lowercase()
            if (n in JUNK_FOLDERS) return true
            if (n.startsWith("extra") || n.startsWith("sample") || n.startsWith("trailer")) return true
            return false
        }

        fun isJunkPath(path: String): Boolean {
            val p = path.replace('\\', '/').lowercase()
            return p.contains("/bdmv/") ||
                p.contains("/stream/") ||
                p.contains("/clipinf/") ||
                p.contains("/playlist/") ||
                p.contains("/certificate/") ||
                p.contains("/video_ts/") ||
                p.contains("/audio_ts/")
        }

        /** 00000.m2ts, sample.mkv, trailer, etc. */
        fun isJunkVideo(file: FileItem): Boolean {
            if (isJunkPath(file.path)) return true
            val name = file.name
            val base = name.substringBeforeLast('.').lowercase().trim()
            val ext = name.substringAfterLast('.', "").lowercase()
            if (base.contains("sample") || base.contains("trailer") || base.startsWith("extra")) return true
            // Segmentos Blu-ray / MPEG-TS puramente numéricos
            if (ext in setOf("m2ts", "mts", "m2t", "ts") && base.matches(Regex("^\\d{3,8}$"))) return true
            if (base.matches(Regex("^\\d{3,8}$"))) return true
            return false
        }

        /** Título ya parseado tipo «00002 m 2 ts» o solo dígitos. */
        fun isJunkMedia(item: MediaItem): Boolean {
            if (isJunkPath(item.filePath)) return true
            val t = item.title.trim().lowercase()
            if (t.length < 2) return true
            if (t.matches(Regex("^\\d{3,8}$"))) return true
            // NameParser parte «00002.m2ts» → «00002 m 2 ts»
            if (t.matches(Regex("^\\d{3,8}(\\s+m)?(\\s*2)?(\\s*t)?\\s*s?$"))) return true
            if (t.matches(Regex("^\\d{3,8}\\s+m\\s+2\\s+ts$"))) return true
            if (t.contains("m 2 ts") || t.endsWith(" m2ts")) return true
            return false
        }
    }
}
