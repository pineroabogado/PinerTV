package com.piner.pinertv.catalog

import com.piner.pinertv.nas.SynologyRepository

/**
 * Localiza vídeos en carpetas tipo extras / bonus / featurettes
 * junto a la película o en la raíz de la serie.
 */
class ExtrasLoader(private val repo: SynologyRepository) {

    data class ExtraEntry(
        val title: String,
        val filePath: String,
        val folderLabel: String
    )

    suspend fun loadFor(item: MediaItem): List<ExtraEntry> {
        val roots = candidateRoots(item)
        if (roots.isEmpty()) return emptyList()
        val out = linkedMapOf<String, ExtraEntry>()
        for (root in roots) {
            val files = repo.listFiles(root).orEmpty()
            for (dir in files.filter { it.isdir && isExtrasFolder(it.name) }) {
                collectVideos(dir.path, dir.name, depth = 0).forEach { entry ->
                    out.putIfAbsent(entry.filePath, entry)
                }
            }
            // Vídeos sueltos en raíz con nombre tipo "extra-", "deleted-", etc. (poco habitual)
            files.filter { !it.isdir && VideoExtensions.isVideo(it.name) && looksLikeExtraFile(it.name) }
                .forEach { f ->
                    val title = NameParser.cleanName(f.name).name.ifBlank {
                        f.name.substringBeforeLast('.')
                    }
                    out.putIfAbsent(
                        f.path,
                        ExtraEntry(title = title, filePath = f.path, folderLabel = "Extras")
                    )
                }
        }
        return out.values.sortedBy { it.title.lowercase() }
    }

    private fun candidateRoots(item: MediaItem): List<String> {
        val roots = linkedSetOf<String>()
        when (item.mediaType) {
            MediaType.SERIES -> {
                item.folderPath?.trimEnd('/')?.takeIf { it.isNotBlank() }?.let { roots += it }
                val parent = item.filePath.trimEnd('/').substringBeforeLast('/')
                if (parent.isNotBlank()) {
                    val parentName = parent.substringAfterLast('/')
                    if (SeriesStructureLoader.seasonNumberFromFolder(parentName) != null) {
                        roots += parent.substringBeforeLast('/')
                    } else {
                        roots += parent
                    }
                }
            }
            MediaType.MOVIE -> {
                val parent = item.filePath.trimEnd('/').substringBeforeLast('/')
                if (parent.isNotBlank()) roots += parent
                // Carpeta de saga un nivel arriba (p.ej. …/James Bond/Skyfall/video + …/James Bond/extras)
                val grand = parent.substringBeforeLast('/')
                if (grand.isNotBlank() && grand.count { it == '/' } >= 2) {
                    roots += grand
                }
            }
        }
        return roots.toList()
    }

    private suspend fun collectVideos(
        path: String,
        folderLabel: String,
        depth: Int
    ): List<ExtraEntry> {
        if (depth > 3) return emptyList()
        val files = repo.listFiles(path).orEmpty()
        val out = mutableListOf<ExtraEntry>()
        files.filter { !it.isdir && VideoExtensions.isVideo(it.name) }.forEach { f ->
            val title = NameParser.cleanName(f.name).name.ifBlank {
                f.name.substringBeforeLast('.')
            }
            out += ExtraEntry(
                title = title,
                filePath = f.path,
                folderLabel = folderLabel
            )
        }
        for (dir in files.filter { it.isdir }) {
            out += collectVideos(dir.path, folderLabel, depth + 1)
        }
        return out
    }

    companion object {
        private val EXTRA_FOLDERS = setOf(
            "extras", "extra", "bonus", "bonuses", "featurettes", "featurette",
            "behind the scenes", "deleted scenes", "interviews", "shorts",
            "other", "misc"
        )

        fun isExtrasFolder(name: String): Boolean {
            val n = name.trim().lowercase()
            if (n in EXTRA_FOLDERS) return true
            if (n.startsWith("extra")) return true
            if (n.contains("deleted") || n.contains("featurette") || n.contains("behind")) return true
            return false
        }

        private fun looksLikeExtraFile(name: String): Boolean {
            val base = name.substringBeforeLast('.').lowercase()
            return base.startsWith("extra") ||
                base.contains("deleted") ||
                base.contains("featurette") ||
                base.contains("making.of") ||
                base.contains("making-of") ||
                base.contains("behind")
        }
    }
}
