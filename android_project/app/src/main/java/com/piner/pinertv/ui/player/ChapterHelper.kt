package com.piner.pinertv.ui.player

import android.app.AlertDialog
import android.content.Context
import android.util.Log
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.extractor.metadata.id3.ChapterFrame
import com.piner.pinertv.R
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

data class ChapterMark(
    val title: String,
    val startMs: Long
)

/**
 * Capítulos desde metadatos ID3 / sidecar `.chapters.txt` junto al vídeo.
 * Intro skip heurístico si no hay marcador de intro.
 */
object ChapterHelper {

    private const val TAG = "PinerTV-Chapters"
    private const val DEFAULT_INTRO_END_MS = 90_000L
    private const val SKIP_WINDOW_MS = 5 * 60_000L
    private const val MIN_DURATION_FOR_SKIP_MS = 20 * 60_000L

    fun parseId3Chapters(metadata: Metadata): List<ChapterMark> {
        val out = mutableListOf<ChapterMark>()
        for (i in 0 until metadata.length()) {
            val entry = metadata.get(i)
            if (entry is ChapterFrame) {
                val title = entry.id.takeIf { it.isNotBlank() }
                    ?: "Capítulo ${out.size + 1}"
                out += ChapterMark(
                    title = title,
                    startMs = entry.startTimeMs.toLong().coerceAtLeast(0L)
                )
            }
        }
        return out.sortedBy { it.startMs }
    }

    /** Sidecar: líneas `HH:MM:SS Título` o `MM:SS Título`. */
    fun parseSidecarText(text: String): List<ChapterMark> {
        val out = mutableListOf<ChapterMark>()
        val re = Regex("""^\s*(\d{1,2}:)?(\d{1,2}):(\d{2})\s+(.+?)\s*$""")
        for (line in text.lineSequence()) {
            val m = re.matchEntire(line.trim()) ?: continue
            val hours = m.groupValues[1].removeSuffix(":").toLongOrNull() ?: 0L
            val mins = m.groupValues[2].toLong()
            val secs = m.groupValues[3].toLong()
            val title = m.groupValues[4].trim()
            val ms = ((hours * 3600) + (mins * 60) + secs) * 1000L
            if (title.isNotEmpty()) out += ChapterMark(title, ms)
        }
        return out.sortedBy { it.startMs }
    }

    suspend fun tryLoadSidecar(downloadUrlForVideo: String): List<ChapterMark> {
        val candidates = sidecarUrls(downloadUrlForVideo)
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
        for (url in candidates) {
            try {
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string().orEmpty()
                    val marks = parseSidecarText(body)
                    if (marks.isNotEmpty()) {
                        Log.i(TAG, "Sidecar capítulos: ${marks.size} desde $url")
                        return marks
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Sidecar falló: $url", e)
            }
        }
        return emptyList()
    }

    private fun sidecarUrls(videoDownloadUrl: String): List<String> {
        // La URL de Synology lleva path=...; sustituimos extensión en el query path.
        val uri = android.net.Uri.parse(videoDownloadUrl)
        val path = uri.getQueryParameter("path") ?: return emptyList()
        val base = path.substringBeforeLast('.', path)
        val variants = listOf("$base.chapters.txt", "$path.chapters.txt", "$base.chapters")
        return variants.map { newPath ->
            uri.buildUpon().clearQuery().apply {
                uri.queryParameterNames.forEach { key ->
                    if (key == "path") appendQueryParameter(key, newPath)
                    else appendQueryParameter(key, uri.getQueryParameter(key))
                }
            }.build().toString()
        }
    }

    fun introEndMs(chapters: List<ChapterMark>): Long? {
        val intro = chapters.firstOrNull { ch ->
            val t = ch.title.lowercase(Locale.ROOT)
            t.contains("intro") || t.contains("opening") ||
                t.contains("créditos iniciales") || t.contains("creditos iniciales") ||
                t.contains("cold open")
        }
        if (intro != null) {
            // Fin de intro = inicio del siguiente capítulo, o +90s
            val next = chapters.firstOrNull { it.startMs > intro.startMs }
            return next?.startMs ?: (intro.startMs + DEFAULT_INTRO_END_MS)
        }
        return null
    }

    fun shouldShowSkipIntro(
        positionMs: Long,
        durationMs: Long,
        chapters: List<ChapterMark>,
        alreadySkipped: Boolean
    ): Boolean {
        if (alreadySkipped) return false
        if (durationMs in 1 until MIN_DURATION_FOR_SKIP_MS) return false
        val end = introEndMs(chapters) ?: DEFAULT_INTRO_END_MS
        return positionMs in 0 until minOf(end, SKIP_WINDOW_MS) && positionMs < end - 2_000L
    }

    fun skipTargetMs(chapters: List<ChapterMark>): Long =
        introEndMs(chapters) ?: DEFAULT_INTRO_END_MS

    fun showChapterPicker(context: Context, player: Player, chapters: List<ChapterMark>) {
        if (chapters.isEmpty()) {
            android.widget.Toast.makeText(context, R.string.no_chapters, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val labels = chapters.map { ch ->
            "${formatTime(ch.startMs)}  ${ch.title}"
        }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.chapters)
            .setItems(labels) { _, which ->
                player.seekTo(chapters[which].startMs)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).toInt()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }
}
