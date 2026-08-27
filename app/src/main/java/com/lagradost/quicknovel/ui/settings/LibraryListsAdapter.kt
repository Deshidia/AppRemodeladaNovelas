package com.lagradost.quicknovel.ui.settings

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.quicknovel.databinding.ItemLibraryListBinding
import com.lagradost.quicknovel.ui.LibraryList
import java.util.Collections

/**
 * Simple (non diff-based) adapter for the library-lists management dialog.
 * A plain adapter is used instead of [com.lagradost.quicknovel.ui.BaseAdapter]
 * because drag & drop reordering needs direct, synchronous control over the
 * backing list via [ItemTouchHelper] (notifyItemMoved), which does not mix
 * well with AsyncListDiffer based adapters.
 */
class LibraryListsAdapter(
    initial: List<LibraryList>,
    private val onRename: (LibraryList) -> Unit,
    private val onDelete: (LibraryList) -> Unit,
    private val onOrderChanged: (List<LibraryList>) -> Unit,
) : RecyclerView.Adapter<LibraryListsAdapter.ViewHolder>() {

    val currentList: MutableList<LibraryList> = initial.toMutableList()

    inner class ViewHolder(val binding: ItemLibraryListBinding) :
        RecyclerView.ViewHolder(binding.root)

    private var touchHelper: ItemTouchHelper? = null

    fun attachTo(recyclerView: RecyclerView) {
        recyclerView.adapter = this
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                Collections.swap(currentList, from, to)
                notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // swipe is not used, only drag
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                onOrderChanged(currentList.toList())
            }
        }
        touchHelper = ItemTouchHelper(callback).also { it.attachToRecyclerView(recyclerView) }
    }

    fun addItem(item: LibraryList) {
        currentList.add(item)
        notifyItemInserted(currentList.lastIndex)
    }

    fun removeItem(item: LibraryList) {
        val index = currentList.indexOfFirst { it.id == item.id }
        if (index == -1) return
        currentList.removeAt(index)
        notifyItemRemoved(index)
    }

    fun updateItem(item: LibraryList) {
        val index = currentList.indexOfFirst { it.id == item.id }
        if (index == -1) return
        currentList[index] = item
        notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            ItemLibraryListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = currentList[position]
        holder.binding.apply {
            libraryListName.text = item.name
            libraryListEdit.setOnClickListener { onRename(item) }
            libraryListDelete.setOnClickListener { onDelete(item) }
            libraryListDragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    touchHelper?.startDrag(holder)
                }
                false
            }
        }
    }

    override fun getItemCount(): Int = currentList.size
}
