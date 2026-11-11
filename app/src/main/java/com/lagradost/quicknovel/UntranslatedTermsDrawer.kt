package com.lagradost.quicknovel

import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.ViewGroup
import android.widget.FrameLayout
import com.lagradost.quicknovel.CommonActivity.showToast
import com.lagradost.quicknovel.databinding.DialogUntranslatedTermBinding
import com.lagradost.quicknovel.databinding.ItemUntranslatedTermBinding
import com.lagradost.quicknovel.databinding.ReadMainBinding
import com.lagradost.quicknovel.util.Coroutines.ioSafe
import com.lagradost.quicknovel.util.ReplaceTiming
import com.lagradost.quicknovel.util.TextReplacement
import com.lagradost.quicknovel.util.TextReplacements
import com.lagradost.quicknovel.util.UIHelper.colorFromAttribute
import com.lagradost.quicknovel.util.UntranslatedTerms
import com.lagradost.quicknovel.util.mergeReplacementRules
import com.lagradost.quicknovel.util.toPx
import kotlin.math.min

/**
 * Menú lateral del lector con los términos que quedaron sin traducir en el capítulo actual.
 * Se abre deslizando de izquierda a derecha sobre el texto. Cada término se muestra con su
 * traducción al español (propuesta del motor activo o la que el usuario escribió); al aceptar,
 * se añade como sustitución "después de traducir" en la pestaña Sustituir del libro y el
 * capítulo se corrige en el momento (la traducción viene de caché: solo se re-aplican reglas).
 */
class UntranslatedTermsDrawer(
    private val activity: ReadActivity2,
    private val binding: ReadMainBinding,
    private val viewModel: ReadActivityViewModel,
) {
    /** Aviso al lector cuando el panel se abre/cierra (para el botón atrás). */
    var onStateChanged: ((Boolean) -> Unit)? = null

    var isOpen = false
        private set

    private enum class TermState {
        /** buscando propuesta con el motor activo */
        LOADING,

        /** propuesta lista, aún sin aceptar */
        PROPOSED,

        /** el motor no la sabe traducir: el usuario escribe la suya */
        NONE,

        /** ya aceptada: hay una regla en Sustituir */
        SAVED,
    }

    private class TermRow(
        val term: String,
        val count: Int,
        var state: TermState,
        var translation: String?,
    )

    private val rows = ArrayList<TermRow>()

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        inner class Holder(val item: ItemUntranslatedTermBinding) :
            RecyclerView.ViewHolder(item.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return Holder(
                ItemUntranslatedTermBinding.inflate(
                    activity.layoutInflater, parent, false
                )
            )
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            val item = (holder as Holder).item

            item.untranslatedTermText.text = row.term
            item.untranslatedTermCount.text =
                activity.getString(R.string.untranslated_count_format, row.count)

            item.untranslatedTermTranslation.text = when (row.state) {
                TermState.LOADING -> activity.getString(R.string.untranslated_searching)
                TermState.PROPOSED -> activity.getString(R.string.untranslated_proposal, row.translation)
                TermState.NONE -> activity.getString(R.string.untranslated_write)
                TermState.SAVED -> activity.getString(R.string.untranslated_saved_row, row.translation)
            }
            item.untranslatedTermTranslation.setTextColor(
                if (row.state == TermState.SAVED || row.state == TermState.PROPOSED)
                    activity.colorFromAttribute(R.attr.textColor)
                else
                    activity.colorFromAttribute(R.attr.grayTextColor)
            )

            // ya aceptada: marca verde y nada que editar (se puede cambiar en Ajustes → Sustituir)
            item.untranslatedTermState.setImageResource(
                if (row.state == TermState.SAVED) R.drawable.ic_baseline_check_24
                else R.drawable.ic_baseline_edit_24
            )
            item.untranslatedTermState.setColorFilter(
                activity.colorFromAttribute(
                    if (row.state == TermState.SAVED) R.attr.colorPrimary else R.attr.grayTextColor
                )
            )

            item.root.setOnClickListener { showEditDialog(holder.bindingAdapterPosition) }
        }

        // el estado cambia por fila (propuesta llegada, término aceptado)
        override fun getItemId(position: Int): Long = rows[position].term.hashCode().toLong()
    }.apply {
        setHasStableIds(true)
    }

    // la reaplicación se difiere un poco por si se aceptan varios términos seguidos
    private val applyHandler = Handler(Looper.getMainLooper())
    private var applyRunnable: Runnable? = null

    fun setup() {
        binding.apply {
            untranslatedList.layoutManager = LinearLayoutManager(activity)
            untranslatedList.adapter = adapter
            untranslatedScrim.setOnClickListener { close() }
            untranslatedClose.setOnClickListener { close() }
        }
    }

    fun open() {
        if (isOpen) return
        isOpen = true
        onStateChanged?.invoke(true)

        // mientras se calcula, la lista queda en blanco con el mensaje de búsqueda
        rows.clear()
        adapter.notifyDataSetChanged()
        setEmpty(activity.getString(R.string.untranslated_searching))
        binding.untranslatedSubtitle.text = currentSubtitle()

        showPanel()

        ioSafe {
            val texts = viewModel.currentChapterTexts()
            val terms = texts?.let { (original, translated) ->
                UntranslatedTerms.find(original, translated, viewModel.mlToLanguage)
            } ?: emptyList()

            activity.runOnUiThread {
                if (!isOpen) return@runOnUiThread
                if (texts == null) {
                    setEmpty(activity.getString(R.string.untranslated_not_translated))
                    return@runOnUiThread
                }
                if (terms.isEmpty()) {
                    setEmpty(activity.getString(R.string.untranslated_none))
                    return@runOnUiThread
                }
                rows.clear()
                rows.addAll(terms.map { TermRow(it.term, it.count, TermState.LOADING, null) })
                binding.untranslatedEmpty.isVisible = false
                adapter.notifyDataSetChanged()
                fetchSuggestions()
            }
        }
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        onStateChanged?.invoke(false)

        binding.untranslatedDrawer.animate()
            .translationX(-binding.untranslatedDrawer.width.toFloat())
            .setDuration(200L)
            .withEndAction { binding.untranslatedDrawerRoot.isVisible = false }
            .start()
        binding.untranslatedScrim.animate().alpha(0f).setDuration(200L).start()
    }

    private fun currentSubtitle(): String {
        val chapter = viewModel.chapterTile.value
        if (chapter != null) return chapter.asString(activity)
        return viewModel.title.value ?: ""
    }

    private fun setEmpty(text: String) {
        binding.untranslatedEmpty.text = text
        binding.untranslatedEmpty.isVisible = true
    }

    private fun showPanel() {
        // ancho: 320dp como máximo, sin salirse de la pantalla
        val width = min(320.toPx, (activity.resources.displayMetrics.widthPixels * 0.85f).toInt())
        binding.untranslatedDrawer.layoutParams =
            (binding.untranslatedDrawer.layoutParams as FrameLayout.LayoutParams).apply {
                this.width = width
            }
        // ya colocado fuera de la pantalla, sin parpadeo al hacerse visible
        binding.untranslatedDrawer.translationX = -width.toFloat()
        binding.untranslatedScrim.alpha = 0f
        binding.untranslatedDrawerRoot.isVisible = true

        binding.untranslatedDrawer.post {
            binding.untranslatedDrawer.animate().translationX(0f).setDuration(220L).start()
            binding.untranslatedScrim.animate().alpha(1f).setDuration(220L).start()
        }
    }

    /** Traduce todos los términos pendientes en un solo lote con el motor activo. */
    private fun fetchSuggestions() {
        val pending = rows.filter { it.state == TermState.LOADING }.map { it.term }
        if (pending.isEmpty()) return
        ioSafe {
            val suggestions = viewModel.suggestTermTranslations(pending)
            activity.runOnUiThread {
                if (!isOpen) return@runOnUiThread
                rows.forEachIndexed { index, row ->
                    val proposal = suggestions[row.term]
                    when {
                        proposal != null -> {
                            row.state = TermState.PROPOSED
                            row.translation = proposal
                            adapter.notifyItemChanged(index)
                        }

                        row.state == TermState.LOADING -> {
                            row.state = TermState.NONE
                            adapter.notifyItemChanged(index)
                        }
                    }
                }
            }
        }
    }

    private fun showEditDialog(position: Int) {
        val row = rows.getOrNull(position) ?: return
        val dialogBinding = DialogUntranslatedTermBinding.inflate(activity.layoutInflater)
        dialogBinding.dialogUntranslatedValue.setText(row.translation.orEmpty())

        val dialog = AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setTitle(row.term)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.untranslated_accept, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        dialogBinding.dialogUntranslatedValue.requestFocus()
        dialogBinding.dialogUntranslatedValue.setSelection(
            dialogBinding.dialogUntranslatedValue.text?.length ?: 0
        )

        // botón manual para poder validar sin cerrar el diálogo si falta la traducción
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = dialogBinding.dialogUntranslatedValue.text?.toString()?.trim().orEmpty()
            if (value.isEmpty() || value.equals(row.term, ignoreCase = true)) {
                showToast(R.string.untranslated_need_translation)
                return@setOnClickListener
            }
            dialog.dismiss()
            acceptTerm(position, value)
        }
    }

    /** Guarda la sustitución en la pestaña Sustituir del libro y corrige el capítulo. */
    private fun acceptTerm(position: Int, translation: String) {
        val row = rows.getOrNull(position) ?: return
        val novelName = viewModel.replacementsNovelName() ?: return

        // mismas reglas que la pestaña Sustituir; si el término ya tenía una, se actualiza
        val merged = mergeReplacementRules(
            TextReplacements.get(novelName),
            listOf(
                TextReplacement(
                    row.term,
                    translation.replace('\n', ' '),
                    ReplaceTiming.AFTER_TRANSLATION
                )
            )
        )
        TextReplacements.set(novelName, merged.rules)

        row.state = TermState.SAVED
        row.translation = translation
        adapter.notifyItemChanged(position)

        showToast(activity.getString(R.string.untranslated_saved, row.term, translation))
        scheduleApply()
    }

    /**
     * Aplica las sustituciones aceptadas al capítulo abierto (como al cerrar la pestaña
     * Sustituir), con un pequeño debounce por si se aceptan varios términos seguidos.
     */
    private fun scheduleApply() {
        applyRunnable?.let { applyHandler.removeCallbacks(it) }
        val runnable = Runnable { viewModel.reapplyReplacements() }
        applyRunnable = runnable
        applyHandler.postDelayed(runnable, 700L)
    }
}
