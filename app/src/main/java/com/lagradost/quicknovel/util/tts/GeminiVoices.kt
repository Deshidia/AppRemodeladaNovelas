package com.lagradost.quicknovel.util.tts

/**
 * Las 30 voces prediseñadas de Gemini TTS (comunes a gemini-3.8-flash-tts y
 * gemini-3.8-flash-lite-tts). El resto (Biblioteca ampliada, Voice design, Voice replication)
 * se identifica con su id y se escribe/elige tal cual en la ficha de actores.
 *
 * El género es el que publica Google para cada voz; sirve solo para que la asignación
 * automática de personajes nuevos no ponga voz de mujer a un hombre.
 */
object GeminiVoices {
    data class Voice(val id: String, val trait: String, val female: Boolean)

    val prebuilt: List<Voice> = listOf(
        Voice("Zephyr", "Bright", true), Voice("Puck", "Upbeat", false),
        Voice("Charon", "Informative", false), Voice("Kore", "Firm", true),
        Voice("Fenrir", "Excitable", false), Voice("Leda", "Youthful", true),
        Voice("Orus", "Firm", false), Voice("Aoede", "Breezy", true),
        Voice("Callirrhoe", "Easy-going", true), Voice("Autonoe", "Bright", true),
        Voice("Enceladus", "Breathy", false), Voice("Iapetus", "Clear", false),
        Voice("Umbriel", "Easy-going", false), Voice("Algieba", "Smooth", false),
        Voice("Despina", "Smooth", true), Voice("Erinome", "Clear", true),
        Voice("Algenib", "Gravelly", false), Voice("Rasalgethi", "Informative", false),
        Voice("Laomedeia", "Upbeat", true), Voice("Achernar", "Soft", true),
        Voice("Alnilam", "Firm", false), Voice("Schedar", "Even", false),
        Voice("Gacrux", "Mature", true), Voice("Pulcherrima", "Forward", true),
        Voice("Achird", "Friendly", false), Voice("Zubenelgenubi", "Casual", false),
        Voice("Vindemiatrix", "Gentle", true), Voice("Sadachbia", "Lively", false),
        Voice("Sadaltager", "Knowledgeable", false), Voice("Sulafat", "Warm", true),
    )

    /** Voz por defecto del narrador impersonal. */
    const val NARRATOR_VOICE = "Charon"

    /** Voz por defecto cuando no se sabe quién habla. */
    const val UNKNOWN_VOICE = "Zubenelgenubi"

    fun label(id: String): String =
        prebuilt.firstOrNull { it.id == id }?.let { "${it.id} — ${it.trait}" } ?: id

    /** Hash estable (String.hashCode no cambia entre ejecuciones ni dispositivos). */
    private fun stableHash(s: String): Int = s.hashCode().let { if (it == Int.MIN_VALUE) 0 else Math.abs(it) }

    /**
     * Elige una voz para un personaje nuevo: prefiere voces del género indicado ("m"/"f") y
     * que no estén ya en uso; si todas lo están se repite alguna (solo hay 30 prediseñadas,
     * para series con muchos personajes conviene asignar voces de la Biblioteca ampliada).
     * Es determinista: el mismo nombre con las mismas voces ocupadas da la misma voz.
     */
    fun suggest(name: String, gender: String?, used: Set<String>): String {
        val byGender = when (gender?.lowercase()) {
            "f" -> prebuilt.filter { it.female }
            "m" -> prebuilt.filter { !it.female }
            else -> prebuilt
        }
        // el narrador y "Desconocido" tienen voz propia: no se reparten a los personajes
        val reserved = setOf(NARRATOR_VOICE, UNKNOWN_VOICE)
        val pool = byGender.filter { it.id !in reserved }.ifEmpty { byGender }
        val free = pool.filter { it.id !in used }
        val candidates = free.ifEmpty { pool }
        return candidates[stableHash(name) % candidates.size].id
    }
}
