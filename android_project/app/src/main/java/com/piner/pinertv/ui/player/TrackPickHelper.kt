package com.piner.pinertv.ui.player

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.piner.pinertv.R
import java.util.Locale

/** Selector TV de pistas audio / subtítulos (Media3 Tracks API). */
object TrackPickHelper {

    data class Option(
        val label: String,
        val group: Tracks.Group?,
        val trackIndex: Int,
        val disableType: Boolean = false
    )

    fun showAudioPicker(context: Context, player: Player) {
        val options = buildOptions(context, player, C.TRACK_TYPE_AUDIO, includeOff = false)
        if (options.isEmpty()) {
            Toast.makeText(context, R.string.no_audio_tracks, Toast.LENGTH_SHORT).show()
            return
        }
        showDialog(context, context.getString(R.string.audio_tracks), options, player, C.TRACK_TYPE_AUDIO)
    }

    fun showSubtitlePicker(context: Context, player: Player) {
        val options = buildOptions(context, player, C.TRACK_TYPE_TEXT, includeOff = true)
        if (options.size <= 1) {
            Toast.makeText(context, R.string.no_subtitle_tracks, Toast.LENGTH_SHORT).show()
            return
        }
        showDialog(context, context.getString(R.string.subtitle_tracks), options, player, C.TRACK_TYPE_TEXT)
    }

    private fun buildOptions(
        context: Context,
        player: Player,
        type: @C.TrackType Int,
        includeOff: Boolean
    ): List<Option> {
        val out = mutableListOf<Option>()
        if (includeOff) {
            out += Option(
                label = context.getString(R.string.subtitles_off),
                group = null,
                trackIndex = -1,
                disableType = true
            )
        }
        var n = 0
        for (group in player.currentTracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                n++
                out += Option(
                    label = formatLabel(context, group.getTrackFormat(i), n),
                    group = group,
                    trackIndex = i
                )
            }
        }
        return out
    }

    private fun formatLabel(context: Context, format: Format, ordinal: Int): String {
        val lang = format.language
            ?.takeIf { it.isNotBlank() && !it.equals("und", true) }
            ?.let { Locale(it).displayLanguage.replaceFirstChar { c -> c.uppercase() } }
        val label = format.label?.takeIf { it.isNotBlank() }
        val channels = format.channelCount.takeIf { it > 0 }?.let { "${it}ch" }
        val bits = listOfNotNull(lang, label, channels).distinct().joinToString(" · ")
        return bits.ifBlank { context.getString(R.string.track_unknown, ordinal) }
    }

    private fun showDialog(
        context: Context,
        title: String,
        options: List<Option>,
        player: Player,
        type: @C.TrackType Int
    ) {
        val labels = options.map { it.label }.toTypedArray()
        val selected = options.indexOfFirst { opt ->
            when {
                opt.disableType ->
                    player.trackSelectionParameters.disabledTrackTypes.contains(type)
                opt.group != null ->
                    opt.group.isSelected && opt.group.isTrackSelected(opt.trackIndex)
                else -> false
            }
        }.coerceAtLeast(0)

        AlertDialog.Builder(context)
            .setTitle(title)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                apply(player, type, options[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun apply(player: Player, type: @C.TrackType Int, option: Option) {
        val params = player.trackSelectionParameters.buildUpon()
        if (option.disableType || option.group == null) {
            params.setTrackTypeDisabled(type, true)
            params.clearOverridesOfType(type)
        } else {
            params.setTrackTypeDisabled(type, false)
            params.clearOverridesOfType(type)
            params.addOverride(TrackSelectionOverride(option.group.mediaTrackGroup, option.trackIndex))
        }
        player.trackSelectionParameters = params.build()
    }
}
