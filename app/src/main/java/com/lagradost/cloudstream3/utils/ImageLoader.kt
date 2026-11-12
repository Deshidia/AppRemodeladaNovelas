package com.lagradost.cloudstream3.utils

// SHIM de compatibilidad (código del patch, no upstream): sustituye al ImageLoader
// del jar ausente delegando en el pipeline coil3 del propio fork
// (com.lagradost.quicknovel.util.ImageLoader).
import android.widget.ImageView
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import com.lagradost.quicknovel.ui.UiImage
import com.lagradost.quicknovel.util.ImageLoader as ForkImageLoader

object ImageLoader {
    fun buildImageLoader(context: PlatformContext): ImageLoader =
        ForkImageLoader.buildImageLoader(context)

    fun ImageView.loadImage(
        imageData: UiImage?,
        builder: ImageRequest.Builder.() -> Unit = {}
    ) = with(ForkImageLoader) { this@loadImage.loadImage(imageData, builder) }
}
