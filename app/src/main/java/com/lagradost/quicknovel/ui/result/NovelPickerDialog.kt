package com.lagradost.quicknovel.ui.result

import android.content.Context
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.lagradost.quicknovel.R
import java.text.Normalizer

/** Una novela de la lista: [name] es la clave real, [label] lo que se muestra (p. ej. con un contador). */
data class NovelPickerEntry(val name: String, val label: String)

private val COMBINING_MARKS = Regex("\\p{InCombiningDiacriticalMarks}+")

/** Minúsculas y sin tildes, para que buscar "orfeo" encuentre "Órfeo". */
fun normalizeForSearch(text: String): String =
    COMBINING_MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()

/**
 * Filtra [names] por [query]: no distingue mayúsculas ni tildes y cada palabra de la búsqueda
 * tiene que aparecer en el nombre (en cualquier orden). Con la búsqueda vacía devuelve todo.
 */
fun filterNovelNames(names: List<String>, query: String): List<String> {
    val words = normalizeForSearch(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return names
    return names.filter { name ->
        val haystack = normalizeForSearch(name)
        words.all { it in haystack }
    }
}

/**
 * Lista de novelas con selección múltiple y, debajo, una barra para buscar por título.
 * Lo marcado se conserva aunque se cambie la búsqueda. [onConfirm] recibe los
 * [NovelPickerEntry.name] marcados al pulsar "Aplicar".
 */
fun Context.showNovelPickerDialog(
    title: String,
    entries: List<NovelPickerEntry>,
    onConfirm: (List<String>) -> Unit,
) {
    val dialog = AlertDialog.Builder(this, R.style.AlertDialogCustom)
        .setView(R.layout.dialog_replace_novels)
        .create()
    dialog.show()
    // el buscador está abajo: que el teclado empuje el diálogo en vez de taparlo
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

    val titleView = dialog.findViewById<TextView>(R.id.replace_pick_title) ?: return
    val listView = dialog.findViewById<ListView>(R.id.replace_pick_list) ?: return
    val emptyView = dialog.findViewById<TextView>(R.id.replace_pick_empty) ?: return
    val searchView = dialog.findViewById<SearchView>(R.id.replace_pick_search) ?: return
    val applyButton = dialog.findViewById<MaterialButton>(R.id.replace_pick_apply) ?: return
    val cancelButton = dialog.findViewById<MaterialButton>(R.id.replace_pick_cancel) ?: return

    titleView.text = title

    val allNames = entries.map { it.name }
    val labelOf = entries.associate { it.name to it.label }
    // la selección va por nombre y no por posición, porque al filtrar las posiciones cambian
    val selected = LinkedHashSet<String>()
    var shown: List<String> = allNames

    val adapter = ArrayAdapter<String>(this, R.layout.sort_bottom_single_choice)
    listView.adapter = adapter
    listView.choiceMode = AbsListView.CHOICE_MODE_MULTIPLE

    fun showFiltered(query: String) {
        shown = filterNovelNames(allNames, query)
        adapter.clear()
        adapter.addAll(shown.map { labelOf.getValue(it) })
        // el ListView recuerda las marcas por posición: se rehacen para la lista filtrada
        listView.clearChoices()
        shown.forEachIndexed { index, name ->
            if (name in selected) listView.setItemChecked(index, true)
        }
        emptyView.isVisible = shown.isEmpty()
    }
    showFiltered("")

    listView.setOnItemClickListener { _, _, position, _ ->
        val name = shown.getOrNull(position) ?: return@setOnItemClickListener
        // el ListView ya ha invertido la marca de esta fila
        if (listView.isItemChecked(position)) selected.add(name) else selected.remove(name)
    }

    searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
        override fun onQueryTextSubmit(query: String?): Boolean {
            searchView.clearFocus() // esconde el teclado
            return true
        }

        override fun onQueryTextChange(newText: String?): Boolean {
            showFiltered(newText.orEmpty())
            return true
        }
    })

    applyButton.setOnClickListener {
        onConfirm(selected.toList())
        dialog.dismiss()
    }
    cancelButton.setOnClickListener { dialog.dismiss() }
}
