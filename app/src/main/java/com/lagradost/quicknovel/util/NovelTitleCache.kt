package com.lagradost.quicknovel.util

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.getKeys
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.DOWNLOAD_FOLDER
import com.lagradost.quicknovel.EPUB_CURRENT_POSITION
import com.lagradost.quicknovel.HISTORY_FOLDER
import com.lagradost.quicknovel.NOVEL_TEXT_REPLACEMENTS
import com.lagradost.quicknovel.RESULT_BOOKMARK
import com.lagradost.quicknovel.ui.download.DownloadFragment

const val NOVEL_TITLE_CACHE: String = "novel_title_cache"

/** Envoltorio para poder guardar el título con getKey/setKey sin perder el tipo. */
data class NovelTitle(
    @JsonProperty("title")
    val title: String,
)

/**
 * Decide qué nombre se queda como identidad de la novela cuando aún no hay nada guardado en
 * [NOVEL_TITLE_CACHE]. Es lógica pura (sin Android) para poder probarla en la JVM.
 *
 * El nombre de la novela es la clave de TODO su estado: progreso de lectura
 * ([EPUB_CURRENT_POSITION] y compañía), reglas "Sustituir" ([NOVEL_TEXT_REPLACEMENTS]),
 * subrayado, ajustes de traducción e incluso el fichero `<nombre>.epub` de Descargas. Si en una
 * sesión la traducción del título falla y en otra no, la novela "cambia de nombre": el lector
 * abriría en un capítulo antiguo y "Sustituir" aparecería vacío hasta que volviera el nombre
 * bueno. Por eso el título se elige UNA vez y se congela.
 *
 * Orden de preferencia:
 *  1. lo que hubiera en la caché (llamada solo con `cachedTitle = null` la primera vez);
 *  2. el nombre de una tarjeta ya guardada (biblioteca/descargas/historial) si bajo ese nombre
 *     ya hay progreso o reglas: es la identidad real del usuario;
 *  3. el título recién traducido si bajo él ya hay datos (p. ej. usuario antiguo cuyas tarjetas
 *     se escribieron en una sesión fallida);
 *  4. el nombre guardado en la tarjeta aunque no tenga datos (continuidad con lo que ya ve el
 *     usuario en su biblioteca);
 *  5. el título traducido (estreno normal: se traduce y se congela);
 *  6. el título original si bajo él hay datos y nada de lo anterior existía;
 *  7. `null` si no hay nada decidible (traducción fallida y novela nueva): se deja el título
 *     sin cachear para volver a intentarlo en la siguiente carga, sin haber creado datos aún.
 */
internal fun chooseStableTitle(
    cachedTitle: String?,
    storedCardTitle: String?,
    storedCardHasData: Boolean,
    translatedTitle: String?,
    translatedHasData: Boolean,
    fallbackHasData: Boolean,
    fallback: String,
): String? {
    if (!cachedTitle.isNullOrBlank()) return cachedTitle

    if (storedCardTitle != null && storedCardHasData) return storedCardTitle
    if (translatedTitle != null && translatedHasData) return translatedTitle
    if (fallbackHasData) return fallback
    if (!storedCardTitle.isNullOrBlank()) return storedCardTitle
    if (!translatedTitle.isNullOrBlank()) return translatedTitle

    return null
}

object NovelTitleCache {

    private fun path(apiName: String, url: String): String {
        // La url puede contener caracteres raros, pero SharedPreferences acepta cualquier cadena
        // como clave y en ningún sitio se enumeran las entradas de esta carpeta.
        return "$apiName/$url"
    }

    /** Título congelado de la novela cargada desde [url] en [apiName], o null si no hay. */
    fun get(apiName: String, url: String): String? {
        return getKey<NovelTitle>(NOVEL_TITLE_CACHE, path(apiName, url))?.title
    }

    /** Congela [title] como identidad de la novela cargada desde [url] en [apiName]. */
    fun set(apiName: String, url: String, title: String) {
        setKey(NOVEL_TITLE_CACHE, path(apiName, url), NovelTitle(title))
    }

    /**
     * Nombre con el que esta novela ya aparece guardada en las tarjetas de la biblioteca
     * ([RESULT_BOOKMARK]), de descargas ([DOWNLOAD_FOLDER]) o del historial ([HISTORY_FOLDER]).
     * Sirve para que la primera carga tras instalar el cambio reutilice el nombre que el usuario
     * ya tiene, en vez de volver a traducir y moverle los datos. Null si no hay tarjeta.
     * Es lectura de disco: llamar fuera del hilo principal.
     */
    fun storedCardName(apiName: String, vararg urls: String?): String? {
        val candidates = urls.filterNotNull().filter { it.isNotBlank() }.toSet()
        if (candidates.isEmpty()) return null

        fun matches(source: String?): Boolean = source != null && source in candidates

        for (key in getKeys(RESULT_BOOKMARK).orEmpty()) {
            val card = getKey<ResultCached>(key) ?: continue
            if (matches(card.source) && card.apiName == apiName && card.name.isNotBlank()) {
                return card.name
            }
        }
        for (key in getKeys(DOWNLOAD_FOLDER).orEmpty()) {
            val data = getKey<DownloadFragment.DownloadData>(key) ?: continue
            if (matches(data.source) && data.apiName == apiName && data.name.isNotBlank()) {
                return data.name
            }
        }
        for (key in getKeys(HISTORY_FOLDER).orEmpty()) {
            val card = getKey<ResultCached>(key) ?: continue
            if (matches(card.source) && card.apiName == apiName && card.name.isNotBlank()) {
                return card.name
            }
        }
        return null
    }

    /**
     * True si ya existen datos de lectura bajo ese nombre (posición guardada o reglas de
     * "Sustituir"): significa que ese nombre ya fue la identidad de la novela de verdad.
     * Es lectura de disco: llamar fuera del hilo principal.
     */
    fun hasReaderData(novelName: String): Boolean {
        if (novelName.isBlank()) return false
        if (getKey<Int>(EPUB_CURRENT_POSITION, novelName) != null) return true
        return TextReplacements.get(novelName).isNotEmpty()
    }
}
