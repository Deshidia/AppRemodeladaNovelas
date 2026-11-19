package com.lagradost.quicknovel.ui.result

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isGone
import com.lagradost.quicknovel.ChapterData
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

    private fun refresh(
        binding: SimpleChapterBinding,
        card: ChapterData,
        viewModel: ResultViewModel
    ) {
        val alpha = if (viewModel.hasReadChapter(chapter = card)) 0.5F else 1.0F

        binding.name.alpha = alpha
        binding.releaseDate.alpha = alpha
    }

    override fun onBindContent(holder: ViewHolderState<Any>, item: ChapterData, position: Int) {
        val binding = holder.view as? SimpleChapterBinding ?: return
        binding.apply {
            name.text = item.name
            releaseDate.text = item.dateOfRelease
            releaseDate.isGone = item.dateOfRelease.isNullOrBlank()
            refresh(binding, item, viewModel)

            // Selección múltiple (app oficial): fila resaltada y casilla en vez de icono de descarga
            val selecting = viewModel.isSelectingChapters()
            val selected = viewModel.isChapterSelected(item)

            root.backgroundTintList = ColorStateList.valueOf(
                root.context.colorFromAttribute(
                    if (selected) R.attr.iconGrayBackground else R.attr.primaryBlackBackground
                )
            )

            if (selecting) {
                downloadChapter.setImageResource(
                    if (selected) R.drawable.ic_baseline_check_box_24
                    else R.drawable.ic_baseline_check_box_outline_blank_24
                )
            } else {
                downloadChapter.setImageResource(R.drawable.ic_baseline_get_app_24)
            }

            root.setOnClickListener {
                if (viewModel.isSelectingChapters()) {
                    viewModel.toggleChapterSelection(item)
                } else {
                    viewModel.streamRead(item)
                    viewModel.isResume = true//to update read status
                }
            }
            root.setOnLongClickListener {
                // app oficial: pulsación larga entra en el modo selección con este capítulo
                if (viewModel.isSelectingChapters()) {
                    viewModel.toggleChapterSelection(item)
                } else {
                    viewModel.enterChapterSelectionMode(item)
                }
                return@setOnLongClickListener true
            }
            downloadChapter.setOnClickListener {
                // app oficial: el icono de descarga abre la selección con este capítulo marcado
                if (viewModel.isSelectingChapters()) {
                    viewModel.toggleChapterSelection(item)
                } else {
                    viewModel.enterChapterSelectionMode(item)
                }
            }
        }
    }
}
