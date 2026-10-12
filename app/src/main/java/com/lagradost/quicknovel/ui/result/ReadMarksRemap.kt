package com.lagradost.quicknovel.ui.result

/**
 * Lógica pura (sin dependencias de Android, para poder probarla en una JVM normal) de la
 * migración de progreso entre dos novelas.
 *
 * El lector guarda, por cada capítulo leído, una clave `"<carpeta>/<nombre de la novela>/<índice>"`
 * cuyo valor es la hora de lectura. Esta función toma el contenido de las preferencias y devuelve
 * qué claves hay que escribir para que la novela destino tenga las mismas marcas que la origen.
 *
 * @param all contenido completo de las preferencias (clave -> valor guardado).
 * @param fromPrefix prefijo de las claves de la novela origen, incluida la barra final
 * (p. ej. `"reader_epub_position_read/Novela vieja/"`).
 * @param toPrefix prefijo equivalente de la novela destino.
 * @return mapa `claveDestino -> valor`, listo para escribirse tal cual (el valor es el JSON que ya
 * estaba guardado, así que no se vuelve a (de)serializar).
 */
internal fun remapReadMarks(
    all: Map<String, *>,
    fromPrefix: String,
    toPrefix: String
): Map<String, String> {
    // Mismo prefijo = misma novela (por ejemplo, si ambas se traducen al mismo título): ya
    // comparten las marcas y copiarlas sobre sí mismas no tiene sentido.
    if (fromPrefix == toPrefix) return emptyMap()

    val out = LinkedHashMap<String, String>()
    for ((key, value) in all) {
        if (value !is String || !key.startsWith(fromPrefix)) continue
        val index = key.substring(fromPrefix.length)
        // Solo índices de capítulo. Sin esto, una novela llamada "Foo" se llevaría también las
        // marcas de otra llamada "Foo/2" (sus claves empiezan igual y siguen con "2/<índice>").
        if (index.isEmpty() || !index.all { it in '0'..'9' }) continue
        out[toPrefix + index] = value
    }
    return out
}
