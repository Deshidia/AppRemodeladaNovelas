package com.lagradost.quicknovel.ui.search

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.SearchFilter
import com.lagradost.quicknovel.SearchFilterOption
import com.lagradost.quicknovel.SearchFilterType
import com.lagradost.quicknovel.util.Coroutines.ioSafe
import com.lagradost.quicknovel.util.Coroutines.main
import com.lagradost.quicknovel.util.SingleSelectionHelper.showDialog
import com.lagradost.quicknovel.util.SingleSelectionHelper.showMultiDialog
import java.util.concurrent.ConcurrentHashMap

/**
 * Diálogo para elegir los [MainAPI.searchFilters] de un proveedor.
 *
 * Funciona como un menú: una lista con cada filtro y su valor actual; al tocar uno se abre su
 * selector (lista, multiselección o número/fecha) y al cerrarlo se vuelve a la lista. Solo al
 * pulsar "Buscar con estos filtros" se aplican los cambios; cerrar el diálogo los descarta.
 */
object SearchFiltersDialog {
    private const val OP_MIN = "min"
    private const val OP_MAX = "max"
    private val DATE_REGEX = Regex("^\\d{1,2}/\\d{1,2}/\\d{4}$")

    /** Opciones ya descargadas (listas largas como las etiquetas), por proveedor y filtro. */
    private val optionsCache = ConcurrentHashMap<String, List<SearchFilterOption>>()

    fun show(
        context: Context,
        api: MainAPI,
        current: Map<String, List<String>>,
        onApply: (Map<String, List<String>>) -> Unit
    ) {
        val working = LinkedHashMap<String, List<String>>()
        working.putAll(current)
        showMain(context, api, working, onApply)
    }

    // ---------------------------------------------------------------------------------------------

    private fun showMain(
        context: Context,
        api: MainAPI,
        working: MutableMap<String, List<String>>,
        onApply: (Map<String, List<String>>) -> Unit
    ) {
        val filters = api.searchFilters
        val items = ArrayList<String>()
        for (filter in filters) {
            items.add("${filter.label}: ${summary(context, filter, working)}")
        }
        items.add(context.getString(R.string.search_filters_reset))
        items.add(context.getString(R.string.search_filters_apply))

        context.showDialog(
            items = items,
            selectedIndex = -1,
            name = context.getString(R.string.search_filters_for, api.name),
            showApply = false,
            dismissCallback = {},
            callback = { index ->
                when {
                    index < filters.size ->
                        edit(context, api, filters[index], working, onApply)

                    index == filters.size -> {
                        working.clear()
                        showMain(context, api, working, onApply)
                    }

                    else -> onApply(LinkedHashMap(working))
                }
            }
        )
    }

    private fun summary(
        context: Context,
        filter: SearchFilter,
        working: Map<String, List<String>>
    ): String {
        val empty = filter.emptyLabel ?: context.getString(R.string.search_filters_any)
        val values = working[filter.key].orEmpty()
        if (values.isEmpty()) return empty

        return when (filter.type) {
            SearchFilterType.SINGLE ->
                filter.options.firstOrNull { it.value == values.first() }?.label ?: values.first()

            SearchFilterType.MULTI -> {
                val labels = values.map { v -> filter.options.firstOrNull { it.value == v }?.label }
                // Las listas "lazy" no tienen etiquetas a mano: solo se muestra cuántas hay
                if (filter.lazyOptions || labels.any { it == null }) {
                    context.getString(R.string.search_filters_n_selected, values.size)
                } else if (labels.size <= 2) {
                    labels.joinToString(", ")
                } else {
                    context.getString(R.string.search_filters_n_selected, values.size)
                }
            }

            SearchFilterType.NUMBER, SearchFilterType.DATE -> {
                val op = filter.operatorKey?.let { working[it]?.firstOrNull() ?: filter.defaultOperator }
                when (op) {
                    OP_MIN -> "≥ ${values.first()}"
                    OP_MAX -> "≤ ${values.first()}"
                    else -> values.first()
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun edit(
        context: Context,
        api: MainAPI,
        filter: SearchFilter,
        working: MutableMap<String, List<String>>,
        onApply: (Map<String, List<String>>) -> Unit
    ) {
        val back = { showMain(context, api, working, onApply) }
        when (filter.type) {
            SearchFilterType.SINGLE -> editSingle(context, filter, working, back)
            SearchFilterType.MULTI -> editMulti(context, api, filter, working, back)
            SearchFilterType.NUMBER, SearchFilterType.DATE -> editValue(context, filter, working, back)
        }
    }

    private fun editSingle(
        context: Context,
        filter: SearchFilter,
        working: MutableMap<String, List<String>>,
        back: () -> Unit
    ) {
        val options = filter.options
        val labels = listOf(filter.emptyLabel ?: context.getString(R.string.search_filters_any)) +
                options.map { it.label }
        val current = working[filter.key]?.firstOrNull()
        val selected = options.indexOfFirst { it.value == current } + 1 // 0 = sin elegir

        context.showDialog(
            items = labels,
            selectedIndex = selected,
            name = filter.label,
            showApply = false,
            dismissCallback = { back() },
            callback = { index ->
                if (index == 0) working.remove(filter.key)
                else working[filter.key] = listOf(options[index - 1].value)
            }
        )
    }

    private fun editMulti(
        context: Context,
        api: MainAPI,
        filter: SearchFilter,
        working: MutableMap<String, List<String>>,
        back: () -> Unit
    ) {
        withOptions(context, api, filter, back) { options ->
            val selectedValues = working[filter.key].orEmpty().toSet()
            val selectedIndexes = options.mapIndexedNotNull { i, option ->
                if (option.value in selectedValues) i else null
            }
            context.showMultiDialog(
                items = options.map { it.label },
                selectedIndex = selectedIndexes,
                name = filter.label,
                dismissCallback = { back() },
                callback = { chosen ->
                    if (chosen.isEmpty()) working.remove(filter.key)
                    else working[filter.key] = chosen.map { options[it].value }
                }
            )
        }
    }

    /** Entrega las opciones del filtro; si hay que descargarlas, lo hace en segundo plano. */
    private fun withOptions(
        context: Context,
        api: MainAPI,
        filter: SearchFilter,
        onFailure: () -> Unit,
        block: (List<SearchFilterOption>) -> Unit
    ) {
        if (!filter.lazyOptions) {
            block(filter.options)
            return
        }
        val cacheKey = "${api.name}/${filter.key}"
        optionsCache[cacheKey]?.let {
            block(it)
            return
        }
        Toast.makeText(context, R.string.search_filters_loading, Toast.LENGTH_SHORT).show()
        ioSafe {
            val loaded = try {
                api.loadSearchFilterOptions(filter)
            } catch (e: Exception) {
                emptyList()
            }
            main {
                if (loaded.isEmpty()) {
                    Toast.makeText(context, R.string.search_filters_load_failed, Toast.LENGTH_LONG)
                        .show()
                    onFailure()
                } else {
                    optionsCache[cacheKey] = loaded
                    block(loaded)
                }
            }
        }
    }

    /**
     * Número o fecha. Primero se elige el operador (mín./máx.) si el filtro lo tiene y después se
     * escribe el valor. "Cualquiera" borra el filtro.
     */
    private fun editValue(
        context: Context,
        filter: SearchFilter,
        working: MutableMap<String, List<String>>,
        back: () -> Unit
    ) {
        val operatorKey = filter.operatorKey
        if (operatorKey == null) {
            askValue(context, filter, working, back)
            return
        }

        val labels = listOf(
            context.getString(R.string.search_filters_any),
            context.getString(R.string.search_filters_min),
            context.getString(R.string.search_filters_max),
        )
        val currentOp = working[operatorKey]?.firstOrNull()
            ?: if (working[filter.key].isNullOrEmpty()) null else filter.defaultOperator
        val selected = when (currentOp) {
            OP_MIN -> 1
            OP_MAX -> 2
            else -> 0
        }

        var handled = false
        context.showDialog(
            items = labels,
            selectedIndex = selected,
            name = filter.label,
            showApply = false,
            dismissCallback = { if (!handled) back() },
            callback = { index ->
                handled = true
                if (index == 0) {
                    working.remove(filter.key)
                    working.remove(operatorKey)
                    back()
                } else {
                    working[operatorKey] = listOf(if (index == 1) OP_MIN else OP_MAX)
                    askValue(context, filter, working, back)
                }
            }
        )
    }

    private fun askValue(
        context: Context,
        filter: SearchFilter,
        working: MutableMap<String, List<String>>,
        back: () -> Unit
    ) {
        val input = EditText(context).apply {
            inputType = if (filter.type == SearchFilterType.DATE) {
                InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
            } else {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
            hint = if (filter.type == SearchFilterType.DATE) "mm/dd/yyyy"
            else context.getString(R.string.search_filters_value_hint)
            setText(working[filter.key]?.firstOrNull().orEmpty())
            setSelection(text.length)
        }
        val padding = (20 * context.resources.displayMetrics.density).toInt()
        val container = FrameLayout(context).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }

        AlertDialog.Builder(context)
            .setTitle(filter.label)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = input.text.toString().trim()
                when {
                    value.isEmpty() -> {
                        working.remove(filter.key)
                        filter.operatorKey?.let { working.remove(it) }
                    }

                    filter.type == SearchFilterType.DATE && !DATE_REGEX.matches(value) ->
                        Toast.makeText(context, R.string.search_filters_bad_date, Toast.LENGTH_LONG)
                            .show()

                    else -> working[filter.key] = listOf(value)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { back() }
            .show()
    }
}
