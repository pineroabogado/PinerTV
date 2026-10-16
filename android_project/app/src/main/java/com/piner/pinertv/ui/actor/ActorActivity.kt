package com.piner.pinertv.ui.actor

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.piner.pinertv.R
import com.piner.pinertv.catalog.MediaItem
import com.piner.pinertv.data.CatalogCache
import com.piner.pinertv.data.PersonIndex
import com.piner.pinertv.metadata.TmdbRepository
import com.piner.pinertv.ui.details.DetailsActivity
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Ficha de actor: filmografía local inmediata; foto/bio TMDB en paralelo. */
class ActorActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_actor)

        val personId = intent.getIntExtra(EXTRA_PERSON_ID, -1)
        val fallbackName = intent.getStringExtra(EXTRA_PERSON_NAME).orEmpty()
        val fallbackPhoto = intent.getStringExtra(EXTRA_PERSON_PHOTO)
        val seedMediaId = intent.getStringExtra(EXTRA_SEED_MEDIA_ID)

        findViewById<TextView>(R.id.txtActorName).text = fallbackName.ifBlank { getString(R.string.actor) }
        findViewById<ImageView>(R.id.imgActorPhoto).load(fallbackPhoto) {
            placeholder(R.drawable.poster_placeholder)
            error(R.drawable.poster_placeholder)
        }

        if (personId <= 0) {
            findViewById<TextView>(R.id.txtActorStatus).text = getString(R.string.no_local_titles)
            return
        }

        // Semilla: título desde el que se abrió la ficha
        if (!seedMediaId.isNullOrBlank()) {
            PersonIndex.linkPerson(personId, seedMediaId)
        }

        val status = findViewById<TextView>(R.id.txtActorStatus)
        val grid = findViewById<RecyclerView>(R.id.gridActor)
        grid.layoutManager = GridLayoutManager(this, 5)
        val adapter = ActorGridAdapter { openDetails(it) }
        grid.adapter = adapter

        // Instantáneo: índice local en memoria (sin red ni cruce pesado en UI)
        val instant = PersonIndex.localTitlesFor(personId)
        if (instant.isNotEmpty()) {
            status.text = "${instant.size} títulos"
            adapter.submit(instant)
            grid.post { grid.getChildAt(0)?.requestFocus() }
        } else {
            status.text = getString(R.string.loading_cast)
        }

        PersonIndex.cachedProfile(personId)?.let { applyProfileUi(it.toPersonProfile(), fallbackName) }

        lifecycleScope.launch {
            val repo = TmdbRepository()
            val local = CatalogCache.movies + CatalogCache.series
            val hadCache = PersonIndex.cachedCredits(personId) != null

            // Bio/foto y filmografía en paralelo; la lista no espera al perfil
            val profileJob = async(Dispatchers.IO) { repo.loadPersonProfile(personId) }
            val filmJob = async(Dispatchers.IO) {
                val credits = if (hadCache) {
                    PersonIndex.cachedCredits(personId).orEmpty()
                } else {
                    repo.loadPersonCreditsCached(personId)
                }
                repo.matchLocalFilmography(credits, local).also {
                    PersonIndex.indexMatches(personId, it)
                }
            }

            launch {
                val profile = profileJob.await()
                if (profile != null) applyProfileUi(profile, fallbackName)
            }

            val matches = filmJob.await()
            if (matches.isEmpty() && adapter.itemCount == 0) {
                status.text = getString(R.string.no_local_titles)
                adapter.submit(emptyList())
            } else if (matches.isNotEmpty()) {
                status.text = "${matches.size} títulos"
                adapter.submit(matches)
                grid.post { grid.getChildAt(0)?.requestFocus() }
            }
        }
    }

    private fun applyProfileUi(p: TmdbRepository.PersonProfile, fallbackName: String) {
        findViewById<TextView>(R.id.txtActorName).text = p.name.ifBlank { fallbackName }
        findViewById<TextView>(R.id.txtActorMeta).text = p.knownFor
        findViewById<TextView>(R.id.txtActorBio).text = p.biography
        if (!p.profileUrl.isNullOrBlank()) {
            findViewById<ImageView>(R.id.imgActorPhoto).load(p.profileUrl) {
                placeholder(R.drawable.poster_placeholder)
                error(R.drawable.poster_placeholder)
            }
        }
    }

    private fun PersonIndex.CachedProfile.toPersonProfile() =
        TmdbRepository.PersonProfile(id, name, biography, profileUrl, knownFor)

    private fun openDetails(item: MediaItem) {
        startActivity(
            Intent(this, DetailsActivity::class.java)
                .putExtra(DetailsActivity.EXTRA_ITEM, item)
        )
    }

    companion object {
        const val EXTRA_PERSON_ID = "extra_person_id"
        const val EXTRA_PERSON_NAME = "extra_person_name"
        const val EXTRA_PERSON_PHOTO = "extra_person_photo"
        const val EXTRA_SEED_MEDIA_ID = "extra_seed_media_id"
    }
}

private class ActorGridAdapter(
    private val onClick: (MediaItem) -> Unit
) : RecyclerView.Adapter<ActorGridAdapter.Holder>() {

    private var items: List<MediaItem> = emptyList()

    fun submit(list: List<MediaItem>) {
        items = list
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
            com.piner.pinertv.ui.widget.PosterLoader.load(holder.img, media.posterUrl)
        } else {
            com.piner.pinertv.ui.widget.PosterLoader.load(holder.img, null)
            holder.noPoster.visibility = View.VISIBLE
            holder.noPoster.text = media.title
        }
        holder.itemView.setOnClickListener { onClick(media) }
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.08f else 1f
            v.animate().cancel()
            v.animate().scaleX(scale).scaleY(scale).setDuration(140).start()
            v.translationZ = if (hasFocus) 8f else 0f
        }
    }

    override fun getItemCount(): Int = items.size
}
