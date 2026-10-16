package com.piner.pinertv.metadata

import android.util.Log
import com.google.gson.annotations.SerializedName
import com.piner.pinertv.BuildConfig
import com.piner.pinertv.catalog.CastMember
import com.piner.pinertv.catalog.MediaItem
import com.piner.pinertv.catalog.MediaType
import com.piner.pinertv.data.CatalogCache
import com.piner.pinertv.data.PersonIndex
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class TmdbRepository {

    private val api: TmdbApi? = if (BuildConfig.TMDB_API_KEY.isBlank()) null else {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl("https://api.themoviedb.org/3/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TmdbApi::class.java)
    }

    suspend fun enrich(item: MediaItem, withDetail: Boolean = false): MediaItem {
        val api = api ?: return item
        return try {
            val queries = searchQueries(item)
            val year = yearForSearch(item)
            if (item.mediaType == MediaType.MOVIE) {
                // Preferir movie; TV solo si no hay hit movie usable
                val scored = findBestMovie(api, queries, year)
                    ?: findBestTv(api, queries, year)?.also {
                        Log.d(TAG, "Poster vía TV fallback: ${item.title}")
                    }
                if (scored == null) return item
                val hit = scored.hit
                Log.i(
                    TAG,
                    "Match ${item.title} -> tmdb=${hit.id} score=${"%.2f".format(scored.score)} " +
                        "'${hit.title}' (${hit.dateYear}) movie=${hit.fromMovie}"
                )
                val detail = if (withDetail && hit.fromMovie) runCatching {
                    api.movieDetail(hit.id, BuildConfig.TMDB_API_KEY)
                }.getOrNull() else null
                item.copy(
                    overview = hit.overview.orEmpty().ifBlank { item.overview },
                    posterUrl = hit.posterPath?.let { IMG + it } ?: item.posterUrl,
                    backdropUrl = hit.backdropPath?.let { BACKDROP + it } ?: item.backdropUrl,
                    voteAverage = hit.voteAverage?.takeIf { it > 0f } ?: item.voteAverage,
                    year = hit.dateYear ?: item.year,
                    runtime = detail?.runtime?.takeIf { it > 0 } ?: item.runtime,
                    genres = detail?.genres?.map { it.name }.orEmpty().ifEmpty { item.genres },
                    tmdbId = hit.id,
                    tmdbMatchScore = scored.score
                )
            } else {
                val scored = findBestTv(api, queries, year)
                    ?: findBestMovie(api, queries, year)?.also {
                        Log.d(TAG, "Poster vía movie fallback: ${item.title}")
                    }
                if (scored == null) return item
                val hit = scored.hit
                Log.i(
                    TAG,
                    "Match ${item.title} -> tmdb=${hit.id} score=${"%.2f".format(scored.score)} " +
                        "'${hit.title}' (${hit.dateYear}) movie=${hit.fromMovie}"
                )
                val detail = if (withDetail && !hit.fromMovie) runCatching {
                    api.tvDetail(hit.id, BuildConfig.TMDB_API_KEY)
                }.getOrNull() else null
                val epRuntime = detail?.episodeRunTime?.firstOrNull()
                item.copy(
                    overview = hit.overview.orEmpty().ifBlank { item.overview },
                    posterUrl = hit.posterPath?.let { IMG + it } ?: item.posterUrl,
                    backdropUrl = hit.backdropPath?.let { BACKDROP + it } ?: item.backdropUrl,
                    voteAverage = hit.voteAverage?.takeIf { it > 0f } ?: item.voteAverage,
                    year = hit.dateYear ?: item.year,
                    runtime = epRuntime?.takeIf { it > 0 } ?: item.runtime,
                    genres = detail?.genres?.map { it.name }.orEmpty().ifEmpty { item.genres },
                    tmdbId = hit.id,
                    tmdbMatchScore = scored.score
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "TMDB enrich falló: ${item.title}", e)
            item
        }
    }

    /** True si conviene rebuscar (sin póster, score bajo, o título corto sin score). */
    fun needsReEnrich(item: MediaItem): Boolean {
        if (item.posterUrl.isNullOrBlank()) return true
        val score = item.tmdbMatchScore
        if (score == null) {
            // Metadatos viejos + título ambiguo (p.ej. «Maverick») → revalidar
            val words = item.title.split(Regex("\\s+")).filter { it.length > 1 }
            return words.size <= 2
        }
        return score < LOW_CONFIDENCE
    }

    /** Elenco principal (hasta [limit] actores) vía credits TMDB. */
    suspend fun loadCast(item: MediaItem, limit: Int = 16): List<CastMember> {
        val api = api ?: return emptyList()
        return try {
            val tmdbId = resolveTmdbId(item) ?: return emptyList()
            val credits = if (item.mediaType == MediaType.MOVIE) {
                api.movieCredits(tmdbId, BuildConfig.TMDB_API_KEY)
            } else {
                api.tvCredits(tmdbId, BuildConfig.TMDB_API_KEY)
            }
            credits.cast.orEmpty()
                .sortedBy { it.order ?: Int.MAX_VALUE }
                .take(limit)
                .map { c ->
                    CastMember(
                        id = c.id,
                        name = c.name.orEmpty(),
                        character = c.character.orEmpty(),
                        profileUrl = c.profilePath?.let { PROFILE + it }
                    )
                }
                .filter { it.name.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "TMDB cast falló: ${item.title}", e)
            emptyList()
        }
    }

    private suspend fun resolveTmdbId(item: MediaItem): Int? {
        item.tmdbId?.takeIf { it > 0 }?.let { return it }
        val api = api ?: return null
        val queries = searchQueries(item)
        val year = yearForSearch(item)
        return if (item.mediaType == MediaType.MOVIE) {
            findBestMovie(api, queries, year)?.hit?.id
        } else {
            findBestTv(api, queries, year)?.hit?.id
        }
    }

    suspend fun enrichAll(items: List<MediaItem>, limit: Int = 40): List<MediaItem> {
        // Prioriza ítems sin póster; detalle (runtime) solo en los primeros 12
        val ordered = items.withIndex()
            .sortedBy { (_, item) -> if (item.posterUrl.isNullOrBlank()) 0 else 1 }
            .take(limit)
        val detailIds = ordered.take(12).map { it.index }.toSet()
        val enrichedByIndex = ordered.associate { (index, item) ->
            index to enrich(item, withDetail = index in detailIds)
        }
        return items.mapIndexed { index, item -> enrichedByIndex[index] ?: item }
    }

    /**
     * Años en coletillas tipo «Catálogo audiovisual 2009» no son el año de estreno
     * y hacen fallar la búsqueda TMDB -> card sin carátula.
     */
    private fun yearForSearch(item: MediaItem): String? {
        val t = item.title
        if (t.contains(Regex("(?i)cat[aá]logo|audiovisual|documental\\s+completo"))) {
            return null
        }
        item.year?.takeIf { it.matches(Regex("^(19|20)\\d{2}$")) }?.let { return it }
        return Regex("\\b(19\\d{2}|20\\d{2})\\b").find(item.filePath)?.groupValues?.get(1)
    }

    /** Título + segmentos de ruta (carpeta «Top Gun Maverick» gana a archivo genérico). */
    private fun searchQueries(item: MediaItem): List<String> {
        val out = linkedSetOf<String>()
        fun addAllFrom(raw: String) {
            searchQueriesFromTitle(raw).forEach { out.add(it) }
        }
        addAllFrom(item.title)
        item.alternativeTitle?.takeIf { it.length >= 3 }?.let { addAllFrom(it) }
        pathTitleCandidates(item.filePath).forEach { addAllFrom(it) }
        return out.sortedByDescending { it.length }
    }

    private fun searchQueriesFromTitle(title: String): List<String> {
        val out = linkedSetOf<String>()
        val t = sanitizeSearchTitle(title.trim())
        if (t.isNotEmpty()) out.add(t)
        t.split(Regex("\\s*[,:;]\\s*")).firstOrNull()?.trim()
            ?.takeIf { it.length >= 3 }?.let { out.add(sanitizeSearchTitle(it)) }
        val cleaned = t.replace(
            Regex("(?i)\\b(cat[aá]logo|audiovisual|documental|completo|edici[oó]n)\\b.*$"),
            ""
        ).trim()
        if (cleaned.length >= 3) out.add(cleaned)
        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size > 4) out.add(words.take(4).joinToString(" "))
        if (words.size > 3) out.add(words.take(3).joinToString(" "))
        if (words.size > 2) out.add(words.take(2).joinToString(" "))
        return out.filter { it.length >= 2 }
    }

    private fun pathTitleCandidates(filePath: String): List<String> {
        val parts = filePath.replace('\\', '/').split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return emptyList()
        val junk = setOf(
            "movies", "movie", "peliculas", "películas", "films", "cinema",
            "series", "tv", "shows", "video", "videos", "media", "volume1", "volume2"
        )
        val out = mutableListOf<String>()
        parts.takeLast(3).forEach { seg ->
            val base = if (seg.contains('.')) seg.substringBeforeLast('.') else seg
            if (base.lowercase() !in junk && base.length >= 3) out.add(base)
        }
        return out
    }

    private data class ScoredHit(val hit: Hit, val score: Float)

    private suspend fun findBestMovie(
        api: TmdbApi,
        queries: List<String>,
        year: String?
    ): ScoredHit? = collectBest(api, queries, year, movie = true)

    private suspend fun findBestTv(
        api: TmdbApi,
        queries: List<String>,
        year: String?
    ): ScoredHit? = collectBest(api, queries, year, movie = false)

    private suspend fun collectBest(
        api: TmdbApi,
        queries: List<String>,
        year: String?,
        movie: Boolean
    ): ScoredHit? {
        var best: ScoredHit? = null
        for (raw in queries) {
            val q = sanitizeSearchTitle(raw).ifBlank { raw.trim() }
            if (q.isBlank()) continue
            val candidates = mutableListOf<TmdbHit>()
            if (!year.isNullOrBlank()) {
                runCatching {
                    if (movie) api.searchMovie(BuildConfig.TMDB_API_KEY, q, year).results
                    else api.searchTv(BuildConfig.TMDB_API_KEY, q, year).results
                }.getOrNull()?.let { candidates.addAll(it) }
            }
            runCatching {
                if (movie) api.searchMovie(BuildConfig.TMDB_API_KEY, q, null).results
                else api.searchTv(BuildConfig.TMDB_API_KEY, q, null).results
            }.getOrNull()?.let { candidates.addAll(it) }
            val scored = scoreCandidates(candidates.distinctBy { it.id }, q, year, movie)
                ?: continue
            if (best == null || scored.score > best.score) best = scored
            if (scored.score >= 0.82f) break
        }
        return best
    }

    private fun scoreCandidates(
        results: List<TmdbHit>,
        query: String,
        year: String?,
        movie: Boolean
    ): ScoredHit? {
        if (results.isEmpty()) return null
        val best = pickBestScored(results, query, year) ?: return null
        return ScoredHit(
            hit = if (movie) Hit.fromMovie(best.first) else Hit.fromTv(best.first),
            score = best.second
        )
    }

    /**
     * Estrategia SynoStream: buscar CON año primero (desambiguación Maverick/Top Gun),
     * luego sin año. Scoring por igualdad / solapamiento de palabras / año / póster.
     */
    private suspend fun findMovieHit(api: TmdbApi, queries: List<String>, year: String?): Hit? {
        for (raw in queries) {
            val q = sanitizeSearchTitle(raw).ifBlank { raw.trim() }
            if (q.isBlank()) continue
            if (!year.isNullOrBlank()) {
                val withYear = runCatching {
                    pickBest(api.searchMovie(BuildConfig.TMDB_API_KEY, q, year).results, q, year)
                }.getOrNull()
                if (withYear != null) return Hit.fromMovie(withYear)
            }
            val noYear = runCatching {
                pickBest(api.searchMovie(BuildConfig.TMDB_API_KEY, q, null).results, q, year)
            }.getOrNull()
            if (noYear != null) return Hit.fromMovie(noYear)
        }
        return null
    }

    private suspend fun findTvHit(api: TmdbApi, queries: List<String>, year: String?): Hit? {
        for (raw in queries) {
            val q = sanitizeSearchTitle(raw).ifBlank { raw.trim() }
            if (q.isBlank()) continue
            if (!year.isNullOrBlank()) {
                val withYear = runCatching {
                    pickBest(api.searchTv(BuildConfig.TMDB_API_KEY, q, year).results, q, year)
                }.getOrNull()
                if (withYear != null) return Hit.fromTv(withYear)
            }
            val noYear = runCatching {
                pickBest(api.searchTv(BuildConfig.TMDB_API_KEY, q, null).results, q, year)
            }.getOrNull()
            if (noYear != null) return Hit.fromTv(noYear)
        }
        return null
    }

    /**
     * Match estilo SynoStream [TmdbRepository.pickBestMovieMatch]:
     * exacto ≫ solape de palabras ≫ año ≫ póster; penaliza títulos cortos incompletos.
     */
    private fun pickBest(results: List<TmdbHit>, query: String, year: String?): TmdbHit? {
        if (results.isEmpty()) return null
        val qNorm = normalizeTitle(query)
        val qWords = qNorm.split(" ").filter { it.length > 1 }
        if (qNorm.isBlank()) {
            return results.firstOrNull { !it.posterPath.isNullOrBlank() } ?: results.firstOrNull()
        }

        fun score(hit: TmdbHit): Int {
            val t = normalizeTitle((hit.title ?: hit.name).orEmpty())
            val o = normalizeTitle((hit.originalTitle ?: hit.originalName).orEmpty())
            var s = 0
            if (t == qNorm || o == qNorm) s += 10_000
            val tWords = t.split(" ").filter { it.length > 1 }
            val oWords = o.split(" ").filter { it.length > 1 }
            val overlap = maxOf(
                qWords.count { w -> tWords.any { it == w } },
                qWords.count { w -> oWords.any { it == w } }
            )
            s += overlap * 200
            // «Matrix Resurrections» no debe caer en «The Matrix»
            if (qWords.size >= 2 && overlap < qWords.size) {
                s -= (qWords.size - overlap) * 400
            }
            val date = hit.releaseDate ?: hit.firstAirDate
            if (year != null && date?.startsWith(year) == true) s += 80
            s += minOf(tWords.size, qWords.size) * 10
            if (!hit.posterPath.isNullOrBlank()) s += 25
            return s
        }

        val best = results.maxByOrNull(::score) ?: return null
        val bestScore = score(best)
        if (qWords.size >= 2 && bestScore < 200) {
            Log.w(TAG, "Match débil '$query' -> '${best.title ?: best.name}' (score=$bestScore)")
        }
        return best
    }

    /**
     * Fuzzy/ratio + año + póster; títulos cortos/ambiguos (Maverick) usan popularidad/votos.
     * @return Pair(hit, confianza 0–1)
     */
    private fun pickBestScored(
        results: List<TmdbHit>,
        query: String,
        year: String?
    ): Pair<TmdbHit, Float>? {
        if (results.isEmpty()) return null
        val qNorm = normalizeTitle(query)
        val qWords = qNorm.split(" ").filter { it.length > 1 }
        val ambiguous = qWords.size <= 2
        if (qNorm.isBlank()) {
            val hit = results.firstOrNull { !it.posterPath.isNullOrBlank() } ?: results.first()
            return hit to 0.4f
        }
        fun rawScore(hit: TmdbHit): Float {
            val t = normalizeTitle((hit.title ?: hit.name).orEmpty())
            val o = normalizeTitle((hit.originalTitle ?: hit.originalName).orEmpty())
            var s = 0f
            val ratio = maxOf(titleSimilarity(qNorm, t), titleSimilarity(qNorm, o))
            s += ratio * 70f
            if (t == qNorm || o == qNorm) s += 40f
            if (qNorm.length >= 4 && (t.contains(qNorm) || o.contains(qNorm) || qNorm.contains(t))) {
                s += 15f
            }
            val tWords = t.split(" ").filter { it.length > 1 }
            val oWords = o.split(" ").filter { it.length > 1 }
            val overlap = maxOf(
                qWords.count { w -> tWords.any { it == w } },
                qWords.count { w -> oWords.any { it == w } }
            )
            s += overlap * 8f
            if (qWords.size >= 2 && overlap < qWords.size) {
                s -= (qWords.size - overlap) * 12f
            }
            val date = hit.releaseDate ?: hit.firstAirDate
            if (!year.isNullOrBlank() && date?.startsWith(year) == true) s += 25f
            else if (!year.isNullOrBlank() && !date.isNullOrBlank() && date.take(4) != year) s -= 20f
            if (!hit.posterPath.isNullOrBlank()) s += 8f
            val pop = hit.popularity ?: 0f
            val votes = hit.voteCount ?: 0
            if (ambiguous) {
                s += (pop / 80f).coerceAtMost(18f)
                s += (votes / 8000f).coerceAtMost(12f)
            } else {
                s += (pop / 200f).coerceAtMost(6f)
            }
            return s
        }
        val best = results.maxByOrNull(::rawScore) ?: return null
        val conf = (rawScore(best) / 140f).coerceIn(0f, 1f)
        if (ambiguous && conf < LOW_CONFIDENCE) {
            Log.w(TAG, "Match ambiguo '$query' → '${best.title ?: best.name}' score=$conf")
        }
        return best to conf
    }

    private fun titleSimilarity(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a == b) return 1f
        val bigA = if (a.length < 2) setOf(a) else (0 until a.length - 1).map { a.substring(it, it + 2) }.toSet()
        val bigB = if (b.length < 2) setOf(b) else (0 until b.length - 1).map { b.substring(it, it + 2) }.toSet()
        val dice = if (bigA.isEmpty() || bigB.isEmpty()) 0f
        else 2f * bigA.intersect(bigB).size / (bigA.size + bigB.size).toFloat()
        val wa = a.split(" ").filter { it.length > 1 }.toSet()
        val wb = b.split(" ").filter { it.length > 1 }.toSet()
        val token = if (wa.isEmpty() || wb.isEmpty()) 0f
        else wa.intersect(wb).size.toFloat() / maxOf(wa.size, wb.size)
        return maxOf(dice, token)
    }

    private fun normalizeTitle(s: String): String =
        s.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Quita tokens basura de release (misma lista que SynoStream). */
    private fun sanitizeSearchTitle(title: String): String {
        val junk = setOf(
            "atomixhq", "atomix", "pctreload", "newpct", "link", "com", "net", "org",
            "remux", "uhd", "hdr", "sdr", "hevc", "x265", "x264", "bluray", "webdl", "webrip"
        )
        return title.split(Regex("\\s+"))
            .filter { tok ->
                val t = tok.lowercase().trim('.')
                t.length > 1 && t !in junk && !t.matches(Regex("pctreload\\d+"))
            }
            .joinToString(" ")
            .trim()
    }

    private data class Hit(
        val id: Int,
        val title: String?,
        val posterPath: String?,
        val backdropPath: String?,
        val overview: String?,
        val voteAverage: Float?,
        val dateYear: String?,
        val fromMovie: Boolean
    ) {
        companion object {
            fun fromMovie(h: TmdbHit) = Hit(
                id = h.id,
                title = h.title ?: h.name,
                posterPath = h.posterPath,
                backdropPath = h.backdropPath,
                overview = h.overview,
                voteAverage = h.voteAverage,
                dateYear = h.releaseDate?.take(4),
                fromMovie = true
            )

            fun fromTv(h: TmdbHit) = Hit(
                id = h.id,
                title = h.name ?: h.title,
                posterPath = h.posterPath,
                backdropPath = h.backdropPath,
                overview = h.overview,
                voteAverage = h.voteAverage,
                dateYear = h.firstAirDate?.take(4),
                fromMovie = false
            )
        }
    }

    interface TmdbApi {
        @GET("search/movie")
        suspend fun searchMovie(
            @Query("api_key") key: String,
            @Query("query") query: String,
            @Query("year") year: String?,
            @Query("language") language: String = "es-ES"
        ): TmdbSearchResponse

        @GET("search/tv")
        suspend fun searchTv(
            @Query("api_key") key: String,
            @Query("query") query: String,
            @Query("first_air_date_year") year: String?,
            @Query("language") language: String = "es-ES"
        ): TmdbSearchResponse

        @GET("movie/{id}")
        suspend fun movieDetail(
            @Path("id") id: Int,
            @Query("api_key") key: String,
            @Query("language") language: String = "es-ES"
        ): TmdbMovieDetail

        @GET("tv/{id}")
        suspend fun tvDetail(
            @Path("id") id: Int,
            @Query("api_key") key: String,
            @Query("language") language: String = "es-ES"
        ): TmdbTvDetail

        @GET("movie/{id}/credits")
        suspend fun movieCredits(
            @Path("id") id: Int,
            @Query("api_key") key: String,
            @Query("language") language: String = "es-ES"
        ): TmdbCredits

        @GET("tv/{id}/credits")
        suspend fun tvCredits(
            @Path("id") id: Int,
            @Query("api_key") key: String,
            @Query("language") language: String = "es-ES"
        ): TmdbCredits

        @GET("person/{id}")
        suspend fun personDetail(
            @Path("id") id: Int,
            @Query("api_key") key: String,
            @Query("language") language: String = "es-ES",
            @Query("append_to_response") append: String? = null
        ): TmdbPersonBundle

        @GET("person/{id}/combined_credits")
        suspend fun personCredits(
            @Path("id") id: Int,
            @Query("api_key") key: String,
            @Query("language") language: String = "es-ES"
        ): TmdbPersonCredits

        @GET("movie/{id}/videos")
        suspend fun movieVideos(
            @Path("id") id: Int,
            @Query("api_key") key: String,
            @Query("language") language: String = "es-ES"
        ): TmdbVideos

        @GET("tv/{id}/videos")
        suspend fun tvVideos(
            @Path("id") id: Int,
            @Query("api_key") key: String,
            @Query("language") language: String = "es-ES"
        ): TmdbVideos

        @GET("collection/{id}")
        suspend fun collection(
            @Path("id") id: Int,
            @Query("api_key") key: String,
            @Query("language") language: String = "es-ES"
        ): TmdbCollection
    }

    data class TmdbVideos(val results: List<TmdbVideo> = emptyList())

    data class TmdbVideo(
        val key: String? = null,
        val name: String? = null,
        val site: String? = null,
        val type: String? = null,
        val official: Boolean? = null
    )

    data class TmdbCollection(
        val id: Int,
        val name: String? = null,
        val parts: List<TmdbHit>? = null
    )

    data class CollectionInfo(
        val id: Int,
        val name: String,
        val localParts: List<MediaItem>
    )

    /** YouTube key del trailer oficial (o primer trailer/teaser). */
    suspend fun findTrailerYoutubeKey(item: MediaItem): String? {
        val api = api ?: return null
        return try {
            val queries = searchQueries(item)
            val year = yearForSearch(item)
            val videos = if (item.mediaType == MediaType.MOVIE) {
                val hit = findMovieHit(api, queries, year) ?: return null
                api.movieVideos(hit.id, BuildConfig.TMDB_API_KEY).results
            } else {
                val hit = findTvHit(api, queries, year) ?: return null
                api.tvVideos(hit.id, BuildConfig.TMDB_API_KEY).results
            }
            val yt = videos.filter { it.site.equals("YouTube", ignoreCase = true) && !it.key.isNullOrBlank() }
            yt.firstOrNull { it.type.equals("Trailer", true) && it.official == true }?.key
                ?: yt.firstOrNull { it.type.equals("Trailer", true) }?.key
                ?: yt.firstOrNull { it.type.equals("Teaser", true) }?.key
                ?: yt.firstOrNull()?.key
        } catch (e: Exception) {
            Log.w(TAG, "Trailer falló: ${item.title}", e)
            null
        }
    }

    /** Colección TMDB cruzada con catálogo local (películas). */
    suspend fun loadLocalCollection(item: MediaItem, localMovies: List<MediaItem>): CollectionInfo? {
        val api = api ?: return null
        if (item.mediaType != MediaType.MOVIE) return null
        return try {
            val queries = searchQueries(item)
            val year = yearForSearch(item)
            val hit = findMovieHit(api, queries, year) ?: return null
            val detail = api.movieDetail(hit.id, BuildConfig.TMDB_API_KEY)
            val colId = detail.belongsToCollection?.id ?: return null
            val colName = detail.belongsToCollection.name.orEmpty().ifBlank { "Colección" }
            val col = api.collection(colId, BuildConfig.TMDB_API_KEY)
            val titles = col.parts.orEmpty().mapNotNull { (it.title ?: it.name)?.trim() }.filter { it.isNotEmpty() }
            val matched = localMovies.filter { local ->
                titles.any { ct -> titlesMatch(local.title, ct) }
            }.distinctBy { it.id }
                .sortedBy { it.year ?: it.title }
            if (matched.size < 2) return null
            CollectionInfo(colId, colName, matched)
        } catch (e: Exception) {
            Log.w(TAG, "Collection falló: ${item.title}", e)
            null
        }
    }

    data class TmdbSearchResponse(val results: List<TmdbHit> = emptyList())

    data class TmdbHit(
        val id: Int,
        val title: String? = null,
        val name: String? = null,
        @SerializedName("original_title") val originalTitle: String? = null,
        @SerializedName("original_name") val originalName: String? = null,
        @SerializedName("poster_path") val posterPath: String?,
        @SerializedName("backdrop_path") val backdropPath: String?,
        val overview: String?,
        @SerializedName("vote_average") val voteAverage: Float?,
        val popularity: Float? = null,
        @SerializedName("vote_count") val voteCount: Int? = null,
        @SerializedName("release_date") val releaseDate: String?,
        @SerializedName("first_air_date") val firstAirDate: String?
    )

    data class TmdbBelongsToCollection(
        val id: Int,
        val name: String? = null
    )

    data class TmdbMovieDetail(
        val runtime: Int?,
        val genres: List<TmdbGenre>? = null,
        @SerializedName("belongs_to_collection") val belongsToCollection: TmdbBelongsToCollection? = null
    )

    data class TmdbTvDetail(
        @SerializedName("episode_run_time") val episodeRunTime: List<Int>? = null,
        val genres: List<TmdbGenre>? = null
    )

    data class TmdbGenre(val name: String)

    data class TmdbCredits(val cast: List<TmdbCastCredit>? = null)

    data class TmdbCastCredit(
        val id: Int,
        val name: String? = null,
        val character: String? = null,
        @SerializedName("profile_path") val profilePath: String? = null,
        val order: Int? = null
    )

    data class TmdbPersonBundle(
        val id: Int,
        val name: String? = null,
        val biography: String? = null,
        @SerializedName("profile_path") val profilePath: String? = null,
        @SerializedName("known_for_department") val knownFor: String? = null,
        @SerializedName("combined_credits") val combinedCredits: TmdbPersonCredits? = null
    )

    data class TmdbPersonCredits(
        val cast: List<TmdbPersonCredit>? = null
    )

    data class TmdbPersonCredit(
        val id: Int,
        val title: String? = null,
        val name: String? = null,
        val character: String? = null,
        @SerializedName("media_type") val mediaType: String? = null,
        @SerializedName("release_date") val releaseDate: String? = null,
        @SerializedName("first_air_date") val firstAirDate: String? = null
    )

    data class PersonProfile(
        val id: Int,
        val name: String,
        val biography: String,
        val profileUrl: String?,
        val knownFor: String
    )

    /**
     * Filmografía local del actor.
     * Usa cache de credits + cruce por tmdbId (O(n)); fallback fuzzy por título.
     * Perfil y credits en una sola llamada TMDB si no hay cache.
     */
    suspend fun loadPersonLocalFilmography(
        personId: Int,
        localCatalog: List<MediaItem>
    ): Pair<PersonProfile?, List<MediaItem>> {
        if (personId <= 0) return null to emptyList()
        val profile = loadPersonProfile(personId)
        val credits = loadPersonCreditsCached(personId)
        val matched = matchLocalFilmography(credits, localCatalog)
        PersonIndex.indexMatches(personId, matched)
        return profile to matched
    }

    /** Solo perfil (cache disco/memoria o red). No bloquea la lista. */
    suspend fun loadPersonProfile(personId: Int): PersonProfile? {
        if (personId <= 0) return null
        PersonIndex.cachedProfile(personId)?.let { c ->
            return PersonProfile(c.id, c.name, c.biography, c.profileUrl, c.knownFor)
        }
        ensurePersonCached(personId)
        return PersonIndex.cachedProfile(personId)?.let { c ->
            PersonProfile(c.id, c.name, c.biography, c.profileUrl, c.knownFor)
        }
    }

    /** Credits del actor: memoria -> disco -> red (con dedupe de inflight). */
    suspend fun loadPersonCreditsCached(personId: Int): List<PersonIndex.CachedCredit> {
        if (personId <= 0) return emptyList()
        PersonIndex.cachedCredits(personId)?.let { return it }
        ensurePersonCached(personId)
        return PersonIndex.cachedCredits(personId).orEmpty()
    }

    /** Una sola llamada TMDB (person + combined_credits) compartida entre perfil/credits. */
    private suspend fun ensurePersonCached(personId: Int) {
        if (PersonIndex.cachedProfile(personId) != null &&
            PersonIndex.cachedCredits(personId) != null
        ) return
        val api = api ?: return
        val mutex = creditFetchLocks.getOrPut(personId) { Mutex() }
        mutex.lock()
        try {
            if (PersonIndex.cachedProfile(personId) != null &&
                PersonIndex.cachedCredits(personId) != null
            ) return
            val bundle = api.personDetail(
                personId,
                BuildConfig.TMDB_API_KEY,
                append = "combined_credits"
            )
            if (PersonIndex.cachedProfile(personId) == null) {
                PersonIndex.putProfile(
                    personId,
                    PersonIndex.CachedProfile(
                        id = bundle.id,
                        name = bundle.name.orEmpty(),
                        biography = bundle.biography.orEmpty(),
                        profileUrl = bundle.profilePath?.let { PROFILE + it },
                        knownFor = bundle.knownFor.orEmpty()
                    )
                )
            }
            if (PersonIndex.cachedCredits(personId) == null) {
                val cast = bundle.combinedCredits?.cast
                    ?: runCatching {
                        api.personCredits(personId, BuildConfig.TMDB_API_KEY).cast
                    }.getOrNull()
                    ?: emptyList()
                cacheCreditsFromApi(personId, cast)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Person bundle falló id=$personId", e)
        } finally {
            mutex.unlock()
            creditFetchLocks.remove(personId)
        }
    }

    /**
     * Prefetch al pintar elenco: credits TMDB + cruce local indexado,
     * para que abrir ficha de actor muestre filmografía sin red.
     */
    suspend fun prefetchPersonCredits(personIds: List<Int>) {
        if (api == null || personIds.isEmpty()) return
        val local = CatalogCache.movies + CatalogCache.series
        // Prioriza los primeros del elenco (los más clicables); ya cacheados solo re-indexan
        val ordered = personIds.filter { it > 0 }.distinct().take(12)
        for (pid in ordered) {
            prefetchSemaphore.withPermit {
                runCatching {
                    val credits = loadPersonCreditsCached(pid)
                    if (credits.isNotEmpty() && local.isNotEmpty()) {
                        PersonIndex.indexMatches(pid, matchLocalFilmography(credits, local))
                    }
                }
            }
        }
    }

    /** Cruce O(n) por tmdbId; fallback por título normalizado (sin N×M). */
    fun matchLocalFilmography(
        credits: List<PersonIndex.CachedCredit>,
        localCatalog: List<MediaItem>
    ): List<MediaItem> {
        if (credits.isEmpty() || localCatalog.isEmpty()) return emptyList()
        val creditIds = HashSet<Int>(credits.size)
        val normTitles = HashSet<String>()
        for (c in credits) {
            creditIds.add(c.id)
            val n = normalizeTitle(c.title)
            if (n.isNotEmpty()) normTitles.add(n)
        }
        val byId = ArrayList<MediaItem>()
        val byTitle = ArrayList<MediaItem>()
        for (item in localCatalog) {
            val tid = item.tmdbId
            if (tid != null && tid in creditIds) {
                byId.add(item)
                continue
            }
            if (normTitles.isEmpty()) continue
            val ln = normalizeTitle(item.title)
            if (ln.isEmpty()) continue
            if (ln in normTitles || titleInSet(ln, normTitles)) byTitle.add(item)
        }
        return (byId + byTitle)
            .distinctBy { it.id }
            .sortedBy { it.title.lowercase() }
    }

    private fun titleInSet(localNorm: String, creditNorms: Set<String>): Boolean {
        for (ct in creditNorms) {
            if ((localNorm.contains(ct) || ct.contains(localNorm)) &&
                localNorm.length >= 4 && ct.length >= 4
            ) return true
        }
        return false
    }

    private fun titlesMatch(local: String, credit: String): Boolean {
        val a = normalizeTitle(local)
        val b = normalizeTitle(credit)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        return (a.contains(b) || b.contains(a)) && a.length >= 4 && b.length >= 4
    }

    private fun cacheCreditsFromApi(
        personId: Int,
        cast: List<TmdbPersonCredit>
    ): List<PersonIndex.CachedCredit> {
        val cached = cast.mapNotNull { c ->
            val title = (c.title ?: c.name)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            PersonIndex.CachedCredit(id = c.id, title = title, mediaType = c.mediaType)
        }
        PersonIndex.putCredits(personId, cached)
        return cached
    }

    companion object {
        private const val TAG = "PinerTV-TMDB"
        private const val IMG = "https://image.tmdb.org/t/p/w500"
        private const val BACKDROP = "https://image.tmdb.org/t/p/w1280"
        private const val PROFILE = "https://image.tmdb.org/t/p/w185"
        private const val LOW_CONFIDENCE = 0.55f
        private val creditFetchLocks = ConcurrentHashMap<Int, Mutex>()
        private val prefetchSemaphore = Semaphore(3)
    }
}
