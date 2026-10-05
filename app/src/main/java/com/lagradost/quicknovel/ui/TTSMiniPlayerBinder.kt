package com.lagradost.quicknovel.ui

import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.TTSGlobalState
import com.lagradost.quicknovel.TTSHelper
import com.lagradost.quicknovel.databinding.TtsMiniPlayerBinding

/**
 * Conecta una tarjeta del reproductor TTS ([TtsMiniPlayerBinding], layout tts_mini_player) con el
 * estado global del TTS. Hay dos tarjetas con este mismo layout:
 *  - la de la actividad principal, encima de la barra de navegación;
 *  - la de la biblioteca, encima de las categorías.
 * Cada una decide cuándo se muestra a través del LiveData [info] que se le pasa (null = oculta).
 */
object TTSMiniPlayerBinder {

    fun bind(
        owner: LifecycleOwner,
        info: LiveData<TTSGlobalState.Info?>,
        player: TtsMiniPlayerBinding
    ) {
        val root = player.root
        val context = root.context

        root.setOnClickListener { TTSGlobalState.openReader(context) }
        player.ttsMiniPlayerPlayPause.setOnClickListener { TTSGlobalState.playPause() }
        player.ttsMiniPlayerForward.setOnClickListener { TTSGlobalState.skipForward() }
        player.ttsMiniPlayerBackward.setOnClickListener { TTSGlobalState.skipBackward() }
        player.ttsMiniPlayerClose.setOnClickListener { TTSGlobalState.stop() }

        info.observe(owner) { value ->
            if (value == null) {
                root.animate().cancel()
                root.isVisible = false
                return@observe
            }

            // Al aparecer, entra deslizándose desde abajo en vez de saltar de golpe
            val wasHidden = !root.isVisible
            root.isVisible = true
            if (wasHidden) {
                root.alpha = 0f
                root.translationY = 16 * context.resources.displayMetrics.density
                root.animate().alpha(1f).translationY(0f).setDuration(220L).start()
            }

            player.ttsMiniPlayerTitle.text = value.bookTitle
            val chapter = value.chapterTitle?.asStringNull(context)
            player.ttsMiniPlayerSubtitle.text = chapter
            player.ttsMiniPlayerSubtitle.isVisible = !chapter.isNullOrBlank()

            // Sin portada se ve el icono de relleno que hay debajo
            player.ttsMiniPlayerCover.setImageBitmap(value.poster)
            player.ttsMiniPlayerCover.isVisible = value.poster != null

            player.ttsMiniPlayerPlayPause.setImageResource(
                if (value.status == TTSHelper.TTSStatus.IsRunning)
                    R.drawable.ic_baseline_pause_24
                else
                    R.drawable.ic_baseline_play_arrow_24
            )
        }
    }
}
