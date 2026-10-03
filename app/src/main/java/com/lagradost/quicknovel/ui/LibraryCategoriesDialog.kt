package com.lagradost.quicknovel.ui

import android.content.Context
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatCheckBox
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.ui.settings.LibraryListsEditor

/**
 * Diálogo centrado para elegir en qué listas de la biblioteca está una novela, al estilo de
 * "Establecer categorías" de Mihon: una casilla por lista (se pueden marcar varias) y los
 * botones Editar (abre el editor de listas), Cancelar y Aceptar.
 * Si no se marca ninguna lista, la novela sale de la biblioteca.
 */
object LibraryCategoriesDialog {

    /**
     * @param selected ids de las listas marcadas al abrir el diálogo.
     * @param onConfirm se llama con las listas marcadas al pulsar Aceptar.
     */
    fun show(context: Context, selected: Set<Int>, onConfirm: (Set<Int>) -> Unit) {
        val lists = LibraryListManager.getLists()
        val checked = selected.toMutableSet()
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        for (list in lists) {
            container.addView(
                AppCompatCheckBox(context).apply {
                    text = list.name
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    minHeight = dp(48)
                    setPadding(dp(12), 0, dp(8), 0)
                    isChecked = list.id in checked
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) checked.add(list.id) else checked.remove(list.id)
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.library_categories_title)
            .setView(ScrollView(context).apply { addView(container) })
            .setPositiveButton(R.string.ok) { _, _ -> onConfirm(checked.toSet()) }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.library_categories_edit) { _, _ ->
                // Al cerrar el editor se vuelve a mostrar el diálogo, conservando lo ya marcado
                // (por si se ha añadido, renombrado o borrado alguna lista).
                LibraryListsEditor.show(context) {
                    show(context, checked, onConfirm)
                }
            }
            .show()
    }
}
