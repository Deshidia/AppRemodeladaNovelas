package com.lagradost.quicknovel.ui.result

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.DownloadState
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.databinding.SimpleChapterBinding
import com.lagradost.quicknovel.ui.BaseDiffCallback
import com.lagradost.quicknovel.ui.NoStateAdapter
import com.lagradost.quicknovel.ui.ViewHolderState
import com.lagradost.quicknovel.ui.newSharedPool
import com.lagradost.quicknovel.util.UIHelper.colorFromAttribute

class ChapterAdapter(val viewModel: ResultViewModel) :
    NoStateAdapter<ChapterData>(
        diffCallback = BaseDiffCallback(
            itemSame = { a, b -> a.url == b.url },
            contentSame = { a, b -> a == b }
        )) {

    companion object {
        val sharedPool =
            newSharedPool {
                setMaxRecycledViews(CONTENT, 10)
            }
    }

    override fun onCreateContent(parent: ViewGroup): ViewHolderState<Any> {
        return ViewHolderState(
            SimpleChapterBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindContent(holder: ViewHolderState<Any>, item: ChapterData, position: Int) {
        val binding = holder.view as? SimpleChapterBinding ?: return
        val context = binding.root.context
        val index = viewModel.chapterIndexOf(item)

        binding.apply {
            name.text = item.name
            releaseDate.text = item.dateOfRelease
            releaseDate.isGone = item.dateOfRelease.isNullOrBlank()

            // app oficial: los capítulos leídos muestran un check a la izquierda
            chapterReadCheck.isGone = !viewModel.hasReadChapter(item)

            // app oficial: la fila seleccionada se resalta (onBackground al 20%)
            root.backgroundTintList = ColorStateList.valueOf(
                if (viewModel.isChapterSelected(item)) {
                    ColorUtils.setAlphaComponent(
                        context.colorFromAttribute(R.attr.textColor),
                        (255 * 0.2f).toInt()
                    )
                } else {
                    context.colorFromAttribute(R.attr.primaryBlackBackground)
                }
            )

            // separador "Descarga parcial desde N +" (app oficial):
            // sobre la fila en orden normal, debajo en orden inverso
            val offset = viewModel.downloadOffsetIndex()
            val isSpecialChapter = index != null && index > 0 && offset == index
            val sorting = ChapterSortingMethodType.from(ResultViewModel.sortChapterBy)
            val strokeText = context.getString(R.string.download_from_chapter) + " ${(index ?: 0) + 1} +"

            chapterStrokeTop.isVisible = isSpecialChapter &&
                    (sorting == ChapterSortingMethodType.Default || sorting == ChapterSortingMethodType.Ascending)
            chapterStrokeTopText.text = strokeText
            chapterStrokeBottom.isVisible = isSpecialChapter &&
                    sorting == ChapterSortingMethodType.RevAscending
            chapterStrokeBottomText.text = strokeText

            // estado del icono de descarga por capítulo (app oficial):
            // en curso = spinner, en cola = not_started, descargado = check, sin descargar = flecha
            val progress = viewModel.currentDownloadProgress()
            val downloading = progress?.let {
                it.state == DownloadState.IsDownloading && it.currentChapter == index
            } == true
            val pending = index != null &&
                    progress?.pendingChapters?.get(index) == true
            val downloaded = viewModel.isChapterDownloaded(item)

            downloadChapterProgress.isVisible = downloading
            downloadChapter.isVisible = !downloading
            when {
                downloading -> {}
                pending -> downloadChapter.setImageResource(R.drawable.not_started)
                downloaded -> downloadChapter.setImageResource(R.drawable.check_circle)
                else -> downloadChapter.setImageResource(R.drawable.arrow_circle_down_24px)
            }

            // app oficial: tocar alterna la selección mientras se está seleccionando
            root.setOnClickListener {
                if (viewModel.isSelectingChapters()) {
                    viewModel.toggleChapterSelection(item)
                } else {
                    viewModel.streamRead(item)
                    viewModel.isResume = true//to update read status
                }
            }
            // app oficial: pulsación larga alterna la selección (entra en el modo selección)
            root.setOnLongClickListener {
                viewModel.toggleChapterSelection(item)
                return@setOnLongClickListener true
            }
            // app oficial: el icono descarga este capítulo (sin acción si ya está descargado
            // o su descarga está en curso o en cola)
            downloadChapter.setOnClickListener {
                if (!downloading && !pending && !downloaded) {
                    viewModel.downloadChapter(item)
                }
            }
        }
    }
}
