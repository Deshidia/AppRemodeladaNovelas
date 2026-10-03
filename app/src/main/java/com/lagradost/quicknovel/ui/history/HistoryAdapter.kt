package com.lagradost.quicknovel.ui.history

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.databinding.HistoryResultCompactBinding
import com.lagradost.quicknovel.ui.LibraryListManager
import com.lagradost.quicknovel.ui.NoStateAdapter
import com.lagradost.quicknovel.ui.ReadType
import com.lagradost.quicknovel.ui.ViewHolderState
import com.lagradost.quicknovel.ui.newSharedPool
import com.lagradost.quicknovel.util.ResultCached
import com.lagradost.quicknovel.util.SingleSelectionHelper.showBottomDialog
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
                showLibraryDialog(view.context, historyBookmark, item)
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
        val saved = viewModel.getLibraryState(item) != ReadType.NONE.prefValue
        icon.setImageResource(
            if (saved) R.drawable.ic_baseline_bookmark_24 else R.drawable.ic_baseline_bookmark_border_24
        )
    }

    /** Selector de lista de la biblioteca (o "Ninguno" para quitarlo), igual que en la pantalla de la novela. */
    private fun showLibraryDialog(context: Context, icon: ImageView, item: ResultCached) {
        val lists = LibraryListManager.getLists()
        val ids = mutableListOf(ReadType.NONE.prefValue)
        val names = mutableListOf(context.getString(R.string.type_none))
        lists.forEach { list ->
            ids.add(list.id)
            names.add(list.name)
        }
        context.showBottomDialog(
            names,
            selectedIndex = ids.indexOf(viewModel.getLibraryState(item)),
            context.getString(R.string.history_save_to_library),
            false,
            {}
        ) { selected ->
            viewModel.setLibraryState(item, ids[selected])
            updateBookmarkIcon(icon, item)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ResultCached>() {
        override fun areItemsTheSame(oldItem: ResultCached, newItem: ResultCached): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ResultCached, newItem: ResultCached): Boolean =
            oldItem == newItem
    }
}