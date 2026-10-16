package com.piner.pinertv.ui.widget

import android.graphics.drawable.Drawable
import android.widget.ImageView
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Scale
import coil.size.Size
import com.piner.pinertv.R

/**
 * Rellena el marco 2:3 sin franjas laterales: CENTER_CROP + Scale.FILL.
 * Size.ORIGINAL evita que Coil use el SizeResolver del ImageView.
 */
object PosterLoader {

    fun load(imageView: ImageView, url: String?) {
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        val clean = url?.takeIf { it.isNotBlank() }
        if (clean == null) {
            imageView.setImageResource(R.drawable.poster_placeholder)
            return
        }
        val ctx = imageView.context
        val request = ImageRequest.Builder(ctx)
            .data(clean)
            .size(Size.ORIGINAL)
            .scale(Scale.FILL)
            .allowHardware(false)
            .placeholder(R.drawable.poster_placeholder)
            .error(R.drawable.poster_placeholder)
            .fallback(R.drawable.poster_placeholder)
            .target(
                onStart = { placeholder ->
                    imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                    if (placeholder != null) imageView.setImageDrawable(placeholder)
                },
                onSuccess = { result: Drawable ->
                    imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                    imageView.setImageDrawable(result)
                },
                onError = { error ->
                    imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                    imageView.setImageDrawable(
                        error ?: ctx.getDrawable(R.drawable.poster_placeholder)
                    )
                }
            )
            .build()
        ctx.imageLoader.enqueue(request)
    }
}
