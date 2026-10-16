package com.piner.pinertv.ui.home

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.ItemBridgeAdapter
import coil.load
import com.piner.pinertv.R
import com.piner.pinertv.catalog.HomeCatalogQueries
import com.piner.pinertv.catalog.MediaItem
import com.piner.pinertv.catalog.MediaType
import android.widget.LinearLayout
import com.piner.pinertv.data.CatalogCache
import com.piner.pinertv.data.ContinueWatchingStore
import com.piner.pinertv.data.HomeLayoutPrefs
import com.piner.pinertv.ui.details.DetailsActivity
import com.piner.pinertv.ui.library.LibraryActivity
import com.piner.pinertv.ui.settings.SettingsActivity
import com.piner.pinertv.ui.widget.FocusStableNestedScrollView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Inicio Emby Fire TV:
 * top bar + hero + filas (Seguir viendo → recientes → series), cards enteras sin recorte.
 */
class MainActivity : FragmentActivity() {

    private lateinit var imgHero: ImageView
    private lateinit var txtTitle: TextView
    private lateinit var txtMeta: TextView
    private lateinit var txtOverview: TextView
    private lateinit var txtClock: TextView
    private lateinit var scrollRows: FocusStableNestedScrollView
    private lateinit var gridContinue: HorizontalGridView
    private lateinit var gridMovies: HorizontalGridView
    private lateinit var gridSeries: HorizontalGridView

    /** Fila activa: solo al cambiar de fila se reajusta visibilidad/scroll (no en ←/→). */
    private var activeRowGridId: Int = View.NO_ID

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            if (::txtClock.isInitialized) {
                txtClock.text = SimpleDateFormat("H:mm", Locale.getDefault()).format(Date())
            }
            clockHandler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imgHero = findViewById(R.id.imgHeroBackdrop)
        txtTitle = findViewById(R.id.txtHeroTitle)
        txtMeta = findViewById(R.id.txtHeroMeta)
        txtOverview = findViewById(R.id.txtHeroOverview)
        txtClock = findViewById(R.id.txtClock)
        scrollRows = findViewById(R.id.scrollRows)
        gridContinue = findViewById(R.id.gridContinue)
        gridMovies = findViewById(R.id.gridMovies)
        gridSeries = findViewById(R.id.gridSeries)

        seedContinueWatchingIfEmpty()
        setupNav()
        setupRows()
        clockHandler.post(clockTick)

        val first = if (ContinueWatchingStore(this).list().isNotEmpty()) gridContinue else gridMovies
        first.post { first.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        if (::gridContinue.isInitialized) {
            setupRows()
        }
    }

    private fun seedContinueWatchingIfEmpty() {
        val store = ContinueWatchingStore(this)
        if (store.list().isNotEmpty()) return
        CatalogCache.movies.take(3).forEach { item ->
            val mins = (item.runtime ?: 100).coerceAtLeast(40)
            val dur = mins * 60_000L
            store.save(item, (dur * 0.38).toLong(), dur)
        }
    }

    private fun setupNav() {
        val btnHome = findViewById<Button>(R.id.btnNavHome)
        val btnMovies = findViewById<Button>(R.id.btnNavMovies)
        val btnSeries = findViewById<Button>(R.id.btnNavSeries)
        val btnSearch = findViewById<ImageButton>(R.id.btnSearch)
        val btnSettings = findViewById<ImageButton>(R.id.btnSettings)
        val accent = ContextCompat.getColor(this, R.color.emby_accent)
        val muted = ContextCompat.getColor(this, R.color.emby_text_secondary)

        btnHome.setOnClickListener {
            switchToRow(R.id.gridContinue)
            gridContinue.requestFocus()
        }
        btnMovies.setOnClickListener {
            startActivity(
                Intent(this, LibraryActivity::class.java)
                    .putExtra(LibraryActivity.EXTRA_TYPE, LibraryActivity.TYPE_MOVIES)
            )
        }
        btnSeries.setOnClickListener {
            startActivity(
                Intent(this, LibraryActivity::class.java)
                    .putExtra(LibraryActivity.EXTRA_TYPE, LibraryActivity.TYPE_SERIES)
            )
        }
        btnSearch.setOnClickListener {
            Toast.makeText(this, "Búsqueda próximamente", Toast.LENGTH_SHORT).show()
        }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        fun paintTabs() {
            btnHome.setTextColor(accent)
            btnMovies.setTextColor(if (btnMovies.isFocused) accent else muted)
            btnSeries.setTextColor(if (btnSeries.isFocused) accent else muted)
        }
        listOf(btnHome, btnMovies, btnSeries).forEach { it.setOnFocusChangeListener { _, _ -> paintTabs() } }
        paintTabs()
    }

    private fun setupRows() {
        val home = HomeLayoutPrefs(this)
        applyHomeRowOrder(home)

        val store = ContinueWatchingStore(this)
        val continueItems = store.list().map { enrichFromCache(it) }
        val cwIds = continueItems.map { it.id }.toSet()
        // Emby latestmoviereleases: estreno reciente (año), no catálogo A–Z completo
        val movies = HomeCatalogQueries.recentlyReleasedMovies(cwIds)
        val series = CatalogCache.series

        val showCw = home.isVisible(HomeLayoutPrefs.RowId.CONTINUE) && continueItems.isNotEmpty()
        val showMovies = home.isVisible(HomeLayoutPrefs.RowId.MOVIES) && movies.isNotEmpty()
        val showSeries = home.isVisible(HomeLayoutPrefs.RowId.SERIES) && series.isNotEmpty()

        val lblContinue = findViewById<TextView>(R.id.lblContinue)
        if (!showCw) {
            lblContinue.visibility = View.GONE
            gridContinue.visibility = View.GONE
            gridMovies.nextFocusUpId = R.id.btnNavHome
        } else {
            bindGrid(gridContinue, continueItems, landscape = true)
            ensureScrollOnFocus(gridContinue, lblContinue)
        }

        findViewById<TextView>(R.id.lblMovies).visibility =
            if (showMovies) View.VISIBLE else View.GONE
        if (!showMovies) {
            gridMovies.visibility = View.GONE
        } else {
            bindGrid(gridMovies, movies, landscape = false)
            ensureScrollOnFocus(gridMovies, findViewById(R.id.lblMovies))
        }

        findViewById<TextView>(R.id.lblSeries).visibility =
            if (showSeries) View.VISIBLE else View.GONE
        if (!showSeries) {
            gridSeries.visibility = View.GONE
        } else {
            bindGrid(gridSeries, series, landscape = false)
            ensureScrollOnFocus(gridSeries, findViewById(R.id.lblSeries))
        }

        when {
            showCw -> {
                activeRowGridId = R.id.gridContinue
                applyRowVisibility(R.id.gridContinue)
                showHero(continueItems.first())
            }
            showMovies -> {
                activeRowGridId = R.id.gridMovies
                applyRowVisibility(R.id.gridMovies)
                showHero(movies.first())
            }
            showSeries -> {
                activeRowGridId = R.id.gridSeries
                applyRowVisibility(R.id.gridSeries)
                showHero(series.first())
            }
        }
    }

    /** Reordena lbl+grid según preferencias de Inicio. */
    private fun applyHomeRowOrder(home: HomeLayoutPrefs) {
        val parent = findViewById<LinearLayout>(R.id.layoutRows)
        val pairs: Map<HomeLayoutPrefs.RowId, List<View>> = mapOf(
            HomeLayoutPrefs.RowId.CONTINUE to listOf(
                findViewById(R.id.lblContinue),
                findViewById(R.id.gridContinue)
            ),
            HomeLayoutPrefs.RowId.MOVIES to listOf(
                findViewById(R.id.lblMovies),
                findViewById(R.id.gridMovies)
            ),
            HomeLayoutPrefs.RowId.SERIES to listOf(
                findViewById(R.id.lblSeries),
                findViewById(R.id.gridSeries)
            )
        )
        home.order().forEach { row ->
            pairs[row]?.forEach { v ->
                parent.removeView(v)
                parent.addView(v)
            }
        }
    }

    private fun ensureScrollOnFocus(grid: HorizontalGridView, @Suppress("UNUSED_PARAMETER") anchor: View) {
        // Solo al cambiar ENTRE filas (↑/↓). ←/→ dentro del mismo HorizontalGridView no toca layout.
        grid.viewTreeObserver.addOnGlobalFocusChangeListener { oldFocus, newFocus ->
            if (newFocus == null || !isDescendant(newFocus, grid)) return@addOnGlobalFocusChangeListener
            val cameFromSameRow = oldFocus != null && isDescendant(oldFocus, grid)
            if (cameFromSameRow) return@addOnGlobalFocusChangeListener
            if (activeRowGridId == grid.id) return@addOnGlobalFocusChangeListener
            switchToRow(grid.id)
        }
    }

    /** Cambio de fila (↑/↓): reajusta layout. Navegación horizontal no llama aquí. */
    private fun switchToRow(focusedGridId: Int) {
        if (activeRowGridId == focusedGridId) return
        activeRowGridId = focusedGridId
        applyRowVisibility(focusedGridId)
        anchorFocusedRowAtHalf()
    }

    /**
     * Solo la fila enfocada (+ peek inferior) queda visible.
     * Tras el layout, [anchorFocusedRowAtHalf] fija la fila activa a media altura del viewport.
     */
    private fun applyRowVisibility(focusedGridId: Int) {
        val home = HomeLayoutPrefs(this)
        val hasCw = home.isVisible(HomeLayoutPrefs.RowId.CONTINUE) &&
            ContinueWatchingStore(this).list().isNotEmpty()
        val hasMovies = home.isVisible(HomeLayoutPrefs.RowId.MOVIES) &&
            HomeCatalogQueries.recentlyReleasedMovies().isNotEmpty()
        val hasSeries = home.isVisible(HomeLayoutPrefs.RowId.SERIES) &&
            CatalogCache.series.isNotEmpty()
        when (focusedGridId) {
            R.id.gridContinue -> {
                txtOverview.visibility = View.VISIBLE
                findViewById<TextView>(R.id.lblContinue).visibility = if (hasCw) View.VISIBLE else View.GONE
                gridContinue.visibility = if (hasCw) View.VISIBLE else View.GONE
                findViewById<TextView>(R.id.lblMovies).visibility = if (hasMovies) View.VISIBLE else View.GONE
                gridMovies.visibility = if (hasMovies) View.VISIBLE else View.GONE
                findViewById<TextView>(R.id.lblSeries).visibility = View.GONE
                gridSeries.visibility = View.GONE
            }
            R.id.gridMovies -> {
                txtOverview.visibility = View.VISIBLE
                findViewById<TextView>(R.id.lblContinue).visibility = View.GONE
                gridContinue.visibility = View.GONE
                findViewById<TextView>(R.id.lblMovies).visibility = if (hasMovies) View.VISIBLE else View.GONE
                gridMovies.visibility = if (hasMovies) View.VISIBLE else View.GONE
                findViewById<TextView>(R.id.lblSeries).visibility = if (hasSeries) View.VISIBLE else View.GONE
                gridSeries.visibility = if (hasSeries) View.VISIBLE else View.GONE
            }
            R.id.gridSeries -> {
                txtOverview.visibility = View.VISIBLE
                findViewById<TextView>(R.id.lblContinue).visibility = View.GONE
                gridContinue.visibility = View.GONE
                findViewById<TextView>(R.id.lblMovies).visibility = View.GONE
                gridMovies.visibility = View.GONE
                findViewById<TextView>(R.id.lblSeries).visibility = if (hasSeries) View.VISIBLE else View.GONE
                gridSeries.visibility = if (hasSeries) View.VISIBLE else View.GONE
            }
        }
        anchorFocusedRowAtHalf()
    }

    /**
     * Ancla la fila enfocada (label + grid) al 50% del área visible de cards.
     * Padding fijo → ←/→ no re-layout ni scroll vertical.
     */
    private fun anchorFocusedRowAtHalf() {
        val rows = findViewById<LinearLayout>(R.id.layoutRows)
        scrollRows.post {
            val viewportH = scrollRows.height
            if (viewportH <= 0) return@post
            val (lbl, grid) = when (activeRowGridId) {
                R.id.gridContinue -> findViewById<View>(R.id.lblContinue) to gridContinue
                R.id.gridMovies -> findViewById<View>(R.id.lblMovies) to gridMovies
                R.id.gridSeries -> findViewById<View>(R.id.lblSeries) to gridSeries
                else -> return@post
            }
            if (grid.visibility != View.VISIBLE) return@post
            // Medir bloque enfocado: label + márgenes + grid
            val lblH = if (lbl.visibility == View.VISIBLE) {
                lbl.height.takeIf { it > 0 } ?: lbl.measuredHeight.takeIf { it > 0 }
                    ?: dp(18)
            } else 0
            val lblTop = if (lbl.visibility == View.VISIBLE) {
                (lbl.layoutParams as? LinearLayout.LayoutParams)?.topMargin ?: 0
            } else 0
            val gridH = grid.height.takeIf { it > 0 } ?: grid.layoutParams.height.takeIf { it > 0 } ?: dp(260)
            val blockH = lblTop + lblH + gridH
            // Centro del bloque = mitad del viewport de cards
            val topPad = ((viewportH / 2) - (blockH / 2)).coerceAtLeast(0)
            if (rows.paddingTop != topPad) {
                rows.setPadding(rows.paddingLeft, topPad, rows.paddingRight, rows.paddingBottom)
            }
            // Scroll siempre a 0: el anclaje es por padding, no por scroll animado
            if (scrollRows.scrollY != 0 || scrollRows.scrollX != 0) {
                scrollRows.suppressFocusScroll = false
                scrollRows.scrollTo(0, 0)
                scrollRows.suppressFocusScroll = true
            }
        }
    }

    private fun bindGrid(grid: HorizontalGridView, items: List<MediaItem>, landscape: Boolean) {
        val presenter = if (landscape) {
            LandscapeCardPresenter(
                onClick = { openDetails(it) },
                onFocused = { showHero(it) }
            )
        } else {
            CardPresenter(
                onClick = { openDetails(it) },
                onFocused = { showHero(it) }
            )
        }
        val adapter = ArrayObjectAdapter(presenter)
        items.forEach { adapter.add(it) }
        grid.setItemSpacing(12)
        // Margen izquierdo cómodo TV (primera card no pegada al borde)
        val padStart = dp(56)
        val padEnd = dp(40)
        grid.setPadding(padStart, grid.paddingTop, padEnd, grid.paddingBottom)
        grid.clipToPadding = false
        // ←/→: alinear al borde bajo sin recentrar (evita saltos / re-layout vertical)
        grid.windowAlignment = HorizontalGridView.WINDOW_ALIGN_LOW_EDGE
        grid.windowAlignmentOffsetPercent = 0f
        grid.itemAlignmentOffsetPercent = 0f
        grid.setHasFixedSize(true)
        grid.adapter = ItemBridgeAdapter(adapter)
    }

    private fun enrichFromCache(item: MediaItem): MediaItem {
        return (CatalogCache.movies + CatalogCache.series).find { it.id == item.id } ?: item
    }

    private fun showHero(item: MediaItem) {
        val full = enrichFromCache(item)
        txtTitle.text = full.title
        // Una sola línea fija → el hero no cambia de altura al navegar ←/→
        txtTitle.maxLines = 1
        txtMeta.maxLines = 1
        txtOverview.maxLines = 1
        txtMeta.text = buildString {
            if (full.voteAverage > 0f) {
                append("★ ")
                append(String.format(Locale.getDefault(), "%.1f", full.voteAverage))
            }
            if (!full.year.isNullOrBlank()) {
                if (isNotEmpty()) append("      ")
                append(full.year)
            }
            val mins = full.runtime
            if (mins != null && mins > 0) {
                if (isNotEmpty()) append("      ")
                val h = mins / 60
                val m = mins % 60
                append(if (h > 0) "${h}h ${m}m" else "${m}m")
                append("      (Termina ")
                val cal = Calendar.getInstance()
                cal.add(Calendar.MINUTE, mins)
                append(SimpleDateFormat("H:mm", Locale.getDefault()).format(cal.time))
                append(")")
            } else {
                if (isNotEmpty()) append("      ")
                append(if (full.mediaType == MediaType.MOVIE) "Película" else "Serie")
            }
        }
        txtOverview.text = full.overview.ifBlank { " " }
        imgHero.load(full.backdropUrl ?: full.posterUrl) {
            crossfade(200)
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val focused = currentFocus ?: return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> when {
                isDescendant(focused, gridContinue) -> {
                    findViewById<Button>(R.id.btnNavHome).requestFocus()
                    return true
                }
                isDescendant(focused, gridMovies) -> {
                    if (ContinueWatchingStore(this).list().isNotEmpty()) {
                        switchToRow(R.id.gridContinue)
                        gridContinue.requestFocus()
                    } else {
                        findViewById<Button>(R.id.btnNavHome).requestFocus()
                    }
                    return true
                }
                isDescendant(focused, gridSeries) -> {
                    switchToRow(R.id.gridMovies)
                    gridMovies.requestFocus()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> when {
                isDescendant(focused, gridContinue) &&
                    HomeCatalogQueries.recentlyReleasedMovies().isNotEmpty() -> {
                    switchToRow(R.id.gridMovies)
                    gridMovies.requestFocus()
                    return true
                }
                isDescendant(focused, gridMovies) && CatalogCache.series.isNotEmpty() -> {
                    switchToRow(R.id.gridSeries)
                    gridSeries.requestFocus()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isDescendant(child: View, ancestor: View): Boolean {
        var v: View? = child
        while (v != null) {
            if (v === ancestor) return true
            v = v.parent as? View
        }
        return false
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        clockHandler.removeCallbacks(clockTick)
        super.onDestroy()
    }
}
