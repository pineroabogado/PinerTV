package com.piner.pinertv.ui.splash

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.piner.pinertv.R
import com.piner.pinertv.catalog.CatalogIndexer
import com.piner.pinertv.data.CatalogCache
import com.piner.pinertv.data.SecurePrefs
import com.piner.pinertv.metadata.TmdbRepository
import com.piner.pinertv.nas.SynologyRepository
import com.piner.pinertv.nas.WakeOnLanService
import com.piner.pinertv.ui.home.MainActivity
import com.piner.pinertv.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SplashActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        val status = findViewById<TextView>(R.id.txtStatus)

        findViewById<TextView>(R.id.txtBrand).setOnLongClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }

        lifecycleScope.launch(Dispatchers.IO) {
            connectAndLoad(status)
        }
    }

    private suspend fun connectAndLoad(status: TextView) {
        val prefs = SecurePrefs(this)
        val wol = WakeOnLanService()
        val repo = SynologyRepository(prefs.nasUrl)

        update(status, getString(R.string.connecting))
        if (!wol.isNasReachable(prefs.nasHost)) {
            update(status, getString(R.string.waking_nas))
            wol.wakeNas(prefs.nasMac, prefs.nasBroadcast)
        }

        val deadline = System.currentTimeMillis() + 5 * 60_000L
        var ok = false
        while (System.currentTimeMillis() < deadline) {
            ok = repo.login(prefs.nasUser, prefs.nasPass)
            if (ok) break
            update(status, getString(R.string.connecting))
            delay(5000)
        }

        if (!ok) {
            update(status, "NAS no operativo. Mantén OK en el logo para Ajustes.")
            return
        }

        update(status, getString(R.string.loading_library))
        var catalog = CatalogIndexer(repo).index()
        // Reintento shares USB
        if (catalog.movies.isEmpty() && catalog.series.isEmpty()) {
            delay(2000)
            catalog = CatalogIndexer(repo).index()
        }

        val tmdb = TmdbRepository()
        // Prioriza sin póster; límite alto para no dejar cards grises en home/biblioteca
        val movies = tmdb.enrichAll(catalog.movies, limit = 200)
        val series = tmdb.enrichAll(catalog.series, limit = 100)

        CatalogCache.movies = movies
        CatalogCache.series = series
        CatalogCache.sid = repo.getSid()
        CatalogCache.baseUrl = repo.getBaseUrl()

        withContext(Dispatchers.Main) {
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            finish()
        }
    }

    private suspend fun update(view: TextView, text: String) {
        withContext(Dispatchers.Main) { view.text = text }
    }
}
