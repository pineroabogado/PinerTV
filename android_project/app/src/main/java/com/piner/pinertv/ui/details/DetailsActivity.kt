package com.piner.pinertv.ui.details

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import com.piner.pinertv.R
import com.piner.pinertv.catalog.CastMember
import com.piner.pinertv.catalog.EpisodeEntry
import com.piner.pinertv.catalog.ExtrasLoader
import com.piner.pinertv.catalog.MediaItem
import com.piner.pinertv.catalog.MediaType
import com.piner.pinertv.catalog.SeasonBucket
import com.piner.pinertv.catalog.SeriesStructureLoader
import com.piner.pinertv.data.CatalogCache
import com.piner.pinertv.data.ContinueWatchingStore
import com.piner.pinertv.data.FavoritesStore
import com.piner.pinertv.data.PersonIndex
import com.piner.pinertv.data.SecurePrefs
import com.piner.pinertv.metadata.TmdbRepository
import com.piner.pinertv.nas.SynologyRepository
import com.piner.pinertv.ui.actor.ActorActivity
import com.piner.pinertv.ui.player.PlayerActivity
import com.piner.pinertv.ui.widget.FocusStableNestedScrollView
import com.piner.pinertv.ui.widget.PosterLoader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Detalle estilo Emby: backdrop + poster + meta + Opciones.
 * En series: selector de temporada + tiles de episodios.
 */
class DetailsActivity : FragmentActivity() {

    private lateinit var item: MediaItem
    private lateinit var store: ContinueWatchingStore
    private var seasons: List<SeasonBucket> = emptyList()
    private var selectedSeason: Int = 1
    private var resumeEpisode: EpisodeEntry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)

        @Suppress("DEPRECATION")
        val raw = intent.getSerializableExtra(EXTRA_ITEM) as? MediaItem
        if (raw == null) {
            finish()
            return
        }
        item = enrich(raw)
        store = ContinueWatchingStore(this)
        val favorites = FavoritesStore(this)

        findViewById<TextView>(R.id.txtTitle).text = item.title
        findViewById<TextView>(R.id.txtMeta).text = buildMeta(item)
        findViewById<TextView>(R.id.txtOverview).text =
            item.overview.ifBlank { "Sin sinopsis disponible. Reproducción directa desde el NAS." }

        findViewById<ImageView>(R.id.imgPoster).load(item.posterUrl) {
            placeholder(R.drawable.poster_placeholder)
            error(R.drawable.poster_placeholder)
            crossfade(true)
        }
        findViewById<ImageView>(R.id.imgBackdrop).load(item.backdropUrl ?: item.posterUrl) {
            crossfade(true)
            placeholder(R.color.emby_bg_deep)
        }

        bindActions(favorites)
        loadCast()
        loadCollection()
        loadExtras()

        if (item.mediaType == MediaType.SERIES) {
            findViewById<View>(R.id.blockSeasons).visibility = View.VISIBLE
            findViewById<FocusStableNestedScrollView>(R.id.scrollDetails).suppressFocusScroll = false
            loadSeriesStructure()
        }
    }

    private fun loadExtras() {
        val prefs = SecurePrefs(this)
        val baseUrl = CatalogCache.baseUrl ?: prefs.nasUrl
        val repo = SynologyRepository(baseUrl)
        CatalogCache.sid?.let { repo.setSid(it) }

        lifecycleScope.launch {
            val extras = withContext(Dispatchers.IO) {
                if (repo.getSid() == null) {
                    repo.login(prefs.nasUser, prefs.nasPass)
                    CatalogCache.sid = repo.getSid()
                }
                ExtrasLoader(repo).loadFor(item)
            }
            if (extras.isEmpty()) return@launch
            val block = findViewById<View>(R.id.blockExtras)
            val row = findViewById<LinearLayout>(R.id.rowExtras)
            block.visibility = View.VISIBLE
            row.removeAllViews()
            val density = resources.displayMetrics.density
            extras.forEach { extra ->
                val tile = LayoutInflater.from(this@DetailsActivity)
                    .inflate(R.layout.item_episode_tile, row, false)
                tile.findViewById<TextView>(R.id.txtEpCode).text = extra.folderLabel
                tile.findViewById<TextView>(R.id.txtEpTitle).text = extra.title
                tile.findViewById<ProgressBar>(R.id.progressEp).visibility = View.GONE
                val lp = LinearLayout.LayoutParams(
                    (220 * density).toInt(),
                    (128 * density).toInt()
                )
                lp.marginEnd = (12 * density).toInt()
                tile.layoutParams = lp
                tile.setOnClickListener {
                    val playItem = MediaItem(
                        id = extra.filePath,
                        title = "${item.title} · ${extra.title}",
                        year = item.year,
                        overview = item.overview,
                        posterUrl = item.posterUrl,
                        backdropUrl = item.backdropUrl,
                        filePath = extra.filePath,
                        mediaType = MediaType.MOVIE,
                        folderPath = item.folderPath
                    )
                    openPlayer(playItem, 0L)
                }
                row.addView(tile)
            }
        }
    }

    private fun loadCollection() {
        if (item.mediaType != MediaType.MOVIE) return
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                TmdbRepository().loadLocalCollection(item, CatalogCache.movies)
            } ?: return@launch
            val block = findViewById<View>(R.id.blockCollection)
            val row = findViewById<LinearLayout>(R.id.rowCollection)
            val lbl = findViewById<TextView>(R.id.lblCollection)
            lbl.text = info.name
            block.visibility = View.VISIBLE
            row.removeAllViews()
            val density = resources.displayMetrics.density
            info.localParts.forEach { part ->
                val card = LayoutInflater.from(this@DetailsActivity)
                    .inflate(R.layout.view_media_card, row, false)
                card.findViewById<TextView>(R.id.txtTitle).text = part.title
                card.findViewById<TextView>(R.id.txtMeta).text = part.year.orEmpty()
                PosterLoader.load(card.findViewById(R.id.imgPoster), part.posterUrl)
                val lp = LinearLayout.LayoutParams(
                    (120 * density).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = (10 * density).toInt()
                card.layoutParams = lp
                card.setOnClickListener {
                    startActivity(
                        Intent(this@DetailsActivity, DetailsActivity::class.java)
                            .putExtra(EXTRA_ITEM, part)
                    )
                }
                row.addView(card)
            }
        }
    }

    private fun loadCast() {
        val block = findViewById<View>(R.id.blockCast)
        val status = findViewById<TextView>(R.id.txtCastStatus)
        block.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        status.text = getString(R.string.loading_cast)

        lifecycleScope.launch {
            val cast = withContext(Dispatchers.IO) {
                TmdbRepository().loadCast(item)
            }
            if (cast.isEmpty()) {
                status.text = getString(R.string.no_cast)
                findViewById<LinearLayout>(R.id.rowCast).removeAllViews()
                return@launch
            }
            status.visibility = View.GONE
            // Índice local: este título ↔ actores del elenco
            PersonIndex.linkCast(item.id, cast.map { it.id })
            bindCast(cast)
            // Prefetch credits para que abrir ficha de actor sea instantáneo
            launch(Dispatchers.IO) {
                TmdbRepository().prefetchPersonCredits(cast.map { it.id })
            }
        }
    }

    private fun bindCast(cast: List<CastMember>) {
        val row = findViewById<LinearLayout>(R.id.rowCast)
        row.removeAllViews()
        val density = resources.displayMetrics.density
        cast.forEach { member ->
            val tile = LayoutInflater.from(this).inflate(R.layout.item_cast_tile, row, false)
            tile.findViewById<TextView>(R.id.txtCastName).text = member.name
            tile.findViewById<TextView>(R.id.txtCastRole).text =
                member.character.ifBlank { "—" }
            tile.findViewById<ImageView>(R.id.imgCast).load(member.profileUrl) {
                placeholder(R.drawable.poster_placeholder)
                error(R.drawable.poster_placeholder)
                crossfade(true)
            }
            val lp = LinearLayout.LayoutParams(
                (112 * density).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (12 * density).toInt()
            tile.layoutParams = lp
            tile.setOnClickListener {
                startActivity(
                    Intent(this, ActorActivity::class.java)
                        .putExtra(ActorActivity.EXTRA_PERSON_ID, member.id)
                        .putExtra(ActorActivity.EXTRA_PERSON_NAME, member.name)
                        .putExtra(ActorActivity.EXTRA_PERSON_PHOTO, member.profileUrl)
                        .putExtra(ActorActivity.EXTRA_SEED_MEDIA_ID, item.id)
                )
            }
            row.addView(tile)
        }
    }

    private fun bindActions(favorites: FavoritesStore) {
        val row = findViewById<LinearLayout>(R.id.rowActions)
        row.removeAllViews()

        val playTile = addTile(row, R.drawable.ic_play_filled, getString(R.string.play)) {
            onPlayPrimary()
        }

        val resumePos = resolveResume()
        if (resumePos != null && resumePos.second > 0L) {
            val mins = (resumePos.second / 60_000L).toInt()
            val label = if (mins > 0) "Reanudar · ${mins} min" else getString(R.string.resume)
            val resumeTile = addTile(row, R.drawable.ic_replay, label) {
                openPlayer(resumePos.first, resumePos.second)
            }
            resumeTile.requestFocus()
        } else {
            playTile.requestFocus()
        }

        addTile(row, R.drawable.ic_play_filled, getString(R.string.trailer)) {
            openTrailer()
        }

        // Favoritos: se mantiene el tile existente (sin ampliar la feature).
        val favTile = addTile(
            row,
            R.drawable.ic_favorite,
            getString(R.string.favorite)
        ) { tile ->
            val added = favorites.toggle(item)
            Toast.makeText(
                this,
                if (added) R.string.favorite_added else R.string.favorite_removed,
                Toast.LENGTH_SHORT
            ).show()
            tile.alpha = if (favorites.isFavorite(item.id)) 1f else 0.55f
        }
        favTile.alpha = if (favorites.isFavorite(item.id)) 1f else 0.55f
    }

    private fun openTrailer() {
        lifecycleScope.launch {
            val key = withContext(Dispatchers.IO) { TmdbRepository().findTrailerYoutubeKey(item) }
            if (key.isNullOrBlank()) {
                Toast.makeText(this@DetailsActivity, R.string.no_trailer, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val app = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$key"))
            val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$key"))
            try {
                startActivity(app)
            } catch (_: Exception) {
                startActivity(web)
            }
        }
    }

    private fun resolveResume(): Pair<MediaItem, Long>? {
        if (item.mediaType != MediaType.SERIES) {
            val pos = store.getPositionFor(item)
            return if (pos > 0L) item to pos else null
        }
        // Serie: reanudar el episodio con progreso (por filePath), o el id de la serie.
        val seriesPos = store.getPositionFor(item)
        if (seriesPos > 0L) return item to seriesPos
        resumeEpisode?.let { ep ->
            val pos = store.getPosition(ep.filePath)
            if (pos > 0L) return episodeAsMedia(ep) to pos
        }
        return null
    }

    private fun onPlayPrimary() {
        if (item.mediaType != MediaType.SERIES) {
            openPlayer(item, 0L)
            return
        }
        val first = seasons.firstOrNull { it.season == selectedSeason }?.episodes?.firstOrNull()
            ?: seasons.firstOrNull()?.episodes?.firstOrNull()
        if (first != null) {
            openPlayer(episodeAsMedia(first), 0L)
        } else {
            openPlayer(item, 0L)
        }
    }

    private fun loadSeriesStructure() {
        val status = findViewById<TextView>(R.id.txtEpisodesStatus)
        status.visibility = View.VISIBLE
        status.text = getString(R.string.loading_episodes)

        val prefs = SecurePrefs(this)
        val baseUrl = CatalogCache.baseUrl ?: prefs.nasUrl
        val repo = SynologyRepository(baseUrl)
        CatalogCache.sid?.let { repo.setSid(it) }

        lifecycleScope.launch {
            val buckets = withContext(Dispatchers.IO) {
                if (repo.getSid() == null) {
                    repo.login(prefs.nasUser, prefs.nasPass)
                    CatalogCache.sid = repo.getSid()
                }
                val loader = SeriesStructureLoader(repo)
                val root = loader.resolveSeriesRoot(item)
                loader.load(root)
            }
            seasons = buckets
            if (buckets.isEmpty()) {
                status.text = getString(R.string.no_episodes)
                return@launch
            }
            status.visibility = View.GONE

            // Preferir temporada del episodio con progreso
            val progressSeason = findResumeEpisode(buckets)?.season
            selectedSeason = progressSeason
                ?: buckets.first().season
            resumeEpisode = findResumeEpisode(buckets)

            bindSeasonChips()
            bindEpisodes()
            // Re-bind acciones para reflejar Reanudar por episodio
            bindActions(FavoritesStore(this@DetailsActivity))
        }
    }

    private fun findResumeEpisode(buckets: List<SeasonBucket>): EpisodeEntry? {
        var best: EpisodeEntry? = null
        var bestUpdated = -1L
        // Usamos progressPercent > 0 como señal; el store no expone updatedAt aquí.
        for (bucket in buckets) {
            for (ep in bucket.episodes) {
                val pct = store.progressPercent(ep.filePath)
                if (pct in 1..94) {
                    val pos = store.getPosition(ep.filePath)
                    if (pos > bestUpdated) {
                        bestUpdated = pos
                        best = ep
                    }
                }
            }
        }
        return best
    }

    private fun refreshSeasonSelection(focusSeason: Int) {
        bindSeasonChips()
        bindEpisodes()
        findViewById<LinearLayout>(R.id.rowSeasons)
            .findViewWithTag<View>(focusSeason)
            ?.requestFocus()
    }

    private fun bindSeasonChips() {
        val row = findViewById<LinearLayout>(R.id.rowSeasons)
        row.removeAllViews()
        val density = resources.displayMetrics.density
        seasons.forEach { bucket ->
            val chip = LayoutInflater.from(this)
                .inflate(R.layout.item_season_chip, row, false) as TextView
            chip.text = bucket.label
            chip.isSelected = bucket.season == selectedSeason
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (10 * density).toInt()
            chip.layoutParams = lp
            chip.tag = bucket.season
            chip.setOnClickListener {
                if (selectedSeason != bucket.season) {
                    selectedSeason = bucket.season
                    refreshSeasonSelection(focusSeason = bucket.season)
                }
            }
            chip.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && selectedSeason != bucket.season) {
                    selectedSeason = bucket.season
                    refreshSeasonSelection(focusSeason = bucket.season)
                }
            }
            row.addView(chip)
        }
    }

    private fun bindEpisodes() {
        val row = findViewById<LinearLayout>(R.id.rowEpisodes)
        row.removeAllViews()
        val density = resources.displayMetrics.density
        val episodes = seasons.firstOrNull { it.season == selectedSeason }?.episodes.orEmpty()
        findViewById<TextView>(R.id.lblEpisodes).text =
            getString(R.string.episodes) + " · ${episodes.size}"

        if (episodes.isEmpty()) {
            findViewById<TextView>(R.id.txtEpisodesStatus).apply {
                visibility = View.VISIBLE
                text = getString(R.string.no_episodes)
            }
            return
        }
        findViewById<TextView>(R.id.txtEpisodesStatus).visibility = View.GONE

        episodes.forEach { ep ->
            val tile = LayoutInflater.from(this).inflate(R.layout.item_episode_tile, row, false)
            tile.findViewById<TextView>(R.id.txtEpCode).text = ep.displayCode
            tile.findViewById<TextView>(R.id.txtEpTitle).text = episodeDisplayTitle(ep)
            val progress = tile.findViewById<ProgressBar>(R.id.progressEp)
            val pct = store.progressPercent(ep.filePath)
            if (pct in 1..99) {
                progress.visibility = View.VISIBLE
                progress.progress = pct
            } else {
                progress.visibility = View.GONE
            }
            val lp = LinearLayout.LayoutParams(
                (220 * density).toInt(),
                (128 * density).toInt()
            )
            lp.marginEnd = (12 * density).toInt()
            tile.layoutParams = lp
            tile.setOnClickListener {
                val mediaEp = episodeAsMedia(ep)
                val pos = store.getPositionFor(mediaEp)
                openPlayer(mediaEp, if (pos > 0L) pos else 0L)
            }
            row.addView(tile)
        }
    }

    private fun episodeDisplayTitle(ep: EpisodeEntry): String {
        val raw = ep.title.trim()
        val series = item.title.trim()
        if (raw.isBlank()) {
            return if (ep.episode != null) "Episodio ${ep.episode}" else "Episodio"
        }
        // Nombres de archivo tipo "Better Call Saul 6" → etiqueta usable
        val looksLikeSeriesOnly = raw.equals(series, ignoreCase = true) ||
            raw.matches(Regex("(?i)${Regex.escape(series)}\\s*\\d{0,2}"))
        return if (looksLikeSeriesOnly && ep.episode != null) {
            "Episodio ${ep.episode}"
        } else {
            raw
        }
    }

    private fun episodeAsMedia(ep: EpisodeEntry): MediaItem {
        val code = when {
            ep.episode != null ->
                "S${ep.season.toString().padStart(2, '0')}E${ep.episode.toString().padStart(2, '0')}"
            else -> "T${ep.season}"
        }
        return MediaItem(
            id = ep.filePath,
            title = "${item.title} · $code",
            year = item.year,
            overview = item.overview,
            posterUrl = item.posterUrl,
            backdropUrl = item.backdropUrl,
            filePath = ep.filePath,
            mediaType = MediaType.SERIES,
            voteAverage = item.voteAverage,
            runtime = item.runtime,
            genres = item.genres,
            folderPath = item.folderPath,
            tmdbId = item.tmdbId
        )
    }

    private fun enrich(item: MediaItem): MediaItem {
        return (CatalogCache.movies + CatalogCache.series).find { it.id == item.id } ?: item
    }

    private fun buildMeta(item: MediaItem): String = buildString {
        append(if (item.mediaType == MediaType.MOVIE) "PELÍCULA" else "SERIE")
        if (item.voteAverage > 0f) {
            append("   ·   ★ ")
            append(String.format(Locale.getDefault(), "%.1f", item.voteAverage))
        }
        if (!item.year.isNullOrBlank()) {
            append("   ·   ")
            append(item.year)
        }
        item.runtime?.takeIf { it > 0 }?.let { mins ->
            append("   ·   ")
            val h = mins / 60
            val m = mins % 60
            append(if (h > 0) "${h}h ${m}m" else "${m}m")
            append("   ·   (Termina ")
            val cal = Calendar.getInstance()
            cal.add(Calendar.MINUTE, mins)
            append(SimpleDateFormat("H:mm", Locale.getDefault()).format(cal.time))
            append(")")
        }
        if (item.genres.isNotEmpty()) {
            append("   ·   ")
            append(item.genres.take(3).joinToString(", "))
        }
    }

    private fun addTile(
        parent: LinearLayout,
        iconRes: Int,
        label: String,
        onClick: (View) -> Unit
    ): View {
        val tile = LayoutInflater.from(this).inflate(R.layout.item_action_tile, parent, false)
        tile.findViewById<ImageView>(R.id.imgAction).setImageResource(iconRes)
        tile.findViewById<TextView>(R.id.txtAction).text = label
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = (12 * resources.displayMetrics.density).toInt()
        tile.layoutParams = lp
        tile.setOnClickListener { onClick(tile) }
        parent.addView(tile)
        return tile
    }

    private fun openPlayer(item: MediaItem, positionMs: Long) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_ITEM, item)
                .putExtra(PlayerActivity.EXTRA_POSITION, positionMs)
        )
    }

    companion object {
        const val EXTRA_ITEM = "extra_item"
    }
}
