package com.example.quicknovel

import com.lagradost.quicknovel.util.chooseStableTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El título de una novela es la clave de todo su estado (progreso, reglas "Sustituir", id,
 * fichero EPUB...). Estas pruebas fijan el orden en que [chooseStableTitle] elige ese título la
 * primera vez (cuando aún no hay nada en la caché), para que una traducción fallida de Google
 * nunca cambie la identidad de una novela que ya tiene datos.
 */
class NovelTitleCacheTest {

    private fun choose(
        cachedTitle: String? = null,
        storedCardTitle: String? = null,
        storedCardHasData: Boolean = false,
        translatedTitle: String? = null,
        translatedHasData: Boolean = false,
        fallbackHasData: Boolean = false,
        fallback: String = "Título original"
    ): String? = chooseStableTitle(
        cachedTitle = cachedTitle,
        storedCardTitle = storedCardTitle,
        storedCardHasData = storedCardHasData,
        translatedTitle = translatedTitle,
        translatedHasData = translatedHasData,
        fallbackHasData = fallbackHasData,
        fallback = fallback
    )

    @Test
    fun cachedTitleAlwaysWins() {
        // Ya hay identidad congelada: ni las tarjetas ni una nueva traducción la cambian.
        assertEquals(
            "Novela (traducida)",
            choose(
                cachedTitle = "Novela (traducida)",
                storedCardTitle = "Otra cosa",
                storedCardHasData = true,
                translatedTitle = "Otra traducción",
                translatedHasData = true
            )
        )
    }

    @Test
    fun storedCardNameWithDataBeatsEverythingFresh() {
        // Usuario antiguo: sus datos (progreso, "Sustituir") viven bajo el nombre de la tarjeta.
        assertEquals(
            "Novela guardada",
            choose(
                storedCardTitle = "Novela guardada",
                storedCardHasData = true,
                translatedTitle = "Traducción de hoy",
                translatedHasData = true
            )
        )
    }

    @Test
    fun translatedNameWithDataBeatsCardWithoutData() {
        // La tarjeta se escribió en una sesión fallida (título sin traducir), pero los datos
        // reales están bajo el título traducido: gana el traducido.
        assertEquals(
            "Traducción buena",
            choose(
                storedCardTitle = "Título sin traducir",
                storedCardHasData = false,
                translatedTitle = "Traducción buena",
                translatedHasData = true
            )
        )
    }

    @Test
    fun originalNameWithDataWinsWhenNothingElseHasData() {
        // Se empezó a leer en una sesión en la que la traducción falló: no se puede cambiar al
        // título traducido sin "perder" ese progreso.
        assertEquals(
            "Título original",
            choose(
                storedCardTitle = null,
                translatedTitle = "Traducción de hoy",
                translatedHasData = false,
                fallbackHasData = true
            )
        )
    }

    @Test
    fun cardNameIsReusedEvenWithoutData() {
        // Continuidad: la biblioteca ya muestra ese nombre, se mantiene aunque aún no haya datos.
        assertEquals(
            "Novela guardada",
            choose(
                storedCardTitle = "Novela guardada",
                translatedTitle = "Traducción de hoy",
                translatedHasData = false
            )
        )
    }

    @Test
    fun freshNovelGetsTheTranslatedTitle() {
        // Estreno normal: novela nueva, sin tarjeta y sin datos -> se traduce y se congela.
        assertEquals(
            "Traducción de hoy",
            choose(
                translatedTitle = "Traducción de hoy"
            )
        )
    }

    @Test
    fun nothingDecidableLeavesItNullSoItIsNotFrozen() {
        // Traducción fallida y novela nueva: null = se conserva el título original SIN congelar,
        // para que la próxima carga (con la traducción disponible) pueda fijarlo bien.
        assertNull(
            choose(
                storedCardTitle = null,
                translatedTitle = null
            )
        )
    }

    @Test
    fun blankNamesAreTreatedAsMissing() {
        // Títulos en blanco (traducción fallida, tarjeta vacía...) cuentan como ausentes.
        assertNull(
            choose(
                cachedTitle = "",
                storedCardTitle = "  ",
                translatedTitle = ""
            )
        )
        // ...pero un nombre guardado con datos bajo él sí se usa aunque venga de una sesión fallida.
        assertEquals(
            "algo",
            choose(
                storedCardTitle = "algo",
                storedCardHasData = true
            )
        )
    }

    @Test
    fun fallbackWithBlankStoredAndTranslatedFallsBackToData() {
        // Ni tarjeta ni traducción, pero hay datos bajo el título original: se conserva.
        assertEquals(
            "Título original",
            choose(
                storedCardTitle = "",
                translatedTitle = null,
                fallbackHasData = true
            )
        )
    }
}
