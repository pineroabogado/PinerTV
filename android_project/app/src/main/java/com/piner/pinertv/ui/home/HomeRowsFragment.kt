package com.piner.pinertv.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import com.piner.pinertv.R
import com.piner.pinertv.catalog.HomeCatalogQueries
import com.piner.pinertv.catalog.MediaItem
import com.piner.pinertv.data.CatalogCache
import com.piner.pinertv.data.ContinueWatchingStore
import com.piner.pinertv.ui.details.DetailsActivity

/**
 * Filas estilo Emby Fire TV:
 * 1) Seguir viendo (landscape)
 * 2) Películas estrenadas recientemente
 * 3) Series
 *
 * RowsSupportFragment hace scroll vertical: las cards no quedan bajo el hero.
 */
class HomeRowsFragment : RowsSupportFragment() {

    var onItemFocused: ((MediaItem) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rowPresenter = ListRowPresenter(FocusHighlight.ZOOM_FACTOR_NONE, false).apply {
            shadowEnabled = false
            selectEffectEnabled = false
        }
        val rows = ArrayObjectAdapter(rowPresenter)

        var rowId = 0L
        val continueItems = ContinueWatchingStore(requireContext()).list()
        if (continueItems.isNotEmpty()) {
            val adapter = ArrayObjectAdapter(LandscapeCardPresenter(
                onClick = { openDetails(it) },
                onFocused = { onItemFocused?.invoke(it) }
            ))
            continueItems.forEach { adapter.add(it) }
            rows.add(ListRow(HeaderItem(rowId++, getString(R.string.continue_watching)), adapter))
        }

        val movies = CatalogCache.movies
        val series = CatalogCache.series
        val cwIds = continueItems.map { it.id }.toSet()

        // Novedades Synology: mtime más reciente en carpeta (películas + series)
        val novedades = (movies + series)
            .filter { it.mtimeSec > 0L && it.id !in cwIds }
            .sortedByDescending { it.mtimeSec }
            .take(24)
        if (novedades.isNotEmpty()) {
            val adapter = ArrayObjectAdapter(CardPresenter(
                onClick = { openDetails(it) },
                onFocused = { onItemFocused?.invoke(it) }
            ))
            novedades.forEach { adapter.add(it) }
            rows.add(ListRow(HeaderItem(rowId++, getString(R.string.recent_additions)), adapter))
        }

        val released = HomeCatalogQueries.recentlyReleasedMovies(cwIds)
        if (released.isNotEmpty()) {
            val recent = ArrayObjectAdapter(CardPresenter(
                onClick = { openDetails(it) },
                onFocused = { onItemFocused?.invoke(it) }
            ))
            released.forEach { recent.add(it) }
            rows.add(
                ListRow(
                    HeaderItem(rowId++, "Películas estrenadas recientemente"),
                    recent
                )
            )
        }

        if (series.isNotEmpty()) {
            val adapter = ArrayObjectAdapter(CardPresenter(
                onClick = { openDetails(it) },
                onFocused = { onItemFocused?.invoke(it) }
            ))
            series.forEach { adapter.add(it) }
            rows.add(ListRow(HeaderItem(rowId, getString(R.string.series)), adapter))
        }

        adapter = rows

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is MediaItem) openDetails(item)
        }
        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, _ ->
            if (item is MediaItem) onItemFocused?.invoke(item)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Evitar que Leanback recorte el borde superior/inferior de las cards
        verticalGridView?.apply {
            clipChildren = false
            clipToPadding = false
            setPadding(paddingLeft, dp(8), paddingRight, dp(28))
            windowAlignment = BaseGridView.WINDOW_ALIGN_LOW_EDGE
            windowAlignmentOffsetPercent = 0f
            itemAlignmentOffsetPercent = 0f
        }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun openDetails(item: MediaItem) {
        startActivity(
            Intent(requireContext(), DetailsActivity::class.java)
                .putExtra(DetailsActivity.EXTRA_ITEM, item)
        )
    }

    fun requestFirstFocus() {
        view?.post {
            setSelectedPosition(0, true)
        }
    }
}
