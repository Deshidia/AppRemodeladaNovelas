package com.lagradost.quicknovel.ui.history

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.databinding.HistoryResultCompactBinding
import com.lagradost.quicknovel.ui.LibraryCategoriesDialog
import com.lagradost.quicknovel.ui.NoStateAdapter
import com.lagradost.quicknovel.ui.ViewHolderState
import com.lagradost.quicknovel.ui.newSharedPool
import com.lagradost.quicknovel.util.ResultCached
import com.lagradost.quicknovel.util.UIHelper.setImage

class HistoryAdapter(private val viewModel: HistoryViewModel) :
    NoStateAdapter<ResultCached>(DiffCallback()) {

    companion object {
        val sharedPool =
            newSharedPool {
                setMaxRecycledViews(CONTENT, 10)
            }
    }

    override fun onCreateContent(parent: ViewGroup): ViewHolderState<Any> {
        return ViewHolderState(HistoryResultCompactBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    @SuppressLint("SetTextI18n")
    override fun onBindContent(
        holder: ViewHolderState<Any>,
        item: ResultCached,
        position: Int
    ) {
        val binding = holder.view as? HistoryResultCompactBinding ?: return

        binding.apply {
            imageText.text = item.name
            historyExtraText.text = "${item.totalChapters} " + root.context.getString(R.string.read_action_chapters)
            imageView.setImage(item.poster)

            updateBookmarkIcon(historyBookmark, item)
            historyBookmark.setOnClickListener { view ->
                LibraryCategoriesDialog.show(view.context, viewModel.getLibraryStates(item)) { states ->
                    viewModel.setLibraryStates(item, states)
                    updateBookmarkIcon(historyBookmark, item)
                }
            }
            historyPlay.setOnClickListener {
                viewModel.stream(item)
            }
            imageView.setOnClickListener {
                viewModel.open(item)
            }
            historyDelete.setOnClickListener {
                viewModel.deleteAlert(item)
            }
            imageView.setOnLongClickListener {
                viewModel.showMetadata(item)
                return@setOnLongClickListener true
            }
        }
    }

    private fun updateBookmarkIcon(icon: ImageView, item: ResultCached) {
        val saved = viewModel.getLibraryStates(item).isNotEmpty()
        icon.setImageResource(
            if (saved) R.drawable.ic_baseline_bookmark_24 else R.drawable.ic_baseline_bookmark_border_24
        )
    }

    class DiffCallback : DiffUtil.ItemCallback<ResultCached>() {
        override fun areItemsTheSame(oldItem: ResultCached, newItem: ResultCached): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ResultCached, newItem: ResultCached): Boolean =
            oldItem == newItem
    }
}