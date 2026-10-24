package com.lagradost.quicknovel

import android.app.Activity
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import com.lagradost.quicknovel.CommonActivity.showToast
import com.lagradost.quicknovel.databinding.ReadBottomSettingsBinding
import com.lagradost.quicknovel.databinding.ResultReplaceItemBinding
import com.lagradost.quicknovel.ui.result.NovelPickerEntry
import com.lagradost.quicknovel.ui.result.showNovelPickerDialog
import com.lagradost.quicknovel.util.Coroutines.ioSafe
import com.lagradost.quicknovel.util.ReplaceTiming
import com.lagradost.quicknovel.util.TextReplacement
import com.lagradost.quicknovel.util.TextReplacements
import com.lagradost.quicknovel.util.UIHelper.hideKeyboard
import com.lagradost.quicknovel.util.mergeReplacementRules

/**
 * Pestaña "Sustituir" de los ajustes rápidos del lector. Edita las mismas reglas que la pestaña
 * Sustituir de la ficha de la novela, pero desde el propio lector: así también se pueden usar
 * con los EPUBs de Descargas, que no tienen ficha. Las reglas se guardan con [novelName]
 * (el título del libro abierto), que es la clave que el lector usa para aplicarlas.
 *
 * [onChanged] se llama cada vez que se añade, edita, borra o importa una regla; el lector
 * decide cuándo repintar el texto (al cerrar los ajustes).
 */
class ReadReplaceTab(
    private val activity: Activity,
    private val binding: ReadBottomSettingsBinding,
    private val novelName: String,
    private val onChanged: () -> Unit,
) {
    private var rules: List<TextReplacement> = TextReplacements.get(novelName)

    /** Índice de la regla que se está editando (null = se está creando una nueva). */
    private var editing: Int? = null

    fun setup() {
        binding.apply {
            readReplaceNovel.text = activity.getString(R.string.replace_reader_novel, novelName)

            readReplaceOriginalInput.doOnTextChanged { _, _, _, _ ->
                readReplaceOriginalLayout.error = null
                readReplaceReplacementLayout.error = null
            }
            readReplaceReplacementInput.doOnTextChanged { _, _, _, _ ->
                readReplaceReplacementLayout.error = null
            }

            readReplaceCancelBtn.setOnClickListener {
                resetForm()
                hideKeyboard(it)
            }

            readReplaceCopyToBtn.setOnClickListener { showCopyDialog() }
            readReplaceImportBtn.setOnClickListener { showImportDialog() }

            readReplaceAddBtn.setOnClickListener {
                val original = readReplaceOriginalInput.text?.toString().orEmpty()
                // un salto de línea desalinearía los párrafos al traducir
                val replacement =
                    readReplaceReplacementInput.text?.toString().orEmpty().replace('\n', ' ')

                if (original.isEmpty()) {
                    readReplaceOriginalLayout.error =
                        activity.getString(R.string.replace_original_required)
                    return@setOnClickListener
                }
                if (original == replacement) {
                    readReplaceReplacementLayout.error =
                        activity.getString(R.string.replace_same_text)
                    return@setOnClickListener
                }

                val timing =
                    if (readReplaceTimingBefore.isChecked) ReplaceTiming.BEFORE_TRANSLATION
                    else ReplaceTiming.AFTER_TRANSLATION

                val rule = TextReplacement(original, replacement, timing)
                val list = rules.toMutableList()
                val index = editing
                if (index != null && index in list.indices) {
                    list[index] = rule
                } else {
                    list.add(rule)
                }
                save(list)
                resetForm()
                hideKeyboard(it)
            }
        }
        render()
    }

    private fun save(list: List<TextReplacement>) {
        rules = list
        TextReplacements.set(novelName, list)
        render()
        onChanged()
    }

    private fun resetForm() {
        binding.apply {
            editing = null
            readReplaceOriginalInput.setText("")
            readReplaceReplacementInput.setText("")
            readReplaceOriginalLayout.error = null
            readReplaceReplacementLayout.error = null
            readReplaceAddBtn.setText(R.string.replace_add)
            readReplaceAddBtn.setIconResource(R.drawable.ic_baseline_add_24)
            readReplaceCancelBtn.isVisible = false
        }
    }

    private fun render() {
        binding.apply {
            readReplaceList.removeAllViews()
            readReplaceEmpty.isVisible = rules.isEmpty()
            // sin reglas no hay nada que copiar
            readReplaceCopyToBtn.isEnabled = rules.isNotEmpty()
            readReplaceCopyToBtn.alpha = if (rules.isEmpty()) 0.4f else 1f

            rules.forEachIndexed { index, rule ->
                val item = ResultReplaceItemBinding.inflate(
                    activity.layoutInflater, readReplaceList, false
                )
                val replacementText =
                    rule.replacement.ifEmpty { activity.getString(R.string.replace_item_empty_value) }
                item.replaceItemText.text = "${rule.original}  →  $replacementText"
                item.replaceItemTiming.setText(
                    if (rule.timing == ReplaceTiming.BEFORE_TRANSLATION) R.string.replace_before
                    else R.string.replace_after
                )

                // tocar una regla la carga en el formulario para editarla
                item.root.setOnClickListener {
                    editing = index
                    readReplaceOriginalInput.setText(rule.original)
                    readReplaceReplacementInput.setText(rule.replacement)
                    if (rule.timing == ReplaceTiming.BEFORE_TRANSLATION) {
                        readReplaceTimingBefore.isChecked = true
                    } else {
                        readReplaceTimingAfter.isChecked = true
                    }
                    readReplaceAddBtn.setText(R.string.replace_save)
                    readReplaceAddBtn.icon = null
                    readReplaceCancelBtn.isVisible = true
                    readReplaceOriginalInput.requestFocus()
                    readReplaceOriginalInput.setSelection(
                        readReplaceOriginalInput.text?.length ?: 0
                    )
                }

                item.replaceItemDelete.setOnClickListener {
                    val current = editing
                    if (current == index) {
                        resetForm()
                    } else if (current != null && current > index) {
                        // al borrar una regla anterior, la que se edita sube una posición
                        editing = current - 1
                    }
                    val list = rules.toMutableList()
                    if (index in list.indices) {
                        list.removeAt(index)
                        save(list)
                    }
                }

                readReplaceList.addView(item.root)
            }
        }
    }

    /** "Copiar a…": añade estas reglas a otras novelas de la biblioteca. */
    private fun showCopyDialog() {
        if (rules.isEmpty()) return
        val snapshot = rules

        // leer la biblioteca recorre muchas claves: fuera del hilo principal
        ioSafe {
            val withRules = TextReplacements.novelsWithRules()
            val entries = TextReplacements.libraryNovelNames()
                .filter { it != novelName }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .map { name ->
                    NovelPickerEntry(name, withRules[name]?.let { "$name ($it)" } ?: name)
                }

            activity.runOnUiThread {
                if (entries.isEmpty()) {
                    showToast(R.string.replace_copy_no_novels)
                    return@runOnUiThread
                }
                activity.showNovelPickerDialog(
                    activity.getString(R.string.replace_copy_title),
                    entries
                ) { targets ->
                    if (targets.isEmpty()) return@showNovelPickerDialog

                    val changed = TextReplacements.copyTo(targets, snapshot)
                    if (changed == 0) {
                        showToast(R.string.replace_copy_nothing_new)
                    } else {
                        showToast(
                            activity.resources.getQuantityString(
                                R.plurals.replace_copy_done, changed, changed
                            )
                        )
                    }
                }
            }
        }
    }

    /** "Importar de…": trae reglas de otras novelas de la biblioteca que ya las tengan. */
    private fun showImportDialog() {
        ioSafe {
            val counts = TextReplacements.novelsWithRules()
            val entries = TextReplacements.libraryNovelNames()
                .filter { it != novelName && it in counts }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .map { name -> NovelPickerEntry(name, "$name (${counts[name]})") }

            activity.runOnUiThread {
                if (entries.isEmpty()) {
                    showToast(R.string.replace_import_no_novels)
                    return@runOnUiThread
                }
                activity.showNovelPickerDialog(
                    activity.getString(R.string.replace_import_title),
                    entries
                ) { sources ->
                    val incoming = sources.flatMap { TextReplacements.get(it) }
                    if (incoming.isEmpty()) return@showNovelPickerDialog

                    val result = mergeReplacementRules(rules, incoming)
                    if (result.changed) {
                        // si se estaba editando una regla, sus posiciones no cambian: el merge
                        // solo actualiza en el sitio o añade al final
                        save(result.rules)
                        showToast(
                            activity.getString(
                                R.string.replace_import_done, result.added, result.updated
                            )
                        )
                    } else {
                        showToast(R.string.replace_import_nothing_new)
                    }
                }
            }
        }
    }
}
