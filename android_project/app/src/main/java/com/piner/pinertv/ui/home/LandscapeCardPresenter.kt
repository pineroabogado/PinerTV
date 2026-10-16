package com.piner.pinertv.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.leanback.widget.Presenter
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.piner.pinertv.R
import com.piner.pinertv.catalog.MediaItem
import com.piner.pinertv.data.CatalogCache
import com.piner.pinertv.data.ContinueWatchingStore
import com.piner.pinertv.data.WatchStateStore
import com.piner.pinertv.metadata.TmdbRepository
import com.piner.pinertv.ui.widget.CardRoundClip
import com.piner.pinertv.ui.widget.PosterLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Thumbs landscape «Seguir viendo» (estilo Emby). */
class LandscapeCardPresenter(
    private val onClick: ((MediaItem) -> Unit)? = null,
    private val onFocused: ((MediaItem) -> Unit)? = null
) : Presenter() {

    private val tmdb by lazy { TmdbRepository() }

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.view_landscape_card, parent, false)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val media = item as MediaItem
        val root = viewHolder.view
        val img = root.findViewById<ImageView>(R.id.imgThumb)
        val title = root.findViewById<TextView>(R.id.txtTitle)
        val progress = root.findViewById<ProgressBar>(R.id.progressWatch)

        (root.getTag(R.id.imgThumb) as? Job)?.cancel()
        root.setTag(R.id.txtTitle, media.id)

        title.text = media.title
        val url = media.backdropUrl?.takeIf { it.isNotBlank() }
            ?: media.posterUrl?.takeIf { it.isNotBlank() }
        if (url != null) {
            PosterLoader.load(img, url)
        } else {
            PosterLoader.load(img, null)
            val owner = root.context as? LifecycleOwner
            if (owner != null) {
                val job = owner.lifecycleScope.launch {
                    val enriched = withContext(Dispatchers.IO) { tmdb.enrich(media) }
                    if (root.getTag(R.id.txtTitle) != media.id) return@launch
                    val u = enriched.backdropUrl?.takeIf { it.isNotBlank() }
                        ?: enriched.posterUrl?.takeIf { it.isNotBlank() }
                        ?: return@launch
                    CatalogCache.upsert(enriched)
                    PosterLoader.load(img, u)
                }
                root.setTag(R.id.imgThumb, job)
            }
        }
        val store = ContinueWatchingStore(root.context)
        val watch = WatchStateStore(root.context)
        val pct = store.progressPercentFor(media)
            .takeIf { it in 1..99 }
            ?: watch.progressPercent(media.id).takeIf { it in 1..99 }
            ?: watch.progressPercent(media.filePath).takeIf { it in 1..99 }
            ?: 0
        if (pct in 1..99) {
            progress.visibility = View.VISIBLE
            progress.progress = pct
        } else {
            progress.visibility = View.GONE
        }
        root.setOnClickListener { onClick?.invoke(media) }
        root.setOnFocusChangeListener { v, hasFocus ->
            applyFocusScale(v, hasFocus)
            if (hasFocus) onFocused?.invoke(media)
        }
        applyFocusScale(root, root.isFocused)
    }

    private fun applyFocusScale(view: View, hasFocus: Boolean) {
        view.clipToOutline = true
        val scale = if (hasFocus) FOCUS_SCALE else 1f
        view.animate().cancel()
        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(140)
            .start()
        view.translationZ = if (hasFocus) 8f else 0f
    }

    companion object {
        private const val FOCUS_SCALE = 1.08f
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val root = viewHolder.view
        (root.getTag(R.id.imgThumb) as? Job)?.cancel()
        root.setTag(R.id.imgThumb, null)
        root.animate().cancel()
        root.scaleX = 1f
        root.scaleY = 1f
        root.translationZ = 0f
        root.findViewById<ImageView>(R.id.imgThumb).setImageDrawable(null)
        root.setOnClickListener(null)
        root.onFocusChangeListener = null
    }
}
