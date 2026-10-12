package com.lagradost.quicknovel

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * El ÚNICO subrayado del usuario en una novela.
 *
 * Se guarda bajo [EPUB_USER_MARK] con el título de la novela como subclave, de modo que crear uno
 * nuevo en esa novela sustituye al anterior y cada novela conserva el suyo. Sirve de marcador de
 * lectura: sobrevive al cierre del lector y, si se activa el TTS, éste empieza exactamente por
 * esta frase.
 *
 * @param bookTitle novela a la que pertenece (informativo; la clave de almacenamiento ya lo lleva)
 * @param chapterIndex capítulo en el que está
 * @param start inicio, en caracteres relativos al capítulo (mismo sistema que [TextSpan.start])
 * @param end final exclusivo, en caracteres relativos al capítulo
 * @param text texto subrayado. Se usa para comprobar que los offsets siguen siendo válidos
 *   (p.ej. si cambia la traducción o las sustituciones del capítulo) y para relocalizar la marca.
 */
data class ReaderMark(
    @JsonProperty("bookTitle")
    val bookTitle: String,
    @JsonProperty("chapterIndex")
    val chapterIndex: Int,
    @JsonProperty("start")
    val start: Int,
    @JsonProperty("end")
    val end: Int,
    @JsonProperty("text")
    val text: String,
)
