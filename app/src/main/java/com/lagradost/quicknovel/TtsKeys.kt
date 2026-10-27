package com.lagradost.quicknovel

// Claves de preferencias del TTS de Gemini. Archivo aparte (en vez de DataStore.kt) para que el parche
// no dependa de cómo tengas tu DataStore.kt. Ojo: getKeys() filtra por prefijo, así que ninguna es
// prefijo de otra clave existente.

// Actores del TTS de Gemini: voz de cada personaje (clave = nombre de la novela, igual que NOVEL_TEXT_REPLACEMENTS).
const val NOVEL_TTS_ACTORS: String = "novel_tts_actors"
// Si el audio generado se guarda en <ruta de descarga>/TTS/<novela> (clave = nombre de la novela).
const val NOVEL_TTS_SAVE_AUDIO: String = "novel_tts_save_audio"
// Motor de voz del lector: "system" (Android), "gemini-3.8-flash-tts" o "gemini-3.8-flash-lite-tts".
const val TTS_ENGINE: String = "reader_tts_engine"
// Agrupar varias frases en una sola petición al TTS de Gemini (ahorra cuota). Por defecto activado.
const val TTS_BATCHING: String = "reader_tts_batching"
// Cuota de tu proyecto de Gemini para el TTS (AI Studio > Límites de frecuencia). 0 = sin límite.
const val TTS_RPM_LIMIT: String = "reader_tts_rpm_limit"
const val TTS_RPD_LIMIT: String = "reader_tts_rpd_limit"
// Generar el capítulo entero (una petición grande por actor) antes de leerlo. Por defecto activado.
const val TTS_CHAPTER_MODE: String = "reader_tts_chapter_mode"
// Contador local de peticiones al TTS de hoy ("yyyy-MM-dd|n").
const val TTS_USAGE_TODAY: String = "reader_tts_usage_today"
// Modelo que identifica a los hablantes antes de generar el audio.
const val TTS_ANNOTATOR_MODEL: String = "reader_tts_annotator_model"
