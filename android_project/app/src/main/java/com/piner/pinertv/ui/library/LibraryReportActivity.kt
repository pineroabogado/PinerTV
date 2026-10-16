package com.piner.pinertv.ui.library

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.piner.pinertv.R
import com.piner.pinertv.catalog.LibraryStatsCollector
import com.piner.pinertv.data.CatalogCache
import com.piner.pinertv.data.SecurePrefs
import com.piner.pinertv.nas.SynologyRepository
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Informe de biblioteca: solo conteos (películas / series / episodios) y tamaño si hay datos.
 * Sin póster ni metadatos TMDB.
 */
class LibraryReportActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library_report)

        val txtMovies = findViewById<TextView>(R.id.txtReportMovies)
        val txtSeries = findViewById<TextView>(R.id.txtReportSeries)
        val txtEpisodes = findViewById<TextView>(R.id.txtReportEpisodes)
        val txtSize = findViewById<TextView>(R.id.txtReportSize)
        val txtStatus = findViewById<TextView>(R.id.txtReportStatus)

        txtMovies.text = CatalogCache.movies.size.toString()
        txtSeries.text = CatalogCache.series.size.toString()
        txtEpisodes.text = "…"
        txtSize.text = "…"
        txtStatus.visibility = View.VISIBLE
        txtStatus.text = getString(R.string.library_report_scanning)

        val prefs = SecurePrefs(this)
        val baseUrl = CatalogCache.baseUrl ?: prefs.nasUrl
        val repo = SynologyRepository(baseUrl)
        CatalogCache.sid?.let { repo.setSid(it) }

        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                if (repo.getSid() == null) {
                    repo.login(prefs.nasUser, prefs.nasPass)
                    CatalogCache.sid = repo.getSid()
                }
                LibraryStatsCollector(repo).collect()
            }
            txtMovies.text = report.movies.toString()
            txtSeries.text = report.series.toString()
            txtEpisodes.text = report.episodes.toString()
            txtSize.text = if (report.sizeKnown) {
                formatSize(report.totalSizeBytes)
            } else {
                getString(R.string.library_report_size_unknown)
            }
            txtStatus.visibility = View.GONE
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return getString(R.string.library_report_size_unknown)
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) {
            String.format(Locale.getDefault(), "%.1f GB", gb)
        } else {
            val mb = bytes / (1024.0 * 1024.0)
            String.format(Locale.getDefault(), "%.0f MB", mb)
        }
    }
}
