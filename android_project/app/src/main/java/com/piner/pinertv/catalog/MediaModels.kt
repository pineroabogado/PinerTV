package com.piner.pinertv.catalog

import java.io.Serializable

enum class MediaType { MOVIE, SERIES }

data class MediaItem(
    val id: String,
    val title: String,
    val year: String?,
    val overview: String = "",
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val filePath: String,
    val mediaType: MediaType,
    val voteAverage: Float = 0f,
    val runtime: Int? = null,
    val genres: List<String> = emptyList(),
    /** Carpeta raíz de la serie en el NAS (SERIES/Nombre). Null en películas. */
    val folderPath: String? = null,
    /** mtime Synology (epoch segundos). 0 si desconocido. */
    val mtimeSec: Long = 0L,
    /** Tamaño del fichero principal (bytes), si Synology lo reportó. */
    val sizeBytes: Long = 0L,
    /** ID TMDB del título (movie o tv). Permite cruce rápido de filmografía. */
    val tmdbId: Int? = null,
    /**
     * Confianza del match TMDB (0–1). null = aún no enriquecido / desconocido.
     * Valores bajos (p.ej. &lt; 0.55) disparan re-enrich al enfocar la card.
     */
    val tmdbMatchScore: Float? = null,
    /** Título alternativo entre paréntesis (NameParser), p.ej. «La ballena (The Whale)». */
    val alternativeTitle: String? = null
) : Serializable

data class EpisodeEntry(
    val season: Int,
    val episode: Int?,
    val title: String,
    val filePath: String,
    val seasonLabel: String
) : Serializable {
    val displayCode: String
        get() = when {
            episode != null -> "E${episode.toString().padStart(2, '0')}"
            else -> "Ep"
        }
}

data class SeasonBucket(
    val season: Int,
    val label: String,
    val episodes: List<EpisodeEntry>
) : Serializable

/** Miembro de elenco (TMDB credits). */
data class CastMember(
    val id: Int,
    val name: String,
    val character: String,
    val profileUrl: String?
) : Serializable

object VideoExtensions {
    private val EXTS = setOf(
        "mp4", "mkv", "avi", "mov", "m4v", "wmv", "ts", "m2ts", "webm", "mpg", "mpeg"
    )

    fun isVideo(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in EXTS
    }
}
