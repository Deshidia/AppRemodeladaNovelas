package com.lagradost.quicknovel.ui.settings

import android.content.Context
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import com.lagradost.quicknovel.CommonActivity.showToast
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.databinding.DialogLibraryListsBinding
import com.lagradost.quicknovel.ui.LibraryList
import com.lagradost.quicknovel.ui.LibraryListManager
import com.lagradost.quicknovel.util.SingleSelectionHelper.showBottomDialog

/**
 * Editor de las listas de la biblioteca (crear, renombrar, borrar, reordenar).
 * Se usa desde Ajustes y desde el botón "Editar" del diálogo de categorías.
 */
object LibraryListsEditor {

    /** [onClose] se llama cuando el usuario cierra el editor. */
    fun show(context: Context, onClose: (() -> Unit)? = null) {
        val binding = DialogLibraryListsBinding.inflate(LayoutInflater.from(context), null, false)

        val dialog = AlertDialog.Builder(context, R.style.AlertDialogCustom)
            .setView(binding.root)
            .create()
        if (onClose != null) dialog.setOnDismissListener { onClose() }

        lateinit var adapter: LibraryListsAdapter

        adapter = LibraryListsAdapter(
            initial = LibraryListManager.getLists(),
            onRename = { list ->
                showTextInputDialog(
                    context,
                    context.getString(R.string.library_list_rename),
                    prefill = list.name
                ) { newName ->
                    LibraryListManager.renameList(list.id, newName)
                    adapter.updateItem(list.copy(name = newName))
                }
            },
            onDelete = { list ->
                if (adapter.currentList.size <= 1) {
                    showToast(R.string.library_list_last_one, Toast.LENGTH_SHORT)
                    return@LibraryListsAdapter
                }
                showDeleteListDialog(context, list, adapter)
            },
            onOrderChanged = { newOrder ->
                LibraryListManager.reorderLists(newOrder)
            }
        )

        binding.libraryListsRecyclerView.layoutManager = LinearLayoutManager(context)
        adapter.attachTo(binding.libraryListsRecyclerView)

        binding.libraryListsAddBtt.setOnClickListener {
            showTextInputDialog(context, context.getString(R.string.library_list_add)) { name ->
                val newList = LibraryListManager.addList(name)
                adapter.addItem(newList)
            }
        }

        dialog.show()
    }

    /** Small text-input dialog used for both "add list" and "rename list". */
    private fun showTextInputDialog(
        context: Context,
        title: String,
        prefill: String = "",
        onConfirm: (String) -> Unit
    ) {
        val editText = EditText(context).apply {
            hint = context.getString(R.string.library_list_add_hint)
            setText(prefill)
            setSelection(prefill.length)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val dialog = AlertDialog.Builder(context, R.style.AlertDialogCustom)
            .setTitle(title)
            .setView(editText)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            // Overridden so an empty name doesn't dismiss the dialog.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = editText.text?.toString()?.trim() ?: ""
                if (name.isEmpty()) {
                    showToast(R.string.library_list_name_empty, Toast.LENGTH_SHORT)
                    return@setOnClickListener
                }
                onConfirm(name)
                if (dialog.isShowing) dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun showDeleteListDialog(
        context: Context,
        list: LibraryList,
        adapter: LibraryListsAdapter
    ) {
        // Every other existing list, plus a "don't assign" option, as a target
        // to migrate this list's novels to.
        val otherLists = adapter.currentList.filter { it.id != list.id }
        val optionNames = mutableListOf(context.getString(R.string.library_list_delete_move_none))
        val optionIds = mutableListOf<Int?>(null)
        otherLists.forEach {
            optionNames.add(it.name)
            optionIds.add(it.id)
        }

        AlertDialog.Builder(context, R.style.AlertDialogCustom)
            .setTitle(context.getString(R.string.library_list_delete))
            .setMessage(context.getString(R.string.library_list_delete_confirm, list.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                if (otherLists.isEmpty()) {
                    LibraryListManager.deleteList(list.id, null)
                    adapter.removeItem(list)
                } else {
                    context.showBottomDialog(
                        optionNames,
                        0,
                        context.getString(R.string.library_list_delete_move_to),
                        false,
                        {}) { selected ->
                        LibraryListManager.deleteList(list.id, optionIds[selected])
                        adapter.removeItem(list)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
