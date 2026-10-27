package com.lagradost.quicknovel.util.tts

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.NOVEL_TTS_ACTORS
import com.lagradost.quicknovel.NOVEL_TTS_SAVE_AUDIO

/** Un actor: el nombre de un personaje (o "Narrador") y la voz de Gemini que lo interpreta. */
data class TtsActor(
    @JsonProperty("name") val name: String,
    /** Voz prediseñada ("Kore"), de la Biblioteca ampliada o una creada con Voice design ("voice_..."). */
    @JsonProperty("voice") val voice: String,
    /** true = lo añadió la app al detectarlo en un capítulo y nadie ha elegido su voz aún. */
    @JsonProperty("auto") val auto: Boolean = false,
)

/** Envoltorio: getKey<List<...>> perdería el tipo de los elementos (mismo motivo que NovelReplacementRules). */
data class NovelActors(
    @JsonProperty("actors") val actors: List<TtsActor> = emptyList()
)

/**
 * Actores de cada novela. Clave = nombre de la novela, igual que las sustituciones, así que
 * ficha y lector comparten la misma lista (y entra en las copias de seguridad como el resto
 * del DataStore).
 */
object TtsActors {
    private val memory = HashMap<String, List<TtsActor>>()

    /** Actores guardados, con el Narrador siempre el primero (aunque aún no se haya tocado). */
    @Synchronized
    fun get(novel: String): List<TtsActor> {
        val stored = memory.getOrPut(novel) {
            getKey<NovelActors>(NOVEL_TTS_ACTORS, novel)?.actors ?: emptyList()
        }
        return if (stored.any { it.name == NARRATOR_NAME }) stored
        else listOf(TtsActor(NARRATOR_NAME, GeminiVoices.NARRATOR_VOICE, auto = true)) + stored
    }

    @Synchronized
    fun set(novel: String, actors: List<TtsActor>) {
        // dos actores con el mismo nombre (sin distinguir mayúsculas) serían ambiguos
        val unique = LinkedHashMap<String, TtsActor>()
        for (a in actors) unique.putIfAbsent(a.name.lowercase(), a)
        val list = unique.values.toList()
        memory[novel] = list
        setKey(NOVEL_TTS_ACTORS, novel, NovelActors(list))
    }

    /** Añade o cambia la voz de [name]; la marca como elegida por el usuario. */
    fun setVoice(novel: String, name: String, voice: String) {
        val list = get(novel).toMutableList()
        val i = list.indexOfFirst { it.name.equals(name, true) }
        val actor = TtsActor(if (i >= 0) list[i].name else name, voice, auto = false)
        if (i >= 0) list[i] = actor else list.add(actor)
        set(novel, list)
    }

    fun remove(novel: String, name: String) {
        if (name == NARRATOR_NAME) return
        set(novel, get(novel).filterNot { it.name.equals(name, true) })
    }

    fun voiceFor(novel: String, speaker: String): String {
        get(novel).firstOrNull { it.name.equals(speaker, true) }?.let { return it.voice }
        return when (speaker) {
            NARRATOR_NAME -> GeminiVoices.NARRATOR_VOICE
            UNKNOWN_SPEAKER -> GeminiVoices.UNKNOWN_VOICE
            else -> GeminiVoices.suggest(speaker, null, get(novel).map { it.voice }.toSet())
        }
    }

    /**
     * Registra los hablantes que aparecen en un capítulo y aún no tienen actor, con una voz
     * asignada automáticamente (marcada como auto para que el usuario la revise). Así cada
     * personaje suena igual en todos los capítulos. [found] = nombre -> género ("m"/"f"/null).
     * Devuelve true si se añadió alguno.
     */
    fun ensure(novel: String, found: Map<String, String?>): Boolean {
        val list = get(novel).toMutableList()
        val used = list.map { it.voice }.toMutableSet()
        var changed = false
        for ((name, gender) in found) {
            if (list.any { it.name.equals(name, true) }) continue
            val voice = when (name) {
                NARRATOR_NAME -> GeminiVoices.NARRATOR_VOICE
                UNKNOWN_SPEAKER -> GeminiVoices.UNKNOWN_VOICE
                else -> GeminiVoices.suggest(name, gender, used)
            }
            used.add(voice)
            list.add(TtsActor(name, voice, auto = true))
            changed = true
        }
        if (changed) set(novel, list)
        return changed
    }

    fun saveAudio(novel: String): Boolean = getKey<Boolean>(NOVEL_TTS_SAVE_AUDIO, novel) ?: false

    fun setSaveAudio(novel: String, value: Boolean) = setKey(NOVEL_TTS_SAVE_AUDIO, novel, value)
}
