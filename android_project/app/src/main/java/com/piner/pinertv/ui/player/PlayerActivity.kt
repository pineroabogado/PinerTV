package com.piner.pinertv.ui.player

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.piner.pinertv.R
import com.piner.pinertv.catalog.EpisodeEntry
import com.piner.pinertv.catalog.MediaItem
import com.piner.pinertv.catalog.MediaType
import com.piner.pinertv.catalog.SeriesStructureLoader
import com.piner.pinertv.data.CatalogCache
import com.piner.pinertv.data.ContinueWatchingStore
import com.piner.pinertv.data.SecurePrefs
import com.piner.pinertv.data.SubtitleStylePrefs
import com.piner.pinertv.nas.SynologyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerActivity : FragmentActivity() {

    private var player: ExoPlayer? = null
    private var media: MediaItem? = null
    private var playerView: PlayerView? = null
    private var titleView: TextView? = null
    private var skipIntroBtn: TextView? = null
    private var chaptersBtn: TextView? = null
    private var panelUpNext: View? = null
    private var txtUpNextTitle: TextView? = null
    private var txtUpNextCountdown: TextView? = null
    private var nextEpisode: MediaItem? = null
    private var upNextSeconds = 0
    /** True mientras la cuenta atrás puede auto-reproducir el siguiente episodio. */
    private var upNextCountdownActive = false
    private var chapters: List<ChapterMark> = emptyList()
    private var introSkipped = false
    private var lastDownloadUrl: String? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val uiHandler = Handler(Looper.getMainLooper())
    private val skipPoll = object : Runnable {
        override fun run() {
            refreshSkipIntroVisibility()
            uiHandler.postDelayed(this, 1000L)
        }
    }
    private val upNextTick = object : Runnable {
        override fun run() {
            if (!upNextCountdownActive) return
            if (upNextSeconds <= 0) {
                upNextCountdownActive = false
                playNextEpisode()
                return
            }
            txtUpNextCountdown?.text = getString(R.string.up_next_in, upNextSeconds)
            upNextSeconds--
            uiHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        @Suppress("DEPRECATION")
        media = intent.getSerializableExtra(EXTRA_ITEM) as? MediaItem
        val startPos = intent.getLongExtra(EXTRA_POSITION, 0L)
        if (media == null) {
            finish()
            return
        }

        playerView = findViewById(R.id.playerView)
        titleView = playerView?.findViewById(R.id.txtOsdTitle)
        skipIntroBtn = playerView?.findViewById(R.id.btnSkipIntro)
        chaptersBtn = playerView?.findViewById(R.id.btnOsdChapters)
        panelUpNext = findViewById(R.id.panelUpNext)
        txtUpNextTitle = findViewById(R.id.txtUpNextTitle)
        txtUpNextCountdown = findViewById(R.id.txtUpNextCountdown)
        titleView?.text = media!!.title
        bindTrackButtons()
        bindChapterButtons()
        bindUpNextButtons()
        applySubtitleStyle()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (panelUpNext?.visibility == View.VISIBLE) {
                        dismissUpNext(finishActivity = true)
                        return
                    }
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        )

        playerView?.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (visibility == View.VISIBLE) {
                    refreshSkipIntroVisibility()
                    playerView?.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)
                        ?.requestFocus()
                }
            }
        )
        playerView?.showController()

        val prefs = SecurePrefs(this)
        val baseUrl = CatalogCache.baseUrl ?: prefs.nasUrl
        val sid = CatalogCache.sid
        val repo = SynologyRepository(baseUrl)
        if (sid != null) repo.setSid(sid)

        scope.launch(Dispatchers.IO) {
            if (repo.getSid() == null) {
                repo.login(prefs.nasUser, prefs.nasPass)
                CatalogCache.sid = repo.getSid()
            }
            val url = repo.getDownloadUrl(media!!.filePath)
            val cookieSid = repo.getSid().orEmpty()
            launch(Dispatchers.Main) {
                setupPlayer(url, cookieSid, startPos)
            }
        }
    }

    private fun applySubtitleStyle() {
        val style = SubtitleStylePrefs(this)
        val subView = playerView?.subtitleView ?: return
        subView.setStyle(style.toCaptionStyle())
        subView.setFractionalTextSize(style.fractionalTextSize())
    }

    private fun setupPlayer(url: String, sid: String, startPos: Long) {
        lastDownloadUrl = url
        hideUpNext()
        val factory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(60_000)
            .setDefaultRequestProperties(mapOf("Cookie" to "id=$sid"))

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .build()
        player = exo
        playerView?.player = exo
        applySubtitleStyle()
        titleView?.text = media?.title
        exo.setMediaItem(ExoMediaItem.fromUri(url))
        exo.prepare()
        if (startPos > 0) exo.seekTo(startPos)
        exo.playWhenReady = true
        playerView?.showController()
        introSkipped = startPos > ChapterHelper.skipTargetMs(emptyList())
        loadSidecarChapters(url)

        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playerView?.controllerShowTimeoutMs = if (isPlaying) 4500 else 0
                if (!isPlaying) playerView?.showController()
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                bindTrackButtons()
            }

            override fun onMetadata(metadata: Metadata) {
                val fromMeta = ChapterHelper.parseId3Chapters(metadata)
                if (fromMeta.isNotEmpty()) mergeChapters(fromMeta)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) refreshSkipIntroVisibility()
                if (playbackState == Player.STATE_ENDED) {
                    saveProgress()
                    maybeShowUpNext()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                ) {
                    scope.launch(Dispatchers.IO) {
                        val prefs = SecurePrefs(this@PlayerActivity)
                        val repo = SynologyRepository(CatalogCache.baseUrl ?: prefs.nasUrl)
                        if (repo.login(prefs.nasUser, prefs.nasPass)) {
                            CatalogCache.sid = repo.getSid()
                            val newUrl = repo.getDownloadUrl(media!!.filePath)
                            val newSid = repo.getSid().orEmpty()
                            launch(Dispatchers.Main) {
                                val pos = player?.currentPosition ?: 0L
                                player?.release()
                                setupPlayer(newUrl, newSid, pos)
                            }
                        }
                    }
                }
            }
        })
        uiHandler.removeCallbacks(skipPoll)
        uiHandler.post(skipPoll)
    }

    private fun bindUpNextButtons() {
        findViewById<TextView>(R.id.btnUpNextPlay)?.setOnClickListener {
            cancelUpNextCountdown()
            playNextEpisode()
        }
        findViewById<TextView>(R.id.btnUpNextCancel)?.setOnClickListener {
            dismissUpNext(finishActivity = true)
        }
    }

    private fun maybeShowUpNext() {
        val current = media ?: return
        if (current.mediaType != MediaType.SERIES) {
            finish()
            return
        }
        scope.launch {
            val next = withContext(Dispatchers.IO) { resolveNextEpisode(current) }
            if (next == null) {
                finish()
                return@launch
            }
            nextEpisode = next
            txtUpNextTitle?.text = next.title
            txtUpNextCountdown?.text = getString(R.string.up_next_in, 8)
            panelUpNext?.visibility = View.VISIBLE
            findViewById<View>(R.id.btnUpNextPlay)?.requestFocus()
            upNextSeconds = 8
            upNextCountdownActive = true
            uiHandler.removeCallbacks(upNextTick)
            uiHandler.post(upNextTick)
        }
    }

    /** Cancela solo la cuenta atrás; el panel y el botón Reproducir siguen disponibles. */
    private fun cancelUpNextCountdown() {
        if (!upNextCountdownActive) return
        upNextCountdownActive = false
        uiHandler.removeCallbacks(upNextTick)
        txtUpNextCountdown?.text = getString(R.string.up_next_cancelled)
    }

    private fun dismissUpNext(finishActivity: Boolean) {
        cancelUpNextCountdown()
        hideUpNext()
        if (finishActivity) finish()
    }

    private suspend fun resolveNextEpisode(current: MediaItem): MediaItem? {
        val prefs = SecurePrefs(this)
        val repo = SynologyRepository(CatalogCache.baseUrl ?: prefs.nasUrl)
        CatalogCache.sid?.let { repo.setSid(it) }
        if (repo.getSid() == null) {
            repo.login(prefs.nasUser, prefs.nasPass)
            CatalogCache.sid = repo.getSid()
        }
        val loader = SeriesStructureLoader(repo)
        val root = loader.resolveSeriesRoot(current)
        val seasons = loader.load(root)
        val flat = seasons.flatMap { it.episodes }
        val idx = flat.indexOfFirst { it.filePath == current.filePath }
        if (idx < 0 || idx >= flat.lastIndex) return null
        return episodeAsMedia(current, flat[idx + 1])
    }

    private fun episodeAsMedia(series: MediaItem, ep: EpisodeEntry): MediaItem {
        val code = when {
            ep.episode != null ->
                "S${ep.season.toString().padStart(2, '0')}E${ep.episode.toString().padStart(2, '0')}"
            else -> "T${ep.season}"
        }
        val seriesTitle = series.title.substringBefore(" · ").trim().ifBlank { series.title }
        return MediaItem(
            id = ep.filePath,
            title = "$seriesTitle · $code",
            year = series.year,
            overview = series.overview,
            posterUrl = series.posterUrl,
            backdropUrl = series.backdropUrl,
            filePath = ep.filePath,
            mediaType = MediaType.SERIES,
            voteAverage = series.voteAverage,
            runtime = series.runtime,
            genres = series.genres,
            folderPath = series.folderPath ?: rootFrom(series)
        )
    }

    private fun rootFrom(item: MediaItem): String? =
        item.folderPath ?: CatalogCache.series.find {
            item.filePath.startsWith(it.folderPath.orEmpty()) || item.filePath.startsWith(it.id)
        }?.folderPath

    private fun playNextEpisode() {
        val next = nextEpisode ?: return
        hideUpNext()
        media = next
        titleView?.text = next.title
        scope.launch(Dispatchers.IO) {
            val prefs = SecurePrefs(this@PlayerActivity)
            val repo = SynologyRepository(CatalogCache.baseUrl ?: prefs.nasUrl)
            CatalogCache.sid?.let { repo.setSid(it) }
            if (repo.getSid() == null) {
                repo.login(prefs.nasUser, prefs.nasPass)
                CatalogCache.sid = repo.getSid()
            }
            val url = repo.getDownloadUrl(next.filePath)
            val sid = repo.getSid().orEmpty()
            launch(Dispatchers.Main) {
                player?.release()
                setupPlayer(url, sid, 0L)
            }
        }
    }

    private fun hideUpNext() {
        upNextCountdownActive = false
        uiHandler.removeCallbacks(upNextTick)
        panelUpNext?.visibility = View.GONE
        nextEpisode = null
    }

    private fun loadSidecarChapters(videoUrl: String) {
        scope.launch(Dispatchers.IO) {
            val marks = ChapterHelper.tryLoadSidecar(videoUrl)
            if (marks.isNotEmpty()) {
                launch(Dispatchers.Main) { mergeChapters(marks) }
            }
        }
    }

    private fun mergeChapters(incoming: List<ChapterMark>) {
        val map = linkedMapOf<Long, ChapterMark>()
        chapters.forEach { map[it.startMs] = it }
        incoming.forEach { map[it.startMs] = it }
        chapters = map.values.sortedBy { it.startMs }
        chaptersBtn?.visibility = if (chapters.isNotEmpty()) View.VISIBLE else View.GONE
        refreshSkipIntroVisibility()
    }

    private fun bindChapterButtons() {
        skipIntroBtn?.setOnClickListener {
            val p = player ?: return@setOnClickListener
            val target = ChapterHelper.skipTargetMs(chapters)
            p.seekTo(target)
            introSkipped = true
            skipIntroBtn?.visibility = View.GONE
            playerView?.showController()
        }
        chaptersBtn?.setOnClickListener {
            val p = player ?: return@setOnClickListener
            playerView?.showController()
            ChapterHelper.showChapterPicker(this, p, chapters)
        }
    }

    private fun refreshSkipIntroVisibility() {
        val p = player ?: return
        val show = ChapterHelper.shouldShowSkipIntro(
            positionMs = p.currentPosition,
            durationMs = p.duration.coerceAtLeast(0L),
            chapters = chapters,
            alreadySkipped = introSkipped
        )
        skipIntroBtn?.visibility = if (show) View.VISIBLE else View.GONE
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (panelUpNext?.visibility == View.VISIBLE) {
            // DPAD / media keys: cancelan el auto-play; el usuario elige Reproducir o Cerrar.
            if (isUpNextCancelKey(keyCode)) {
                cancelUpNextCountdown()
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                dismissUpNext(finishActivity = true)
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        val pv = playerView
        if (pv != null && shouldRevealOsd(keyCode)) {
            if (!pv.isControllerFullyVisible) {
                pv.showController()
                pv.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus()
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                ) {
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun isUpNextCancelKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_STOP -> true
        else -> false
    }

    private fun bindTrackButtons() {
        val audioBtn = playerView?.findViewById<TextView>(R.id.btnOsdAudio) ?: return
        val subsBtn = playerView?.findViewById<TextView>(R.id.btnOsdSubs) ?: return
        audioBtn.setOnClickListener {
            val p = player ?: return@setOnClickListener
            playerView?.showController()
            TrackPickHelper.showAudioPicker(this, p)
        }
        subsBtn.setOnClickListener {
            val p = player ?: return@setOnClickListener
            playerView?.showController()
            TrackPickHelper.showSubtitlePicker(this, p)
        }
    }

    private fun shouldRevealOsd(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> true
        else -> false
    }

    override fun onStop() {
        saveProgress()
        super.onStop()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(skipPoll)
        uiHandler.removeCallbacks(upNextTick)
        saveProgress()
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun saveProgress() {
        val item = media ?: return
        val p = player ?: return
        ContinueWatchingStore(this).save(item, p.currentPosition, p.duration.coerceAtLeast(0L))
    }

    companion object {
        const val EXTRA_ITEM = "extra_item"
        const val EXTRA_POSITION = "extra_position"
    }
}
