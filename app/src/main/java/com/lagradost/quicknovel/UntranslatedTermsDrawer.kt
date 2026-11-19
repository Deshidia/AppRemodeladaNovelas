package com.lagradost.quicknovel

import android.animation.ValueAnimator
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.core.animation.doOnEnd
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.lagradost.quicknovel.CommonActivity.showToast
import com.lagradost.quicknovel.databinding.DialogUntranslatedTermBinding
import com.lagradost.quicknovel.databinding.ItemUntranslatedTermBinding
import com.lagradost.quicknovel.databinding.ReadBottomSettingsBinding
import com.lagradost.quicknovel.databinding.ReadMainBinding
import com.lagradost.quicknovel.ui.ScrollIndex
import com.lagradost.quicknovel.ui.SearchHighlight
import com.lagradost.quicknovel.util.Coroutines.ioSafe
import com.lagradost.quicknovel.util.ReplaceTiming
import com.lagradost.quicknovel.util.TextReplacement
import com.lagradost.quicknovel.util.TextReplacements
import com.lagradost.quicknovel.util.UIHelper.colorFromAttribute
import com.lagradost.quicknovel.util.UIHelper.hideKeyboard
import com.lagradost.quicknovel.util.UntranslatedTerms
import com.lagradost.quicknovel.util.mergeReplacementRules
import com.lagradost.quicknovel.util.toPx
import kotlin.math.min

/**
 * Menú lateral del lector (sale del borde derecho) con los términos que quedaron sin traducir
 * en el capítulo actual. Se abre deslizando de derecha a izquierda sobre el texto. Cada término
 * se muestra con su traducción al español (propuesta del motor activo o la que el usuario
 * escribió); al aceptar, se añade como sustitución "después de traducir" en la pestaña
 * Sustituir del libro y el capítulo se corrige en el momento (la traducción viene de caché:
 * solo se re-aplican reglas).
 *
 * Además incluye una búsqueda para navegar por el capítulo actual (sin guardar sustituciones):
 * escribir y saltar entre coincidencias con las flechas; todas quedan subrayadas en el texto
 * (cian, distinto del marcador del usuario) y la que se visita lleva tinte. Al navegar, el
 * panel se cierra y una píldora flotante permite seguir avanzando mientras se lee.
 *
 * Abajo del panel, donde estaba el botón, va plegada la propia ventana de Sustituir: una barra
 * fina que solo muestra «Sustituciones de: …» (así la lista de términos queda lo más alta
 * posible). Al tocarla, o deslizar hacia arriba como quien baja el scroll, el editor de reglas
 * de los ajustes del lector se despliega a pantalla completa con una transición suave y se
 * pliega de vuelta con el botón atrás, deslizando hacia abajo o con la flecha de la cabecera.
 * Sus cambios se reaplican al capítulo en caliente.
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

    // búsqueda para navegar por el capítulo: el texto mostrado lo aporta el lector y el
    // salto a cada coincidencia lo hace el lector con su scroll exacto (el del TTS)
    var searchTextProvider: (() -> List<TextSpan>)? = null
    var onSearchNavigate: ((ScrollIndex) -> Unit)? = null

    // subrayado de las coincidencias en el texto (lo pinta TextAdapter/RoundedBgTextView)
    var onSearchHighlight: ((List<SearchHighlight>, SearchHighlight?) -> Unit)? = null

    /** Una coincidencia de búsqueda: span del texto + offset local dentro del span. */
    private class SearchMatch(val span: TextSpan, val local: Int) {
        fun toScrollIndex() = ScrollIndex(span.index, span.innerIndex, span.start + local)
    }

    private var searchMatches = emptyList<SearchMatch>()
    private var searchIndex = -1
    private var lastSearchQuery: String? = null

    // el repintado del subrayado se difiere por si se escriben varias letras seguidas
    private val highlightHandler = Handler(Looper.getMainLooper())
    private var highlightRunnable: Runnable? = null

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
        var example: String? = null,
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

    /** Ventana de Sustituir integrada abajo del panel (null hasta que el libro tiene título). */
    private var replaceTab: ReadReplaceTab? = null

    /** Animación de despliegue/plegado del editor a pantalla completa (null = parado). */
    private var replaceAnim: ValueAnimator? = null

    // gesto en la barra plegada: deslizar hacia arriba (como quien baja el scroll) despliega;
    // el toque simple también despliega (OnClickListener)
    private var barTouchY = 0f
    private var barExpandFired = false

    // gesto en la cabecera a pantalla completa: deslizar hacia abajo pliega de vuelta
    private var headerTouchY = 0f
    private var headerCollapseFired = false

    fun setup() {
        binding.apply {
            untranslatedList.layoutManager = LinearLayoutManager(activity)
            untranslatedList.adapter = adapter
            untranslatedScrim.setOnClickListener { close() }
            untranslatedClose.setOnClickListener { close() }
            untranslatedSearchInput.doAfterTextChanged { editable ->
                runSearch(editable?.toString().orEmpty())
            }
            untranslatedSearchInput.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    navigateSearch(1)
                    true
                } else {
                    false
                }
            }
            untranslatedSearchPrev.setOnClickListener { navigateSearch(-1) }
            untranslatedSearchNext.setOnClickListener { navigateSearch(1) }
            untranslatedSearchPillPrev.setOnClickListener { navigateSearch(-1) }
            untranslatedSearchPillNext.setOnClickListener { navigateSearch(1) }
            untranslatedSearchPillClose.setOnClickListener {
                untranslatedSearchPill.isVisible = false
            }

            // barra plegada de Sustituir: toque o deslizamiento hacia arriba = pantalla completa
            untranslatedReplaceBar.setOnClickListener { expandReplace() }
            untranslatedReplaceBar.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        barTouchY = ev.rawY
                        barExpandFired = false
                        false
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (!barExpandFired && barTouchY - ev.rawY >
                            ViewConfiguration.get(activity).scaledTouchSlop * 2f
                        ) {
                            barExpandFired = true
                            expandReplace()
                        }
                        barExpandFired
                    }

                    else -> barExpandFired
                }
            }

            // cabecera a pantalla completa: toque en la flecha o deslizamiento hacia abajo = plegar
            untranslatedReplaceCollapse.setOnClickListener { collapseReplace() }
            untranslatedReplaceFullHeader.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        headerTouchY = ev.rawY
                        headerCollapseFired = false
                        false
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (!headerCollapseFired && ev.rawY - headerTouchY >
                            ViewConfiguration.get(activity).scaledTouchSlop * 2f
                        ) {
                            headerCollapseFired = true
                            collapseReplace()
                        }
                        headerCollapseFired
                    }

                    else -> headerCollapseFired
                }
            }
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
        // la ventana de Sustituir integrada se monta en cuanto el libro tiene título; sin clave
        // para las sustituciones no se muestra (no habría dónde guardar las reglas)
        if (replaceTab == null) setupReplaceEditor()
        binding.untranslatedReplaceBar.isVisible = replaceTab != null

        // la píldora sobra con el panel abierto; se restaura la última búsqueda
        binding.untranslatedSearchPill.isVisible = false
        binding.untranslatedSearchInput.setText(lastSearchQuery.orEmpty())
        runSearch(lastSearchQuery.orEmpty())

        showPanel()

        ioSafe {
            val texts = viewModel.currentChapterTexts()
            val terms = texts?.let { (original, translated) ->
                UntranslatedTerms.find(original, translated, viewModel.mlToLanguage)
            } ?: emptyList()
            // primera frase del capítulo con cada término, para el diálogo de traducción
            val examples = texts?.second?.let { translated ->
                terms.associate { it.term to UntranslatedTerms.exampleSentence(translated, it.term) }
            } ?: emptyMap()

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
                rows.addAll(terms.map {
                    TermRow(it.term, it.count, TermState.LOADING, null, examples[it.term])
                })
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

        // el editor desplegado no puede quedarse flotando sin el panel
        if (binding.untranslatedReplaceFull.isVisible || replaceAnim != null) {
            replaceAnim = null
            hideReplaceOverlay()
        }

        // al cerrar se quita el foco y el teclado: que no persista sobre el texto
        binding.untranslatedSearchInput.clearFocus()
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.untranslatedSearchInput.windowToken, 0)

        // el panel sale por la derecha, donde está anclado (layout_gravity="end")
        binding.untranslatedDrawer.animate()
            .translationX(binding.untranslatedDrawer.width.toFloat())
            .setDuration(200L)
            .withEndAction { binding.untranslatedDrawerRoot.isVisible = false }
            .start()
        binding.untranslatedScrim.animate().alpha(0f).setDuration(200L).start()
    }

    /**
     * Pega la ventana de Sustituir a la capa a pantalla completa del layout raíz: se infla el
     * layout de los ajustes del lector, se despega solo su bloque de sustituciones y se añade
     * al host de la capa. Mientras está plegada, la capa no se ve y el panel solo muestra la
     * barra con el título. Cada cambio en el editor reaplica las reglas al capítulo (mismo
     * debounce que al aceptar un término del panel).
     */
    private fun setupReplaceEditor() {
        val novelName = viewModel.replacementsNovelName() ?: return

        val settingsBinding = ReadBottomSettingsBinding.inflate(activity.layoutInflater)
        // solo el bloque de sustituciones: se despega del resto del layout de ajustes
        val container = settingsBinding.tabReplaceContainer
        (container.parent as? ViewGroup)?.removeView(container)
        container.isVisible = true
        // el título del editor ya lo pintan la barra del panel y la cabecera de la capa
        settingsBinding.readReplaceNovel.isVisible = false
        binding.untranslatedReplaceFullHost.addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val title = activity.getString(R.string.replace_reader_novel, novelName)
        binding.untranslatedReplaceBarTitle.text = title
        binding.untranslatedReplaceFullTitle.text = title

        replaceTab = ReadReplaceTab(activity, settingsBinding, novelName) {
            scheduleApply()
        }.apply { setup() }
    }

    /**
     * Despliega Sustituir a pantalla completa con una transición suave: la capa nace con el
     * tamaño y la esquina de la barra del panel (abajo a la derecha) y crece hasta cubrir
     * todo, como una hoja que se abre hacia arriba.
     */
    private fun expandReplace() {
        val overlay = binding.untranslatedReplaceFull
        // con el panel ya cerrándose no se despliega: se quedaría flotando sin el panel
        if (replaceTab == null || !isOpen || replaceAnim != null || overlay.isVisible) return
        val root = overlay.parent as ViewGroup

        // punto de partida: la barra del panel (mismo ancho que el panel, mismo alto que la barra)
        val barHeight = binding.untranslatedReplaceBar.height.takeIf { it > 0 } ?: 40.toPx
        val drawerWidth = binding.untranslatedDrawer.width.takeIf { it > 0 }
            ?: min(320.toPx, (activity.resources.displayMetrics.widthPixels * 0.85f).toInt())
        val fullWidth = root.width
        val fullHeight = root.height
        if (fullWidth <= 0 || fullHeight <= 0) return

        overlay.layoutParams = (overlay.layoutParams as FrameLayout.LayoutParams).apply {
            width = drawerWidth
            height = barHeight
            gravity = Gravity.BOTTOM or Gravity.END
        }
        overlay.isVisible = true

        replaceAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { animator ->
                if (animator !== replaceAnim) return@addUpdateListener
                val t = animator.animatedValue as Float
                overlay.layoutParams =
                    (overlay.layoutParams as FrameLayout.LayoutParams).apply {
                        width = (drawerWidth + (fullWidth - drawerWidth) * t).toInt()
                        height = (barHeight + (fullHeight - barHeight) * t).toInt()
                    }
            }
            doOnEnd {
                if (it !== replaceAnim) return@doOnEnd
                replaceAnim = null
                overlay.layoutParams =
                    (overlay.layoutParams as FrameLayout.LayoutParams).apply {
                        width = ViewGroup.LayoutParams.MATCH_PARENT
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                    }
            }
            start()
        }
    }

    /**
     * Plega el editor de vuelta a la barra del panel, con la animación inversa (o sin ella si
     * venía a medio camino). Devuelve true si la capa estaba abierta: el botón atrás la usa
     * para plegar el editor antes de cerrar el panel.
     */
    fun collapseReplace(): Boolean {
        val overlay = binding.untranslatedReplaceFull
        val midAnimation = replaceAnim != null
        if (!midAnimation && !overlay.isVisible) return false

        val barHeight = binding.untranslatedReplaceBar.height.takeIf { it > 0 } ?: 40.toPx
        val drawerWidth = binding.untranslatedDrawer.width.takeIf { it > 0 }
            ?: min(320.toPx, (activity.resources.displayMetrics.widthPixels * 0.85f).toInt())
        if (midAnimation || barHeight <= 0 || overlay.height <= 0 || overlay.width <= 0) {
            // sin condiciones para animar: plegado seco, sin saltos raros a medio camino
            replaceAnim = null
            hideReplaceOverlay()
            return true
        }

        val startWidth = overlay.width
        val startHeight = overlay.height
        replaceAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { animator ->
                if (animator !== replaceAnim) return@addUpdateListener
                val t = animator.animatedValue as Float
                overlay.layoutParams =
                    (overlay.layoutParams as FrameLayout.LayoutParams).apply {
                        width = (startWidth + (drawerWidth - startWidth) * t).toInt()
                        height = (startHeight + (barHeight - startHeight) * t).toInt()
                    }
            }
            doOnEnd {
                if (it !== replaceAnim) return@doOnEnd
                hideReplaceOverlay()
            }
            start()
        }
        return true
    }

    /** Oculta la capa a pantalla completa y deja su tamaño listo para el próximo despliegue. */
    private fun hideReplaceOverlay() {
        replaceAnim = null
        val overlay = binding.untranslatedReplaceFull
        if (overlay.isVisible) {
            overlay.isVisible = false
            // el teclado se va con el editor: que no quede flotando sobre el texto
            hideKeyboard(binding.untranslatedReplaceBar)
        }
        overlay.layoutParams = (overlay.layoutParams as FrameLayout.LayoutParams).apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
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
        // ya colocado fuera de la pantalla (por la derecha), sin parpadeo al hacerse visible
        binding.untranslatedDrawer.translationX = width.toFloat()
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

    /** Busca el texto en los spans mostrados del capítulo y actualiza el contador. */
    private fun runSearch(query: String) {
        lastSearchQuery = query
        val trimmed = query.trim()
        searchMatches = if (trimmed.isEmpty()) emptyList() else findMatches(trimmed)
        searchIndex = -1
        binding.untranslatedSearchCount.isVisible = trimmed.isNotEmpty()
        if (trimmed.isNotEmpty()) updateSearchCount()
        // el subrayado se difiere un poco para no repintar el texto en cada pulsación
        postSearchHighlights(250L)
    }

    /** Envía al lector las coincidencias a subrayar (todas, y cuál es la que se visita). */
    private fun postSearchHighlights(delayMs: Long = 0L) {
        highlightRunnable?.let { highlightHandler.removeCallbacks(it) }
        val runnable = Runnable { doPostSearchHighlights() }
        highlightRunnable = runnable
        if (delayMs > 0) highlightHandler.postDelayed(runnable, delayMs) else highlightHandler.post(runnable)
    }

    private fun doPostSearchHighlights() {
        val query = lastSearchQuery?.trim().orEmpty()
        if (query.isEmpty() || searchMatches.isEmpty()) {
            onSearchHighlight?.invoke(emptyList(), null)
            return
        }
        val current = searchMatches.getOrNull(searchIndex)
        onSearchHighlight?.invoke(
            searchMatches.map { SearchHighlight(it.span.innerIndex, it.local, it.local + query.length) },
            current?.let { SearchHighlight(it.span.innerIndex, it.local, it.local + query.length) }
        )
    }

    private fun updateSearchCount() {
        binding.untranslatedSearchCount.text = when {
            searchMatches.isEmpty() -> activity.getString(R.string.untranslated_search_none)
            searchIndex >= 0 -> activity.getString(
                R.string.untranslated_search_progress,
                searchIndex + 1,
                searchMatches.size
            )
            else -> searchMatches.size.toString()
        }
    }

    /** Coincidencias (sin importar mayúsculas) sobre el texto mostrado del capítulo. */
    private fun findMatches(query: String): List<SearchMatch> {
        val spans = searchTextProvider?.invoke().orEmpty()
        val matches = ArrayList<SearchMatch>()
        for (span in spans) {
            val text = span.text.toString()
            var from = 0
            while (true) {
                val at = text.indexOf(query, from, ignoreCase = true)
                if (at < 0) break
                matches.add(SearchMatch(span, at))
                from = at + query.length
            }
            if (matches.size > 5000) break // red de seguridad ante capítulos patológicos
        }
        return matches
    }

    /**
     * Salta a la coincidencia anterior/siguiente (con vuelta al inicio/fin). Recalcula antes
     * por si el texto cambió (sustituciones aceptadas, capítulo nuevo). Con el panel abierto
     * lo cierra y deja una píldora flotante para seguir navegando mientras se lee.
     */
    private fun navigateSearch(delta: Int) {
        val query = lastSearchQuery?.trim().orEmpty()
        if (query.isEmpty()) return

        val matches = findMatches(query)
        if (matches.isEmpty()) {
            searchMatches = emptyList()
            searchIndex = -1
            updateSearchCount()
            showToast(R.string.untranslated_search_none)
            return
        }

        val prevChar = searchMatches.getOrNull(searchIndex)?.let { it.span.start + it.local }
        searchMatches = matches
        searchIndex = if (prevChar == null) {
            if (delta > 0) 0 else matches.size - 1
        } else {
            val kept = matches.indexOfFirst { it.span.start + it.local >= prevChar }
                .let { if (it < 0) 0 else it }
            val next = kept + delta
            when {
                next >= matches.size -> 0
                next < 0 -> matches.size - 1
                else -> next
            }
        }

        updateSearchCount()
        postSearchHighlights()
        onSearchNavigate?.invoke(searchMatches[searchIndex].toScrollIndex())
        if (isOpen) close()
        showPill()
    }

    private fun showPill() {
        binding.untranslatedSearchPillCount.text = activity.getString(
            R.string.untranslated_search_progress,
            searchIndex + 1,
            searchMatches.size
        )
        binding.untranslatedSearchPill.isVisible = true
    }

    private fun showEditDialog(position: Int) {
        val row = rows.getOrNull(position) ?: return
        val dialogBinding = DialogUntranslatedTermBinding.inflate(activity.layoutInflater)
        dialogBinding.dialogUntranslatedValue.setText(row.translation.orEmpty())

        // contexto: primera frase del capítulo que contiene el término
        val example = row.example
        if (example.isNullOrBlank()) {
            dialogBinding.dialogUntranslatedExample.isVisible = false
        } else {
            dialogBinding.dialogUntranslatedExample.text =
                activity.getString(R.string.untranslated_example, example)
            dialogBinding.dialogUntranslatedExample.isVisible = true
        }

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

        // la ventana integrada pinta ya la regla aceptada
        replaceTab?.refresh()

        // el texto va a cambiar (reapplyReplacements): quita el subrayado de la búsqueda,
        // que dejaría de coincidir con las posiciones; se recalcula al navegar de nuevo
        searchMatches = emptyList()
        searchIndex = -1
        postSearchHighlights()

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
