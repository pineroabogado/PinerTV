package com.piner.pinertv.ui.library

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.piner.pinertv.R
import com.piner.pinertv.catalog.MediaItem
import com.piner.pinertv.catalog.MediaType
import com.piner.pinertv.data.CatalogCache
import com.piner.pinertv.ui.details.DetailsActivity
import com.piner.pinertv.ui.widget.CardRoundClip
import com.piner.pinertv.ui.widget.FocusStableRecyclerView
import java.util.Locale

/**
 * Biblioteca estilo Emby: hero + filtros (A–Z / Año / Género) + rejilla.
 */
class LibraryActivity : FragmentActivity() {

    private enum class SortMode { AZ, YEAR }

    private lateinit var imgBackdrop: ImageView
    private lateinit var txtItemTitle: TextView
    private lateinit var txtItemMeta: TextView
    private lateinit var txtItemOverview: TextView
    private lateinit var adapter: LibraryGridAdapter

    private var allItems: List<MediaItem> = emptyList()
    private var sortMode: SortMode = SortMode.AZ
    private var genreFilter: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        val type = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_MOVIES
        allItems = if (type == TYPE_MOVIES) CatalogCache.movies else CatalogCache.series

        findViewById<TextView>(R.id.txtLibTitle).text =
            if (type == TYPE_MOVIES) getString(R.string.movies) else getString(R.string.series)

        imgBackdrop = findViewById(R.id.imgLibBackdrop)
        txtItemTitle = findViewById(R.id.txtLibItemTitle)
        txtItemMeta = findViewById(R.id.txtLibItemMeta)
        txtItemOverview = findViewById(R.id.txtLibItemOverview)

        val grid = findViewById<FocusStableRecyclerView>(R.id.gridLibrary)
        grid.layoutManager = GridLayoutManager(this, 5)
        grid.setHasFixedSize(true)
        grid.suppressFocusScroll = true
        val d = resources.displayMetrics.density
        grid.setPadding(
            (56 * d).toInt(),
            (8 * d).toInt(),
            (40 * d).toInt(),
            (12 * d).toInt()
        )
        grid.clipToPadding = false
        adapter = LibraryGridAdapter(::showHero, ::openDetails)
        grid.adapter = adapter

        bindFilterChips()
        applyFilters(requestFocus = true)
    }

    private fun bindFilterChips() {
        val row = findViewById<LinearLayout>(R.id.rowLibFilters)
        row.removeAllViews()
        val density = resources.displayMetrics.density

        fun addChip(label: String, selected: Boolean, onClick: () -> Unit) {
            val chip = LayoutInflater.from(this)
                .inflate(R.layout.item_season_chip, row, false) as TextView
            chip.text = label
            chip.isSelected = selected
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (10 * density).toInt()
            chip.layoutParams = lp
            chip.setOnClickListener { onClick() }
            row.addView(chip)
        }

        addChip(getString(R.string.sort_az), sortMode == SortMode.AZ) {
            sortMode = SortMode.AZ
            bindFilterChips()
            applyFilters(requestFocus = true)
        }
        addChip(getString(R.string.sort_year), sortMode == SortMode.YEAR) {
            sortMode = SortMode.YEAR
            bindFilterChips()
            applyFilters(requestFocus = true)
        }

        val genres = allItems.flatMap { it.genres }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(12)

        if (genres.isNotEmpty()) {
            addChip(getString(R.string.filter_all), genreFilter == null) {
                genreFilter = null
                bindFilterChips()
                applyFilters(requestFocus = true)
            }
            genres.forEach { g ->
                addChip(g, genreFilter == g) {
                    genreFilter = if (genreFilter == g) null else g
                    bindFilterChips()
                    applyFilters(requestFocus = true)
                }
            }
        }
    }

    private fun applyFilters(requestFocus: Boolean) {
        var list = allItems
        genreFilter?.let { g ->
            list = list.filter { item ->
                item.genres.any { it.equals(g, ignoreCase = true) }
            }
        }
        list = when (sortMode) {
            SortMode.AZ -> list.sortedBy { it.title.lowercase(Locale.getDefault()) }
            SortMode.YEAR -> list.sortedWith(
                compareByDescending<MediaItem> { it.year?.toIntOrNull() ?: 0 }
                    .thenBy { it.title.lowercase(Locale.getDefault()) }
            )
        }
        LibraryGridAdapter.lastFocusedRow = -1
        adapter.submit(list)
        list.firstOrNull()?.let { showHero(it) }
        if (requestFocus) {
            findViewById<RecyclerView>(R.id.gridLibrary).post {
                findViewById<RecyclerView>(R.id.gridLibrary).getChildAt(0)?.requestFocus()
            }
        }
    }

    private fun showHero(item: MediaItem) {
        txtItemTitle.text = item.title
        txtItemMeta.text = buildString {
            if (item.voteAverage > 0f) {
                append("★ ")
                append(String.format(Locale.getDefault(), "%.1f", item.voteAverage))
            }
            if (!item.year.isNullOrBlank()) {
                if (isNotEmpty()) append("      ")
                append(item.year)
            }
            item.runtime?.takeIf { it > 0 }?.let { mins ->
                if (isNotEmpty()) append("      ")
                val h = mins / 60
                val m = mins % 60
                append(if (h > 0) "${h}h ${m}m" else "${m}m")
            }
            if (item.genres.isNotEmpty()) {
                if (isNotEmpty()) append("      ")
                append(item.genres.take(2).joinToString(", "))
            }
            if (isEmpty()) {
                append(if (item.mediaType == MediaType.MOVIE) "Película" else "Serie")
            }
        }
        txtItemOverview.text = item.overview.ifBlank { "" }
        imgBackdrop.load(item.backdropUrl ?: item.posterUrl) {
            crossfade(250)
            placeholder(R.color.emby_bg_deep)
            error(R.color.emby_bg_deep)
        }
    }

    private fun openDetails(item: MediaItem) {
        startActivity(
            Intent(this, DetailsActivity::class.java)
                .putExtra(DetailsActivity.EXTRA_ITEM, item)
        )
    }

    companion object {
        const val EXTRA_TYPE = "extra_type"
        const val TYPE_MOVIES = "movies"
        const val TYPE_SERIES = "series"
    }
}

private class LibraryGridAdapter(
    private val onFocused: (MediaItem) -> Unit,
    private val onClick: (MediaItem) -> Unit
) : RecyclerView.Adapter<LibraryGridAdapter.Holder>() {

    private var items: List<MediaItem> = emptyList()

    fun submit(newItems: List<MediaItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val img: ImageView = view.findViewById(R.id.imgPoster)
        val title: TextView = view.findViewById(R.id.txtTitle)
        val meta: TextView = view.findViewById(R.id.txtMeta)
        val rating: TextView = view.findViewById(R.id.txtRating)
        val noPoster: TextView = view.findViewById(R.id.txtNoPoster)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.view_media_card, parent, false)
        val lp = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val m = (6 * parent.resources.displayMetrics.density).toInt()
        lp.setMargins(m, m, m, m)
        view.layoutParams = lp
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val media = items[position]
        holder.title.text = media.title
        holder.meta.text = buildString {
            if (!media.year.isNullOrBlank()) append(media.year)
            if (media.voteAverage > 0f) {
                if (isNotEmpty()) append(" • ")
                append(String.format(Locale.getDefault(), "%.1f", media.voteAverage))
            }
        }
        if (media.voteAverage > 0f) {
            holder.rating.visibility = View.VISIBLE
            holder.rating.text = String.format(Locale.getDefault(), "%.1f", media.voteAverage)
        } else {
            holder.rating.visibility = View.GONE
        }
        if (!media.posterUrl.isNullOrBlank()) {
            holder.noPoster.visibility = View.GONE
            holder.img.visibility = View.VISIBLE
            com.piner.pinertv.ui.widget.PosterLoader.load(holder.img, media.posterUrl)
        } else {
            holder.img.visibility = View.VISIBLE
            com.piner.pinertv.ui.widget.PosterLoader.load(holder.img, null)
            holder.noPoster.visibility = View.VISIBLE
            holder.noPoster.text = media.title
        }
        val watch = com.piner.pinertv.data.WatchStateStore(holder.itemView.context)
        val watchedBadge = holder.itemView.findViewById<TextView>(R.id.txtWatched)
        val progressBar = holder.itemView.findViewById<android.widget.ProgressBar>(R.id.progressWatch)
        if (watch.isWatched(media.id) || watch.isWatched(media.filePath)) {
            watchedBadge?.visibility = View.VISIBLE
            progressBar?.visibility = View.GONE
        } else {
            watchedBadge?.visibility = View.GONE
            val pct = watch.progressPercent(media.id).takeIf { it > 0 }
                ?: watch.progressPercent(media.filePath)
            if (pct in 1..99) {
                progressBar?.visibility = View.VISIBLE
                progressBar?.progress = pct
            } else {
                progressBar?.visibility = View.GONE
            }
        }
        holder.itemView.setOnClickListener { onClick(media) }
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            val poster = v.findViewById<View>(R.id.framePoster) ?: v
            CardRoundClip.apply(poster)
            CardRoundClip.apply(v.findViewById(R.id.imgPoster))
            val scale = if (hasFocus) 1.08f else 1f
            poster.animate().cancel()
            poster.animate().scaleX(scale).scaleY(scale).setDuration(140).start()
            poster.translationZ = if (hasFocus) 6f else 0f
            if (hasFocus) {
                onFocused(media)
                // Solo scroll vertical al cambiar DE FILA; ancla a ~50% del viewport
                val rv = v.parent as? RecyclerView ?: return@setOnFocusChangeListener
                val lm = rv.layoutManager as? GridLayoutManager ?: return@setOnFocusChangeListener
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnFocusChangeListener
                val row = pos / lm.spanCount
                if (row != lastFocusedRow) {
                    lastFocusedRow = row
                    val rowStart = row * lm.spanCount
                    val rowH = v.height.takeIf { it > 0 } ?: (220 * v.resources.displayMetrics.density).toInt()
                    // Fila 0: pegada bajo géneros (sin hueco). Filas ↓: ancla a ~50% viewport.
                    val offset = if (row == 0) {
                        (8 * v.resources.displayMetrics.density).toInt()
                    } else {
                        ((rv.height / 2) - (rowH / 2)).coerceAtLeast(0)
                    }
                    lm.scrollToPositionWithOffset(rowStart, offset)
                }
            }
        }
        val focused = holder.itemView.isFocused
        val poster = holder.itemView.findViewById<View>(R.id.framePoster)
        CardRoundClip.apply(poster)
        CardRoundClip.apply(holder.itemView.findViewById(R.id.imgPoster))
        val s = if (focused) 1.08f else 1f
        poster?.scaleX = s
        poster?.scaleY = s
        holder.itemView.scaleX = 1f
        holder.itemView.scaleY = 1f
    }

    companion object {
        /** Fila enfocada actual: evita scrollTo en cada ←/→ de la misma fila. */
        var lastFocusedRow: Int = -1
    }

    override fun getItemCount(): Int = items.size
}
