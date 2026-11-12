package com.lagradost.quicknovel.ui.result

import android.graphics.Color
import android.util.TypedValue
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
        // Icono de leído a la izquierda, como el ChapterItem oficial
        // (ResultScreen.kt: AnimatedVisibility(response.chaptersRead[...]))
        binding.chapterReadCheck.isGone = !viewModel.hasReadChapter(chapter = card)

        // Resalte de selección, como la oficial:
        // onBackground.copy(alpha = 0.2f) — aquí ?attr/textColor con alpha 0.2
        binding.root.setBackgroundColor(
            if (viewModel.isChapterSelected(card)) {
                val tv = TypedValue()
                binding.root.context.theme.resolveAttribute(R.attr.textColor, tv, true)
                Color.argb(51, Color.red(tv.data), Color.green(tv.data), Color.blue(tv.data))
            } else {
                Color.TRANSPARENT
            }
        )
    }

    override fun onBindContent(holder: ViewHolderState<Any>, item: ChapterData, position: Int) {
        val binding = holder.view as? SimpleChapterBinding ?: return
        binding.apply {
            name.text = item.name
            releaseDate.text = item.dateOfRelease
            releaseDate.isGone = item.dateOfRelease.isNullOrBlank()
            root.setOnClickListener {
                // Con selección activa, el toque alterna la selección (oficial:
                // onClick = if (isSelectingChapters) toggleClick else Stream)
                if (viewModel.isSelectingChapters) {
                    viewModel.toggleChapterSelection(item)
                } else {
                    viewModel.streamRead(item)
                    viewModel.isResume = true//to update read status
                }
            }
            // Pulsación larga = entrar en modo selección (oficial: onLongClick = toggleClick)
            root.setOnLongClickListener {
                viewModel.toggleChapterSelection(item)
                return@setOnLongClickListener true
            }
            refresh(binding, item, viewModel)
        }
    }
}
