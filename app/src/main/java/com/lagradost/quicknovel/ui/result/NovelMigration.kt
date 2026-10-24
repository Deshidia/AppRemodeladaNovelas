package com.lagradost.quicknovel.ui.result

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import com.lagradost.quicknovel.BaseApplication.Companion.context
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.removeKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.DataStore.getFolderName
import com.lagradost.quicknovel.DataStore.getSharedPrefs
import com.lagradost.quicknovel.EPUB_CURRENT_POSITION
import com.lagradost.quicknovel.EPUB_CURRENT_POSITION_CHAPTER
import com.lagradost.quicknovel.EPUB_CURRENT_POSITION_READ_AT
import com.lagradost.quicknovel.EPUB_CURRENT_POSITION_SCROLL_CHAR
import com.lagradost.quicknovel.HISTORY_FOLDER
import com.lagradost.quicknovel.MainActivity.Companion.navigate
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.RESULT_BOOKMARK
import com.lagradost.quicknovel.ui.LibraryListManager
import com.lagradost.quicknovel.ui.search.SearchFragment
import com.lagradost.quicknovel.util.ResultCached

/**
 * La novela "de origen" cuando se busca la misma obra en otros proveedores (pulsación larga en el
 * título de la ficha). Viaja en los argumentos de la búsqueda y de las fichas que se abren desde
 * ella, y es lo que permite ofrecer "duplicar o migrar" al guardar la nueva en marcadores.
 *
 * @param id id de la novela (el de [com.lagradost.quicknovel.BookDownloader2Helper.generateId]).
 * @param name nombre con el que se guardó su progreso. Es el título ya traducido, porque el
 * progreso de lectura está indexado por nombre y no por id.
 * @param apiName proveedor de la novela de origen.
 */
data class MigrationSource(
    val id: Int,
    val name: String,
    val apiName: String
) {
    fun writeTo(bundle: Bundle) {
        bundle.putInt(ARG_ID, id)
        bundle.putString(ARG_NAME, name)
        bundle.putString(ARG_API, apiName)
    }

    companion object {
        private const val ARG_ID = "migrateFromId"
        private const val ARG_NAME = "migrateFromName"
        private const val ARG_API = "migrateFromApi"

        fun readFrom(bundle: Bundle?): MigrationSource? {
            if (bundle == null || !bundle.containsKey(ARG_ID)) return null
            val name = bundle.getString(ARG_NAME) ?: return null
            val apiName = bundle.getString(ARG_API) ?: return null
            return MigrationSource(bundle.getInt(ARG_ID), name, apiName)
        }
    }
}

object NovelMigration {

    /**
     * Título original (sin traducir) guardado para la novela [id], sea de la biblioteca o del
     * historial. Sirve para las tarjetas que se abren sin cargar la ficha (p. ej. cuando la web
     * no responde). Null si no hay nada guardado o la novela no se tradujo.
     */
    fun savedOriginalName(id: Int): String? {
        val key = id.toString()
        return (getKey<ResultCached>(RESULT_BOOKMARK, key)?.originalName
            ?: getKey<ResultCached>(HISTORY_FOLDER, key)?.originalName)
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Abre la búsqueda avanzada con [originalTitle] (el título SIN traducir, que es el que usan
     * las fuentes), recordando [source] como novela de origen por si luego se migra a otra
     * fuente. Devuelve false si no se pudo abrir (sin actividad o consulta demasiado corta).
     */
    fun searchByOriginalTitle(
        activity: Activity?,
        originalTitle: String,
        source: MigrationSource?
    ): Boolean {
        val query = originalTitle.trim()
        // SearchViewModel ignora las consultas de un solo carácter
        if (query.length <= 1 || activity == null) return false
        activity.navigate(
            R.id.navigation_search,
            SearchFragment.newInstance(query, source)
        )
        return true
    }

    /** Último capítulo leído de la novela [novelName] (1 = el primero), o 0 si no hay progreso. */
    fun lastChapterRead(novelName: String): Int {
        return getKey<Int>(EPUB_CURRENT_POSITION, novelName)?.let { it + 1 } ?: 0
    }

    /**
     * Copia el progreso de lectura de [fromName] a [toName]. El progreso está indexado por el
     * nombre de la novela, no por su id, por eso hay que copiar claves y no basta con mover la
     * novela de lista.
     *
     * Se copia el capítulo por el que ibas (índice y nombre: el lector busca primero por nombre y
     * cae al índice, así que en un proveedor con otra numeración encuentra el mismo capítulo) y
     * las marcas de "capítulo leído". No se copia la posición dentro del capítulo: el texto de
     * otro proveedor no es idéntico, así que se retoma al principio del capítulo.
     *
     * No borra nada de la novela de origen.
     */
    fun copyProgress(fromName: String, toName: String) {
        // Mismo nombre = mismas claves: ya comparten el progreso.
        if (fromName == toName) return

        val position = getKey<Int>(EPUB_CURRENT_POSITION, fromName)
        if (position != null) {
            setKey(EPUB_CURRENT_POSITION, toName, position)

            val chapterName = getKey<String>(EPUB_CURRENT_POSITION_CHAPTER, fromName)
            if (chapterName != null) {
                setKey(EPUB_CURRENT_POSITION_CHAPTER, toName, chapterName)
            } else {
                // Si el destino tuviera un nombre de capítulo antiguo, el lector lo preferiría
                // al índice que acabamos de copiar.
                removeKey(EPUB_CURRENT_POSITION_CHAPTER, toName)
            }
            setKey(EPUB_CURRENT_POSITION_SCROLL_CHAR, toName, 0)
        }

        copyReadMarks(fromName, toName)
    }

    private fun copyReadMarks(fromName: String, toName: String) {
        val prefs = context?.getSharedPrefs() ?: return
        val copies = remapReadMarks(
            prefs.all,
            fromPrefix = getFolderName(EPUB_CURRENT_POSITION_READ_AT, "$fromName/"),
            toPrefix = getFolderName(EPUB_CURRENT_POSITION_READ_AT, "$toName/")
        )
        if (copies.isEmpty()) return

        // Un solo apply() para todas: una novela puede tener miles de capítulos leídos y un
        // apply() por clave reescribiría el fichero de preferencias cada vez.
        prefs.edit {
            for ((key, value) in copies) {
                putString(key, value)
            }
        }
    }

    /**
     * Saca la novela antigua de la biblioteca (todas sus listas y los datos guardados para los
     * marcadores) y de "Historial". NO borra lo descargado: los capítulos bajados siguen ahí y
     * se pueden eliminar aparte desde la propia novela.
     */
    fun removeFromLibrary(source: MigrationSource) {
        LibraryListManager.removeNovel(source.id)
        removeKey(HISTORY_FOLDER, source.id.toString())
    }

    /**
     * Pregunta qué hacer al guardar en marcadores una novela nueva encontrada buscando la
     * [source]: añadirla como duplicado o migrar (sustituir a la antigua conservando el progreso).
     * Cerrar el diálogo o pulsar "Cancelar" no hace nada.
     */
    fun showChoiceDialog(
        context: Context,
        source: MigrationSource,
        newName: String,
        onDuplicate: () -> Unit,
        onMigrate: () -> Unit
    ) {
        val lastRead = lastChapterRead(source.name)

        val message = buildString {
            append(context.getString(R.string.migrate_message, newName, source.name))
            append("\n\n")
            append(context.getString(R.string.migrate_option_duplicate))
            append("\n\n")
            append(context.getString(R.string.migrate_option_migrate, source.name))
            if (lastRead > 0) {
                append(' ')
                append(context.getString(R.string.migrate_progress_kept, lastRead))
            }
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.migrate_title)
            .setMessage(message)
            .setPositiveButton(R.string.migrate_action_migrate) { _, _ -> onMigrate() }
            .setNegativeButton(R.string.migrate_action_duplicate) { _, _ -> onDuplicate() }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }
}
