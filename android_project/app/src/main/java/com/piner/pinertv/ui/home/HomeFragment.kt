package com.piner.pinertv.ui.home

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.leanback.app.BackgroundManager
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import coil.ImageLoader
import coil.request.ImageRequest
import com.piner.pinertv.R
import com.piner.pinertv.catalog.HomeCatalogQueries
import com.piner.pinertv.catalog.MediaItem
import com.piner.pinertv.data.CatalogCache
import com.piner.pinertv.data.ContinueWatchingStore
import com.piner.pinertv.ui.details.DetailsActivity
import com.piner.pinertv.ui.settings.SettingsActivity

/**
 * Home estilo Emby Android TV:
 * - Cabecera lateral oscura
 * - Filas horizontales con posters
 * - Fondo dinámico según artwork seleccionado
 */
class HomeFragment : BrowseSupportFragment() {

    private lateinit var backgroundManager: BackgroundManager
    private val handler = Handler(Looper.getMainLooper())
    private var backgroundTask: Runnable? = null
    private var defaultBackground: Drawable? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        title = getString(R.string.app_name)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.emby_bg_deep)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.emby_accent)

        prepareBackgroundManager()

        setOnSearchClickedListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

        loadRows()

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is MediaItem) {
                startActivity(
                    Intent(requireContext(), DetailsActivity::class.java)
                        .putExtra(DetailsActivity.EXTRA_ITEM, item)
                )
            }
        }

        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, _ ->
            if (item is MediaItem) {
                scheduleBackgroundUpdate(item.backdropUrl ?: item.posterUrl)
            }
        }
    }

    private fun prepareBackgroundManager() {
        backgroundManager = BackgroundManager.getInstance(requireActivity())
        if (!backgroundManager.isAttached) {
            backgroundManager.attach(requireActivity().window)
        }
        defaultBackground = ContextCompat.getDrawable(requireContext(), R.drawable.bg_splash_gradient)
        backgroundManager.drawable = defaultBackground
    }

    private fun scheduleBackgroundUpdate(url: String?) {
        backgroundTask?.let { handler.removeCallbacks(it) }
        if (url.isNullOrBlank()) {
            backgroundManager.drawable = defaultBackground
            return
        }
        val task = Runnable {
            val loader = ImageLoader(requireContext())
            val request = ImageRequest.Builder(requireContext())
                .data(url)
                .allowHardware(false)
                .target(
                    onSuccess = { result ->
                        backgroundManager.drawable = result
                    },
                    onError = {
                        backgroundManager.drawable = defaultBackground
                    }
                )
                .build()
            loader.enqueue(request)
        }
        backgroundTask = task
        handler.postDelayed(task, 350)
    }

    private fun loadRows() {
        val rowPresenter = ListRowPresenter().apply {
            shadowEnabled = true
            selectEffectEnabled = true
        }
        val rows = ArrayObjectAdapter(rowPresenter)
        val posterPresenter = CardPresenter()
        val landscapePresenter = LandscapeCardPresenter()
        var rowId = 0L

        val continueItems = ContinueWatchingStore(requireContext()).list()
        if (continueItems.isNotEmpty()) {
            val adapter = ArrayObjectAdapter(landscapePresenter)
            continueItems.forEach { adapter.add(it) }
            rows.add(ListRow(HeaderItem(rowId++, getString(R.string.continue_watching)), adapter))
        }

        val released = HomeCatalogQueries.recentlyReleasedMovies()
        if (released.isNotEmpty()) {
            val recent = ArrayObjectAdapter(posterPresenter)
            released.forEach { recent.add(it) }
            rows.add(ListRow(HeaderItem(rowId++, "Películas recientes"), recent))
        }

        val series = CatalogCache.series
        if (series.isNotEmpty()) {
            val adapter = ArrayObjectAdapter(posterPresenter)
            series.take(40).forEach { adapter.add(it) }
            rows.add(ListRow(HeaderItem(rowId, getString(R.string.series)), adapter))
        }

        adapter = rows
    }

    override fun onDestroy() {
        backgroundTask?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }
}
