package com.piner.pinertv.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.Presenter
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.piner.pinertv.R
import com.piner.pinertv.catalog.MediaItem
import android.widget.ProgressBar
import com.piner.pinertv.data.CatalogCache
import com.piner.pinertv.data.WatchStateStore
import com.piner.pinertv.metadata.TmdbRepository
import com.piner.pinertv.ui.widget.CardRoundClip
import com.piner.pinertv.ui.widget.PosterLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Poster vertical estilo Emby (borde verde al foco + rating). */
class CardPresenter(
    private val onClick: ((MediaItem) -> Unit)? = null,
    private val onFocused: ((MediaItem) -> Unit)? = null
) : Presenter() {

    private val tmdb by lazy { TmdbRepository() }

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.view_media_card, parent, false)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.nextFocusUpId = R.id.btnNavHome
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val media = item as MediaItem
        val root = viewHolder.view
        val img = root.findViewById<ImageView>(R.id.imgPoster)
        val title = root.findViewById<TextView>(R.id.txtTitle)
        val meta = root.findViewById<TextView>(R.id.txtMeta)
        val rating = root.findViewById<TextView>(R.id.txtRating)
        val noPoster = root.findViewById<TextView>(R.id.txtNoPoster)
        val progress = root.findViewById<ProgressBar>(R.id.progressWatch)
        val watched = root.findViewById<TextView>(R.id.txtWatched)

        cancelEnrich(root)
        root.setTag(R.id.imgPoster, media.id)

        val watch = WatchStateStore(root.context)
        if (watch.isWatched(media.id) || watch.isWatched(media.filePath)) {
            watched?.visibility = View.VISIBLE
            progress?.visibility = View.GONE
        } else {
            watched?.visibility = View.GONE
            val pct = watch.progressPercent(media.id).takeIf { it > 0 }
                ?: watch.progressPercent(media.filePath)
            if (pct in 1..99) {
                progress?.visibility = View.VISIBLE
                progress?.progress = pct
            } else {
                progress?.visibility = View.GONE
            }
        }

        title.text = media.title
        meta.text = buildString {
            if (!media.year.isNullOrBlank()) append(media.year)
            if (media.voteAverage > 0f) {
                if (isNotEmpty()) append(" • ")
                append(String.format(Locale.getDefault(), "%.1f", media.voteAverage))
            }
        }

        if (media.voteAverage > 0f) {
            rating.visibility = View.VISIBLE
            rating.text = String.format(Locale.getDefault(), "%.1f", media.voteAverage)
        } else {
            rating.visibility = View.GONE
        }

        val url = media.posterUrl?.takeIf { it.isNotBlank() }
        if (url != null) {
            noPoster.visibility = View.GONE
            img.visibility = View.VISIBLE
            PosterLoader.load(img, url)
            // Match débil (p.ej. Maverick ambiguo): rebusca con scoring Synology
            if (tmdb.needsReEnrich(media)) {
                scheduleEnrich(root, img, noPoster, media)
            }
        } else {
            img.visibility = View.VISIBLE
            PosterLoader.load(img, null)
            noPoster.visibility = View.VISIBLE
            noPoster.text = media.title
            scheduleEnrich(root, img, noPoster, media)
        }

        root.setOnClickListener { onClick?.invoke(media) }
        root.setOnFocusChangeListener { v, hasFocus ->
            applyFocusScale(v, hasFocus)
            if (hasFocus) onFocused?.invoke(media)
        }
        // Restaurar escala si el ViewHolder se reusa ya enfocado
        applyFocusScale(root, root.isFocused)
    }

    private fun applyFocusScale(view: View, hasFocus: Boolean) {
        // Escalar solo el marco del póster: el root no cambia de layout → sin jitter ←/→
        val poster = view.findViewById<View>(R.id.framePoster) ?: view
        CardRoundClip.apply(poster)
        CardRoundClip.apply(view.findViewById(R.id.imgPoster))
        val scale = if (hasFocus) FOCUS_SCALE else 1f
        poster.animate().cancel()
        poster.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(140)
            .start()
        poster.translationZ = if (hasFocus) 8f else 0f
        view.translationZ = if (hasFocus) 4f else 0f
    }

    companion object {
        private const val FOCUS_SCALE = 1.08f
    }

    private fun scheduleEnrich(
        root: View,
        img: ImageView,
        noPoster: TextView,
        media: MediaItem
    ) {
        val owner = root.context as? LifecycleOwner ?: return
        val job = owner.lifecycleScope.launch {
            val enriched = withContext(Dispatchers.IO) { tmdb.enrich(media) }
            if (root.getTag(R.id.imgPoster) != media.id) return@launch
            val poster = enriched.posterUrl?.takeIf { it.isNotBlank() } ?: return@launch
            CatalogCache.upsert(enriched)
            noPoster.visibility = View.GONE
            PosterLoader.load(img, poster)
        }
        root.setTag(R.id.txtNoPoster, job)
    }

    private fun cancelEnrich(root: View) {
        (root.getTag(R.id.txtNoPoster) as? Job)?.cancel()
        root.setTag(R.id.txtNoPoster, null)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        cancelEnrich(viewHolder.view)
        val root = viewHolder.view
        val poster = root.findViewById<View>(R.id.framePoster)
        poster?.animate()?.cancel()
        poster?.scaleX = 1f
        poster?.scaleY = 1f
        poster?.translationZ = 0f
        root.animate().cancel()
        root.scaleX = 1f
        root.scaleY = 1f
        root.translationZ = 0f
        root.findViewById<ImageView>(R.id.imgPoster).setImageDrawable(null)
        root.setOnClickListener(null)
        root.onFocusChangeListener = null
    }
}
